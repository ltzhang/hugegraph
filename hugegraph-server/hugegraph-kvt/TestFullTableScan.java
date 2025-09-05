import org.apache.hugegraph.backend.store.kvt.KVTNative;

public class TestFullTableScan {
    public static void main(String[] args) {
        System.out.println("=== Test Full Table Scan with New API ===\n");
        
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
            Object[] tableResult = KVTNative.nativeCreateTable("test_table", "range");
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
            
            // Insert test data
            System.out.println("\n== Inserting test data ==");
            for (int i = 1; i <= 10; i++) {
                String key = String.format("key_%02d", i);
                String value = "value_" + i;
                
                Object[] setResult = KVTNative.nativeSet(txId, tableId, key.getBytes(), value.getBytes());
                error = (Integer) setResult[0];
                if (error != 0) {
                    throw new RuntimeException("Failed to set " + key + ": " + setResult[1]);
                }
                System.out.println("  ✓ Set " + key + " = " + value);
            }
            
            // Test 1: Full table scan with null keys (should use min/max)
            System.out.println("\n== Test 1: Full table scan (null, null) ==");
            Object[] scanResult = KVTNative.nativeScan(txId, tableId, null, null, 100);
            error = (Integer) scanResult[0];
            if (error != 0) {
                throw new RuntimeException("Full table scan failed: " + scanResult[3]);
            }
            
            byte[][] keys = (byte[][]) scanResult[1];
            byte[][] values = (byte[][]) scanResult[2];
            System.out.println("  Found " + keys.length + " entries:");
            for (int i = 0; i < keys.length && i < 5; i++) {
                System.out.println("    - " + new String(keys[i]) + " = " + new String(values[i]));
            }
            if (keys.length > 5) {
                System.out.println("    ... and " + (keys.length - 5) + " more");
            }
            
            // Test 2: Range scan from key_05 to key_08
            System.out.println("\n== Test 2: Range scan (key_05 to key_08) ==");
            scanResult = KVTNative.nativeScan(txId, tableId, "key_05".getBytes(), "key_08".getBytes(), 100);
            error = (Integer) scanResult[0];
            if (error != 0) {
                throw new RuntimeException("Range scan failed: " + scanResult[3]);
            }
            
            keys = (byte[][]) scanResult[1];
            values = (byte[][]) scanResult[2];
            System.out.println("  Found " + keys.length + " entries:");
            for (int i = 0; i < keys.length; i++) {
                System.out.println("    - " + new String(keys[i]) + " = " + new String(values[i]));
            }
            
            // Test 3: Scan from key_08 to end (null)
            System.out.println("\n== Test 3: Scan from key_08 to end ==");
            scanResult = KVTNative.nativeScan(txId, tableId, "key_08".getBytes(), null, 100);
            error = (Integer) scanResult[0];
            if (error != 0) {
                throw new RuntimeException("Scan from key_08 failed: " + scanResult[3]);
            }
            
            keys = (byte[][]) scanResult[1];
            values = (byte[][]) scanResult[2];
            System.out.println("  Found " + keys.length + " entries:");
            for (int i = 0; i < keys.length; i++) {
                System.out.println("    - " + new String(keys[i]) + " = " + new String(values[i]));
            }
            
            // Test 4: Scan from beginning to key_03
            System.out.println("\n== Test 4: Scan from beginning to key_03 ==");
            scanResult = KVTNative.nativeScan(txId, tableId, null, "key_03".getBytes(), 100);
            error = (Integer) scanResult[0];
            if (error != 0) {
                throw new RuntimeException("Scan to key_03 failed: " + scanResult[3]);
            }
            
            keys = (byte[][]) scanResult[1];
            values = (byte[][]) scanResult[2];
            System.out.println("  Found " + keys.length + " entries:");
            for (int i = 0; i < keys.length; i++) {
                System.out.println("    - " + new String(keys[i]) + " = " + new String(values[i]));
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
            
            System.out.println("\n=== ALL SCAN TESTS PASSED ===");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}