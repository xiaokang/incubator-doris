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

suite("test_null_partition") {
    sql "set enable_fallback_to_original_planner=false"
    sql "set allow_partition_column_nullable = true;"

    sql " drop table if exists test_null "
    test {
        sql """
            CREATE TABLE `test_null` (
            `k0` BIGINT NOT NULL,
            `k1` BIGINT NOT NULL
            )
            partition by list (k0, k1) (
            PARTITION `pX` values in ((NULL, 1))
            )
            PROPERTIES (
            "replication_allocation" = "tag.location.default: 1"
            );
        """
        exception "Can't have null partition is for NOT NULL partition column in partition expr's index 0"
    }
    
    test {
        sql """
            CREATE TABLE `test_null` (
            `k0` BIGINT NOT NULL,
            `k1` BIGINT NOT NULL
            )
            partition by list (k0, k1) (
            PARTITION `pX` values in ((1, 2), (1, NULL))
            )
            PROPERTIES (
            "replication_allocation" = "tag.location.default: 1"
            );
        """
        exception "Can't have null partition is for NOT NULL partition column in partition expr's index 1"
    }

    sql " drop table if exists OK "
    sql """
        CREATE TABLE `OK` (
        `k0` BIGINT NULL,
        `k1` BIGINT NOT NULL
        )
        partition by list (k0, k1) (
        PARTITION `pX` values in ((NULL, 1), (NULL, 2), (NULL, 3))
        )
        PROPERTIES (
        "replication_allocation" = "tag.location.default: 1"
        );
    """

    test {
        sql """
            CREATE TABLE `test_null` (
            `k0` BIGINT NULL,
            `k1` BIGINT NOT NULL
            )
            partition by list (k0, k1) (
            PARTITION `pX` values in ((NULL, 1), (NULL, 2), (NULL, 3), (4, NULL))
            )
            PROPERTIES (
            "replication_allocation" = "tag.location.default: 1"
            );
        """
        exception "Can't have null partition is for NOT NULL partition column in partition expr's index 1"
    }
}