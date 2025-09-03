# KVT Integration Plan for HugeGraph

## Overview
This document outlines the plan for integrating the KVT (Key-Value Transaction) C++ store into HugeGraph as a new backend storage option. The KVT store provides transactional key-value operations with full ACID properties.

## Phase 1: Project Setup and JNI Bridge
**Goal**: Establish basic connectivity between Java and C++ KVT library

### 1.1 Maven Module Structure
- [ ] Add hugegraph-kvt module to hugegraph-server/pom.xml
- [ ] Create pom.xml for hugegraph-kvt with dependencies:
  - hugegraph-core
  - JNI dependencies
- [ ] Set up directory structure:
  ```
  hugegraph-kvt/
  ├── pom.xml
  ├── src/main/java/          # Java code
  ├── src/main/native/         # JNI bridge code
  ├── src/test/java/           # Java tests
  └── kvt/                     # Existing C++ code
  ```

### 1.2 Build C++ KVT Library
- [ ] Compile kvt_memory.cpp to kvt_memory.o with position-independent code (-fPIC)
- [ ] Create shared library libkvt.so (Linux) / libkvt.dylib (macOS) / kvt.dll (Windows)
- [ ] Set up build script for native compilation
- [ ] Document build process in KVT_README.md

### 1.3 JNI Wrapper Layer
- [ ] Create KVTNative.java with native method declarations matching kvt_inc.h
- [ ] Implement KVTJNIBridge.cpp with JNI functions
- [ ] Handle data type conversions:
  - Java String ↔ std::string
  - Java long ↔ uint64_t
  - Error codes ↔ Exceptions
- [ ] Implement proper resource management (cleanup on GC)

### Test Milestone
- [ ] Simple Java test that:
  - Loads native library
  - Initializes KVT
  - Creates a table
  - Performs get/set/delete operations
  - Handles transactions

## Phase 2: Backend Store Implementation
**Goal**: Implement HugeGraph's BackendStore interface for KVT

### 2.1 KVTStoreProvider
- [ ] Create KVTStoreProvider extends AbstractBackendStoreProvider
- [ ] Implement required methods:
  - `type()` returns "kvt"
  - `newSchemaStore()`
  - `newGraphStore()`
  - `newSystemStore()`
- [ ] Add version management

### 2.2 KVTStore Base Implementation
- [ ] Create KVTStore extends AbstractBackendStore<KVTSession>
- [ ] Implement core methods:
  - `open()` / `close()`
  - `init()` / `clear()`
  - `beginTx()` / `commitTx()` / `rollbackTx()`
  - `mutate(BackendMutation mutation)`
  - `query(Query query)`
- [ ] Manage table lifecycle (create tables for each HugeType)

### 2.3 KVTSession Management
- [ ] Create KVTSession class to wrap transaction state
- [ ] Track transaction ID from KVT
- [ ] Handle session pooling if needed
- [ ] Implement proper cleanup on session close

### Test Milestone
- [ ] Unit tests for:
  - Store lifecycle (open/close/init/clear)
  - Transaction management
  - Basic mutations and queries

## Phase 3: Data Model Mapping
**Goal**: Map HugeGraph's data model to KVT's key-value model

### 3.1 Table Structure Design
- [ ] Define table mapping:
  ```
  HugeType.VERTEX         → "vertex" table (hash partition)
  HugeType.EDGE_OUT       → "edge_out" table (range partition)
  HugeType.EDGE_IN        → "edge_in" table (range partition)
  HugeType.PROPERTY_KEY   → "property_key" table (hash partition)
  HugeType.VERTEX_LABEL   → "vertex_label" table (hash partition)
  HugeType.EDGE_LABEL     → "edge_label" table (hash partition)
  HugeType.INDEX_LABEL    → "index_label" table (hash partition)
  HugeType.SECONDARY_INDEX → "secondary_index" table (range partition)
  HugeType.RANGE_INDEX    → "range_index" table (range partition)
  HugeType.SEARCH_INDEX   → "search_index" table (hash partition)
  ```
