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
import java.util.Arrays;

import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.backend.id.EdgeId;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.E;
import org.apache.hugegraph.util.NumericUtil;

/**
 * Utility class for ID serialization in KVT backend.
 * Handles conversion between HugeGraph IDs and byte arrays.
 */
public class KVTIdUtil {

    // Type prefixes for different ID types
    private static final byte PREFIX_VERTEX = 0x01;
    private static final byte PREFIX_EDGE_OUT = 0x02;
    private static final byte PREFIX_EDGE_IN = 0x03;
    private static final byte PREFIX_PROPERTY_KEY = 0x04;
    private static final byte PREFIX_VERTEX_LABEL = 0x05;
    private static final byte PREFIX_EDGE_LABEL = 0x06;
    private static final byte PREFIX_INDEX_LABEL = 0x07;
    private static final byte PREFIX_SECONDARY_INDEX = 0x08;
    private static final byte PREFIX_RANGE_INDEX = 0x09;
    private static final byte PREFIX_SEARCH_INDEX = 0x0A;
    private static final byte PREFIX_UNIQUE_INDEX = 0x0B;
    private static final byte PREFIX_SHARD_INDEX = 0x0C;
    
    /**
     * Convert an ID to bytes with appropriate type prefix
     */
    public static byte[] idToBytes(HugeType type, Id id) {
        E.checkNotNull(type, "type");
        E.checkNotNull(id, "id");
        
        byte prefix = typePrefixByte(type);
        byte[] idBytes = id.asBytes();
        
        // Create result with prefix + id bytes
        byte[] result = new byte[1 + idBytes.length];
        result[0] = prefix;
        System.arraycopy(idBytes, 0, result, 1, idBytes.length);
        
        return result;
    }
    
    /**
     * Convert bytes back to ID, extracting type prefix
     */
    public static Id bytesToId(byte[] bytes) {
        E.checkNotNull(bytes, "bytes");
        E.checkArgument(bytes.length > 0, "Empty byte array");
        
        // Skip the type prefix and create ID from remaining bytes
        byte[] idBytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return IdGenerator.of(idBytes);
    }
    
    /**
     * Extract HugeType from byte array prefix
     */
    public static HugeType extractType(byte[] bytes) {
        E.checkNotNull(bytes, "bytes");
        E.checkArgument(bytes.length > 0, "Empty byte array");
        
        byte prefix = bytes[0];
        return prefixToType(prefix);
    }
    
    /**
     * Serialize an EdgeId to bytes
     */
    public static byte[] edgeIdToBytes(EdgeId edgeId) {
        E.checkNotNull(edgeId, "edgeId");
        
        // EdgeId contains: ownerVertexId, direction, labelId, sortKeys, otherVertexId
        ByteBuffer buffer = ByteBuffer.allocate(1024); // Initial size
        
        // Write owner vertex ID
        byte[] ownerBytes = edgeId.ownerVertexId().asBytes();
        buffer.putInt(ownerBytes.length);
        buffer.put(ownerBytes);
        
        // Write direction (1 byte)
        buffer.put((byte) edgeId.direction().code());
        
        // Write label ID
        byte[] labelBytes = edgeId.edgeLabelId().asBytes();
        buffer.putInt(labelBytes.length);
        buffer.put(labelBytes);
        
        // Write sort values
        String sortValues = edgeId.sortValues();
        if (sortValues != null) {
            byte[] sortBytes = sortValues.getBytes();
            buffer.putInt(sortBytes.length);
            buffer.put(sortBytes);
        } else {
            buffer.putInt(0);
        }
        
        // Write other vertex ID
        byte[] otherBytes = edgeId.otherVertexId().asBytes();
        buffer.putInt(otherBytes.length);
        buffer.put(otherBytes);
        
        // Return only the used portion
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
    
    /**
     * Create scan start key for range queries
     */
    public static byte[] scanStartKey(HugeType type, Id startId) {
        if (startId == null) {
            // Return just the type prefix for scanning from beginning
            return new byte[] { typePrefix(type) };
        }
        return idToBytes(type, startId);
    }
    
    /**
     * Create scan end key for range queries
     */
    public static byte[] scanEndKey(HugeType type, Id endId) {
        if (endId == null) {
            // Return next prefix value for scanning to end of type range
            byte prefix = typePrefix(type);
            return new byte[] { (byte)(prefix + 1) };
        }
        return idToBytes(type, endId);
    }
    
    /**
     * Create prefix bytes for scanning all entries of a type
     */
    public static byte[] typePrefix(HugeType type) {
        return new byte[] { typePrefixByte(type) };
    }
    
    /**
     * Get the type prefix byte for a HugeType
     */
    private static byte typePrefixByte(HugeType type) {
        switch (type) {
            case VERTEX:
                return PREFIX_VERTEX;
            case EDGE_OUT:
                return PREFIX_EDGE_OUT;
            case EDGE_IN:
                return PREFIX_EDGE_IN;
            case PROPERTY_KEY:
                return PREFIX_PROPERTY_KEY;
            case VERTEX_LABEL:
                return PREFIX_VERTEX_LABEL;
            case EDGE_LABEL:
                return PREFIX_EDGE_LABEL;
            case INDEX_LABEL:
                return PREFIX_INDEX_LABEL;
            case SECONDARY_INDEX:
                return PREFIX_SECONDARY_INDEX;
            case RANGE_INDEX:
                return PREFIX_RANGE_INDEX;
            case SEARCH_INDEX:
                return PREFIX_SEARCH_INDEX;
            case UNIQUE_INDEX:
                return PREFIX_UNIQUE_INDEX;
            case SHARD_INDEX:
                return PREFIX_SHARD_INDEX;
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
    }
    
    /**
     * Convert prefix byte back to HugeType
     */
    private static HugeType prefixToType(byte prefix) {
        switch (prefix) {
            case PREFIX_VERTEX:
                return HugeType.VERTEX;
            case PREFIX_EDGE_OUT:
                return HugeType.EDGE_OUT;
            case PREFIX_EDGE_IN:
                return HugeType.EDGE_IN;
            case PREFIX_PROPERTY_KEY:
                return HugeType.PROPERTY_KEY;
            case PREFIX_VERTEX_LABEL:
                return HugeType.VERTEX_LABEL;
            case PREFIX_EDGE_LABEL:
                return HugeType.EDGE_LABEL;
            case PREFIX_INDEX_LABEL:
                return HugeType.INDEX_LABEL;
            case PREFIX_SECONDARY_INDEX:
                return HugeType.SECONDARY_INDEX;
            case PREFIX_RANGE_INDEX:
                return HugeType.RANGE_INDEX;
            case PREFIX_SEARCH_INDEX:
                return HugeType.SEARCH_INDEX;
            case PREFIX_UNIQUE_INDEX:
                return HugeType.UNIQUE_INDEX;
            case PREFIX_SHARD_INDEX:
                return HugeType.SHARD_INDEX;
            default:
                throw new IllegalArgumentException("Unknown prefix: " + prefix);
        }
    }
    
    /**
     * Compare two ID bytes for ordering
     */
    public static int compareIdBytes(byte[] id1, byte[] id2) {
        int minLength = Math.min(id1.length, id2.length);
        
        for (int i = 0; i < minLength; i++) {
            int cmp = Byte.compare(id1[i], id2[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        
        return Integer.compare(id1.length, id2.length);
    }
}