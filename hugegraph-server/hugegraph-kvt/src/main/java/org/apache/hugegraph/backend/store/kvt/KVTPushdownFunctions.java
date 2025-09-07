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

package org.apache.hugegraph.backend.store.kvt;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.apache.hugegraph.backend.query.Condition;
import org.apache.hugegraph.backend.query.ConditionQuery;
import org.apache.hugegraph.backend.query.Query;
import org.apache.hugegraph.backend.serializer.BytesBuffer;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.HugeKeys;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * KVT Pushdown Functions for computation pushdown to the storage layer.
 * These functions are used with kvt_process and kvt_range_process to
 * perform filtering, aggregation, and other operations at the storage level.
 */
public class KVTPushdownFunctions {
    
    private static final Logger LOG = Log.logger(KVTPushdownFunctions.class);
    
    /**
     * Property filter pushdown for vertices and edges.
     * This function filters entries based on property conditions.
     */
    public static class PropertyFilterFunction {
        private final List<Condition> conditions;
        private final HugeType type;
        
        public PropertyFilterFunction(List<Condition> conditions, HugeType type) {
            this.conditions = conditions;
            this.type = type;
        }
        
        /**
         * Build parameter bytes for the filter function.
         * Format: [num_conditions][condition1][condition2]...
         * Each condition: [key_len][key][relation][value_len][value]
         */
        public byte[] buildParameter() {
            BytesBuffer buffer = BytesBuffer.allocate(1024);
            
            // Write number of conditions
            buffer.writeVInt(conditions.size());
            
            for (Condition condition : conditions) {
                if (!(condition instanceof Condition.Relation)) {
                    continue; // Skip non-relation conditions
                }
                Condition.Relation relation = (Condition.Relation) condition;
                
                // Write property key
                String key = relation.key().toString();
                buffer.writeVInt(key.length());
                buffer.writeBytes(key.getBytes());
                
                // Write relation type
                buffer.write((byte) relation.relation().ordinal());
                
                // Write value
                Object value = relation.value();
                byte[] valueBytes = serializeValue(value);
                buffer.writeVInt(valueBytes.length);
                buffer.writeBytes(valueBytes);
            }
            
            return buffer.bytes();
        }
        
