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

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hugegraph.backend.BackendException;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.query.Query;
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

/**
 * Base class for KVT backend store implementation.
 * This class manages KVT tables and provides basic store operations.
 */
public abstract class KVTStore extends AbstractBackendStore<KVTSession> {

    private static final Logger LOG = Log.logger(KVTStore.class);
    private static final BackendFeatures FEATURES = new KVTFeatures();
    
    private final String store;
    private final String database;
    private final BackendStoreProvider provider;
    
    private final Map<HugeType, KVTTable> tables;
    private final Map<Long, String> tableIdToName;
    
    private boolean opened;
    private HugeConfig config;
    private KVTSessions sessions;
    
    public KVTStore(BackendStoreProvider provider, 
                   String database, String store) {
        this.provider = provider;
        this.database = database;
        this.store = store;
        this.tables = new HashMap<>();
        this.tableIdToName = new ConcurrentHashMap<>();
        this.opened = false;
        
        this.registerMetaHandlers();
        this.registerTableManagers();
    }
    
    private void registerMetaHandlers() {
        // Register metadata handlers for store information
        this.registerMetaHandler("metrics", (session, meta, args) -> {
            return this.getMetrics();
        });
    }
    
    protected abstract void registerTableManagers();
    
    protected void registerTable(HugeType type, KVTTable table) {
        this.tables.put(type, table);
    }
    
    private Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("backend", "kvt");
        metrics.put("tables", this.tables.size());
        metrics.put("database", this.database);
        metrics.put("store", this.store);
        return metrics;
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
    
    @Override
    public synchronized void open(HugeConfig config) {
        E.checkNotNull(config, "config");
        
        if (this.opened) {
            LOG.debug("Store {} already opened", this.store);
            return;
        }
        
        this.config = config;
        this.sessions = new KVTSessions(config, this.database, this.store);
        
        LOG.info("Opening KVT store '{}' for database '{}'", 
                this.store, this.database);
        
        this.opened = true;
    }
    
    @Override
    public void close() {
        if (!this.opened) {
            return;
        }
        
        LOG.info("Closing KVT store '{}'", this.store);
        
        if (this.sessions != null) {
            this.sessions.close();
            this.sessions = null;
        }
        
        this.opened = false;
    }
    
    @Override
    public boolean opened() {
        return this.opened;
    }
    
    @Override
    public void init() {
        this.checkOpened();
        
        // Create tables for each HugeType
        for (Map.Entry<HugeType, KVTTable> entry : this.tables.entrySet()) {
            HugeType type = entry.getKey();
            KVTTable table = entry.getValue();
            
            String tableName = this.tableFullName(type);
            String partitionMethod = table.isRangePartitioned() ? "range" : "hash";
            
            KVTNative.KVTResult<Long> result = 
                KVTNative.createTable(tableName, partitionMethod);
            
            if (result.error == KVTNative.KVTError.TABLE_ALREADY_EXISTS) {
                // Table already exists, get its ID
                KVTNative.KVTResult<Long> idResult = 
                    KVTNative.nativeGetTableId(tableName)[0] instanceof Integer ? 
                    null : null;  // This needs proper implementation
                LOG.debug("Table {} already exists", tableName);
            } else if (result.error != KVTNative.KVTError.SUCCESS) {
                throw new BackendException("Failed to create table %s: %s",
                                         tableName, result.errorMessage);
            } else {
                table.setTableId(result.value);
                this.tableIdToName.put(result.value, tableName);
                LOG.info("Created KVT table '{}' with ID {}", tableName, result.value);
            }
        }
        
        LOG.info("Store '{}' initialized", this.store);
    }
    
    @Override
    public void clear(boolean clearSpace) {
        this.checkOpened();
        
        // Drop all tables
        for (Map.Entry<HugeType, KVTTable> entry : this.tables.entrySet()) {
            KVTTable table = entry.getValue();
            if (table.getTableId() > 0) {
                KVTNative.KVTResult<Void> result = 
                    KVTNative.dropTable(table.getTableId());
                if (result.error != KVTNative.KVTError.SUCCESS &&
                    result.error != KVTNative.KVTError.TABLE_NOT_FOUND) {
                    LOG.warn("Failed to drop table {}: {}", 
                            table.table(), result.errorMessage);
                }
            }
        }
        
        // Re-initialize to recreate tables
        this.init();
        
        LOG.info("Store '{}' cleared", this.store);
    }
    
