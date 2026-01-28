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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import org.apache.hugegraph.HugeException;
import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.query.Query;
import org.apache.hugegraph.backend.serializer.MergeIterator;
import org.apache.hugegraph.backend.store.AbstractBackendStore;
import org.apache.hugegraph.backend.store.BackendAction;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.backend.store.BackendFeatures;
import org.apache.hugegraph.backend.store.BackendMutation;
import org.apache.hugegraph.backend.store.BackendStoreProvider;
import org.apache.hugegraph.backend.store.BackendTable;
import org.apache.hugegraph.config.CoreOptions;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.exception.ConnectionException;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

public abstract class EloqStore
        extends AbstractBackendStore<EloqSessions.EloqSession> {

    private static final Logger LOG = Log.logger(EloqStore.class);

    private static final BackendFeatures FEATURES = new EloqFeatures();

    private final String store;
    private final String database;
    private final BackendStoreProvider provider;

    private final Map<HugeType, EloqTable> tables;
    private final Map<String, EloqTable> olapTables;

    private EloqSessions sessions;
    private final ReadWriteLock storeLock;
    private boolean isGraphStore;

    public EloqStore(final BackendStoreProvider provider,
                     final String database, final String store) {
        this.tables = new HashMap<>();
        this.olapTables = new HashMap<>();
        this.provider = provider;
        this.database = database;
        this.store = store;
        this.sessions = null;
        this.storeLock = new ReentrantReadWriteLock();
    }

    protected void registerTableManager(HugeType type, EloqTable table) {
        this.tables.put(type, table);
    }

    protected void registerTableManager(String name, EloqTable table) {
        this.olapTables.put(name, table);
    }

    protected void unregisterTableManager(String name) {
        this.olapTables.remove(name);
    }

    @Override
    protected final EloqTable table(HugeType type) {
        EloqTable table = this.tables.get(convertTaskOrServerToVertex(type));
        if (table == null) {
            throw new BackendException("Unsupported table: '%s'", type);
        }
        return table;
    }

    protected final EloqTable table(String name) {
        EloqTable table = this.olapTables.get(name);
        if (table == null) {
            throw new BackendException("Unsupported table: '%s'", name);
        }
        return table;
    }

    protected List<String> tableNames() {
        List<String> tables = this.tables.values().stream()
                                         .map(BackendTable::table)
                                         .collect(Collectors.toList());
        tables.addAll(this.olapTableNames());
        return tables;
    }

    protected List<String> olapTableNames() {
        return this.olapTables.values().stream()
                              .map(EloqTable::table)
                              .collect(Collectors.toList());
    }

    @Override
    public String store() {
        return this.store;
    }

    @Override
    public String database() {
        return this.database;
    }

    @Override
    public BackendStoreProvider provider() {
        return this.provider;
    }

    @Override
    public BackendFeatures features() {
        return FEATURES;
    }

    protected ReadWriteLock storeLock() {
        return this.storeLock;
    }

    // ======================== Lifecycle ========================

    @Override
    public synchronized void open(HugeConfig config) {
        LOG.debug("Store open: {}", this.store);

        E.checkNotNull(config, "config");
        String graphStore = config.get(CoreOptions.STORE_GRAPH);
        this.isGraphStore = this.store.equals(graphStore);

        if (this.sessions != null && !this.sessions.closed()) {
            LOG.debug("Store {} has been opened before", this.store);
            this.sessions.useSession();
            return;
        }

        try {
            this.sessions = new EloqSessions(config, this.database, this.store);
            this.sessions.open();
        } catch (Exception e) {
            LOG.error("Failed to open EloqRocks store '{}'", this.store, e);
            throw new ConnectionException(
                    "Failed to open EloqRocks store '%s'", e, this.store);
        }

        this.sessions.session().open();
        LOG.debug("Store opened: {}", this.store);
    }

    @Override
    public void close() {
        LOG.debug("Store close: {}", this.store);
        this.checkOpened();
        this.sessions.close();
    }

    @Override
    public boolean opened() {
        this.checkDbOpened();
        return this.sessions.session().opened();
    }

    @Override
    public synchronized void init() {
        Lock writeLock = this.storeLock.writeLock();
        writeLock.lock();
        try {
            this.checkDbOpened();
            this.sessions.createTable(
                    this.tableNames().toArray(new String[0]));
            LOG.debug("Store initialized: {}", this.store);
        } catch (Exception e) {
            throw new BackendException(
                    "Failed to init tables for '%s'", e, this.store);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public synchronized void clear(boolean clearSpace) {
        Lock writeLock = this.storeLock.writeLock();
        writeLock.lock();
        try {
            this.checkDbOpened();
            this.sessions.dropTable(
                    this.tableNames().toArray(new String[0]));
            LOG.debug("Store cleared: {}", this.store);
        } catch (Exception e) {
            throw new BackendException(
                    "Failed to clear tables for '%s'", e, this.store);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean initialized() {
        this.checkDbOpened();
        if (!this.opened()) {
            return false;
        }
        for (String table : this.tableNames()) {
            if (!this.sessions.existsTable(table)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void truncate() {
        Lock writeLock = this.storeLock.writeLock();
        writeLock.lock();
        try {
            this.checkOpened();
            this.clear(false);
            this.init();
            LOG.debug("Store truncated: {}", this.store);
        } finally {
            writeLock.unlock();
        }
    }

    // ======================== Transactions ========================

    @Override
    public void beginTx() {
        Lock readLock = this.storeLock.readLock();
        readLock.lock();
        try {
            this.checkOpened();
            EloqSessions.EloqSession session = this.sessions.session();
            assert !session.hasChanges();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void commitTx() {
        Lock readLock = this.storeLock.readLock();
        readLock.lock();
        try {
            this.checkOpened();
            EloqSessions.EloqSession session = this.sessions.session();
            Object count = session.commit();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Store {} committed {} items", this.store, count);
            }
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void rollbackTx() {
        Lock readLock = this.storeLock.readLock();
        readLock.lock();
        try {
            this.checkOpened();
            EloqSessions.EloqSession session = this.sessions.session();
            session.rollback();
        } finally {
            readLock.unlock();
        }
    }

    // ======================== Mutations & Queries ========================

    @Override
    public void mutate(BackendMutation mutation) {
        Lock readLock = this.storeLock.readLock();
        readLock.lock();
        try {
            this.checkOpened();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Store {} mutation: {}", this.store, mutation);
            }

            for (HugeType type : mutation.types()) {
                EloqSessions.EloqSession session = this.session(type);
                for (Iterator<BackendAction> it = mutation.mutation(type);
                     it.hasNext(); ) {
                    this.mutate(session, it.next());
                }
            }
        } finally {
            readLock.unlock();
        }
    }

    private void mutate(EloqSessions.EloqSession session,
                        BackendAction item) {
        BackendEntry entry = item.entry();
        EloqTable table;

        if (!entry.olap()) {
            table = this.table(entry.type());
        } else {
            if (entry.type().isIndex()) {
                table = this.table(this.olapTableName(entry.type()));
            } else {
                table = this.table(this.olapTableName(entry.subId()));
            }
        }

        switch (item.action()) {
            case INSERT:
                table.insert(session, entry);
                break;
            case DELETE:
                table.delete(session, entry);
                break;
            case APPEND:
                table.append(session, entry);
                break;
            case ELIMINATE:
                table.eliminate(session, entry);
                break;
            default:
                throw new AssertionError(String.format(
                        "Unsupported mutate action: %s", item.action()));
        }
    }

    @Override
    public Iterator<BackendEntry> query(Query query) {
        Lock readLock = this.storeLock.readLock();
        readLock.lock();
        try {
            this.checkOpened();
            HugeType tableType = EloqTable.tableType(query);
            EloqTable table;
            EloqSessions.EloqSession session;
            if (query.olap()) {
                table = this.table(this.olapTableName(tableType));
                session = this.session(tableType);
            } else {
                table = this.table(tableType);
                session = this.session(tableType);
            }

            Iterator<BackendEntry> entries = table.query(session, query);
            // Merge OLAP results
            Set<Id> olapPks = query.olapPks();
            if (this.isGraphStore && !olapPks.isEmpty()) {
                List<Iterator<BackendEntry>> iterators = new ArrayList<>();
                for (Id pk : olapPks) {
                    Query q = query.copy();
                    table = this.table(this.olapTableName(pk));
                    iterators.add(table.query(
                            this.session(tableType), q));
                }
                entries = new MergeIterator<>(entries, iterators,
                                              BackendEntry::mergeable);
            }
            return entries;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Number queryNumber(Query query) {
        Lock readLock = this.storeLock.readLock();
        readLock.lock();
        try {
            this.checkOpened();
            HugeType tableType = EloqTable.tableType(query);
            EloqTable table = this.table(tableType);
            return table.queryNumber(this.session(tableType), query);
        } finally {
            readLock.unlock();
        }
    }

    // ======================== Session access ========================

    @Override
    protected EloqSessions.EloqSession session(HugeType tableType) {
        this.checkOpened();
        return this.sessions.session();
    }

    private void checkDbOpened() {
        E.checkState(this.sessions != null && !this.sessions.closed(),
                     "EloqRocks store has not been opened");
    }

    // =====================================================
    //  Inner store classes
    // =====================================================

    public static class EloqSchemaStore extends EloqStore {

        private final EloqTables.Counters counters;

        public EloqSchemaStore(BackendStoreProvider provider,
                               String database, String store) {
            super(provider, database, store);

            this.counters = new EloqTables.Counters(database);

            registerTableManager(HugeType.VERTEX_LABEL,
                    new EloqTables.VertexLabel(database));
            registerTableManager(HugeType.EDGE_LABEL,
                    new EloqTables.EdgeLabel(database));
            registerTableManager(HugeType.PROPERTY_KEY,
                    new EloqTables.PropertyKey(database));
            registerTableManager(HugeType.INDEX_LABEL,
                    new EloqTables.IndexLabel(database));
            registerTableManager(HugeType.SECONDARY_INDEX,
                    new EloqTables.SecondaryIndex(database));
        }

        @Override
        protected List<String> tableNames() {
            List<String> tableNames = super.tableNames();
            tableNames.add(this.counters.table());
            return tableNames;
        }

        @Override
        public boolean isSchemaStore() {
            return true;
        }

        @Override
        public void increaseCounter(HugeType type, long increment) {
            Lock readLock = super.storeLock().readLock();
            readLock.lock();
            try {
                super.checkOpened();
                EloqSessions.EloqSession session =
                        super.session(HugeType.PROPERTY_KEY);
                this.counters.increaseCounter(session, type, increment);
            } finally {
                readLock.unlock();
            }
        }

        @Override
        public long getCounter(HugeType type) {
            Lock readLock = super.storeLock().readLock();
            readLock.lock();
            try {
                super.checkOpened();
                EloqSessions.EloqSession session =
                        super.session(HugeType.PROPERTY_KEY);
                return this.counters.getCounter(session, type);
            } finally {
                readLock.unlock();
            }
        }
    }

    public static class EloqGraphStore extends EloqStore {

        public EloqGraphStore(BackendStoreProvider provider,
                              String database, String store) {
            super(provider, database, store);

            registerTableManager(HugeType.VERTEX,
                    new EloqTables.Vertex(database));
            registerTableManager(HugeType.EDGE_OUT,
                    EloqTables.Edge.out(database));
            registerTableManager(HugeType.EDGE_IN,
                    EloqTables.Edge.in(database));

            registerTableManager(HugeType.SECONDARY_INDEX,
                    new EloqTables.SecondaryIndex(database));
            registerTableManager(HugeType.VERTEX_LABEL_INDEX,
                    new EloqTables.VertexLabelIndex(database));
            registerTableManager(HugeType.EDGE_LABEL_INDEX,
                    new EloqTables.EdgeLabelIndex(database));
            registerTableManager(HugeType.RANGE_INT_INDEX,
                    new EloqTables.RangeIntIndex(database));
            registerTableManager(HugeType.RANGE_FLOAT_INDEX,
                    new EloqTables.RangeFloatIndex(database));
            registerTableManager(HugeType.RANGE_LONG_INDEX,
                    new EloqTables.RangeLongIndex(database));
            registerTableManager(HugeType.RANGE_DOUBLE_INDEX,
                    new EloqTables.RangeDoubleIndex(database));
            registerTableManager(HugeType.SEARCH_INDEX,
                    new EloqTables.SearchIndex(database));
            registerTableManager(HugeType.SHARD_INDEX,
                    new EloqTables.ShardIndex(database));
            registerTableManager(HugeType.UNIQUE_INDEX,
                    new EloqTables.UniqueIndex(database));

            registerTableManager(this.olapTableName(
                    HugeType.SECONDARY_INDEX),
                    new EloqTables.OlapSecondaryIndex(store));
            registerTableManager(this.olapTableName(
                    HugeType.RANGE_INT_INDEX),
                    new EloqTables.OlapRangeIntIndex(store));
            registerTableManager(this.olapTableName(
                    HugeType.RANGE_LONG_INDEX),
                    new EloqTables.OlapRangeLongIndex(store));
            registerTableManager(this.olapTableName(
                    HugeType.RANGE_FLOAT_INDEX),
                    new EloqTables.OlapRangeFloatIndex(store));
            registerTableManager(this.olapTableName(
                    HugeType.RANGE_DOUBLE_INDEX),
                    new EloqTables.OlapRangeDoubleIndex(store));
        }

        @Override
        public boolean isSchemaStore() {
            return false;
        }

        @Override
        public Id nextId(HugeType type) {
            throw new UnsupportedOperationException(
                    "EloqGraphStore.nextId()");
        }

        @Override
        public void increaseCounter(HugeType type, long num) {
            throw new UnsupportedOperationException(
                    "EloqGraphStore.increaseCounter()");
        }

        @Override
        public long getCounter(HugeType type) {
            throw new UnsupportedOperationException(
                    "EloqGraphStore.getCounter()");
        }

        @Override
        public void createOlapTable(Id id) {
            EloqTable table = new EloqTables.OlapTable(this.store(), id);
            try {
                this.sessions().createTable(table.table());
            } catch (Exception e) {
                throw new BackendException(
                        "Failed to create OLAP table '%s'", e, table.table());
            }
            registerTableManager(this.olapTableName(id), table);
        }

        @Override
        public void checkAndRegisterOlapTable(Id id) {
            EloqTable table = new EloqTables.OlapTable(this.store(), id);
            if (!this.sessions().existsTable(table.table())) {
                throw new HugeException(
                        "Not exist table '%s'", table.table());
            }
            registerTableManager(this.olapTableName(id), table);
        }

        @Override
        public void clearOlapTable(Id id) {
            String name = this.olapTableName(id);
            EloqTable table = this.table(name);
            EloqSessions sessions = this.sessions();
            if (!sessions.existsTable(table.table())) {
                throw new HugeException("Not exist table '%s'", name);
            }
            try {
                sessions.dropTable(table.table());
                sessions.createTable(table.table());
            } catch (Exception e) {
                throw new BackendException(
                        "Failed to clear OLAP table '%s'", e, name);
            }
        }

        @Override
        public void removeOlapTable(Id id) {
            String name = this.olapTableName(id);
            EloqTable table = this.table(name);
            EloqSessions sessions = this.sessions();
            if (!sessions.existsTable(table.table())) {
                throw new HugeException("Not exist table '%s'", name);
            }
            try {
                sessions.dropTable(table.table());
            } catch (Exception e) {
                throw new BackendException(
                        "Failed to remove OLAP table '%s'", e, name);
            }
            this.unregisterTableManager(this.olapTableName(id));
        }

        protected EloqSessions sessions() {
            return super.sessions;
        }
    }

    public static class EloqSystemStore extends EloqGraphStore {

        private final EloqTables.Meta meta;

        public EloqSystemStore(BackendStoreProvider provider,
                               String database, String store) {
            super(provider, database, store);
            this.meta = new EloqTables.Meta(database);
        }

        @Override
        public synchronized void init() {
            super.init();
            Lock writeLock = this.storeLock().writeLock();
            writeLock.lock();
            try {
                EloqSessions.EloqSession session =
                        super.session(HugeType.META);
                String driverVersion = this.provider().driverVersion();
                this.meta.writeVersion(session, driverVersion);
                LOG.info("Write down the backend version: {}",
                         driverVersion);
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public String storedVersion() {
            Lock readLock = this.storeLock().readLock();
            readLock.lock();
            try {
                super.checkOpened();
                EloqSessions.EloqSession session = super.session(null);
                return this.meta.readVersion(session);
            } finally {
                readLock.unlock();
            }
        }

        @Override
        protected List<String> tableNames() {
            List<String> tableNames = super.tableNames();
            tableNames.add(this.meta.table());
            return tableNames;
        }
    }
}
