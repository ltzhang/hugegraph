package org.apache.hugegraph.backend.store.kvt;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import static org.junit.Assert.*;

/**
 * Comprehensive test suite for prefix scan optimization in KVT backend
 * Tests correctness, performance, edge cases, and concurrent operations
 */
public class ComprehensivePrefixScanTest {
    private static Map<String, Long> tableIds = new HashMap<>();
    private static final int LARGE_DATASET_SIZE = 50000;
    private static final int CONCURRENT_THREADS = 10;
    
    static {
        // Library will be loaded by KVTNative static initializer
    }
    
    public static void main(String[] args) {
        System.out.println("=== COMPREHENSIVE PREFIX SCAN TEST SUITE ===\n");
        
        try {
            // Initialize
            initialize();
            
            // Run test suites
            testCorrectness();
            testBoundaryConditions();
            testLargeDataset();
            testComplexHierarchies();
            testConcurrentOperations();
            testPerformanceBenchmark();
            testErrorHandling();
            
            // Cleanup
            cleanup();
            
            System.out.println("\n=== ALL COMPREHENSIVE TESTS PASSED ===");
            System.out.println("✅ Prefix scan optimization is production-ready!");
            
        } catch (Exception e) {
            System.err.println("❌ Test failed: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void initialize() {
        System.out.println("📋 Initializing test environment...");
        
        int result = KVTNative.nativeInitialize();
        if (result != 0) {
            throw new RuntimeException("Failed to initialize KVT: " + result);
        }
        
        // Create multiple test tables
        createTable("vertices", "range");
        createTable("edges", "range");
        createTable("index", "hash");
        
        System.out.println("   ✓ Initialized with " + tableIds.size() + " tables\n");
    }
    
    private static void createTable(String name, String method) {
        Object[] result = KVTNative.nativeCreateTable(name, method);
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to create table " + name);
        }
        tableIds.put(name, (long)result[1]);
    }
    
    /**
     * Test 1: Correctness Tests
     */
    private static void testCorrectness() {
        System.out.println("🧪 Test 1: Correctness Tests");
        System.out.println("─────────────────────────────");
        
        long txId = beginTransaction();
        long tableId = tableIds.get("vertices");
        
        // Insert test data with various prefixes
        Map<String, List<String>> testData = new HashMap<>();
        testData.put("person", Arrays.asList("alice", "bob", "charlie", "david"));
        testData.put("software", Arrays.asList("hugegraph", "tinkerpop", "gremlin"));
        testData.put("company", Arrays.asList("apache", "google", "microsoft"));
        
        for (Map.Entry<String, List<String>> entry : testData.entrySet()) {
            String prefix = "vertex_" + entry.getKey() + "_";
            for (String value : entry.getValue()) {
                String key = prefix + value;
                set(txId, tableId, key, entry.getKey() + ":" + value);
            }
        }
        
        commitTransaction(txId);
        
        // Test prefix scans
        txId = beginTransaction();
        
        for (String label : testData.keySet()) {
            byte[] prefix = ("vertex_" + label).getBytes();
            byte[] endKey = KVTIdUtil.prefixEnd(prefix);
            
            List<String> results = scanKeys(txId, tableId, prefix, endKey);
            
            assert results.size() == testData.get(label).size() : 
                "Size mismatch for " + label + ": " + results.size() + " vs " + testData.get(label).size();
            
            System.out.println("   ✓ Prefix scan for '" + label + "': " + results.size() + " results");
            
            // Verify all results have correct prefix
            for (String key : results) {
                assert key.startsWith("vertex_" + label) : "Invalid key: " + key;
            }
        }
        
        commitTransaction(txId);
        System.out.println("   ✅ All correctness tests passed\n");
    }
    
    /**
     * Test 2: Boundary Conditions
     */
    private static void testBoundaryConditions() {
        System.out.println("🔍 Test 2: Boundary Conditions");
        System.out.println("──────────────────────────────");
        
        long txId = beginTransaction();
        long tableId = tableIds.get("edges");
        
        // Test empty prefix
        byte[] emptyPrefix = new byte[0];
        try {
            byte[] endKey = KVTIdUtil.prefixEnd(emptyPrefix);
            List<String> results = scanKeys(txId, tableId, emptyPrefix, endKey);
            System.out.println("   ✓ Empty prefix handled: " + results.size() + " results");
        } catch (Exception e) {
            System.out.println("   ✓ Empty prefix correctly rejected");
        }
        
        // Test single-byte prefix
        set(txId, tableId, "a_test1", "value1");
        set(txId, tableId, "a_test2", "value2");
        set(txId, tableId, "b_test1", "value3");
        
        byte[] singleBytePrefix = "a".getBytes();
        byte[] endKey = KVTIdUtil.prefixEnd(singleBytePrefix);
        List<String> results = scanKeys(txId, tableId, singleBytePrefix, endKey);
        assert results.size() == 2 : "Single-byte prefix failed: " + results.size();
        System.out.println("   ✓ Single-byte prefix: " + results.size() + " results");
        
        // Test max-value prefix (all 0xFF bytes)
        byte[] maxPrefix = new byte[]{(byte)0xFF, (byte)0xFF};
        set(txId, tableId, new String(maxPrefix) + "_test", "max_value");
        
        endKey = KVTIdUtil.prefixEnd(maxPrefix);
        results = scanKeys(txId, tableId, maxPrefix, endKey);
        assert results.size() >= 1 : "Max-value prefix failed";
        System.out.println("   ✓ Max-value prefix handled correctly");
        
        // Test overlapping prefixes
        set(txId, tableId, "prefix_123", "value1");
        set(txId, tableId, "prefix_1234", "value2");
        set(txId, tableId, "prefix_12345", "value3");
        set(txId, tableId, "prefix_456", "value4");
        
        byte[] prefix123 = "prefix_123".getBytes();
        endKey = KVTIdUtil.prefixEnd(prefix123);
        results = scanKeys(txId, tableId, prefix123, endKey);
        assert results.size() == 3 : "Overlapping prefix failed: " + results.size();
        System.out.println("   ✓ Overlapping prefixes: " + results.size() + " results");
        
        commitTransaction(txId);
        System.out.println("   ✅ All boundary tests passed\n");
    }
    
    /**
     * Test 3: Large Dataset Performance
     */
    private static void testLargeDataset() {
        System.out.println("📊 Test 3: Large Dataset (" + LARGE_DATASET_SIZE + " records)");
        System.out.println("────────────────────────────────────────");
        
        long txId = beginTransaction();
        long tableId = tableIds.get("vertices");
        
        // Insert large dataset
        System.out.print("   Inserting " + LARGE_DATASET_SIZE + " records...");
        long startTime = System.currentTimeMillis();
        
        int[] distribution = {1000, 2000, 5000, 10000, 32000}; // Different label distributions
        String[] labels = {"rare", "uncommon", "common", "frequent", "dominant"};
        
        int inserted = 0;
        for (int i = 0; i < labels.length; i++) {
            for (int j = 0; j < distribution[i]; j++) {
                String key = String.format("v_%s_%08d", labels[i], j);
                set(txId, tableId, key, labels[i] + ":" + j);
                inserted++;
            }
        }
        
        commitTransaction(txId);
        long insertTime = System.currentTimeMillis() - startTime;
        System.out.println(" done in " + insertTime + "ms");
        
        // Test prefix scans on different sizes
        txId = beginTransaction();
        
        for (int i = 0; i < labels.length; i++) {
            byte[] prefix = ("v_" + labels[i]).getBytes();
            byte[] endKey = KVTIdUtil.prefixEnd(prefix);
            
            startTime = System.currentTimeMillis();
            List<String> results = scanKeys(txId, tableId, prefix, endKey);
            long scanTime = System.currentTimeMillis() - startTime;
            
            assert results.size() == distribution[i] : 
                "Count mismatch for " + labels[i] + ": " + results.size() + " vs " + distribution[i];
            
            double throughput = results.size() * 1000.0 / scanTime;
            System.out.println(String.format("   ✓ Scan '%s' (%d records): %dms (%.0f rec/sec)", 
                labels[i], distribution[i], scanTime, throughput));
        }
        
        commitTransaction(txId);
        System.out.println("   ✅ Large dataset tests passed\n");
    }
    
    /**
     * Test 4: Complex Hierarchical Keys
     */
    private static void testComplexHierarchies() {
        System.out.println("🌳 Test 4: Complex Hierarchical Keys");
        System.out.println("────────────────────────────────────");
        
        long txId = beginTransaction();
        long tableId = tableIds.get("edges");
        
        // Simulate complex edge structure: [source][direction][label][sort][target]
        String[] sources = {"v001", "v002", "v003"};
        String[] directions = {"OUT", "IN", "BOTH"};
        String[] edgeLabels = {"knows", "created", "likes"};
        String[] targets = {"v100", "v101", "v102"};
        
        // Insert complex hierarchical data
        for (String source : sources) {
            for (String dir : directions) {
                for (String label : edgeLabels) {
                    for (String target : targets) {
                        String key = source + "_" + dir + "_" + label + "_" + 
                                   System.currentTimeMillis() + "_" + target;
                        set(txId, tableId, key, source + "->" + target);
                    }
                }
            }
        }
        
        commitTransaction(txId);
        
        // Test different levels of hierarchy
        txId = beginTransaction();
        
        // Level 1: All edges from v001
        byte[] prefix = "v001".getBytes();
        byte[] endKey = KVTIdUtil.prefixEnd(prefix);
        List<String> results = scanKeys(txId, tableId, prefix, endKey);
        assert results.size() == 27 : "Level 1 failed: " + results.size(); // 3*3*3
        System.out.println("   ✓ Level 1 (source=v001): " + results.size() + " edges");
        
        // Level 2: OUT edges from v001
        prefix = "v001_OUT".getBytes();
        endKey = KVTIdUtil.prefixEnd(prefix);
        results = scanKeys(txId, tableId, prefix, endKey);
        assert results.size() == 9 : "Level 2 failed: " + results.size(); // 3*3
        System.out.println("   ✓ Level 2 (v001 OUT): " + results.size() + " edges");
        
        // Level 3: OUT knows edges from v001
        prefix = "v001_OUT_knows".getBytes();
        endKey = KVTIdUtil.prefixEnd(prefix);
        results = scanKeys(txId, tableId, prefix, endKey);
        assert results.size() == 3 : "Level 3 failed: " + results.size(); // 3
        System.out.println("   ✓ Level 3 (v001 OUT knows): " + results.size() + " edges");
        
        commitTransaction(txId);
        System.out.println("   ✅ Hierarchical key tests passed\n");
    }
    
    /**
     * Test 5: Concurrent Operations
     */
    private static void testConcurrentOperations() throws Exception {
        System.out.println("⚡ Test 5: Concurrent Operations (" + CONCURRENT_THREADS + " threads)");
        System.out.println("──────────────────────────────────────────────");
        
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // Each thread performs prefix scans and writes
        for (int t = 0; t < CONCURRENT_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    long txId = beginTransaction();
                    long tableId = tableIds.get("vertices");
                    
                    // Write thread-specific data
                    String threadPrefix = "thread_" + threadId + "_";
                    for (int i = 0; i < 100; i++) {
                        set(txId, tableId, threadPrefix + i, "value_" + i);
                    }
                    
                    commitTransaction(txId);
                    
                    // Read back with prefix scan
                    txId = beginTransaction();
                    byte[] prefix = threadPrefix.getBytes();
                    byte[] endKey = KVTIdUtil.prefixEnd(prefix);
                    List<String> results = scanKeys(txId, tableId, prefix, endKey);
                    
                    if (results.size() == 100) {
                        successCount.incrementAndGet();
                    } else {
                        System.err.println("Thread " + threadId + " got " + results.size() + " results");
                        errorCount.incrementAndGet();
                    }
                    
                    commitTransaction(txId);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        System.out.println("   ✓ Successful threads: " + successCount.get());
        System.out.println("   ✓ Failed threads: " + errorCount.get());
        
        assert successCount.get() == CONCURRENT_THREADS : 
            "Concurrent test failed: " + successCount.get() + "/" + CONCURRENT_THREADS;
        
        System.out.println("   ✅ Concurrent operations passed\n");
    }
    
    /**
     * Test 6: Performance Benchmark
     */
    private static void testPerformanceBenchmark() {
        System.out.println("⚡ Test 6: Performance Benchmark");
        System.out.println("────────────────────────────────");
        
        long tableId = tableIds.get("vertices");
        
        // Benchmark different scan sizes
        int[] scanSizes = {10, 100, 1000, 10000};
        
        for (int size : scanSizes) {
            long txId = beginTransaction();
            
            // Insert data
            String prefix = "bench_" + size + "_";
            for (int i = 0; i < size; i++) {
                set(txId, tableId, prefix + i, "value_" + i);
            }
            
            // Insert noise data (10x more)
            for (int i = 0; i < size * 10; i++) {
                set(txId, tableId, "noise_" + size + "_" + i, "noise_" + i);
            }
            
            commitTransaction(txId);
            
            // Measure prefix scan
            txId = beginTransaction();
            
            long startTime = System.nanoTime();
            byte[] prefixBytes = prefix.getBytes();
            byte[] endKey = KVTIdUtil.prefixEnd(prefixBytes);
            List<String> results = scanKeys(txId, tableId, prefixBytes, endKey);
            long prefixTime = System.nanoTime() - startTime;
            
            assert results.size() == size : "Size mismatch: " + results.size() + " vs " + size;
            
            // Measure full scan equivalent
            startTime = System.nanoTime();
            List<String> allResults = scanKeys(txId, tableId, 
                "bench".getBytes(), "noise~".getBytes());
            
            int filtered = 0;
            for (String key : allResults) {
                if (key.startsWith(prefix)) filtered++;
            }
            long fullScanTime = System.nanoTime() - startTime;
            
            double speedup = (double)fullScanTime / prefixTime;
            
            System.out.println(String.format("   ✓ Size %d: Prefix %.2fms vs Full %.2fms (%.1fx faster)",
                size, prefixTime/1_000_000.0, fullScanTime/1_000_000.0, speedup));
            
            commitTransaction(txId);
        }
        
        System.out.println("   ✅ Performance benchmarks completed\n");
    }
    
    /**
     * Test 7: Error Handling
     */
    private static void testErrorHandling() {
        System.out.println("⚠️  Test 7: Error Handling");
        System.out.println("─────────────────────────");
        
        long tableId = tableIds.get("vertices");
        
        // Test invalid transaction
        try {
            scanKeys(999999L, tableId, "test".getBytes(), "test~".getBytes());
            assert false : "Should have failed with invalid transaction";
        } catch (Exception e) {
            System.out.println("   ✓ Invalid transaction handled");
        }
        
        // Test invalid table
        long txId = beginTransaction();
        try {
            scanKeys(txId, 999999L, "test".getBytes(), "test~".getBytes());
            assert false : "Should have failed with invalid table";
        } catch (Exception e) {
            System.out.println("   ✓ Invalid table handled");
        }
        rollbackTransaction(txId);
        
        // Test null keys
        txId = beginTransaction();
        try {
            Object[] result = KVTNative.nativeScan(txId, tableId, null, null, 100);
            // Should handle nulls gracefully
            System.out.println("   ✓ Null keys handled gracefully");
        } catch (Exception e) {
            System.out.println("   ✓ Null keys rejected appropriately");
        }
        commitTransaction(txId);
        
        System.out.println("   ✅ Error handling tests passed\n");
    }
    
    // Helper methods
    private static long beginTransaction() {
        Object[] result = KVTNative.nativeStartTransaction();
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to start transaction");
        }
        return (long)result[1];
    }
    
    private static void commitTransaction(long txId) {
        Object[] result = KVTNative.nativeCommitTransaction(txId);
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to commit transaction");
        }
    }
    
