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

import java.util.List;
import java.util.ArrayList;

/**
 * JNI wrapper for KVT (Key-Value Transaction) native library.
 * This class provides Java bindings for the C++ KVT implementation.
 */
public class KVTNative {

    static {
        try {
            System.loadLibrary("kvtjni");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load kvtjni library: " + e.getMessage());
            throw e;
        }
    }

    /**
     * KVT Error Codes matching kvt_inc.h
     */
    public enum KVTError {
        SUCCESS(0),
        KVT_NOT_INITIALIZED(1),
        TABLE_ALREADY_EXISTS(2),
        TABLE_NOT_FOUND(3),
        INVALID_PARTITION_METHOD(4),
        TRANSACTION_NOT_FOUND(5),
        TRANSACTION_ALREADY_RUNNING(6),
        KEY_NOT_FOUND(7),
        KEY_IS_DELETED(8),
        KEY_IS_LOCKED(9),
        TRANSACTION_HAS_STALE_DATA(10),
        ONE_SHOT_WRITE_NOT_ALLOWED(11),
        ONE_SHOT_DELETE_NOT_ALLOWED(12),
        BATCH_NOT_FULLY_SUCCESS(13),
        UNKNOWN_ERROR(14);

        private final int code;

        KVTError(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static KVTError fromCode(int code) {
            for (KVTError error : values()) {
                if (error.code == code) {
                    return error;
                }
            }
            return UNKNOWN_ERROR;
        }
    }

    /**
     * Key-value pair
     */
    public static class KVTPair {
        public final byte[] key;
        public final byte[] value;
        
        public KVTPair(byte[] key, byte[] value) {
            this.key = key;
            this.value = value;
        }
    }

    /**
     * Operation type for batch operations
     */
    public enum OpType {
        OP_UNKNOWN(0),
        OP_GET(1),
        OP_SET(2),
        OP_DEL(3);

        private final int code;

