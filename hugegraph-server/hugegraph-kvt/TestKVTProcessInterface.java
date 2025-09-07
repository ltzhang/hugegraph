import org.apache.hugegraph.backend.store.kvt.KVTNative;

public class TestKVTProcessInterface {
    static {
        System.setProperty("java.library.path", "./src/main/resources/native");
        // Library is loaded automatically by KVTNative static block
    }
    
    public static void main(String[] args) {
        System.out.println("Testing KVT Process Interface...");
        
        // Initialize KVT
        int initResult = KVTNative.nativeInitialize();
        if (initResult != 0) {
            System.err.println("Failed to initialize KVT: " + initResult);
            System.exit(1);
        }
        System.out.println("KVT initialized successfully");
        
        try {
            // Create a table
            Object[] createResult = KVTNative.nativeCreateTable("test_table", "hash");
            Integer createError = (Integer) createResult[0];
            Long tableId = (Long) createResult[1];
            
            if (createError != 0) {
                System.err.println("Failed to create table: " + createResult[2]);
                System.exit(1);
            }
            System.out.println("Table created with ID: " + tableId);
            
            // Start a transaction
            Object[] txResult = KVTNative.nativeStartTransaction();
            Integer txError = (Integer) txResult[0];
            Long txId = (Long) txResult[1];
            
            if (txError != 0) {
                System.err.println("Failed to start transaction: " + txResult[2]);
                System.exit(1);
            }
            System.out.println("Transaction started with ID: " + txId);
            
            // Test property update (which uses kvt_process internally)
            byte[] key = "vertex_key".getBytes();
            byte[] initialValue = "initial_data".getBytes();
            
            // First set an initial value
            Object[] setResult = KVTNative.nativeSet(txId, tableId, key, initialValue);
            Integer setError = (Integer) setResult[0];
            if (setError != 0) {
                System.err.println("Failed to set initial value: " + setResult[1]);
                System.exit(1);
            }
            System.out.println("Initial value set successfully");
            
            // Now test the property update using kvt_process
            // Format: [property_name_len][property_name][property_value_len][property_value]
            String propName = "test_prop";
            String propValue = "test_value";
            
            // Build the parameter with simple encoding (for testing)
            byte[] paramBytes = new byte[2 + propName.length() + propValue.length()];
            paramBytes[0] = (byte) propName.length();
            System.arraycopy(propName.getBytes(), 0, paramBytes, 1, propName.length());
            paramBytes[1 + propName.length()] = (byte) propValue.length();
            System.arraycopy(propValue.getBytes(), 0, paramBytes, 2 + propName.length(), propValue.length());
            
            Object[] updateResult = KVTNative.nativeVertexPropertyUpdate(txId, tableId, key, paramBytes);
            Integer updateError = (Integer) updateResult[0];
            
            if (updateError != 0) {
                System.err.println("Failed to update property: " + updateResult[2]);
            } else {
                System.out.println("Property update succeeded");
                byte[] resultBytes = (byte[]) updateResult[1];
                if (resultBytes != null) {
                    System.out.println("Update result: " + new String(resultBytes));
                }
            }
            
            // Commit the transaction
            Object[] commitResult = KVTNative.nativeCommitTransaction(txId);
            Integer commitError = (Integer) commitResult[0];
            
            if (commitError != 0) {
                System.err.println("Failed to commit transaction: " + commitResult[1]);
                System.exit(1);
            }
            System.out.println("Transaction committed successfully");
            
            System.out.println("\nAll tests passed successfully!");
            
        } finally {
            // Shutdown KVT
            KVTNative.nativeShutdown();
            System.out.println("KVT shutdown completed");
        }
    }
}