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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.backend.tx.IsolationLevel;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Transaction wrapper for KVT operations.
 * Manages transaction lifecycle and coordinates with KVT native transactions.
 */
public class KVTTransaction {
    
    private static final Logger LOG = Log.logger(KVTTransaction.class);
    
    // Transaction state
    public enum State {
        CREATED,
        ACTIVE,
        COMMITTED,
        ROLLED_BACK,
        FAILED
    }
    
    private final long transactionId;
    private final IsolationLevel isolationLevel;
    private final boolean readOnly;
    private final AtomicBoolean closed;
    private volatile State state;
    private final long startTime;
    private final List<Operation> operations;
    private final List<Runnable> commitCallbacks;
    private final List<Runnable> rollbackCallbacks;
    
    // Statistics
    private final AtomicLong readCount;
    private final AtomicLong writeCount;
    private final AtomicLong deleteCount;
    
    public KVTTransaction(long transactionId, IsolationLevel isolationLevel, 
                         boolean readOnly) {
        this.transactionId = transactionId;
        this.isolationLevel = isolationLevel;
        this.readOnly = readOnly;
        this.closed = new AtomicBoolean(false);
        this.state = State.CREATED;
        this.startTime = System.currentTimeMillis();
        this.operations = new ArrayList<>();
        this.commitCallbacks = new ArrayList<>();
        this.rollbackCallbacks = new ArrayList<>();
        
        this.readCount = new AtomicLong(0);
        this.writeCount = new AtomicLong(0);
        this.deleteCount = new AtomicLong(0);
    }
    
    /**
     * Start the transaction
     */
    public void begin() {
        E.checkState(this.state == State.CREATED, 
                    "Transaction already started: %s", this.state);
        
        // Start KVT native transaction
        int result = KVTNative.startTransaction(this.transactionId);
        if (result != 0) {
            throw new BackendException("Failed to start transaction: %d", result);
        }
        
        this.state = State.ACTIVE;
        LOG.debug("Started transaction {}", this.transactionId);
    }
    
    /**
     * Commit the transaction
     */
    public void commit() {
        E.checkState(this.state == State.ACTIVE, 
                    "Cannot commit transaction in state: %s", this.state);
        
        if (this.readOnly && this.writeCount.get() > 0) {
            throw new BackendException("Read-only transaction has writes");
        }
        
        try {
            // Execute pre-commit callbacks
            for (Runnable callback : this.commitCallbacks) {
                callback.run();
            }
            
            // Commit KVT native transaction
            int result = KVTNative.commit(this.transactionId);
            if (result != 0) {
                throw new BackendException("Failed to commit transaction: %d", result);
            }
            
            this.state = State.COMMITTED;
            
            long duration = System.currentTimeMillis() - this.startTime;
            LOG.debug("Committed transaction {} (duration: {}ms, reads: {}, writes: {}, deletes: {})",
                     this.transactionId, duration, 
                     this.readCount.get(), this.writeCount.get(), this.deleteCount.get());
            
        } catch (Exception e) {
            this.state = State.FAILED;
            rollback();
            throw new BackendException("Transaction commit failed", e);
        } finally {
            close();
        }
    }
    
    /**
     * Rollback the transaction
     */
    public void rollback() {
        if (this.state != State.ACTIVE && this.state != State.FAILED) {
            LOG.warn("Cannot rollback transaction in state: {}", this.state);
            return;
        }
        
        try {
            // Execute rollback callbacks
            for (Runnable callback : this.rollbackCallbacks) {
                try {
                    callback.run();
                } catch (Exception e) {
                    LOG.error("Rollback callback failed", e);
                }
            }
            
            // Rollback KVT native transaction
            int result = KVTNative.rollback(this.transactionId);
            if (result != 0) {
                LOG.error("Failed to rollback transaction: {}", result);
            }
            
            this.state = State.ROLLED_BACK;
            LOG.debug("Rolled back transaction {}", this.transactionId);
            
        } finally {
            close();
        }
    }
    
    /**
     * Close the transaction
     */
    private void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.operations.clear();
            this.commitCallbacks.clear();
            this.rollbackCallbacks.clear();
        }
    }
    
    /**
     * Add an operation to the transaction log
     */
    public void addOperation(Operation op) {
        this.operations.add(op);
    }
    
    /**
     * Add a callback to execute on commit
     */
    public void onCommit(Runnable callback) {
        this.commitCallbacks.add(callback);
    }
    
    /**
     * Add a callback to execute on rollback
     */
    public void onRollback(Runnable callback) {
        this.rollbackCallbacks.add(callback);
    }
    
    /**
     * Record a read operation
     */
    public void recordRead() {
        this.readCount.incrementAndGet();
    }
    
    /**
     * Record a write operation
     */
    public void recordWrite() {
        E.checkState(!this.readOnly, "Cannot write in read-only transaction");
        this.writeCount.incrementAndGet();
    }
    
    /**
     * Record a delete operation
     */
    public void recordDelete() {
        E.checkState(!this.readOnly, "Cannot delete in read-only transaction");
        this.deleteCount.incrementAndGet();
    }
    
    // Getters
    public long getTransactionId() {
        return this.transactionId;
    }
    
    public IsolationLevel getIsolationLevel() {
        return this.isolationLevel;
    }
    
    public boolean isReadOnly() {
        return this.readOnly;
    }
    
    public State getState() {
        return this.state;
    }
    
    public long getStartTime() {
        return this.startTime;
    }
    
    public long getReadCount() {
        return this.readCount.get();
    }
    
    public long getWriteCount() {
        return this.writeCount.get();
    }
    
    public long getDeleteCount() {
        return this.deleteCount.get();
    }
    
    /**
     * Check if transaction is active
     */
    public boolean isActive() {
        return this.state == State.ACTIVE;
    }
    
    /**
     * Operation record for transaction log
     */
    public static class Operation {
        public enum Type {
            GET, SET, DELETE, SCAN
        }
        
        public final Type type;
        public final long tableId;
        public final byte[] key;
        public final byte[] value;
        public final long timestamp;
        
        public Operation(Type type, long tableId, byte[] key, byte[] value) {
            this.type = type;
            this.tableId = tableId;
            this.key = key;
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
    }
}