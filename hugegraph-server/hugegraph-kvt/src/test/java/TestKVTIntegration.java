import org.apache.hugegraph.backend.store.kvt.*;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.query.*;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.backend.store.BackendMutation;
import org.apache.hugegraph.backend.tx.IsolationLevel;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.HugeKeys;
import org.apache.hugegraph.type.define.IndexType;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Comprehensive integration test suite for KVT backend
 */
public class TestKVTIntegration {

    static {
        // Load the JNI library
        try {
            String libPath = System.getProperty("user.dir") + 
                           "/target/native/libkvtjni.so";
            System.load(libPath);
            System.out.println("Loaded library from: " + libPath);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load library: " + e.getMessage());
            throw e;
        }
    }
    
    private static KVTStoreProvider provider;
    private static KVTStore store;
    private static HugeConfig config;
    
    public static void main(String[] args) {
        System.out.println("=== KVT Integration Test Suite ===\n");
        
        int totalTests = 0;
        int passedTests = 0;
        int failedTests = 0;
        
        try {
            // Setup
            setUp();
            
            // Run test suites
            System.out.println("=== Running Test Suites ===\n");
            
            // 1. Basic CRUD Tests
            System.out.println("1. Basic CRUD Operations");
            if (runTestSuite(TestKVTIntegration::testBasicCRUD)) {
                passedTests++;
            } else {
                failedTests++;
            }
            totalTests++;
            
            // 2. Transaction Tests
            System.out.println("\n2. Transaction Management");
            if (runTestSuite(TestKVTIntegration::testTransactions)) {
                passedTests++;
            } else {
                failedTests++;
            }
            totalTests++;
            
            // 3. Query Tests
            System.out.println("\n3. Query Operations");
            if (runTestSuite(TestKVTIntegration::testQueries)) {
                passedTests++;
            } else {
                failedTests++;
            }
            totalTests++;
            
            // 4. Index Tests
            System.out.println("\n4. Index Management");
            if (runTestSuite(TestKVTIntegration::testIndexes)) {
                passedTests++;
            } else {
                failedTests++;
            }
            totalTests++;
            
            // 5. Batch Tests
            System.out.println("\n5. Batch Operations");
            if (runTestSuite(TestKVTIntegration::testBatchOperations)) {
                passedTests++;
            } else {
                failedTests++;
            }
            totalTests++;
            
            // 6. Cache Tests
            System.out.println("\n6. Query Cache");
            if (runTestSuite(TestKVTIntegration::testQueryCache)) {
                passedTests++;
            } else {
                failedTests++;
            }
            totalTests++;
            
            // 7. Concurrent Tests
            System.out.println("\n7. Concurrent Operations");
            if (runTestSuite(TestKVTIntegration::testConcurrency)) {
                passedTests++;
            } else {
                failedTests++;
            }
            totalTests++;
            
            // 8. Performance Tests
            System.out.println("\n8. Performance Benchmarks");
            if (runTestSuite(TestKVTIntegration::testPerformance)) {
                passedTests++;
            } else {
                failedTests++;
            }
            totalTests++;
            
            // 9. Error Handling Tests
            System.out.println("\n9. Error Handling");
            if (runTestSuite(TestKVTIntegration::testErrorHandling)) {
                passedTests++;
            } else {
                failedTests++;
            }
            totalTests++;
            
            // 10. Edge Cases
            System.out.println("\n10. Edge Cases");
            if (runTestSuite(TestKVTIntegration::testEdgeCases)) {
                passedTests++;
            } else {
                failedTests++;
            }
            totalTests++;
            
            // Print summary
            System.out.println("\n=== TEST SUMMARY ===");
            System.out.printf("Total Tests: %d\n", totalTests);
            System.out.printf("Passed: %d (%.1f%%)\n", passedTests, 
                            (double)passedTests * 100 / totalTests);
            System.out.printf("Failed: %d (%.1f%%)\n", failedTests,
                            (double)failedTests * 100 / totalTests);
            
            if (failedTests == 0) {
                System.out.println("\n✓ ALL TESTS PASSED!");
            } else {
                System.out.println("\n✗ SOME TESTS FAILED");
                System.exit(1);
            }
            
        } catch (Exception e) {
            System.err.println("\n=== TEST SUITE FAILED ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            tearDown();
        }
    }
    
    private static void setUp() throws Exception {
        System.out.println("Setting up test environment...");
        
        // Initialize KVT
        KVTNative.KVTError error = KVTNative.initialize();
        if (error != KVTNative.KVTError.SUCCESS) {
            throw new RuntimeException("Failed to initialize KVT: " + error);
        }
        
        // Create provider and store
        provider = new KVTStoreProvider();
        config = createTestConfig();
        
        // Initialize provider
        provider.init();
        
        // Create stores
        String graph = "test_graph";
        store = (KVTStore) provider.newGraphStore(graph);
        store.open(config);
        store.init();
        
        System.out.println("Test environment ready\n");
    }
    
    private static void tearDown() {
        try {
            if (store != null) {
                store.close();
            }
            if (provider != null) {
                provider.close();
            }
            KVTNative.shutdown();
        } catch (Exception e) {
            System.err.println("Error during teardown: " + e.getMessage());
        }
    }
    
    private static HugeConfig createTestConfig() {
        // Create test configuration
        Properties props = new Properties();
        props.setProperty("backend", "kvt");
        props.setProperty("kvt.cache.enabled", "true");
        props.setProperty("kvt.batch.enabled", "true");
        props.setProperty("kvt.query.optimizer.enabled", "true");
        
        // Note: This is a simplified config creation
        // Actual implementation would use HugeConfig properly
        return null; // Placeholder
    }
    
    private static boolean runTestSuite(Runnable test) {
        try {
            test.run();
            System.out.println("   ✓ Passed");
            return true;
        } catch (Exception e) {
            System.out.println("   ✗ Failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        } catch (AssertionError e) {
            System.out.println("   ✗ Failed: " + e.getMessage());
            return false;
        }
    }
    
    // Test Methods
    
    private static void testBasicCRUD() throws Exception {
        KVTSession session = new KVTSession(config, "test");
        
        // Test Create
        Id vertexId = IdGenerator.of(1);
        KVTBackendEntry entry = new KVTBackendEntry(HugeType.VERTEX, vertexId);
        entry.column("name".getBytes(), "Test".getBytes());
        entry.column("age".getBytes(), "30".getBytes());
        
        BackendMutation mutation = new BackendMutation();
        mutation.add(entry, BackendMutation.Action.INSERT);
        store.mutate(mutation);
        
        // Test Read
        IdQuery query = new IdQuery(HugeType.VERTEX);
        query.query(vertexId);
        Iterator<BackendEntry> results = store.query(query);
        
        assert results.hasNext() : "Should find created vertex";
        BackendEntry found = results.next();
        assert found.id().equals(vertexId) : "ID mismatch";
        
        // Test Update
        entry.column("age".getBytes(), "31".getBytes());
        mutation = new BackendMutation();
        mutation.add(entry, BackendMutation.Action.UPDATE);
        store.mutate(mutation);
        
        // Test Delete
        mutation = new BackendMutation();
        mutation.add(entry, BackendMutation.Action.DELETE);
        store.mutate(mutation);
        
        // Verify deletion
        results = store.query(query);
        assert !results.hasNext() : "Vertex should be deleted";
    }
    
    private static void testTransactions() throws Exception {
        // Test commit
        store.beginTx();
        
        Id id1 = IdGenerator.of(100);
        KVTBackendEntry entry1 = new KVTBackendEntry(HugeType.VERTEX, id1);
        entry1.column("tx".getBytes(), "commit".getBytes());
        
        BackendMutation mutation = new BackendMutation();
        mutation.add(entry1, BackendMutation.Action.INSERT);
        store.mutate(mutation);
        
        store.commitTx();
        
        // Verify committed data
        IdQuery query = new IdQuery(HugeType.VERTEX);
        query.query(id1);
        Iterator<BackendEntry> results = store.query(query);
        assert results.hasNext() : "Committed data should exist";
        
        // Test rollback
        store.beginTx();
        
        Id id2 = IdGenerator.of(101);
        KVTBackendEntry entry2 = new KVTBackendEntry(HugeType.VERTEX, id2);
        entry2.column("tx".getBytes(), "rollback".getBytes());
        
        mutation = new BackendMutation();
        mutation.add(entry2, BackendMutation.Action.INSERT);
        store.mutate(mutation);
        
        store.rollbackTx();
        
        // Verify rolled back data doesn't exist
        query = new IdQuery(HugeType.VERTEX);
        query.query(id2);
        results = store.query(query);
        assert !results.hasNext() : "Rolled back data should not exist";
    }
    
    private static void testQueries() throws Exception {
        // Add test data
        for (int i = 0; i < 10; i++) {
            Id id = IdGenerator.of(200 + i);
            KVTBackendEntry entry = new KVTBackendEntry(HugeType.VERTEX, id);
            entry.column("type".getBytes(), "test".getBytes());
            entry.column("index".getBytes(), String.valueOf(i).getBytes());
            
            BackendMutation mutation = new BackendMutation();
            mutation.add(entry, BackendMutation.Action.INSERT);
            store.mutate(mutation);
        }
        
        // Test ID query
        IdQuery idQuery = new IdQuery(HugeType.VERTEX);
        idQuery.query(IdGenerator.of(205));
        Iterator<BackendEntry> results = store.query(idQuery);
        assert results.hasNext() : "ID query should return result";
        
        // Test condition query
        ConditionQuery condQuery = new ConditionQuery(HugeType.VERTEX);
        condQuery.limit(5);
        results = store.query(condQuery);
        
        int count = 0;
        while (results.hasNext()) {
            results.next();
            count++;
        }
        assert count <= 5 : "Limit should be respected";
    }
    
    private static void testIndexes() throws Exception {
        KVTSession session = new KVTSession(config, "test");
        KVTIndexManager indexManager = new KVTIndexManager(session);
        
        // Initialize index tables
        indexManager.initIndexTables();
        
        // Add index entries
        Id indexId = IdGenerator.of("age_index");
        Id elementId = IdGenerator.of(300);
        
        indexManager.addIndex(IndexType.RANGE, indexId, elementId, 25, 0);
        
        // Query index
        Iterator<Id> results = indexManager.queryIndex(
            IndexType.RANGE, indexId, 25, 10);
        
        assert results.hasNext() : "Index query should return results";
        Id foundId = results.next();
        assert foundId.equals(elementId) : "Index should return correct element";
        
        // Remove index entry
        indexManager.removeIndex(IndexType.RANGE, indexId, elementId, 25);
        
        // Verify removal
        results = indexManager.queryIndex(IndexType.RANGE, indexId, 25, 10);
        assert !results.hasNext() : "Index entry should be removed";
    }
    
    private static void testBatchOperations() throws Exception {
        KVTSessionV2 session = new KVTSessionV2(config, "test");
        session.enableBatch(100);
        
        // Batch insert
        session.beginTx();
        
        for (int i = 0; i < 500; i++) {
            Id id = IdGenerator.of(400 + i);
            KVTBackendEntry entry = new KVTBackendEntry(HugeType.VERTEX, id);
            entry.column("batch".getBytes(), "true".getBytes());
            
            BackendMutation mutation = new BackendMutation();
            mutation.add(entry, BackendMutation.Action.INSERT);
            store.mutate(mutation);
        }
        
        session.flushBatch();
        session.commitTx();
        
        // Verify batch insert
        Id testId = IdGenerator.of(450);
        IdQuery query = new IdQuery(HugeType.VERTEX);
        query.query(testId);
        Iterator<BackendEntry> results = store.query(query);
        
        assert results.hasNext() : "Batch inserted data should exist";
    }
    
    private static void testQueryCache() throws Exception {
        // Create cache
        KVTQueryCache cache = new KVTQueryCache(100, 60000, true);
        
        // Create query
        IdQuery query = new IdQuery(HugeType.VERTEX);
        query.query(IdGenerator.of(500));
        
        // First query - cache miss
        Iterator<BackendEntry> result1 = cache.get(query, 1);
        assert result1 == null : "First query should be cache miss";
        
        // Add to cache
        List<BackendEntry> entries = new ArrayList<>();
        entries.add(new KVTBackendEntry(HugeType.VERTEX, IdGenerator.of(500)));
        cache.put(query, 1, entries.iterator());
        
        // Second query - cache hit
        Iterator<BackendEntry> result2 = cache.get(query, 1);
        assert result2 != null : "Second query should be cache hit";
        
        // Check statistics
        KVTQueryCache.CacheStatistics stats = cache.getStatistics();
        assert stats.hits > 0 : "Should have cache hits";
    }
    
    private static void testConcurrency() throws Exception {
        int numThreads = 10;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        Id id = IdGenerator.of(1000 + threadId * 1000 + i);
                        KVTBackendEntry entry = new KVTBackendEntry(
                            HugeType.VERTEX, id);
                        entry.column("thread".getBytes(), 
                                   String.valueOf(threadId).getBytes());
                        
                        BackendMutation mutation = new BackendMutation();
                        mutation.add(entry, BackendMutation.Action.INSERT);
                        
                        synchronized(store) {
                            store.beginTx();
                            store.mutate(mutation);
                            store.commitTx();
                        }
                        
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        assert errorCount.get() == 0 : "Concurrent operations had errors: " + 
                                       errorCount.get();
        assert successCount.get() == numThreads * opsPerThread : 
               "Not all operations succeeded";
    }
    
    private static void testPerformance() throws Exception {
        long startTime = System.currentTimeMillis();
        
        // Write performance
        for (int i = 0; i < 1000; i++) {
            Id id = IdGenerator.of(10000 + i);
            KVTBackendEntry entry = new KVTBackendEntry(HugeType.VERTEX, id);
            entry.column("perf".getBytes(), "test".getBytes());
            
            BackendMutation mutation = new BackendMutation();
            mutation.add(entry, BackendMutation.Action.INSERT);
            store.mutate(mutation);
        }
        
        long writeTime = System.currentTimeMillis() - startTime;
        
        // Read performance
        startTime = System.currentTimeMillis();
        
        for (int i = 0; i < 1000; i++) {
            IdQuery query = new IdQuery(HugeType.VERTEX);
            query.query(IdGenerator.of(10000 + i));
            Iterator<BackendEntry> results = store.query(query);
            if (results.hasNext()) {
                results.next();
            }
        }
        
        long readTime = System.currentTimeMillis() - startTime;
        
        System.out.printf("   Write: 1000 ops in %dms (%.0f ops/sec)\n",
                         writeTime, 1000.0 * 1000 / writeTime);
        System.out.printf("   Read: 1000 ops in %dms (%.0f ops/sec)\n",
                         readTime, 1000.0 * 1000 / readTime);
        
        assert writeTime < 5000 : "Write performance too slow";
        assert readTime < 5000 : "Read performance too slow";
    }
    
    private static void testErrorHandling() throws Exception {
        // Test invalid operations
        try {
            // Query non-existent data
            IdQuery query = new IdQuery(HugeType.VERTEX);
            query.query(IdGenerator.of(99999));
            Iterator<BackendEntry> results = store.query(query);
            assert !results.hasNext() : "Should return empty for non-existent";
            
            // Test transaction conflicts
            // This would require more sophisticated setup
            
        } catch (Exception e) {
            throw new AssertionError("Error handling failed: " + e.getMessage());
        }
    }
    
    private static void testEdgeCases() throws Exception {
        // Empty entry
        Id emptyId = IdGenerator.of(20000);
        KVTBackendEntry emptyEntry = new KVTBackendEntry(HugeType.VERTEX, emptyId);
        
        BackendMutation mutation = new BackendMutation();
        mutation.add(emptyEntry, BackendMutation.Action.INSERT);
        store.mutate(mutation);
        
        // Large entry
        Id largeId = IdGenerator.of(20001);
        KVTBackendEntry largeEntry = new KVTBackendEntry(HugeType.VERTEX, largeId);
        byte[] largeValue = new byte[100000];
        Arrays.fill(largeValue, (byte)'X');
        largeEntry.column("large".getBytes(), largeValue);
        
        mutation = new BackendMutation();
        mutation.add(largeEntry, BackendMutation.Action.INSERT);
        store.mutate(mutation);
        
        // Verify both
        IdQuery query = new IdQuery(HugeType.VERTEX);
        query.query(emptyId, largeId);
        Iterator<BackendEntry> results = store.query(query);
        
        int count = 0;
        while (results.hasNext()) {
            results.next();
            count++;
        }
        
        assert count == 2 : "Should handle edge cases";
    }
}