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

import org.apache.hugegraph.backend.store.AbstractBackendStoreProvider;
import org.apache.hugegraph.backend.store.BackendStore;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Provider for KVT backend store implementation.
 * This class is responsible for creating and managing KVT store instances.
 */
public class KVTStoreProvider extends AbstractBackendStoreProvider {

    private static final Logger LOG = Log.logger(KVTStoreProvider.class);
    
    private static final String KVT_BACKEND_TYPE = "kvt";
    private static boolean initialized = false;
    
    static {
        // Load the native library
        try {
            System.loadLibrary("kvtjni");
            LOG.info("Loaded kvtjni library from system path");
        } catch (UnsatisfiedLinkError e) {
            // Try to load from the module's target directory
            try {
                String libPath = System.getProperty("user.dir") + 
                               "/hugegraph-server/hugegraph-kvt/target/native/libkvtjni.so";
                System.load(libPath);
                LOG.info("Loaded kvtjni library from: {}", libPath);
            } catch (UnsatisfiedLinkError e2) {
                LOG.error("Failed to load kvtjni library", e2);
                throw new RuntimeException("Cannot load KVT JNI library", e2);
            }
        }
    }

    protected String database() {
        return this.graph().toLowerCase();
    }

    @Override
    protected BackendStore newSchemaStore(HugeConfig config, String store) {
        return new KVTStore.KVTSchemaStore(this, this.database(), store);
    }

    @Override
    protected BackendStore newGraphStore(HugeConfig config, String store) {
        return new KVTStore.KVTGraphStore(this, this.database(), store);
    }

    @Override
    protected BackendStore newSystemStore(HugeConfig config, String store) {
        return new KVTStore.KVTSystemStore(this, this.database(), store);
    }

    @Override
    public String type() {
        return KVT_BACKEND_TYPE;
    }

    @Override
    public String driverVersion() {
        /*
         * Version history:
         * [1.0] Initial KVT backend implementation
         *       - Basic CRUD operations
         *       - Transaction support
         *       - Table management
         */
        return "1.0";
    }
    
    @Override
    public void init() {
        super.init();
        
        // Initialize KVT system once
        synchronized (KVTStoreProvider.class) {
            if (!initialized) {
                KVTNative.KVTError error = KVTNative.initialize();
                if (error != KVTNative.KVTError.SUCCESS) {
                    throw new RuntimeException("Failed to initialize KVT: " + error);
                }
                initialized = true;
                LOG.info("KVT system initialized successfully");
                
                // Register shutdown hook to cleanup KVT
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (initialized) {
                        KVTNative.shutdown();
                        initialized = false;
                        LOG.info("KVT system shutdown");
                    }
                }));
            }
        }
    }
    
    @Override
    public void clear() {
        super.clear();
        // KVT will drop tables when stores are cleared
    }
}