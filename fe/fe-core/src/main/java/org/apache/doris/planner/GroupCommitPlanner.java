// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.planner;

import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.NativeInsertStmt;
import org.apache.doris.analysis.SelectStmt;
import org.apache.doris.analysis.SlotRef;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.FormatOptions;
import org.apache.doris.common.UserException;
import org.apache.doris.proto.InternalService;
import org.apache.doris.proto.InternalService.PGroupCommitInsertRequest;
import org.apache.doris.proto.InternalService.PGroupCommitInsertResponse;
import org.apache.doris.proto.Types;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.qe.StmtExecutor;
import org.apache.doris.rpc.BackendServiceProxy;
import org.apache.doris.rpc.RpcException;
import org.apache.doris.system.Backend;
import org.apache.doris.task.StreamLoadTask;
import org.apache.doris.thrift.TFileCompressType;
import org.apache.doris.thrift.TFileFormatType;
import org.apache.doris.thrift.TFileType;
import org.apache.doris.thrift.TMergeType;
import org.apache.doris.thrift.TNetworkAddress;
import org.apache.doris.thrift.TPipelineFragmentParams;
import org.apache.doris.thrift.TPipelineFragmentParamsList;
import org.apache.doris.thrift.TScanRangeParams;
import org.apache.doris.thrift.TStreamLoadPutRequest;
import org.apache.doris.thrift.TUniqueId;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

// Used to generate a plan fragment for a group commit
// we only support OlapTable now.
public class GroupCommitPlanner {
    private static final Logger LOG = LogManager.getLogger(GroupCommitPlanner.class);
    public static final String SCHEMA_CHANGE = " is blocked on schema change";

    protected Database db;
    protected OlapTable table;
    protected TUniqueId loadId;
    protected Backend backend;
    private TPipelineFragmentParamsList paramsList;
    private ByteString execPlanFragmentParamsBytes;

    public GroupCommitPlanner(Database db, OlapTable table, List<String> targetColumnNames, TUniqueId queryId,
            String groupCommit)
            throws UserException, TException {
        this.db = db;
        this.table = table;
        if (Env.getCurrentEnv().getGroupCommitManager().isBlock(this.table.getId())) {
            String msg = "insert table " + this.table.getId() + SCHEMA_CHANGE;
            LOG.info(msg);
            throw new DdlException(msg);
        }
        TStreamLoadPutRequest streamLoadPutRequest = new TStreamLoadPutRequest();
        if (targetColumnNames != null) {
            streamLoadPutRequest.setColumns("`" + String.join("`,`", targetColumnNames) + "`");
            if (targetColumnNames.stream().anyMatch(col -> col.equalsIgnoreCase(Column.SEQUENCE_COL))) {
                streamLoadPutRequest.setSequenceCol(Column.SEQUENCE_COL);
            }
        }
        streamLoadPutRequest
                .setDb(db.getFullName())
                .setMaxFilterRatio(ConnectContext.get().getSessionVariable().enableInsertStrict ? 0
                        : ConnectContext.get().getSessionVariable().insertMaxFilterRatio)
                .setTbl(table.getName())
                .setFileType(TFileType.FILE_STREAM).setFormatType(TFileFormatType.FORMAT_CSV_PLAIN)
                .setMergeType(TMergeType.APPEND).setThriftRpcTimeoutMs(5000).setLoadId(queryId)
                .setTrimDoubleQuotes(true).setGroupCommitMode(groupCommit)
                .setStrictMode(ConnectContext.get().getSessionVariable().enableInsertStrict);
        StreamLoadTask streamLoadTask = StreamLoadTask.fromTStreamLoadPutRequest(streamLoadPutRequest);
        StreamLoadPlanner planner = new StreamLoadPlanner(db, table, streamLoadTask);
        // Will using load id as query id in fragment
        // TODO support pipeline
        TPipelineFragmentParams tRequest = planner.plan(streamLoadTask.getId());
        for (Map.Entry<Integer, List<TScanRangeParams>> entry : tRequest.local_params.get(0)
                .per_node_scan_ranges.entrySet()) {
            for (TScanRangeParams scanRangeParams : entry.getValue()) {
                scanRangeParams.scan_range.ext_scan_range.file_scan_range.params.setFormatType(
                        TFileFormatType.FORMAT_PROTO);
                scanRangeParams.scan_range.ext_scan_range.file_scan_range.params.setCompressType(
                        TFileCompressType.PLAIN);
            }
        }
        List<TScanRangeParams> scanRangeParams = tRequest.local_params.get(0).per_node_scan_ranges.values().stream()
                .flatMap(Collection::stream).collect(Collectors.toList());
        Preconditions.checkState(scanRangeParams.size() == 1);
        loadId = queryId;
        // see BackendServiceProxy#execPlanFragmentsAsync
        paramsList = new TPipelineFragmentParamsList();
        paramsList.addToParamsList(tRequest);
        execPlanFragmentParamsBytes = ByteString.copyFrom(new TSerializer().serialize(paramsList));
    }

