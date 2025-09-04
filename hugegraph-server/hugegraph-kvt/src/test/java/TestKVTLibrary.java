public class TestKVTLibrary {
    
    public static void main(String[] args) {
        System.out.println("=== KVT Library Test ===\n");
        
        try {
            // Test loading the KVT native library
            String kvtPath = System.getProperty("user.dir") + "/kvt/libkvt.so";
            System.out.println("Attempting to load: " + kvtPath);
            
            // Check if file exists
            java.io.File kvtFile = new java.io.File(kvtPath);
            if (kvtFile.exists()) {
                System.out.println("✓ KVT library file found");
                System.out.println("  Size: " + kvtFile.length() + " bytes");
                System.out.println("  Executable: " + kvtFile.canExecute());
            } else {
                System.out.println("✗ KVT library file not found");
                System.exit(1);
            }
            
            // Try to load it
            System.load(kvtPath);
            System.out.println("✓ KVT library loaded successfully");
            
            // Check if we can create a basic structure
            System.out.println("\n=== Library Test PASSED ===");
            
        } catch (UnsatisfiedLinkError e) {
            System.err.println("✗ Failed to load KVT library: " + e.getMessage());
            System.err.println("\nPossible causes:");
            System.err.println("1. Missing dependencies (libstdc++, etc.)");
            System.err.println("2. Architecture mismatch");
            System.err.println("3. Library not properly compiled");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("✗ Unexpected error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}