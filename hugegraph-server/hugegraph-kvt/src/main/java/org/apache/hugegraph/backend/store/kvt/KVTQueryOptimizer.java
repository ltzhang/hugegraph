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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.query.Condition;
import org.apache.hugegraph.backend.query.ConditionQuery;
import org.apache.hugegraph.backend.query.IdQuery;
import org.apache.hugegraph.backend.query.Query;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.iterator.FilterIterator;
import org.apache.hugegraph.iterator.LimitIterator;
import org.apache.hugegraph.iterator.MapperIterator;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.HugeKeys;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Query optimizer for KVT backend.
 * Optimizes query execution plans for better performance.
 */
public class KVTQueryOptimizer {
    
    private static final Logger LOG = Log.logger(KVTQueryOptimizer.class);
    
    // Query execution strategies
    public enum Strategy {
        DIRECT_GET,      // Direct key lookup
        RANGE_SCAN,      // Range scan with start/end
        PREFIX_SCAN,     // Prefix-based scan
        INDEX_LOOKUP,    // Use secondary index
        FULL_SCAN,       // Full table scan
        BATCH_GET        // Batch get for multiple IDs
    }
    
    /**
     * Optimize a query and return execution plan
     */
    public static QueryPlan optimize(Query query, HugeType type) {
        if (query instanceof IdQuery) {
            return optimizeIdQuery((IdQuery) query, type);
        } else if (query instanceof ConditionQuery) {
            return optimizeConditionQuery((ConditionQuery) query, type);
        } else {
            // Default to full scan for unknown query types
            return new QueryPlan(Strategy.FULL_SCAN, query, null);
        }
    }
    
    /**
     * Optimize ID query
     */
    private static QueryPlan optimizeIdQuery(IdQuery query, HugeType type) {
        Set<Id> ids = query.ids();
        
        if (ids.size() == 1) {
            // Single ID - use direct get
            return new QueryPlan(Strategy.DIRECT_GET, query, null);
        } else if (ids.size() <= 100) {
            // Multiple IDs but not too many - use batch get
            return new QueryPlan(Strategy.BATCH_GET, query, null);
        } else {
            // Too many IDs - might be better to scan
            // Check if IDs are sequential/close together
            if (areIdsSequential(ids)) {
                // Convert to range scan
                ConditionQuery rangeQuery = convertToRangeQuery(query, ids);
                return new QueryPlan(Strategy.RANGE_SCAN, rangeQuery, 
                                   "Converted " + ids.size() + " IDs to range scan");
            } else {
                // Still use batch get but warn about performance
                return new QueryPlan(Strategy.BATCH_GET, query, 
                                   "Large batch get with " + ids.size() + " IDs");
            }
        }
    }
    
    /**
     * Optimize condition query
     */
    private static QueryPlan optimizeConditionQuery(ConditionQuery query, HugeType type) {
        List<Condition> conditions = new ArrayList<>(query.conditions());
        
        // Sort conditions by selectivity (most selective first)
        sortBySelectivity(conditions);
        
        // Check for index opportunities
        IndexHint indexHint = findBestIndex(conditions, type);
        if (indexHint != null) {
            return new QueryPlan(Strategy.INDEX_LOOKUP, query, 
                               "Using index: " + indexHint.indexName);
        }
        
        // Check for range scan opportunity
        if (hasRangeCondition(conditions) && isRangePartitioned(type)) {
            RangeHint rangeHint = extractRangeHint(conditions);
            if (rangeHint != null) {
                return new QueryPlan(Strategy.RANGE_SCAN, query, 
                                   "Range scan from " + rangeHint.start + 
                                   " to " + rangeHint.end);
            }
        }
        
        // Check for prefix scan opportunity
        PrefixHint prefixHint = extractPrefixHint(conditions);
        if (prefixHint != null && isRangePartitioned(type)) {
            return new QueryPlan(Strategy.PREFIX_SCAN, query, 
                               "Prefix scan with: " + prefixHint.prefix);
        }
        
        // Default to full scan with filters
        if (conditions.size() > 0) {
            return new QueryPlan(Strategy.FULL_SCAN, query, 
                               "Full scan with " + conditions.size() + " filters");
        } else {
            return new QueryPlan(Strategy.FULL_SCAN, query, "Full table scan");
        }
    }
    
    /**
     * Sort conditions by estimated selectivity
     */
    private static void sortBySelectivity(List<Condition> conditions) {
        conditions.sort(new Comparator<Condition>() {
            @Override
            public int compare(Condition c1, Condition c2) {
                return getSelectivityScore(c1) - getSelectivityScore(c2);
            }
        });
    }
    
    /**
     * Estimate selectivity score (lower is more selective)
     */
    private static int getSelectivityScore(Condition condition) {
        switch (condition.relation()) {
            case EQ:
                return 1;  // Equality is most selective
            case IN:
                return 2;  // IN is selective but less than EQ
            case GT:
            case GTE:
            case LT:
            case LTE:
                return 3;  // Range conditions
            case CONTAINS:
            case CONTAINS_KEY:
            case CONTAINS_VALUE:
                return 4;  // Text search conditions
            case NEQ:
                return 5;  // Not equal is less selective
            default:
                return 10; // Unknown conditions
        }
    }
    