    @Override
    public boolean initialized() {
        this.checkOpened();
        // Check if all tables exist
        for (KVTTable table : this.tables.values()) {
            if (table.getTableId() <= 0) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public void truncate() {
        this.clear(false);
    }
    
    @Override
    public void beginTx() {
        this.checkOpened();
        KVTSession session = (KVTSession) this.sessions.session();
        session.beginTx();
    }
    
    @Override
    public void commitTx() {
        this.checkOpened();
        KVTSession session = (KVTSession) this.sessions.session();
        session.commitTx();
    }
    
    @Override
    public void rollbackTx() {
        this.checkOpened();
        KVTSession session = (KVTSession) this.sessions.session();
        session.rollbackTx();
    }
    
    @Override
    public void mutate(BackendMutation mutation) {
        this.checkOpened();
        KVTSession session = (KVTSession) this.sessions.session();
        
        for (Iterator<BackendAction> it = mutation.mutation(); it.hasNext();) {
            BackendAction action = it.next();
            BackendEntry entry = action.entry();
            KVTTable table = (KVTTable) this.table(entry.type());
            KVTBackendEntry kvtEntry = (KVTBackendEntry) entry;
            
            switch (action.action()) {
                case INSERT:
                case APPEND:
                    table.insert(session, kvtEntry);
                    break;
                case DELETE:
                    table.delete(session, kvtEntry);
                    break;
                case ELIMINATE:
                    table.eliminate(session, kvtEntry);
                    break;
                case UPDATE_IF_PRESENT:
                    table.updateIfPresent(session, kvtEntry);
                    break;
                case UPDATE_IF_ABSENT:
                    table.updateIfAbsent(session, kvtEntry);
                    break;
                default:
                    throw new BackendException("Unsupported action: %s", 
                                             action.action());
            }
        }
    }
    
    @Override
    public Iterator<BackendEntry> query(Query query) {
        this.checkOpened();
        KVTSession session = (KVTSession) this.sessions.session();
        KVTTable table = (KVTTable) this.table(query.resultType());
        return table.query(session, query);
    }
    
    @Override
    public Number queryNumber(Query query) {
        this.checkOpened();
        KVTSession session = (KVTSession) this.sessions.session();
        KVTTable table = (KVTTable) this.table(query.resultType());
        return table.queryNumber(session, query);
    }
    
    @Override
    public void increaseCounter(HugeType type, long increment) {
        // TODO: Implement counter support
        throw new UnsupportedOperationException("Counter not yet implemented");
    }
    
    @Override
    public long getCounter(HugeType type) {
        // TODO: Implement counter support
        return 0;
    }
    
    @Override
    protected BackendTable<KVTSession, ?> table(HugeType type) {
        KVTTable table = this.tables.get(type);
        if (table == null) {
            throw new BackendException("Unsupported table type: %s", type);
        }
        return table;
    }
    
    @Override
    protected KVTSession session(HugeType type) {
        this.checkOpened();
        return (KVTSession) this.sessions.session();
    }
    
    protected void checkOpened() throws ConnectionException {
        E.checkState(this.opened, "Store '%s' is not opened", this.store);
    }
    
    private String tableFullName(HugeType type) {
        return this.database + "_" + this.store + "_" + type.string();
    }
    
    /**
     * KVT Schema Store implementation
     */
    public static class KVTSchemaStore extends KVTStore {
        
        public KVTSchemaStore(BackendStoreProvider provider,
                            String database, String store) {
            super(provider, database, store);
        }
        
        @Override
        protected void registerTableManagers() {
            this.registerTable(HugeType.PROPERTY_KEY, 
                              new KVTTable(HugeType.PROPERTY_KEY));
            this.registerTable(HugeType.VERTEX_LABEL,
                              new KVTTable(HugeType.VERTEX_LABEL));
            this.registerTable(HugeType.EDGE_LABEL,
                              new KVTTable(HugeType.EDGE_LABEL));
            this.registerTable(HugeType.INDEX_LABEL,
                              new KVTTable(HugeType.INDEX_LABEL));
        }
        
        @Override
        public boolean isSchemaStore() {
            return true;
        }
    }
    
    /**
     * KVT Graph Store implementation
     */
    public static class KVTGraphStore extends KVTStore {
        
        public KVTGraphStore(BackendStoreProvider provider,
                           String database, String store) {
            super(provider, database, store);
        }
        
        @Override
        protected void registerTableManagers() {
            this.registerTable(HugeType.VERTEX,
                              new KVTTable(HugeType.VERTEX));
            this.registerTable(HugeType.EDGE_OUT,
                              new KVTTable(HugeType.EDGE_OUT, true));  // Range partitioned
            this.registerTable(HugeType.EDGE_IN,
                              new KVTTable(HugeType.EDGE_IN, true));   // Range partitioned
            this.registerTable(HugeType.SECONDARY_INDEX,
                              new KVTTable(HugeType.SECONDARY_INDEX, true));
            this.registerTable(HugeType.RANGE_INT_INDEX,
                              new KVTTable(HugeType.RANGE_INT_INDEX, true));
            this.registerTable(HugeType.RANGE_FLOAT_INDEX,
                              new KVTTable(HugeType.RANGE_FLOAT_INDEX, true));
            this.registerTable(HugeType.RANGE_LONG_INDEX,
                              new KVTTable(HugeType.RANGE_LONG_INDEX, true));
            this.registerTable(HugeType.RANGE_DOUBLE_INDEX,
                              new KVTTable(HugeType.RANGE_DOUBLE_INDEX, true));
            this.registerTable(HugeType.SEARCH_INDEX,
                              new KVTTable(HugeType.SEARCH_INDEX));
            this.registerTable(HugeType.SHARD_INDEX,
                              new KVTTable(HugeType.SHARD_INDEX));
            this.registerTable(HugeType.UNIQUE_INDEX,
                              new KVTTable(HugeType.UNIQUE_INDEX));
        }
        
        @Override
        public boolean isSchemaStore() {
            return false;
        }
    }
    
    /**
     * KVT System Store implementation
     */
    public static class KVTSystemStore extends KVTStore {
        
        public KVTSystemStore(BackendStoreProvider provider,
                            String database, String store) {
            super(provider, database, store);
        }
        
        @Override
        protected void registerTableManagers() {
            this.registerTable(HugeType.SYS_PROPERTY,
                              new KVTTable(HugeType.SYS_PROPERTY));
        }
        
        @Override
        public boolean isSchemaStore() {
            return false;
        }
    }
}