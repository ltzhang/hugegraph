/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <string>
#include <vector>
#include <map>
#include <unordered_set>
#include <algorithm>
#include <numeric>
#include <random>
#include <sstream>
#include "../../../kvt/kvt_inc.h"

// Helper function to decode variable-length integer (matching HugeGraph's format)
size_t decodeVInt(const unsigned char* data, size_t& bytes_read) {
    unsigned char leading = data[0];
    size_t value = leading & 0x7F;
    bytes_read = 1;
    
    if ((leading & 0x80) == 0) {
        return value;
    }
    
    for (int i = 1; i < 5; i++) {
        unsigned char b = data[bytes_read];
        bytes_read++;
        
        if ((b & 0x80) == 0) {
            value = (value << 7) | b;
            return value;
        } else {
            value = (value << 7) | (b & 0x7F);
        }
    }
    
    throw std::runtime_error("Invalid vInt encoding");
}

// Helper function to encode variable-length integer
void encodeVInt(size_t value, std::string& output) {
    if (value > 0x0fffffff) {
        output.push_back(0x80 | ((value >> 28) & 0x7f));
    }
    if (value > 0x1fffff) {
        output.push_back(0x80 | ((value >> 21) & 0x7f));
    }
    if (value > 0x3fff) {
        output.push_back(0x80 | ((value >> 14) & 0x7f));
    }
    if (value > 0x7f) {
        output.push_back(0x80 | ((value >> 7) & 0x7f));
    }
    output.push_back(value & 0x7f);
}

/**
 * Property filter function - filters entries based on property conditions
 */
bool hg_property_filter(KVTProcessInput& input, KVTProcessOutput& output) {
    if (!input.value || !input.parameter) {
        output.return_value = "Missing required input";
        return false;
    }
    
    const std::string& value = *input.value;
    const std::string& parameter = *input.parameter;
    
    try {
        // Parse parameter to get filter conditions
        const unsigned char* param_data = reinterpret_cast<const unsigned char*>(parameter.data());
        size_t param_pos = 0;
        size_t bytes_read = 0;
        
        // Read number of conditions
        size_t num_conditions = decodeVInt(param_data + param_pos, bytes_read);
        param_pos += bytes_read;
        
        // For simplicity, if all conditions match, keep the entry
        bool all_match = true;
        
        for (size_t i = 0; i < num_conditions && all_match; i++) {
            // Read property key
            size_t key_len = decodeVInt(param_data + param_pos, bytes_read);
            param_pos += bytes_read;
            std::string prop_key(parameter.data() + param_pos, key_len);
            param_pos += key_len;
            
            // Read relation type
            uint8_t relation = param_data[param_pos++];
            
            // Read value
            size_t val_len = decodeVInt(param_data + param_pos, bytes_read);
            param_pos += bytes_read;
            std::string filter_value(parameter.data() + param_pos, val_len);
            param_pos += val_len;
            
            // TODO: Implement actual property matching logic
            // For now, we'll just check if the value contains the filter string
            if (value.find(filter_value) == std::string::npos) {
                all_match = false;
            }
        }
        
        if (all_match) {
            // Keep the entry unchanged
            output.return_value = value;
            output.delete_key = false;
        } else {
            // Filter out the entry
            output.delete_key = false; // Don't actually delete, just don't return
            // Return empty to indicate filtered out
        }
        
        return true;
        
    } catch (const std::exception& e) {
        output.return_value = std::string("Filter error: ") + e.what();
        return false;
    }
}

/**
 * Count aggregation function - counts matching entries
 */
bool hg_count_aggregation(KVTProcessInput& input, KVTProcessOutput& output) {
    static uint64_t count = 0;
    static std::unordered_set<std::string> seen_keys;
    
    if (!input.parameter) {
        output.return_value = "Missing parameter";
        return false;
    }
    
    const std::string& parameter = *input.parameter;
    
    try {
        // Parse parameter
        const unsigned char* param_data = reinterpret_cast<const unsigned char*>(parameter.data());
        size_t param_pos = 0;
        
        // Read deduplicate flag
        bool deduplicate = param_data[param_pos++] != 0;
        
        // Initialize count on first entry
        if (input.range_first) {
            count = 0;
            seen_keys.clear();
        }
        
        // Count this entry
        if (deduplicate) {
            if (input.key && seen_keys.insert(*input.key).second) {
                count++;
            }
        } else {
            count++;
        }
        
        // Return count on last entry
        if (input.range_last) {
            output.return_value = std::to_string(count);
        }
        
        return true;
        
    } catch (const std::exception& e) {
        output.return_value = std::string("Count error: ") + e.what();
        return false;
    }
}

