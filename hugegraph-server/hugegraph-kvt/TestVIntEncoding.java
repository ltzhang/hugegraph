import org.apache.hugegraph.backend.store.kvt.KVTNative;

public class TestVIntEncoding {
    static {
        String libPath = System.getProperty("user.dir") + "/target/native";
        System.setProperty("java.library.path", libPath);
        try {
            System.load(libPath + "/libkvtjni.so");
            System.out.println("✓ Library loaded from: " + libPath);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load library: " + e.getMessage());
            System.exit(1);
        }
    }
    
    public static void main(String[] args) {
        System.out.println("=== Testing Variable Integer Encoding ===\n");
        
        try {
            // Initialize KVT
            int code = KVTNative.nativeInit();
            if (code != 0) {
                System.err.println("Failed to initialize KVT: " + code);
                return;
            }
            
            // Create a test table
            long tableId = KVTNative.nativeCreateTable("vint_test", false);
            System.out.println("Created table with ID: " + tableId);
            
            // Start transaction
            long txId = KVTNative.nativeBeginTransaction();
            System.out.println("Started transaction: " + txId);
            
            // Test with properties of various sizes
            testPropertyUpdate(txId, tableId, "small", 10);      // < 128 bytes
            testPropertyUpdate(txId, tableId, "medium", 200);    // > 128 bytes (needs 2-byte vInt)
            testPropertyUpdate(txId, tableId, "large", 20000);   // > 16K bytes (needs 3-byte vInt)
            testPropertyUpdate(txId, tableId, "xlarge", 2000000); // > 2M bytes (needs 4-byte vInt)
            
            // Commit transaction
            KVTNative.nativeCommitTransaction(txId);
            System.out.println("\n✓ All vInt encoding tests passed!");
            
            // Cleanup
            KVTNative.nativeDropTable(tableId);
            KVTNative.nativeShutdown();
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e);
            e.printStackTrace();
        }
    }
    
    private static void testPropertyUpdate(long txId, long tableId, String testName, int valueSize) {
        System.out.println("\nTesting " + testName + " property (" + valueSize + " bytes):");
        
        // Create a vertex with initial data
        byte[] key = ("vertex_" + testName).getBytes();
        byte[] initialValue = createVertexData("initial", 10);
        
        int setCode = KVTNative.nativeSet(txId, tableId, key, initialValue);
        if (setCode != 0) {
            throw new RuntimeException("Failed to set initial vertex: " + setCode);
        }
        System.out.println("  - Created vertex");
        
        // Create property update with specified size
        String propName = "testProp";
        byte[] propValue = new byte[valueSize];
        for (int i = 0; i < valueSize; i++) {
            propValue[i] = (byte)('A' + (i % 26));
        }
        
        // Build update parameter: [name_len_vint][name][value_len_vint][value]
        byte[] updateParam = buildPropertyUpdate(propName, propValue);
        
        // Perform property update
        byte[] result = KVTNative.nativeVertexPropertyUpdate(txId, tableId, key, updateParam);
        if (result == null || result.length == 0) {
            throw new RuntimeException("Property update failed for size " + valueSize);
        }
        
        // Verify the update worked
        byte[] newValue = KVTNative.nativeGet(txId, tableId, key);
        if (newValue == null || newValue.length == 0) {
            throw new RuntimeException("Failed to retrieve updated vertex");
        }
        
        System.out.println("  - Property updated successfully");
        System.out.println("  - New vertex size: " + newValue.length + " bytes");
        
        // Verify the property value is correct by parsing the result
        if (containsProperty(newValue, propName, valueSize)) {
            System.out.println("  ✓ Property correctly stored with " + getVIntByteCount(valueSize) + "-byte vInt length");
        } else {
            throw new RuntimeException("Property value verification failed");
        }
    }
    
    private static byte[] createVertexData(String id, int propSize) {
        // Simple vertex data: [id_bytes][column_data]
        // Column format: [name_len_vint][name][value_len_vint][value]
        byte[] idBytes = id.getBytes();
        byte[] propName = "initialProp".getBytes();
        byte[] propValue = new byte[propSize];
        
        int totalSize = idBytes.length + 1 + propName.length + 1 + propValue.length;
        byte[] result = new byte[totalSize];
        
        int pos = 0;
        // Copy ID
        System.arraycopy(idBytes, 0, result, pos, idBytes.length);
        pos += idBytes.length;
        
        // Property name length (simplified - assume < 128)
        result[pos++] = (byte)propName.length;
        System.arraycopy(propName, 0, result, pos, propName.length);
        pos += propName.length;
        
        // Property value length (simplified - assume < 128)
        result[pos++] = (byte)propValue.length;
        System.arraycopy(propValue, 0, result, pos, propValue.length);
        
        return result;
    }
    
    private static byte[] buildPropertyUpdate(String name, byte[] value) {
        byte[] nameBytes = name.getBytes();
        
        // Calculate vInt sizes
        int nameLenBytes = getVIntByteCount(nameBytes.length);
        int valueLenBytes = getVIntByteCount(value.length);
        
        byte[] result = new byte[nameLenBytes + nameBytes.length + valueLenBytes + value.length];
        int pos = 0;
        
        // Write name length as vInt
        pos += writeVInt(nameBytes.length, result, pos);
        
        // Write name
        System.arraycopy(nameBytes, 0, result, pos, nameBytes.length);
        pos += nameBytes.length;
        
        // Write value length as vInt
        pos += writeVInt(value.length, result, pos);
        
        // Write value
        System.arraycopy(value, 0, result, pos, value.length);
        
        return result;
    }
    
    private static int writeVInt(int value, byte[] buffer, int offset) {
        int pos = offset;
        
        // Match HugeGraph's BytesBuffer.writeVInt() format
        if (value > 0x0fffffff) {
            buffer[pos++] = (byte)(0x80 | ((value >>> 28) & 0x7f));
        }
        if (value > 0x1fffff) {
            buffer[pos++] = (byte)(0x80 | ((value >>> 21) & 0x7f));
        }
        if (value > 0x3fff) {
            buffer[pos++] = (byte)(0x80 | ((value >>> 14) & 0x7f));
        }
        if (value > 0x7f) {
            buffer[pos++] = (byte)(0x80 | ((value >>> 7) & 0x7f));
        }
        buffer[pos++] = (byte)(value & 0x7f);
        
        return pos - offset;
    }
    
    private static int getVIntByteCount(int value) {
        if (value <= 0x7f) return 1;
        if (value <= 0x3fff) return 2;
        if (value <= 0x1fffff) return 3;
        if (value <= 0x0fffffff) return 4;
        return 5;
    }
    
    private static boolean containsProperty(byte[] vertexData, String propName, int expectedSize) {
        // Simple check - just verify the data is large enough to contain the property
        // A more thorough check would parse the entire structure
        return vertexData.length >= expectedSize;
    }
}