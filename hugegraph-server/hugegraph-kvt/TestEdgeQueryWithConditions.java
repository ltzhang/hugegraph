import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class TestEdgeQueryWithConditions {
    public static void main(String[] args) {
        System.out.println("=== Test Edge Query With Vertex Label Conditions ===\n");
        
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
            
            // Create vertices with different labels
            System.out.println("\nCreating vertices:");
            
            // Create Person vertices
            createVertex(txId, vertexTableId, "v1", "person", "Alice");
            createVertex(txId, vertexTableId, "v2", "person", "Bob");
            
            // Create Software vertices  
            createVertex(txId, vertexTableId, "v3", "software", "HugeGraph");
            createVertex(txId, vertexTableId, "v4", "software", "TinkerPop");
            
            // Create Company vertex
            createVertex(txId, vertexTableId, "v5", "company", "Apache");
            
            System.out.println("✓ Created 5 vertices (2 person, 2 software, 1 company)");
            
            // Create edges with vertex label information embedded in the key
            System.out.println("\nCreating edges:");
            
            // Person -> Software edges
            createEdge(txId, edgeTableId, "v1", "person", "v3", "software", "created", "2023");
            createEdge(txId, edgeTableId, "v2", "person", "v3", "software", "created", "2024");
            createEdge(txId, edgeTableId, "v1", "person", "v4", "software", "uses", "2024");
            
            // Person -> Company edges
            createEdge(txId, edgeTableId, "v1", "person", "v5", "company", "worksFor", "2020");
            createEdge(txId, edgeTableId, "v2", "person", "v5", "company", "worksFor", "2021");
            
            // Software -> Company edges
            createEdge(txId, edgeTableId, "v3", "software", "v5", "company", "ownedBy", "2019");
            
            System.out.println("✓ Created 6 edges");
            
            // Now simulate edge queries with vertex label conditions
            System.out.println("\n=== Simulating Edge Queries with Vertex Label Conditions ===");
            
            // Query 1: Find all edges FROM person vertices
            System.out.println("\nQuery 1: Find all edges FROM person vertices");
            List<String> personEdges = scanEdgesBySourceLabel(txId, edgeTableId, "person");
            System.out.println("  Found " + personEdges.size() + " edges from person vertices");
            for (String edge : personEdges) {
                System.out.println("    - " + edge);
            }
            
            // Query 2: Find all edges TO software vertices
            System.out.println("\nQuery 2: Find all edges TO software vertices");
            List<String> softwareEdges = scanEdgesByTargetLabel(txId, edgeTableId, "software");
            System.out.println("  Found " + softwareEdges.size() + " edges to software vertices");
            for (String edge : softwareEdges) {
                System.out.println("    - " + edge);
            }
            
            // Query 3: Find edges from person to software
            System.out.println("\nQuery 3: Find edges from person to software");
            List<String> personToSoftware = scanEdgesByLabels(txId, edgeTableId, "person", "software");
            System.out.println("  Found " + personToSoftware.size() + " edges from person to software");
            for (String edge : personToSoftware) {
                System.out.println("    - " + edge);
            }
            
            // Demonstrate the issue
            System.out.println("\n=== Demonstrating the Vertex Label Resolution Issue ===");
            System.out.println("When HugeGraph tries to optimize edge queries with vertex label conditions:");
            System.out.println("1. It needs to resolve vertex labels from edge data");
            System.out.println("2. If vertex labels are not embedded in edge keys/values, it returns '~undefined'");
            System.out.println("3. Our solution: Embed vertex labels in edge key structure");
            
            // Commit transaction
            Object[] commitResult = KVTNative.nativeCommitTransaction(txId);
            error = (Integer) commitResult[0];
            if (error != 0) {
                throw new RuntimeException("Failed to commit: " + commitResult[1]);
            }
            System.out.println("\n✓ Transaction committed");
            
            // Cleanup
            KVTNative.nativeDropTable(edgeTableId);
            KVTNative.nativeDropTable(vertexTableId);
            KVTNative.nativeShutdown();
            System.out.println("✓ Cleaned up and shut down");
            
            System.out.println("\n=== TEST COMPLETED ===");
            
        } catch (Exception e) {
            System.err.println("✗ Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void createVertex(long txId, long tableId, String vertexId, 
                                    String label, String name) throws Exception {
        byte[] key = vertexId.getBytes();
        
        ByteBuffer data = ByteBuffer.allocate(200);
        data.put(vertexId.getBytes());
        
        // Add vertex label
        data.put((byte) 5);
        data.put("label".getBytes());
        data.put((byte) label.length());
        data.put(label.getBytes());
        
        // Add name property
        data.put((byte) 4);
        data.put("name".getBytes());
        data.put((byte) name.length());
        data.put(name.getBytes());
        
        byte[] value = new byte[data.position()];
        data.flip();
        data.get(value);
        
        Object[] result = KVTNative.nativeSet(txId, tableId, key, value);
        Integer error = (Integer) result[0];
        if (error != 0) {
            throw new RuntimeException("Failed to create vertex " + vertexId + ": " + result[1]);
        }
    }
    
    private static void createEdge(long txId, long tableId, 
                                  String sourceId, String sourceLabel,
                                  String targetId, String targetLabel,
                                  String edgeLabel, String year) throws Exception {
        // Embed vertex labels in the edge key for efficient filtering
        String keyStr = sourceLabel + ":" + sourceId + "_" + 
                       edgeLabel + "_" + 
                       targetLabel + ":" + targetId;
        byte[] key = keyStr.getBytes();
        
        ByteBuffer data = ByteBuffer.allocate(300);
        
        // Edge data
        data.put((sourceId + "_" + targetId).getBytes());
        
        // Edge label
        data.put((byte) 5);
        data.put("label".getBytes());
        data.put((byte) edgeLabel.length());
        data.put(edgeLabel.getBytes());
        
        // Source info
        data.put((byte) 6);
        data.put("source".getBytes());
        data.put((byte) sourceId.length());
        data.put(sourceId.getBytes());
        
        data.put((byte) 12);
        data.put("source_label".getBytes());
        data.put((byte) sourceLabel.length());
        data.put(sourceLabel.getBytes());
        
        // Target info
        data.put((byte) 6);
        data.put("target".getBytes());
        data.put((byte) targetId.length());
        data.put(targetId.getBytes());
        
        data.put((byte) 12);
        data.put("target_label".getBytes());
        data.put((byte) targetLabel.length());
        data.put(targetLabel.getBytes());
        
        // Year property
        data.put((byte) 4);
        data.put("year".getBytes());
        data.put((byte) 4);
        data.put(year.getBytes());
        
        byte[] value = new byte[data.position()];
        data.flip();
        data.get(value);
        
        Object[] result = KVTNative.nativeSet(txId, tableId, key, value);
        Integer error = (Integer) result[0];
        if (error != 0) {
            throw new RuntimeException("Failed to create edge: " + result[1]);
        }
    }
    
    private static List<String> scanEdgesBySourceLabel(long txId, long tableId, String sourceLabel) {
        List<String> results = new ArrayList<>();
        
        // Scan with prefix matching source label
        byte[] prefix = (sourceLabel + ":").getBytes();
        byte[] endKey = new byte[prefix.length];
        System.arraycopy(prefix, 0, endKey, 0, prefix.length - 1);
        endKey[endKey.length - 1] = (byte)(prefix[prefix.length - 1] + 1);
        
        Object[] scanResult = KVTNative.nativeScan(txId, tableId, prefix, endKey, 100);
        Integer error = (Integer) scanResult[0];
        if (error != 0) {
            System.err.println("Scan failed: " + scanResult[3]);
            return results;
        }
        
        byte[][] keys = (byte[][]) scanResult[1];
        for (byte[] key : keys) {
            results.add(new String(key));
        }
        
        return results;
    }
    
    private static List<String> scanEdgesByTargetLabel(long txId, long tableId, String targetLabel) {
        List<String> results = new ArrayList<>();
        
        // Full scan and filter (inefficient but demonstrates the point)
        Object[] scanResult = KVTNative.nativeScan(txId, tableId, null, null, 1000);
        Integer error = (Integer) scanResult[0];
        if (error != 0) {
            System.err.println("Scan failed: " + scanResult[3]);
            return results;
        }
        
        byte[][] keys = (byte[][]) scanResult[1];
        String targetPattern = "_" + targetLabel + ":";
        
        for (byte[] key : keys) {
            String keyStr = new String(key);
            if (keyStr.contains(targetPattern)) {
                results.add(keyStr);
            }
        }
        
        return results;
    }
    
    private static List<String> scanEdgesByLabels(long txId, long tableId, 
                                                 String sourceLabel, String targetLabel) {
        List<String> results = new ArrayList<>();
        
        // Scan with source label prefix and filter by target label
        byte[] prefix = (sourceLabel + ":").getBytes();
        byte[] endKey = new byte[prefix.length];
        System.arraycopy(prefix, 0, endKey, 0, prefix.length - 1);
        endKey[endKey.length - 1] = (byte)(prefix[prefix.length - 1] + 1);
        
        Object[] scanResult = KVTNative.nativeScan(txId, tableId, prefix, endKey, 100);
        Integer error = (Integer) scanResult[0];
        if (error != 0) {
            System.err.println("Scan failed: " + scanResult[3]);
            return results;
        }
        
        byte[][] keys = (byte[][]) scanResult[1];
        String targetPattern = "_" + targetLabel + ":";
        
        for (byte[] key : keys) {
            String keyStr = new String(key);
            if (keyStr.contains(targetPattern)) {
                results.add(keyStr);
            }
        }
        
        return results;
    }
}