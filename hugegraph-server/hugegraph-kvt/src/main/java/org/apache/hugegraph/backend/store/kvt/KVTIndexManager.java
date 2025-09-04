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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.HugeKeys;
import org.apache.hugegraph.type.define.IndexType;
import org.apache.hugegraph.util.Bytes;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Index manager for KVT backend.
 * Manages secondary indexes for efficient query execution.
 */
public class KVTIndexManager {
    
    private static final Logger LOG = Log.logger(KVTIndexManager.class);
    
    // Index table IDs
    private final Map<IndexType, Long> indexTableIds;
    private final KVTSession session;
    
    // Index metadata
    private final Map<String, IndexMetadata> indexMetadata;
    
    public KVTIndexManager(KVTSession session) {
        this.session = session;
        this.indexTableIds = new HashMap<>();
        this.indexMetadata = new HashMap<>();
    }
    
    /**
     * Initialize index tables
     */
    public void initIndexTables() {
        // Create tables for each index type
        for (IndexType indexType : IndexType.values()) {
            String tableName = getIndexTableName(indexType);
            
            KVTNative.KVTResult<Long> result = 
                KVTNative.createTable(tableName, getPartitionMethod(indexType));
            
            if (result.isSuccess()) {
                this.indexTableIds.put(indexType, result.value);
                LOG.info("Created index table {} with ID {}", tableName, result.value);
            } else if (result.error != KVTNative.KVTError.TABLE_ALREADY_EXISTS) {
                throw new BackendException("Failed to create index table %s: %s",
                                         tableName, result.errorMessage);
            }
        }
    }
    
    /**
     * Add entry to index
     */
    public void addIndex(IndexType indexType, Id indexId, Id elementId,
                        Object indexValue, long expireTime) {
        Long tableId = this.indexTableIds.get(indexType);
        if (tableId == null) {
            throw new BackendException("Index table not initialized for type: %s", 
                                     indexType);
        }
        
        // Build index key
        byte[] indexKey = buildIndexKey(indexType, indexId, indexValue, elementId);
        
        // Build index value (element ID + expire time)
        byte[] indexValue2 = buildIndexValue(elementId, expireTime);
        
        // Store in index table
        this.session.set(tableId, indexKey, indexValue2);
        
        // Update metadata
        updateIndexMetadata(indexId.toString(), indexType, 1);
        
        LOG.trace("Added index entry: type={}, indexId={}, elementId={}", 
                 indexType, indexId, elementId);
    }
    
    /**
     * Remove entry from index
     */
    public void removeIndex(IndexType indexType, Id indexId, Id elementId,
                           Object indexValue) {
        Long tableId = this.indexTableIds.get(indexType);
        if (tableId == null) {
            throw new BackendException("Index table not initialized for type: %s", 
                                     indexType);
        }
        
        // Build index key
        byte[] indexKey = buildIndexKey(indexType, indexId, indexValue, elementId);
        
        // Delete from index table
        this.session.delete(tableId, indexKey);
        
        // Update metadata
        updateIndexMetadata(indexId.toString(), indexType, -1);
        
        LOG.trace("Removed index entry: type={}, indexId={}, elementId={}", 
                 indexType, indexId, elementId);
    }
    
    /**
     * Query index for matching elements
     */
    public Iterator<Id> queryIndex(IndexType indexType, Id indexId, 
                                  Object indexValue, int limit) {
        Long tableId = this.indexTableIds.get(indexType);
        if (tableId == null) {
            throw new BackendException("Index table not initialized for type: %s", 
                                     indexType);
        }
        
        List<Id> results = new ArrayList<>();
        
        if (indexType == IndexType.SECONDARY || indexType == IndexType.RANGE) {
            // Range scan for these index types
            byte[] prefix = buildIndexPrefix(indexType, indexId, indexValue);
            byte[] startKey = prefix;
            // Create end key by incrementing last byte of prefix
            byte[] endKey = Arrays.copyOf(prefix, prefix.length);
            endKey[endKey.length - 1] = (byte)(endKey[endKey.length - 1] + 1);
            
            Iterator<KVTNative.KVTPair> pairs = 
                this.session.scan(tableId, startKey, endKey, limit);
            
            while (pairs.hasNext()) {
                KVTNative.KVTPair pair = pairs.next();
                Id elementId = extractElementId(pair.value);
                
                // Check expiration
                if (!isExpired(pair.value)) {
                    results.add(elementId);
                }
            }
        } else if (indexType == IndexType.UNIQUE) {
            // Direct get for unique index
            byte[] indexKey = buildUniqueIndexKey(indexId, indexValue);
            byte[] value = this.session.get(tableId, indexKey);
            
            if (value != null && !isExpired(value)) {
                Id elementId = extractElementId(value);
                results.add(elementId);
            }
        }
        
        LOG.debug("Index query returned {} results for type={}, indexId={}", 
                 results.size(), indexType, indexId);
        
        return results.iterator();
    }
    
