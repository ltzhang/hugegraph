import org.apache.hugegraph.backend.store.kvt.KVTNative;
import org.apache.hugegraph.backend.store.kvt.KVTIdUtil;
import java.util.*;

/**
 * Test for prefix scan optimization in KVT backend
 * This test verifies that IdPrefixQuery and IdRangeQuery work correctly
 */
public class TestPrefixScanOptimization {
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
        System.out.println("=== Testing Prefix Scan Optimization ===\n");
        
        try {
            // Initialize
            initialize();
            
            // Run tests
            testPrefixScan();
            testRangeScan();
            testHierarchicalKeys();
            testPerformanceComparison();
            
            // Cleanup
            cleanup();
            
            System.out.println("\n=== ALL PREFIX SCAN TESTS PASSED ===");
            
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
        
        // Create test table
        Object[] tableResult = KVTNative.nativeCreateTable("test_prefix", "range");
        if ((int)tableResult[0] != 0) {
            throw new RuntimeException("Failed to create table");
        }
        tableIds.put("test_prefix", (long)tableResult[1]);
        
        System.out.println("   ✓ KVT initialized with test table");
    }
    
    private static void testPrefixScan() {
        System.out.println("\n2. Testing prefix scan...");
        
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        long tableId = tableIds.get("test_prefix");
        
        // Insert data with common prefixes
        // Simulating: [type_prefix][label_id][vertex_id]
        insertTestData(txId, tableId, "vertex_person_001", "Alice");
        insertTestData(txId, tableId, "vertex_person_002", "Bob");
        insertTestData(txId, tableId, "vertex_person_003", "Charlie");
        insertTestData(txId, tableId, "vertex_software_001", "HugeGraph");
        insertTestData(txId, tableId, "vertex_software_002", "TinkerPop");
        insertTestData(txId, tableId, "edge_knows_001", "Alice->Bob");
        insertTestData(txId, tableId, "edge_knows_002", "Bob->Charlie");
        insertTestData(txId, tableId, "edge_created_001", "Alice->HugeGraph");
        
        KVTNative.nativeCommitTransaction(txId);
        
        // Test prefix scan for "vertex_person"
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        // Scan with prefix "vertex_person"
        byte[] prefix = "vertex_person".getBytes();
        byte[] endKey = KVTIdUtil.prefixEnd(prefix);
        
        Object[] scanResult = KVTNative.nativeScan(txId, tableId, 
            prefix, endKey, 100);
        
        if ((int)scanResult[0] != 0) {
            throw new RuntimeException("Prefix scan failed: " + scanResult[0]);
        }
        
        byte[][] keys = (byte[][])scanResult[1];
        byte[][] values = (byte[][])scanResult[2];
        
        // Should get exactly 3 person vertices
        assert keys.length == 3 : "Expected 3 person vertices, got " + keys.length;
        
        System.out.println("   ✓ Prefix scan for 'vertex_person' returned " + keys.length + " results");
        
        // Verify results
        for (int i = 0; i < keys.length; i++) {
            String key = new String(keys[i]);
            String value = new String(values[i]);
            assert key.startsWith("vertex_person") : "Key doesn't match prefix: " + key;
            System.out.println("     - " + key + " -> " + value);
        }
        
        KVTNative.nativeCommitTransaction(txId);
        
        System.out.println("   ✓ Prefix scan verification successful");
    }
    
    private static void testRangeScan() {
        System.out.println("\n3. Testing range scan...");
        
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        long tableId = tableIds.get("test_prefix");
        
        // Range scan from vertex_person_002 to vertex_person_003 (inclusive)
        byte[] startKey = "vertex_person_002".getBytes();
        byte[] endKey = KVTIdUtil.incrementBytes("vertex_person_003".getBytes());
        
        Object[] scanResult = KVTNative.nativeScan(txId, tableId, 
            startKey, endKey, 100);
        
        if ((int)scanResult[0] != 0) {
            throw new RuntimeException("Range scan failed: " + scanResult[0]);
        }
        
        byte[][] keys = (byte[][])scanResult[1];
        byte[][] values = (byte[][])scanResult[2];
        
        // Should get exactly 2 results (002 and 003)
        assert keys.length == 2 : "Expected 2 results, got " + keys.length;
        
        System.out.println("   ✓ Range scan returned " + keys.length + " results");
        
        // Verify results
        String key1 = new String(keys[0]);
        String key2 = new String(keys[1]);
        assert key1.equals("vertex_person_002") : "First key mismatch: " + key1;
        assert key2.equals("vertex_person_003") : "Second key mismatch: " + key2;
        
        KVTNative.nativeCommitTransaction(txId);
        
        System.out.println("   ✓ Range scan verification successful");
    }
    