        OpType(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    /**
     * Represents a single operation in a batch
     */
    public static class KVTOp {
        public OpType op;
        public long tableId;
        public byte[] key;
        public byte[] value;

        public KVTOp(OpType op, long tableId, byte[] key, byte[] value) {
            this.op = op;
            this.tableId = tableId;
            this.key = key;
            this.value = value;
        }
    }

    /**
     * Represents the result of a single operation in a batch
     */
    public static class KVTOpResult {
        public KVTError error;
        public byte[] value;

        public KVTOpResult(KVTError error, byte[] value) {
            this.error = error;
            this.value = value;
        }
    }

    /**
     * Result wrapper for operations that return both an error code and a value
     */
    public static class KVTResult<T> {
        public final KVTError error;
        public final T value;
        public final String errorMessage;

        public KVTResult(KVTError error, T value, String errorMessage) {
            this.error = error;
            this.value = value;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return error == KVTError.SUCCESS;
        }
    }

    // Native method declarations

    /**
     * Initialize the KVT system.
     * @return Error code
     */
    public static native int nativeInitialize();

    /**
     * Shutdown the KVT system.
     */
    public static native void nativeShutdown();

    /**
     * Create a new table.
     * @param tableName Name of the table
     * @param partitionMethod Partition method ("hash" or "range")
     * @return Array with [errorCode, tableId, errorMessage]
     */
    public static native Object[] nativeCreateTable(String tableName, String partitionMethod);

    /**
     * Drop a table.
     * @param tableId Table ID
     * @return Array with [errorCode, errorMessage]
     */
    public static native Object[] nativeDropTable(long tableId);

    /**
     * Get table name by ID.
     * @param tableId Table ID
     * @return Array with [errorCode, tableName, errorMessage]
     */
    public static native Object[] nativeGetTableName(long tableId);

    /**
     * Get table ID by name.
     * @param tableName Table name
     * @return Array with [errorCode, tableId, errorMessage]
     */
    public static native Object[] nativeGetTableId(String tableName);

    /**
     * List all tables.
     * @return Array with [errorCode, tableNames[], tableIds[], errorMessage]
     */
    public static native Object[] nativeListTables();

    /**
     * Start a transaction.
     * @return Array with [errorCode, transactionId, errorMessage]
     */
    public static native Object[] nativeStartTransaction();

    /**
     * Get a value.
     * @param txId Transaction ID (0 for auto-commit)
     * @param tableId Table ID
     * @param key Key bytes
     * @return Array with [errorCode, valueBytes, errorMessage]
     */
    public static native Object[] nativeGet(long txId, long tableId, byte[] key);

    /**
     * Set a key-value pair.
     * @param txId Transaction ID (0 for auto-commit)
     * @param tableId Table ID
     * @param key Key bytes
     * @param value Value bytes
     * @return Array with [errorCode, errorMessage]
     */
    public static native Object[] nativeSet(long txId, long tableId, byte[] key, byte[] value);

    /**
     * Delete a key.
     * @param txId Transaction ID (0 for auto-commit)
     * @param tableId Table ID
     * @param key Key bytes
     * @return Array with [errorCode, errorMessage]
     */
    public static native Object[] nativeDel(long txId, long tableId, byte[] key);

    /**
     * Scan a range of keys.
     * @param txId Transaction ID (0 for auto-commit)
     * @param tableId Table ID
     * @param keyStart Start key (inclusive)
     * @param keyEnd End key (exclusive)
     * @param limit Maximum number of results
     * @return Array with [errorCode, keys[], values[], errorMessage]
     */
    public static native Object[] nativeScan(long txId, long tableId, 
                                            byte[] keyStart, byte[] keyEnd, int limit);

    /**
     * Execute a batch of operations.
     * @param txId Transaction ID (0 for auto-commit)
     * @param opTypes Array of operation types
     * @param tableIds Array of table IDs
     * @param keys Array of keys
     * @param values Array of values
     * @return Array with [errorCode, resultCodes[], resultValues[], errorMessage]
     */
    public static native Object[] nativeBatchExecute(long txId, int[] opTypes, 
                                                    long[] tableIds, byte[][] keys, byte[][] values);

    /**
     * Commit a transaction.
     * @param txId Transaction ID
     * @return Array with [errorCode, errorMessage]
     */
    public static native Object[] nativeCommitTransaction(long txId);

    /**
     * Rollback a transaction.
     * @param txId Transaction ID
     * @return Array with [errorCode, errorMessage]
     */
    public static native Object[] nativeRollbackTransaction(long txId);

    // Java wrapper methods for easier usage

    public static KVTError initialize() {
        int code = nativeInitialize();
        return KVTError.fromCode(code);
    }

    public static void shutdown() {
        nativeShutdown();
    }

    public static KVTResult<Long> createTable(String tableName, String partitionMethod) {
        Object[] result = nativeCreateTable(tableName, partitionMethod);
        return new KVTResult<>(
            KVTError.fromCode((Integer) result[0]),
            (Long) result[1],
            (String) result[2]
        );
    }

    public static KVTResult<Void> dropTable(long tableId) {
        Object[] result = nativeDropTable(tableId);
        return new KVTResult<>(
            KVTError.fromCode((Integer) result[0]),
            null,
            (String) result[1]
        );
    }

    public static KVTResult<Long> startTransaction() {
        Object[] result = nativeStartTransaction();
        return new KVTResult<>(
            KVTError.fromCode((Integer) result[0]),
            (Long) result[1],
            (String) result[2]
        );
    }

    public static KVTResult<byte[]> get(long txId, long tableId, byte[] key) {
        Object[] result = nativeGet(txId, tableId, key);
        return new KVTResult<>(
            KVTError.fromCode((Integer) result[0]),
            (byte[]) result[1],
            (String) result[2]
        );
    }

    public static KVTResult<Void> set(long txId, long tableId, byte[] key, byte[] value) {
        Object[] result = nativeSet(txId, tableId, key, value);
        return new KVTResult<>(
            KVTError.fromCode((Integer) result[0]),
            null,
            (String) result[1]
        );
    }

    public static KVTResult<Void> del(long txId, long tableId, byte[] key) {
        Object[] result = nativeDel(txId, tableId, key);
        return new KVTResult<>(
            KVTError.fromCode((Integer) result[0]),
            null,
            (String) result[1]
        );
    }

    public static KVTResult<Void> commitTransaction(long txId) {
        Object[] result = nativeCommitTransaction(txId);
        return new KVTResult<>(
            KVTError.fromCode((Integer) result[0]),
            null,
            (String) result[1]
        );
    }

    public static KVTResult<Void> rollbackTransaction(long txId) {
        Object[] result = nativeRollbackTransaction(txId);
        return new KVTResult<>(
            KVTError.fromCode((Integer) result[0]),
            null,
            (String) result[1]
        );
    }
}