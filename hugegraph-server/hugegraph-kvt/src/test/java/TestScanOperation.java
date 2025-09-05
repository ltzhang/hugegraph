import org.apache.hugegraph.backend.store.kvt.KVTNative;

public class TestScanOperation {
    public static void main(String[] args) {
        System.out.println("=== Testing Scan Operation ===\n");
        
        try {
            // Load library
            System.loadLibrary("kvtjni");
            System.out.println("✓ Library loaded");
            
            // Initialize KVT
            KVTNative.KVTError initError = KVTNative.initialize();
            if (initError != KVTNative.KVTError.SUCCESS) {
                System.err.println("Failed to initialize KVT: " + initError);
                return;
            }
            System.out.println("✓ KVT initialized");
            
            // Create table
            KVTNative.KVTResult<Long> tableResult = KVTNative.createTable("scan_test", "range");
            if (!tableResult.isSuccess()) {
                System.err.println("Failed to create table: " + tableResult.error);
                return;
            }
            long tableId = tableResult.value;
            System.out.println("✓ Table created with ID: " + tableId);
            
            // Start transaction for populating data
            KVTNative.KVTResult<Long> txResult = KVTNative.startTransaction();
            if (!txResult.isSuccess()) {
                System.err.println("Failed to start transaction: " + txResult.error);
                return;
            }
            long txId = txResult.value;
            System.out.println("✓ Transaction started with ID: " + txId);
            
            // Populate some data
            for (int i = 0; i < 10; i++) {
                String key = String.format("key_%02d", i);
                String value = "value_" + i;
                KVTNative.KVTResult<Void> setResult = KVTNative.set(txId, tableId, 
                    key.getBytes(), value.getBytes());
                if (!setResult.isSuccess()) {
                    System.err.println("Failed to set " + key + ": " + setResult.error);
                    return;
                }
            }
            System.out.println("✓ Populated 10 key-value pairs");
            
            // Commit transaction
            KVTNative.KVTResult<Void> commitResult = KVTNative.commitTransaction(txId);
            if (!commitResult.isSuccess()) {
                System.err.println("Failed to commit: " + commitResult.error);
                return;
            }
            System.out.println("✓ Transaction committed\n");
            
            // Now test scan operation
            System.out.println("Testing scan operation:");
            System.out.println("------------------------");
            
            // Test 1: Scan without transaction (txId = 0)
            System.out.println("\n1. Scan without transaction (txId=0):");
            testScan(0, tableId, "key_00", "key_05", 10);
            
            // Test 2: Scan within transaction
            txResult = KVTNative.startTransaction();
            if (!txResult.isSuccess()) {
                System.err.println("Failed to start new transaction: " + txResult.error);
                return;
            }
            long txId2 = txResult.value;
            System.out.println("\n2. Scan within transaction (txId=" + txId2 + "):");
            testScan(txId2, tableId, "key_00", "key_05", 10);
            
            // Test 3: Scan with null bounds (full scan)
            System.out.println("\n3. Full scan (null bounds):");
            testScan(txId2, tableId, null, null, 5);
            
            // Test 4: Scan with only start key
            System.out.println("\n4. Scan with only start key:");
            testScan(txId2, tableId, "key_05", null, 10);
            
            // Commit and cleanup
            KVTNative.commitTransaction(txId2);
            
            // Drop table
            KVTNative.dropTable(tableId);
            System.out.println("\n✓ Table dropped");
            
            // Shutdown
            KVTNative.shutdown();
            System.out.println("✓ KVT shutdown");
            
            System.out.println("\n=== Scan Test Complete ===");
            
        } catch (Exception e) {
            System.err.println("Exception during test: " + e);
            e.printStackTrace();
        }
    }
    
    private static void testScan(long txId, long tableId, String startKey, String endKey, int limit) {
        try {
            byte[] start = startKey != null ? startKey.getBytes() : null;
            byte[] end = endKey != null ? endKey.getBytes() : null;
            
            System.out.println("  Calling nativeScan with:");
            System.out.println("    txId=" + txId);
            System.out.println("    tableId=" + tableId);
            System.out.println("    startKey=" + (startKey != null ? startKey : "null"));
            System.out.println("    endKey=" + (endKey != null ? endKey : "null"));
            System.out.println("    limit=" + limit);
            
            // Call nativeScan
            Object[] scanResult = KVTNative.nativeScan(txId, tableId, start, end, limit);
            
            if (scanResult == null) {
                System.err.println("  ERROR: nativeScan returned null!");
                return;
            }
            
            System.out.println("  Result array length: " + scanResult.length);
            
            // Parse result
            if (scanResult.length < 3) {
                System.err.println("  ERROR: Invalid result array length: " + scanResult.length);
                return;
            }
            
            Integer errorCode = (Integer) scanResult[0];
            KVTNative.KVTError error = KVTNative.KVTError.fromCode(errorCode);
            System.out.println("  Error code: " + errorCode + " (" + error + ")");
            
            if (error != KVTNative.KVTError.SUCCESS) {
                System.err.println("  ERROR: Scan failed with: " + error);
                return;
            }
            
            byte[][] keys = (byte[][]) scanResult[1];
            byte[][] values = (byte[][]) scanResult[2];
            
            System.out.println("  Results found: " + keys.length);
            for (int i = 0; i < keys.length && i < 5; i++) {
                System.out.println("    [" + i + "] " + new String(keys[i]) + " = " + new String(values[i]));
            }
            if (keys.length > 5) {
                System.out.println("    ... (" + (keys.length - 5) + " more)");
            }
            
        } catch (Exception e) {
            System.err.println("  EXCEPTION in testScan: " + e);
            e.printStackTrace();
        }
    }
}