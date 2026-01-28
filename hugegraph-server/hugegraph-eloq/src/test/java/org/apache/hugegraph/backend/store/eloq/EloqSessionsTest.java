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

import java.util.Arrays;
import java.util.List;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumn;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumnIterator;
import org.apache.hugegraph.config.HugeConfig;
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
        EloqTestBase.initOnce();
        HugeConfig config = new HugeConfig(new PropertiesConfiguration());
        sessions = new EloqSessions(config, DATABASE, STORE);
        sessions.open();
    }

    @AfterClass
    public static void shutdown() {
        if (sessions != null) {
            sessions.close();
        }
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
                TABLE, new byte[]{(byte) 'b'},
                new byte[]{(byte) 'd'}, type)) {
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
                TABLE, new byte[]{(byte) 'b'},
                new byte[]{(byte) 'd'}, type)) {
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
