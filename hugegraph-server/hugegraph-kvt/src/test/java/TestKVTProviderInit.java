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

import org.apache.hugegraph.backend.store.kvt.KVTStoreProvider;
import org.apache.hugegraph.backend.store.kvt.KVTNative;

public class TestKVTProviderInit {
    static {
        System.setProperty("java.library.path", "target/native");
        try {
            System.loadLibrary("kvtjni");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load native library: " + e.getMessage());
            System.exit(1);
        }
    }
    
    public static void main(String[] args) {
        try {
            // Initialize KVT directly
            KVTNative.KVTError error = KVTNative.initialize();
            if (error != KVTNative.KVTError.SUCCESS) {
                System.err.println("Failed to initialize KVT: " + error);
                System.exit(1);
            }
            System.out.println("KVT system initialized");
            
            // Create provider
            KVTStoreProvider provider = new KVTStoreProvider();
            System.out.println("Created KVT provider");
            System.out.println("Backend type: " + provider.type());
            System.out.println("Driver version: " + provider.driverVersion());
            
            // Initialize provider (will detect KVT is already initialized)
            provider.init();
            System.out.println("Provider initialized successfully");
            
            // Create some test tables
            testTableOperations();
            
            // Cleanup
            KVTNative.shutdown();
            System.out.println("KVT system shut down");
            
            System.out.println("\nAll integration tests completed successfully!");
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void testTableOperations() {
        System.out.println("\nTesting table operations:");
        
        // Create a test table
        KVTNative.KVTResult<Long> createResult = 
            KVTNative.createTable("provider_test_table", "hash");
        if (!createResult.isSuccess()) {
            System.err.println("Failed to create table: " + createResult.error);
            System.exit(1);
        }
        System.out.println("Created table with ID: " + createResult.value);
        
        // Test some basic operations
        long tableId = createResult.value;
        
        // Test set/get
        KVTNative.KVTResult<Void> setResult = 
            KVTNative.set(0, tableId, "test_key".getBytes(), "test_value".getBytes());
        if (!setResult.isSuccess()) {
            System.err.println("Failed to set value: " + setResult.error);
            System.exit(1);
        }
        System.out.println("Set key-value pair successfully");
        
        KVTNative.KVTResult<byte[]> getResult = 
            KVTNative.get(0, tableId, "test_key".getBytes());
        if (!getResult.isSuccess()) {
            System.err.println("Failed to get value: " + getResult.error);
            System.exit(1);
        }
        System.out.println("Retrieved value: " + new String(getResult.value));
        
        // Drop table
        KVTNative.KVTResult<Void> dropResult = KVTNative.dropTable(tableId);
        if (!dropResult.isSuccess()) {
            System.err.println("Failed to drop table: " + dropResult.error);
            System.exit(1);
        }
        System.out.println("Dropped table successfully");
    }
}