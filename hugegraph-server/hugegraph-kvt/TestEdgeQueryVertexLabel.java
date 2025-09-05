import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.nio.ByteBuffer;

public class TestEdgeQueryVertexLabel {
    public static void main(String[] args) {
        System.out.println("=== Test Edge Query with Vertex Label Resolution ===\n");
        
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
            
            // Create test tables
            Object[] vertexTableResult = KVTNative.nativeCreateTable("vertex_table", "range");
            Integer error = (Integer) vertexTableResult[0];
            Long vertexTableId = (Long) vertexTableResult[1];
            if (error != 0) {
                throw new RuntimeException("Failed to create vertex table: " + vertexTableResult[2]);
            }
            System.out.println("✓ Created vertex table with ID: " + vertexTableId);
            
            Object[] edgeTableResult = KVTNative.nativeCreateTable("edge_table", "range");
            error = (Integer) edgeTableResult[0];
            Long edgeTableId = (Long) edgeTableResult[1];
            if (error != 0) {
                throw new RuntimeException("Failed to create edge table: " + edgeTableResult[2]);
            }
            System.out.println("✓ Created edge table with ID: " + edgeTableId);
            
            // Start a transaction
            Object[] txResult = KVTNative.nativeStartTransaction();
            error = (Integer) txResult[0];
            Long txId = (Long) txResult[1];
            if (error != 0) {
                throw new RuntimeException("Failed to start transaction: " + txResult[2]);
            }
            System.out.println("✓ Started transaction: " + txId);
            
            // Create vertices with labels
            System.out.println("\nCreating vertices with labels:");
            
            // Vertex 1 - Person label
            byte[] vertex1Key = "v1".getBytes();
            ByteBuffer v1Data = ByteBuffer.allocate(200);
            v1Data.put("v1".getBytes());
            // Add vertex label info
            v1Data.put((byte) 5); // property name length
            v1Data.put("label".getBytes());
            v1Data.put((byte) 6); // property value length
            v1Data.put("person".getBytes());
            // Add name property
            v1Data.put((byte) 4); // property name length
            v1Data.put("name".getBytes());
            v1Data.put((byte) 5); // property value length
            v1Data.put("Alice".getBytes());
            
            byte[] vertex1Value = new byte[v1Data.position()];
            v1Data.flip();
            v1Data.get(vertex1Value);
            
            Object[] v1SetResult = KVTNative.nativeSet(txId, vertexTableId, vertex1Key, vertex1Value);
            error = (Integer) v1SetResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to set vertex 1: " + v1SetResult[1]);
            }
            System.out.println("✓ Created vertex 1 (person: Alice)");
            
            // Vertex 2 - Software label
            byte[] vertex2Key = "v2".getBytes();
            ByteBuffer v2Data = ByteBuffer.allocate(200);
            v2Data.put("v2".getBytes());
            // Add vertex label info
            v2Data.put((byte) 5); // property name length
            v2Data.put("label".getBytes());
            v2Data.put((byte) 8); // property value length
            v2Data.put("software".getBytes());
            // Add name property
            v2Data.put((byte) 4); // property name length
            v2Data.put("name".getBytes());
            v2Data.put((byte) 9); // property value length
            v2Data.put("HugeGraph".getBytes());
            
            byte[] vertex2Value = new byte[v2Data.position()];
            v2Data.flip();
            v2Data.get(vertex2Value);
            
