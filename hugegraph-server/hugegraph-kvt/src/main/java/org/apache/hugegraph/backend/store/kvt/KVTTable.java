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
import org.apache.hugegraph.backend.serializer.BinarySerializer;
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
public class KVTTable extends BackendTable<KVTSession, KVTBackendEntry> {

    private static final Logger LOG = Log.logger(KVTTable.class);
    
    private final HugeType type;
    private final boolean rangePartitioned;
    private long tableId;
    
    public KVTTable(HugeType type) {
        this(type, false);
    }
    
    public KVTTable(HugeType type, boolean rangePartitioned) {
        this.type = type;
        this.rangePartitioned = rangePartitioned;
        this.tableId = 0;
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
        // Clear all data in the table
        // TODO: Implement table clearing (scan and delete all)
        LOG.warn("Table clear not yet implemented for {}", this.table());
    }
    
    /**
     * Insert or update an entry
     */
    public void insert(KVTSession session, BackendEntry entry) {
        assert entry instanceof KVTBackendEntry;
        KVTBackendEntry kvtEntry = (KVTBackendEntry) entry;
        
        byte[] key = KVTIdUtil.idToBytes(this.type, kvtEntry.id());
        byte[] value = kvtEntry.columnsBytes();
        
        session.set(this.tableId, key, value);
        
        LOG.trace("Inserted entry with key {} to table {}", 
                 entry.id(), this.table());
    }
    
    /**
     * Delete an entry
     */
    @Override
    public void delete(KVTSession session, BackendEntry entry) {
        assert entry instanceof KVTBackendEntry;
        KVTBackendEntry kvtEntry = (KVTBackendEntry) entry;
        
        byte[] key = KVTIdUtil.idToBytes(this.type, kvtEntry.id());
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
        assert entry instanceof KVTBackendEntry;
        KVTBackendEntry kvtEntry = (KVTBackendEntry) entry;
        
        // Get existing entry
        byte[] key = KVTIdUtil.idToBytes(this.type, kvtEntry.id());
        byte[] existingValue = session.get(this.tableId, key);
        
        if (existingValue == null) {
            return;  // Nothing to eliminate
        }
        
        // TODO: Implement column elimination logic
        // This would involve deserializing the value, removing specific columns,
        // and re-serializing
        LOG.warn("Column elimination not yet fully implemented");
    }
    
    /**
     * Update entry if present
     */
    @Override
    public boolean updateIfPresent(KVTSession session, BackendEntry entry) {
        assert entry instanceof KVTBackendEntry;
        KVTBackendEntry kvtEntry = (KVTBackendEntry) entry;
        
        byte[] key = KVTIdUtil.idToBytes(this.type, kvtEntry.id());
        byte[] existingValue = session.get(this.tableId, key);
        
        if (existingValue != null) {
            byte[] value = kvtEntry.columnsBytes();
            session.set(this.tableId, key, value);
            return true;
        }
        
        return false;
    }
    
    /**
     * Update entry if absent
     */
    @Override
    public boolean updateIfAbsent(KVTSession session, BackendEntry entry) {
        assert entry instanceof KVTBackendEntry;
        KVTBackendEntry kvtEntry = (KVTBackendEntry) entry;
        
        byte[] key = KVTIdUtil.idToBytes(this.type, kvtEntry.id());
        byte[] existingValue = session.get(this.tableId, key);
        
        if (existingValue == null) {
            byte[] value = kvtEntry.columnsBytes();
            session.set(this.tableId, key, value);
            return true;
        }
        
        return false;
    }
    
    /**
     * Query entries from the table
     */
    @Override
    public Iterator<BackendEntry> query(KVTSession session, Query query) {
        if (query instanceof IdQuery) {
            return this.queryById(session, (IdQuery) query);
        } else if (query instanceof ConditionQuery) {
            return this.queryByCondition(session, (ConditionQuery) query);
        } else {
            throw new BackendException("Unsupported query type: %s", 
                                     query.getClass());
        }
    }
    
    private Iterator<BackendEntry> queryById(KVTSession session, IdQuery query) {
        List<BackendEntry> entries = new ArrayList<>();
        
        for (Id id : query.ids()) {
            byte[] key = KVTIdUtil.idToBytes(this.type, id);
            byte[] value = session.get(this.tableId, key);
            
            if (value != null) {
                KVTBackendEntry entry = new KVTBackendEntry(this.type, id);
                entry.columnsBytes(value);
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
            KVTQueryTranslator.extractScanRange(this.type, query);
        
        int limit = query.limit() == Query.NO_LIMIT ? 
                   Integer.MAX_VALUE : (int) query.limit();
        
        Iterator<KVTNative.KVTPair> pairs = 
            session.scan(this.tableId, range.startKey, range.endKey, limit);
        
        Iterator<BackendEntry> entries = new MapperIterator<>(pairs, pair -> {
            Id id = KVTIdUtil.bytesToId(pair.key);
            KVTBackendEntry entry = new KVTBackendEntry(this.type, id);
            entry.columnsBytes(pair.value);
            return entry;
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
            Id id = KVTIdUtil.bytesToId(pair.key);
            KVTBackendEntry entry = new KVTBackendEntry(this.type, id);
            entry.columnsBytes(pair.value);
            return entry;
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
        assert entry instanceof KVTBackendEntry;
        KVTBackendEntry kvtEntry = (KVTBackendEntry) entry;
        
        byte[] key = KVTIdUtil.idToBytes(this.type, kvtEntry.id());
        byte[] value = session.get(this.tableId, key);
        
        return value != null;
    }
    
    @Override
    public Shard getShard(KVTSession session, int partitionId) {
        // Sharding support - not yet implemented
        throw new UnsupportedOperationException("Sharding not yet supported");
    }
    
    @Override
    public void addShard(KVTSession session, Shard shard) {
        // Sharding support - not yet implemented
        throw new UnsupportedOperationException("Sharding not yet supported");
    }
    
    @Override
    public void updateShard(KVTSession session, Shard shard) {
        // Sharding support - not yet implemented  
        throw new UnsupportedOperationException("Sharding not yet supported");
    }
    
    @Override
    public void deleteShard(KVTSession session, Shard shard) {
        // Sharding support - not yet implemented
        throw new UnsupportedOperationException("Sharding not yet supported");
    }
}