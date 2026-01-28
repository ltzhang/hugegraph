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

import java.util.Iterator;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.Id.IdType;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.query.IdPrefixQuery;
import org.apache.hugegraph.backend.query.IdQuery;
import org.apache.hugegraph.backend.query.IdRangeQuery;
import org.apache.hugegraph.backend.query.Query;
import org.apache.hugegraph.backend.serializer.BinaryBackendEntry;
import org.apache.hugegraph.backend.serializer.BytesBuffer;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumn;
import org.apache.hugegraph.config.HugeConfig;
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
        EloqTestBase.initOnce();
        HugeConfig config = new HugeConfig(new PropertiesConfiguration());
        sessions = new EloqSessions(config, DATABASE, STORE);
        sessions.open();
        table = new EloqTable(DATABASE, "test_vertex");
    }

    @AfterClass
    public static void shutdown() {
        if (sessions != null) {
            sessions.close();
        }
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

        Id id = IdGenerator.of(key, IdType.STRING);
        IdQuery query = new IdQuery(HugeType.VERTEX, id);
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

        Id prefixId = IdGenerator.of(prefix, IdType.STRING);
        IdPrefixQuery pq = new IdPrefixQuery(HugeType.VERTEX, prefixId);
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

        // Use properly binary-serialized String Ids so that
        // BinaryEntryIterator.parseId() can round-trip them correctly
        String[] idStrings = {"a", "b", "c", "d", "e"};
        for (String s : idStrings) {
            Id id = IdGenerator.of(s);
            BytesBuffer buf = BytesBuffer.allocate(8);
            buf.writeId(id);
            byte[] key = buf.bytes();
            byte[] value = s.getBytes();
            BinaryBackendEntry entry = new BinaryBackendEntry(
                    HugeType.VERTEX, key);
            entry.column(BackendColumn.of(key, value));
            table.insert(session, entry);
        }
        session.commit();

        // Range query [b, d] inclusive both ends — expects 3 entries
        Id startId = IdGenerator.of("b");
        Id endId = IdGenerator.of("d");
        BytesBuffer startBuf = BytesBuffer.allocate(8);
        startBuf.writeId(startId);
        BytesBuffer endBuf = BytesBuffer.allocate(8);
        endBuf.writeId(endId);
        BinaryBackendEntry.BinaryId start =
                new BinaryBackendEntry.BinaryId(startBuf.bytes(), startId);
        BinaryBackendEntry.BinaryId end =
                new BinaryBackendEntry.BinaryId(endBuf.bytes(), endId);

        IdRangeQuery rq = new IdRangeQuery(HugeType.VERTEX, null,
                                            start, true, end, true);
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
