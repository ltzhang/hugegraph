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

import org.apache.hugegraph.backend.store.kvt.KVTNative;
import org.apache.hugegraph.backend.store.kvt.KVTNative.KVTError;
import org.apache.hugegraph.backend.store.kvt.KVTNative.KVTResult;
import org.apache.hugegraph.backend.store.kvt.KVTNative.KVTOp;
import org.apache.hugegraph.backend.store.kvt.KVTNative.KVTOpResult;
import org.apache.hugegraph.backend.store.kvt.KVTNative.OpType;

import java.util.List;
import java.util.ArrayList;

public class TestNewMethods {
    static {
        System.setProperty("java.library.path", "target");
        try {
            System.loadLibrary("kvtjni");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load native library: " + e.getMessage());
            System.exit(1);
        }
    }
    
    public static void main(String[] args) {
        // Initialize KVT
        KVTError error = KVTNative.initialize();
        if (error != KVTError.SUCCESS) {
            System.err.println("Failed to initialize KVT: " + error);
            System.exit(1);
        }
        
        System.out.println("KVT initialized successfully");
        
        // Create a test table
        KVTResult<Long> createResult = KVTNative.createTable("test_table", "hash");
        if (!createResult.isSuccess()) {
            System.err.println("Failed to create table: " + createResult.error + " - " + createResult.errorMessage);
            System.exit(1);
        }
        long tableId = createResult.value;
        System.out.println("Created table with ID: " + tableId);
        
        // Test getTableName
        KVTResult<String> nameResult = KVTNative.getTableName(tableId);
        if (!nameResult.isSuccess()) {
            System.err.println("Failed to get table name: " + nameResult.error);
            System.exit(1);
        }
        System.out.println("Table name retrieved: " + nameResult.value);
        
        // Test listTables
        KVTResult<List<Long>> listResult = KVTNative.listTables();
        if (!listResult.isSuccess()) {
            System.err.println("Failed to list tables: " + listResult.error);
            System.exit(1);
        }
        System.out.println("Tables found: " + listResult.value.size());
        for (Long id : listResult.value) {
            System.out.println("  Table ID: " + id);
        }
        
        // Test batch execute
        List<KVTOp> ops = new ArrayList<>();
        ops.add(new KVTOp(OpType.OP_SET, tableId, "key1".getBytes(), "value1".getBytes()));
        ops.add(new KVTOp(OpType.OP_SET, tableId, "key2".getBytes(), "value2".getBytes()));
        ops.add(new KVTOp(OpType.OP_GET, tableId, "key1".getBytes(), null));
        
        KVTResult<List<KVTOpResult>> batchResult = KVTNative.executeBatch(0, ops);
        if (!batchResult.isSuccess() && batchResult.error != KVTError.BATCH_NOT_FULLY_SUCCESS) {
            System.err.println("Failed to execute batch: " + batchResult.error);
            System.exit(1);
        }
        
        System.out.println("Batch executed successfully");
        for (int i = 0; i < batchResult.value.size(); i++) {
            KVTOpResult opResult = batchResult.value.get(i);
            System.out.println("  Op " + i + ": " + opResult.error);
            if (opResult.value != null) {
                System.out.println("    Value: " + new String(opResult.value));
            }
        }
        
        // Shutdown
        KVTNative.shutdown();
        System.out.println("Test completed successfully!");
    }
}