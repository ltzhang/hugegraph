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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumn;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumnIterator;
import org.apache.hugegraph.backend.store.BackendSession;
import org.apache.hugegraph.backend.store.BackendSessionPool;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.util.Bytes;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * EloqRocks session pool.
 *
 * Extends BackendSessionPool directly (no dependency on hugegraph-rocksdb).
 * The inner EloqSession buffers write operations in an ArrayList.
 * On commit(), it starts an EloqRocks transaction, replays all
 * buffered operations, and commits. Reads go through auto-commit (tx=0).
 */
public class EloqSessions extends BackendSessionPool {

    private static final Logger LOG = Log.logger(EloqSessions.class);

    private static final AtomicBoolean NATIVE_INITIALIZED = new AtomicBoolean(false);

    private final String database;
    private final String store;
    private final Set<String> openedTables;
    private boolean opened;

    public EloqSessions(HugeConfig config, String database, String store) {
        super(config, database + "/" + store);
        this.database = database;
        this.store = store;
        this.openedTables = new HashSet<>();
        this.opened = false;
    }

    @Override
    public void open() throws Exception {
        if (NATIVE_INITIALIZED.compareAndSet(false, true)) {
            EloqNative.init("");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                EloqNative.shutdown();
            }));
            LOG.info("EloqRocks native service initialized");
        }
        this.opened = true;
        LOG.debug("Opened EloqSessions for {}/{}", this.database, this.store);
    }

    @Override
    protected boolean opened() {
        return this.opened;
    }

    @Override
    public EloqSession session() {
        return (EloqSession) super.getOrNewSession();
    }

    @Override
    protected EloqSession newSession() {
        return new EloqSession();
    }

    @Override
    protected void doClose() {
        this.opened = false;
        LOG.debug("Closed EloqSessions for {}/{}", this.database, this.store);
    }

    // ---- Table management ----

    public Set<String> openedTables() {
        return this.openedTables;
    }

    public void createTable(String... tables) {
        for (String table : tables) {
            if (!EloqNative.hasTable(table)) {
                EloqNative.createTable(table);
            }
            this.openedTables.add(table);
        }
    }

    public void dropTable(String... tables) {
        for (String table : tables) {
            if (EloqNative.hasTable(table)) {
                EloqNative.dropTable(table);
            }
            this.openedTables.remove(table);
        }
    }

    public void clearTable(String... tables) {
        for (String table : tables) {
            if (EloqNative.hasTable(table)) {
                // Scan all keys and delete them within a transaction
                long tx = EloqNative.startTx();
                try {
                    byte[][][] results = EloqNative.scan(
                        tx, table, null, null, true, true, 0);
                    if (results != null && results[0] != null) {
                        for (byte[] key : results[0]) {
                            EloqNative.delete(tx, table, key);
                        }
                    }
                    EloqNative.commitTx(tx);
                } catch (Exception e) {
                    try {
                        EloqNative.abortTx(tx);
                    } catch (Exception abortEx) {
                        LOG.warn("Failed to abort tx in clearTable()", abortEx);
                    }
                    throw new BackendException(
                            "Failed to clear table '%s'", e, table);
                }
            }
        }
    }

    public boolean existsTable(String table) {
        return EloqNative.hasTable(table);
    }

    // =====================================================
    // Inner Session class
    // =====================================================

    /**
     * Concrete session backed by EloqRocks JNI bridge.
     *
     * Scan type constants define how range scans interpret their
     * start/end keys. These are bitmask flags combined with OR.
     */
    public static class EloqSession extends BackendSession.AbstractBackendSession {

        // ---- Scan type constants (self-contained, no rocksdb dependency) ----
        public static final int SCAN_ANY          = 0x80;
        public static final int SCAN_PREFIX_BEGIN  = 0x01;
        public static final int SCAN_PREFIX_END    = 0x02;
        public static final int SCAN_GT_BEGIN      = 0x04;
        public static final int SCAN_GTE_BEGIN     = 0x0c;
        public static final int SCAN_LT_END        = 0x10;
        public static final int SCAN_LTE_END       = 0x30;

        public static boolean matchScanType(int expected, int actual) {
            return (expected & actual) == expected;
        }

        // Buffered write operations (replayed on commit)
        private final List<WriteOp> batch;

        public EloqSession() {
            this.batch = new ArrayList<>();
        }

        // ---- Session lifecycle ----

        @Override
        public void open() {
            this.opened = true;
        }

        @Override
        public void close() {
            if (this.batch.size() > 0) {
                LOG.warn("Closing session with {} uncommitted operations",
                         this.batch.size());
                this.batch.clear();
            }
            this.opened = false;
        }

        // ---- Transaction control ----

        @Override
        public Object commit() {
            int count = this.batch.size();
            if (count == 0) {
                return 0;
            }

            long tx = EloqNative.startTx();
            try {
                for (WriteOp op : this.batch) {
                    op.execute(tx);
                }
                EloqNative.commitTx(tx);
            } catch (Exception e) {
                try {
                    EloqNative.abortTx(tx);
                } catch (Exception abortEx) {
                    LOG.warn("Failed to abort tx after commit failure",
                             abortEx);
                }
                throw new BackendException("EloqRocks commit failed", e);
            } finally {
                this.batch.clear();
            }
            return count;
        }

        @Override
        public void rollback() {
            this.batch.clear();
        }

        @Override
        public boolean hasChanges() {
            return !this.batch.isEmpty();
        }

        // ---- Write operations (buffered) ----

        public void put(String table, byte[] key, byte[] value) {
            this.batch.add(tx -> EloqNative.put(tx, table, key, value));
        }

        public void merge(String table, byte[] key, byte[] value) {
            // No native merge; buffer as put (caller handles semantics)
            this.batch.add(tx -> EloqNative.put(tx, table, key, value));
        }

        public void increase(String table, byte[] key, byte[] value) {
            // Atomic read-modify-write increment.
            // Flush pending ops first, then do a standalone tx.
            this.commit();

            long tx = EloqNative.startTx();
            try {
                byte[] old = EloqNative.get(tx, table, key);
                long oldVal = 0;
                if (old != null && old.length == Long.BYTES) {
                    oldVal = ByteBuffer.wrap(old)
                                       .order(ByteOrder.nativeOrder())
                                       .getLong();
                }
                long inc = ByteBuffer.wrap(value)
                                     .order(ByteOrder.nativeOrder())
                                     .getLong();
                long newVal = oldVal + inc;
                byte[] newBytes = ByteBuffer.allocate(Long.BYTES)
                                            .order(ByteOrder.nativeOrder())
                                            .putLong(newVal)
                                            .array();
                EloqNative.put(tx, table, key, newBytes);
                EloqNative.commitTx(tx);
            } catch (Exception e) {
                try {
                    EloqNative.abortTx(tx);
                } catch (Exception abortEx) {
                    LOG.warn("Failed to abort tx in increase()", abortEx);
                }
                throw new BackendException("EloqRocks increase failed", e);
            }
        }

        public void delete(String table, byte[] key) {
            this.batch.add(tx -> EloqNative.delete(tx, table, key));
        }

        public void deleteSingle(String table, byte[] key) {
            this.delete(table, key);
        }

        public void deletePrefix(String table, byte[] prefix) {
            // Scan with prefix, delete each matching key.
            this.batch.add(tx -> {
                byte[][][] results = EloqNative.scan(
                    tx, table, prefix, null, true, false, 0);
                if (results != null && results[0] != null) {
                    for (byte[] key : results[0]) {
                        if (Bytes.prefixWith(key, prefix)) {
                            EloqNative.delete(tx, table, key);
                        }
                    }
                }
            });
        }

        public void deleteRange(String table, byte[] keyFrom, byte[] keyTo) {
            // Scan range [keyFrom, keyTo), delete each key.
            this.batch.add(tx -> {
                byte[][][] results = EloqNative.scan(
                    tx, table, keyFrom, keyTo, true, false, 0);
                if (results != null && results[0] != null) {
                    for (byte[] key : results[0]) {
                        EloqNative.delete(tx, table, key);
                    }
                }
            });
        }

        // ---- Read operations (direct, no buffering) ----

        public byte[] get(String table, byte[] key) {
            return EloqNative.get(0L, table, key);
        }

        public BackendColumnIterator get(String table, List<byte[]> keys) {
            List<BackendColumn> results = new ArrayList<>();
            for (byte[] key : keys) {
                byte[] value = this.get(table, key);
                if (value != null) {
                    results.add(BackendColumn.of(key, value));
                }
            }
            return new EloqColumnIterator(results);
        }

        // ---- Scan operations ----

        public BackendColumnIterator scan(String table) {
            byte[][][] results = EloqNative.scan(
                0L, table, null, null, true, true, 0);
            return toColumnIterator(results);
        }

        public BackendColumnIterator scan(String table, byte[] prefix) {
            byte[][][] results = EloqNative.scan(
                0L, table, prefix, null, true, false, 0);
            return toPrefixColumnIterator(results, prefix);
        }

        public BackendColumnIterator scan(String table, byte[] keyFrom,
                                          byte[] keyTo, int scanType) {
            boolean startInclusive =
                matchScanType(SCAN_GTE_BEGIN, scanType) ||
                matchScanType(SCAN_PREFIX_BEGIN, scanType);
            boolean endInclusive =
                matchScanType(SCAN_LTE_END, scanType);
            boolean prefixEnd =
                matchScanType(SCAN_PREFIX_END, scanType);

            byte[][][] results;
            if (prefixEnd) {
                // keyTo is a prefix, not an end key
                results = EloqNative.scan(
                    0L, table, keyFrom, null,
                    startInclusive, false, 0);
                return toPrefixColumnIterator(results, keyTo);
            }

            results = EloqNative.scan(
                0L, table, keyFrom, keyTo,
                startInclusive, endInclusive, 0);
            return toColumnIterator(results);
        }

        public BackendColumnIterator scan(String table,
                                          byte[] keyFrom, byte[] keyTo) {
            return this.scan(table, keyFrom, keyTo, SCAN_LT_END);
        }

        // ---- Info stubs ----

        public String dataPath() { return ""; }
        public String walPath() { return ""; }

        public String property(String table, String property) {
            return "0";
        }

        public Pair<byte[], byte[]> keyRange(String table) {
            return null;
        }

        public void compactRange(String table) {
            // No-op
        }
    }

    // =====================================================
    // Write operation interface (for buffering)
    // =====================================================

    @FunctionalInterface
    private interface WriteOp {
        void execute(long txHandle);
    }

    // =====================================================
    // Column iterator implementations
    // =====================================================

    static BackendColumnIterator toColumnIterator(byte[][][] results) {
        if (results == null || results[0] == null ||
            results[0].length == 0) {
            return BackendColumnIterator.empty();
        }
        List<BackendColumn> columns = new ArrayList<>(results[0].length);
        for (int i = 0; i < results[0].length; i++) {
            columns.add(BackendColumn.of(results[0][i], results[1][i]));
        }
        return new EloqColumnIterator(columns);
    }

    static BackendColumnIterator toPrefixColumnIterator(
            byte[][][] results, byte[] prefix) {
        if (results == null || results[0] == null ||
            results[0].length == 0) {
            return BackendColumnIterator.empty();
        }
        List<BackendColumn> columns = new ArrayList<>();
        for (int i = 0; i < results[0].length; i++) {
            if (Bytes.prefixWith(results[0][i], prefix)) {
                columns.add(BackendColumn.of(results[0][i], results[1][i]));
            }
        }
        if (columns.isEmpty()) {
            return BackendColumnIterator.empty();
        }
        return new EloqColumnIterator(columns);
    }

    /**
     * In-memory column iterator backed by a list of BackendColumn.
     */
    public static class EloqColumnIterator implements BackendColumnIterator {

        private final List<BackendColumn> columns;
        private int index;

        public EloqColumnIterator(List<BackendColumn> columns) {
            this.columns = columns;
            this.index = 0;
        }

        @Override
        public boolean hasNext() {
            return this.index < this.columns.size();
        }

        @Override
        public BackendColumn next() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            return this.columns.get(this.index++);
        }

        @Override
        public void close() {
            // No resources to release
        }

        @Override
        public byte[] position() {
            if (this.index > 0 && this.index <= this.columns.size()) {
                return this.columns.get(this.index - 1).name;
            }
            return null;
        }

        public long count() {
            return this.columns.size();
        }
    }
}