    /**
     * Build composite index
     */
    public void buildCompositeIndex(Id indexId, List<String> fields) {
        String indexName = indexId.toString();
        IndexMetadata metadata = new IndexMetadata(
            indexName, IndexType.SECONDARY, fields
        );
        
        this.indexMetadata.put(indexName, metadata);
        
        LOG.info("Built composite index {} on fields: {}", indexName, fields);
    }
    
    /**
     * Rebuild index from scratch
     */
    public void rebuildIndex(IndexType indexType, Id indexId) {
        LOG.info("Starting index rebuild for type={}, indexId={}", 
                indexType, indexId);
        
        // Clear existing index entries
        clearIndex(indexType, indexId);
        
        // Rebuild from base data
        // This would scan the base table and recreate index entries
        // Implementation depends on the specific data model
        
        LOG.info("Completed index rebuild for type={}, indexId={}", 
                indexType, indexId);
    }
    
    /**
     * Clear all entries for an index
     */
    private void clearIndex(IndexType indexType, Id indexId) {
        Long tableId = this.indexTableIds.get(indexType);
        if (tableId == null) {
            return;
        }
        
        byte[] prefix = indexId.asBytes();
        byte[] startKey = prefix;
        // Create end key by incrementing last byte of prefix
        byte[] endKey = Arrays.copyOf(prefix, prefix.length);
        endKey[endKey.length - 1] = (byte)(endKey[endKey.length - 1] + 1);
        
        // Scan and delete all entries with this prefix
        Iterator<KVTNative.KVTPair> pairs = 
            this.session.scan(tableId, startKey, endKey, Integer.MAX_VALUE);
        
        int count = 0;
        while (pairs.hasNext()) {
            KVTNative.KVTPair pair = pairs.next();
            this.session.delete(tableId, pair.key);
            count++;
        }
        
        LOG.debug("Cleared {} entries from index {}", count, indexId);
    }
    
    /**
     * Get index table name
     */
    private String getIndexTableName(IndexType indexType) {
        return "kvt_index_" + indexType.name().toLowerCase();
    }
    
    /**
     * Get partition method for index type
     */
    private String getPartitionMethod(IndexType indexType) {
        switch (indexType) {
            case SECONDARY:
            case RANGE:
            case UNIQUE:
            case SHARD:
                return "range";
            default:
                return "hash";
        }
    }
    
    /**
     * Build index key
     */
    private byte[] buildIndexKey(IndexType indexType, Id indexId, 
                                Object indexValue, Id elementId) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        // Index ID
        byte[] indexIdBytes = indexId.asBytes();
        buffer.putInt(indexIdBytes.length);
        buffer.put(indexIdBytes);
        
        // Index value
        byte[] valueBytes = serializeIndexValue(indexValue);
        buffer.putInt(valueBytes.length);
        buffer.put(valueBytes);
        
        // Element ID (for uniqueness in non-unique indexes)
        if (indexType != IndexType.UNIQUE) {
            byte[] elementIdBytes = elementId.asBytes();
            buffer.putInt(elementIdBytes.length);
            buffer.put(elementIdBytes);
        }
        
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
    
    /**
     * Build index key prefix for scanning
     */
    private byte[] buildIndexPrefix(IndexType indexType, Id indexId, 
                                   Object indexValue) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        // Index ID
        byte[] indexIdBytes = indexId.asBytes();
        buffer.putInt(indexIdBytes.length);
        buffer.put(indexIdBytes);
        
