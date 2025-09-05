import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.util.*;
import java.nio.charset.StandardCharsets;

/**
 * Edge case integration test for KVT backend
 * Tests boundary conditions, error handling, and stress scenarios
 */
public class EdgeCaseIntegrationTest {
    private static Map<String, Long> tableIds = new HashMap<>();
    
    static {
        String libPath = System.getProperty("user.dir") + "/target/native";
        System.setProperty("java.library.path", libPath);
        try {
            System.load(libPath + "/libkvtjni.so");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load library: " + e.getMessage());
            System.exit(1);
        }
    }
    
    public static void main(String[] args) {
        System.out.println("=== KVT Edge Case Integration Test ===\n");
        
        try {
            // Initialize
            initialize();
            
            // Test suites
            testBoundaryValues();
            testErrorConditions();
            testConcurrentStress();
            testMemoryPressure();
            testComplexQueries();
            testTransactionIsolation();
            
            // Cleanup
            cleanup();
            
            System.out.println("\n=== ALL EDGE CASE TESTS PASSED ===");
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void initialize() {
        System.out.println("1. Initializing KVT...");
        
        int result = KVTNative.nativeInitialize();
        if (result != 0) {
            throw new RuntimeException("Failed to initialize KVT: " + result);
        }
        
        // Create tables with different partition methods
        createTable("test_hash", "hash");
        createTable("test_range", "range");
        createTable("test_data", "hash");
        
        System.out.println("   ✓ Initialized with " + tableIds.size() + " tables");
    }
    
    private static void createTable(String name, String partitionMethod) {
        Object[] result = KVTNative.nativeCreateTable(name, partitionMethod);
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to create table " + name);
        }
        tableIds.put(name, (long)result[1]);
    }
    
    private static void testBoundaryValues() {
        System.out.println("\n2. Testing boundary values...");
        
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        
        // Test empty key/value
        testEmptyKeyValue(txId);
        
        // Test maximum key length (1KB key)
        String longKey = "k" + "x".repeat(1023);
        Object[] result = KVTNative.nativeSet(txId, tableIds.get("test_data"), 
            longKey.getBytes(), "value".getBytes());
        assert (int)result[0] == 0 : "Failed to handle 1KB key";
        
        // Test maximum value size (1MB value)
        String megaValue = "v".repeat(1024 * 1024);
        result = KVTNative.nativeSet(txId, tableIds.get("test_data"),
            "megakey".getBytes(), megaValue.getBytes());
        assert (int)result[0] == 0 : "Failed to handle 1MB value";
        
        // Test special characters in keys
        String specialKey = "key\u0000\u0001\u007F\u0080\u00FF";
        result = KVTNative.nativeSet(txId, tableIds.get("test_data"),
            specialKey.getBytes(StandardCharsets.UTF_8), "special".getBytes());
        assert (int)result[0] == 0 : "Failed to handle special characters";
        
        // Test binary data
        byte[] binaryKey = new byte[]{0, 1, 2, 3, -1, -2, -3, -128, 127};
        byte[] binaryValue = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryValue[i] = (byte)i;
        }
        result = KVTNative.nativeSet(txId, tableIds.get("test_data"),
            binaryKey, binaryValue);
        assert (int)result[0] == 0 : "Failed to handle binary data";
        
        KVTNative.nativeCommitTransaction(txId);
        
        // Verify special data
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        result = KVTNative.nativeGet(txId, tableIds.get("test_data"), binaryKey);
        assert Arrays.equals((byte[])result[1], binaryValue) : "Binary data mismatch";
        
        KVTNative.nativeCommitTransaction(txId);
        
        System.out.println("   ✓ All boundary values handled correctly");
    }
    
    private static void testEmptyKeyValue(long txId) {
        // Test empty value (allowed)
        Object[] result = KVTNative.nativeSet(txId, tableIds.get("test_data"),
            "empty_val".getBytes(), new byte[0]);
        assert (int)result[0] == 0 : "Failed to handle empty value";
        
        // Verify empty value retrieval
        result = KVTNative.nativeGet(txId, tableIds.get("test_data"), 
            "empty_val".getBytes());
        assert (int)result[0] == 0 : "Failed to retrieve empty value";
        assert ((byte[])result[1]).length == 0 : "Empty value not preserved";
    }
    
