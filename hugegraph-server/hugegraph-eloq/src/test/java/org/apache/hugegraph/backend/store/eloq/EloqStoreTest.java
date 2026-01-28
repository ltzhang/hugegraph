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
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.query.IdQuery;
import org.apache.hugegraph.backend.serializer.BinaryBackendEntry;
import org.apache.hugegraph.backend.serializer.BytesBuffer;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.backend.store.BackendEntry.BackendColumn;
import org.apache.hugegraph.backend.store.BackendFeatures;
import org.apache.hugegraph.backend.store.BackendMutation;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.Action;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for EloqStore: store lifecycle, mutations, queries, counters,
 * and system version through the BackendStore API.
 */
public class EloqStoreTest {

    private static EloqStore.EloqSchemaStore schemaStore;
    private static EloqStore.EloqGraphStore graphStore;
    private static EloqStore.EloqSystemStore systemStore;
    private static HugeConfig config;

    @BeforeClass
    public static void init() throws Exception {
        EloqTestBase.initOnce();
        config = new HugeConfig(new PropertiesConfiguration());
        // Set store.graph so isGraphStore detection works
        config.setProperty("store.graph", "g");

        EloqStoreProvider provider = new EloqStoreProvider();
        provider.open("test_graph");

        schemaStore = new EloqStore.EloqSchemaStore(
                provider, "test_graph", "m");
        graphStore = new EloqStore.EloqGraphStore(
                provider, "test_graph", "g");
        systemStore = new EloqStore.EloqSystemStore(
                provider, "test_graph", "s");

        schemaStore.open(config);
        graphStore.open(config);
        systemStore.open(config);

        schemaStore.init();
        graphStore.init();
        systemStore.init();
    }

    @AfterClass
    public static void shutdown() {
        if (schemaStore != null) {
            schemaStore.clear(false);
            schemaStore.close();
        }
        if (graphStore != null) {
            graphStore.clear(false);
            graphStore.close();
        }
        if (systemStore != null) {
            systemStore.clear(false);
            systemStore.close();
        }
    }

    @Test
    public void testStoreOpenedAndFeatures() {
        Assert.assertTrue(schemaStore.opened());
        Assert.assertTrue(graphStore.opened());
        Assert.assertTrue(systemStore.opened());

        BackendFeatures features = schemaStore.features();
        Assert.assertNotNull(features);
        Assert.assertTrue(features.supportsScanKeyPrefix());
        Assert.assertTrue(features.supportsScanKeyRange());
        Assert.assertTrue(features.supportsTransaction());
        Assert.assertFalse(features.supportsSharedStorage());
        Assert.assertFalse(features.supportsSnapshot());
    }

    @Test
    public void testStoreInitialized() {
        Assert.assertTrue(schemaStore.initialized());
        Assert.assertTrue(graphStore.initialized());
        Assert.assertTrue(systemStore.initialized());
    }

    @Test
    public void testSchemaStoreIdentity() {
        Assert.assertTrue(schemaStore.isSchemaStore());
        Assert.assertFalse(graphStore.isSchemaStore());
        Assert.assertEquals("m", schemaStore.store());
        Assert.assertEquals("g", graphStore.store());
        Assert.assertEquals("s", systemStore.store());
        Assert.assertEquals("test_graph", schemaStore.database());
    }

    @Test
    public void testMutateAndQuery() {
        // Build a vertex entry with a properly serialized key
        BytesBuffer buf = BytesBuffer.allocate(16);
        buf.writeId(IdGenerator.of("test_vertex_1"));
        byte[] key = buf.bytes();
        byte[] value = "test_value".getBytes();

        BinaryBackendEntry entry = new BinaryBackendEntry(
                HugeType.VERTEX, key);
        entry.column(BackendColumn.of(key, value));

        // Mutate: INSERT
        BackendMutation mutation = new BackendMutation();
        mutation.add(entry, Action.INSERT);
        graphStore.beginTx();
        graphStore.mutate(mutation);
        graphStore.commitTx();

        // Query by id
        IdQuery query = new IdQuery(HugeType.VERTEX, entry.id());
        Iterator<BackendEntry> results = graphStore.query(query);
        Assert.assertTrue(results.hasNext());
        BackendEntry result = results.next();
        Assert.assertNotNull(result);
        Assert.assertFalse(results.hasNext());

        // Mutate: DELETE
        BackendMutation deleteMutation = new BackendMutation();
        deleteMutation.add(entry, Action.DELETE);
        graphStore.beginTx();
        graphStore.mutate(deleteMutation);
        graphStore.commitTx();

        // Verify deleted
        results = graphStore.query(query);
        Assert.assertFalse(results.hasNext());
    }

    @Test
    public void testRollbackTx() {
        BytesBuffer buf = BytesBuffer.allocate(16);
        buf.writeId(IdGenerator.of("rollback_vertex"));
        byte[] key = buf.bytes();
        byte[] value = "rollback_val".getBytes();

        BinaryBackendEntry entry = new BinaryBackendEntry(
                HugeType.VERTEX, key);
        entry.column(BackendColumn.of(key, value));

        BackendMutation mutation = new BackendMutation();
        mutation.add(entry, Action.INSERT);
        graphStore.beginTx();
        graphStore.mutate(mutation);
        graphStore.rollbackTx();

        // Should not be visible after rollback
        IdQuery query = new IdQuery(HugeType.VERTEX, entry.id());
        Iterator<BackendEntry> results = graphStore.query(query);
        Assert.assertFalse(results.hasNext());
    }

    @Test
    public void testSchemaStoreCounters() {
        // Initial counter should be 0
        long initial = schemaStore.getCounter(HugeType.VERTEX_LABEL);

        // Increase by 5
        schemaStore.increaseCounter(HugeType.VERTEX_LABEL, 5);
        long after = schemaStore.getCounter(HugeType.VERTEX_LABEL);
        Assert.assertEquals(initial + 5, after);

        // Increase by 3 more
        schemaStore.increaseCounter(HugeType.VERTEX_LABEL, 3);
        long afterMore = schemaStore.getCounter(HugeType.VERTEX_LABEL);
        Assert.assertEquals(initial + 8, afterMore);
    }

    @Test
    public void testSystemStoreVersion() {
        String version = systemStore.storedVersion();
        Assert.assertNotNull(version);
        Assert.assertEquals("1.0", version);
    }

    @Test
    public void testTruncate() {
        // Insert some data
        BytesBuffer buf = BytesBuffer.allocate(16);
        buf.writeId(IdGenerator.of("truncate_vertex"));
        byte[] key = buf.bytes();
        byte[] value = "truncate_val".getBytes();

        BinaryBackendEntry entry = new BinaryBackendEntry(
                HugeType.VERTEX, key);
        entry.column(BackendColumn.of(key, value));

        BackendMutation mutation = new BackendMutation();
        mutation.add(entry, Action.INSERT);
        graphStore.beginTx();
        graphStore.mutate(mutation);
        graphStore.commitTx();

        // Verify data exists
        IdQuery query = new IdQuery(HugeType.VERTEX, entry.id());
        Assert.assertTrue(graphStore.query(query).hasNext());

        // Truncate
        graphStore.truncate();

        // Data should be gone after truncate
        Assert.assertFalse(graphStore.query(query).hasNext());

        // Store should still be initialized
        Assert.assertTrue(graphStore.initialized());
    }
}
