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

import org.apache.hugegraph.backend.store.BackendSession;
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
    
    private final String database;
    private boolean opened;
    
    public KVTSessions(HugeConfig config, String database, String store) {
        super(config, store);
        this.database = database;
        this.opened = false;
        
        LOG.debug("Created KVTSessions for database '{}', store '{}'", 
                 database, store);
    }
    
    public String database() {
        return this.database;
    }
    
    public String store() {
        return this.store;
    }
    
    @Override
    public void open() throws Exception {
        this.opened = true;
        LOG.info("Opened KVT session pool");
    }
    
    @Override
    protected boolean opened() {
        return this.opened;
    }
    
    @Override
    public BackendSession session() {
        return (BackendSession) this.getOrNewSession();
    }
    
    @Override
    protected BackendSession newSession() {
        return new KVTSession(this.config(), this.database);
    }
    
    @Override
    protected void doClose() {
        this.opened = false;
        LOG.info("Closed KVT session pool");
    }
}