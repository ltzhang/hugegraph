import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.nio.charset.StandardCharsets;

/**
 * Standalone test for KVT JNI connectivity
 */
public class TestKVTConnectivity {

    static {
        // Load the JNI library
        try {
            String libPath = System.getProperty("user.dir") + 
                           "/target/native/libkvtjni.so";
            System.load(libPath);
            System.out.println("Loaded library from: " + libPath);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load library: " + e.getMessage());
            throw e;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== KVT Connectivity Test ===\n");
        
        try {
            // 1. Initialize KVT
            System.out.println("1. Initializing KVT...");
            KVTNative.KVTError initError = KVTNative.initialize();
            if (initError != KVTNative.KVTError.SUCCESS) {
                throw new RuntimeException("Failed to initialize KVT: " + initError);
            }
            System.out.println("   ✓ KVT initialized successfully");
            
            // 2. Create a table
            System.out.println("\n2. Creating table...");
            KVTNative.KVTResult<Long> createResult = 
                KVTNative.createTable("test_table", "hash");
            if (createResult.error != KVTNative.KVTError.SUCCESS) {
                throw new RuntimeException("Failed to create table: " + createResult.errorMessage);
            }
            long tableId = createResult.value;
            System.out.println("   ✓ Created table with ID: " + tableId);
            
            // 3. Start a transaction
            System.out.println("\n3. Starting transaction...");
            KVTNative.KVTResult<Long> txResult = KVTNative.startTransaction();
            if (txResult.error != KVTNative.KVTError.SUCCESS) {
                throw new RuntimeException("Failed to start transaction: " + txResult.errorMessage);
            }
            long txId = txResult.value;
            System.out.println("   ✓ Started transaction with ID: " + txId);
            
            // 4. Write data
            System.out.println("\n4. Writing data...");
            byte[] key = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] value = "world".getBytes(StandardCharsets.UTF_8);
            
            KVTNative.KVTResult<Void> setResult = 
                KVTNative.set(txId, tableId, key, value);
            if (setResult.error != KVTNative.KVTError.SUCCESS) {
                throw new RuntimeException("Failed to set value: " + setResult.errorMessage);
            }
            System.out.println("   ✓ Set key='hello', value='world'");
            
            // 5. Read data
            System.out.println("\n5. Reading data...");
            KVTNative.KVTResult<byte[]> getResult = 
                KVTNative.get(txId, tableId, key);
            if (getResult.error != KVTNative.KVTError.SUCCESS) {
                throw new RuntimeException("Failed to get value: " + getResult.errorMessage);
            }
            String retrievedValue = new String(getResult.value, StandardCharsets.UTF_8);
            System.out.println("   ✓ Retrieved value: '" + retrievedValue + "'");
            
            if (!retrievedValue.equals("world")) {
                throw new RuntimeException("Value mismatch! Expected 'world', got '" + 
                                         retrievedValue + "'");
            }
            
            // 6. Commit transaction
            System.out.println("\n6. Committing transaction...");
            KVTNative.KVTResult<Void> commitResult = 
                KVTNative.commitTransaction(txId);
            if (commitResult.error != KVTNative.KVTError.SUCCESS) {
                throw new RuntimeException("Failed to commit: " + commitResult.errorMessage);
            }
            System.out.println("   ✓ Transaction committed");
            
            // 7. Verify persistence (read without transaction)
            System.out.println("\n7. Verifying persistence...");
            KVTNative.KVTResult<byte[]> verifyResult = 
                KVTNative.get(0, tableId, key);  // txId=0 for auto-commit
            if (verifyResult.error != KVTNative.KVTError.SUCCESS) {
                throw new RuntimeException("Failed to verify: " + verifyResult.errorMessage);
            }
            String persistedValue = new String(verifyResult.value, StandardCharsets.UTF_8);
            System.out.println("   ✓ Persisted value: '" + persistedValue + "'");
            
            // 8. Clean up
            System.out.println("\n8. Cleaning up...");
            KVTNative.KVTResult<Void> dropResult = KVTNative.dropTable(tableId);
            if (dropResult.error != KVTNative.KVTError.SUCCESS) {
                throw new RuntimeException("Failed to drop table: " + dropResult.errorMessage);
            }
            System.out.println("   ✓ Table dropped");
            
            // 9. Shutdown
            System.out.println("\n9. Shutting down KVT...");
            KVTNative.shutdown();
            System.out.println("   ✓ KVT shutdown complete");
            
            System.out.println("\n=== ALL TESTS PASSED! ===");
            System.out.println("KVT JNI bridge is working correctly.");
            
        } catch (Exception e) {
            System.err.println("\n=== TEST FAILED ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}