import org.apache.hugegraph.backend.store.kvt.KVTNative;

public class TestScanEdgeCases {
    public static void main(String[] args) {
        System.out.println("=== Testing Scan Edge Cases ===\n");
        
        try {
            // Load library
            System.loadLibrary("kvtjni");
            System.out.println("✓ Library loaded");
            
            // Initialize KVT
            KVTNative.initialize();
            System.out.println("✓ KVT initialized");
            
            // Create table
            KVTNative.KVTResult<Long> tableResult = KVTNative.createTable("edge_test", "range");
            long tableId = tableResult.value;
            System.out.println("✓ Table created with ID: " + tableId);
            
            // Populate some data with formatted keys (00000-00099)
            KVTNative.KVTResult<Long> txResult = KVTNative.startTransaction();
            long txId = txResult.value;
            
            for (int i = 0; i < 100; i++) {
                String key = String.format("%05d", i);
                String value = String.valueOf(i);
                KVTNative.set(txId, tableId, key.getBytes(), value.getBytes());
            }
            KVTNative.commitTransaction(txId);
            System.out.println("✓ Populated 100 keys (00000-00099)\n");
            
            System.out.println("Testing edge cases:");
            System.out.println("-------------------\n");
            
            // Test 1: Scan with end key beyond data (should work)
            System.out.println("1. Scan from 00000 to 10000 (beyond data):");
            Object[] scanResult = KVTNative.nativeScan(0, tableId,
                "00000".getBytes(), "10000".getBytes(), 10000);
            printScanResult(scanResult);
            
            // Test 2: Scan with reversed bounds (should return empty or error)
            System.out.println("\n2. Scan with reversed bounds (00100 to 00000):");
            scanResult = KVTNative.nativeScan(0, tableId,
                "00100".getBytes(), "00000".getBytes(), 100);
            printScanResult(scanResult);
            
            // Test 3: Scan with empty range (same start and end)
            System.out.println("\n3. Scan with same start and end (00050 to 00050):");
            scanResult = KVTNative.nativeScan(0, tableId,
                "00050".getBytes(), "00050".getBytes(), 100);
            printScanResult(scanResult);
            
            // Test 4: Scan with non-existent range
            System.out.println("\n4. Scan non-existent range (00200 to 00300):");
            scanResult = KVTNative.nativeScan(0, tableId,
                "00200".getBytes(), "00300".getBytes(), 100);
            printScanResult(scanResult);
            
            // Test 5: Scan with very large limit
            System.out.println("\n5. Scan with very large limit (1000000):");
            scanResult = KVTNative.nativeScan(0, tableId,
                "00000".getBytes(), "00100".getBytes(), 1000000);
            printScanResult(scanResult);
            
            // Cleanup
            KVTNative.dropTable(tableId);
            KVTNative.shutdown();
            System.out.println("\n✓ Cleanup complete");
            
        } catch (Exception e) {
            System.err.println("Exception: " + e);
            e.printStackTrace();
        }
    }
    
    private static void printScanResult(Object[] scanResult) {
        if (scanResult == null) {
            System.err.println("  ERROR: nativeScan returned null!");
            return;
        }
        
        Integer errorCode = (Integer) scanResult[0];
        KVTNative.KVTError error = KVTNative.KVTError.fromCode(errorCode);
        System.out.println("  Error code: " + errorCode + " (" + error + ")");
        
        if (error != KVTNative.KVTError.SUCCESS) {
            System.err.println("  Scan failed with error: " + error);
            return;
        }
        
        byte[][] keys = (byte[][]) scanResult[1];
        System.out.println("  Results found: " + keys.length);
        if (keys.length > 0) {
            System.out.println("  First key: " + new String(keys[0]));
            System.out.println("  Last key: " + new String(keys[keys.length - 1]));
        }
    }
}