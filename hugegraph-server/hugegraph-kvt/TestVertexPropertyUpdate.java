import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.nio.ByteBuffer;

public class TestVertexPropertyUpdate {
    public static void main(String[] args) {
        System.out.println("=== Test Vertex Property Update (Fixed) ===\n");
        
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
            Object[] createResult = KVTNative.nativeCreateTable("vertex_table", "range");
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
            
            // Create initial vertex data (simplified)
            byte[] vertexKey = "vertex1".getBytes();
            ByteBuffer initialData = ByteBuffer.allocate(100);
            // Write vertex ID
            initialData.put("vertex1".getBytes());
            // Write initial property: name=Alice
            initialData.put((byte) 4); // property name length
            initialData.put("name".getBytes());
            initialData.put((byte) 5); // property value length
            initialData.put("Alice".getBytes());
            // Write initial property: age=30
            initialData.put((byte) 3); // property name length
            initialData.put("age".getBytes());
            initialData.put((byte) 2); // property value length
            initialData.put("30".getBytes());
            
            byte[] initialValue = new byte[initialData.position()];
            initialData.flip();
            initialData.get(initialValue);
            
            // Set initial vertex
            Object[] setResult = KVTNative.nativeSet(txId, tableId, vertexKey, initialValue);
            error = (Integer) setResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to set initial vertex: " + setResult[1]);
            }
            System.out.println("✓ Set initial vertex with name=Alice, age=30");
            
            // Update age property to 31
            ByteBuffer updateData = ByteBuffer.allocate(20);
            updateData.put((byte) 3); // property name length
            updateData.put("age".getBytes());
            updateData.put((byte) 2); // property value length
            updateData.put("31".getBytes());
            
            byte[] propertyUpdate = new byte[updateData.position()];
            updateData.flip();
            updateData.get(propertyUpdate);
            
            // Update vertex property
            Object[] updateResult = KVTNative.nativeVertexPropertyUpdate(
                txId, tableId, vertexKey, propertyUpdate);
            error = (Integer) updateResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to update property: " + updateResult[2]);
            }
            System.out.println("✓ Updated vertex property age to 31");
            
            // Get the updated vertex
            Object[] getResult = KVTNative.nativeGet(txId, tableId, vertexKey);
            error = (Integer) getResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to get vertex: " + getResult[2]);
            }
            byte[] updatedValue = (byte[]) getResult[1];
            System.out.println("✓ Retrieved updated vertex, size: " + updatedValue.length + " bytes");
            
            // Verification - check if only one age=31 appears
            String valueStr = new String(updatedValue);
            int count30 = countOccurrences(valueStr, "30");
            int count31 = countOccurrences(valueStr, "31");
            
            System.out.println("\nVerification Results:");
            System.out.println("  Occurrences of '30': " + count30);
            System.out.println("  Occurrences of '31': " + count31);
            
            if (count30 == 0 && count31 == 1) {
                System.out.println("✓ SUCCESS: Property properly replaced (no duplicate)");
            } else if (count30 > 0 && count31 > 0) {
                System.out.println("✗ FAILURE: Both old and new values present (append bug still exists)");
            } else {
                System.out.println("? UNEXPECTED: count30=" + count30 + ", count31=" + count31);
            }
            
            // Update age property again to 32
            updateData = ByteBuffer.allocate(20);
            updateData.put((byte) 3); // property name length
            updateData.put("age".getBytes());
            updateData.put((byte) 2); // property value length
            updateData.put("32".getBytes());
            
            propertyUpdate = new byte[updateData.position()];
            updateData.flip();
            updateData.get(propertyUpdate);
            
            // Second update
            updateResult = KVTNative.nativeVertexPropertyUpdate(
                txId, tableId, vertexKey, propertyUpdate);
            error = (Integer) updateResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to update property: " + updateResult[2]);
            }
            System.out.println("\n✓ Updated vertex property age to 32");
            
            // Get the vertex after second update
            getResult = KVTNative.nativeGet(txId, tableId, vertexKey);
            error = (Integer) getResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to get vertex: " + getResult[2]);
            }
            updatedValue = (byte[]) getResult[1];
            
            valueStr = new String(updatedValue);
            count30 = countOccurrences(valueStr, "30");
            count31 = countOccurrences(valueStr, "31");
            int count32 = countOccurrences(valueStr, "32");
            
            System.out.println("\nSecond Update Verification:");
            System.out.println("  Occurrences of '30': " + count30);
            System.out.println("  Occurrences of '31': " + count31);
            System.out.println("  Occurrences of '32': " + count32);
            
            if (count30 == 0 && count31 == 0 && count32 == 1) {
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