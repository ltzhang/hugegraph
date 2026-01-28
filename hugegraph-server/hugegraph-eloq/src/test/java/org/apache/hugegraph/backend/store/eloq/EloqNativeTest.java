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

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Smoke test for EloqRocks JNI bridge.
 *
 * Tests run in alphabetical order so lifecycle methods execute in the
 * expected sequence. Each test crashes on failure (via assert) as required
 * by project convention.
 *
 * Prerequisites:
 *   - libeloqjni.so must be built and on java.library.path
 *   - EloqRocks C++ library must be built (eloqrocks/bld/)
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class EloqNativeTest {

    private static final String TEST_TABLE = "smoke_test_table";

    @BeforeClass
    public static void setUp() {
        assertTrue("Native library must be loaded", EloqNative.isLoaded());
        // Initialize EloqRocks with default config (single-node mode)
        EloqNative.init("");
    }

    @AfterClass
    public static void tearDown() {
        // Clean up: drop test table if it exists
        if (EloqNative.hasTable(TEST_TABLE)) {
            EloqNative.dropTable(TEST_TABLE);
        }
        EloqNative.shutdown();
    }

    @Test
    public void test01_CreateAndDropTable() {
        // Create table
        EloqNative.createTable(TEST_TABLE);
        assertTrue("Table should exist after creation",
                   EloqNative.hasTable(TEST_TABLE));

        // Drop table
        EloqNative.dropTable(TEST_TABLE);
        assertFalse("Table should not exist after drop",
                    EloqNative.hasTable(TEST_TABLE));

        // Re-create for subsequent tests
        EloqNative.createTable(TEST_TABLE);
        assertTrue("Table should exist after re-creation",
                   EloqNative.hasTable(TEST_TABLE));
    }

    @Test
    public void test02_PutGetDelete() {
        byte[] key = toBytes("key1");
        byte[] value = toBytes("value1");

        // Put
        EloqNative.put(0L, TEST_TABLE, key, value);

        // Get
        byte[] result = EloqNative.get(0L, TEST_TABLE, key);
        assertNotNull("Get should return value for existing key", result);
        assertArrayEquals("Value should match what was put", value, result);

        // Delete
        EloqNative.delete(0L, TEST_TABLE, key);

        // Get after delete
        byte[] deleted = EloqNative.get(0L, TEST_TABLE, key);
        assertNull("Get should return null after delete", deleted);
    }

    @Test
    public void test03_PutGetBinaryData() {
        // Test with binary data (not valid UTF-8)
        byte[] key = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF};
        byte[] value = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE,
                                  (byte) 0xEF};

        EloqNative.put(0L, TEST_TABLE, key, value);

        byte[] result = EloqNative.get(0L, TEST_TABLE, key);
        assertNotNull("Binary key-value should be retrievable", result);
        assertArrayEquals("Binary value should match exactly", value, result);

        // Clean up
        EloqNative.delete(0L, TEST_TABLE, key);
    }

    @Test
    public void test04_ScanRange() {
        // Insert 5 ordered keys: a, b, c, d, e
        String[] keys = {"a", "b", "c", "d", "e"};
        for (String k : keys) {
            EloqNative.put(0L, TEST_TABLE, toBytes(k), toBytes("val_" + k));
        }

        // Scan all keys (null start/end = full range)
        byte[][][] allResults = EloqNative.scan(
            0L, TEST_TABLE, null, null, true, true, 0);
        assertNotNull("Scan should return results", allResults);
        assertEquals("Scan result should have 2 arrays (keys, values)",
                     2, allResults.length);
        assertTrue("Should find at least 5 keys",
                   allResults[0].length >= 5);

        // Verify ordering: keys should be lexicographically sorted
        for (int i = 1; i < allResults[0].length; i++) {
            String prev = fromBytes(allResults[0][i - 1]);
            String curr = fromBytes(allResults[0][i]);
            assertTrue("Keys should be sorted: " + prev + " <= " + curr,
                       prev.compareTo(curr) <= 0);
        }

        // Scan range [b, d) — startInclusive=true, endInclusive=false
        byte[][][] rangeResults = EloqNative.scan(
            0L, TEST_TABLE, toBytes("b"), toBytes("d"),
            true, false, 0);
        assertNotNull("Range scan should return results", rangeResults);
        assertEquals("Range [b,d) should contain 2 keys (b,c)",
                     2, rangeResults[0].length);
        assertEquals("First key should be 'b'", "b",
                     fromBytes(rangeResults[0][0]));
        assertEquals("Second key should be 'c'", "c",
                     fromBytes(rangeResults[0][1]));

        // Scan with limit
        byte[][][] limitResults = EloqNative.scan(
            0L, TEST_TABLE, null, null, true, true, 3);
        assertNotNull("Limited scan should return results", limitResults);
        assertEquals("Limited scan should return exactly 3 keys",
                     3, limitResults[0].length);

        // Clean up
        for (String k : keys) {
            EloqNative.delete(0L, TEST_TABLE, toBytes(k));
        }
    }

    @Test
    public void test05_Transaction() {
        byte[] key = toBytes("tx_key");
        byte[] value = toBytes("tx_value");

        // Test commit
        long tx1 = EloqNative.startTx();
        assertTrue("Transaction handle should be non-zero", tx1 != 0L);
        EloqNative.put(tx1, TEST_TABLE, key, value);
        EloqNative.commitTx(tx1);

        // Verify committed data is visible
        byte[] result = EloqNative.get(0L, TEST_TABLE, key);
        assertNotNull("Committed data should be visible", result);
        assertArrayEquals("Committed value should match", value, result);

        // Test abort
        byte[] key2 = toBytes("tx_key_abort");
        byte[] value2 = toBytes("tx_value_abort");

        long tx2 = EloqNative.startTx();
        EloqNative.put(tx2, TEST_TABLE, key2, value2);
        EloqNative.abortTx(tx2);

        // Verify aborted data is NOT visible
        byte[] abortResult = EloqNative.get(0L, TEST_TABLE, key2);
        assertNull("Aborted data should not be visible", abortResult);

        // Clean up committed key
        EloqNative.delete(0L, TEST_TABLE, key);
    }

    @Test
    public void test06_MultipleTablesIsolation() {
        String table2 = "smoke_test_table_2";
        EloqNative.createTable(table2);

        byte[] key = toBytes("shared_key");
        byte[] val1 = toBytes("table1_value");
        byte[] val2 = toBytes("table2_value");

        // Put same key in both tables with different values
        EloqNative.put(0L, TEST_TABLE, key, val1);
        EloqNative.put(0L, table2, key, val2);

        // Verify isolation
        byte[] r1 = EloqNative.get(0L, TEST_TABLE, key);
        byte[] r2 = EloqNative.get(0L, table2, key);

        assertArrayEquals("Table 1 should have its own value", val1, r1);
        assertArrayEquals("Table 2 should have its own value", val2, r2);

        // Clean up
        EloqNative.delete(0L, TEST_TABLE, key);
        EloqNative.delete(0L, table2, key);
        EloqNative.dropTable(table2);
    }

    private static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String fromBytes(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
