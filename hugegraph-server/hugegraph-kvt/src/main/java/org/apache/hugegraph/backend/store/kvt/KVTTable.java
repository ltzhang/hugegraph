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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.query.Condition;
import org.apache.hugegraph.backend.query.ConditionQuery;
import org.apache.hugegraph.backend.query.IdQuery;
import org.apache.hugegraph.backend.query.IdPrefixQuery;
import org.apache.hugegraph.backend.query.IdRangeQuery;
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
        
        // Serialize the entry with ID and columns
        BytesBuffer buffer = BytesBuffer.allocate(0);
        
        // Write ID in the proper BinaryBackendEntry format
        if (entry instanceof BinaryBackendEntry) {
            BinaryBackendEntry bEntry = (BinaryBackendEntry) entry;
            BinaryId bid = bEntry.id();
            buffer.write(bid.asBytes());
        } else {
            // For non-binary entries, convert ID to bytes
            byte[] idBytes = KVTIdUtil.idToBytes(this.type, entry.id());
            buffer.write(idBytes);
        }
        
        // Write columns with length prefixes for proper parsing
        for (BackendEntry.BackendColumn column : entry.columns()) {
            // Write column name length and name
            buffer.writeVInt(column.name.length);
            buffer.write(column.name);
            
            // Write column value length and value
            buffer.writeVInt(column.value.length);
            buffer.write(column.value);
        }
        
        byte[] value = buffer.bytes();
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
        // Check if this is a property update (vertex or edge)
        if (entry.subId() != null) {
            if (entry.type() == HugeType.VERTEX) {
                // This is a vertex property update
                this.updateVertexProperty(session, entry);
            } else if (entry.type() == HugeType.EDGE_OUT || 
                       entry.type() == HugeType.EDGE_IN) {
                // This is an edge property update
                this.updateEdgeProperty(session, entry);
            } else {
                // For other cases with subId, use regular insert
                this.insert(session, entry);
            }
        } else {
            // For other cases, append is similar to insert
            this.insert(session, entry);
        }
    }
    
    /**
     * Update a vertex property using native atomic update
     */
    private void updateVertexProperty(KVTSession session, BackendEntry entry) {
        byte[] key = KVTIdUtil.idToBytes(this.type, entry.id());
        
        // Serialize the property update data
        BytesBuffer buffer = BytesBuffer.allocate(0);
        
        // Write property columns with length prefixes
        for (BackendEntry.BackendColumn column : entry.columns()) {
            buffer.writeVInt(column.name.length);
            buffer.write(column.name);
            buffer.writeVInt(column.value.length);
            buffer.write(column.value);
        }
        
        byte[] propertyUpdate = buffer.bytes();
        
        // Call native update
        session.updateVertexProperty(this.tableId, key, propertyUpdate);
        
        // Invalidate cache for this table
        if (this.cache != null) {
            this.cache.invalidate(this.tableId);
        }
        
        LOG.trace("Updated vertex property for key {} in table {}", 
                 entry.id(), this.table());
    }
    
    /**
     * Update an edge property using native atomic update
     */
    private void updateEdgeProperty(KVTSession session, BackendEntry entry) {
        byte[] key = KVTIdUtil.idToBytes(this.type, entry.id());
        
        // Serialize the property update data
        BytesBuffer buffer = BytesBuffer.allocate(0);
        
        // Write property columns with length prefixes
        for (BackendEntry.BackendColumn column : entry.columns()) {
            buffer.writeVInt(column.name.length);
            buffer.write(column.name);
            buffer.writeVInt(column.value.length);
            buffer.write(column.value);
        }
        
        byte[] propertyUpdate = buffer.bytes();
        
        // Call native update
        session.updateEdgeProperty(this.tableId, key, propertyUpdate);
        
        // Invalidate cache for this table
        if (this.cache != null) {
            this.cache.invalidate(this.tableId);
        }
        
        LOG.trace("Updated edge property for key {} in table {}", 
                 entry.id(), this.table());
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
        
        // Execute query based on plan - following RocksDB pattern
        Iterator<BackendEntry> result;
        
        // Query by prefix - optimized scan
        if (query instanceof IdPrefixQuery) {
            result = this.queryByPrefix(session, (IdPrefixQuery) query);
        }
        // Query by range - optimized scan
        else if (query instanceof IdRangeQuery) {
            result = this.queryByRange(session, (IdRangeQuery) query);
        }
        // Query by specific IDs
        else if (query instanceof IdQuery) {
            result = this.queryById(session, (IdQuery) query);
        }
        // Query with conditions
        else if (query instanceof ConditionQuery) {
            result = this.queryByCondition(session, (ConditionQuery) query);
        }
        // Base Query class - scan all entries
        else {
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
                // Parse the stored value to reconstruct the entry
                BinaryBackendEntry entry = parseStoredEntry(id, value);
                entries.add(entry);
            }
        }
        
        return entries.iterator();
    }
    
    /**
     * Query by prefix - optimized prefix scan following RocksDB pattern
     */
    private Iterator<BackendEntry> queryByPrefix(KVTSession session, IdPrefixQuery query) {
        // Calculate scan range based on prefix
        byte[] prefix = query.prefix().asBytes();
        byte[] start = query.start().asBytes();
        
        // If start is not at the beginning of prefix, use it; otherwise use prefix
        if (!Arrays.equals(start, prefix)) {
            // Start is specified separately from prefix
            start = start;
        }
        
        // End key is prefix + max value to scan entire prefix range
        byte[] end = KVTIdUtil.prefixEnd(prefix);
        
        int limit = query.limit() == Query.NO_LIMIT ? 
                   Integer.MAX_VALUE : (int) query.limit();
        
        // Perform prefix scan
        Iterator<KVTNative.KVTPair> pairs = 
            session.scan(this.tableId, start, end, limit);
        
        // Convert to BackendEntry iterator
        return new MapperIterator<>(pairs, pair -> {
            Id id = KVTIdUtil.bytesToId(pair.key);
            return parseStoredEntry(id, pair.value);
        });
    }
    
    /**
     * Query by range - optimized range scan following RocksDB pattern
     */
    private Iterator<BackendEntry> queryByRange(KVTSession session, IdRangeQuery query) {
        byte[] start = query.start().asBytes();
        byte[] end = query.end() == null ? null : query.end().asBytes();
        
        // Adjust for inclusive/exclusive boundaries
        if (!query.inclusiveStart() && start != null) {
            // Exclusive start - increment start key
            start = KVTIdUtil.incrementBytes(start);
        }
        
        if (query.inclusiveEnd() && end != null) {
            // Inclusive end - increment end key for scan
            end = KVTIdUtil.incrementBytes(end);
        }
        
        int limit = query.limit() == Query.NO_LIMIT ? 
                   Integer.MAX_VALUE : (int) query.limit();
        
        // Perform range scan
        Iterator<KVTNative.KVTPair> pairs = 
            session.scan(this.tableId, start, end, limit);
        
        // Convert to BackendEntry iterator
        return new MapperIterator<>(pairs, pair -> {
            Id id = KVTIdUtil.bytesToId(pair.key);
            return parseStoredEntry(id, pair.value);
        });
    }
    
    private Iterator<BackendEntry> queryByCondition(KVTSession session, 
                                                   ConditionQuery query) {
        // For range queries on range-partitioned tables
        if (this.rangePartitioned && query.hasRangeCondition()) {
            return this.queryByRangeCondition(session, query);
        }
        
        // For other queries, we need to scan
        // This is inefficient and should be optimized with indexes
        return this.scanTable(session, query);
    }
    
    private Iterator<BackendEntry> queryByRangeCondition(KVTSession session, 
                                                        ConditionQuery query) {
        // Extract range conditions using query translator
        KVTQueryTranslator.ScanRange range = 
            KVTQueryTranslator.translateToScan(query, this.type);
        
        int limit = query.limit() == Query.NO_LIMIT ? 
                   Integer.MAX_VALUE : (int) query.limit();
        
        Iterator<KVTNative.KVTPair> pairs = 
            session.scan(this.tableId, range.startKey, range.endKey, limit);
        
        Iterator<BackendEntry> entries = new MapperIterator<>(pairs, pair -> {
            // Parse the stored value to reconstruct the entry
            Id id = KVTIdUtil.bytesToId(pair.key);
            return parseStoredEntry(id, pair.value);
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
            // Parse the stored value to reconstruct the entry  
            Id id = KVTIdUtil.bytesToId(pair.key);
            return parseStoredEntry(id, pair.value);
        });
    }
    
    /**
     * Parse a stored entry from its serialized form
     */
    private BinaryBackendEntry parseStoredEntry(Id id, byte[] value) {
        try {
            BytesBuffer buffer = BytesBuffer.wrap(value);
            
            // The data format stored is: [id_bytes][columns...]
            // The ID was already parsed from the key, so we need to skip the ID bytes in the value
            
            // Determine ID size to skip based on the type
            int idBytesLength = 0;
            if (id instanceof BinaryId) {
                BinaryId bid = (BinaryId) id;
                idBytesLength = bid.asBytes().length;
            } else {
                // For non-binary IDs, we need to determine the serialized size
                // This depends on the HugeType and ID format
                byte[] idBytes = KVTIdUtil.idToBytes(this.type, id);
                idBytesLength = idBytes.length;
            }
            
            // Skip the ID bytes that are at the beginning of the value
            if (idBytesLength > 0 && buffer.remaining() >= idBytesLength) {
                buffer.read(idBytesLength);
            }
            
            // Create the entry with the known ID
            BinaryBackendEntry entry;
            if (id instanceof BinaryId) {
                entry = new BinaryBackendEntry(this.type, (BinaryId) id);
            } else {
                // Convert the ID to BinaryId format for the entry
                byte[] idBytes = KVTIdUtil.idToBytes(this.type, id);
                BinaryId bid = new BinaryId(idBytes, id);
                entry = new BinaryBackendEntry(this.type, bid);
            }
            
            // Parse columns: [name_len_vInt][name][value_len_vInt][value]...
            while (buffer.remaining() > 0) {
                try {
                    // Read column name length as vInt
                    int nameLen = buffer.readVInt();
                    if (nameLen <= 0 || nameLen > buffer.remaining()) {
                        // Invalid name length, stop parsing
                        LOG.debug("Invalid column name length {} at position {}, stopping parse", 
                                 nameLen, buffer.position());
                        break;
                    }
                    byte[] name = buffer.read(nameLen);
                    
                    // Check if we have enough bytes for value length
                    if (buffer.remaining() < 1) {
                        LOG.debug("No remaining bytes for value length at position {}", 
                                 buffer.position());
                        break;
                    }
                    
                    // Read column value length as vInt
                    int valueLen = buffer.readVInt();
                    if (valueLen < 0 || valueLen > buffer.remaining()) {
                        // Invalid value length, stop parsing
                        LOG.debug("Invalid column value length {} at position {}, stopping parse", 
                                 valueLen, buffer.position());
                        break;
                    }
                    
                    // Read the value (could be empty)
                    byte[] colValue = valueLen > 0 ? buffer.read(valueLen) : BytesBuffer.BYTES_EMPTY;
                    
                    // Add the column to the entry
                    entry.column(name, colValue);
                    
                    LOG.trace("Parsed column: name_len={}, value_len={}", nameLen, valueLen);
                    
                } catch (Exception e) {
                    // Error parsing column, log and continue with what we have
                    LOG.debug("Error parsing column at position {}: {}", 
                             buffer.position(), e.getMessage());
                    break;
                }
            }
            
            LOG.trace("Parsed entry with {} columns", entry.columnsSize());
            return entry;
            
        } catch (Exception e) {
            // Critical parsing error, create minimal entry with just the ID
            LOG.warn("Critical error parsing stored entry for ID {}: {}", id, e.getMessage());
            
            // Create a basic entry with just the ID
            BinaryId bid;
            if (id instanceof BinaryId) {
                bid = (BinaryId) id;
            } else {
                byte[] idBytes = KVTIdUtil.idToBytes(this.type, id);
                bid = new BinaryId(idBytes, id);
            }
            return new BinaryBackendEntry(this.type, bid);
        }
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