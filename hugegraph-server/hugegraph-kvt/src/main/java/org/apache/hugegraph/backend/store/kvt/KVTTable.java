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

import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.query.Condition;
import org.apache.hugegraph.backend.query.ConditionQuery;
import org.apache.hugegraph.backend.query.IdQuery;
import org.apache.hugegraph.backend.query.Query;
import org.apache.hugegraph.backend.serializer.BinaryBackendEntry;
import org.apache.hugegraph.backend.serializer.BinaryBackendEntry.BinaryId;
import org.apache.hugegraph.backend.serializer.BinarySerializer;
import org.apache.hugegraph.backend.serializer.BytesBuffer;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.backend.store.BackendTable;
import org.apache.hugegraph.backend.store.Shard;
import org.apache.hugegraph.iterator.MapperIterator;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.apache.hugegraph.util.NumericUtil;
import org.slf4j.Logger;

/**
 * KVT table implementation.
 * Maps HugeGraph table operations to KVT key-value operations.
 */
public class KVTTable extends BackendTable<KVTSession, BackendEntry> {

    private static final Logger LOG = Log.logger(KVTTable.class);
    
    private final HugeType type;
    private final boolean rangePartitioned;
    protected long tableId;
    private final KVTQueryCache cache;
    private final boolean cacheEnabled;
    
    public KVTTable(HugeType type) {
        this(type, false);
    }
    
    public KVTTable(HugeType type, boolean rangePartitioned) {
        this(type, rangePartitioned, true);
    }
    
    public KVTTable(HugeType type, boolean rangePartitioned, boolean enableCache) {
        super(type.string());
        this.type = type;
        this.rangePartitioned = rangePartitioned;
        this.tableId = 0;
        this.cacheEnabled = enableCache;
        this.cache = enableCache ? new KVTQueryCache() : null;
    }
    
    @Override
    public String table() {
        return this.type.string();
    }
    
    public HugeType type() {
        return this.type;
    }
    
    public boolean isRangePartitioned() {
        return this.rangePartitioned;
    }
    
    public long getTableId() {
        return this.tableId;
    }
    
    public void setTableId(long tableId) {
        this.tableId = tableId;
    }
    
    @Override
    public void init(KVTSession session) {
        // Table initialization is handled by KVTStore
    }
    
    @Override
    public void clear(KVTSession session) {
        // Clear all data in the table by scanning and deleting all keys
        LOG.info("Clearing table {} (tableId: {})", this.table(), this.tableId);
        
        // Scan all keys in the table with empty start and end keys
        byte[] startKey = new byte[0];
        byte[] endKey = new byte[0];
        int batchSize = 1000;
        int totalDeleted = 0;
        
        while (true) {
            // Scan a batch of keys
            Iterator<KVTNative.KVTPair> iter = session.scan(this.tableId, startKey, endKey, batchSize);
            
            List<KVTNative.KVTPair> batch = new ArrayList<>();
            while (iter.hasNext() && batch.size() < batchSize) {
                batch.add(iter.next());
            }
            
            if (batch.isEmpty()) {
                break; // No more keys
            }
            
            // Delete each key in the batch
            for (KVTNative.KVTPair pair : batch) {
                session.delete(this.tableId, pair.key);
                totalDeleted++;
            }
            
            // If we got less than batchSize, we're done
            if (batch.size() < batchSize) {
                break;
            }
            
            // Update startKey for next batch (start after last key)
            byte[] lastKey = batch.get(batch.size() - 1).key;
            startKey = new byte[lastKey.length + 1];
            System.arraycopy(lastKey, 0, startKey, 0, lastKey.length);
            startKey[lastKey.length] = 0x01; // Ensure we start after the last key
        }
        
        LOG.info("Cleared {} entries from table {}", totalDeleted, this.table());
    }
    