- [ ] Design composite key format: `[type_byte][id_bytes]`
- [ ] Define value serialization format

### 3.2 KVTTable Implementation
- [ ] Create KVTTable abstract class
- [ ] Implement specific tables for each HugeType
- [ ] Handle BackendEntry ↔ KV conversion:
  - Serialize BackendColumns to value bytes
  - Deserialize KV pairs to BackendEntry
- [ ] Implement scan operations for range queries

### 3.3 Serialization Layer
- [ ] Create KVTSerializer for data conversion
- [ ] Handle property serialization
- [ ] Support different data types (string, number, boolean, etc.)
- [ ] Implement compression if needed

### Test Milestone
- [ ] Integration tests for:
  - Vertex CRUD operations
  - Edge CRUD operations
  - Property operations
  - Index operations

## Phase 4: Transaction Management
**Goal**: Properly handle transactional semantics

### 4.1 Transaction Coordination
- [ ] Map HugeGraph transaction to single KVT transaction
- [ ] Handle transaction isolation levels
- [ ] Implement proper locking semantics
- [ ] Support read-only transactions

### 4.2 Batch Operations
- [ ] Implement batch mutations using kvt_batch_execute
- [ ] Optimize bulk loading scenarios
- [ ] Handle partial failure recovery
- [ ] Implement write buffering

### Test Milestone
- [ ] Concurrent transaction tests
- [ ] Rollback scenarios
- [ ] Deadlock detection and handling
- [ ] Performance under concurrent load

## Phase 5: Query Optimization
**Goal**: Optimize query performance

### 5.1 Query Translation
- [ ] Convert Query types to KVT operations:
  - IdQuery → direct get()
  - ConditionQuery → scan() with filters
  - Optimize query conditions
- [ ] Implement query result pagination
- [ ] Add query caching layer if needed

### 5.2 Index Support
- [ ] Create secondary index tables
- [ ] Maintain index consistency on updates
- [ ] Optimize index lookups
- [ ] Support composite indexes

### Test Milestone
- [ ] Query performance benchmarks
- [ ] Compare with RocksDB backend
- [ ] Profile and optimize hot paths

## Phase 6: Integration and Polish
**Goal**: Complete integration with HugeGraph ecosystem

### 6.1 Backend Registration
- [ ] Register KVT backend in build system
- [ ] Add to hugegraph-dist packaging
- [ ] Create sample configuration files
- [ ] Update Docker images if needed

### 6.2 Comprehensive Testing
- [ ] Run full HugeGraph test suite
- [ ] Performance testing with large graphs
- [ ] Memory leak detection
- [ ] Stress testing

### 6.3 Documentation
- [ ] Complete KVT_README.md with:
  - Build instructions
  - Configuration options
  - Performance tuning guide
  - Troubleshooting
- [ ] Update CLAUDE.md with KVT-specific information
- [ ] Add JavaDoc comments
- [ ] Create example usage code

### Final Test Milestone
- [ ] All HugeGraph tests passing
- [ ] Performance meets or exceeds RocksDB
- [ ] No memory leaks detected
- [ ] Documentation complete

## Required KVT Properties
The following properties are assumed from the KVT store:
- **ACID Compliance**: Full transaction support with atomicity, consistency, isolation, durability
- **Concurrent Access**: Multiple transactions can run concurrently with proper isolation
- **Range Queries**: Support for scan operations on range-partitioned tables
- **Batch Operations**: Efficient batch execution of multiple operations
- **Durability**: Data persists after commit (configurable)
- **Scalability**: Can handle large datasets efficiently

## Success Criteria
1. KVT backend can be selected via configuration
2. All HugeGraph features work with KVT backend
3. Performance comparable to or better than existing backends
4. Stable under concurrent load
5. Well-documented and maintainable code

## Notes
- This plan will be updated as implementation progresses
- Each phase builds on the previous one
- Testing at each phase ensures early detection of issues
- Performance optimization is an ongoing concern throughout