/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.backend.store.kvt;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import org.apache.hugegraph.backend.serializer.BytesBuffer;
import org.apache.hugegraph.type.define.Cardinality;
import org.apache.hugegraph.type.define.DataType;
import org.apache.hugegraph.util.Bytes;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.KryoUtil;
import org.apache.hugegraph.util.NumericUtil;

/**
 * Serializer for converting HugeGraph data types to/from bytes.
 * Used by KVT backend for storing property values and other data.
 */
public class KVTSerializer {

    // Type markers for serialized data
    private static final byte MARKER_NULL = 0x00;
    private static final byte MARKER_BOOLEAN_FALSE = 0x01;
    private static final byte MARKER_BOOLEAN_TRUE = 0x02;
    private static final byte MARKER_BYTE = 0x03;
    private static final byte MARKER_SHORT = 0x04;
    private static final byte MARKER_INT = 0x05;
    private static final byte MARKER_LONG = 0x06;
    private static final byte MARKER_FLOAT = 0x07;
    private static final byte MARKER_DOUBLE = 0x08;
    private static final byte MARKER_STRING = 0x09;
    private static final byte MARKER_BYTES = 0x0A;
    private static final byte MARKER_DATE = 0x0B;
    private static final byte MARKER_UUID = 0x0C;
    private static final byte MARKER_OBJECT = 0x0D;
    
    /**
     * Serialize a value to bytes based on its data type
     */
    public static byte[] serialize(Object value, DataType dataType) {
        if (value == null) {
            return new byte[] { MARKER_NULL };
        }
        
        switch (dataType) {
            case BOOLEAN:
                return serializeBoolean((Boolean) value);
            case BYTE:
                return serializeByte((Byte) value);
            case INT:
                return serializeInt((Integer) value);
            case LONG:
                return serializeLong((Long) value);
            case FLOAT:
                return serializeFloat((Float) value);
            case DOUBLE:
                return serializeDouble((Double) value);
            case TEXT:
            case STRING:
                return serializeString((String) value);
            case BLOB:
                return serializeBytes((byte[]) value);
            case DATE:
                return serializeDate((Date) value);
            case UUID:
                return serializeUuid((UUID) value);
            case OBJECT:
                return serializeObject(value);
            default:
                throw new IllegalArgumentException("Unsupported data type: " + dataType);
        }
    }
    
    /**
     * Deserialize bytes to a value
     */
    public static Object deserialize(byte[] bytes) {
        E.checkNotNull(bytes, "bytes");
        E.checkArgument(bytes.length > 0, "Empty byte array");
        
        byte marker = bytes[0];
        
        switch (marker) {
            case MARKER_NULL:
                return null;
            case MARKER_BOOLEAN_FALSE:
                return false;
            case MARKER_BOOLEAN_TRUE:
                return true;
            case MARKER_BYTE:
                return deserializeByte(bytes);
            case MARKER_SHORT:
                return deserializeShort(bytes);
            case MARKER_INT:
                return deserializeInt(bytes);
            case MARKER_LONG:
                return deserializeLong(bytes);
            case MARKER_FLOAT:
                return deserializeFloat(bytes);
            case MARKER_DOUBLE:
                return deserializeDouble(bytes);
            case MARKER_STRING:
                return deserializeString(bytes);
            case MARKER_BYTES:
                return deserializeBytes(bytes);
            case MARKER_DATE:
                return deserializeDate(bytes);
            case MARKER_UUID:
                return deserializeUuid(bytes);
            case MARKER_OBJECT:
                return deserializeObject(bytes);
            default:
                throw new IllegalArgumentException("Unknown marker: " + marker);
        }
    }
    
    private static byte[] serializeBoolean(boolean value) {
        return new byte[] { value ? MARKER_BOOLEAN_TRUE : MARKER_BOOLEAN_FALSE };
    }
    
    private static byte[] serializeByte(byte value) {
        return new byte[] { MARKER_BYTE, value };
    }
    
    private static byte[] serializeInt(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(5);
        buffer.put(MARKER_INT);
        buffer.putInt(value);
        return buffer.array();
    }
    
    private static byte[] serializeLong(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.put(MARKER_LONG);
        buffer.putLong(value);
        return buffer.array();
    }
    
    private static byte[] serializeFloat(float value) {
        ByteBuffer buffer = ByteBuffer.allocate(5);
        buffer.put(MARKER_FLOAT);
        buffer.putFloat(value);
        return buffer.array();
    }
    
    private static byte[] serializeDouble(double value) {
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.put(MARKER_DOUBLE);
        buffer.putDouble(value);
        return buffer.array();
    }
    