    /**
     * Insert or update an entry
     */
    @Override
    public void insert(KVTSession session, BackendEntry entry) {
        byte[] key = KVTIdUtil.idToBytes(this.type, entry.id());
        
        // For BinaryBackendEntry, we can get the full serialized form
        byte[] value;
        if (entry instanceof BinaryBackendEntry) {
            // Get the complete serialized entry
            BinaryBackendEntry bEntry = (BinaryBackendEntry) entry;
            BytesBuffer buffer = BytesBuffer.allocate(0);
            
            // Write ID in the proper BinaryBackendEntry format
            BinaryId bid = bEntry.id();
            buffer.write(bid.asBytes());
            
            // Write columns
            for (BackendEntry.BackendColumn column : bEntry.columns()) {
                buffer.write(column.name);
                buffer.write(column.value);
            }
            value = buffer.bytes();
        } else {
            // For other entries, just serialize the columns
            BytesBuffer buffer = BytesBuffer.allocate(0);
            for (BackendEntry.BackendColumn column : entry.columns()) {
                buffer.write(column.name);
                buffer.write(column.value);
            }
            value = buffer.bytes();
        }
        
        session.set(this.tableId, key, value);
        
        // Invalidate cache for this table
        if (this.cache != null) {
            this.cache.invalidate(this.tableId);
        }
        
        LOG.trace("Inserted entry with key {} to table {}", 
                 entry.id(), this.table());
    }
    
    /**
     * Delete an entry
     */
    @Override
    public void delete(KVTSession session, BackendEntry entry) {
        byte[] key = KVTIdUtil.idToBytes(this.type, entry.id());
        session.delete(this.tableId, key);
        
        LOG.trace("Deleted entry with key {} from table {}", 
                 entry.id(), this.table());
    }
    
    /**
     * Append columns to an entry
     */
    @Override
    public void append(KVTSession session, BackendEntry entry) {
        // For KVT, append is similar to insert (overwrites the value)
        // In a real implementation, you might want to merge columns
        this.insert(session, entry);
    }
    
    /**
     * Eliminate specific columns from an entry
     */
    @Override
    public void eliminate(KVTSession session, BackendEntry entry) {
        // Get existing entry
        byte[] key = KVTIdUtil.idToBytes(this.type, entry.id());
        byte[] existingValue = session.get(this.tableId, key);
        
        if (existingValue == null) {
            return;  // Nothing to eliminate
        }
        
        // TODO: Implement column elimination logic
        // This would involve deserializing the value, removing specific columns,
        // and re-serializing
        LOG.warn("Column elimination not yet fully implemented");
    }
    
    // updateIfPresent and updateIfAbsent are inherited from BackendTable
    
    /**
     * Query entries from the table
     */
    @Override
    public Iterator<BackendEntry> query(KVTSession session, Query query) {
        // Check cache first
        if (this.cache != null) {
            Iterator<BackendEntry> cached = this.cache.get(query, this.tableId);
            if (cached != null) {
                return cached;
            }
        }
        
        // Optimize query
        KVTQueryOptimizer.QueryPlan plan = 
            KVTQueryOptimizer.optimize(query, this.type);
        
        // Execute query based on plan
        Iterator<BackendEntry> result;
        if (query instanceof IdQuery) {
            result = this.queryById(session, (IdQuery) query);
        } else if (query instanceof ConditionQuery) {
            result = this.queryByCondition(session, (ConditionQuery) query);
        } else {
            // Base Query class - scan all entries
            result = this.scanTable(session, query);
        }
        
        // Apply optimization
        result = KVTQueryOptimizer.createIterator(plan, result);
        
        // Cache result if appropriate
        if (this.cache != null && shouldCache(query)) {
            // Materialize results for caching
            List<BackendEntry> materializedResults = new ArrayList<>();
            while (result.hasNext()) {
                materializedResults.add(result.next());
            }
            this.cache.put(query, this.tableId, materializedResults.iterator());
            result = materializedResults.iterator();
        }
        
        return result;
    }
    
    private Iterator<BackendEntry> queryById(KVTSession session, IdQuery query) {
        List<BackendEntry> entries = new ArrayList<>();
        
        for (Id id : query.ids()) {
            byte[] key = KVTIdUtil.idToBytes(this.type, id);
            byte[] value = session.get(this.tableId, key);
            
            if (value != null) {
                // Value already contains the complete entry with ID and columns
                BinaryBackendEntry entry = new BinaryBackendEntry(this.type, value);
                entries.add(entry);
            }
        }
        
        return entries.iterator();
    }
    
