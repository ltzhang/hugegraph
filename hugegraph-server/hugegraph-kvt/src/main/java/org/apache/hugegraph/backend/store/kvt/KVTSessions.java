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

import org.apache.hugegraph.backend.store.BackendSessionPool;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Session pool for KVT sessions.
 * Manages thread-local sessions for efficient transaction handling.
 */
public class KVTSessions extends BackendSessionPool {

    private static final Logger LOG = Log.logger(KVTSessions.class);
    
    private final HugeConfig config;
    private final String database;
    private final String store;
    
    // Thread-local session storage
    private final ThreadLocal<KVTSession> threadLocalSession;
    
    public KVTSessions(HugeConfig config, String database, String store) {
        this.config = config;
        this.database = database;
        this.store = store;
        this.threadLocalSession = new ThreadLocal<>();
        
        LOG.debug("Created KVTSessions for database '{}', store '{}'", 
                 database, store);
    }
    
    public String database() {
        return this.database;
    }
    
    public String store() {
        return this.store;
    }
    
    /**
     * Get or create a session for the current thread
     */
    public KVTSession session() {
        KVTSession session = this.threadLocalSession.get();
        if (session == null) {
            session = new KVTSession(this.config, this.database);
            this.threadLocalSession.set(session);
            LOG.debug("Created new KVT session for thread {}", 
                     Thread.currentThread().getName());
        }
        return session;
    }
    
    @Override
    public void close() {
        // Clean up the session for the current thread
        KVTSession session = this.threadLocalSession.get();
        if (session != null) {
            session.close();
            this.threadLocalSession.remove();
            LOG.debug("Closed KVT session for thread {}", 
                     Thread.currentThread().getName());
        }
    }
    
    @Override
    public boolean closed() {
        return this.threadLocalSession.get() == null;
    }
}