/**
 * Sum aggregation function - sums a property value across entries
 */
bool hg_sum_aggregation(KVTProcessInput& input, KVTProcessOutput& output) {
    static double sum = 0.0;
    
    if (!input.value || !input.parameter) {
        output.return_value = "Missing required input";
        return false;
    }
    
    try {
        // Initialize sum on first entry
        if (input.range_first) {
            sum = 0.0;
        }
        
        // Parse parameter to get property key
        const std::string& parameter = *input.parameter;
        const unsigned char* param_data = reinterpret_cast<const unsigned char*>(parameter.data());
        size_t param_pos = 0;
        size_t bytes_read = 0;
        
        // Read property key
        size_t key_len = decodeVInt(param_data + param_pos, bytes_read);
        param_pos += bytes_read;
        std::string prop_key(parameter.data() + param_pos, key_len);
        
        // TODO: Extract actual property value from the entry
        // For now, use a dummy value
        double value = 1.0;
        sum += value;
        
        // Return sum on last entry
        if (input.range_last) {
            output.return_value = std::to_string(sum);
        }
        
        return true;
        
    } catch (const std::exception& e) {
        output.return_value = std::string("Sum error: ") + e.what();
        return false;
    }
}

/**
 * Min/Max aggregation function
 */
bool hg_minmax_aggregation(KVTProcessInput& input, KVTProcessOutput& output) {
    static double extreme_value;
    static bool first_value;
    
    if (!input.value || !input.parameter) {
        output.return_value = "Missing required input";
        return false;
    }
    
    try {
        // Parse parameter
        const std::string& parameter = *input.parameter;
        const unsigned char* param_data = reinterpret_cast<const unsigned char*>(parameter.data());
        size_t param_pos = 0;
        
        // Read find_max flag
        bool find_max = param_data[param_pos++] != 0;
        
        // Initialize on first entry
        if (input.range_first) {
            first_value = true;
        }
        
        // TODO: Extract actual property value from the entry
        // For now, use a dummy value
        double value = 1.0;
        
        if (first_value) {
            extreme_value = value;
            first_value = false;
        } else {
            if (find_max) {
                extreme_value = std::max(extreme_value, value);
            } else {
                extreme_value = std::min(extreme_value, value);
            }
        }
        
        // Return result on last entry
        if (input.range_last) {
            output.return_value = std::to_string(extreme_value);
        }
        
        return true;
        
    } catch (const std::exception& e) {
        output.return_value = std::string("MinMax error: ") + e.what();
        return false;
    }
}

/**
 * Group-by aggregation function
 */
bool hg_groupby_aggregation(KVTProcessInput& input, KVTProcessOutput& output) {
    static std::map<std::string, double> groups;
    static std::map<std::string, uint64_t> counts; // For AVG calculation
    
    if (!input.value || !input.parameter) {
        output.return_value = "Missing required input";
        return false;
    }
    
    try {
        // Initialize on first entry
        if (input.range_first) {
            groups.clear();
            counts.clear();
        }
        
        // Parse parameter
        const std::string& parameter = *input.parameter;
        const unsigned char* param_data = reinterpret_cast<const unsigned char*>(parameter.data());
        size_t param_pos = 0;
        size_t bytes_read = 0;
        
        // Read group-by key
        size_t group_key_len = decodeVInt(param_data + param_pos, bytes_read);
        param_pos += bytes_read;
        std::string group_key(parameter.data() + param_pos, group_key_len);
        param_pos += group_key_len;
        
        // Read aggregation type
        uint8_t agg_type = param_data[param_pos++];
        
        // TODO: Extract actual group value and aggregate value from entry
        // For now, use dummy values
        std::string group_value = "group1";
        double agg_value = 1.0;
        
        // Perform aggregation based on type
        switch (agg_type) {
            case 0: // COUNT
                groups[group_value] += 1.0;
                break;
            case 1: // SUM
                groups[group_value] += agg_value;
                break;
            case 2: // AVG
                groups[group_value] += agg_value;
                counts[group_value]++;
                break;
            case 3: // MIN
                if (groups.find(group_value) == groups.end()) {
                    groups[group_value] = agg_value;
                } else {
                    groups[group_value] = std::min(groups[group_value], agg_value);
                }
                break;
            case 4: // MAX
                if (groups.find(group_value) == groups.end()) {
                    groups[group_value] = agg_value;
                } else {
                    groups[group_value] = std::max(groups[group_value], agg_value);
                }
                break;
        }
        
        // Return results on last entry
        if (input.range_last) {
            std::stringstream result;
            result << "{";
            bool first = true;
            for (const auto& [key, value] : groups) {
                if (!first) result << ",";
                first = false;
                
                double final_value = value;
                if (agg_type == 2 && counts[key] > 0) { // AVG
                    final_value = value / counts[key];
                }
                
                result << "\"" << key << "\":" << final_value;
            }
            result << "}";
            output.return_value = result.str();
        }
        
        return true;
        
    } catch (const std::exception& e) {
        output.return_value = std::string("GroupBy error: ") + e.what();
        return false;
    }
}