    /**
     * Check if IDs are sequential/close together
     */
    private static boolean areIdsSequential(Set<Id> ids) {
        if (ids.size() < 2) {
            return false;
        }
        
        // Convert to sorted list
        List<Id> sortedIds = new ArrayList<>(ids);
        Collections.sort(sortedIds);
        
        // Check if IDs form a continuous range
        // This is simplified - actual implementation would check ID types
        return false; // Conservative default
    }
    
    /**
     * Convert ID query to range query
     */
    private static ConditionQuery convertToRangeQuery(IdQuery query, Set<Id> ids) {
        List<Id> sortedIds = new ArrayList<>(ids);
        Collections.sort(sortedIds);
        
        Id minId = sortedIds.get(0);
        Id maxId = sortedIds.get(sortedIds.size() - 1);
        
        ConditionQuery rangeQuery = new ConditionQuery(query.resultType());
        rangeQuery.gte(HugeKeys.ID, minId);
        rangeQuery.lte(HugeKeys.ID, maxId);
        rangeQuery.limit(query.limit());
        
        return rangeQuery;
    }
    
    /**
     * Find best index for conditions
     */
    private static IndexHint findBestIndex(List<Condition> conditions, HugeType type) {
        // Check for label index
        for (Condition c : conditions) {
            if (c.key() == HugeKeys.LABEL && c.relation() == Condition.RelationType.EQ) {
                return new IndexHint("label_index", c.value());
            }
        }
        
        // Check for property indexes
        for (Condition c : conditions) {
            if (c.key() == HugeKeys.PROPERTIES && c.relation() == Condition.RelationType.EQ) {
                return new IndexHint("property_index", c.value());
            }
        }
        
        return null;
    }
    
    /**
     * Check if query has range conditions
     */
    private static boolean hasRangeCondition(List<Condition> conditions) {
        for (Condition c : conditions) {
            switch (c.relation()) {
                case GT:
                case GTE:
                case LT:
                case LTE:
                    return true;
                default:
                    continue;
            }
        }
        return false;
    }
    
    /**
     * Extract range hint from conditions
     */
    private static RangeHint extractRangeHint(List<Condition> conditions) {
        Id start = null;
        Id end = null;
        
        for (Condition c : conditions) {
            if (c.key() != HugeKeys.ID) {
                continue;
            }
            
            switch (c.relation()) {
                case GT:
                case GTE:
                    start = (Id) c.value();
                    break;
                case LT:
                case LTE:
                    end = (Id) c.value();
                    break;
                default:
                    break;
            }
        }
        
        if (start != null || end != null) {
            return new RangeHint(start, end);
        }
        
        return null;
    }
    
    /**
     * Extract prefix hint from conditions
     */
    private static PrefixHint extractPrefixHint(List<Condition> conditions) {
        // Look for conditions that can be converted to prefix scan
        for (Condition c : conditions) {
            if (c.relation() == Condition.RelationType.TEXT_CONTAINS_PREFIX) {
                return new PrefixHint(c.value().toString());
            }
        }
        return null;
    }
    
    /**
     * Check if type uses range partitioning
     */
    private static boolean isRangePartitioned(HugeType type) {
        switch (type) {
            case EDGE_OUT:
            case EDGE_IN:
            case SECONDARY_INDEX:
            case RANGE_INDEX:
            case UNIQUE_INDEX:
            case SHARD_INDEX:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Query execution plan
     */
    public static class QueryPlan {
        public final Strategy strategy;
        public final Query query;
        public final String hint;
        
        public QueryPlan(Strategy strategy, Query query, String hint) {
            this.strategy = strategy;
            this.query = query;
            this.hint = hint;
        }
        
        @Override
        public String toString() {
            return String.format("QueryPlan{strategy=%s, hint='%s'}", 
                               strategy, hint);
        }
    }
    
    /**
     * Index hint
     */
    private static class IndexHint {
        public final String indexName;
        public final Object value;
        
        public IndexHint(String indexName, Object value) {
            this.indexName = indexName;
            this.value = value;
        }
    }
    
    /**
     * Range hint
     */
    private static class RangeHint {
        public final Id start;
        public final Id end;
        
        public RangeHint(Id start, Id end) {
            this.start = start;
            this.end = end;
        }
    }
    
    /**
     * Prefix hint
     */
    private static class PrefixHint {
        public final String prefix;
        
        public PrefixHint(String prefix) {
            this.prefix = prefix;
        }
    }
    
    /**
     * Create optimized iterator based on query plan
     */
    public static Iterator<BackendEntry> createIterator(QueryPlan plan, 
                                                       Iterator<BackendEntry> base) {
        Iterator<BackendEntry> result = base;
        
        // Apply limit if specified
        if (plan.query.limit() != Query.NO_LIMIT) {
            result = new LimitIterator<>(result, plan.query.limit());
        }
        
        // Log optimization hint
        if (plan.hint != null) {
            LOG.debug("Query optimization: {}", plan.hint);
        }
        
        return result;
    }
}