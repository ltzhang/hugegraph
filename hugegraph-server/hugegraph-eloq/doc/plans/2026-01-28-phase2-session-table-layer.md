# Phase 2: EloqRocks Session & Table Layer — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement `EloqSessions` (session pool + session) and `EloqTable`/`EloqTables` (per-table operations) that bridge HugeGraph's backend abstractions to EloqRocks via the Phase 1 JNI bridge.

**Architecture:** EloqSessions extends `BackendSessionPool` (from hugegraph-core) directly. The inner `EloqSession` extends `AbstractBackendSession`. EloqTable extends `BackendTable` directly and reimplements the query dispatch logic (prefix/range/id/condition routing). No dependency on hugegraph-rocksdb — EloqRocks is an independent KV store, not a RocksDB derivative.

**Tech Stack:** Java 11, EloqNative JNI bridge (Phase 1), HugeGraph backend interfaces from hugegraph-core only.

**Key Design Decisions:**
- **No hugegraph-rocksdb dependency.** Scan type constants, session methods, query dispatch, and table subclasses are self-contained in the eloq module.
- **Buffered writes.** The session buffers writes in an `ArrayList<WriteOp>`. On `commit()`, it starts an EloqRocks transaction, replays all buffered operations, and commits. This mirrors the WriteBatch pattern for compatibility with HugeGraph's engine.
- **Eager scan results.** Scans load all results into memory as `byte[][][]` from JNI. For Phase 2 this is acceptable; streaming iterators can be added in Phase 4 if needed.

---

## File Inventory

### New files to create:
| File | Purpose |
|------|---------|
| `src/main/java/.../eloq/EloqSessions.java` | Session pool + concrete EloqSession (extends core interfaces only) |
| `src/main/java/.../eloq/EloqTable.java` | Table base class with query dispatch (extends `BackendTable` directly) |
| `src/main/java/.../eloq/EloqTables.java` | Table subclasses (Vertex, Edge, Index, Meta, Counters, etc.) |
| `src/test/java/.../eloq/EloqSessionsTest.java` | Session lifecycle, CRUD, scan, transaction tests |
| `src/test/java/.../eloq/EloqTableTest.java` | Table query dispatch tests with real entries |

All paths under `hugegraph-server/hugegraph-eloq/`.

### Files to modify:
None. pom.xml already has the `hugegraph-core` dependency from Phase 1.

### Reference files (read-only, for understanding patterns):
| File | Why |
|------|-----|
| `hugegraph-core/.../BackendSessionPool.java` | Base class we extend for session pooling |
| `hugegraph-core/.../BackendSession.java` | `AbstractBackendSession` we extend for session impl |
| `hugegraph-core/.../BackendTable.java` | Base class we extend for table operations |
| `hugegraph-core/.../BackendEntry.java` | `BackendColumnIterator`, `BackendColumn` interfaces |
| `hugegraph-rocksdb/.../RocksDBTable.java` | Reference for query dispatch logic (copy pattern, not extend) |
| `hugegraph-rocksdb/.../RocksDBTables.java` | Reference for table subclass pattern |
| `hugegraph-eloq/.../EloqNative.java` | JNI bridge API we call |

---

## Task 1: Write EloqSessions.java — Session Pool + Concrete Session

**Files:**
- Create: `src/main/java/org/apache/hugegraph/backend/store/eloq/EloqSessions.java`

This is the core of Phase 2. EloqSessions extends `BackendSessionPool` directly and provides a concrete `EloqSession` that uses `EloqNative` for all operations.

The session defines its own scan type constants (matching the semantics from the codebase) so there is no dependency on hugegraph-rocksdb.

**Step 1: Write EloqSessions.java**

```java
package org.apache.hugegraph.backend.store.eloq;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumn;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumnIterator;
import org.apache.hugegraph.backend.store.BackendSession;
import org.apache.hugegraph.backend.store.BackendSessionPool;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.util.Bytes;
import org.apache.hugegraph.util.E;
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
            EloqNative.createTable(table);
            this.openedTables.add(table);
        }
    }

    public void dropTable(String... tables) {
        for (String table : tables) {
            EloqNative.dropTable(table);
            this.openedTables.remove(table);
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
    public class EloqSession extends BackendSession.AbstractBackendSession {

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

        @Override
        public boolean opened() {
            return this.opened;
        }

        @Override
        public boolean closed() {
            return !this.opened;
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
            // Must execute immediately (not buffered) like RocksDB's merge.
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
```

**Step 2: Verify compilation**

Run: `mvn compile -pl hugegraph-server/hugegraph-eloq -Dmaven.test.skip=true`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add hugegraph-server/hugegraph-eloq/src/main/java/org/apache/hugegraph/backend/store/eloq/EloqSessions.java
git commit -m "phase2: add EloqSessions with buffered-write session over JNI bridge"
```

---

## Task 2: Write EloqTable.java — Table Base Class with Query Dispatch

**Files:**
- Create: `src/main/java/org/apache/hugegraph/backend/store/eloq/EloqTable.java`

EloqTable extends `BackendTable<EloqSessions.EloqSession, BackendEntry>` directly. It reimplements the query dispatch logic (prefix/range/id/condition routing) without any RocksDB dependency.

**Step 1: Write EloqTable.java**

```java
package org.apache.hugegraph.backend.store.eloq;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.page.PageState;
import org.apache.hugegraph.backend.query.Aggregate;
import org.apache.hugegraph.backend.query.Aggregate.AggregateFunc;
import org.apache.hugegraph.backend.query.Condition.Relation;
import org.apache.hugegraph.backend.query.ConditionQuery;
import org.apache.hugegraph.backend.query.IdPrefixQuery;
import org.apache.hugegraph.backend.query.IdRangeQuery;
import org.apache.hugegraph.backend.query.Query;
import org.apache.hugegraph.backend.serializer.BinaryBackendEntry;
import org.apache.hugegraph.backend.serializer.BinaryEntryIterator;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumn;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumnIterator;
import org.apache.hugegraph.backend.store.BackendEntryIterator;
import org.apache.hugegraph.backend.store.BackendTable;
import org.apache.hugegraph.backend.store.Shard;
import org.apache.hugegraph.exception.NotSupportException;
import org.apache.hugegraph.iterator.FlatMapperIterator;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.Bytes;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.apache.hugegraph.util.StringEncoding;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.slf4j.Logger;