/**
 * Top-K function - returns top K entries based on a property
 */
bool hg_topk_function(KVTProcessInput& input, KVTProcessOutput& output) {
    static std::vector<std::pair<double, std::string>> top_entries;
    
    if (!input.value || !input.parameter) {
        output.return_value = "Missing required input";
        return false;
    }
    
    try {
        // Parse parameter
        const std::string& parameter = *input.parameter;
        const unsigned char* param_data = reinterpret_cast<const unsigned char*>(parameter.data());
        size_t param_pos = 0;
        size_t bytes_read = 0;
        
        // Read sort key
        size_t sort_key_len = decodeVInt(param_data + param_pos, bytes_read);
        param_pos += bytes_read;
        std::string sort_key(parameter.data() + param_pos, sort_key_len);
        param_pos += sort_key_len;
        
        // Read K value
        size_t k = decodeVInt(param_data + param_pos, bytes_read);
        param_pos += bytes_read;
        
        // Read ascending flag
        bool ascending = param_data[param_pos++] != 0;
        
        // Initialize on first entry
        if (input.range_first) {
            top_entries.clear();
            top_entries.reserve(k);
        }
        
        // TODO: Extract actual sort value from entry
        // For now, use a dummy value
        double sort_value = 1.0;
        
        // Add to top entries
        if (top_entries.size() < k) {
            top_entries.emplace_back(sort_value, *input.value);
        } else {
            // Check if this entry should replace the worst entry
            auto compare = ascending ? 
                [](const auto& a, const auto& b) { return a.first < b.first; } :
                [](const auto& a, const auto& b) { return a.first > b.first; };
            
            std::sort(top_entries.begin(), top_entries.end(), compare);
            
            if ((ascending && sort_value < top_entries.back().first) ||
                (!ascending && sort_value > top_entries.back().first)) {
                top_entries.back() = {sort_value, *input.value};
            }
        }
        
        // Return top K on last entry
        if (input.range_last) {
            auto compare = ascending ? 
                [](const auto& a, const auto& b) { return a.first < b.first; } :
                [](const auto& a, const auto& b) { return a.first > b.first; };
            
            std::sort(top_entries.begin(), top_entries.end(), compare);
            
            std::stringstream result;
            result << "[";
            for (size_t i = 0; i < top_entries.size(); i++) {
                if (i > 0) result << ",";
                result << "{\"value\":" << top_entries[i].first << ",\"data\":\"" 
                      << top_entries[i].second << "\"}";
            }
            result << "]";
            output.return_value = result.str();
        }
        
        return true;
        
    } catch (const std::exception& e) {
        output.return_value = std::string("TopK error: ") + e.what();
        return false;
    }
}

/**
 * Sampling function - randomly samples entries
 */
bool hg_sampling_function(KVTProcessInput& input, KVTProcessOutput& output) {
    static std::mt19937_64 rng;
    static std::uniform_real_distribution<double> dist(0.0, 1.0);
    static bool initialized = false;
    
    if (!input.value || !input.parameter) {
        output.return_value = "Missing required input";
        return false;
    }
    
    try {
        // Parse parameter
        const std::string& parameter = *input.parameter;
        const unsigned char* param_data = reinterpret_cast<const unsigned char*>(parameter.data());
        size_t param_pos = 0;
        
        // Read sample rate
        double sample_rate;
        memcpy(&sample_rate, param_data + param_pos, sizeof(double));
        param_pos += sizeof(double);
        
        // Read seed
        uint64_t seed;
        memcpy(&seed, param_data + param_pos, sizeof(uint64_t));
        param_pos += sizeof(uint64_t);
        
        // Initialize RNG on first entry
        if (input.range_first || !initialized) {
            rng.seed(seed);
            initialized = true;
        }
        
        // Decide whether to sample this entry
        if (dist(rng) < sample_rate) {
            output.return_value = *input.value;
        }
        // Else return nothing (filtered out)
        
        return true;
        
    } catch (const std::exception& e) {
        output.return_value = std::string("Sampling error: ") + e.what();
        return false;
    }
}