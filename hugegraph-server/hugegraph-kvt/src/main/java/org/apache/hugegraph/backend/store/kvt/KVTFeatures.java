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

import org.apache.hugegraph.backend.store.BackendFeatures;

/**
 * Features supported by the KVT backend.
 */
public class KVTFeatures implements BackendFeatures {

    @Override
    public boolean supportsScanToken() {
        // KVT supports scanning with start/end keys
        return true;
    }

    @Override
    public boolean supportsScanKeyPrefix() {
        // KVT can scan by key prefix using range queries
        return true;
    }

    @Override
    public boolean supportsScanKeyRange() {
        // KVT supports range scans on range-partitioned tables
        return true;
    }

    @Override
    public boolean supportsQuerySchemaByName() {
        // Can query schema elements by name
        return true;
    }

    @Override
    public boolean supportsQueryByLabel() {
        // Can query vertices/edges by label
        return true;
    }

    @Override
    public boolean supportsQueryWithInCondition() {
        // IN conditions need to be expanded to multiple queries
        return false;
    }

    @Override
    public boolean supportsQueryWithRangeCondition() {
        // KVT supports range conditions on range-partitioned tables
        return true;
    }

    @Override
    public boolean supportsQueryWithOrderBy() {
        // Range-partitioned tables maintain key order
        return true;
    }

    @Override
    public boolean supportsQueryWithContains() {
        // Text search not natively supported
        return false;
    }

    @Override
    public boolean supportsQueryWithContainsKey() {
        // Property key containment check
        return false;
    }


    @Override
    public boolean supportsQueryByPage() {
        // Pagination can be implemented with scan cursors
        return true;
    }

    @Override
    public boolean supportsQuerySortByInputIds() {
        // Can maintain input ID order
        return true;
    }

    @Override
    public boolean supportsDeleteEdgeByLabel() {
        // Can delete edges by label with scan+delete
        return true;
    }

    @Override
    public boolean supportsUpdateVertexProperty() {
        // Now that BinarySerializer implements writeVertexProperty()
        // we can support partial property updates
        return true;
    }

    @Override
    public boolean supportsUpdateEdgeProperty() {
        // Now that BinarySerializer implements writeEdgeProperty()
        // we can support partial property updates
        return true;
    }

    @Override
    public boolean supportsTransaction() {
        // KVT has full transaction support
        return true;
    }

    @Override
    public boolean supportsNumberType() {
        // Numbers are serialized as bytes
        return true;
    }

    @Override
    public boolean supportsAggregateProperty() {
        // Aggregate properties can be implemented
        return true;
    }

    @Override
    public boolean supportsTtl() {
        // TTL not yet implemented
        return false;
    }

    @Override
    public boolean supportsOlapProperties() {
        // OLAP properties can be stored
        return true;
    }

    @Override
    public boolean supportsMergeVertexProperty() {
        // Vertex property merging is supported
        return true;
    }
    
    @Override
    public boolean supportsPersistence() {
        // Current implementation is in-memory only (kvt_memory.o)
        // This will return true when using a persistent KVT implementation
        return false;
    }
}