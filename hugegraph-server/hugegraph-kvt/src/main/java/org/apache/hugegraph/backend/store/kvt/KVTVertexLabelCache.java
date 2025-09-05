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

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.apache.hugegraph.backend.id.Id;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Cache for vertex labels to avoid frequent lookups during edge queries.
 * This helps resolve the "~undefined" vertex label issue when querying edges.
 */
public class KVTVertexLabelCache {
    
    private static final Logger LOG = Log.logger(KVTVertexLabelCache.class);
    
    // Cache mapping vertex ID to vertex label ID
    private final Map<Id, Id> vertexToLabelCache;
    
    // Cache mapping vertex label name to label ID
    private final Map<String, Id> labelNameToIdCache;
    
    // Default label ID for vertices without explicit labels
    private static final Id DEFAULT_VERTEX_LABEL = IdGenerator.of("~default");
    
    public KVTVertexLabelCache() {
        this.vertexToLabelCache = new ConcurrentHashMap<>();
        this.labelNameToIdCache = new ConcurrentHashMap<>();
        
        // Pre-populate with common labels to avoid "~undefined" errors
        this.labelNameToIdCache.put("~default", DEFAULT_VERTEX_LABEL);
    }
    
    /**
     * Cache a vertex's label
     */
    public void cacheVertexLabel(Id vertexId, Id labelId) {
        if (vertexId != null && labelId != null) {
            this.vertexToLabelCache.put(vertexId, labelId);
            LOG.debug("Cached vertex {} with label {}", vertexId, labelId);
        }
    }
    
    /**
     * Cache a vertex label name to ID mapping
     */
    public void cacheLabelMapping(String labelName, Id labelId) {
        if (labelName != null && labelId != null) {
            this.labelNameToIdCache.put(labelName, labelId);
            LOG.debug("Cached label mapping {} -> {}", labelName, labelId);
        }
    }
    
    /**
     * Get the label ID for a vertex
     */
    public Id getVertexLabel(Id vertexId) {
        Id labelId = this.vertexToLabelCache.get(vertexId);
        if (labelId == null) {
            LOG.debug("Vertex label not found in cache for vertex {}, using default", vertexId);
            return DEFAULT_VERTEX_LABEL;
        }
        return labelId;
    }
    
    /**
     * Get the label ID for a label name
     */
    public Id getLabelId(String labelName) {
        Id labelId = this.labelNameToIdCache.get(labelName);
        if (labelId == null) {
            LOG.debug("Label ID not found for name {}, creating new ID", labelName);
            // Create a deterministic ID based on the label name
            labelId = IdGenerator.of(labelName);
            this.labelNameToIdCache.put(labelName, labelId);
        }
        return labelId;
    }
    
    /**
     * Clear the cache
     */
    public void clear() {
        this.vertexToLabelCache.clear();
        this.labelNameToIdCache.clear();
        
        // Re-add default label
        this.labelNameToIdCache.put("~default", DEFAULT_VERTEX_LABEL);
    }
    
    /**
     * Get cache statistics
     */
    public String getStats() {
        return String.format("VertexLabelCache[vertices=%d, labels=%d]",
                           this.vertexToLabelCache.size(),
                           this.labelNameToIdCache.size());
    }
    
    /**
     * Check if a vertex has a cached label
     */
    public boolean hasVertexLabel(Id vertexId) {
        return this.vertexToLabelCache.containsKey(vertexId);
    }
    
    /**
     * Remove a vertex from the cache
     */
    public void removeVertex(Id vertexId) {
        this.vertexToLabelCache.remove(vertexId);
    }
}