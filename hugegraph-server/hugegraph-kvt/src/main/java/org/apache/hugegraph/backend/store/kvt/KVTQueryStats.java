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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.apache.hugegraph.backend.query.Query;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Query statistics collector for KVT backend.
 * Tracks query performance metrics and patterns.
 */
public class KVTQueryStats {
    
    private static final Logger LOG = Log.logger(KVTQueryStats.class);
    
    private static final KVTQueryStats INSTANCE = new KVTQueryStats();
    
    // Global statistics
    private final LongAdder totalQueries;
    private final LongAdder totalTime;
    private final LongAdder cacheHits;
    private final LongAdder cacheMisses;
    
    // Per-table statistics
    private final Map<HugeType, TableStats> tableStats;
    
    // Per-query type statistics
    private final Map<String, QueryTypeStats> queryTypeStats;
    
    // Slow query tracking
    private final long slowQueryThreshold;
    private final Map<String, SlowQuery> slowQueries;
    
    private KVTQueryStats() {
        this.totalQueries = new LongAdder();
        this.totalTime = new LongAdder();
        this.cacheHits = new LongAdder();
        this.cacheMisses = new LongAdder();
        
        this.tableStats = new ConcurrentHashMap<>();
        this.queryTypeStats = new ConcurrentHashMap<>();
        this.slowQueries = new ConcurrentHashMap<>();
        
        this.slowQueryThreshold = 1000; // 1 second
    }
    
    public static KVTQueryStats getInstance() {
        return INSTANCE;
    }
    
    /**
     * Record query execution
     */
    public void recordQuery(HugeType table, Query query, long startTime, 
                          long endTime, long resultCount, boolean fromCache) {
        long duration = endTime - startTime;
        
        // Update global stats
        this.totalQueries.increment();
        this.totalTime.add(duration);
        
        if (fromCache) {
            this.cacheHits.increment();
        } else {
            this.cacheMisses.increment();
        }
        
        // Update table stats
        TableStats tStats = this.tableStats.computeIfAbsent(
            table, k -> new TableStats(table)
        );
        tStats.record(duration, resultCount);
        
        // Update query type stats
        String queryType = query.getClass().getSimpleName();
        QueryTypeStats qtStats = this.queryTypeStats.computeIfAbsent(
            queryType, k -> new QueryTypeStats(queryType)
        );
        qtStats.record(duration, resultCount);
        
        // Track slow queries
        if (duration > this.slowQueryThreshold) {
            recordSlowQuery(table, query, duration, resultCount);
        }
        
        // Log if very slow
        if (duration > this.slowQueryThreshold * 10) {
            LOG.warn("Very slow query on table {}: {}ms for {} results. Query: {}",
                    table, duration, resultCount, query);
        }
    }
    
    /**
     * Record a slow query
     */
    private void recordSlowQuery(HugeType table, Query query, 
                                long duration, long resultCount) {
        String key = table + ":" + query.toString();
        
        SlowQuery slowQuery = this.slowQueries.computeIfAbsent(
            key, k -> new SlowQuery(table, query.toString())
        );
        
        slowQuery.record(duration, resultCount);
        
        // Keep only top 100 slow queries
        if (this.slowQueries.size() > 100) {
            // Remove fastest slow query
            String fastestKey = null;
            long fastestTime = Long.MAX_VALUE;
            
            for (Map.Entry<String, SlowQuery> entry : this.slowQueries.entrySet()) {
                if (entry.getValue().maxDuration < fastestTime) {
                    fastestTime = entry.getValue().maxDuration;
                    fastestKey = entry.getKey();
                }
            }
            
            if (fastestKey != null) {
                this.slowQueries.remove(fastestKey);
            }
        }
    }
    
    /**
     * Get statistics summary
     */
    public StatsSummary getSummary() {
        long queries = this.totalQueries.sum();
        long time = this.totalTime.sum();
        long hits = this.cacheHits.sum();
        long misses = this.cacheMisses.sum();
        
        double avgTime = queries > 0 ? (double) time / queries : 0;
        double hitRatio = (hits + misses) > 0 ? 
                         (double) hits / (hits + misses) : 0;
        
        return new StatsSummary(
            queries, time, avgTime, hits, misses, hitRatio,
            this.tableStats.size(), this.slowQueries.size()
        );
    }
    
    /**
     * Get table-specific statistics
     */
    public TableStats getTableStats(HugeType table) {
        return this.tableStats.get(table);
    }
    
    /**
     * Get slow queries
     */
    public Map<String, SlowQuery> getSlowQueries() {
        return new ConcurrentHashMap<>(this.slowQueries);
    }
    
    /**
     * Reset all statistics
     */
    public void reset() {
        this.totalQueries.reset();
        this.totalTime.reset();
        this.cacheHits.reset();
        this.cacheMisses.reset();
        this.tableStats.clear();
        this.queryTypeStats.clear();
        this.slowQueries.clear();
        
        LOG.info("Query statistics reset");
    }
    
