import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.OptionSpace;
import org.apache.hugegraph.backend.store.kvt.KVTStoreProvider;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import java.util.Iterator;
import java.util.HashMap;

public class VertexDebugTest {
    
    public static void main(String[] args) {
        System.out.println("\n========== VERTEX DEBUG TEST ==========\n");
        
        try {
            // Register KVT backend
            org.apache.hugegraph.backend.store.BackendProviderFactory.register(
                "kvt", "org.apache.hugegraph.backend.store.kvt.KVTStoreProvider");
            System.out.println("[DEBUG] KVT backend registered");
            
            // Create configuration
            HashMap<String, Object> config = new HashMap<>();
            config.put("gremlin.graph", "org.apache.hugegraph.HugeFactory");
            config.put("backend", "kvt");
            config.put("serializer", "binary");
            config.put("store", "kvt-test");
            config.put("kvt.data_path", "/tmp/kvt-debug");
            config.put("kvt.memory_mode", "true");
            config.put("vertex.default_label", "vertex");
            
            OptionSpace.register("kvt", "org.apache.hugegraph.backend.store.kvt.KVTStoreProvider");
            HugeConfig hugeConfig = new HugeConfig(config);
            HugeGraph graph = HugeFactory.open(hugeConfig);
            System.out.println("[DEBUG] Graph opened with KVT backend");
            
            // Create schema - single vertex label
            System.out.println("\n--- Creating Schema ---");
            graph.schema()
                 .propertyKey("name")
                 .asText()
                 .ifNotExist()
                 .create();
            
            graph.schema()
                 .vertexLabel("person")
                 .properties("name")
                 .useCustomizeStringId()
                 .ifNotExist()
                 .create();
            
            graph.tx().commit();
            System.out.println("[DEBUG] Schema created and committed");
            
            // Add a single vertex
            System.out.println("\n--- Adding Vertex ---");
            System.out.println("[DEBUG] Adding vertex with ID='v1', name='TestVertex'");
            
            Vertex v = graph.addVertex(
                T.label, "person",
                T.id, "v1",
                "name", "TestVertex"
            );
            
            System.out.println("[DEBUG] Vertex added in memory: " + v);
            System.out.println("[DEBUG] Vertex ID: " + v.id());
            System.out.println("[DEBUG] Vertex Label: " + v.label());
            
            // Commit the transaction
            System.out.println("\n--- Committing Transaction ---");
            graph.tx().commit();
            System.out.println("[DEBUG] Transaction committed");
            
            // Query the vertex back
            System.out.println("\n--- Querying Vertex by ID ---");
            System.out.println("[DEBUG] Querying for vertex with ID='v1'");
            
            Iterator<Vertex> vertices = graph.vertices("v1");
            if (vertices.hasNext()) {
                Vertex retrieved = vertices.next();
                System.out.println("[SUCCESS] Vertex retrieved!");
                System.out.println("[DEBUG] Retrieved ID: " + retrieved.id());
                System.out.println("[DEBUG] Retrieved Label: " + retrieved.label());
                System.out.println("[DEBUG] Retrieved Name: " + retrieved.property("name").value());
            } else {
                System.out.println("[ERROR] Vertex not found!");
            }
            
            // Query all vertices
            System.out.println("\n--- Querying All Vertices ---");
            Iterator<Vertex> allVertices = graph.vertices();
            int count = 0;
            while (allVertices.hasNext()) {
                Vertex v2 = allVertices.next();
                count++;
                System.out.println("[DEBUG] Vertex " + count + ":");
                System.out.println("  - ID: " + v2.id());
                System.out.println("  - Label: " + v2.label());
                try {
                    System.out.println("  - Name: " + v2.property("name").value());
                } catch (Exception e) {
                    System.out.println("  - Name: <error reading property>");
                }
            }
            System.out.println("[DEBUG] Total vertices found: " + count);
            
            // Close the graph
            graph.close();
            System.out.println("\n[DEBUG] Graph closed");
            System.out.println("\n========== TEST COMPLETE ==========\n");
            
        } catch (Exception e) {
            System.err.println("\n[ERROR] Test failed with exception:");
            e.printStackTrace();
        }
    }
}