    public PGroupCommitInsertResponse executeGroupCommitInsert(ConnectContext ctx,
            List<InternalService.PDataRow> rows)
            throws DdlException, RpcException, ExecutionException, InterruptedException {
        selectBackends(ctx);

        PGroupCommitInsertRequest request = PGroupCommitInsertRequest.newBuilder()
                .setExecPlanFragmentRequest(InternalService.PExecPlanFragmentRequest.newBuilder()
                        .setRequest(execPlanFragmentParamsBytes)
                        .setCompact(false).setVersion(InternalService.PFragmentRequestVersion.VERSION_3).build())
                .setLoadId(Types.PUniqueId.newBuilder().setHi(loadId.hi).setLo(loadId.lo)
                .build()).addAllData(rows)
                .build();
        Future<PGroupCommitInsertResponse> future = BackendServiceProxy.getInstance()
                .groupCommitInsert(new TNetworkAddress(backend.getHost(), backend.getBrpcPort()), request);
        return future.get();
    }

    // cloud override
    protected void selectBackends(ConnectContext ctx) throws DdlException {
        backend = ctx.getInsertGroupCommit(this.table.getId());
        if (backend != null && backend.isAlive() && !backend.isDecommissioned()) {
            return;
        }

        List<Long> allBackendIds = Env.getCurrentSystemInfo().getAllBackendIds(true);
        if (allBackendIds.isEmpty()) {
            throw new DdlException("No alive backend");
        }
        Collections.shuffle(allBackendIds);
        for (Long beId : allBackendIds) {
            backend = Env.getCurrentSystemInfo().getBackend(beId);
            if (!backend.isDecommissioned()) {
                ctx.setInsertGroupCommit(this.table.getId(), backend);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("choose new be {}", backend.getId());
                }
                return;
            }
        }

        throw new DdlException("No suitable backend");
    }

    public Backend getBackend() {
        return backend;
    }

    public TPipelineFragmentParamsList getParamsList() {
        return paramsList;
    }

    public List<InternalService.PDataRow> getRows(NativeInsertStmt stmt) throws UserException {
        List<InternalService.PDataRow> rows = new ArrayList<>();
        SelectStmt selectStmt = (SelectStmt) (stmt.getQueryStmt());
        if (selectStmt.getValueList() != null) {
            for (List<Expr> row : selectStmt.getValueList().getRows()) {
                InternalService.PDataRow data = StmtExecutor.getRowStringValue(row, FormatOptions.getDefault());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("add row: [{}]", data.getColList().stream().map(c -> c.getValue())
                            .collect(Collectors.joining(",")));
                }
                rows.add(data);
            }
        } else {
            List<Expr> exprList = new ArrayList<>();
            for (Expr resultExpr : selectStmt.getResultExprs()) {
                if (resultExpr instanceof SlotRef) {
                    exprList.add(((SlotRef) resultExpr).getDesc().getSourceExprs().get(0));
                } else {
                    exprList.add(resultExpr);
                }
            }
            InternalService.PDataRow data = StmtExecutor.getRowStringValue(exprList, FormatOptions.getDefault());
            if (LOG.isDebugEnabled()) {
                LOG.debug("add row: [{}]", data.getColList().stream().map(c -> c.getValue())
                        .collect(Collectors.joining(",")));
            }
            rows.add(data);
        }
        return rows;
    }
}
