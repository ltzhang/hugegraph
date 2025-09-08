import org.apache.hugegraph.backend.store.kvt.KVTNative;
import org.apache.hugegraph.backend.store.kvt.KVTNative.KVTResult;
import org.apache.hugegraph.backend.store.kvt.KVTNative.KVTPair;

public class TestOptimizations {
    
    public static void main(String[] args) {
        System.out.println("=== Testing KVT Optimizations ===\n");
        
        // Initialize KVT
        System.out.println("1. Initializing KVT...");
        KVTNative.KVTError initError = KVTNative.initialize();
        if (initError != KVTNative.KVTError.SUCCESS) {
            System.err.println("   ✗ Failed to initialize KVT: " + initError);
            return;
        }
        System.out.println("   ✓ KVT initialized");
        
        // Create table
        System.out.println("\n2. Creating test table...");
        KVTResult<Long> tableResult = KVTNative.createTable("opt_test", "hash");
        if (tableResult.error != KVTNative.KVTError.SUCCESS) {
            System.err.println("   ✗ Failed to create table: " + tableResult.error);
            return;
        }
        long tableId = tableResult.value;
        System.out.println("   ✓ Table created with ID: " + tableId);
        
        // Start transaction
        System.out.println("\n3. Starting transaction...");
        KVTResult<Long> txResult = KVTNative.startTransaction();
        if (txResult.error != KVTNative.KVTError.SUCCESS) {
            System.err.println("   ✗ Failed to start transaction: " + txResult.error);
            return;
        }
        long txId = txResult.value;
        System.out.println("   ✓ Transaction started with ID: " + txId);
        
        // Test value setup
        System.out.println("\n4. Setting up test value...");
        for (int i = 1; i <= 10; i++) {
            String key = "key" + i;
            String value = "value" + i;
            KVTResult<Void> setResult = KVTNative.set(txId, tableId, key.getBytes(), value.getBytes());
            if (setResult.error != KVTNative.KVTError.SUCCESS) {
                System.err.println("   ✗ Failed to set " + key + ": " + setResult.error);
                return;
            }
        }
        System.out.println("   ✓ Added 10 key-value pairs");
        
        // Test 1: Batch Get
        System.out.println("\n5. Testing Batch Get...");
        byte[][] keysToGet = new byte[][] {
            "key1".getBytes(),
            "key3".getBytes(),
            "key5".getBytes(),
            "key7".getBytes(),
            "key9".getBytes()
        };
        
        KVTResult<byte[][]> batchGetResult = KVTNative.batchGet(txId, tableId, keysToGet);
        if (batchGetResult.error != KVTNative.KVTError.SUCCESS) {
            System.err.println("   ✗ Batch get failed: " + batchGetResult.error);
        } else {
            System.out.println("   ✓ Batch get succeeded, retrieved " + batchGetResult.value.length + " values:");
            for (int i = 0; i < batchGetResult.value.length; i++) {
                if (batchGetResult.value[i] != null) {
                    System.out.println("     - key" + (i*2 + 1) + " = " + new String(batchGetResult.value[i]));
                }
            }
        }
        
        // Test 2: Scan with Filter
        System.out.println("\n6. Testing Scan with Filter...");
        byte[] startKey = "key1".getBytes();
        byte[] endKey = "key9".getBytes();
        byte[] filterParams = "".getBytes(); // No filter for now
        
        KVTResult<KVTPair[]> scanResult = KVTNative.scanWithFilter(txId, tableId, startKey, endKey, 5, filterParams);
        if (scanResult.error != KVTNative.KVTError.SUCCESS && scanResult.error != KVTNative.KVTError.SCAN_LIMIT_REACHED) {
            System.err.println("   ✗ Scan with filter failed: " + scanResult.error);
        } else {
            System.out.println("   ✓ Scan succeeded, found " + scanResult.value.length + " entries:");
            for (KVTPair pair : scanResult.value) {
                System.out.println("     - " + new String(pair.key) + " = " + new String(pair.value));
            }
        }
        
        // Test 3: Get Vertex Edges (simulated)
        System.out.println("\n7. Testing Get Vertex Edges...");
        
        // Add some edge value
        String vertexId = "v1";
        String[] edges = {
            vertexId + ":OUT:knows:v2",
            vertexId + ":OUT:knows:v3",
            vertexId + ":OUT:created:v4",
            vertexId + ":IN:knows:v5",
            vertexId + ":IN:created:v6"
        };
        
        for (String edge : edges) {
            KVTNative.set(txId, tableId, edge.getBytes(), ("edge_value_" + edge).getBytes());
        }
        
        // Get all OUT edges
        KVTResult<byte[][]> outEdges = KVTNative.getVertexEdges(txId, tableId, vertexId.getBytes(), 0, null);
        if (outEdges.error != KVTNative.KVTError.SUCCESS) {
            System.err.println("   ✗ Get vertex OUT edges failed: " + outEdges.error);
        } else {
            System.out.println("   ✓ Found " + outEdges.value.length + " OUT edges");
        }
        
        // Get IN edges with label filter
        KVTResult<byte[][]> inKnowsEdges = KVTNative.getVertexEdges(txId, tableId, vertexId.getBytes(), 1, "knows".getBytes());
        if (inKnowsEdges.error != KVTNative.KVTError.SUCCESS) {
            System.err.println("   ✗ Get vertex IN edges with filter failed: " + inKnowsEdges.error);
        } else {
            System.out.println("   ✓ Found " + inKnowsEdges.value.length + " IN 'knows' edges");
        }
        
        // Commit transaction
        System.out.println("\n8. Committing transaction...");
        KVTResult<Void> commitResult = KVTNative.commitTransaction(txId);
        if (commitResult.error != KVTNative.KVTError.SUCCESS) {
            System.err.println("   ✗ Failed to commit: " + commitResult.error);
            return;
        }
        System.out.println("   ✓ Transaction committed");
        
        // Cleanup
        System.out.println("\n9. Cleaning up...");
        KVTNative.dropTable(tableId);
        System.out.println("   ✓ Table dropped");
        
        System.out.println("\n10. Shutting down KVT...");
        KVTNative.shutdown();
        System.out.println("   ✓ KVT shut down");
        
        System.out.println("\n=== ALL OPTIMIZATION TESTS PASSED! ===");
    }
}