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
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.backend.store.BackendSession;
import org.apache.hugegraph.backend.tx.IsolationLevel;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Enhanced KVT session with better transaction and batch support.
 * This version integrates KVTTransaction and KVTBatch for improved performance.
 */
public class KVTSessionV2 extends BackendSession {

    private static final Logger LOG = Log.logger(KVTSessionV2.class);
    private static final AtomicLong TRANSACTION_COUNTER = new AtomicLong(1);
    
    private final HugeConfig config;
    private final String database;
    
    // Transaction management
    private KVTTransaction transaction;
    private IsolationLevel isolationLevel;
    private boolean readOnly;
    
    // Batch management
    private KVTBatch batch;
    private boolean batchEnabled;
    private int batchSize;
    
    // Statistics
    private long totalTransactions;
    private long totalOperations;
    
    public KVTSessionV2(HugeConfig config, String database) {
        this.config = config;
        this.database = database;
        
        this.transaction = null;
        this.isolationLevel = IsolationLevel.DEFAULT;
        this.readOnly = false;
        
        this.batch = null;
        this.batchEnabled = false;
        this.batchSize = 1000;
        
        this.totalTransactions = 0;
        this.totalOperations = 0;
    }
    
    /**
     * Begin a new transaction
     */
    public void beginTx() {
        beginTx(this.isolationLevel, this.readOnly);
    }
    
    /**
     * Begin a new transaction with specific settings
     */
    public void beginTx(IsolationLevel isolationLevel, boolean readOnly) {
        if (this.transaction != null && this.transaction.isActive()) {
            throw new BackendException("Transaction already active: %s", 
                                     this.transaction.getTransactionId());
        }
        
        // Create new transaction
        long txId = TRANSACTION_COUNTER.getAndIncrement();
        this.transaction = new KVTTransaction(txId, isolationLevel, readOnly);
        this.transaction.begin();
        
        // Create batch if enabled and not read-only
        if (this.batchEnabled && !readOnly) {
            this.batch = new KVTBatch.Builder(txId)
                .maxBatchSize(this.batchSize)
                .build();
        }
        
        this.totalTransactions++;
        
        LOG.debug("Started transaction {} (isolation: {}, readOnly: {})", 
                 txId, isolationLevel, readOnly);
    }
    
    /**
     * Commit the current transaction
     */
    public void commitTx() {
        if (this.transaction == null || !this.transaction.isActive()) {
            LOG.debug("No active transaction to commit");
            return;
        }
        
        try {
            // Execute any pending batch operations
            if (this.batch != null && !this.batch.isExecuted()) {
                this.batch.execute();
            }
            
            // Commit the transaction
            this.transaction.commit();
            
            LOG.debug("Committed transaction {} (reads: {}, writes: {}, deletes: {})",
                     this.transaction.getTransactionId(),
                     this.transaction.getReadCount(),
                     this.transaction.getWriteCount(),
                     this.transaction.getDeleteCount());
            
        } finally {
            this.transaction = null;
            this.batch = null;
        }
    }
    
    /**
     * Rollback the current transaction
     */
    public void rollbackTx() {
        if (this.transaction == null || !this.transaction.isActive()) {
            LOG.debug("No active transaction to rollback");
            return;
        }
        
        try {
            // Clear any pending batch operations
            if (this.batch != null) {
                this.batch.clear();
            }
            
            // Rollback the transaction
            this.transaction.rollback();
            
            LOG.debug("Rolled back transaction {}", 
                     this.transaction.getTransactionId());
            
        } finally {
            this.transaction = null;
            this.batch = null;
        }
    }
    
    /**
     * Get a value from KVT
     */
    public byte[] get(long tableId, byte[] key) {
        ensureTransaction();
        
        long txId = this.transaction.getTransactionId();
        KVTNative.KVTResult<byte[]> result = KVTNative.get(txId, tableId, key);
        
        this.transaction.recordRead();
        this.totalOperations++;
        
        if (result.error == KVTNative.KVTError.KEY_NOT_FOUND) {
            return null;
        } else if (result.error != KVTNative.KVTError.SUCCESS) {
            throw new BackendException("Failed to get key: %s", 
                                     result.errorMessage);
        }
        
        // Record operation for debugging
        this.transaction.addOperation(
            new KVTTransaction.Operation(
                KVTTransaction.Operation.Type.GET,
                tableId, key, null
            )
        );
        
        return result.value;
    }
    
    /**
     * Set a key-value pair in KVT
     */
    public void set(long tableId, byte[] key, byte[] value) {
        ensureTransaction();
        E.checkNotNull(key, "key");
        E.checkNotNull(value, "value");
        
        if (this.batch != null) {
            // Add to batch
            this.batch.set(tableId, key, value);
        } else {
            // Direct set
            long txId = this.transaction.getTransactionId();
            KVTNative.KVTResult<Void> result = 
                KVTNative.set(txId, tableId, key, value);
            
            if (result.error != KVTNative.KVTError.SUCCESS) {
                throw new BackendException("Failed to set key: %s", 
                                         result.errorMessage);
            }
        }
        
        this.transaction.recordWrite();
        this.totalOperations++;
        
        // Record operation for debugging
        this.transaction.addOperation(
            new KVTTransaction.Operation(
                KVTTransaction.Operation.Type.SET,
                tableId, key, value
            )
        );
    }
    
