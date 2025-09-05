import java.util.ServiceLoader;
import org.apache.hugegraph.backend.store.BackendStoreProvider;

public class TestProviderLoading {
    public static void main(String[] args) {
        System.out.println("Testing BackendStoreProvider loading...\n");
        
        ServiceLoader<BackendStoreProvider> loader = ServiceLoader.load(BackendStoreProvider.class);
        
        int count = 0;
        for (BackendStoreProvider provider : loader) {
            System.out.println("Found provider: " + provider.getClass().getName());
            System.out.println("  Type: " + provider.type());
            System.out.println("  Version: " + provider.driverVersion());
            count++;
        }
        
        System.out.println("\nTotal providers found: " + count);
        
        if (count == 0) {
            System.err.println("ERROR: No providers found!");
            System.err.println("Check that META-INF/services/org.apache.hugegraph.backend.store.BackendStoreProvider exists");
        }
    }
}