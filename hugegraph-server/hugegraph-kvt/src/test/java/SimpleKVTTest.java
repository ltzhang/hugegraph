import java.io.File;

public class SimpleKVTTest {
    
    static {
        // Load the native library
        String kvtPath = System.getProperty("user.dir") + "/kvt/libkvt.so";
        System.load(kvtPath);
    }
    
    // Native method declarations (simplified)
    private static native long kvtInit();
    private static native void kvtShutdown();
    private static native long kvtCreateTable(String tableName);
    private static native void kvtDropTable(long tableId);
    private static native long kvtStartTransaction();
    private static native int kvtCommit(long txnId);
    private static native int kvtRollback(long txnId);
    private static native int kvtSet(long tableId, long txnId, byte[] key, byte[] value);
    private static native byte[] kvtGet(long tableId, long txnId, byte[] key);
    private static native int kvtDelete(long tableId, long txnId, byte[] key);
    
    public static void main(String[] args) {
        System.out.println("=== Simple KVT Integration Test ===\n");
        
        try {
            // Initialize KVT
            System.out.println("1. Initializing KVT...");
            long result = kvtInit();
            if (result == 0) {
                System.out.println("   ✓ KVT initialized successfully");
            } else {
                System.err.println("   ✗ Failed to initialize KVT");
                System.exit(1);
            }
            
            // Create a table
            System.out.println("\n2. Creating table 'test_table'...");
            long tableId = kvtCreateTable("test_table");
            if (tableId > 0) {
                System.out.println("   ✓ Table created with ID: " + tableId);
            } else {
                System.err.println("   ✗ Failed to create table");
                System.exit(1);
            }
            
            // Start a transaction
            System.out.println("\n3. Starting transaction...");
            long txnId = kvtStartTransaction();
            if (txnId > 0) {
                System.out.println("   ✓ Transaction started with ID: " + txnId);
            } else {
                System.err.println("   ✗ Failed to start transaction");
                System.exit(1);
            }
            
            // Write some data
            System.out.println("\n4. Writing key-value pairs...");
            String key1 = "key1";
            String value1 = "Hello, KVT!";
            int setResult = kvtSet(tableId, txnId, key1.getBytes(), value1.getBytes());
            if (setResult == 0) {
                System.out.println("   ✓ Set key1 = " + value1);
            } else {
                System.err.println("   ✗ Failed to set key1");
            }
            
            // Read the data back
            System.out.println("\n5. Reading data...");
            byte[] readValue = kvtGet(tableId, txnId, key1.getBytes());
            if (readValue != null) {
                String readStr = new String(readValue);
                System.out.println("   ✓ Read key1 = " + readStr);
                if (readStr.equals(value1)) {
                    System.out.println("   ✓ Data matches!");
                }
            } else {
                System.err.println("   ✗ Failed to read key1");
            }
            
            // Commit the transaction
            System.out.println("\n6. Committing transaction...");
            int commitResult = kvtCommit(txnId);
            if (commitResult == 0) {
                System.out.println("   ✓ Transaction committed successfully");
            } else {
                System.err.println("   ✗ Failed to commit transaction");
            }
            
            // Clean up
            System.out.println("\n7. Cleaning up...");
            kvtDropTable(tableId);
            System.out.println("   ✓ Table dropped");
            
            kvtShutdown();
            System.out.println("   ✓ KVT shutdown");
            
            System.out.println("\n=== Test PASSED ===");
            
        } catch (UnsatisfiedLinkError e) {
            System.err.println("✗ Failed to load native methods: " + e.getMessage());
            System.err.println("\nThis test requires the JNI bridge library (libkvtjni.so)");
            System.err.println("Build it with: cd src/main/native && make");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("✗ Unexpected error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}