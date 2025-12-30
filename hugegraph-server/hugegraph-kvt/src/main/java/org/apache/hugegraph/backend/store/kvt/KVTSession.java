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
import org.apache.hugegraph.backend.store.BackendSession;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * KVT session for managing transactions.
 * Each session corresponds to a transaction context in KVT.
 */
public class KVTSession extends BackendSession.AbstractBackendSession {

    private static final Logger LOG = Log.logger(KVTSession.class);
    
    private final HugeConfig config;
    private final String database;
    
    // Current transaction ID (0 means no active transaction)
    private long transactionId;
    private boolean autoCommit;
    private int txCount;
    
    public KVTSession(HugeConfig config, String database) {
        this.config = config;
        this.database = database;
        this.transactionId = 0;
        this.autoCommit = true;
        this.txCount = 0;
    }
    
    public String database() {
        return this.database;
    }
    
    public long transactionId() {
        return this.transactionId;
    }
    
    public boolean autoCommit() {
        return this.autoCommit;
    }
    
    public void autoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }
    
    public void beginTx() {
        if (this.transactionId != 0) {
            throw new BackendException("Transaction already started: %s", 
                                     this.transactionId);
        }
        
        KVTNative.KVTResult<Long> result = KVTNative.startTransaction();
        if (result.error != KVTNative.KVTError.SUCCESS) {
            throw new BackendException("Failed to start transaction: %s",
                                     result.errorMessage);
        }
        
        this.transactionId = result.value;
        this.autoCommit = false;
        this.txCount++;
        
        LOG.debug("Started KVT transaction {} (count: {})", 
                 this.transactionId, this.txCount);
    }
    
    public void commitTx() {
        if (this.transactionId == 0) {
            LOG.debug("No active transaction to commit");
            return;
        }
        
        KVTNative.KVTResult<Void> result = 
            KVTNative.commitTransaction(this.transactionId);
        
        if (result.error != KVTNative.KVTError.SUCCESS) {
            throw new BackendException("Failed to commit transaction %s: %s",
                                     this.transactionId, result.errorMessage);
        }
        
        LOG.debug("Committed KVT transaction {}", this.transactionId);
        
        this.transactionId = 0;
        this.autoCommit = true;
    }
    
    public void rollbackTx() {
        if (this.transactionId == 0) {
            LOG.debug("No active transaction to rollback");
            return;
        }
        
        KVTNative.KVTResult<Void> result = 
            KVTNative.rollbackTransaction(this.transactionId);
        
        if (result.error != KVTNative.KVTError.SUCCESS) {
            LOG.warn("Failed to rollback transaction {}: {}", 
                    this.transactionId, result.errorMessage);
        } else {
            LOG.debug("Rolled back KVT transaction {}", this.transactionId);
        }
        
        this.transactionId = 0;
        this.autoCommit = true;
    }
    
    /**
     * Execute a KVT operation, handling auto-commit if needed
     */
    public <T> T execute(KVTOperation<T> operation) {
        boolean needCommit = false;
        
        if (this.autoCommit) {
            // Start a new transaction for this operation
            this.beginTx();
            needCommit = true;
        }
        
        try {
            T result = operation.execute(this.transactionId);
            
            if (needCommit) {
                this.commitTx();
            }
            
            return result;
        } catch (Exception e) {
            if (needCommit) {
                this.rollbackTx();
            }
            throw e;
        }
    }
    
    /**
     * Get a value from KVT
     */
    public byte[] get(long tableId, byte[] key) {
        KVTNative.KVTResult<byte[]> result = 
            KVTNative.get(this.transactionId, tableId, key);
        
        if (result.error == KVTNative.KVTError.KEY_NOT_FOUND) {
            return null;
        } else if (result.error != KVTNative.KVTError.SUCCESS) {
            throw new BackendException("Failed to get key: %s", 
                                     result.errorMessage);
        }
        return result.value;
    }
    
    /**
     * Set a key-value pair in KVT
     */
    public void set(long tableId, byte[] key, byte[] value) {
        E.checkNotNull(key, "key");
        E.checkNotNull(value, "value");

        KVTNative.KVTResult<Void> result = 
            KVTNative.set(this.transactionId, tableId, key, value);
        
        if (result.error != KVTNative.KVTError.SUCCESS) {
            throw new BackendException("Failed to set key: %s", 
                                     result.errorMessage);
        }
    }
    
    /**
     * Delete a key from KVT
     */
    public void delete(long tableId, byte[] key) {
        E.checkNotNull(key, "key");
        
        KVTNative.KVTResult<Void> result = 
            KVTNative.del(this.transactionId, tableId, key);
        
        if (result.error != KVTNative.KVTError.SUCCESS && 
            result.error != KVTNative.KVTError.KEY_NOT_FOUND) {
            throw new BackendException("Failed to delete key: %s", 
                                     result.errorMessage);
        }
    }
    
    /**
     * Update a vertex property atomically
     */
    public void updateVertexProperty(long tableId, byte[] key, byte[] propertyUpdate) {
        E.checkNotNull(key, "key");
        E.checkNotNull(propertyUpdate, "propertyUpdate");
        
        KVTNative.KVTResult<byte[]> result = 
            KVTNative.updateVertexProperty(this.transactionId, tableId, key, propertyUpdate);
        
        if (result.error != KVTNative.KVTError.SUCCESS) {
            throw new BackendException("Failed to update vertex property: %s", 
                                     result.errorMessage);
        }
    }
    
    /**
     * Update an edge property atomically
     */
    public void updateEdgeProperty(long tableId, byte[] key, byte[] propertyUpdate) {
        E.checkNotNull(key, "key");
        E.checkNotNull(propertyUpdate, "propertyUpdate");
        
        KVTNative.KVTResult<byte[]> result = 
            KVTNative.updateEdgeProperty(this.transactionId, tableId, key, propertyUpdate);
        
        if (result.error != KVTNative.KVTError.SUCCESS) {
            throw new BackendException("Failed to update edge property: %s", 
                                     result.errorMessage);
        }
    }

    /**
     * Scan a range of keys
     */
    public Iterator<KVTNative.KVTPair> scan(long tableId, 
                                           byte[] startKey, 
                                           byte[] endKey,
                                           int limit) {
        Object[] result = KVTNative.nativeScan(this.transactionId, tableId, 
                                              startKey, endKey, limit);
        
        int errorCode = (Integer) result[0];
        if (errorCode != KVTNative.KVTError.SUCCESS.getCode()) {
            String errorMsg = (String) result[3];
            throw new BackendException("Failed to scan: %s", errorMsg);
        }
        
        byte[][] keys = (byte[][]) result[1];
        byte[][] values = (byte[][]) result[2];
        
        List<KVTNative.KVTPair> pairs = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            pairs.add(new KVTNative.KVTPair(keys[i], values[i]));
        }
        
        return pairs.iterator();
    }

    /**
     * Batch get multiple keys from a table using native batchGet
     */
    public byte[][] batchGet(long tableId, byte[][] keys) {
        E.checkNotNull(keys, "keys");
        KVTNative.KVTResult<byte[][]> result =
                KVTNative.batchGet(this.transactionId, tableId, keys);
        if (result.error != KVTNative.KVTError.SUCCESS &&
            result.error != KVTNative.KVTError.BATCH_NOT_FULLY_SUCCESS) {
            throw new BackendException("Failed to batchGet: %s", result.errorMessage);
        }
        return result.value;
    }
    
    @Override
    public void open() {
        this.opened = true;
    }
    
    @Override
    public void close() {
        if (this.transactionId != 0) {
            LOG.warn("Closing session with active transaction {}, rolling back",
                    this.transactionId);
            this.rollbackTx();
        }
        this.opened = false;
    }
    
    @Override
    public Object commit() {
        this.commitTx();
        return null;
    }
    
    @Override
    public void rollback() {
        this.rollbackTx();
    }
    
    @Override
    public boolean hasChanges() {
        return this.transactionId != 0;
    }
    
    /**
     * Functional interface for KVT operations
     */
    @FunctionalInterface
    public interface KVTOperation<T> {
        T execute(long transactionId);
    }
    
    // Helper methods for debugging
    private static String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, bytes.length);
    }
    
    private static String bytesToHex(byte[] bytes, int maxLen) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        int len = Math.min(bytes.length, maxLen);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X", bytes[i]));
            if (i < len - 1) sb.append(" ");
        }
        if (bytes.length > maxLen) {
            sb.append("...");
        }
        return sb.toString();
    }
    
    private static String tryAsString(byte[] bytes) {
        if (bytes == null) return "null";
        try {
            String str = new String(bytes, "UTF-8");
            // Replace non-printable chars
            return str.replaceAll("[\\p{Cntrl}]", "?");
        } catch (Exception e) {
            return "<not a string>";
        }
    }
}