            Object[] v2SetResult = KVTNative.nativeSet(txId, vertexTableId, vertex2Key, vertex2Value);
            error = (Integer) v2SetResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to set vertex 2: " + v2SetResult[1]);
            }
            System.out.println("✓ Created vertex 2 (software: HugeGraph)");
            
            // Create edge between vertices
            System.out.println("\nCreating edge:");
            
            // Edge: person -[created]-> software
            // In HugeGraph, EdgeId format includes vertex labels
            byte[] edgeKey = createEdgeKey("v1", "v2", "created", "OUT");
            
            ByteBuffer edgeData = ByteBuffer.allocate(300);
            // Edge ID information
            edgeData.put("e1".getBytes());
            
            // Add edge label
            edgeData.put((byte) 5); // property name length
            edgeData.put("label".getBytes());
            edgeData.put((byte) 7); // property value length
            edgeData.put("created".getBytes());
            
            // Add source vertex info (including label)
            edgeData.put((byte) 6); // property name length
            edgeData.put("source".getBytes());
            edgeData.put((byte) 2); // property value length
            edgeData.put("v1".getBytes());
            
            edgeData.put((byte) 12); // property name length
            edgeData.put("source_label".getBytes());
            edgeData.put((byte) 6); // property value length
            edgeData.put("person".getBytes());
            
            // Add target vertex info (including label)
            edgeData.put((byte) 6); // property name length
            edgeData.put("target".getBytes());
            edgeData.put((byte) 2); // property value length
            edgeData.put("v2".getBytes());
            
            edgeData.put((byte) 12); // property name length
            edgeData.put("target_label".getBytes());
            edgeData.put((byte) 8); // property value length
            edgeData.put("software".getBytes());
            
            // Add edge property
            edgeData.put((byte) 4); // property name length
            edgeData.put("year".getBytes());
            edgeData.put((byte) 4); // property value length
            edgeData.put("2024".getBytes());
            
            byte[] edgeValue = new byte[edgeData.position()];
            edgeData.flip();
            edgeData.get(edgeValue);
            
            Object[] edgeSetResult = KVTNative.nativeSet(txId, edgeTableId, edgeKey, edgeValue);
            error = (Integer) edgeSetResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to set edge: " + edgeSetResult[1]);
            }
            System.out.println("✓ Created edge: person(v1) -[created]-> software(v2)");
            
            // Query edge and verify vertex labels are accessible
            System.out.println("\nQuerying edge:");
            Object[] getResult = KVTNative.nativeGet(txId, edgeTableId, edgeKey);
            error = (Integer) getResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to get edge: " + getResult[2]);
            }
            
            byte[] retrievedEdge = (byte[]) getResult[1];
            String edgeStr = new String(retrievedEdge);
            System.out.println("✓ Retrieved edge, size: " + retrievedEdge.length + " bytes");
            
            // Check for vertex labels in edge data
            boolean hasSourceLabel = edgeStr.contains("person");
            boolean hasTargetLabel = edgeStr.contains("software");
            boolean hasEdgeLabel = edgeStr.contains("created");
            
            System.out.println("\nVerification Results:");
            System.out.println("  Edge label 'created' present: " + hasEdgeLabel);
            System.out.println("  Source vertex label 'person' present: " + hasSourceLabel);
            System.out.println("  Target vertex label 'software' present: " + hasTargetLabel);
            
            if (hasSourceLabel && hasTargetLabel && hasEdgeLabel) {
                System.out.println("✓ SUCCESS: Vertex labels are properly stored with edge");
            } else {
                System.out.println("✗ FAILURE: Missing vertex label information in edge");
                System.out.println("  This is the root cause of '~undefined' vertex label errors");
            }
            
            // Simulate edge traversal query
            System.out.println("\nSimulating edge traversal query:");
            System.out.println("  Query: Find all edges from person vertices");
            
            // In a real implementation, we'd need to:
            // 1. Filter edges by source vertex label
            // 2. This requires vertex label to be accessible from edge data
            
            if (!hasSourceLabel) {
                System.out.println("✗ ERROR: Cannot filter edges by vertex label");
                System.out.println("  This causes 'Undefined vertex label: ~undefined' error");
                System.out.println("  Solution: Store vertex label info in edge structure");
            } else {
                System.out.println("✓ Can filter edges by source vertex label");
            }
            
            // Commit transaction
            Object[] commitResult = KVTNative.nativeCommitTransaction(txId);
            error = (Integer) commitResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to commit: " + commitResult[1]);
            }
            System.out.println("\n✓ Transaction committed");
            
            // Cleanup
            Object[] dropEdgeResult = KVTNative.nativeDropTable(edgeTableId);
            Object[] dropVertexResult = KVTNative.nativeDropTable(vertexTableId);
            
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
    
    private static byte[] createEdgeKey(String sourceVertex, String targetVertex, 
                                       String edgeLabel, String direction) {
        // Simplified edge key creation
        // In real implementation, this should match HugeGraph's EdgeId format
        String keyStr = sourceVertex + "_" + direction + "_" + 
                       edgeLabel + "_" + targetVertex;
        return keyStr.getBytes();
    }
}