import org.apache.hugegraph.backend.store.kvt.KVTNative;

public class TestFullScan {
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
        System.out.println("=== Testing Full Table Scan ===\n");
        
        try {
            // Initialize KVT
            int initResult = KVTNative.nativeInitialize();
            if (initResult != 0) {
                System.err.println("Failed to initialize KVT: " + initResult);
                return;
            }
            System.out.println("✓ KVT initialized");
            
            // Create a table
            Object[] tableResult = KVTNative.nativeCreateTable("scan_test", "range");
            if ((int)tableResult[0] != 0) {
                System.err.println("Failed to create table, error code: " + tableResult[0]);
                if (tableResult.length > 2 && tableResult[2] != null) {
                    System.err.println("  Error message: " + tableResult[2]);
                }
                return;
            }
            long tableId = (long)tableResult[1];
            System.out.println("✓ Created table with ID: " + tableId);
            
            // Verify table exists
            if (tableId < 0) {
                System.err.println("Invalid table ID: " + tableId);
                return;
            }
            
            // Start transaction
            Object[] txResult = KVTNative.nativeStartTransaction();
            long txId = (long)txResult[1];
            System.out.println("✓ Started transaction: " + txId);
            
            // Insert test data with various key formats
            String[] testKeys = {
                "a_first",
                "b_second", 
                "c_third",
                "key_00",
                "key_01",
                "key_02",
                "test_key_1",
                "test_key_2",
                "z_last"
            };
            
            System.out.println("\nInserting " + testKeys.length + " test keys:");
            for (String key : testKeys) {
                Object[] setResult = KVTNative.nativeSet(txId, tableId, 
                    key.getBytes(), ("value_" + key).getBytes());
                if ((int)setResult[0] != 0) {
                    System.err.println("Failed to set key: " + key + ", error code: " + setResult[0]);
                    if (setResult[3] != null) {
                        System.err.println("  Error message: " + setResult[3]);
                    }
                    return;
                }
                System.out.println("  - Inserted: " + key);
            }
            
            // Commit to persist data
            KVTNative.nativeCommitTransaction(txId);
            System.out.println("\n✓ Transaction committed");
            
            // Start new transaction for scanning
            txResult = KVTNative.nativeStartTransaction();
            txId = (long)txResult[1];
            System.out.println("✓ Started new transaction for scanning: " + txId);
            
            // Test 1: Scan with specific bounds
            System.out.println("\n1. Scan with bounds [a, z]:");
            Object[] scanResult = KVTNative.nativeScan(txId, tableId, 
                "a".getBytes(), "z".getBytes(), 100);
            printScanResults(scanResult);
            
            // Test 2: Full scan with null bounds
            System.out.println("\n2. Full scan with null bounds:");
            scanResult = KVTNative.nativeScan(txId, tableId, null, null, 100);
            printScanResults(scanResult);
            
            // Test 3: Scan with empty string bounds
            System.out.println("\n3. Scan with empty string start:");
            scanResult = KVTNative.nativeScan(txId, tableId, "".getBytes(), "zzz".getBytes(), 100);
            printScanResults(scanResult);
            
            // Test 4: Scan from beginning with null start
            System.out.println("\n4. Scan from beginning (null start, 'zzz' end):");
            scanResult = KVTNative.nativeScan(txId, tableId, null, "zzz".getBytes(), 100);
            printScanResults(scanResult);
            
            // Test 5: Scan to end with null end
            System.out.println("\n5. Scan to end ('a' start, null end):");
            scanResult = KVTNative.nativeScan(txId, tableId, "a".getBytes(), null, 100);
            printScanResults(scanResult);
            
            // Cleanup
            KVTNative.nativeCommitTransaction(txId);
            KVTNative.nativeDropTable(tableId);
            KVTNative.nativeShutdown();
            
            System.out.println("\n✓ Test completed successfully");
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e);
            e.printStackTrace();
        }
    }
    
    private static void printScanResults(Object[] scanResult) {
        int errorCode = (int)scanResult[0];
        System.out.println("  Error code: " + errorCode);
        
        if (errorCode != 0) {
            System.out.println("  ERROR: Scan failed with code " + errorCode);
            String errorMsg = (String)scanResult[3];
            if (errorMsg != null && !errorMsg.isEmpty()) {
                System.out.println("  Error message: " + errorMsg);
            }
            return;
        }
        
        byte[][] keys = (byte[][])scanResult[1];
        byte[][] values = (byte[][])scanResult[2];
        
        if (keys == null || keys.length == 0) {
            System.out.println("  No results found");
        } else {
            System.out.println("  Found " + keys.length + " results:");
            for (int i = 0; i < keys.length && i < 10; i++) {
                String key = new String(keys[i]);
                String value = new String(values[i]);
                System.out.println("    [" + i + "] " + key + " = " + value);
            }
            if (keys.length > 10) {
                System.out.println("    ... and " + (keys.length - 10) + " more");
            }
        }
    }
}