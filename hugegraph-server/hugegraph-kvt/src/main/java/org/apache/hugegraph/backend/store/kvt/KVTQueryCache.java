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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.backend.query.Query;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Query result cache for KVT backend.
 * Caches query results to avoid repeated expensive operations.
 */
public class KVTQueryCache {
    
    private static final Logger LOG = Log.logger(KVTQueryCache.class);
    
    private static final int DEFAULT_MAX_SIZE = 10000;
    private static final long DEFAULT_TTL = 60000; // 60 seconds
    
    private final Map<CacheKey, CacheEntry> cache;
    private final int maxSize;
    private final long ttl;
    private final boolean enabled;
    
    // Statistics
    private final AtomicLong hits;
    private final AtomicLong misses;
    private final AtomicLong evictions;
    
    public KVTQueryCache() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL, true);
    }
    
    public KVTQueryCache(int maxSize, long ttl, boolean enabled) {
        this.cache = new ConcurrentHashMap<>();
        this.maxSize = maxSize;
        this.ttl = ttl;
        this.enabled = enabled;
        
        this.hits = new AtomicLong(0);
        this.misses = new AtomicLong(0);
        this.evictions = new AtomicLong(0);
    }
    
    /**
     * Get cached query result
     */
    public Iterator<BackendEntry> get(Query query, long tableId) {
        if (!this.enabled) {
            return null;
        }
        
        CacheKey key = new CacheKey(query, tableId);
        CacheEntry entry = this.cache.get(key);
        
        if (entry == null) {
            this.misses.incrementAndGet();
            return null;
        }
        
        // Check if entry is expired
        if (entry.isExpired()) {
            this.cache.remove(key);
            this.evictions.incrementAndGet();
            this.misses.incrementAndGet();
            return null;
        }
        
        this.hits.incrementAndGet();
        entry.updateAccessTime();
        
        // Return a copy of cached results to avoid modification
        return new ArrayList<>(entry.results).iterator();
    }
    
    /**
     * Cache query result
     */
    public void put(Query query, long tableId, Iterator<BackendEntry> results) {
        if (!this.enabled) {
            return;
        }
        
        // Check cache size
        if (this.cache.size() >= this.maxSize) {
            evictOldest();
        }
        
        // Materialize results
        List<BackendEntry> resultList = new ArrayList<>();
        while (results.hasNext()) {
            resultList.add(results.next());
        }
        
        CacheKey key = new CacheKey(query, tableId);
        CacheEntry entry = new CacheEntry(resultList, this.ttl);
        this.cache.put(key, entry);
        
        LOG.trace("Cached query result with {} entries", resultList.size());
    }
    
    /**
     * Invalidate cache entries for a table
     */
    public void invalidate(long tableId) {
        if (!this.enabled) {
            return;
        }
        
        List<CacheKey> keysToRemove = new ArrayList<>();
        for (CacheKey key : this.cache.keySet()) {
            if (key.tableId == tableId) {
                keysToRemove.add(key);
            }
        }
        
        for (CacheKey key : keysToRemove) {
            this.cache.remove(key);
            this.evictions.incrementAndGet();
        }
        
        LOG.debug("Invalidated {} cache entries for table {}", 
                 keysToRemove.size(), tableId);
    }
    
    /**
     * Invalidate specific query cache
     */
    public void invalidate(Query query, long tableId) {
        if (!this.enabled) {
            return;
        }
        
        CacheKey key = new CacheKey(query, tableId);
        if (this.cache.remove(key) != null) {
            this.evictions.incrementAndGet();
        }
    }
    
    /**
     * Clear all cache
     */
    public void clear() {
        int size = this.cache.size();
        this.cache.clear();
        this.evictions.addAndGet(size);
        LOG.debug("Cleared {} cache entries", size);
    }
    
    /**
     * Evict oldest entries
     */
    private void evictOldest() {
        // Simple eviction: remove expired entries first
        List<CacheKey> expiredKeys = new ArrayList<>();
        for (Map.Entry<CacheKey, CacheEntry> entry : this.cache.entrySet()) {
            if (entry.getValue().isExpired()) {
                expiredKeys.add(entry.getKey());
            }
        }
        
        for (CacheKey key : expiredKeys) {
            this.cache.remove(key);
            this.evictions.incrementAndGet();
        }
        
        // If still over size, remove oldest accessed
        if (this.cache.size() >= this.maxSize) {
            CacheKey oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            
            for (Map.Entry<CacheKey, CacheEntry> entry : this.cache.entrySet()) {
                if (entry.getValue().lastAccessTime < oldestTime) {
                    oldestTime = entry.getValue().lastAccessTime;
                    oldestKey = entry.getKey();
                }
            }
            
            if (oldestKey != null) {
                this.cache.remove(oldestKey);
                this.evictions.incrementAndGet();
            }
        }
    }
    
    /**
     * Get cache statistics
     */
    public CacheStatistics getStatistics() {
        return new CacheStatistics(
            this.hits.get(),
            this.misses.get(),
            this.evictions.get(),
            this.cache.size()
        );
    }
    
    /**
     * Enable or disable cache
     */
    public void setEnabled(boolean enabled) {
        if (!enabled && this.enabled) {
            clear();
        }
    }
    
    /**
     * Cache key
     */
    private static class CacheKey {
        private final Query query;
        private final long tableId;
        private final int hashCode;
        
        public CacheKey(Query query, long tableId) {
            this.query = query;
            this.tableId = tableId;
            this.hashCode = computeHashCode();
        }
        
        private int computeHashCode() {
            int result = Long.hashCode(tableId);
            result = 31 * result + query.hashCode();
            return result;
        }
        
        @Override
        public int hashCode() {
            return this.hashCode;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CacheKey)) {
                return false;
            }
            
            CacheKey other = (CacheKey) obj;
            return this.tableId == other.tableId && 
                   this.query.equals(other.query);
        }
    }
    
    /**
     * Cache entry
     */
    private static class CacheEntry {
        private final List<BackendEntry> results;
        private final long createTime;
        private final long ttl;
        private volatile long lastAccessTime;
        
        public CacheEntry(List<BackendEntry> results, long ttl) {
            this.results = results;
            this.createTime = System.currentTimeMillis();
            this.ttl = ttl;
            this.lastAccessTime = this.createTime;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - this.createTime > this.ttl;
        }
        
        public void updateAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Cache statistics
     */
    public static class CacheStatistics {
        public final long hits;
        public final long misses;
        public final long evictions;
        public final int size;
        
        public CacheStatistics(long hits, long misses, long evictions, int size) {
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.size = size;
        }
        
        public double getHitRatio() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats{hits=%d, misses=%d, ratio=%.2f%%, " +
                               "evictions=%d, size=%d}",
                               hits, misses, getHitRatio() * 100, 
                               evictions, size);
        }
    }
}