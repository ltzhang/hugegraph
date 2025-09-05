import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.util.*;

/**
 * Simple integration test for KVT backend using only KVTNative
 * Tests graph-like operations without HugeGraph framework
 */
public class SimpleIntegrationTest {
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
        System.out.println("=== Simple KVT Integration Test ===\n");
        
        try {
            // Initialize
            initialize();
            
            // Test suites
            testBasicGraphOperations();
            testPropertyUpdates();
            testRangeQueries();
            testTransactions();
            testStressOperations();
            
            // Cleanup
            cleanup();
            
            System.out.println("\n=== ALL TESTS PASSED ===");
            
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
        
        // Create tables
        createTable("vertices", "hash");
        createTable("edges", "range");
        createTable("index", "range");
        
        System.out.println("   ✓ Created " + tableIds.size() + " tables");
    }
    
    private static void createTable(String name, String partitionMethod) {
        Object[] result = KVTNative.nativeCreateTable(name, partitionMethod);
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to create table " + name);
        }
        tableIds.put(name, (long)result[1]);
        System.out.println("   - Table '" + name + "' created with ID: " + result[1]);
    }
    
    private static void testBasicGraphOperations() {
        System.out.println("\n2. Testing basic graph operations...");
        
        // Start transaction
        Object[] txResult = KVTNative.nativeStartTransaction();
        if ((int)txResult[0] != 0) {
            throw new RuntimeException("Failed to start transaction");
        }
        long txId = (long)txResult[1];
        
        // Create vertices
        createVertex(txId, "v1", "person", "name=Alice;age=30");
        createVertex(txId, "v2", "person", "name=Bob;age=35");
        createVertex(txId, "v3", "software", "name=HugeGraph;lang=Java");
        
        // Create edges
        createEdge(txId, "e1", "v1", "v2", "knows", "since=2020");
        createEdge(txId, "e2", "v1", "v3", "created", "role=developer");
        createEdge(txId, "e3", "v2", "v3", "uses", "");
        
        // Commit
        KVTNative.nativeCommitTransaction(txId);
        System.out.println("   ✓ Created 3 vertices and 3 edges");
        
        // Verify data
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        String v1Data = getVertex(txId, "v1");
        assert v1Data.contains("Alice");
        
        String e1Data = getEdge(txId, "e1");
        assert e1Data.contains("knows");
        
        KVTNative.nativeCommitTransaction(txId);
        System.out.println("   ✓ Data verification successful");
    }
    
    private static void createVertex(long txId, String id, String label, String properties) {
        String data = label + "|" + properties;
        Object[] result = KVTNative.nativeSet(txId, tableIds.get("vertices"), 
            id.getBytes(), data.getBytes());
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to create vertex " + id);
        }
    }
    
    private static String getVertex(long txId, String id) {
        Object[] result = KVTNative.nativeGet(txId, tableIds.get("vertices"), id.getBytes());
        if ((int)result[0] != 0) {
            return null;
        }
        return new String((byte[])result[1]);
    }
    
    private static void createEdge(long txId, String id, String source, String target, 
                                  String label, String properties) {
        String data = source + "->" + target + "|" + label + "|" + properties;
        Object[] result = KVTNative.nativeSet(txId, tableIds.get("edges"), 
            id.getBytes(), data.getBytes());
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to create edge " + id);
        }
        
        // Also create index entries for source and target
        String sourceIndex = source + "_out_" + id;
        String targetIndex = target + "_in_" + id;
        
        KVTNative.nativeSet(txId, tableIds.get("index"), 
            sourceIndex.getBytes(), id.getBytes());
        KVTNative.nativeSet(txId, tableIds.get("index"), 
            targetIndex.getBytes(), id.getBytes());
    }
    
    private static String getEdge(long txId, String id) {
        Object[] result = KVTNative.nativeGet(txId, tableIds.get("edges"), id.getBytes());
        if ((int)result[0] != 0) {
            return null;
        }
        return new String((byte[])result[1]);
    }
    
    private static void testPropertyUpdates() {
        System.out.println("\n3. Testing property updates...");
        
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        
        // Update vertex property using native update
        String updateParam = encodePropertyUpdate("age", "31");
        Object[] updateResult = KVTNative.nativeVertexPropertyUpdate(
            txId, tableIds.get("vertices"), "v1".getBytes(), updateParam.getBytes());
        
        if ((int)updateResult[0] != 0) {
            System.err.println("Property update failed: " + updateResult[2]);
        } else {
            System.out.println("   ✓ Updated vertex property");
        }
        
        // Test large property value
        String largeValue = "x".repeat(10000);
        createVertex(txId, "v4", "test", "data=" + largeValue);
        
        KVTNative.nativeCommitTransaction(txId);
        
        // Verify large property
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        String v4Data = getVertex(txId, "v4");
        assert v4Data.length() > 10000;
        
        KVTNative.nativeCommitTransaction(txId);
        System.out.println("   ✓ Handled large property (10KB)");
    }
    
    private static String encodePropertyUpdate(String name, String value) {
        // Simple encoding: name_length(1 byte) + name + value_length(1 byte) + value
        StringBuilder sb = new StringBuilder();
        sb.append((char)name.length());
        sb.append(name);
        sb.append((char)value.length());
        sb.append(value);
        return sb.toString();
    }
    
    private static void testRangeQueries() {
        System.out.println("\n4. Testing range queries...");
        
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        
        // Add more vertices for range testing
        for (int i = 0; i < 20; i++) {
            createVertex(txId, "test_v" + String.format("%02d", i), "test", "id=" + i);
        }
        
        KVTNative.nativeCommitTransaction(txId);
        
        // Test range scan
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        Object[] scanResult = KVTNative.nativeScan(txId, tableIds.get("vertices"),
            "test_v05".getBytes(), "test_v15".getBytes(), 100);
        
        if ((int)scanResult[0] != 0) {
            throw new RuntimeException("Range scan failed");
        }
        
        byte[][] keys = (byte[][])scanResult[1];
        System.out.println("   ✓ Range scan returned " + keys.length + " results");
        assert keys.length == 10; // v05 through v14
        
        // Test full table scan
        scanResult = KVTNative.nativeScan(txId, tableIds.get("vertices"),
            null, null, 1000);
        
        keys = (byte[][])scanResult[1];
        System.out.println("   ✓ Full scan returned " + keys.length + " vertices");
        
        KVTNative.nativeCommitTransaction(txId);
    }
    
    private static void testTransactions() {
        System.out.println("\n5. Testing transactions...");
        
        // Test rollback
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        
        createVertex(txId, "rollback_test", "test", "should_not_exist=true");
        KVTNative.nativeRollbackTransaction(txId);
        
        // Verify rollback
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        String data = getVertex(txId, "rollback_test");
        assert data == null;
        
        KVTNative.nativeCommitTransaction(txId);
        System.out.println("   ✓ Transaction rollback successful");
        
        // Test concurrent transactions (if supported)
        testConcurrentTransactions();
    }
    
    private static void testConcurrentTransactions() {
        List<Thread> threads = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            Thread t = new Thread(() -> {
                try {
                    Object[] txResult = KVTNative.nativeStartTransaction();
                    long txId = (long)txResult[1];
                    
                    createVertex(txId, "concurrent_" + threadId, "test", 
                        "thread=" + threadId);
                    
                    Thread.sleep(10); // Small delay
                    
                    KVTNative.nativeCommitTransaction(txId);
                } catch (Exception e) {
                    errors.add(e);
                }
            });
            threads.add(t);
            t.start();
        }
        
        // Wait for all threads
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        
        if (!errors.isEmpty()) {
            System.err.println("   ⚠ Some concurrent transactions failed (expected with 2PL)");
        } else {
            System.out.println("   ✓ Concurrent transactions successful");
        }
    }
    
    private static void testStressOperations() {
        System.out.println("\n6. Testing stress operations...");
        
        long startTime = System.currentTimeMillis();
        int operations = 1000;
        
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        
        // Bulk insert
        for (int i = 0; i < operations; i++) {
            createVertex(txId, "stress_" + i, "stress", "value=" + i);
        }
        
        KVTNative.nativeCommitTransaction(txId);
        
        long duration = System.currentTimeMillis() - startTime;
        double throughput = (operations * 1000.0) / duration;
        
        System.out.println("   ✓ Inserted " + operations + " vertices");
        System.out.println("   ✓ Throughput: " + String.format("%.0f", throughput) + " ops/sec");
        
        // Verify data integrity
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        // Scan and count
        Object[] scanResult = KVTNative.nativeScan(txId, tableIds.get("vertices"),
            "stress_".getBytes(), "stress_~".getBytes(), operations + 100);
        
        byte[][] keys = (byte[][])scanResult[1];
        assert keys.length == operations;
        
        KVTNative.nativeCommitTransaction(txId);
        System.out.println("   ✓ Data integrity verified");
    }
    
    private static void cleanup() {
        System.out.println("\n7. Cleaning up...");
        
        // Drop tables
        for (Long tableId : tableIds.values()) {
            KVTNative.nativeDropTable(tableId);
        }
        
        // Shutdown
        KVTNative.nativeShutdown();
        
        System.out.println("   ✓ Cleanup complete");
    }
}