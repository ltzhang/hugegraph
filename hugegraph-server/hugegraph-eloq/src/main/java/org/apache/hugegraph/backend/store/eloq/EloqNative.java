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

package org.apache.hugegraph.backend.store.eloq;

import org.apache.hugegraph.backend.BackendException;

/**
 * JNI bridge to EloqRocks C++ library.
 *
 * The C++ side manages a singleton RocksService instance with the full
 * DataSubstrate lifecycle (Init → EnableEngine → RocksService.Init →
 * DataSubstrate.Start → RocksService.Start).
 *
 * Table handles are managed on the C++ side. Java passes table names
 * as strings; the C++ side looks up or creates TableHandle objects.
 *
 * Transaction handles are opaque longs (C++ pointer cast to jlong).
 * Pass 0L for auto-commit (single-operation transactions).
 */
public final class EloqNative {

    private static boolean loaded = false;

    static {
        try {
            System.loadLibrary("eloqjni");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load eloqjni native library: " +
                               e.getMessage());
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    // ---- Lifecycle ----

    /**
     * Initialize EloqRocks: DataSubstrate::Init → EnableEngine →
     * RocksService::Init → DataSubstrate::Start → RocksService::Start.
     *
     * @param configPath path to config file, or empty string for defaults
     * @return true on success
     */
    public static native boolean nativeInit(String configPath);

    /**
     * Shut down EloqRocks: RocksService::Stop → DataSubstrate::Shutdown.
     */
    public static native void nativeShutdown();

    // ---- Table Management ----

    /**
     * Create a new table.
     *
     * @param name user-facing table name
     * @return true if table was created (or already exists)
     */
    public static native boolean nativeCreateTable(String name);

    /**
     * Drop a table.
     *
     * @param name user-facing table name
     * @return true if table was dropped
     */
    public static native boolean nativeDropTable(String name);

    /**
     * Check whether a table exists.
     *
     * @param name user-facing table name
     * @return true if the table exists
     */
    public static native boolean nativeHasTable(String name);

    // ---- Transaction Management ----

    /**
     * Start a new transaction.
     *
     * @return opaque transaction handle (C++ pointer as long), or 0 on failure
     */
    public static native long nativeStartTx();

    /**
     * Commit a transaction.
     *
     * @param txHandle handle from nativeStartTx()
     * @return true if commit succeeded
     */
    public static native boolean nativeCommitTx(long txHandle);

    /**
     * Abort a transaction.
     *
     * @param txHandle handle from nativeStartTx()
     * @return true if abort succeeded
     */
    public static native boolean nativeAbortTx(long txHandle);

    // ---- Data Operations ----

    /**
     * Put a key-value pair into a table.
     *
     * @param txHandle transaction handle (0 = auto-commit)
     * @param table    table name
     * @param key      key bytes
     * @param value    value bytes
     * @return true on success
     */
    public static native boolean nativePut(long txHandle, String table,
                                           byte[] key, byte[] value);

    /**
     * Get the value for a key.
     *
     * @param txHandle transaction handle (0 = auto-commit)
     * @param table    table name
     * @param key      key bytes
     * @return value bytes, or null if key not found
     */
    public static native byte[] nativeGet(long txHandle, String table,
                                          byte[] key);

    /**
     * Delete a key.
     *
     * @param txHandle transaction handle (0 = auto-commit)
     * @param table    table name
     * @param key      key bytes
     * @return true on success
     */
    public static native boolean nativeDelete(long txHandle, String table,
                                              byte[] key);

    /**
     * Scan a range of keys in a table.
     *
     * Returns a 2D array: result[0] = keys array, result[1] = values array.
     * Each keys[i] / values[i] is a byte[].
     *
     * @param txHandle       transaction handle (0 = auto-commit)
     * @param table          table name
     * @param startKey       start of range (null = negative infinity)
     * @param endKey         end of range (null = positive infinity)
     * @param startInclusive whether start boundary is inclusive
     * @param endInclusive   whether end boundary is inclusive
     * @param limit          max results (0 = no limit)
     * @return byte[2][][] where [0] = keys, [1] = values; or null on error
     */
    public static native byte[][][] nativeScan(long txHandle, String table,
                                               byte[] startKey, byte[] endKey,
                                               boolean startInclusive,
                                               boolean endInclusive,
                                               int limit);

    // ---- Java convenience methods ----

    public static void init(String configPath) {
        checkLoaded();
        if (!nativeInit(configPath != null ? configPath : "")) {
            throw new BackendException("Failed to initialize EloqRocks");
        }
    }

    public static void shutdown() {
        checkLoaded();
        nativeShutdown();
    }

    public static void createTable(String name) {
        checkLoaded();
        if (!nativeCreateTable(name)) {
            throw new BackendException(
                "Failed to create EloqRocks table: " + name);
        }
    }

    public static void dropTable(String name) {
        checkLoaded();
        if (!nativeDropTable(name)) {
            throw new BackendException(
                "Failed to drop EloqRocks table: " + name);
        }
    }

    public static boolean hasTable(String name) {
        checkLoaded();
        return nativeHasTable(name);
    }

    public static long startTx() {
        checkLoaded();
        long handle = nativeStartTx();
        if (handle == 0L) {
            throw new BackendException("Failed to start EloqRocks transaction");
        }
        return handle;
    }

    public static void commitTx(long txHandle) {
        checkLoaded();
        if (!nativeCommitTx(txHandle)) {
            throw new BackendException(
                "Failed to commit EloqRocks transaction");
        }
    }

    public static void abortTx(long txHandle) {
        checkLoaded();
        if (!nativeAbortTx(txHandle)) {
            throw new BackendException(
                "Failed to abort EloqRocks transaction");
        }
    }

    public static void put(long txHandle, String table,
                           byte[] key, byte[] value) {
        checkLoaded();
        if (!nativePut(txHandle, table, key, value)) {
            throw new BackendException("EloqRocks put failed on table: " +
                                       table);
        }
    }

    public static byte[] get(long txHandle, String table, byte[] key) {
        checkLoaded();
        return nativeGet(txHandle, table, key);
    }

    public static void delete(long txHandle, String table, byte[] key) {
        checkLoaded();
        if (!nativeDelete(txHandle, table, key)) {
            throw new BackendException("EloqRocks delete failed on table: " +
                                       table);
        }
    }

    public static byte[][][] scan(long txHandle, String table,
                                  byte[] startKey, byte[] endKey,
                                  boolean startInclusive,
                                  boolean endInclusive,
                                  int limit) {
        checkLoaded();
        return nativeScan(txHandle, table, startKey, endKey,
                          startInclusive, endInclusive, limit);
    }

    private static void checkLoaded() {
        if (!loaded) {
            throw new BackendException(
                "EloqRocks native library (libeloqjni.so) not loaded. " +
                "Ensure it is built and java.library.path is set correctly.");
        }
    }

    private EloqNative() {
    }
}