    private static void testErrorConditions() {
        System.out.println("\n3. Testing error conditions...");
        
        // Test invalid transaction ID
        Object[] result = KVTNative.nativeGet(999999L, tableIds.get("test_data"),
            "key".getBytes());
        assert (int)result[0] != 0 : "Should fail with invalid transaction";
        
        // Test invalid table ID
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        
        result = KVTNative.nativeSet(txId, 999999L, "key".getBytes(), "value".getBytes());
        assert (int)result[0] != 0 : "Should fail with invalid table";
        
        KVTNative.nativeRollbackTransaction(txId);
        
        // Test double commit
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        KVTNative.nativeCommitTransaction(txId);
        
        result = KVTNative.nativeCommitTransaction(txId);
        assert (int)result[0] != 0 : "Should fail on double commit";
        
        // Test operations after rollback
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        KVTNative.nativeRollbackTransaction(txId);
        
        result = KVTNative.nativeSet(txId, tableIds.get("test_data"),
            "key".getBytes(), "value".getBytes());
        assert (int)result[0] != 0 : "Should fail after rollback";
        
        System.out.println("   ✓ Error conditions handled correctly");
    }
    
    private static void testConcurrentStress() {
        System.out.println("\n4. Testing concurrent stress...");
        
        int numThreads = 10;
        int opsPerThread = 100;
        List<Thread> threads = new ArrayList<>();
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        Object[] txResult = KVTNative.nativeStartTransaction();
                        long txId = (long)txResult[1];
                        
                        String key = "thread_" + threadId + "_op_" + i;
                        String value = "data_" + System.nanoTime();
                        
                        // Mix of operations
                        Object[] result = KVTNative.nativeSet(txId, tableIds.get("test_data"),
                            key.getBytes(), value.getBytes());
                        
                        if ((int)result[0] != 0) {
                            throw new RuntimeException("Set failed: " + result[0]);
                        }
                        
                        // Read back
                        result = KVTNative.nativeGet(txId, tableIds.get("test_data"),
                            key.getBytes());
                        
                        if ((int)result[0] != 0) {
                            throw new RuntimeException("Get failed: " + result[0]);
                        }
                        
                        // Random commit or rollback
                        if (Math.random() > 0.1) {
                            KVTNative.nativeCommitTransaction(txId);
                        } else {
                            KVTNative.nativeRollbackTransaction(txId);
                        }
                    }
                } catch (Exception e) {
                    errors.add(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        // Wait for completion
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        
        if (!errors.isEmpty()) {
            System.err.println("   ⚠ " + errors.size() + " errors in concurrent operations");
            for (Exception e : errors) {
                System.err.println("     - " + e.getMessage());
            }
            // With 2PL, some conflicts are expected
            if (errors.size() > numThreads * opsPerThread * 0.1) {
                throw new RuntimeException("Too many concurrent errors");
            }
        }
        
        System.out.println("   ✓ Concurrent stress test completed");
    }
    
    private static void testMemoryPressure() {
        System.out.println("\n5. Testing memory pressure...");
        
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        
        // Create many small entries
        int numEntries = 10000;
        for (int i = 0; i < numEntries; i++) {
            String key = "mem_" + i;
            String value = "val_" + i;
            Object[] result = KVTNative.nativeSet(txId, tableIds.get("test_data"),
                key.getBytes(), value.getBytes());
            
            if ((int)result[0] != 0) {
                throw new RuntimeException("Failed at entry " + i);
            }
        }
        
        KVTNative.nativeCommitTransaction(txId);
        
        // Scan all entries
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        Object[] scanResult = KVTNative.nativeScan(txId, tableIds.get("test_data"),
            "mem_".getBytes(), "mem_~".getBytes(), numEntries + 100);
        
        byte[][] keys = (byte[][])scanResult[1];
        assert keys.length == numEntries : "Missing entries: " + keys.length + " vs " + numEntries;
        
        // Delete half the entries
        for (int i = 0; i < numEntries; i += 2) {
            String key = "mem_" + i;
            KVTNative.nativeDel(txId, tableIds.get("test_data"), key.getBytes());
        }
        
        KVTNative.nativeCommitTransaction(txId);
        
        // Verify deletions
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        scanResult = KVTNative.nativeScan(txId, tableIds.get("test_data"),
            "mem_".getBytes(), "mem_~".getBytes(), numEntries);
        
        keys = (byte[][])scanResult[1];
        assert keys.length == numEntries / 2 : "Delete failed: " + keys.length;
        
        KVTNative.nativeCommitTransaction(txId);
        
        System.out.println("   ✓ Memory pressure test passed (" + numEntries + " entries)");
    }
    
    private static void testComplexQueries() {
        System.out.println("\n6. Testing complex queries...");
        
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        
        // Create hierarchical data
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                String key = String.format("path/%02d/%02d", i, j);
                String value = "data_" + i + "_" + j;
                KVTNative.nativeSet(txId, tableIds.get("test_range"),
                    key.getBytes(), value.getBytes());
            }
        }
        
        KVTNative.nativeCommitTransaction(txId);
        
        // Test prefix scans
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        // Scan specific directory
        Object[] scanResult = KVTNative.nativeScan(txId, tableIds.get("test_range"),
            "path/05/".getBytes(), "path/05/~".getBytes(), 100);
        
        byte[][] keys = (byte[][])scanResult[1];
        assert keys.length == 10 : "Prefix scan failed: " + keys.length;
        
        // Scan range of directories
        scanResult = KVTNative.nativeScan(txId, tableIds.get("test_range"),
            "path/02/".getBytes(), "path/05/".getBytes(), 1000);
        
        keys = (byte[][])scanResult[1];
        assert keys.length == 30 : "Range scan failed: " + keys.length;
        
        KVTNative.nativeCommitTransaction(txId);
        
        System.out.println("   ✓ Complex queries executed successfully");
    }
    
    private static void testTransactionIsolation() {
        System.out.println("\n7. Testing transaction isolation...");
        
        // Setup initial data
        Object[] tx1Result = KVTNative.nativeStartTransaction();
        long tx1 = (long)tx1Result[1];
        
        KVTNative.nativeSet(tx1, tableIds.get("test_data"),
            "isolation_test".getBytes(), "initial".getBytes());
        KVTNative.nativeCommitTransaction(tx1);
        
        // Start two concurrent transactions
        tx1Result = KVTNative.nativeStartTransaction();
        tx1 = (long)tx1Result[1];
        
        Object[] tx2Result = KVTNative.nativeStartTransaction();
        long tx2 = (long)tx2Result[1];
        
        // TX1 reads the value
        Object[] result = KVTNative.nativeGet(tx1, tableIds.get("test_data"),
            "isolation_test".getBytes());
        String value1 = new String((byte[])result[1]);
        assert value1.equals("initial") : "TX1 read failed";
        
        // TX2 modifies the value
        KVTNative.nativeSet(tx2, tableIds.get("test_data"),
            "isolation_test".getBytes(), "modified_by_tx2".getBytes());
        
        // TX1 reads again (should still see initial)
        result = KVTNative.nativeGet(tx1, tableIds.get("test_data"),
            "isolation_test".getBytes());
        String value2 = new String((byte[])result[1]);
        assert value2.equals("initial") : "Isolation violated before commit";
        
        // TX2 commits
        KVTNative.nativeCommitTransaction(tx2);
        
        // TX1 still sees old value (snapshot isolation)
        result = KVTNative.nativeGet(tx1, tableIds.get("test_data"),
            "isolation_test".getBytes());
        String value3 = new String((byte[])result[1]);
        assert value3.equals("initial") : "Snapshot isolation violated";
        
        KVTNative.nativeCommitTransaction(tx1);
        
        // New transaction sees the update
        Object[] tx3Result = KVTNative.nativeStartTransaction();
        long tx3 = (long)tx3Result[1];
        
        result = KVTNative.nativeGet(tx3, tableIds.get("test_data"),
            "isolation_test".getBytes());
        String value4 = new String((byte[])result[1]);
        assert value4.equals("modified_by_tx2") : "Update not visible after commit";
        
        KVTNative.nativeCommitTransaction(tx3);
        
        System.out.println("   ✓ Transaction isolation verified");
    }
    
    private static void cleanup() {
        System.out.println("\n8. Cleaning up...");
        
        // Clear all test data
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        
        // Scan and delete all test data
        for (String prefix : Arrays.asList("mem_", "thread_", "path/", "isolation_")) {
            Object[] scanResult = KVTNative.nativeScan(txId, tableIds.get("test_data"),
                prefix.getBytes(), (prefix + "~").getBytes(), 100000);
            
            byte[][] keys = (byte[][])scanResult[1];
            for (byte[] key : keys) {
                KVTNative.nativeDel(txId, tableIds.get("test_data"), key);
            }
        }
        
        KVTNative.nativeCommitTransaction(txId);
        
        // Drop tables
        for (Long tableId : tableIds.values()) {
            KVTNative.nativeDropTable(tableId);
        }
        
        // Shutdown
        KVTNative.nativeShutdown();
        
        System.out.println("   ✓ Cleanup complete");
    }
}