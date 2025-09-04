import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.io.File;

public class SimpleKVTTest {
    
    public static void main(String[] args) {
        System.out.println("=== Simple KVT Integration Test ===\n");
        
        try {
            // Load the native library through KVTNative
            System.out.println("0. Loading KVT library...");
            try {
                // KVTNative will load the library in its static block
                Class.forName("org.apache.hugegraph.backend.store.kvt.KVTNative");
                System.out.println("   ✓ KVT library loaded successfully");
            } catch (Exception e) {
                System.err.println("   ✗ Failed to load KVT library: " + e.getMessage());
                System.exit(1);
            }
            
            // Initialize KVT
            System.out.println("\n1. Initializing KVT...");
            KVTNative.KVTError initError = KVTNative.initialize();
            if (initError == KVTNative.KVTError.SUCCESS) {
                System.out.println("   ✓ KVT initialized successfully");
            } else {
                System.err.println("   ✗ Failed to initialize KVT: " + initError);
                System.exit(1);
            }
            
            // Create a table
            System.out.println("\n2. Creating table 'test_table'...");
            KVTNative.KVTResult<Long> tableResult = KVTNative.createTable("test_table", "hash");
            if (tableResult.error == KVTNative.KVTError.SUCCESS) {
                long tableId = tableResult.value;
                System.out.println("   ✓ Table created with ID: " + tableId);
            } else {
                System.err.println("   ✗ Failed to create table: " + tableResult.error);
                System.exit(1);
            }
            long tableId = tableResult.value;
            
            // Start a transaction
            System.out.println("\n3. Starting transaction...");
            KVTNative.KVTResult<Long> txResult = KVTNative.startTransaction();
            if (txResult.error == KVTNative.KVTError.SUCCESS) {
                long txnId = txResult.value;
                System.out.println("   ✓ Transaction started with ID: " + txnId);
            } else {
                System.err.println("   ✗ Failed to start transaction: " + txResult.error);
                System.exit(1);
            }
            long txnId = txResult.value;
            
            // Set a key-value pair
            System.out.println("\n4. Setting key-value pair...");
            byte[] key = "test_key".getBytes();
            byte[] value = "test_value".getBytes();
            KVTNative.KVTResult<Void> setResult = KVTNative.set(txnId, tableId, key, value);
            if (setResult.error == KVTNative.KVTError.SUCCESS) {
                System.out.println("   ✓ Set key='test_key', value='test_value'");
            } else {
                System.err.println("   ✗ Failed to set key-value: " + setResult.error);
                System.exit(1);
            }
            
            // Get the value
            System.out.println("\n5. Getting value for key...");
            KVTNative.KVTResult<byte[]> getResult = KVTNative.get(txnId, tableId, key);
            if (getResult.error == KVTNative.KVTError.SUCCESS) {
                String retrievedValue = new String(getResult.value);
                System.out.println("   ✓ Retrieved value: " + retrievedValue);
                if (!"test_value".equals(retrievedValue)) {
                    System.err.println("   ✗ Value mismatch!");
                    System.exit(1);
                }
            } else {
                System.err.println("   ✗ Failed to get value: " + getResult.error);
                System.exit(1);
            }
            
            // Commit the transaction
            System.out.println("\n6. Committing transaction...");
            KVTNative.KVTResult<Void> commitResult = KVTNative.commitTransaction(txnId);
            if (commitResult.error == KVTNative.KVTError.SUCCESS) {
                System.out.println("   ✓ Transaction committed successfully");
            } else {
                System.err.println("   ✗ Failed to commit transaction: " + commitResult.error);
                System.exit(1);
            }
            
            // Verify data persisted (read without transaction)
            System.out.println("\n7. Verifying data persistence...");
            KVTNative.KVTResult<byte[]> verifyResult = KVTNative.get(0, tableId, key);
            if (verifyResult.error == KVTNative.KVTError.SUCCESS) {
                String persistedValue = new String(verifyResult.value);
                System.out.println("   ✓ Data persisted: " + persistedValue);
            } else {
                System.err.println("   ✗ Failed to verify persistence: " + verifyResult.error);
                System.exit(1);
            }
            
            // Clean up - drop table
            System.out.println("\n8. Cleaning up...");
            KVTNative.KVTResult<Void> dropResult = KVTNative.dropTable(tableId);
            if (dropResult.error == KVTNative.KVTError.SUCCESS) {
                System.out.println("   ✓ Table dropped");
            } else {
                System.err.println("   ✗ Failed to drop table: " + dropResult.error);
                System.exit(1);
            }
            
            // Shutdown KVT
            System.out.println("\n9. Shutting down KVT...");
            KVTNative.shutdown();
            System.out.println("   ✓ KVT shut down successfully");
            
            System.out.println("\n=== ALL TESTS PASSED! ===");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed with exception: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}