    private Iterator<BackendEntry> queryByCondition(KVTSession session, 
                                                   ConditionQuery query) {
        // For range queries on range-partitioned tables
        if (this.rangePartitioned && query.hasRangeCondition()) {
            return this.queryByRange(session, query);
        }
        
        // For other queries, we need to scan
        // This is inefficient and should be optimized with indexes
        return this.scanTable(session, query);
    }
    
    private Iterator<BackendEntry> queryByRange(KVTSession session, 
                                               ConditionQuery query) {
        // Extract range conditions using query translator
        KVTQueryTranslator.ScanRange range = 
            KVTQueryTranslator.translateToScan(query, this.type);
        
        int limit = query.limit() == Query.NO_LIMIT ? 
                   Integer.MAX_VALUE : (int) query.limit();
        
        Iterator<KVTNative.KVTPair> pairs = 
            session.scan(this.tableId, range.startKey, range.endKey, limit);
        
        Iterator<BackendEntry> entries = new MapperIterator<>(pairs, pair -> {
            // Value already contains the complete entry with ID and columns
            return new BinaryBackendEntry(this.type, pair.value);
        });
        
        // Apply filter conditions if any
        return KVTQueryTranslator.applyFilters(entries, range.filterConditions);
    }
    
    private Iterator<BackendEntry> scanTable(KVTSession session, Query query) {
        // Full table scan - should be avoided for large tables
        LOG.warn("Performing full table scan on {} for query {}", 
                this.table(), query);
        
        int limit = query.limit() == Query.NO_LIMIT ? 
                   Integer.MAX_VALUE : (int) query.limit();
        
        byte[] startKey = KVTIdUtil.scanStartKey(this.type, null);
        byte[] endKey = KVTIdUtil.scanEndKey(this.type, null);
        
        Iterator<KVTNative.KVTPair> pairs = 
            session.scan(this.tableId, startKey, endKey, limit);
        
        return new MapperIterator<>(pairs, pair -> {
            // Value already contains the complete entry with ID and columns
            return new BinaryBackendEntry(this.type, pair.value);
        });
    }
    
    @Override
    public Number queryNumber(KVTSession session, Query query) {
        // Count query - can be optimized in the future
        long count = 0;
        Iterator<BackendEntry> iter = this.query(session, query);
        while (iter.hasNext()) {
            iter.next();
            count++;
        }
        return count;
    }
    
    @Override
    public boolean queryExist(KVTSession session, BackendEntry entry) {
        byte[] key = KVTIdUtil.idToBytes(this.type, entry.id());
        byte[] value = session.get(this.tableId, key);
        
        return value != null;
    }
    
    public Shard getShard(KVTSession session, int partitionId) {
        // Sharding support - not yet implemented
        throw new UnsupportedOperationException("Sharding not yet supported");
    }
    
    public void addShard(KVTSession session, Shard shard) {
        // Sharding support - not yet implemented
        throw new UnsupportedOperationException("Sharding not yet supported");
    }
    
    public void updateShard(KVTSession session, Shard shard) {
        // Sharding support - not yet implemented  
        throw new UnsupportedOperationException("Sharding not yet supported");
    }
    
    public void deleteShard(KVTSession session, Shard shard) {
        // Sharding support - not yet implemented
        throw new UnsupportedOperationException("Sharding not yet supported");
    }
    
    /**
     * Check if query result should be cached
     */
    private boolean shouldCache(Query query) {
        // Don't cache queries with large limits
        if (query.limit() > 1000) {
            return false;
        }
        
        // Don't cache queries without conditions (full scans)
        if (query instanceof ConditionQuery) {
            ConditionQuery cq = (ConditionQuery) query;
            if (cq.conditions().isEmpty()) {
                return false;
            }
        }
        
        // Cache other queries
        return true;
    }
    
    /**
     * Get cache statistics
     */
    public KVTQueryCache.CacheStatistics getCacheStatistics() {
        return this.cache != null ? this.cache.getStatistics() : null;
    }
}