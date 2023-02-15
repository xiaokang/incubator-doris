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

#include "data_type_map.h"

#include "gen_cpp/data.pb.h"
#include "vec/columns/column_array.h"
#include "vec/columns/column_map.h"
#include "vec/common/assert_cast.h"
#include "vec/data_types/data_type_array.h"
#include "vec/data_types/data_type_nullable.h"

namespace doris::vectorized {

DataTypeMap::DataTypeMap(const DataTypePtr& keys_, const DataTypePtr& values_) {
    if (!keys_->is_nullable()) {
        key_type = make_nullable(keys_);
    } else {
        key_type = keys_;
    }
    if (!values_->is_nullable()) {
        value_type = make_nullable(values_);
    } else {
        value_type = values_;
    }

    keys = std::make_shared<DataTypeArray>(key_type);
    values = std::make_shared<DataTypeArray>(value_type);
}

std::string DataTypeMap::to_string(const IColumn& column, size_t row_num) const {
    const ColumnMap& map_column = assert_cast<const ColumnMap&>(column);
    const ColumnArray::Offsets64& offsets = map_column.get_offsets();

    size_t offset = offsets[row_num - 1];
    size_t next_offset = offsets[row_num];

    auto& keys_arr = assert_cast<const ColumnArray&>(map_column.get_keys());
    auto& values_arr = assert_cast<const ColumnArray&>(map_column.get_values());

    const IColumn& nested_keys_column = keys_arr.get_data();
    const IColumn& nested_values_column = values_arr.get_data();

    std::stringstream ss;
    ss << "{";
    for (size_t i = offset; i < next_offset; ++i) {
        if (i != offset) {
            ss << ", ";
        }
        if (nested_keys_column.is_null_at(i)) {
            ss << "null";
        } else if (WhichDataType(remove_nullable(key_type)).is_string_or_fixed_string()) {
            ss << "'" << key_type->to_string(nested_keys_column, i) << "'";
        } else {
            ss << key_type->to_string(nested_keys_column, i);
        }
        ss << ":";
        if (nested_values_column.is_null_at(i)) {
            ss << "null";
        } else if (WhichDataType(remove_nullable(value_type)).is_string_or_fixed_string()) {
            ss << "'" << value_type->to_string(nested_values_column, i) << "'";
        } else {
            ss << value_type->to_string(nested_values_column, i);
        }
    }
    ss << "}";
    return ss.str();
}

void DataTypeMap::to_string(const class doris::vectorized::IColumn& column, size_t row_num,
                            class doris::vectorized::BufferWritable& ostr) const {
    std::string ss = to_string(column, row_num);
    ostr.write(ss.c_str(), strlen(ss.c_str()));
}

bool next_slot_from_string(ReadBuffer& rb, StringRef& output) {
    StringRef element(rb.position(), 0);
    bool has_quota = false;
    if (rb.eof()) {
        return false;
    }

    // ltrim
    while (!rb.eof() && isspace(*rb.position())) {
        ++rb.position();
        element.data = rb.position();
    }

    // parse string
    if (*rb.position() == '"' || *rb.position() == '\'') {
        const char str_sep = *rb.position();
        size_t str_len = 1;
        // search until next '"' or '\''
        while (str_len < rb.count() && *(rb.position() + str_len) != str_sep) {
            ++str_len;
        }
        // invalid string
        if (str_len >= rb.count()) {
            rb.position() = rb.end();
            return false;
        }
        has_quota = true;
        rb.position() += str_len + 1;
        element.size += str_len + 1;
    }

    // parse array element until map separator ':' or ',' or end '}'
    while (!rb.eof() && (*rb.position() != ':') && (*rb.position() != ',') &&
           (rb.count() != 1 || *rb.position() != '}')) {
        if (has_quota && !isspace(*rb.position())) {
            return false;
        }
        ++rb.position();
        ++element.size;
    }
    // invalid array element
    if (rb.eof()) {
        return false;
    }
    // adjust read buffer position to first char of next array element
    ++rb.position();

    // rtrim
    while (element.size > 0 && isspace(element.data[element.size - 1])) {
        --element.size;
    }

    // trim '"' and '\'' for string
    if (element.size >= 2 && (element.data[0] == '"' || element.data[0] == '\'') &&
        element.data[0] == element.data[element.size - 1]) {
        ++element.data;
        element.size -= 2;
    }
    output = element;
    return true;
}

Status DataTypeMap::from_string(ReadBuffer& rb, IColumn* column) const {
    DCHECK(!rb.eof());
    auto* map_column = assert_cast<ColumnMap*>(column);

    if (*rb.position() != '{') {
        return Status::InvalidArgument("map does not start with '{' character, found '{}'",
                                       *rb.position());
    }
    if (*(rb.end() - 1) != '}') {
        return Status::InvalidArgument("map does not end with '}' character, found '{}'",
                                       *(rb.end() - 1));
    }

    if (rb.count() == 2) {
        // empty map {} , need to make empty array to add offset
        map_column->insert_default();
    } else {
        // {"aaa": 1, "bbb": 20}, need to handle key slot and value slot to make key column arr and value arr
        // skip "{"
        ++rb.position();
        auto& keys_arr = reinterpret_cast<ColumnArray&>(map_column->get_keys());
        ColumnArray::Offsets64& key_off = keys_arr.get_offsets();
        auto& values_arr = reinterpret_cast<ColumnArray&>(map_column->get_values());
        ColumnArray::Offsets64& val_off = values_arr.get_offsets();

        IColumn& nested_key_column = keys_arr.get_data();
        DCHECK(nested_key_column.is_nullable());
        IColumn& nested_val_column = values_arr.get_data();
        DCHECK(nested_val_column.is_nullable());

        size_t element_num = 0;
        while (!rb.eof()) {
            StringRef key_element(rb.position(), rb.count());
            if (!next_slot_from_string(rb, key_element)) {
                return Status::InvalidArgument("Cannot read map key from text '{}'",
                                               key_element.to_string());
            }
            StringRef value_element(rb.position(), rb.count());
            if (!next_slot_from_string(rb, value_element)) {
                return Status::InvalidArgument("Cannot read map value from text '{}'",
                                               value_element.to_string());
            }
            ReadBuffer krb(const_cast<char*>(key_element.data), key_element.size);
            ReadBuffer vrb(const_cast<char*>(value_element.data), value_element.size);
            if (auto st = key_type->from_string(krb, &nested_key_column); !st.ok()) {
                map_column->pop_back(element_num);
                return st;
            }
            if (auto st = value_type->from_string(vrb, &nested_val_column); !st.ok()) {
                map_column->pop_back(element_num);
                return st;
            }

            ++element_num;
        }
        key_off.push_back(key_off.back() + element_num);
        val_off.push_back(val_off.back() + element_num);
    }
    return Status::OK();
}

MutableColumnPtr DataTypeMap::create_column() const {
    return ColumnMap::create(keys->create_column(), values->create_column());
}

void DataTypeMap::to_pb_column_meta(PColumnMeta* col_meta) const {
    IDataType::to_pb_column_meta(col_meta);
    auto key_children = col_meta->add_children();
    auto value_children = col_meta->add_children();
    keys->to_pb_column_meta(key_children);
    values->to_pb_column_meta(value_children);
}

bool DataTypeMap::equals(const IDataType& rhs) const {
    if (typeid(rhs) != typeid(*this)) {
        return false;
    }

    const DataTypeMap& rhs_map = static_cast<const DataTypeMap&>(rhs);

    if (!keys->equals(*rhs_map.keys)) {
        return false;
    }

    if (!values->equals(*rhs_map.values)) {
        return false;
    }

    return true;
}

int64_t DataTypeMap::get_uncompressed_serialized_bytes(const IColumn& column,
                                                       int data_version) const {
    auto ptr = column.convert_to_full_column_if_const();
    const auto& data_column = assert_cast<const ColumnMap&>(*ptr.get());
    return get_keys()->get_uncompressed_serialized_bytes(data_column.get_keys(), data_version) +
           get_values()->get_uncompressed_serialized_bytes(data_column.get_values(), data_version);
}

// serialize to binary
char* DataTypeMap::serialize(const IColumn& column, char* buf, int data_version) const {
    auto ptr = column.convert_to_full_column_if_const();
    const auto& map_column = assert_cast<const ColumnMap&>(*ptr.get());

    buf = get_keys()->serialize(map_column.get_keys(), buf, data_version);
    return get_values()->serialize(map_column.get_values(), buf, data_version);
}

const char* DataTypeMap::deserialize(const char* buf, IColumn* column, int data_version) const {
    const auto* map_column = assert_cast<const ColumnMap*>(column);
    buf = get_keys()->deserialize(buf, map_column->get_keys_ptr()->assume_mutable(), data_version);
    return get_values()->deserialize(buf, map_column->get_values_ptr()->assume_mutable(),
                                     data_version);
}

} // namespace doris::vectorized