    private static byte[] serializeString(String value) {
        byte[] stringBytes = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + stringBytes.length);
        buffer.put(MARKER_STRING);
        buffer.putInt(stringBytes.length);
        buffer.put(stringBytes);
        return buffer.array();
    }
    
    private static byte[] serializeBytes(byte[] value) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + value.length);
        buffer.put(MARKER_BYTES);
        buffer.putInt(value.length);
        buffer.put(value);
        return buffer.array();
    }
    
    private static byte[] serializeDate(Date value) {
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.put(MARKER_DATE);
        buffer.putLong(value.getTime());
        return buffer.array();
    }
    
    private static byte[] serializeUuid(UUID value) {
        ByteBuffer buffer = ByteBuffer.allocate(17);
        buffer.put(MARKER_UUID);
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
        return buffer.array();
    }
    
    private static byte[] serializeObject(Object value) {
        // Use Kryo for complex object serialization
        byte[] objectBytes = KryoUtil.toKryo(value);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + objectBytes.length);
        buffer.put(MARKER_OBJECT);
        buffer.putInt(objectBytes.length);
        buffer.put(objectBytes);
        return buffer.array();
    }
    
    private static byte deserializeByte(byte[] bytes) {
        return bytes[1];
    }
    
    private static short deserializeShort(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, 2);
        return buffer.getShort();
    }
    
    private static int deserializeInt(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, 4);
        return buffer.getInt();
    }
    
    private static long deserializeLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, 8);
        return buffer.getLong();
    }
    
    private static float deserializeFloat(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, 4);
        return buffer.getFloat();
    }
    
    private static double deserializeDouble(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, 8);
        return buffer.getDouble();
    }
    
    private static String deserializeString(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, bytes.length - 1);
        int length = buffer.getInt();
        byte[] stringBytes = new byte[length];
        buffer.get(stringBytes);
        return new String(stringBytes, StandardCharsets.UTF_8);
    }
    
    private static byte[] deserializeBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, bytes.length - 1);
        int length = buffer.getInt();
        byte[] result = new byte[length];
        buffer.get(result);
        return result;
    }
    
    private static Date deserializeDate(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, 8);
        long time = buffer.getLong();
        return new Date(time);
    }
    
    private static UUID deserializeUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, 16);
        long mostSig = buffer.getLong();
        long leastSig = buffer.getLong();
        return new UUID(mostSig, leastSig);
    }
    
    private static Object deserializeObject(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, bytes.length - 1);
        int length = buffer.getInt();
        byte[] objectBytes = new byte[length];
        buffer.get(objectBytes);
        return KryoUtil.fromKryo(objectBytes, Object.class);
    }
    
    /**
     * Serialize a property value based on its cardinality
     */
    public static byte[] serializeProperty(Object value, Cardinality cardinality, 
                                          DataType dataType) {
        if (cardinality == Cardinality.SINGLE) {
            return serialize(value, dataType);
        }
        
        // For SET and LIST cardinalities, we need to handle collections
        // This is simplified - actual implementation would need collection handling
        return serialize(value, dataType);
    }
    
    /**
     * Compare two serialized values for ordering
     */
    public static int compare(byte[] value1, byte[] value2) {
        // Extract types
        byte type1 = value1[0];
        byte type2 = value2[0];
        
        // Different types - order by type marker
        if (type1 != type2) {
            return Byte.compare(type1, type2);
        }
        
        // Same type - compare values
        switch (type1) {
            case MARKER_NULL:
                return 0;
            case MARKER_BOOLEAN_FALSE:
            case MARKER_BOOLEAN_TRUE:
                return 0;  // Booleans are equal if same marker
            case MARKER_BYTE:
                return Byte.compare(value1[1], value2[1]);
            case MARKER_INT:
                return Integer.compare(deserializeInt(value1), deserializeInt(value2));
            case MARKER_LONG:
                return Long.compare(deserializeLong(value1), deserializeLong(value2));
            case MARKER_FLOAT:
                return Float.compare(deserializeFloat(value1), deserializeFloat(value2));
            case MARKER_DOUBLE:
                return Double.compare(deserializeDouble(value1), deserializeDouble(value2));
            case MARKER_STRING:
                return deserializeString(value1).compareTo(deserializeString(value2));
            case MARKER_DATE:
                return Long.compare(deserializeLong(value1), deserializeLong(value2));
            default:
                // For complex types, compare bytes directly
                return Bytes.compare(value1, value2);
        }
    }
}