/**
 * Base table for EloqRocks backend.
 * Extends BackendTable directly (no hugegraph-rocksdb dependency).
 * Reimplements query dispatch logic for sorted KV scan semantics.
 */
public class EloqTable
        extends BackendTable<EloqSessions.EloqSession, BackendEntry> {

    private static final Logger LOG = Log.logger(EloqTable.class);

    public EloqTable(String database, String table) {
        super(String.format("%s+%s", database, table));
    }

    @Override
    protected void registerMetaHandlers() {
        // No meta handlers for now; can add shard splitting later
    }

    @Override
    public void init(EloqSessions.EloqSession session) {
        // Table creation handled by EloqSessions.createTable()
    }

    @Override
    public void clear(EloqSessions.EloqSession session) {
        // Table clearing handled by EloqSessions.dropTable() + createTable()
    }

    @Override
    public void insert(EloqSessions.EloqSession session, BackendEntry entry) {
        assert !entry.columns().isEmpty();
        for (BackendColumn col : entry.columns()) {
            assert entry.belongToMe(col) : entry;
            session.put(this.table(), col.name, col.value);
        }
    }

    @Override
    public void delete(EloqSessions.EloqSession session, BackendEntry entry) {
        if (entry.columns().isEmpty()) {
            session.delete(this.table(), entry.id().asBytes());
        } else {
            for (BackendColumn col : entry.columns()) {
                assert entry.belongToMe(col) : entry;
                session.delete(this.table(), col.name);
            }
        }
    }

    @Override
    public void append(EloqSessions.EloqSession session, BackendEntry entry) {
        assert entry.columns().size() == 1;
        this.insert(session, entry);
    }

    @Override
    public void eliminate(EloqSessions.EloqSession session, BackendEntry entry) {
        assert entry.columns().size() == 1;
        this.delete(session, entry);
    }

    @Override
    public boolean queryExist(EloqSessions.EloqSession session,
                              BackendEntry entry) {
        Id id = entry.id();
        try (BackendColumnIterator iter = this.queryById(session, id)) {
            return iter.hasNext();
        }
    }

    @Override
    public Number queryNumber(EloqSessions.EloqSession session, Query query) {
        Aggregate aggregate = query.aggregateNotNull();
        if (aggregate.func() != AggregateFunc.COUNT) {
            throw new NotSupportException(aggregate.toString());
        }
        assert query.noLimit();
        try (BackendColumnIterator results = this.queryBy(session, query)) {
            if (results instanceof EloqSessions.EloqColumnIterator) {
                return ((EloqSessions.EloqColumnIterator) results).count();
            }
            return IteratorUtils.count(results);
        }
    }

    @Override
    public Iterator<BackendEntry> query(EloqSessions.EloqSession session,
                                        Query query) {
        if (query.limit() == 0L && !query.noLimit()) {
            LOG.debug("Return empty result(limit=0) for query {}", query);
            return Collections.emptyIterator();
        }
        return newEntryIterator(this.queryBy(session, query), query);
    }

    // ---- Query dispatch ----

    protected BackendColumnIterator queryBy(EloqSessions.EloqSession session,
                                            Query query) {
        if (query.empty()) {
            return this.queryAll(session, query);
        }
        if (query instanceof IdPrefixQuery) {
            return this.queryByPrefix(session, (IdPrefixQuery) query);
        }
        if (query instanceof IdRangeQuery) {
            return this.queryByRange(session, (IdRangeQuery) query);
        }
        if (query.conditionsSize() == 0) {
            assert query.idsSize() > 0;
            return this.queryByIds(session, query.ids());
        }
        ConditionQuery cq = (ConditionQuery) query;
        return this.queryByCond(session, cq);
    }

    protected BackendColumnIterator queryAll(EloqSessions.EloqSession session,
                                             Query query) {
        if (query.paging()) {
            PageState page = PageState.fromString(query.page());
            byte[] begin = page.position();
            return session.scan(this.table(), begin, null,
                                EloqSessions.EloqSession.SCAN_ANY);
        } else {
            return session.scan(this.table());
        }
    }

    protected BackendColumnIterator queryById(EloqSessions.EloqSession session,
                                              Id id) {
        // Default: prefix scan on id (schema elements have multi-column keys)
        return session.scan(this.table(), id.asBytes());
    }

    protected BackendColumnIterator queryByIds(
            EloqSessions.EloqSession session, Collection<Id> ids) {
        if (ids.size() == 1) {
            return this.queryById(session, ids.iterator().next());
        }
        return BackendColumnIterator.wrap(new FlatMapperIterator<>(
                ids.iterator(), id -> this.queryById(session, id)
        ));
    }

    protected BackendColumnIterator getById(EloqSessions.EloqSession session,
                                            Id id) {
        byte[] value = session.get(this.table(), id.asBytes());
        if (value == null) {
            return BackendColumnIterator.empty();
        }
        BackendColumn col = BackendColumn.of(id.asBytes(), value);
        return BackendColumnIterator.iterator(col);
    }

    protected BackendColumnIterator getByIds(EloqSessions.EloqSession session,
                                             Set<Id> ids) {
        if (ids.size() == 1) {
            return this.getById(session, ids.iterator().next());
        }
        List<byte[]> keys = new ArrayList<>(ids.size());
        for (Id id : ids) {
            keys.add(id.asBytes());
        }
        return session.get(this.table(), keys);
    }

    protected BackendColumnIterator queryByPrefix(
            EloqSessions.EloqSession session, IdPrefixQuery query) {
        int type = query.inclusiveStart() ?
                   EloqSessions.EloqSession.SCAN_GTE_BEGIN :
                   EloqSessions.EloqSession.SCAN_GT_BEGIN;
        type |= EloqSessions.EloqSession.SCAN_PREFIX_END;
        return session.scan(this.table(), query.start().asBytes(),
                            query.prefix().asBytes(), type);
    }

    protected BackendColumnIterator queryByRange(
            EloqSessions.EloqSession session, IdRangeQuery query) {
        byte[] start = query.start().asBytes();
        byte[] end = query.end() == null ? null : query.end().asBytes();
        int type = query.inclusiveStart() ?
                   EloqSessions.EloqSession.SCAN_GTE_BEGIN :
                   EloqSessions.EloqSession.SCAN_GT_BEGIN;
        if (end != null) {
            type |= query.inclusiveEnd() ?
                    EloqSessions.EloqSession.SCAN_LTE_END :
                    EloqSessions.EloqSession.SCAN_LT_END;
        }
        return session.scan(this.table(), start, end, type);
    }

    protected BackendColumnIterator queryByCond(
            EloqSessions.EloqSession session, ConditionQuery query) {
        if (query.containsScanRelation()) {
            E.checkArgument(query.relations().size() == 1,
                            "Invalid scan with multi conditions: %s", query);
            Relation scan = query.relations().iterator().next();
            Shard shard = (Shard) scan.value();
            return this.queryByRange(session, shard, query.page());
        }
        throw new NotSupportException("query: %s", query);
    }

    protected BackendColumnIterator queryByRange(
            EloqSessions.EloqSession session, Shard shard, String page) {
        byte[] start = this.position(shard.start());
        byte[] end = this.position(shard.end());
        if (page != null && !page.isEmpty()) {
            byte[] position = PageState.fromString(page).position();
            E.checkArgument(start == null ||
                            Bytes.compare(position, start) >= 0,
                            "Invalid page out of lower bound");
            start = position;
        }
        if (start == null) {
            start = ShardSplitter.START_BYTES;
        }
        int type = EloqSessions.EloqSession.SCAN_GTE_BEGIN;
        if (end != null) {
            type |= EloqSessions.EloqSession.SCAN_LT_END;
        }
        return session.scan(this.table(), start, end, type);
    }

    protected byte[] position(String position) {
        if (ShardSplitter.START.equals(position) ||
            ShardSplitter.END.equals(position)) {
            return null;
        }
        return StringEncoding.decodeBase64(position);
    }

    public boolean isOlap() {
        return false;
    }

    // ---- Entry iterator construction ----

    protected static BackendEntryIterator newEntryIterator(
            BackendColumnIterator cols, Query query) {
        return new BinaryEntryIterator<>(cols, query, (entry, col) -> {
            if (entry == null || !entry.belongToMe(col)) {
                HugeType type = query.resultType();
                entry = new BinaryBackendEntry(type, col.name);
            } else {
                assert !Bytes.equals(entry.id().asBytes(), col.name);
            }
            entry.columns(col);
            return entry;
        });
    }

    protected static long sizeOfBackendEntry(BackendEntry entry) {
        return BinaryEntryIterator.sizeOfEntry(entry);
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -pl hugegraph-server/hugegraph-eloq -Dmaven.test.skip=true`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add hugegraph-server/hugegraph-eloq/src/main/java/org/apache/hugegraph/backend/store/eloq/EloqTable.java
git commit -m "phase2: add EloqTable with self-contained query dispatch"
```

---

## Task 3: Write EloqTables.java — All Table Subclasses

**Files:**
- Create: `src/main/java/org/apache/hugegraph/backend/store/eloq/EloqTables.java`

Mirrors RocksDBTables but extends EloqTable. Includes Meta, Counters, SchemaTable, Vertex, Edge, all index tables, and OLAP tables.

**Step 1: Write EloqTables.java**

```java
package org.apache.hugegraph.backend.store.eloq;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.List;

import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.query.Condition;
import org.apache.hugegraph.backend.query.Condition.Relation;
import org.apache.hugegraph.backend.query.ConditionQuery;
import org.apache.hugegraph.backend.serializer.BinarySerializer;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumnIterator;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.HugeKeys;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.StringEncoding;

/**
 * Table subclass definitions for EloqRocks backend.
 * All extend EloqTable (no dependency on hugegraph-rocksdb).
 */
public class EloqTables {

    public static class Meta extends EloqTable {

        private static final String TABLE = HugeType.META.string();

        public Meta(String database) {
            super(database, TABLE);
        }

        public void writeVersion(EloqSessions.EloqSession session,
                                 String version) {
            byte[] key = new byte[]{HugeKeys.VERSION.code()};
            byte[] value = StringEncoding.encode(version);
            session.put(this.table(), key, value);
            try {
                session.commit();
            } catch (Exception e) {
                session.rollback();
                throw e;
            }
        }

        public String readVersion(EloqSessions.EloqSession session) {
            byte[] key = new byte[]{HugeKeys.VERSION.code()};
            byte[] value = session.get(this.table(), key);
            if (value == null) {
                return null;
            }
            return StringEncoding.decode(value);
        }
    }

    public static class Counters extends EloqTable {

        private static final String TABLE = HugeType.COUNTER.string();

        public Counters(String database) {
            super(database, TABLE);
        }

        public long getCounter(EloqSessions.EloqSession session,
                               HugeType type) {
            byte[] key = new byte[]{type.code()};
            byte[] value = session.get(this.table(), key);
            if (value != null) {
                return toLong(value);
            } else {
                return 0L;
            }
        }

        public void increaseCounter(EloqSessions.EloqSession session,
                                    HugeType type, long increment) {
            byte[] key = new byte[]{type.code()};
            session.increase(this.table(), key, toBytes(increment));
        }

        private static byte[] toBytes(long value) {
            return ByteBuffer.allocate(Long.BYTES)
                             .order(ByteOrder.nativeOrder())
                             .putLong(value).array();
        }

        private static long toLong(byte[] bytes) {
            assert bytes.length == Long.BYTES;
            return ByteBuffer.wrap(bytes)
                             .order(ByteOrder.nativeOrder())
                             .getLong();
        }
    }

    public static class SchemaTable extends EloqTable {

        public SchemaTable(String database, String table) {
            super(database, table);
        }

        @Override
        public void delete(EloqSessions.EloqSession session,
                           BackendEntry entry) {
            assert entry.columns().isEmpty();
            byte[] prefix = entry.id().asBytes();
            try (BackendColumnIterator results =
                     session.scan(this.table(), prefix)) {
                while (results.hasNext()) {
                    byte[] column = results.next().name;
                    session.delete(this.table(), column);
                }
                session.commit();
            }
        }
    }

    public static class VertexLabel extends SchemaTable {
        public static final String TABLE = HugeType.VERTEX_LABEL.string();
        public VertexLabel(String database) { super(database, TABLE); }
    }

    public static class EdgeLabel extends SchemaTable {
        public static final String TABLE = HugeType.EDGE_LABEL.string();
        public EdgeLabel(String database) { super(database, TABLE); }
    }

    public static class PropertyKey extends SchemaTable {
        public static final String TABLE = HugeType.PROPERTY_KEY.string();
        public PropertyKey(String database) { super(database, TABLE); }
    }

    public static class IndexLabel extends SchemaTable {
        public static final String TABLE = HugeType.INDEX_LABEL.string();
        public IndexLabel(String database) { super(database, TABLE); }
    }

    public static class Vertex extends EloqTable {
        public static final String TABLE = HugeType.VERTEX.string();
        public Vertex(String database) { super(database, TABLE); }

        @Override
        protected BackendColumnIterator queryById(
                EloqSessions.EloqSession session, Id id) {
            return this.getById(session, id);
        }

        @Override
        protected BackendColumnIterator queryByIds(
                EloqSessions.EloqSession session, Collection<Id> ids) {
            return super.queryByIds(session, ids);
        }
    }

    public static class Edge extends EloqTable {
        public static final String TABLE_SUFFIX = HugeType.EDGE.string();

        public Edge(boolean out, String database) {
            super(database, (out ? 'o' : 'i') + TABLE_SUFFIX);
        }

        public static Edge out(String database) {
            return new Edge(true, database);
        }

        public static Edge in(String database) {
            return new Edge(false, database);
        }

        @Override
        protected BackendColumnIterator queryById(
                EloqSessions.EloqSession session, Id id) {
            return this.getById(session, id);
        }
    }

    public static class IndexTable extends EloqTable {

        public IndexTable(String database, String table) {
            super(database, table);
        }

        @Override
        public void eliminate(EloqSessions.EloqSession session,
                              BackendEntry entry) {
            assert entry.columns().size() == 1;
            super.delete(session, entry);
        }

        @Override
        public void delete(EloqSessions.EloqSession session,
                           BackendEntry entry) {
            for (BackendEntry.BackendColumn column : entry.columns()) {
                session.deletePrefix(this.table(), column.name);
            }
        }
    }

    public static class SecondaryIndex extends IndexTable {
        public static final String TABLE = HugeType.SECONDARY_INDEX.string();
        public SecondaryIndex(String database) { super(database, TABLE); }
    }

    public static class VertexLabelIndex extends IndexTable {
        public static final String TABLE =
                HugeType.VERTEX_LABEL_INDEX.string();
        public VertexLabelIndex(String database) { super(database, TABLE); }
    }

    public static class EdgeLabelIndex extends IndexTable {
        public static final String TABLE =
                HugeType.EDGE_LABEL_INDEX.string();
        public EdgeLabelIndex(String database) { super(database, TABLE); }
    }

    public static class SearchIndex extends IndexTable {
        public static final String TABLE = HugeType.SEARCH_INDEX.string();
        public SearchIndex(String database) { super(database, TABLE); }
    }

    public static class UniqueIndex extends IndexTable {
        public static final String TABLE = HugeType.UNIQUE_INDEX.string();
        public UniqueIndex(String database) { super(database, TABLE); }
    }

    public static class RangeIndex extends IndexTable {

        public RangeIndex(String database, String table) {
            super(database, table);
        }

        @Override
        protected BackendColumnIterator queryByCond(
                EloqSessions.EloqSession session, ConditionQuery query) {
            assert query.conditionsSize() > 0;
            List<Condition> conds = query.syspropConditions(HugeKeys.ID);
            E.checkArgument(!conds.isEmpty(),
                            "Please specify the index conditions");

            Id prefix = null;
            Id min = null;
            boolean minEq = false;
            Id max = null;
            boolean maxEq = false;

            for (Condition c : conds) {
                Relation r = (Relation) c;
                switch (r.relation()) {
                    case PREFIX:
                        prefix = (Id) r.value();
                        break;
                    case GTE:
                        minEq = true;
                        min = (Id) r.value();
                        break;
                    case GT:
                        min = (Id) r.value();
                        break;
                    case LTE:
                        maxEq = true;
                        max = (Id) r.value();
                        break;
                    case LT:
                        max = (Id) r.value();
                        break;
                    default:
                        E.checkArgument(false,
                                        "Unsupported relation '%s'",
                                        r.relation());
                }
            }

            E.checkArgumentNotNull(min,
                                   "Range index begin key is missing");
            byte[] begin = min.asBytes();
            if (!minEq) {
                BinarySerializer.increaseOne(begin);
            }

            if (max == null) {
                E.checkArgumentNotNull(prefix,
                                       "Range index prefix is missing");
                return session.scan(this.table(), begin, prefix.asBytes(),
                                    EloqSessions.EloqSession.SCAN_PREFIX_END);
            } else {
                byte[] end = max.asBytes();
                int type = maxEq ?
                           EloqSessions.EloqSession.SCAN_LTE_END :
                           EloqSessions.EloqSession.SCAN_LT_END;
                return session.scan(this.table(), begin, end, type);
            }
        }
    }

    public static class RangeIntIndex extends RangeIndex {
        public static final String TABLE =
                HugeType.RANGE_INT_INDEX.string();
        public RangeIntIndex(String store) { super(store, TABLE); }
    }

    public static class RangeFloatIndex extends RangeIndex {
        public static final String TABLE =
                HugeType.RANGE_FLOAT_INDEX.string();
        public RangeFloatIndex(String store) { super(store, TABLE); }
    }

    public static class RangeLongIndex extends RangeIndex {
        public static final String TABLE =
                HugeType.RANGE_LONG_INDEX.string();
        public RangeLongIndex(String store) { super(store, TABLE); }
    }

    public static class RangeDoubleIndex extends RangeIndex {
        public static final String TABLE =
                HugeType.RANGE_DOUBLE_INDEX.string();
        public RangeDoubleIndex(String store) { super(store, TABLE); }
    }

    public static class ShardIndex extends RangeIndex {
        public static final String TABLE = HugeType.SHARD_INDEX.string();
        public ShardIndex(String database) { super(database, TABLE); }
    }

    public static class OlapTable extends EloqTable {
        public static final String TABLE = HugeType.OLAP.string();
        public OlapTable(String database, Id id) {
            super(database, joinTableName(TABLE, id.asString()));
        }

        @Override
        protected BackendColumnIterator queryById(
                EloqSessions.EloqSession session, Id id) {
            return this.getById(session, id);
        }

        @Override
        public boolean isOlap() {
            return true;
        }
    }

    public static class OlapSecondaryIndex extends SecondaryIndex {
        public static final String TABLE = HugeType.OLAP.string();
        public OlapSecondaryIndex(String store) { this(store, TABLE); }
        protected OlapSecondaryIndex(String store, String table) {
            super(joinTableName(store, table));
        }
    }

    public static class OlapRangeIntIndex extends RangeIntIndex {
        public static final String TABLE = HugeType.OLAP.string();
        public OlapRangeIntIndex(String store) { this(store, TABLE); }
        protected OlapRangeIntIndex(String store, String table) {
            super(joinTableName(store, table));
        }
    }

    public static class OlapRangeLongIndex extends RangeLongIndex {
        public static final String TABLE = HugeType.OLAP.string();
        public OlapRangeLongIndex(String store) { this(store, TABLE); }
        protected OlapRangeLongIndex(String store, String table) {
            super(joinTableName(store, table));
        }
    }

    public static class OlapRangeFloatIndex extends RangeFloatIndex {
        public static final String TABLE = HugeType.OLAP.string();
        public OlapRangeFloatIndex(String store) { this(store, TABLE); }
        protected OlapRangeFloatIndex(String store, String table) {
            super(joinTableName(store, table));
        }
    }

    public static class OlapRangeDoubleIndex extends RangeDoubleIndex {
        public static final String TABLE = HugeType.OLAP.string();
        public OlapRangeDoubleIndex(String store) { this(store, TABLE); }
        protected OlapRangeDoubleIndex(String store, String table) {
            super(joinTableName(store, table));
        }
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -pl hugegraph-server/hugegraph-eloq -Dmaven.test.skip=true`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add hugegraph-server/hugegraph-eloq/src/main/java/org/apache/hugegraph/backend/store/eloq/EloqTable.java \
        hugegraph-server/hugegraph-eloq/src/main/java/org/apache/hugegraph/backend/store/eloq/EloqTables.java
git commit -m "phase2: add EloqTable and EloqTables (all table subclasses, no rocksdb dep)"
```

---

## Task 4: Write EloqSessionsTest.java — Session TDD Tests

**Files:**
- Create: `src/test/java/org/apache/hugegraph/backend/store/eloq/EloqSessionsTest.java`

**Step 1: Write tests**

```java
package org.apache.hugegraph.backend.store.eloq;

import java.util.Arrays;
import java.util.List;

import org.apache.hugegraph.backend.store.BackendEntry.BackendColumn;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumnIterator;
import org.apache.hugegraph.testutil.Assert;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for EloqSessions: session pool lifecycle, table management,
 * CRUD operations, scan variants, and transaction semantics.
 */
public class EloqSessionsTest {

    private static EloqSessions sessions;
    private static final String DATABASE = "test_db";
    private static final String STORE = "test_store";
    private static final String TABLE = DATABASE + "+" + STORE + "+test_tbl";

    @BeforeClass
    public static void init() throws Exception {
        EloqNative.init("");
        sessions = new EloqSessions(null, DATABASE, STORE);
        sessions.open();
    }

    @AfterClass
    public static void shutdown() {
        if (sessions != null) {
            sessions.close();
        }
        EloqNative.shutdown();
    }

    @Before
    public void setUp() throws Exception {
        if (!sessions.existsTable(TABLE)) {
            sessions.createTable(TABLE);
        }
    }

    @After
    public void tearDown() throws Exception {
        if (sessions.existsTable(TABLE)) {
            sessions.dropTable(TABLE);
        }
    }

    @Test
    public void testSessionLifecycle() {
        EloqSessions.EloqSession session = sessions.session();
        Assert.assertNotNull(session);
        Assert.assertFalse(session.hasChanges());
    }

    @Test
    public void testCreateAndDropTable() throws Exception {
        String tbl = DATABASE + "+" + STORE + "+lifecycle_tbl";
        Assert.assertFalse(sessions.existsTable(tbl));
        sessions.createTable(tbl);
        Assert.assertTrue(sessions.existsTable(tbl));
        Assert.assertTrue(sessions.openedTables().contains(tbl));
        sessions.dropTable(tbl);
        Assert.assertFalse(sessions.existsTable(tbl));
    }

    @Test
    public void testPutGetDelete() {
        EloqSessions.EloqSession session = sessions.session();
        byte[] key = "hello".getBytes();
        byte[] value = "world".getBytes();

        session.put(TABLE, key, value);
        Assert.assertTrue(session.hasChanges());
        session.commit();
        Assert.assertFalse(session.hasChanges());

        byte[] got = session.get(TABLE, key);
        Assert.assertNotNull(got);
        Assert.assertArrayEquals(value, got);

        session.delete(TABLE, key);
        session.commit();
        Assert.assertNull(session.get(TABLE, key));
    }

    @Test
    public void testRollback() {
        EloqSessions.EloqSession session = sessions.session();
        byte[] key = "rollback_key".getBytes();
        byte[] value = "rollback_val".getBytes();

        session.put(TABLE, key, value);
        Assert.assertTrue(session.hasChanges());
        session.rollback();
        Assert.assertFalse(session.hasChanges());
        Assert.assertNull(session.get(TABLE, key));
    }

    @Test
    public void testMultiGet() {
        EloqSessions.EloqSession session = sessions.session();
        for (int i = 1; i <= 3; i++) {
            session.put(TABLE, ("k" + i).getBytes(), ("v" + i).getBytes());
        }
        session.commit();

        List<byte[]> keys = Arrays.asList(
            "k1".getBytes(), "k2".getBytes(), "k3".getBytes(),
            "k_missing".getBytes()
        );
        try (BackendColumnIterator iter = session.get(TABLE, keys)) {
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            Assert.assertEquals(3, count);
        }
    }

    @Test
    public void testScanAll() {
        EloqSessions.EloqSession session = sessions.session();
        for (int i = 0; i < 5; i++) {
            session.put(TABLE, ("key_" + i).getBytes(),
                        ("val_" + i).getBytes());
        }
        session.commit();

        try (BackendColumnIterator iter = session.scan(TABLE)) {
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            Assert.assertEquals(5, count);
        }
    }

    @Test
    public void testScanPrefix() {
        EloqSessions.EloqSession session = sessions.session();
        session.put(TABLE, "abc_1".getBytes(), "v1".getBytes());
        session.put(TABLE, "abc_2".getBytes(), "v2".getBytes());
        session.put(TABLE, "abd_1".getBytes(), "v3".getBytes());
        session.put(TABLE, "xyz_1".getBytes(), "v4".getBytes());
        session.commit();

        try (BackendColumnIterator iter =
                 session.scan(TABLE, "abc".getBytes())) {
            int count = 0;
            while (iter.hasNext()) {
                BackendColumn col = iter.next();
                Assert.assertTrue(new String(col.name).startsWith("abc"));
                count++;
            }
            Assert.assertEquals(2, count);
        }
    }

    @Test
    public void testScanRange() {
        EloqSessions.EloqSession session = sessions.session();
        for (char c = 'a'; c <= 'e'; c++) {
            session.put(TABLE, new byte[]{(byte) c}, new byte[]{(byte) c});
        }
        session.commit();

        // [b, d) — GTE_BEGIN | LT_END
        int type = EloqSessions.EloqSession.SCAN_GTE_BEGIN |
                   EloqSessions.EloqSession.SCAN_LT_END;
        try (BackendColumnIterator iter = session.scan(
                TABLE, new byte[]{(byte) 'b'}, new byte[]{(byte) 'd'}, type)) {
            int count = 0;
            while (iter.hasNext()) {
                byte k = iter.next().name[0];
                Assert.assertTrue(k >= 'b' && k < 'd');
                count++;
            }
            Assert.assertEquals(2, count);
        }

        // [b, d] — GTE_BEGIN | LTE_END
        type = EloqSessions.EloqSession.SCAN_GTE_BEGIN |
               EloqSessions.EloqSession.SCAN_LTE_END;
        try (BackendColumnIterator iter = session.scan(
                TABLE, new byte[]{(byte) 'b'}, new byte[]{(byte) 'd'}, type)) {
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            Assert.assertEquals(3, count);
        }
    }

    @Test
    public void testScanPrefixEnd() {
        EloqSessions.EloqSession session = sessions.session();
        session.put(TABLE, "idx_a1".getBytes(), "v1".getBytes());
        session.put(TABLE, "idx_a2".getBytes(), "v2".getBytes());
        session.put(TABLE, "idx_b1".getBytes(), "v3".getBytes());
        session.put(TABLE, "other".getBytes(), "v4".getBytes());
        session.commit();

        int type = EloqSessions.EloqSession.SCAN_GTE_BEGIN |
                   EloqSessions.EloqSession.SCAN_PREFIX_END;
        try (BackendColumnIterator iter = session.scan(
                TABLE, "idx_a1".getBytes(), "idx".getBytes(), type)) {
            int count = 0;
            while (iter.hasNext()) {
                Assert.assertTrue(
                    new String(iter.next().name).startsWith("idx"));
                count++;
            }
            Assert.assertEquals(3, count);
        }
    }

    @Test
    public void testDeletePrefix() {
        EloqSessions.EloqSession session = sessions.session();
        session.put(TABLE, "pfx_a".getBytes(), "v1".getBytes());
        session.put(TABLE, "pfx_b".getBytes(), "v2".getBytes());
        session.put(TABLE, "other".getBytes(), "v3".getBytes());
        session.commit();

        session.deletePrefix(TABLE, "pfx".getBytes());
        session.commit();

        Assert.assertNull(session.get(TABLE, "pfx_a".getBytes()));
        Assert.assertNull(session.get(TABLE, "pfx_b".getBytes()));
        Assert.assertNotNull(session.get(TABLE, "other".getBytes()));
    }

    @Test
    public void testDeleteRange() {
        EloqSessions.EloqSession session = sessions.session();
        for (char c = 'a'; c <= 'e'; c++) {
            session.put(TABLE, new byte[]{(byte) c}, new byte[]{(byte) c});
        }
        session.commit();

        session.deleteRange(TABLE, new byte[]{(byte) 'b'},
                            new byte[]{(byte) 'd'});
        session.commit();

        Assert.assertNotNull(session.get(TABLE, new byte[]{(byte) 'a'}));
        Assert.assertNull(session.get(TABLE, new byte[]{(byte) 'b'}));
        Assert.assertNull(session.get(TABLE, new byte[]{(byte) 'c'}));
        Assert.assertNotNull(session.get(TABLE, new byte[]{(byte) 'd'}));
        Assert.assertNotNull(session.get(TABLE, new byte[]{(byte) 'e'}));
    }

    @Test
    public void testIncrease() {
        EloqSessions.EloqSession session = sessions.session();
        byte[] key = "counter".getBytes();

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(Long.BYTES)
                .order(java.nio.ByteOrder.nativeOrder());
        buf.putLong(5L);
        session.increase(TABLE, key, buf.array());

        byte[] val = session.get(TABLE, key);
        Assert.assertNotNull(val);
        long result = java.nio.ByteBuffer.wrap(val)
                .order(java.nio.ByteOrder.nativeOrder()).getLong();
        Assert.assertEquals(5L, result);

        buf = java.nio.ByteBuffer.allocate(Long.BYTES)
                .order(java.nio.ByteOrder.nativeOrder());
        buf.putLong(3L);
        session.increase(TABLE, key, buf.array());

        val = session.get(TABLE, key);
        result = java.nio.ByteBuffer.wrap(val)
                .order(java.nio.ByteOrder.nativeOrder()).getLong();
        Assert.assertEquals(8L, result);
    }

    @Test
    public void testIteratorPosition() {
        EloqSessions.EloqSession session = sessions.session();
        session.put(TABLE, "p1".getBytes(), "v1".getBytes());
        session.put(TABLE, "p2".getBytes(), "v2".getBytes());
        session.put(TABLE, "p3".getBytes(), "v3".getBytes());
        session.commit();

        try (BackendColumnIterator iter = session.scan(TABLE)) {
            Assert.assertNull(iter.position());
            iter.next();
            Assert.assertNotNull(iter.position());
            Assert.assertArrayEquals("p1".getBytes(), iter.position());
        }
    }
}
```

**Step 2: Run tests**

Run: `mvn test -pl hugegraph-server/hugegraph-eloq -Dtest=EloqSessionsTest`
Expected: ALL TESTS PASS

**Step 3: Commit**

```bash
git add hugegraph-server/hugegraph-eloq/src/test/java/org/apache/hugegraph/backend/store/eloq/EloqSessionsTest.java
git commit -m "phase2: add EloqSessionsTest — 13 tests for session lifecycle, CRUD, scans, counters"
```

---

## Task 5: Write EloqTableTest.java — Table Layer Tests

**Files:**
- Create: `src/test/java/org/apache/hugegraph/backend/store/eloq/EloqTableTest.java`

**Step 1: Write tests**

```java
package org.apache.hugegraph.backend.store.eloq;

import java.util.Collections;
import java.util.Iterator;

import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.query.IdPrefixQuery;
import org.apache.hugegraph.backend.query.IdRangeQuery;
import org.apache.hugegraph.backend.query.Query;
import org.apache.hugegraph.backend.serializer.BinaryBackendEntry;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumn;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.type.HugeType;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for EloqTable: insert, delete, query dispatch via BackendTable API.
 */
public class EloqTableTest {

    private static EloqSessions sessions;
    private static EloqTable table;
    private static final String DATABASE = "tbl_test_db";
    private static final String STORE = "tbl_test_store";
    private static final String TABLE_NAME = DATABASE + "+test_vertex";

    @BeforeClass
    public static void init() throws Exception {
        EloqNative.init("");
        sessions = new EloqSessions(null, DATABASE, STORE);
        sessions.open();
        table = new EloqTable(DATABASE, "test_vertex");
    }

    @AfterClass
    public static void shutdown() {
        if (sessions != null) {
            sessions.close();
        }
        EloqNative.shutdown();
    }

    @Before
    public void setUp() throws Exception {
        if (!sessions.existsTable(TABLE_NAME)) {
            sessions.createTable(TABLE_NAME);
        }
    }

    @After
    public void tearDown() throws Exception {
        if (sessions.existsTable(TABLE_NAME)) {
            sessions.dropTable(TABLE_NAME);
        }
    }

    @Test
    public void testInsertAndQueryById() {
        EloqSessions.EloqSession session = sessions.session();

        byte[] key = new byte[]{0x01, 0x02, 0x03};
        byte[] value = new byte[]{0x10, 0x20, 0x30};
        BinaryBackendEntry entry = new BinaryBackendEntry(
                HugeType.VERTEX, key);
        entry.column(BackendColumn.of(key, value));

        table.insert(session, entry);
        session.commit();

        Id id = IdGenerator.of(key, true);
        Query query = new Query(HugeType.VERTEX);
        query.ids(Collections.singleton(id));
        Iterator<BackendEntry> results = table.query(session, query);

        Assert.assertTrue(results.hasNext());
        BackendEntry result = results.next();
        Assert.assertNotNull(result);
        Assert.assertFalse(results.hasNext());
    }

    @Test
    public void testDeleteEntry() {
        EloqSessions.EloqSession session = sessions.session();

        byte[] key = new byte[]{0x04, 0x05};
        byte[] value = new byte[]{0x40, 0x50};
        BinaryBackendEntry entry = new BinaryBackendEntry(
                HugeType.VERTEX, key);
        entry.column(BackendColumn.of(key, value));

        table.insert(session, entry);
        session.commit();

        Assert.assertNotNull(session.get(TABLE_NAME, key));

        table.delete(session, entry);
        session.commit();
        Assert.assertNull(session.get(TABLE_NAME, key));
    }

    @Test
    public void testQueryByPrefix() {
        EloqSessions.EloqSession session = sessions.session();

        byte[] prefix = new byte[]{0x0A};
        for (int i = 0; i < 3; i++) {
            byte[] key = new byte[]{0x0A, (byte) i};
            byte[] value = new byte[]{(byte) (i + 1)};
            BinaryBackendEntry entry = new BinaryBackendEntry(
                    HugeType.VERTEX, key);
            entry.column(BackendColumn.of(key, value));
            table.insert(session, entry);
        }
        byte[] otherKey = new byte[]{0x0B, 0x00};
        BinaryBackendEntry other = new BinaryBackendEntry(
                HugeType.VERTEX, otherKey);
        other.column(BackendColumn.of(otherKey, new byte[]{(byte) 0xFF}));
        table.insert(session, other);
        session.commit();

        Id prefixId = IdGenerator.of(prefix, true);
        IdPrefixQuery pq = new IdPrefixQuery(HugeType.VERTEX,
                                              prefixId, prefixId);
        Iterator<BackendEntry> results = table.query(session, pq);

        int count = 0;
        while (results.hasNext()) {
            results.next();
            count++;
        }
        Assert.assertEquals(3, count);
    }

    @Test
    public void testQueryByRange() {
        EloqSessions.EloqSession session = sessions.session();

        for (int i = 1; i <= 5; i++) {
            byte[] key = new byte[]{(byte) i};
            byte[] value = new byte[]{(byte) (i * 10)};
            BinaryBackendEntry entry = new BinaryBackendEntry(
                    HugeType.VERTEX, key);
            entry.column(BackendColumn.of(key, value));
            table.insert(session, entry);
        }
        session.commit();

        Id start = IdGenerator.of(new byte[]{0x02}, true);
        Id end = IdGenerator.of(new byte[]{0x04}, true);
        IdRangeQuery rq = new IdRangeQuery(HugeType.VERTEX, start, true,
                                            end, true);
        Iterator<BackendEntry> results = table.query(session, rq);

        int count = 0;
        while (results.hasNext()) {
            results.next();
            count++;
        }
        Assert.assertEquals(3, count);
    }

    @Test
    public void testQueryEmpty() {
        EloqSessions.EloqSession session = sessions.session();

        Query query = new Query(HugeType.VERTEX);
        Iterator<BackendEntry> results = table.query(session, query);

        int count = 0;
        while (results.hasNext()) {
            results.next();
            count++;
        }
        Assert.assertEquals(0, count);
    }
}
```

**Step 2: Run tests**

Run: `mvn test -pl hugegraph-server/hugegraph-eloq -Dtest=EloqTableTest`
Expected: ALL TESTS PASS

**Step 3: Commit**

```bash
git add hugegraph-server/hugegraph-eloq/src/test/java/org/apache/hugegraph/backend/store/eloq/EloqTableTest.java
git commit -m "phase2: add EloqTableTest — insert, delete, query dispatch tests"
```

---

## Task 6: Run All Tests Together + Final Commit

**Step 1: Run all eloq tests**

Run: `mvn test -pl hugegraph-server/hugegraph-eloq`
Expected: ALL TESTS PASS (EloqNativeTest + EloqSessionsTest + EloqTableTest)

**Step 2: Commit and push**

```bash
git add -A hugegraph-server/hugegraph-eloq/
git commit -m "phase2: EloqRocks session & table layer complete — all tests green"
git push
```

---

## Key Design Differences from RocksDB Backend

| Aspect | RocksDB | EloqRocks |
|--------|---------|-----------|
| Session base class | `RocksDBSessions.Session` | `AbstractBackendSession` (from core) |
| Table base class | `RocksDBTable` | `EloqTable` (extends `BackendTable` from core) |
| Module dependency | N/A (self-contained) | `hugegraph-core` only (no rocksdb dep) |
| Write buffering | `WriteBatch` (C++) | `ArrayList<WriteOp>` (Java) |
| Commit | `db.write(batch)` | `startTx` → replay ops → `commitTx` |
| Read isolation | Reads bypass WriteBatch | Reads via auto-commit (tx=0) |
| `increase()` | `merge()` operator | Read-modify-write in standalone tx |
| `deletePrefix()` | `deleteRange(prefix, prefixEnd)` | Scan + delete each key |
| `deleteRange()` | `WriteBatch.deleteRange()` | Scan + delete each key |
| Scan returns | `RocksIterator` (lazy) | `byte[][][]` (eager, in-memory) |
| Scan type constants | In `RocksDBSessions.Session` | In `EloqSessions.EloqSession` (same values) |
