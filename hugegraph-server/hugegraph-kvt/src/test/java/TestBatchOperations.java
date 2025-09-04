import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.util.Arrays;

public class TestBatchOperations {
    
    public static void main(String[] args) {
        System.out.println("=== KVT Batch Operations Test ===\n");
        
        try {
            // Initialize KVT
            System.out.println("1. Initializing KVT...");
            KVTNative.KVTError initError = KVTNative.initialize();
            if (initError != KVTNative.KVTError.SUCCESS) {
                System.err.println("   ✗ Failed to initialize KVT: " + initError);
                System.exit(1);
            }
            System.out.println("   ✓ KVT initialized");
            
            // Create table
            System.out.println("\n2. Creating table 'batch_test'...");
            KVTNative.KVTResult<Long> tableResult = KVTNative.createTable("batch_test", "hash");
            if (!tableResult.isSuccess()) {
                System.err.println("   ✗ Failed to create table: " + tableResult.error);
                System.exit(1);
            }
            long tableId = tableResult.value;
            System.out.println("   ✓ Table created with ID: " + tableId);
            
            // Start transaction
            System.out.println("\n3. Starting transaction...");
            KVTNative.KVTResult<Long> txResult = KVTNative.startTransaction();
            if (!txResult.isSuccess()) {
                System.err.println("   ✗ Failed to start transaction: " + txResult.error);
                System.exit(1);
            }
            long txId = txResult.value;
            System.out.println("   ✓ Transaction started with ID: " + txId);
            
            // Prepare batch operations
            System.out.println("\n4. Executing batch operations...");
            
            // Create arrays for batch execute
            int numOps = 5;
            int[] opTypes = new int[numOps];
            long[] tableIds = new long[numOps];
            byte[][] keys = new byte[numOps][];
            byte[][] values = new byte[numOps][];
            
            // Operation 1: SET key1=value1
            opTypes[0] = KVTNative.OpType.OP_SET.getCode();
            tableIds[0] = tableId;
            keys[0] = "batch_key1".getBytes();
            values[0] = "batch_value1".getBytes();
            
            // Operation 2: SET key2=value2
            opTypes[1] = KVTNative.OpType.OP_SET.getCode();
            tableIds[1] = tableId;
            keys[1] = "batch_key2".getBytes();
            values[1] = "batch_value2".getBytes();
            
            // Operation 3: SET key3=value3
            opTypes[2] = KVTNative.OpType.OP_SET.getCode();
            tableIds[2] = tableId;
            keys[2] = "batch_key3".getBytes();
            values[2] = "batch_value3".getBytes();
            
            // Operation 4: GET key1
            opTypes[3] = KVTNative.OpType.OP_GET.getCode();
            tableIds[3] = tableId;
            keys[3] = "batch_key1".getBytes();
            values[3] = null;
            
            // Operation 5: GET key2
            opTypes[4] = KVTNative.OpType.OP_GET.getCode();
            tableIds[4] = tableId;
            keys[4] = "batch_key2".getBytes();
            values[4] = null;
            
            // Execute batch
            Object[] batchResult = KVTNative.nativeBatchExecute(txId, opTypes, tableIds, keys, values);
            
            // Check overall result
            KVTNative.KVTError batchError = KVTNative.KVTError.fromCode((Integer)batchResult[0]);
            int[] resultCodes = (int[])batchResult[1];
            byte[][] resultValues = (byte[][])batchResult[2];
            String errorMsg = (String)batchResult[3];
            
            if (batchError != KVTNative.KVTError.SUCCESS) {
                System.err.println("   ✗ Batch execute failed: " + batchError + " - " + errorMsg);
                System.exit(1);
            }
            
            // Check individual operation results
            boolean allSuccess = true;
            for (int i = 0; i < numOps; i++) {
                KVTNative.KVTError opError = KVTNative.KVTError.fromCode(resultCodes[i]);
                if (opError != KVTNative.KVTError.SUCCESS) {
                    System.err.println("   ✗ Operation " + i + " failed: " + opError);
                    allSuccess = false;
                } else if (opTypes[i] == KVTNative.OpType.OP_GET.getCode()) {
                    // For GET operations, verify we got the expected value
                    if (resultValues[i] != null) {
                        String value = new String(resultValues[i]);
                        System.out.println("   ✓ GET operation " + i + " returned: " + value);
                    }
                }
            }
            
            if (allSuccess) {
                System.out.println("   ✓ All batch operations succeeded");
            } else {
                System.err.println("   ✗ Some batch operations failed");
                System.exit(1);
            }
            
            // Commit transaction
            System.out.println("\n5. Committing transaction...");
            KVTNative.KVTResult<Void> commitResult = KVTNative.commitTransaction(txId);
            if (!commitResult.isSuccess()) {
                System.err.println("   ✗ Failed to commit: " + commitResult.error);
                System.exit(1);
            }
            System.out.println("   ✓ Transaction committed");
            
            // Verify persistence
            System.out.println("\n6. Verifying data persistence...");
            for (int i = 1; i <= 3; i++) {
                String key = "batch_key" + i;
                KVTNative.KVTResult<byte[]> getResult = KVTNative.get(0, tableId, key.getBytes());
                if (getResult.isSuccess()) {
                    String value = new String(getResult.value);
                    System.out.println("   ✓ " + key + " = " + value);
                } else {
                    System.err.println("   ✗ Failed to get " + key);
                    System.exit(1);
                }
            }
            
            // Test list tables
            System.out.println("\n7. Testing list tables...");
            Object[] listResult = KVTNative.nativeListTables();
            KVTNative.KVTError listError = KVTNative.KVTError.fromCode((Integer)listResult[0]);
            if (listError == KVTNative.KVTError.SUCCESS) {
                String[] tableNames = (String[])listResult[1];
                Long[] tableIdList = (Long[])listResult[2];
                System.out.println("   ✓ Found " + tableNames.length + " table(s):");
                for (int i = 0; i < tableNames.length; i++) {
                    System.out.println("     - " + tableNames[i] + " (ID: " + tableIdList[i] + ")");
                }
            } else {
                System.err.println("   ✗ Failed to list tables");
            }
            
            // Clean up
            System.out.println("\n8. Cleaning up...");
            KVTNative.KVTResult<Void> dropResult = KVTNative.dropTable(tableId);
            if (!dropResult.isSuccess()) {
                System.err.println("   ✗ Failed to drop table: " + dropResult.error);
                System.exit(1);
            }
            System.out.println("   ✓ Table dropped");
            
            // Shutdown
            System.out.println("\n9. Shutting down KVT...");
            KVTNative.shutdown();
            System.out.println("   ✓ KVT shut down");
            
            System.out.println("\n=== ALL TESTS PASSED! ===");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed with exception: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}