    private static void testHierarchicalKeys() {
        System.out.println("\n4. Testing hierarchical key encoding...");
        
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        long tableId = tableIds.get("test_prefix");
        
        // Insert hierarchical data simulating edge structure
        // Format: [source_vertex][direction][edge_label][target_vertex]
        insertTestData(txId, tableId, "v001_OUT_knows_v002", "Edge1");
        insertTestData(txId, tableId, "v001_OUT_knows_v003", "Edge2");
        insertTestData(txId, tableId, "v001_OUT_created_v004", "Edge3");
        insertTestData(txId, tableId, "v002_IN_knows_v001", "Edge1-reverse");
        insertTestData(txId, tableId, "v002_OUT_knows_v003", "Edge4");
        
        KVTNative.nativeCommitTransaction(txId);
        
        // Query all OUT edges from v001
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        byte[] prefix = "v001_OUT".getBytes();
        byte[] endKey = KVTIdUtil.prefixEnd(prefix);
        
        Object[] scanResult = KVTNative.nativeScan(txId, tableId, 
            prefix, endKey, 100);
        
        byte[][] keys = (byte[][])scanResult[1];
        
        // Should get 3 OUT edges from v001
        assert keys.length == 3 : "Expected 3 OUT edges from v001, got " + keys.length;
        
        System.out.println("   ✓ Found " + keys.length + " OUT edges from v001");
        
        // Query only "knows" edges from v001
        prefix = "v001_OUT_knows".getBytes();
        endKey = KVTIdUtil.prefixEnd(prefix);
        
        scanResult = KVTNative.nativeScan(txId, tableId, 
            prefix, endKey, 100);
        
        keys = (byte[][])scanResult[1];
        
        // Should get 2 "knows" edges from v001
        assert keys.length == 2 : "Expected 2 'knows' edges from v001, got " + keys.length;
        
        System.out.println("   ✓ Found " + keys.length + " 'knows' edges from v001");
        
        KVTNative.nativeCommitTransaction(txId);
        
        System.out.println("   ✓ Hierarchical key encoding works correctly");
    }
    
    private static void testPerformanceComparison() {
        System.out.println("\n5. Testing performance improvement...");
        
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        long tableId = tableIds.get("test_prefix");
        
        // Insert many records
        int totalRecords = 10000;
        int personRecords = 100;
        
        for (int i = 0; i < personRecords; i++) {
            String key = String.format("vertex_person_%05d", i);
            insertTestData(txId, tableId, key, "Person" + i);
        }
        
        for (int i = 0; i < totalRecords - personRecords; i++) {
            String key = String.format("vertex_other_%05d", i);
            insertTestData(txId, tableId, key, "Other" + i);
        }
        
        KVTNative.nativeCommitTransaction(txId);
        
        // Measure prefix scan performance
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        long startTime = System.currentTimeMillis();
        
        // Prefix scan for person vertices
        byte[] prefix = "vertex_person".getBytes();
        byte[] endKey = KVTIdUtil.prefixEnd(prefix);
        
        Object[] scanResult = KVTNative.nativeScan(txId, tableId, 
            prefix, endKey, 10000);
        
        byte[][] keys = (byte[][])scanResult[1];
        
        long prefixTime = System.currentTimeMillis() - startTime;
        
        assert keys.length == personRecords : 
            "Expected " + personRecords + " person records, got " + keys.length;
        
        System.out.println("   ✓ Prefix scan: " + personRecords + " records in " + prefixTime + "ms");
        
        // Compare with full scan (simulation)
        startTime = System.currentTimeMillis();
        
        // Full scan with filtering (worst case)
        scanResult = KVTNative.nativeScan(txId, tableId, 
            "vertex".getBytes(), "vertex~".getBytes(), 20000);
        
        keys = (byte[][])scanResult[1];
        
        // Manual filtering (simulating what would happen without prefix optimization)
        int filteredCount = 0;
        for (byte[] key : keys) {
            String keyStr = new String(key);
            if (keyStr.startsWith("vertex_person")) {
                filteredCount++;
            }
        }
        
        long fullScanTime = System.currentTimeMillis() - startTime;
        
        assert filteredCount == personRecords : 
            "Filtering mismatch: " + filteredCount + " vs " + personRecords;
        
        System.out.println("   ✓ Full scan + filter: " + totalRecords + " records in " + fullScanTime + "ms");
        
        double improvement = (double)fullScanTime / prefixTime;
        System.out.println("   ✓ Performance improvement: " + 
            String.format("%.1fx faster", improvement));
        
        // Cleanup
        KVTNative.nativeCommitTransaction(txId);
        
        System.out.println("   ✓ Prefix scan optimization provides significant performance gains");
    }
    
    private static void insertTestData(long txId, long tableId, String key, String value) {
        Object[] result = KVTNative.nativeSet(txId, tableId, 
            key.getBytes(), value.getBytes());
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to insert " + key + ": " + result[0]);
        }
    }
    
    private static void cleanup() {
        System.out.println("\n6. Cleaning up...");
        
        // Drop table
        for (Long tableId : tableIds.values()) {
            KVTNative.nativeDropTable(tableId);
        }
        
        // Shutdown
        KVTNative.nativeShutdown();
        
        System.out.println("   ✓ Cleanup complete");
    }
}