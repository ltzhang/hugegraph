import org.apache.hugegraph.backend.store.kvt.KVTNative;

public class TestDeleteCommit {
    public static void main(String[] args) {
        System.out.println("=== Test Delete Commit ===");
        
        try {
            // Load library
            Class.forName("org.apache.hugegraph.backend.store.kvt.KVTNative");
            
            // Initialize KVT
            KVTNative.KVTError error = KVTNative.initialize();
            if (error != KVTNative.KVTError.SUCCESS) {
                System.err.println("Failed to initialize: " + error);
                System.exit(1);
            }
            System.out.println("✓ KVT initialized");
            
            // Create table
            KVTNative.KVTResult<Long> tableResult = KVTNative.createTable("test_delete", "hash");
            if (tableResult.error != KVTNative.KVTError.SUCCESS) {
                System.err.println("Failed to create table: " + tableResult.error);
                System.exit(1);
            }
            long tableId = tableResult.value;
            System.out.println("✓ Table created with ID: " + tableId);
            
            // Start transaction
            KVTNative.KVTResult<Long> txResult = KVTNative.startTransaction();
            if (txResult.error != KVTNative.KVTError.SUCCESS) {
                System.err.println("Failed to start transaction: " + txResult.error);
                System.exit(1);
            }
            long txId = txResult.value;
            System.out.println("✓ Transaction started with ID: " + txId);
            
            // Set a value
            byte[] key = "test_key".getBytes();
            byte[] value = "test_value".getBytes();
            KVTNative.KVTResult<Void> setResult = KVTNative.set(txId, tableId, key, value);
            if (setResult.error != KVTNative.KVTError.SUCCESS) {
                System.err.println("Failed to set value: " + setResult.error);
                System.exit(1);
            }
            System.out.println("✓ Set key='test_key', value='test_value'");
            
            // Delete the key
            KVTNative.KVTResult<Void> delResult = KVTNative.del(txId, tableId, key);
            if (delResult.error != KVTNative.KVTError.SUCCESS) {
                System.err.println("Failed to delete key: " + delResult.error);
                System.exit(1);
            }
            System.out.println("✓ Deleted key='test_key'");
            
            // Verify key is marked as deleted
            KVTNative.KVTResult<byte[]> getResult = KVTNative.get(txId, tableId, key);
            if (getResult.error != KVTNative.KVTError.KEY_IS_DELETED) {
                System.err.println("Expected KEY_IS_DELETED but got: " + getResult.error);
                System.exit(1);
            }
            System.out.println("✓ Key is marked as deleted");
            
            // Commit transaction - This is where it previously crashed
            System.out.println("Attempting to commit transaction with deleted key...");
            KVTNative.KVTResult<Void> commitResult = KVTNative.commitTransaction(txId);
            if (commitResult.error != KVTNative.KVTError.SUCCESS) {
                System.err.println("Failed to commit: " + commitResult.error);
                System.exit(1);
            }
            System.out.println("✓ Transaction committed successfully!");
            
            // Verify deletion persisted
            KVTNative.KVTResult<byte[]> verifyResult = KVTNative.get(0, tableId, key);
            if (verifyResult.error == KVTNative.KVTError.KEY_IS_DELETED || 
                verifyResult.error == KVTNative.KVTError.KEY_NOT_FOUND) {
                System.out.println("✓ Deletion persisted (status: " + verifyResult.error + ")");
            } else {
                System.err.println("Unexpected status after commit: " + verifyResult.error);
                System.exit(1);
            }
            
            // Clean up
            KVTNative.dropTable(tableId);
            KVTNative.shutdown();
            
            System.out.println("\n=== DELETE COMMIT TEST PASSED! ===");
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}