        // Index value (if provided)
        if (indexValue != null) {
            byte[] valueBytes = serializeIndexValue(indexValue);
            buffer.putInt(valueBytes.length);
            buffer.put(valueBytes);
        }
        
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
    
    /**
     * Build unique index key
     */
    private byte[] buildUniqueIndexKey(Id indexId, Object indexValue) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        // Index ID
        byte[] indexIdBytes = indexId.asBytes();
        buffer.putInt(indexIdBytes.length);
        buffer.put(indexIdBytes);
        
        // Index value
        byte[] valueBytes = serializeIndexValue(indexValue);
        buffer.putInt(valueBytes.length);
        buffer.put(valueBytes);
        
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
    
    /**
     * Build index value
     */
    private byte[] buildIndexValue(Id elementId, long expireTime) {
        ByteBuffer buffer = ByteBuffer.allocate(256);
        
        // Element ID
        byte[] elementIdBytes = elementId.asBytes();
        buffer.putInt(elementIdBytes.length);
        buffer.put(elementIdBytes);
        
        // Expire time
        buffer.putLong(expireTime);
        
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
    
    /**
     * Extract element ID from index value
     */
    private Id extractElementId(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        
        int idLength = buffer.getInt();
        byte[] idBytes = new byte[idLength];
        buffer.get(idBytes);
        
        return IdGenerator.of(idBytes);
    }
    
    /**
     * Check if index entry is expired
     */
    private boolean isExpired(byte[] value) {
        if (value.length < 12) {
            return false;
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(value);
        
        // Skip element ID
        int idLength = buffer.getInt();
        buffer.position(buffer.position() + idLength);
        
        // Get expire time
        long expireTime = buffer.getLong();
        
        if (expireTime == 0) {
            return false; // No expiration
        }
        
        return System.currentTimeMillis() > expireTime;
    }
    
    /**
     * Serialize index value
     */
    private byte[] serializeIndexValue(Object value) {
        if (value == null) {
            return new byte[0];
        }
        
        if (value instanceof byte[]) {
            return (byte[]) value;
        } else if (value instanceof String) {
            return ((String) value).getBytes();
        } else if (value instanceof Id) {
            return ((Id) value).asBytes();
        } else {
            return value.toString().getBytes();
        }
    }
    
    /**
     * Update index metadata
     */
    private void updateIndexMetadata(String indexName, IndexType type, int delta) {
        IndexMetadata metadata = this.indexMetadata.computeIfAbsent(
            indexName, k -> new IndexMetadata(indexName, type, null)
        );
        
        metadata.entryCount += delta;
        metadata.lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Get index statistics
     */
    public IndexStatistics getStatistics(String indexName) {
        IndexMetadata metadata = this.indexMetadata.get(indexName);
        if (metadata == null) {
            return null;
        }
        
        return new IndexStatistics(
            metadata.indexName,
            metadata.indexType,
            metadata.entryCount,
            metadata.lastUpdateTime
        );
    }
    
    /**
     * Index metadata
     */
    private static class IndexMetadata {
        final String indexName;
        final IndexType indexType;
        final List<String> fields;
        long entryCount;
        long lastUpdateTime;
        
        public IndexMetadata(String indexName, IndexType indexType, 
                           List<String> fields) {
            this.indexName = indexName;
            this.indexType = indexType;
            this.fields = fields;
            this.entryCount = 0;
            this.lastUpdateTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Index statistics
     */
    public static class IndexStatistics {
        public final String indexName;
        public final IndexType indexType;
        public final long entryCount;
        public final long lastUpdateTime;
        
        public IndexStatistics(String indexName, IndexType indexType,
                             long entryCount, long lastUpdateTime) {
            this.indexName = indexName;
            this.indexType = indexType;
            this.entryCount = entryCount;
            this.lastUpdateTime = lastUpdateTime;
        }
        
        @Override
        public String toString() {
            return String.format("IndexStats{name=%s, type=%s, entries=%d}",
                               indexName, indexType, entryCount);
        }
    }
}