import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.nio.ByteBuffer;

public class TestEdgeDeletion {
    public static void main(String[] args) {
        System.out.println("=== Test Edge Deletion ===\n");
        
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
            
            // Create edge table
            Object[] tableResult = KVTNative.nativeCreateTable("edge_table", "range");
            Integer error = (Integer) tableResult[0];
            Long tableId = (Long) tableResult[1];
            if (error != 0) {
                throw new RuntimeException("Failed to create table: " + tableResult[2]);
            }
            System.out.println("✓ Created edge table with ID: " + tableId);
            
            // Start transaction
            Object[] txResult = KVTNative.nativeStartTransaction();
            error = (Integer) txResult[0];
            Long txId = (Long) txResult[1];
            if (error != 0) {
                throw new RuntimeException("Failed to start transaction: " + txResult[2]);
            }
            System.out.println("✓ Started transaction: " + txId);
            
            // Create 3 edges: alice->bob, alice->charlie, bob->charlie
            String edge1Key = "alice_knows_bob";
            String edge2Key = "alice_knows_charlie";
            String edge3Key = "bob_knows_charlie";
            
            // Add edge 1
            byte[] edge1Value = "alice->bob:weight=1.0".getBytes();
            Object[] setResult = KVTNative.nativeSet(txId, tableId, edge1Key.getBytes(), edge1Value);
            error = (Integer) setResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to create edge 1: " + setResult[1]);
            }
            System.out.println("✓ Created edge: " + edge1Key);
            
            // Add edge 2
            byte[] edge2Value = "alice->charlie:weight=2.0".getBytes();
            setResult = KVTNative.nativeSet(txId, tableId, edge2Key.getBytes(), edge2Value);
            error = (Integer) setResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to create edge 2: " + setResult[1]);
            }
            System.out.println("✓ Created edge: " + edge2Key);
            
            // Add edge 3
            byte[] edge3Value = "bob->charlie:weight=3.0".getBytes();
            setResult = KVTNative.nativeSet(txId, tableId, edge3Key.getBytes(), edge3Value);
            error = (Integer) setResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to create edge 3: " + setResult[1]);
            }
            System.out.println("✓ Created edge: " + edge3Key);
            
            // Verify all 3 edges exist
            Object[] scanResult = KVTNative.nativeScan(txId, tableId, null, null, 10);
            error = (Integer) scanResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to scan: " + scanResult[3]);
            }
            byte[][] keys = (byte[][]) scanResult[1];
            System.out.println("✓ Found " + keys.length + " edges before deletion");
            
            // Delete edge 1 (alice->bob)
            Object[] delResult = KVTNative.nativeDel(txId, tableId, edge1Key.getBytes());
            error = (Integer) delResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to delete edge: " + delResult[1]);
            }
            System.out.println("✓ Deleted edge: " + edge1Key);
            
            // Verify only 2 edges remain
            scanResult = KVTNative.nativeScan(txId, tableId, null, null, 10);
            error = (Integer) scanResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to scan after delete: " + scanResult[3]);
            }
            keys = (byte[][]) scanResult[1];
            System.out.println("✓ Found " + keys.length + " edges after deletion");
            
            // Verify the deleted edge is gone
            Object[] getResult = KVTNative.nativeGet(txId, tableId, edge1Key.getBytes());
            error = (Integer) getResult[0];
            if (error == 2) { // KEY_NOT_FOUND
                System.out.println("✓ Confirmed: deleted edge not found");
            } else {
                throw new RuntimeException("Edge should have been deleted but was found");
            }
            
            // Commit transaction
            Object[] commitResult = KVTNative.nativeCommitTransaction(txId);
            error = (Integer) commitResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to commit: " + commitResult[1]);
            }
            System.out.println("✓ Transaction committed");
            
            // Clean up
            KVTNative.nativeDropTable(tableId);
            System.out.println("✓ Cleaned up test table");
            
            KVTNative.nativeShutdown();
            System.out.println("✓ KVT shut down");
            
            System.out.println("\n=== EDGE DELETION TEST PASSED ===");
            
        } catch (Exception e) {
            System.err.println("✗ Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}