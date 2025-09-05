import org.apache.hugegraph.backend.store.kvt.KVTNative;

public class TestNumericKeyScan {
    public static void main(String[] args) {
        System.out.println("=== Test Numeric Key Scan ===\n");
        
        try {
            // Load native library
            System.loadLibrary("kvtjni");
            System.out.println("✓ Native library loaded");
            
            // Initialize KVT
            int result = KVTNative.nativeInitialize();
            if (result != 0) {
                throw new RuntimeException("Failed to initialize KVT: " + result);
            }
            System.out.println("✓ KVT initialized");
            
            // Create test table
            Object[] tableResult = KVTNative.nativeCreateTable("numeric_table", "range");
            Integer error = (Integer) tableResult[0];
            Long tableId = (Long) tableResult[1];
            if (error != 0) {
                throw new RuntimeException("Failed to create table: " + tableResult[2]);
            }
            System.out.println("✓ Created table with ID: " + tableId);
            
            // Start transaction
            Object[] txResult = KVTNative.nativeStartTransaction();
            error = (Integer) txResult[0];
            Long txId = (Long) txResult[1];
            if (error != 0) {
                throw new RuntimeException("Failed to start transaction: " + txResult[2]);
            }
            System.out.println("✓ Started transaction: " + txId);
            
            // Insert numeric keys like stress test does
            System.out.println("\n== Inserting numeric keys ==");
            for (int i = 100; i <= 200; i += 10) {
                String key = String.format("%05d", i);  // Format as 5-digit string
                String value = "value_" + i;
                
                Object[] setResult = KVTNative.nativeSet(txId, tableId, key.getBytes(), value.getBytes());
                error = (Integer) setResult[0];
                if (error != 0) {
                    throw new RuntimeException("Failed to set " + key + ": " + setResult[1]);
                }
                System.out.println("  ✓ Set " + key);
            }
            
            // Test similar scan to stress test
            System.out.println("\n== Test scan from 00120 to 00180 ==");
            String startKey = "00120";
            String endKey = "00180";
            
            Object[] scanResult = KVTNative.nativeScan(txId, tableId, 
                startKey.getBytes(), endKey.getBytes(), 100);
            error = (Integer) scanResult[0];
            
            System.out.println("Scan result error code: " + error);
            
            if (error != 0 && error != 21) {  // 21 is SCAN_LIMIT_REACHED
                System.err.println("Scan failed with error code: " + error);
                String errorMsg = (String) scanResult[3];
                System.err.println("Error message: " + errorMsg);
                
                // Try without transaction
                System.out.println("\n== Trying scan without active transaction (one-shot) ==");
                scanResult = KVTNative.nativeScan(0L, tableId, 
                    startKey.getBytes(), endKey.getBytes(), 100);
                error = (Integer) scanResult[0];
                System.out.println("One-shot scan error code: " + error);
                if (error != 0) {
                    errorMsg = (String) scanResult[3];
                    System.err.println("One-shot error message: " + errorMsg);
                }
            } else {
                byte[][] keys = (byte[][]) scanResult[1];
                byte[][] values = (byte[][]) scanResult[2];
                System.out.println("  Found " + keys.length + " entries:");
                for (int i = 0; i < keys.length; i++) {
                    System.out.println("    - " + new String(keys[i]) + " = " + new String(values[i]));
                }
            }
            
            // Commit transaction
            Object[] commitResult = KVTNative.nativeCommitTransaction(txId);
            error = (Integer) commitResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to commit: " + commitResult[1]);
            }
            System.out.println("\n✓ Transaction committed");
            
            // Clean up
            KVTNative.nativeDropTable(tableId);
            System.out.println("✓ Cleaned up test table");
            
            KVTNative.nativeShutdown();
            System.out.println("✓ KVT shut down");
            
            System.out.println("\n=== TEST COMPLETED ===");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}