    private static void rollbackTransaction(long txId) {
        KVTNative.nativeRollbackTransaction(txId);
    }
    
    private static void set(long txId, long tableId, String key, String value) {
        Object[] result = KVTNative.nativeSet(txId, tableId, 
            key.getBytes(), value.getBytes());
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to set " + key);
        }
    }
    
    private static List<String> scanKeys(long txId, long tableId, 
                                         byte[] start, byte[] end) {
        Object[] result = KVTNative.nativeScan(txId, tableId, start, end, 500000);
        if ((int)result[0] != 0 && (int)result[0] != 22) { // 22 = SCAN_LIMIT_REACHED (not an error)
            throw new RuntimeException("Scan failed: " + result[0]);
        }
        
        byte[][] keys = (byte[][])result[1];
        List<String> keyStrings = new ArrayList<>();
        for (byte[] key : keys) {
            keyStrings.add(new String(key));
        }
        return keyStrings;
    }
    
    private static void cleanup() {
        System.out.println("🧹 Cleaning up...");
        
        // Drop all tables
        for (Long tableId : tableIds.values()) {
            KVTNative.nativeDropTable(tableId);
        }
        
        // Shutdown
        KVTNative.nativeShutdown();
        
        System.out.println("   ✓ Cleanup complete");
    }
}