import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.nio.ByteBuffer;

public class TestEdgePropertyUpdateFixed {
    public static void main(String[] args) {
        System.out.println("=== Test Edge Property Update (Fixed) ===\n");
        
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
            
            // Update weight property to 2.5
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
            
            // Get the updated edge
            Object[] getResult = KVTNative.nativeGet(txId, tableId, edgeKey);
            error = (Integer) getResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to get edge: " + getResult[2]);
            }
            byte[] updatedValue = (byte[]) getResult[1];
            System.out.println("✓ Retrieved updated edge, size: " + updatedValue.length + " bytes");
            
            // Verification - check occurrences
            String valueStr = new String(updatedValue);
            int count10 = countOccurrences(valueStr, "1.0");
            int count25 = countOccurrences(valueStr, "2.5");
            
            System.out.println("\nVerification Results:");
            System.out.println("  Occurrences of '1.0': " + count10);
            System.out.println("  Occurrences of '2.5': " + count25);
            
            if (count10 == 0 && count25 == 1) {
                System.out.println("✓ SUCCESS: Property properly replaced (no duplicate)");
            } else if (count10 > 0 && count25 > 0) {
                System.out.println("✗ FAILURE: Both old and new values present (append bug still exists)");
            } else {
                System.out.println("? UNEXPECTED: count10=" + count10 + ", count25=" + count25);
            }
            
            // Update weight property again to 3.7
            updateData = ByteBuffer.allocate(20);
            updateData.put((byte) 6); // property name length
            updateData.put("weight".getBytes());
            updateData.put((byte) 3); // property value length
            updateData.put("3.7".getBytes());
            
            propertyUpdate = new byte[updateData.position()];
            updateData.flip();
            updateData.get(propertyUpdate);
            
            // Second update
            updateResult = KVTNative.nativeEdgePropertyUpdate(
                txId, tableId, edgeKey, propertyUpdate);
            error = (Integer) updateResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to update property: " + updateResult[2]);
            }
            System.out.println("\n✓ Updated edge property weight to 3.7");
            
            // Get the edge after second update
            getResult = KVTNative.nativeGet(txId, tableId, edgeKey);
            error = (Integer) getResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to get edge: " + getResult[2]);
            }
            updatedValue = (byte[]) getResult[1];
            
            valueStr = new String(updatedValue);
            count10 = countOccurrences(valueStr, "1.0");
            count25 = countOccurrences(valueStr, "2.5");
            int count37 = countOccurrences(valueStr, "3.7");
            
            System.out.println("\nSecond Update Verification:");
            System.out.println("  Occurrences of '1.0': " + count10);
            System.out.println("  Occurrences of '2.5': " + count25);
            System.out.println("  Occurrences of '3.7': " + count37);
            
            if (count10 == 0 && count25 == 0 && count37 == 1) {
                System.out.println("✓ SUCCESS: Multiple updates work correctly");
            } else {
                System.out.println("✗ FAILURE: Old values still present after multiple updates");
            }
            
            // Commit transaction
            Object[] commitResult = KVTNative.nativeCommitTransaction(txId);
            error = (Integer) commitResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to commit: " + commitResult[1]);
            }
            System.out.println("\n✓ Transaction committed");
            
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
    
    private static int countOccurrences(String str, String substring) {
        int count = 0;
        int index = 0;
        while ((index = str.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}