    /**
     * Table-specific statistics
     */
    public static class TableStats {
        public final HugeType table;
        private final LongAdder queryCount;
        private final LongAdder totalTime;
        private final LongAdder totalResults;
        private final AtomicLong maxTime;
        private final AtomicLong minTime;
        
        public TableStats(HugeType table) {
            this.table = table;
            this.queryCount = new LongAdder();
            this.totalTime = new LongAdder();
            this.totalResults = new LongAdder();
            this.maxTime = new AtomicLong(0);
            this.minTime = new AtomicLong(Long.MAX_VALUE);
        }
        
        public void record(long duration, long resultCount) {
            this.queryCount.increment();
            this.totalTime.add(duration);
            this.totalResults.add(resultCount);
            
            // Update max
            long currentMax;
            do {
                currentMax = this.maxTime.get();
            } while (duration > currentMax && 
                    !this.maxTime.compareAndSet(currentMax, duration));
            
            // Update min
            long currentMin;
            do {
                currentMin = this.minTime.get();
            } while (duration < currentMin && 
                    !this.minTime.compareAndSet(currentMin, duration));
        }
        
        public long getQueryCount() {
            return this.queryCount.sum();
        }
        
        public double getAverageTime() {
            long count = this.queryCount.sum();
            return count > 0 ? (double) this.totalTime.sum() / count : 0;
        }
        
        public double getAverageResults() {
            long count = this.queryCount.sum();
            return count > 0 ? (double) this.totalResults.sum() / count : 0;
        }
        
        @Override
        public String toString() {
            return String.format("TableStats{table=%s, queries=%d, avgTime=%.2fms, " +
                               "avgResults=%.2f, maxTime=%dms, minTime=%dms}",
                               table, getQueryCount(), getAverageTime(),
                               getAverageResults(), maxTime.get(), 
                               minTime.get() == Long.MAX_VALUE ? 0 : minTime.get());
        }
    }
    
    /**
     * Query type statistics
     */
    public static class QueryTypeStats {
        public final String queryType;
        private final LongAdder count;
        private final LongAdder totalTime;
        private final LongAdder totalResults;
        
        public QueryTypeStats(String queryType) {
            this.queryType = queryType;
            this.count = new LongAdder();
            this.totalTime = new LongAdder();
            this.totalResults = new LongAdder();
        }
        
        public void record(long duration, long resultCount) {
            this.count.increment();
            this.totalTime.add(duration);
            this.totalResults.add(resultCount);
        }
        
        public long getCount() {
            return this.count.sum();
        }
        
        public double getAverageTime() {
            long c = this.count.sum();
            return c > 0 ? (double) this.totalTime.sum() / c : 0;
        }
    }
    
    /**
     * Slow query record
     */
    public static class SlowQuery {
        public final HugeType table;
        public final String query;
        private final AtomicLong count;
        private final AtomicLong totalDuration;
        private volatile long maxDuration;
        private volatile long lastOccurrence;
        
        public SlowQuery(HugeType table, String query) {
            this.table = table;
            this.query = query;
            this.count = new AtomicLong(0);
            this.totalDuration = new AtomicLong(0);
            this.maxDuration = 0;
            this.lastOccurrence = System.currentTimeMillis();
        }
        
        public void record(long duration, long resultCount) {
            this.count.incrementAndGet();
            this.totalDuration.addAndGet(duration);
            
            if (duration > this.maxDuration) {
                this.maxDuration = duration;
            }
            
            this.lastOccurrence = System.currentTimeMillis();
        }
        
        public long getCount() {
            return this.count.get();
        }
        
        public double getAverageDuration() {
            long c = this.count.get();
            return c > 0 ? (double) this.totalDuration.get() / c : 0;
        }
        
        @Override
        public String toString() {
            return String.format("SlowQuery{table=%s, count=%d, avgTime=%.2fms, " +
                               "maxTime=%dms, query='%s'}",
                               table, getCount(), getAverageDuration(),
                               maxDuration, query);
        }
    }
    
    /**
     * Statistics summary
     */
    public static class StatsSummary {
        public final long totalQueries;
        public final long totalTime;
        public final double averageTime;
        public final long cacheHits;
        public final long cacheMisses;
        public final double cacheHitRatio;
        public final int tableCount;
        public final int slowQueryCount;
        
        public StatsSummary(long totalQueries, long totalTime, double averageTime,
                          long cacheHits, long cacheMisses, double cacheHitRatio,
                          int tableCount, int slowQueryCount) {
            this.totalQueries = totalQueries;
            this.totalTime = totalTime;
            this.averageTime = averageTime;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.cacheHitRatio = cacheHitRatio;
            this.tableCount = tableCount;
            this.slowQueryCount = slowQueryCount;
        }
        
        @Override
        public String toString() {
            return String.format(
                "QueryStats{queries=%d, totalTime=%dms, avgTime=%.2fms, " +
                "cacheHitRatio=%.2f%%, tables=%d, slowQueries=%d}",
                totalQueries, totalTime, averageTime, 
                cacheHitRatio * 100, tableCount, slowQueryCount
            );
        }
    }
}