        private byte[] serializeValue(Object value) {
            BytesBuffer buffer = BytesBuffer.allocate(256);
            if (value instanceof String) {
                buffer.write((byte) 0); // String type
                buffer.writeString((String) value);
            } else if (value instanceof Number) {
                buffer.write((byte) 1); // Number type
                buffer.writeDouble(((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                buffer.write((byte) 2); // Boolean type
                buffer.writeBoolean((Boolean) value);
            } else if (value instanceof Id) {
                buffer.write((byte) 3); // Id type
                buffer.writeId((Id) value);
            } else {
                // Default to string representation
                buffer.write((byte) 0);
                buffer.writeString(value.toString());
            }
            return buffer.bytes();
        }
    }
    
    /**
     * Count aggregation pushdown.
     * This function counts matching entries during a range scan.
     */
    public static class CountAggregationFunction {
        private final List<Condition> filterConditions;
        private final boolean deduplicate;
        
        public CountAggregationFunction(List<Condition> filterConditions, 
                                       boolean deduplicate) {
            this.filterConditions = filterConditions;
            this.deduplicate = deduplicate;
        }
        
        /**
         * Build parameter bytes for count aggregation.
         * Format: [deduplicate_flag][num_conditions][conditions...]
         */
        public byte[] buildParameter() {
            BytesBuffer buffer = BytesBuffer.allocate(1024);
            
            // Write deduplicate flag
            buffer.writeBoolean(deduplicate);
            
            // Write filter conditions if any
            if (filterConditions != null && !filterConditions.isEmpty()) {
                PropertyFilterFunction filter = new PropertyFilterFunction(
                    filterConditions, HugeType.VERTEX);
                byte[] filterParams = filter.buildParameter();
                buffer.writeBytes(filterParams);
            } else {
                buffer.writeVInt(0); // No conditions
            }
            
            return buffer.bytes();
        }
    }
    
    /**
     * Sum aggregation pushdown.
     * This function sums a specific property value during a range scan.
     */
    public static class SumAggregationFunction {
        private final String propertyKey;
        private final List<Condition> filterConditions;
        
        public SumAggregationFunction(String propertyKey, 
                                     List<Condition> filterConditions) {
            this.propertyKey = propertyKey;
            this.filterConditions = filterConditions;
        }
        
        /**
         * Build parameter bytes for sum aggregation.
         * Format: [property_key_len][property_key][num_conditions][conditions...]
         */
        public byte[] buildParameter() {
            BytesBuffer buffer = BytesBuffer.allocate(1024);
            
            // Write property key to sum
            buffer.writeVInt(propertyKey.length());
            buffer.writeBytes(propertyKey.getBytes());
            
            // Write filter conditions if any
            if (filterConditions != null && !filterConditions.isEmpty()) {
                PropertyFilterFunction filter = new PropertyFilterFunction(
                    filterConditions, HugeType.VERTEX);
                byte[] filterParams = filter.buildParameter();
                buffer.writeBytes(filterParams);
            } else {
                buffer.writeVInt(0); // No conditions
            }
            
            return buffer.bytes();
        }
    }
    
    /**
     * Min/Max aggregation pushdown.
     * This function finds the minimum or maximum value of a property.
     */
    public static class MinMaxAggregationFunction {
        private final String propertyKey;
        private final boolean findMax; // true for max, false for min
        private final List<Condition> filterConditions;
        
        public MinMaxAggregationFunction(String propertyKey, 
                                        boolean findMax,
                                        List<Condition> filterConditions) {
            this.propertyKey = propertyKey;
            this.findMax = findMax;
            this.filterConditions = filterConditions;
        }
        
        /**
         * Build parameter bytes for min/max aggregation.
         * Format: [find_max_flag][property_key_len][property_key][conditions...]
         */
        public byte[] buildParameter() {
            BytesBuffer buffer = BytesBuffer.allocate(1024);
            
            // Write find max flag
            buffer.writeBoolean(findMax);
            
            // Write property key
            buffer.writeVInt(propertyKey.length());
            buffer.writeBytes(propertyKey.getBytes());
            
            // Write filter conditions if any
            if (filterConditions != null && !filterConditions.isEmpty()) {
                PropertyFilterFunction filter = new PropertyFilterFunction(
                    filterConditions, HugeType.VERTEX);
                byte[] filterParams = filter.buildParameter();
                buffer.writeBytes(filterParams);
            } else {
                buffer.writeVInt(0); // No conditions
            }
            
            return buffer.bytes();
        }
    }
    
    /**
     * Group-by aggregation pushdown.
     * This function groups entries by a property and performs aggregation.
     */
    public static class GroupByAggregationFunction {
        private final String groupByKey;
        private final String aggregateKey;
        private final AggregationType aggregationType;
        private final List<Condition> filterConditions;
        
        public enum AggregationType {
            COUNT,
            SUM,
            AVG,
            MIN,
            MAX
        }
        
        public GroupByAggregationFunction(String groupByKey,
                                         String aggregateKey,
                                         AggregationType aggregationType,
                                         List<Condition> filterConditions) {
            this.groupByKey = groupByKey;
            this.aggregateKey = aggregateKey;
            this.aggregationType = aggregationType;
            this.filterConditions = filterConditions;
        }
        
        /**
         * Build parameter bytes for group-by aggregation.
         * Format: [group_key_len][group_key][agg_type][agg_key_len][agg_key][conditions...]
         */
        public byte[] buildParameter() {
            BytesBuffer buffer = BytesBuffer.allocate(1024);
            
            // Write group-by key
            buffer.writeVInt(groupByKey.length());
            buffer.writeBytes(groupByKey.getBytes());
            
            // Write aggregation type
            buffer.write((byte) aggregationType.ordinal());
            
            // Write aggregate key (may be empty for COUNT)
            if (aggregateKey != null) {
                buffer.writeVInt(aggregateKey.length());
                buffer.writeBytes(aggregateKey.getBytes());
            } else {
                buffer.writeVInt(0);
            }
            
            // Write filter conditions if any
            if (filterConditions != null && !filterConditions.isEmpty()) {
                PropertyFilterFunction filter = new PropertyFilterFunction(
                    filterConditions, HugeType.VERTEX);
                byte[] filterParams = filter.buildParameter();
                buffer.writeBytes(filterParams);
            } else {
                buffer.writeVInt(0); // No conditions
            }
            
            return buffer.bytes();
        }
    }
    
    /**
     * Top-K pushdown.
     * This function returns the top K entries based on a property value.
     */
    public static class TopKFunction {
        private final String sortKey;
        private final int k;
        private final boolean ascending;
        private final List<Condition> filterConditions;
        
        public TopKFunction(String sortKey, int k, boolean ascending,
                           List<Condition> filterConditions) {
            this.sortKey = sortKey;
            this.k = k;
            this.ascending = ascending;
            this.filterConditions = filterConditions;
        }
        
        /**
         * Build parameter bytes for top-k function.
         * Format: [sort_key_len][sort_key][k][ascending_flag][conditions...]
         */
        public byte[] buildParameter() {
            BytesBuffer buffer = BytesBuffer.allocate(1024);
            
            // Write sort key
            buffer.writeVInt(sortKey.length());
            buffer.writeBytes(sortKey.getBytes());
            
            // Write k value
            buffer.writeVInt(k);
            
            // Write ascending flag
            buffer.writeBoolean(ascending);
            
            // Write filter conditions if any
            if (filterConditions != null && !filterConditions.isEmpty()) {
                PropertyFilterFunction filter = new PropertyFilterFunction(
                    filterConditions, HugeType.VERTEX);
                byte[] filterParams = filter.buildParameter();
                buffer.writeBytes(filterParams);
            } else {
                buffer.writeVInt(0); // No conditions
            }
            
            return buffer.bytes();
        }
    }
    
    /**
     * Distinct/Deduplicate pushdown.
     * This function removes duplicate entries based on specified keys.
     */
    public static class DistinctFunction {
        private final List<String> distinctKeys;
        private final List<Condition> filterConditions;
        
        public DistinctFunction(List<String> distinctKeys,
                               List<Condition> filterConditions) {
            this.distinctKeys = distinctKeys;
            this.filterConditions = filterConditions;
        }
        
        /**
         * Build parameter bytes for distinct function.
         * Format: [num_keys][key1_len][key1][key2_len][key2]...[conditions...]
         */
        public byte[] buildParameter() {
            BytesBuffer buffer = BytesBuffer.allocate(1024);
            
            // Write number of distinct keys
            buffer.writeVInt(distinctKeys.size());
            
            // Write each distinct key
            for (String key : distinctKeys) {
                buffer.writeVInt(key.length());
                buffer.writeBytes(key.getBytes());
            }
            
            // Write filter conditions if any
            if (filterConditions != null && !filterConditions.isEmpty()) {
                PropertyFilterFunction filter = new PropertyFilterFunction(
                    filterConditions, HugeType.VERTEX);
                byte[] filterParams = filter.buildParameter();
                buffer.writeBytes(filterParams);
            } else {
                buffer.writeVInt(0); // No conditions
            }
            
            return buffer.bytes();
        }
    }
    
    /**
     * Sampling pushdown.
     * This function samples entries randomly or systematically.
     */
    public static class SamplingFunction {
        private final double sampleRate;
        private final long seed;
        private final List<Condition> filterConditions;
        
        public SamplingFunction(double sampleRate, long seed,
                               List<Condition> filterConditions) {
            E.checkArgument(sampleRate > 0 && sampleRate <= 1.0,
                           "Sample rate must be between 0 and 1");
            this.sampleRate = sampleRate;
            this.seed = seed;
            this.filterConditions = filterConditions;
        }
        
        /**
         * Build parameter bytes for sampling function.
         * Format: [sample_rate][seed][conditions...]
         */
        public byte[] buildParameter() {
            BytesBuffer buffer = BytesBuffer.allocate(1024);
            
            // Write sample rate
            buffer.writeDouble(sampleRate);
            
            // Write random seed
            buffer.writeLong(seed);
            
            // Write filter conditions if any
            if (filterConditions != null && !filterConditions.isEmpty()) {
                PropertyFilterFunction filter = new PropertyFilterFunction(
                    filterConditions, HugeType.VERTEX);
                byte[] filterParams = filter.buildParameter();
                buffer.writeBytes(filterParams);
            } else {
                buffer.writeVInt(0); // No conditions
            }
            
            return buffer.bytes();
        }
    }
}