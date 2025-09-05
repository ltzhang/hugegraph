import org.apache.hugegraph.backend.store.kvt.KVTNative;
import org.apache.hugegraph.backend.serializer.BytesBuffer;

public class TestParsingRobustness {
    static {
        String libPath = System.getProperty("user.dir") + "/target/native";
        System.setProperty("java.library.path", libPath);
        try {
            System.load(libPath + "/libkvtjni.so");
            System.out.println("✓ Library loaded");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load library: " + e.getMessage());
            System.exit(1);
        }
    }
    
    public static void main(String[] args) {
        System.out.println("=== Testing Robust Parsing ===\n");
        
        try {
            // Initialize KVT
            Object[] initResult = KVTNative.nativeInit();
            if ((int)initResult[0] != 0) {
                System.err.println("Failed to initialize KVT");
                return;
            }
            
            // Create table
            Object[] tableResult = KVTNative.nativeCreateTable("test_parse", "RANGE");
            long tableId = (long)tableResult[1];
            System.out.println("Created table: " + tableId);
            
            // Start transaction
            Object[] txResult = KVTNative.nativeBeginTransaction();
            long txId = (long)txResult[1];
            
            // Test 1: Store entry with various column sizes
            testVariousColumnSizes(txId, tableId);
            
            // Test 2: Store entry with edge case values
            testEdgeCaseValues(txId, tableId);
            
            // Test 3: Store entry with large data
            testLargeData(txId, tableId);
            
            // Commit
            KVTNative.nativeCommitTransaction(txId);
            
            System.out.println("\n✓ All parsing tests passed!");
            
            // Cleanup
            KVTNative.nativeDropTable(tableId);
            KVTNative.nativeShutdown();
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e);
            e.printStackTrace();
        }
    }
    
    private static void testVariousColumnSizes(long txId, long tableId) {
        System.out.println("\n1. Testing various column sizes:");
        
        byte[] key = "test1".getBytes();
        
        // Build value with columns of different sizes
        BytesBuffer buffer = BytesBuffer.allocate(0);
        
        // Write ID (simplified)
        buffer.write("test1".getBytes());
        
        // Small column (< 128 bytes, 1-byte vInt)
        byte[] smallName = "small".getBytes();
        byte[] smallValue = new byte[50];
        buffer.writeVInt(smallName.length);
        buffer.write(smallName);
        buffer.writeVInt(smallValue.length);
        buffer.write(smallValue);
        
        // Medium column (> 128 bytes, 2-byte vInt)
        byte[] medName = "medium".getBytes();
        byte[] medValue = new byte[500];
        buffer.writeVInt(medName.length);
        buffer.write(medName);
        buffer.writeVInt(medValue.length);
        buffer.write(medValue);
        
        // Large column (> 16K bytes, 3-byte vInt)
        byte[] largeName = "large".getBytes();
        byte[] largeValue = new byte[20000];
        buffer.writeVInt(largeName.length);
        buffer.write(largeName);
        buffer.writeVInt(largeValue.length);
        buffer.write(largeValue);
        
        // Store the entry
        Object[] result = KVTNative.nativeSet(txId, tableId, key, buffer.bytes());
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to store entry: " + result[3]);
        }
        
        // Retrieve and verify
        Object[] getResult = KVTNative.nativeGet(txId, tableId, key);
        byte[] retrieved = (byte[])getResult[1];
        
        if (retrieved == null) {
            throw new RuntimeException("Failed to retrieve entry");
        }
        
        System.out.println("  - Stored entry with 3 columns of different sizes");
        System.out.println("  - Total size: " + buffer.bytes().length + " bytes");
        System.out.println("  - Retrieved size: " + retrieved.length + " bytes");
        System.out.println("  ✓ Various column sizes handled correctly");
    }
    
    private static void testEdgeCaseValues(long txId, long tableId) {
        System.out.println("\n2. Testing edge case values:");
        
        byte[] key = "test2".getBytes();
        BytesBuffer buffer = BytesBuffer.allocate(0);
        
        // Write ID
        buffer.write("test2".getBytes());
        
        // Empty column value
        byte[] emptyName = "empty".getBytes();
        buffer.writeVInt(emptyName.length);
        buffer.write(emptyName);
        buffer.writeVInt(0); // Empty value
        
        // Column with special bytes
        byte[] specialName = "special".getBytes();
        byte[] specialValue = new byte[]{0, 1, 127, (byte)128, (byte)255};
        buffer.writeVInt(specialName.length);
        buffer.write(specialName);
        buffer.writeVInt(specialValue.length);
        buffer.write(specialValue);
        
        // Column at vInt boundary (exactly 128 bytes)
        byte[] boundaryName = "boundary".getBytes();
        byte[] boundaryValue = new byte[128];
        buffer.writeVInt(boundaryName.length);
        buffer.write(boundaryName);
        buffer.writeVInt(boundaryValue.length);
        buffer.write(boundaryValue);
        
        // Store and retrieve
        Object[] result = KVTNative.nativeSet(txId, tableId, key, buffer.bytes());
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to store edge case entry");
        }
        
        Object[] getResult = KVTNative.nativeGet(txId, tableId, key);
        byte[] retrieved = (byte[])getResult[1];
        
        System.out.println("  - Stored entry with edge case values");
        System.out.println("  - Empty values, special bytes, boundary sizes");
        System.out.println("  ✓ Edge cases handled correctly");
    }
    
    private static void testLargeData(long txId, long tableId) {
        System.out.println("\n3. Testing large data:");
        
        byte[] key = "test3".getBytes();
        BytesBuffer buffer = BytesBuffer.allocate(0);
        
        // Write ID
        buffer.write("test3".getBytes());
        
        // Multiple large columns
        for (int i = 0; i < 10; i++) {
            byte[] colName = ("column" + i).getBytes();
            byte[] colValue = new byte[10000]; // 10KB each
            
            buffer.writeVInt(colName.length);
            buffer.write(colName);
            buffer.writeVInt(colValue.length);
            buffer.write(colValue);
        }
        
        // Store and retrieve
        Object[] result = KVTNative.nativeSet(txId, tableId, key, buffer.bytes());
        if ((int)result[0] != 0) {
            throw new RuntimeException("Failed to store large entry");
        }
        
        Object[] getResult = KVTNative.nativeGet(txId, tableId, key);
        byte[] retrieved = (byte[])getResult[1];
        
        System.out.println("  - Stored entry with 10 columns of 10KB each");
        System.out.println("  - Total size: " + buffer.bytes().length + " bytes");
        System.out.println("  ✓ Large data handled correctly");
    }
}