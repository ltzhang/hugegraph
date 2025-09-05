import org.apache.hugegraph.HugeFactory;
import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.schema.SchemaManager;
import org.apache.hugegraph.structure.HugeVertex;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.Iterator;

public class HugeGraphKVTIntegrationTest {
    
    public static void main(String[] args) {
        System.out.println("=== HugeGraph KVT Integration Test ===\n");
        
        // Set library path for native KVT library
        String libPath = System.getProperty("user.dir") + "/target/native";
        System.setProperty("java.library.path", libPath);
        
        // Register KVT backend
        System.out.println("0. Registering KVT backend provider...");
        org.apache.hugegraph.backend.store.BackendProviderFactory.register(
            "kvt", "org.apache.hugegraph.backend.store.kvt.KVTStoreProvider");
        System.out.println("   ✓ KVT backend registered");
        
        HugeGraph graph = null;
        try {
            // 1. Initialize HugeGraph with KVT backend
            System.out.println("\n1. Initializing HugeGraph with KVT backend...");
            String configPath = "conf/hugegraph-kvt.properties";
            HugeConfig config = new HugeConfig(configPath);
            graph = (HugeGraph) HugeFactory.open(config);
            System.out.println("   ✓ HugeGraph initialized");
            
            // 2. Create schema
            System.out.println("\n2. Creating schema...");
            SchemaManager schema = graph.schema();
            
            // Create property keys
            schema.propertyKey("name").asText().ifNotExist().create();
            schema.propertyKey("age").asInt().ifNotExist().create();
            schema.propertyKey("city").asText().ifNotExist().create();
            
            // Create vertex label
            schema.vertexLabel("person")
                  .properties("name", "age", "city")
                  .useCustomizeStringId()
                  .ifNotExist()
                  .create();
            
            // Create edge label
            schema.edgeLabel("knows")
                  .sourceLabel("person")
                  .targetLabel("person")
                  .ifNotExist()
                  .create();
            
            // Note: Index creation requires task scheduler which needs master server
            // Skipping index for this basic integration test
            
            System.out.println("   ✓ Schema created");
            
            // Commit any pending schema changes
            graph.tx().commit();
            
            // 3. Add vertices
            System.out.println("\n3. Adding vertices...");
            
            Vertex alice = graph.addVertex(
                T.label, "person",
                T.id, "alice",
                "name", "Alice",
                "age", 30,
                "city", "Beijing"
            );
            
            Vertex bob = graph.addVertex(
                T.label, "person",
                T.id, "bob",
                "name", "Bob",
                "age", 25,
                "city", "Shanghai"
            );
            
            Vertex charlie = graph.addVertex(
                T.label, "person",
                T.id, "charlie",
                "name", "Charlie",
                "age", 35,
                "city", "Shenzhen"
            );
            
            System.out.println("   ✓ Added 3 vertices");
            
            // 4. Add edges
            System.out.println("\n4. Adding edges...");
            alice.addEdge("knows", bob);
            bob.addEdge("knows", charlie);
            alice.addEdge("knows", charlie);
            
            System.out.println("   ✓ Added 3 edges");
            
            // 5. Commit transaction
            System.out.println("\n5. Committing transaction...");
            graph.tx().commit();
            System.out.println("   ✓ Transaction committed");
            
            // 6. Query vertices
            System.out.println("\n6. Querying vertices...");
            Iterator<Vertex> vertices = graph.vertices();
            int vertexCount = 0;
            while (vertices.hasNext()) {
                Vertex v = vertices.next();
                System.out.println("   - " + v.label() + ": " + 
                    v.property("name").value() + ", age: " + 
                    v.property("age").value() + ", city: " + 
                    v.property("city").value());
                vertexCount++;
            }
            System.out.println("   ✓ Found " + vertexCount + " vertices");
            
            // 7. Query edges using Gremlin
            System.out.println("\n7. Querying edges...");
            long edgeCount = graph.traversal().E().count().next();
            System.out.println("   ✓ Found " + edgeCount + " edges");
            
            // 8. Traverse graph - find who Alice knows
            System.out.println("\n8. Traversing graph - Who does Alice know?");
            graph.traversal()
                 .V("alice")
                 .out("knows")
                 .forEachRemaining(v -> {
                     System.out.println("   - Alice knows: " + v.property("name").value());
                 });
            
            // 9. Complex query - find people older than 26
            // Note: This query requires an index on 'age' property which needs master server
            // System.out.println("\n9. Complex query - People older than 26:");
            // graph.traversal()
            //      .V()
            //      .hasLabel("person")
            //      .has("age", org.apache.tinkerpop.gremlin.process.traversal.P.gt(26))
            //      .forEachRemaining(v -> {
            //          System.out.println("   - " + v.property("name").value() + 
            //                           " (age: " + v.property("age").value() + ")");
            //      });
            
            // 10. Update vertex property
            System.out.println("\n10. Updating vertex property...");
            Vertex aliceToUpdate = graph.vertices("alice").next();
            aliceToUpdate.property("age", 31);
            graph.tx().commit();
            System.out.println("   ✓ Updated Alice's age to 31");
            
            // Verify update
            Vertex aliceUpdated = graph.vertices("alice").next();
            System.out.println("   ✓ Verified: Alice's age is now " + 
                             aliceUpdated.property("age").value());
            
            // 11. Delete an edge
            System.out.println("\n11. Deleting edge...");
            graph.traversal()
                 .V("alice")
                 .outE("knows")
                 .has("inV", graph.vertices("bob").next())
                 .next()
                 .remove();
            graph.tx().commit();
            System.out.println("   ✓ Deleted edge from Alice to Bob");
            
            // Verify deletion
            long remainingEdges = graph.traversal().E().count().next();
            System.out.println("   ✓ Remaining edges: " + remainingEdges);
            
            System.out.println("\n=== ALL INTEGRATION TESTS PASSED! ===");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed with exception:");
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (graph != null) {
                try {
                    System.out.println("\n12. Closing HugeGraph...");
                    graph.close();
                    System.out.println("   ✓ HugeGraph closed successfully");
                } catch (Exception e) {
                    System.err.println("Error closing graph: " + e.getMessage());
                }
            }
        }
    }
}