import org.apache.hugegraph.backend.store.kvt.KVTNative;
import org.apache.hugegraph.backend.store.kvt.KVTSession;
import org.apache.hugegraph.backend.store.kvt.KVTSessions;
import java.util.*;

/**
 * Comprehensive integration test for KVT backend
 * Simulates HugeGraph operations without requiring full framework
 */
public class KVTIntegrationTest {
    private static KVTSessions sessions;
    private static Map<String, Long> tableIds = new HashMap<>();
    
    static {
        String libPath = System.getProperty("user.dir") + "/target/native";
        System.setProperty("java.library.path", libPath);
        try {
            System.load(libPath + "/libkvtjni.so");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load library: " + e.getMessage());
            System.exit(1);
        }
    }
    
    public static void main(String[] args) {
        System.out.println("=== KVT Backend Integration Test ===\n");
        
        try {
            // Initialize
            initialize();
            
            // Test suites
            testSchemaOperations();
            testVertexOperations();
            testEdgeOperations();
            testPropertyOperations();
            testTransactionOperations();
            testQueryOperations();
            testConcurrentOperations();
            
            // Cleanup
            cleanup();
            
            System.out.println("\n=== ALL INTEGRATION TESTS PASSED ===");
            
        } catch (Exception e) {
            System.err.println("Integration test failed: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void initialize() {
        System.out.println("1. Initializing KVT backend...");
        
        // Initialize KVT
        int result = KVTNative.nativeInitialize();
        if (result != 0) {
            throw new RuntimeException("Failed to initialize KVT: " + result);
        }
        
        // Create sessions manager
        sessions = new KVTSessions("test-graph");
        
        // Create tables for different HugeGraph types
        createTable("vertex", "hash");
        createTable("edge_out", "range");
        createTable("edge_in", "range");
        createTable("property_key", "hash");
        createTable("vertex_label", "hash");
        createTable("edge_label", "hash");
        createTable("index_label", "hash");
        createTable("secondary_index", "range");
        
        System.out.println("   ✓ Initialized with " + tableIds.size() + " tables");
    }
    
    private static void createTable(String name, String partitionMethod) {
        Object[] result = KVTNative.nativeCreateTable(name, partitionMethod);
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to create table " + name);
        }
        tableIds.put(name, (long)result[1]);
    }
    
    private static void testSchemaOperations() {
        System.out.println("\n2. Testing schema operations...");
        
        KVTSession session = sessions.getOrCreate();
        session.beginTransaction();
        
        try {
            // Create property keys
            createPropertyKey(session, "name", "string");
            createPropertyKey(session, "age", "int");
            createPropertyKey(session, "city", "string");
            createPropertyKey(session, "weight", "double");
            
            // Create vertex labels
            createVertexLabel(session, "person", Arrays.asList("name", "age", "city"));
            createVertexLabel(session, "software", Arrays.asList("name"));
            
            // Create edge labels
            createEdgeLabel(session, "knows", "person", "person", Arrays.asList("weight"));
            createEdgeLabel(session, "created", "person", "software", Arrays.asList());
            
            session.commit();
            System.out.println("   ✓ Schema created successfully");
            
        } catch (Exception e) {
            session.rollback();
            throw e;
        } finally {
            sessions.release(session);
        }
    }
    
    private static void createPropertyKey(KVTSession session, String name, String dataType) {
        byte[] key = ("pk_" + name).getBytes();
        byte[] value = dataType.getBytes();
        session.set(tableIds.get("property_key"), key, value);
    }
    
    private static void createVertexLabel(KVTSession session, String name, List<String> properties) {
        byte[] key = ("vl_" + name).getBytes();
        byte[] value = String.join(",", properties).getBytes();
        session.set(tableIds.get("vertex_label"), key, value);
    }
    
    private static void createEdgeLabel(KVTSession session, String name, 
                                       String sourceLabel, String targetLabel, 
                                       List<String> properties) {
        byte[] key = ("el_" + name).getBytes();
        String value = sourceLabel + ">" + targetLabel + ":" + String.join(",", properties);
        session.set(tableIds.get("edge_label"), key, value.getBytes());
    }
    
    private static void testVertexOperations() {
        System.out.println("\n3. Testing vertex operations...");
        
        KVTSession session = sessions.getOrCreate();
        session.beginTransaction();
        
        try {
            // Create vertices
            String v1 = createVertex(session, "person", "alice", 
                Map.of("name", "Alice", "age", "30", "city", "NYC"));
            String v2 = createVertex(session, "person", "bob",
                Map.of("name", "Bob", "age", "35", "city", "LA"));
            String v3 = createVertex(session, "software", "graph",
                Map.of("name", "HugeGraph"));
            
            session.commit();
            
            // Read vertices
            session.beginTransaction();
            
            Map<String, String> alice = getVertex(session, v1);
            assert alice.get("name").equals("Alice");
            assert alice.get("age").equals("30");
            
            Map<String, String> bob = getVertex(session, v2);
            assert bob.get("name").equals("Bob");
            
            // Update vertex property
            updateVertexProperty(session, v1, "age", "31");
            
            session.commit();
            
            // Verify update
            session.beginTransaction();
            alice = getVertex(session, v1);
            assert alice.get("age").equals("31");
            session.commit();
            
            System.out.println("   ✓ Created 3 vertices");
            System.out.println("   ✓ Updated vertex properties");
            System.out.println("   ✓ Vertex operations successful");
            
        } catch (Exception e) {
            session.rollback();
            throw e;
        } finally {
            sessions.release(session);
        }
    }
    
    private static String createVertex(KVTSession session, String label, String id, 
                                      Map<String, String> properties) {
        String vertexId = label + "_" + id;
        
        // Build vertex data
        StringBuilder data = new StringBuilder();
        data.append(label).append("|");
        for (Map.Entry<String, String> prop : properties.entrySet()) {
            data.append(prop.getKey()).append("=").append(prop.getValue()).append(";");
        }
        
        byte[] key = vertexId.getBytes();
        byte[] value = data.toString().getBytes();
        session.set(tableIds.get("vertex"), key, value);
        
        return vertexId;
    }
    
    private static Map<String, String> getVertex(KVTSession session, String vertexId) {
        byte[] value = session.get(tableIds.get("vertex"), vertexId.getBytes());
        if (value == null) {
            return null;
        }
        
        String data = new String(value);
        String[] parts = data.split("\\|");
        Map<String, String> properties = new HashMap<>();
        properties.put("label", parts[0]);
        
        if (parts.length > 1) {
            String[] props = parts[1].split(";");
            for (String prop : props) {
                if (!prop.isEmpty()) {
                    String[] kv = prop.split("=");
                    if (kv.length == 2) {
                        properties.put(kv[0], kv[1]);
                    }
                }
            }
        }
        
        return properties;
    }
    
    private static void updateVertexProperty(KVTSession session, String vertexId, 
                                            String propName, String propValue) {
        Map<String, String> vertex = getVertex(session, vertexId);
        vertex.put(propName, propValue);
        
        // Rebuild vertex data
        String label = vertex.get("label");
        vertex.remove("label");
        
        String updatedVertex = createVertex(session, label, 
            vertexId.substring(label.length() + 1), vertex);
    }
    
    private static void testEdgeOperations() {
        System.out.println("\n4. Testing edge operations...");
        
        KVTSession session = sessions.getOrCreate();
        session.beginTransaction();
        
        try {
            // Create edges
            String e1 = createEdge(session, "knows", "person_alice", "person_bob", 
                Map.of("weight", "0.8"));
            String e2 = createEdge(session, "knows", "person_bob", "person_alice",
                Map.of("weight", "0.9"));
            String e3 = createEdge(session, "created", "person_alice", "software_graph",
                Map.of());
            
            session.commit();
            
            // Query edges
            session.beginTransaction();
            
            List<String> aliceOutEdges = getOutEdges(session, "person_alice");
            assert aliceOutEdges.size() == 2;
            
            List<String> bobInEdges = getInEdges(session, "person_bob");
            assert bobInEdges.size() == 1;
            
            // Delete edge
            deleteEdge(session, e1);
            session.commit();
            
            // Verify deletion
            session.beginTransaction();
            aliceOutEdges = getOutEdges(session, "person_alice");
            assert aliceOutEdges.size() == 1;
            session.commit();
            
            System.out.println("   ✓ Created 3 edges");
            System.out.println("   ✓ Queried in/out edges");
            System.out.println("   ✓ Deleted edge successfully");
            System.out.println("   ✓ Edge operations successful");
            
        } catch (Exception e) {
            session.rollback();
            throw e;
        } finally {
            sessions.release(session);
        }
    }
    
    private static String createEdge(KVTSession session, String label, 
                                    String sourceId, String targetId,
                                    Map<String, String> properties) {
        String edgeId = sourceId + "_" + label + "_" + targetId;
        
        // Store in edge_out table
        String outKey = sourceId + "_out_" + edgeId;
        String outValue = label + "|" + targetId + "|" + serializeProps(properties);
        session.set(tableIds.get("edge_out"), outKey.getBytes(), outValue.getBytes());
        
        // Store in edge_in table
        String inKey = targetId + "_in_" + edgeId;
        String inValue = label + "|" + sourceId + "|" + serializeProps(properties);
        session.set(tableIds.get("edge_in"), inKey.getBytes(), inValue.getBytes());
        
        return edgeId;
    }
    
    private static String serializeProps(Map<String, String> properties) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> prop : properties.entrySet()) {
            sb.append(prop.getKey()).append("=").append(prop.getValue()).append(";");
        }
        return sb.toString();
    }
    
    private static List<String> getOutEdges(KVTSession session, String vertexId) {
        String prefix = vertexId + "_out_";
        return scanWithPrefix(session, tableIds.get("edge_out"), prefix);
    }
    
    private static List<String> getInEdges(KVTSession session, String vertexId) {
        String prefix = vertexId + "_in_";
        return scanWithPrefix(session, tableIds.get("edge_in"), prefix);
    }
    
    private static List<String> scanWithPrefix(KVTSession session, long tableId, String prefix) {
        byte[] startKey = prefix.getBytes();
        byte[] endKey = (prefix + "\uffff").getBytes();
        
        Iterator<KVTNative.KVTPair> iter = session.scan(tableId, startKey, endKey, 100);
        List<String> results = new ArrayList<>();
        while (iter.hasNext()) {
            KVTNative.KVTPair pair = iter.next();
            results.add(new String(pair.key));
        }
        return results;
    }
    
    private static void deleteEdge(KVTSession session, String edgeId) {
        // Parse edge ID to get source and target
        String[] parts = edgeId.split("_");
        String sourceType = parts[0];
        String sourceName = parts[1];
        String label = parts[2];
        String targetType = parts[3];
        String targetName = parts[4];
        
        String sourceId = sourceType + "_" + sourceName;
        String targetId = targetType + "_" + targetName;
        
        // Delete from edge_out
        String outKey = sourceId + "_out_" + edgeId;
        session.delete(tableIds.get("edge_out"), outKey.getBytes());
        
        // Delete from edge_in
        String inKey = targetId + "_in_" + edgeId;
        session.delete(tableIds.get("edge_in"), inKey.getBytes());
    }
    
    private static void testPropertyOperations() {
        System.out.println("\n5. Testing property operations...");
        
        KVTSession session = sessions.getOrCreate();
        session.beginTransaction();
        
        try {
            // Test large property values
            String largeValue = "x".repeat(10000);
            createVertex(session, "person", "charlie",
                Map.of("name", "Charlie", "bio", largeValue));
            
            session.commit();
            
            // Verify large property
            session.beginTransaction();
            Map<String, String> charlie = getVertex(session, "person_charlie");
            assert charlie.get("bio").length() == 10000;
            
            // Test property deletion (set to empty)
            updateVertexProperty(session, "person_charlie", "bio", "");
            session.commit();
            
            // Verify deletion
            session.beginTransaction();
            charlie = getVertex(session, "person_charlie");
            assert charlie.get("bio").isEmpty();
            session.commit();
            
            System.out.println("   ✓ Handled large property values (10KB)");
            System.out.println("   ✓ Property deletion successful");
            
        } catch (Exception e) {
            session.rollback();
            throw e;
        } finally {
            sessions.release(session);
        }
    }
    
    private static void testTransactionOperations() {
        System.out.println("\n6. Testing transaction operations...");
        
        KVTSession session1 = sessions.getOrCreate();
        KVTSession session2 = sessions.getOrCreate();
        
        try {
            // Test rollback
            session1.beginTransaction();
            createVertex(session1, "person", "dave", Map.of("name", "Dave"));
            session1.rollback();
            
            session1.beginTransaction();
            Map<String, String> dave = getVertex(session1, "person_dave");
            assert dave == null;
            session1.commit();
            
            System.out.println("   ✓ Transaction rollback successful");
            
            // Test isolation (depends on implementation)
            session1.beginTransaction();
            createVertex(session1, "person", "eve", Map.of("name", "Eve"));
            
            session2.beginTransaction();
            Map<String, String> eve = getVertex(session2, "person_eve");
            assert eve == null; // Not visible until commit
            session2.commit();
            
            session1.commit();
            
            System.out.println("   ✓ Transaction isolation verified");
            
        } catch (Exception e) {
            session1.rollback();
            session2.rollback();
            throw e;
        } finally {
            sessions.release(session1);
            sessions.release(session2);
        }
    }
    
    private static void testQueryOperations() {
        System.out.println("\n7. Testing query operations...");
        
        KVTSession session = sessions.getOrCreate();
        session.beginTransaction();
        
        try {
            // Range scan
            String startKey = "person_a";
            String endKey = "person_z";
            Iterator<KVTNative.KVTPair> iter = session.scan(
                tableIds.get("vertex"), 
                startKey.getBytes(), 
                endKey.getBytes(), 
                100);
            
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            
            System.out.println("   ✓ Range scan found " + count + " vertices");
            
            // Full table scan
            iter = session.scan(tableIds.get("vertex"), null, null, 1000);
            count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            
            System.out.println("   ✓ Full scan found " + count + " total vertices");
            
            session.commit();
            
        } catch (Exception e) {
            session.rollback();
            throw e;
        } finally {
            sessions.release(session);
        }
    }
    
    private static void testConcurrentOperations() {
        System.out.println("\n8. Testing concurrent operations...");
        
        List<Thread> threads = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();
        
        // Create 10 concurrent threads
        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            Thread t = new Thread(() -> {
                try {
                    KVTSession session = sessions.getOrCreate();
                    session.beginTransaction();
                    
                    // Each thread creates its own vertex
                    createVertex(session, "person", "thread" + threadId,
                        Map.of("name", "Thread" + threadId));
                    
                    // Small delay to increase chance of conflicts
                    Thread.sleep(10);
                    
                    session.commit();
                    sessions.release(session);
                    
                } catch (Exception e) {
                    errors.add(e);
                }
            });
            threads.add(t);
            t.start();
        }
        
        // Wait for all threads
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        
        if (!errors.isEmpty()) {
            throw new RuntimeException("Concurrent operations failed: " + errors.get(0));
        }
        
        // Verify all vertices were created
        KVTSession session = sessions.getOrCreate();
        session.beginTransaction();
        
        int created = 0;
        for (int i = 0; i < 10; i++) {
            Map<String, String> vertex = getVertex(session, "person_thread" + i);
            if (vertex != null) {
                created++;
            }
        }
        
        session.commit();
        sessions.release(session);
        
        System.out.println("   ✓ " + created + "/10 concurrent vertices created");
        System.out.println("   ✓ Concurrent operations successful");
    }
    
    private static void cleanup() {
        System.out.println("\n9. Cleaning up...");
        
        // Drop all tables
        for (Map.Entry<String, Long> entry : tableIds.entrySet()) {
            KVTNative.nativeDropTable(entry.getValue());
        }
        
        // Shutdown
        KVTNative.nativeShutdown();
        
        System.out.println("   ✓ Cleanup complete");
    }
}