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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Batch operations handler for KVT.
 * Accumulates operations and executes them efficiently in batches.
 */
public class KVTBatch {
    
    private static final Logger LOG = Log.logger(KVTBatch.class);
    
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int MAX_BATCH_SIZE = 10000;
    
    // Operation types for batch execution
    private static final byte OP_SET = 1;
    private static final byte OP_DELETE = 2;
    
    private final long transactionId;
    private final int maxBatchSize;
    private final Map<Long, TableBatch> tableBatches;
    private int totalOperations;
    private boolean executed;
    
    public KVTBatch(long transactionId) {
        this(transactionId, DEFAULT_BATCH_SIZE);
    }
    
    public KVTBatch(long transactionId, int maxBatchSize) {
        E.checkArgument(maxBatchSize > 0 && maxBatchSize <= MAX_BATCH_SIZE,
                       "Batch size must be between 1 and %d", MAX_BATCH_SIZE);
        
        this.transactionId = transactionId;
        this.maxBatchSize = maxBatchSize;
        this.tableBatches = new HashMap<>();
        this.totalOperations = 0;
        this.executed = false;
    }
    
    /**
     * Add a set operation to the batch
     */
    public void set(long tableId, byte[] key, byte[] value) {
        E.checkState(!this.executed, "Batch already executed");
        E.checkNotNull(key, "key");
        E.checkNotNull(value, "value");
        
        TableBatch tableBatch = this.tableBatches.computeIfAbsent(
            tableId, k -> new TableBatch(tableId));
        
        tableBatch.addSet(key, value);
        this.totalOperations++;
        
        // Auto-execute if batch is full
        if (this.totalOperations >= this.maxBatchSize) {
            execute();
        }
    }
    
    /**
     * Add a delete operation to the batch
     */
    public void delete(long tableId, byte[] key) {
        E.checkState(!this.executed, "Batch already executed");
        E.checkNotNull(key, "key");
        
        TableBatch tableBatch = this.tableBatches.computeIfAbsent(
            tableId, k -> new TableBatch(tableId));
        
        tableBatch.addDelete(key);
        this.totalOperations++;
        
        // Auto-execute if batch is full
        if (this.totalOperations >= this.maxBatchSize) {
            execute();
        }
    }
    
    /**
     * Execute all batched operations
     */
    public void execute() {
        if (this.executed || this.totalOperations == 0) {
            return;
        }
        
        LOG.debug("Executing batch with {} operations across {} tables",
                 this.totalOperations, this.tableBatches.size());
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Execute batch for each table
            for (TableBatch tableBatch : this.tableBatches.values()) {
                executeBatch(tableBatch);
            }
            
            this.executed = true;
            
            long duration = System.currentTimeMillis() - startTime;
            LOG.debug("Batch executed successfully ({} operations in {}ms)",
                     this.totalOperations, duration);
            
        } catch (Exception e) {
            throw new BackendException("Batch execution failed", e);
        }
    }
    
    /**
     * Execute batch operations for a single table
     */
    private void executeBatch(TableBatch tableBatch) {
        if (tableBatch.operations.isEmpty()) {
            return;
        }
        
        // Serialize batch operations
        byte[] batchData = serializeBatch(tableBatch);
        
        // Call native batch execute
        int result = KVTNative.batchExecute(
            this.transactionId,
            tableBatch.tableId,
            batchData,
            tableBatch.operations.size()
        );
        
        if (result != 0) {
            throw new BackendException("Batch execution failed for table %d: %d",
                                     tableBatch.tableId, result);
        }
    }
    
    /**
     * Serialize batch operations to byte array
     */
    private byte[] serializeBatch(TableBatch tableBatch) {
        // Calculate total size needed
        int totalSize = 0;
        for (BatchOperation op : tableBatch.operations) {
            totalSize += 1; // operation type
            totalSize += 4; // key length
            totalSize += op.key.length;
            if (op.type == OP_SET) {
                totalSize += 4; // value length
                totalSize += op.value.length;
            }
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        
        // Serialize each operation
        for (BatchOperation op : tableBatch.operations) {
            buffer.put(op.type);
            buffer.putInt(op.key.length);
            buffer.put(op.key);
            
            if (op.type == OP_SET) {
                buffer.putInt(op.value.length);
                buffer.put(op.value);
            }
        }
        
        return buffer.array();
    }
    
    /**
     * Clear the batch
     */
    public void clear() {
        this.tableBatches.clear();
        this.totalOperations = 0;
        this.executed = false;
    }
    
    /**
     * Get the number of pending operations
     */
    public int size() {
        return this.totalOperations;
    }
    
    /**
     * Check if batch has been executed
     */
    public boolean isExecuted() {
        return this.executed;
    }
    
    /**
     * Batch operations for a single table
     */
    private static class TableBatch {
        final long tableId;
        final List<BatchOperation> operations;
        
        TableBatch(long tableId) {
            this.tableId = tableId;
            this.operations = new ArrayList<>();
        }
        
        void addSet(byte[] key, byte[] value) {
            operations.add(new BatchOperation(OP_SET, key, value));
        }
        
        void addDelete(byte[] key) {
            operations.add(new BatchOperation(OP_DELETE, key, null));
        }
    }
    
    /**
     * Single batch operation
     */
    private static class BatchOperation {
        final byte type;
        final byte[] key;
        final byte[] value;
        
        BatchOperation(byte type, byte[] key, byte[] value) {
            this.type = type;
            this.key = key;
            this.value = value;
        }
    }
    
    /**
     * Builder for creating batches with custom settings
     */
    public static class Builder {
        private long transactionId;
        private int maxBatchSize = DEFAULT_BATCH_SIZE;
        
        public Builder(long transactionId) {
            this.transactionId = transactionId;
        }
        
        public Builder maxBatchSize(int size) {
            this.maxBatchSize = size;
            return this;
        }
        
        public KVTBatch build() {
            return new KVTBatch(transactionId, maxBatchSize);
        }
    }
}