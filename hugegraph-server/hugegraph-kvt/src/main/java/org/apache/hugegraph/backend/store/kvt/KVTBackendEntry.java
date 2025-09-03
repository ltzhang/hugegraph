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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.serializer.BinaryBackendEntry;
import org.apache.hugegraph.backend.serializer.BytesBuffer;
import org.apache.hugegraph.backend.store.BackendEntry;
import org.apache.hugegraph.type.HugeType;
import org.apache.hugegraph.util.Bytes;

/**
 * KVT backend entry implementation.
 * Represents a single key-value pair in KVT storage.
 */
public class KVTBackendEntry extends BinaryBackendEntry {

    private static final byte[] EMPTY_BYTES = new byte[0];
    
    private byte[] bytesValue;
    
    public KVTBackendEntry(HugeType type, Id id) {
        super(type, id);
        this.bytesValue = null;
    }
    
    public KVTBackendEntry(HugeType type, byte[] bytes) {
        super(type, bytes);
        this.bytesValue = null;
    }
    
    /**
     * Get the serialized columns as bytes
     */
    public byte[] columnsBytes() {
        if (this.bytesValue == null) {
            // Serialize columns to bytes
            this.bytesValue = this.serializeColumns();
        }
        return this.bytesValue;
    }
    
    /**
     * Set the serialized columns bytes
     */
    public void columnsBytes(byte[] bytes) {
        this.bytesValue = bytes;
        // Clear columns so they'll be deserialized on demand
        this.columns.clear();
    }
    
    /**
     * Serialize columns to bytes
     */
    private byte[] serializeColumns() {
        if (this.columns.isEmpty()) {
            return EMPTY_BYTES;
        }
        
        // Calculate total size
        int totalSize = 4;  // 4 bytes for column count
        for (BackendColumn column : this.columns) {
            totalSize += 4 + column.name.length;   // 4 bytes for name length + name
            totalSize += 4 + column.value.length;  // 4 bytes for value length + value
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        
        // Write column count
        buffer.putInt(this.columns.size());
        
        // Write each column
        for (BackendColumn column : this.columns) {
            // Write name
            buffer.putInt(column.name.length);
            buffer.put(column.name);
            
            // Write value
            buffer.putInt(column.value.length);
            buffer.put(column.value);
        }
        
        return buffer.array();
    }
    
    /**
     * Deserialize columns from bytes
     */
    private void deserializeColumns() {
        if (this.bytesValue == null || this.bytesValue.length == 0) {
            return;
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(this.bytesValue);
        
        // Read column count
        int columnCount = buffer.getInt();
        
        // Read each column
        for (int i = 0; i < columnCount; i++) {
            // Read name
            int nameLen = buffer.getInt();
            byte[] name = new byte[nameLen];
            buffer.get(name);
            
            // Read value
            int valueLen = buffer.getInt();
            byte[] value = new byte[valueLen];
            buffer.get(value);
            
            this.columns.add(BackendColumn.of(name, value));
        }
    }
    
    @Override
    public Collection<BackendColumn> columns() {
        if (this.columns.isEmpty() && this.bytesValue != null) {
            // Deserialize columns from bytes on demand
            this.deserializeColumns();
        }
        return this.columns;
    }
    
    @Override
    public void merge(BackendEntry other) {
        // Ensure both entries have their columns loaded
        this.columns();  // Trigger deserialization if needed
        super.merge(other);
        // Clear cached bytes since columns have changed
        this.bytesValue = null;
    }
    
    @Override
    public void clear() {
        super.clear();
        this.bytesValue = null;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof KVTBackendEntry)) {
            return false;
        }
        
        KVTBackendEntry other = (KVTBackendEntry) obj;
        if (!this.type().equals(other.type()) || 
            !this.id().equals(other.id())) {
            return false;
        }
        
        // Compare serialized bytes for efficiency
        byte[] thisBytes = this.columnsBytes();
        byte[] otherBytes = other.columnsBytes();
        return Bytes.equals(thisBytes, otherBytes);
    }
    
    @Override
    public String toString() {
        return String.format("KVTBackendEntry{type=%s, id=%s, columns=%s}",
                           this.type(), this.id(), 
                           this.columns.isEmpty() ? 
                           (this.bytesValue == null ? "null" : 
                            this.bytesValue.length + " bytes") : 
                           this.columns.size());
    }
}