import org.apache.hugegraph.backend.store.kvt.*;
import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.id.EdgeId;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.type.define.DataType;
import org.apache.hugegraph.type.define.Directions;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

/**
 * Test program for KVT serialization and data model mapping
 */
public class TestKVTSerialization {

    public static void main(String[] args) {
        System.out.println("=== KVT Serialization Tests ===\n");
        
        boolean allTestsPassed = true;
        
        try {
            // Test ID serialization
            System.out.println("1. Testing ID Serialization...");
            testIdSerialization();
            System.out.println("   ✓ ID serialization tests passed");
            
            // Test data type serialization
            System.out.println("\n2. Testing Data Type Serialization...");
            testDataTypeSerialization();
            System.out.println("   ✓ Data type serialization tests passed");
            
            // Test scan range extraction
            System.out.println("\n3. Testing Scan Range Extraction...");
            testScanRanges();
            System.out.println("   ✓ Scan range tests passed");
            
            // Test backend entry serialization
            System.out.println("\n4. Testing Backend Entry Serialization...");
            testBackendEntrySerialization();
            System.out.println("   ✓ Backend entry tests passed");
            
            System.out.println("\n=== ALL SERIALIZATION TESTS PASSED! ===");
            
        } catch (Exception e) {
            System.err.println("\n=== TEST FAILED ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void testIdSerialization() {
        // Test vertex ID serialization
        Id vertexId = IdGenerator.of(123);
        byte[] vertexBytes = KVTIdUtil.idToBytes(HugeType.VERTEX, vertexId);
        
        // Verify prefix is correct
        assert vertexBytes[0] == 0x01 : "Vertex prefix should be 0x01";
        
        // Test round-trip conversion
        Id recoveredId = KVTIdUtil.bytesToId(vertexBytes);
        assert vertexId.equals(recoveredId) : "ID round-trip failed";
        
        // Test type extraction
        HugeType extractedType = KVTIdUtil.extractType(vertexBytes);
        assert extractedType == HugeType.VERTEX : "Type extraction failed";
        
        // Test edge ID serialization
        Id sourceId = IdGenerator.of(100);
        Id targetId = IdGenerator.of(200);
        Id labelId = IdGenerator.of(1);
        // EdgeId constructor needs: ownerVertexId, direction, edgeLabelId, subLabelId, sortValues, otherVertexId
        EdgeId edgeId = new EdgeId(sourceId, Directions.OUT, labelId, labelId, "", targetId);
        
        byte[] edgeBytes = KVTIdUtil.edgeIdToBytes(edgeId);
        assert edgeBytes.length > 0 : "Edge ID serialization failed";
        
        // Test scan key generation
        byte[] startKey = KVTIdUtil.scanStartKey(HugeType.VERTEX, null);
        byte[] endKey = KVTIdUtil.scanEndKey(HugeType.VERTEX, null);
        
        assert startKey[0] == 0x01 : "Start key prefix incorrect";
        assert endKey[0] == 0x02 : "End key should be next prefix";
    }
    
    private static void testDataTypeSerialization() {
        // Test boolean
        byte[] boolTrue = KVTSerializer.serialize(true, DataType.BOOLEAN);
        byte[] boolFalse = KVTSerializer.serialize(false, DataType.BOOLEAN);
        assert (Boolean)KVTSerializer.deserialize(boolTrue) == true;
        assert (Boolean)KVTSerializer.deserialize(boolFalse) == false;
        
        // Test integer
        Integer intValue = 42;
        byte[] intBytes = KVTSerializer.serialize(intValue, DataType.INT);
        Integer recoveredInt = (Integer)KVTSerializer.deserialize(intBytes);
        assert intValue.equals(recoveredInt) : "Integer serialization failed";
        
        // Test long
        Long longValue = 1234567890123L;
        byte[] longBytes = KVTSerializer.serialize(longValue, DataType.LONG);
        Long recoveredLong = (Long)KVTSerializer.deserialize(longBytes);
        assert longValue.equals(recoveredLong) : "Long serialization failed";
        
        // Test double
        Double doubleValue = 3.14159;
        byte[] doubleBytes = KVTSerializer.serialize(doubleValue, DataType.DOUBLE);
        Double recoveredDouble = (Double)KVTSerializer.deserialize(doubleBytes);
        assert Math.abs(doubleValue - recoveredDouble) < 0.00001 : "Double serialization failed";
        
        // Test string
        String stringValue = "Hello, KVT!";
        byte[] stringBytes = KVTSerializer.serialize(stringValue, DataType.TEXT);
        String recoveredString = (String)KVTSerializer.deserialize(stringBytes);
        assert stringValue.equals(recoveredString) : "String serialization failed";
        
        // Test date
        Date dateValue = new Date();
        byte[] dateBytes = KVTSerializer.serialize(dateValue, DataType.DATE);
        Date recoveredDate = (Date)KVTSerializer.deserialize(dateBytes);
        assert dateValue.equals(recoveredDate) : "Date serialization failed";
        
        // Test UUID
        UUID uuidValue = UUID.randomUUID();
        byte[] uuidBytes = KVTSerializer.serialize(uuidValue, DataType.UUID);
        UUID recoveredUuid = (UUID)KVTSerializer.deserialize(uuidBytes);
        assert uuidValue.equals(recoveredUuid) : "UUID serialization failed";
        
        // Test null
        byte[] nullBytes = KVTSerializer.serialize(null, DataType.OBJECT);
        Object recoveredNull = KVTSerializer.deserialize(nullBytes);
        assert recoveredNull == null : "Null serialization failed";
        
        // Test comparison
        byte[] int1 = KVTSerializer.serialize(10, DataType.INT);
        byte[] int2 = KVTSerializer.serialize(20, DataType.INT);
        assert KVTSerializer.compare(int1, int2) < 0 : "Comparison failed";
    }
    
    private static void testScanRanges() {
        // Test full range scan
        byte[] fullStart = KVTIdUtil.scanStartKey(HugeType.VERTEX, null);
        byte[] fullEnd = KVTIdUtil.scanEndKey(HugeType.VERTEX, null);
        
        assert fullStart.length == 1 : "Full scan start should be just prefix";
        assert fullEnd.length == 1 : "Full scan end should be just next prefix";
        assert fullEnd[0] == (byte)(fullStart[0] + 1) : "End should be next prefix";
        
        // Test partial range scan
        Id startId = IdGenerator.of(100);
        Id endId = IdGenerator.of(200);
        
        byte[] partialStart = KVTIdUtil.scanStartKey(HugeType.EDGE_OUT, startId);
        byte[] partialEnd = KVTIdUtil.scanEndKey(HugeType.EDGE_OUT, endId);
        
        assert partialStart[0] == 0x02 : "Edge out prefix should be 0x02";
        assert partialEnd[0] == 0x02 : "Edge out prefix should be maintained";
        
        // Test comparison
        int cmp = KVTIdUtil.compareIdBytes(partialStart, partialEnd);
        assert cmp < 0 : "Start should be less than end";
    }
    
    private static void testBackendEntrySerialization() {
        // Create a backend entry
        Id id = IdGenerator.of(999);
        KVTBackendEntry entry = new KVTBackendEntry(HugeType.VERTEX, id);
        
        // Add columns
        entry.column("name".getBytes(), "Alice".getBytes());
        entry.column("age".getBytes(), "30".getBytes());
        entry.column("city".getBytes(), "New York".getBytes());
        
        // Serialize columns
        byte[] serialized = entry.columnsBytes();
        assert serialized.length > 0 : "Column serialization failed";
        
        // Create new entry and deserialize
        KVTBackendEntry entry2 = new KVTBackendEntry(HugeType.VERTEX, id);
        entry2.columnsBytes(serialized);
        
        // Verify columns were restored
        assert entry2.columns().size() == 3 : "Should have 3 columns";
        
        // Test merge operation
        KVTBackendEntry entry3 = new KVTBackendEntry(HugeType.VERTEX, id);
        entry3.column("country".getBytes(), "USA".getBytes());
        
        entry2.merge(entry3);
        assert entry2.columns().size() == 4 : "Should have 4 columns after merge";
        
        // Test clear operation
        entry2.clear();
        assert entry2.columns().isEmpty() : "Columns should be empty after clear";
    }
}