    /**
     * Delete a key from KVT
     */
    public void delete(long tableId, byte[] key) {
        ensureTransaction();
        E.checkNotNull(key, "key");
        
        if (this.batch != null) {
            // Add to batch
            this.batch.delete(tableId, key);
        } else {
            // Direct delete
            long txId = this.transaction.getTransactionId();
            KVTNative.KVTResult<Void> result = 
                KVTNative.del(txId, tableId, key);
            
            if (result.error != KVTNative.KVTError.SUCCESS && 
                result.error != KVTNative.KVTError.KEY_NOT_FOUND) {
                throw new BackendException("Failed to delete key: %s", 
                                         result.errorMessage);
            }
        }
        
        this.transaction.recordDelete();
        this.totalOperations++;
        
        // Record operation for debugging
        this.transaction.addOperation(
            new KVTTransaction.Operation(
                KVTTransaction.Operation.Type.DELETE,
                tableId, key, null
            )
        );
    }
    
    /**
     * Scan a range of keys
     */
    public Iterator<KVTNative.KVTPair> scan(long tableId, 
                                           byte[] startKey, 
                                           byte[] endKey,
                                           int limit) {
        ensureTransaction();
        
        long txId = this.transaction.getTransactionId();
        Object[] result = KVTNative.nativeScan(txId, tableId, 
                                              startKey, endKey, limit);
        
        this.transaction.recordRead();
        this.totalOperations++;
        
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
        
        // Record operation for debugging
        this.transaction.addOperation(
            new KVTTransaction.Operation(
                KVTTransaction.Operation.Type.SCAN,
                tableId, startKey, endKey
            )
        );
        
        return pairs.iterator();
    }
    
    /**
     * Enable batch mode
     */
    public void enableBatch(int batchSize) {
        this.batchEnabled = true;
        this.batchSize = batchSize;
        
        // Create batch for current transaction if active
        if (this.transaction != null && this.transaction.isActive() && 
            !this.transaction.isReadOnly()) {
            this.batch = new KVTBatch.Builder(this.transaction.getTransactionId())
                .maxBatchSize(batchSize)
                .build();
        }
    }
    
    /**
     * Disable batch mode
     */
    public void disableBatch() {
        this.batchEnabled = false;
        
        if (this.batch != null) {
            if (!this.batch.isExecuted() && this.batch.size() > 0) {
                this.batch.execute();
            }
            this.batch = null;
        }
    }
    
    /**
     * Flush pending batch operations
     */
    public void flushBatch() {
        if (this.batch != null && !this.batch.isExecuted() && this.batch.size() > 0) {
            this.batch.execute();
            
            // Create new batch for continued operations
            if (this.transaction != null && this.transaction.isActive()) {
                this.batch = new KVTBatch.Builder(this.transaction.getTransactionId())
                    .maxBatchSize(this.batchSize)
                    .build();
            }
        }
    }
    
    /**
     * Set isolation level for new transactions
     */
    public void setIsolationLevel(IsolationLevel level) {
        this.isolationLevel = level;
    }
    
    /**
     * Set read-only mode for new transactions
     */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
    
    /**
     * Ensure a transaction is active
     */
    private void ensureTransaction() {
        if (this.transaction == null || !this.transaction.isActive()) {
            // Auto-start transaction if not active
            beginTx();
        }
    }
    
    /**
     * Check if transaction is active
     */
    public boolean hasTransaction() {
        return this.transaction != null && this.transaction.isActive();
    }
    
    /**
     * Get transaction statistics
     */
    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Session Statistics:\n");
        sb.append("  Total transactions: ").append(this.totalTransactions).append("\n");
        sb.append("  Total operations: ").append(this.totalOperations).append("\n");
        
        if (this.transaction != null) {
            sb.append("  Active transaction: ").append(this.transaction.getTransactionId()).append("\n");
            sb.append("    Reads: ").append(this.transaction.getReadCount()).append("\n");
            sb.append("    Writes: ").append(this.transaction.getWriteCount()).append("\n");
            sb.append("    Deletes: ").append(this.transaction.getDeleteCount()).append("\n");
        }
        
        if (this.batch != null) {
            sb.append("  Batch size: ").append(this.batch.size()).append("\n");
            sb.append("  Batch executed: ").append(this.batch.isExecuted()).append("\n");
        }
        
        return sb.toString();
    }
    
    @Override
    public void close() {
        if (this.transaction != null && this.transaction.isActive()) {
            LOG.warn("Closing session with active transaction {}, rolling back",
                    this.transaction.getTransactionId());
            rollbackTx();
        }
    }
    
    @Override
    public boolean closed() {
        return false;  // Sessions are managed by pool
    }
    
    // Getters
    public String database() {
        return this.database;
    }
    
    public long transactionId() {
        return this.transaction != null ? this.transaction.getTransactionId() : 0;
    }
}