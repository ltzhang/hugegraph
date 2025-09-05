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
import org.apache.hugegraph.backend.store.kvt.KVTStore;
import org.apache.hugegraph.backend.store.BackendStore;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.OptionSpace;

import java.util.HashMap;
import java.util.Map;

public class TestKVTBackendIntegration {
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
            // Create provider
            KVTStoreProvider provider = new KVTStoreProvider();
            
            System.out.println("Created KVT provider");
            System.out.println("Backend type: " + provider.type());
            System.out.println("Driver version: " + provider.driverVersion());
            
            // Initialize the provider
            provider.init();
            System.out.println("Provider initialized");
            
            // Create configuration
            Map<String, Object> configMap = new HashMap<>();
            configMap.put("backend", "kvt");
            configMap.put("serializer", "binary");
            configMap.put("store", "test_graph");
            configMap.put("gremlin.graph", "org.apache.hugegraph.HugeGraph");
            
            // Build configuration
            HugeConfig config = new HugeConfig(configMap);
            System.out.println("Configuration created");
            
            // Create a schema store
            BackendStore schemaStore = provider.loadSchemaStore(config);
            System.out.println("Schema store created: " + schemaStore.store());
            
            // Open the store
            schemaStore.open(config);
            System.out.println("Schema store opened");
            
            // Initialize the store 
            schemaStore.init();
            System.out.println("Schema store initialized");
            
            // Check if it's initialized
            boolean initialized = schemaStore.initialized();
            System.out.println("Store initialized: " + initialized);
            
            // Close the store
            schemaStore.close();
            System.out.println("Schema store closed");
            
            System.out.println("\nIntegration test completed successfully!");
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}