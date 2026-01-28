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
    public void eliminate(EloqSessions.EloqSession session,
                          BackendEntry entry) {
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
        int limit = queryLimit(query);
        if (query.paging()) {
            PageState page = PageState.fromString(query.page());
            byte[] begin = page.position();
            return session.scan(this.table(), begin, null,
                                EloqSessions.EloqSession.SCAN_ANY, limit);
        } else {
            return session.scan(this.table(), limit);
        }
    }

    protected BackendColumnIterator queryById(
            EloqSessions.EloqSession session, Id id) {
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

    protected BackendColumnIterator getById(
            EloqSessions.EloqSession session, Id id) {
        byte[] value = session.get(this.table(), id.asBytes());
        if (value == null) {
            return BackendColumnIterator.empty();
        }
        BackendColumn col = BackendColumn.of(id.asBytes(), value);
        return BackendColumnIterator.iterator(col);
    }

    protected BackendColumnIterator getByIds(
            EloqSessions.EloqSession session, Set<Id> ids) {
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
        int limit = queryLimit(query);
        return session.scan(this.table(), query.start().asBytes(),
                            query.prefix().asBytes(), type, limit);
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
        int limit = queryLimit(query);
        return session.scan(this.table(), start, end, type, limit);
    }

    protected BackendColumnIterator queryByCond(
            EloqSessions.EloqSession session, ConditionQuery query) {
        if (query.containsScanRelation()) {
            E.checkArgument(query.relations().size() == 1,
                            "Invalid scan with multi conditions: %s", query);
            Relation scan = query.relations().iterator().next();
            Shard shard = (Shard) scan.value();
            int limit = queryLimit(query);
            return this.queryByRange(session, shard, query.page(), limit);
        }
        throw new NotSupportException("query: %s", query);
    }

    protected BackendColumnIterator queryByRange(
            EloqSessions.EloqSession session, Shard shard, String page,
            int limit) {
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
        return session.scan(this.table(), start, end, type, limit);
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

    /**
     * Compute the limit to pass to native scan.
     * Returns 0 (no limit) if query has no limit.
     * Otherwise returns limit + 1 to allow detecting more pages.
     */
    protected static int queryLimit(Query query) {
        if (query.noLimit()) {
            return 0;
        }
        long limit = query.limit();
        // Add 1 to detect if there are more results for paging
        // Cap at Integer.MAX_VALUE to avoid overflow
        if (limit >= Integer.MAX_VALUE - 1) {
            return 0; // Effectively no limit
        }
        return (int) (limit + 1);
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
