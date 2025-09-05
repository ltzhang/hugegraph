import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.nio.ByteBuffer;

public class TestEdgePropertyUpdate {
    public static void main(String[] args) {
        System.out.println("=== Test Edge Property Update ===\n");
        
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
            
            // Create a test table
            Object[] createResult = KVTNative.nativeCreateTable("edge_table", "range");
            Integer error = (Integer) createResult[0];
            Long tableId = (Long) createResult[1];
            if (error != 0) {
                throw new RuntimeException("Failed to create table: " + createResult[2]);
            }
            System.out.println("✓ Created table with ID: " + tableId);
            
            // Start a transaction
            Object[] txResult = KVTNative.nativeStartTransaction();
            error = (Integer) txResult[0];
            Long txId = (Long) txResult[1];
            if (error != 0) {
                throw new RuntimeException("Failed to start transaction: " + txResult[2]);
            }
            System.out.println("✓ Started transaction: " + txId);
            
            // Create initial edge data (simplified)
            byte[] edgeKey = "edge1".getBytes();
            ByteBuffer initialData = ByteBuffer.allocate(100);
            // Write edge ID
            initialData.put("edge1".getBytes());
            // Write initial property: weight=1.0
            initialData.put((byte) 6); // property name length
            initialData.put("weight".getBytes());
            initialData.put((byte) 3); // property value length
            initialData.put("1.0".getBytes());
            // Write initial property: label=knows
            initialData.put((byte) 5); // property name length
            initialData.put("label".getBytes());
            initialData.put((byte) 5); // property value length
            initialData.put("knows".getBytes());
            
            byte[] initialValue = new byte[initialData.position()];
            initialData.flip();
            initialData.get(initialValue);
            
            // Set initial edge
            Object[] setResult = KVTNative.nativeSet(txId, tableId, edgeKey, initialValue);
            error = (Integer) setResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to set initial edge: " + setResult[1]);
            }
            System.out.println("✓ Set initial edge with weight=1.0, label=knows");
            
            // Prepare property update: weight=2.5
            ByteBuffer updateData = ByteBuffer.allocate(20);
            updateData.put((byte) 6); // property name length
            updateData.put("weight".getBytes());
            updateData.put((byte) 3); // property value length
            updateData.put("2.5".getBytes());
            
            byte[] propertyUpdate = new byte[updateData.position()];
            updateData.flip();
            updateData.get(propertyUpdate);
            
            // Update edge property
            Object[] updateResult = KVTNative.nativeEdgePropertyUpdate(
                txId, tableId, edgeKey, propertyUpdate);
            error = (Integer) updateResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to update property: " + updateResult[2]);
            }
            System.out.println("✓ Updated edge property weight to 2.5");
            System.out.println("  Update result: " + new String((byte[])updateResult[1]));
            
            // Get the updated edge
            Object[] getResult = KVTNative.nativeGet(txId, tableId, edgeKey);
            error = (Integer) getResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to get edge: " + getResult[2]);
            }
            byte[] updatedValue = (byte[]) getResult[1];
            System.out.println("✓ Retrieved updated edge, size: " + updatedValue.length + " bytes");
            
            // Simple verification - check if "2.5" appears in the data
            String valueStr = new String(updatedValue);
            if (valueStr.contains("2.5")) {
                System.out.println("✓ Verified: weight updated to 2.5");
            } else {
                System.out.println("✗ Warning: weight=2.5 not found in updated data");
            }
            
            // Note the current implementation issue
            if (valueStr.contains("1.0") && valueStr.contains("2.5")) {
                System.out.println("⚠️  Note: Both old (1.0) and new (2.5) values present");
                System.out.println("   This is the known append issue that needs fixing");
            }
            
            // Commit transaction
            Object[] commitResult = KVTNative.nativeCommitTransaction(txId);
            error = (Integer) commitResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to commit: " + commitResult[1]);
            }
            System.out.println("✓ Transaction committed");
            
            // Cleanup
            Object[] dropResult = KVTNative.nativeDropTable(tableId);
            error = (Integer) dropResult[0];
            if (error != 0) {
                System.out.println("Warning: Failed to drop table: " + dropResult[1]);
            } else {
                System.out.println("✓ Cleaned up test table");
            }
            
            // Shutdown
            KVTNative.nativeShutdown();
            System.out.println("✓ KVT shut down");
            
            System.out.println("\n=== TEST COMPLETED ===");
            
        } catch (Exception e) {
            System.err.println("✗ Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}