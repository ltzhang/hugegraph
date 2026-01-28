# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Apache HugeGraph is a fast-speed and highly-scalable graph database that supports more than 10 billion data. It's compliant with Apache TinkerPop 3 framework and supports Gremlin and Cypher query languages.

## Build Commands

### Prerequisites
- Java 11
- Maven 3.5+

### Common Build Commands
```bash
# Build without tests (fastest for development)
mvn clean package -Dmaven.test.skip=true

# Build with tests
mvn clean package

# Install to local maven repository
mvn clean install -Dmaven.test.skip=true

# Run tests only
mvn test

# Run specific test class
mvn test -Dtest=ClassName

# Run specific test method
mvn test -Dtest=ClassName#methodName
```

## Project Architecture

### Multi-Module Structure
The project is organized into several key modules:

- **hugegraph-commons**: Shared utilities and common components
  - `hugegraph-common`: Core utilities
  - `hugegraph-rpc`: RPC framework implementation

- **hugegraph-server**: Main server implementation (core functionality)
  - `hugegraph-core`: Core graph engine with TinkerPop 3 implementation
  - `hugegraph-api`: REST API layer built with Jersey
  - `hugegraph-dist`: Distribution packaging and scripts
  - Backend implementations:
    - `hugegraph-rocksdb`: RocksDB backend (embedded, default)
    - `hugegraph-cassandra`: Cassandra backend
    - `hugegraph-scylladb`: ScyllaDB backend
    - `hugegraph-hbase`: HBase backend
    - `hugegraph-mysql`: MySQL backend
    - `hugegraph-postgresql`: PostgreSQL backend
    - `hugegraph-hstore`: HStore backend
    - `hugegraph-palo`: Palo backend
  - `hugegraph-test`: Integration and unit tests

- **hugegraph-pd**: Placement Driver for distributed mode
  - Manages metadata and coordinates distributed operations

- **hugegraph-store**: Storage layer implementation for distributed mode
  - Handles distributed data storage and replication

### Core Concepts

1. **Schema Management**: The system uses explicit schema definition
   - PropertyKey: Defines properties that can be used by vertices/edges
   - VertexLabel: Defines vertex types with their properties
   - EdgeLabel: Defines edge types with their properties and connections
   - IndexLabel: Defines indexes for efficient querying

2. **Storage Abstraction**: Backend-agnostic storage layer
   - All backends implement common interfaces in `hugegraph-core`
   - Backend selection via configuration
   - Transaction support with ACID properties

3. **Query Engine**: 
   - Gremlin traversal implementation through TinkerPop 3
   - Query optimization strategies in `org.apache.hugegraph.traversal.optimize`
   - Support for both OLTP and OLAP operations

4. **API Layer**:
   - REST API in `hugegraph-api` module
   - Gremlin Server integration for remote traversals
   - Authentication and authorization support

## Server Operations

### Starting HugeGraph Server
```bash
# From hugegraph-server/hugegraph-dist after build
cd hugegraph-server/hugegraph-dist/target/apache-hugegraph-server-incubating-*/
bin/start-hugegraph.sh

# Stop server
bin/stop-hugegraph.sh

# Check server status
bin/checksocket.sh
```

### Configuration
- Main configuration: `conf/gremlin-server.yaml`
- Graph configuration: `conf/hugegraph.properties`
- Backend selection via `backend` property in graph configuration

## Development Workflow

### Running Tests
```bash
# Unit tests for a specific module
cd hugegraph-server/hugegraph-core
mvn test

# Integration tests
cd hugegraph-server/hugegraph-test
mvn test -Dtest=*IT

# Run with specific backend (default is memory)
mvn test -Dbackend=rocksdb
```

### Code Style
- Uses Apache license headers (enforced by license-maven-plugin)
- Follows standard Java conventions
- Tab size: 4 spaces (see .editorconfig)

## Key Interfaces and Entry Points

1. **HugeGraph Interface** (`hugegraph-core/src/main/java/org/apache/hugegraph/HugeGraph.java`):
   - Main graph interface extending TinkerPop's Graph
   - Entry point for all graph operations

2. **StandardHugeGraph** (`hugegraph-core/src/main/java/org/apache/hugegraph/StandardHugeGraph.java`):
   - Standard implementation of HugeGraph interface
   - Manages transactions, schema, and backends

3. **GraphManager** (`hugegraph-api/src/main/java/org/apache/hugegraph/api/graph/GraphManager.java`):
   - REST API endpoint management
   - HTTP request handling

4. **Backend Interfaces** (`hugegraph-core/src/main/java/org/apache/hugegraph/backend/store/`):
   - `BackendStore`: Main storage interface
   - `BackendTransaction`: Transaction handling
   - Each backend module implements these interfaces

## Current Effort: EloqRocks Backend Integration

### Goal
Integrate EloqRocks as a new storage backend for HugeGraph. EloqRocks is a transactional key-value store built on the `data_substrate` framework (C++), providing ACID transactions with multi-version concurrency control over RocksDB. We co-develop both systems: adding features to EloqRocks and building the HugeGraph adapter.

### Module Location
- `hugegraph-server/hugegraph-eloq/` — the integration module (adapter code, docs, build config live here)
- `hugegraph-server/hugegraph-eloq/doc/` — documentation for the integration effort (hugegraph backend analysis, design docs, etc.)
- `hugegraph-server/hugegraph-eloq/eloqrocks/` — git submodule pointing to the EloqRocks C++ project. **Read-only**: this is a self-contained submodule with its own repo, docs, and build. Do not modify files inside this directory; changes to EloqRocks go through its own repository.

### What EloqRocks Provides (C++ side)
- Transactional key-value store with `Put`, `Get`, `Delete`, `Scan` operations
- Range-partitioned sorted keys (lexicographic order) via `RocksKey`
- ACID transactions: `StartTx`, `CommitTx`, `AbortTx` with conflict detection
- Built on `data_substrate` framework with `CatalogFactory` pattern
- Storage engine: RocksDB (local embedded, single-node)
- Build system: CMake (not Maven)

### Integration Architecture (to be built)
The Java adapter in `hugegraph-eloq` needs to bridge HugeGraph's backend abstraction to EloqRocks via JNI or a service protocol. Key components to implement:

1. **EloqStoreProvider** — implements `BackendStoreProvider`, creates schema/graph/system stores
2. **EloqStore** — implements `BackendStore`, manages sessions and table dispatch
3. **EloqSessions** — implements `BackendSession`, wraps EloqRocks transaction lifecycle
4. **EloqTable** — implements `BackendTable`, maps HugeGraph table operations to EloqRocks key-value operations
5. **EloqSerializer** — extends `BinarySerializer` (binary key-value format, like RocksDB backend)
6. **EloqFeatures** — declares backend capabilities
7. **Registration** — register `"eloq"` provider in `BackendProviderFactory`

### Design Responsibility Split
- **EloqRocks (C++)**: Owns transaction management, MVCC, conflict detection, storage engine, persistence, recovery. Exposes a clean key-value transactional API.
- **HugeGraph Adapter (Java)**: Owns graph-to-KV mapping (serialization of vertices/edges/indexes to key-value pairs), query translation (ConditionQuery → scan ranges), schema/table management, and HugeGraph lifecycle integration.
- **Bridge Layer**: JNI bindings or gRPC service to connect Java adapter to C++ EloqRocks. Handles type marshalling and error code translation.

### Backend Abstraction Reference
HugeGraph organizes storage into three logical stores, each with specific tables:
- **Schema Store ("m")**: VERTEX_LABEL, EDGE_LABEL, PROPERTY_KEY, INDEX_LABEL
- **Graph Store ("g")**: VERTEX, EDGE_OUT, EDGE_IN, plus index tables (SECONDARY, RANGE_*, SEARCH, UNIQUE, LABEL indexes)
- **System Store ("s")**: META, COUNTERS

Each table maps to key-value operations. The RocksDB backend (`hugegraph-rocksdb`) is the closest reference implementation — it uses column families for tables, `BinarySerializer` for graph-to-binary encoding, and `WriteBatch` for transaction batching. The EloqRocks adapter should follow similar patterns but delegate transaction management to EloqRocks instead of RocksDB's WriteBatch.

### Existing Backend Patterns (reference for implementation)
- **KV-style** (RocksDB, KVT): Use `BinarySerializer`, `BinaryBackendEntry`, column-per-property encoding
- **Table-style** (MySQL, Cassandra): Use `TableSerializer`, `TableBackendEntry`, row-per-element encoding
- **Distributed** (HStore): Uses binary format with gRPC protocol, owner-based partitioning

The EloqRocks backend should follow the KV-style pattern since EloqRocks is a sorted key-value store.

### Phase 1 Status: COMPLETE (JNI Bridge + Smoke Test)

All 6 smoke tests pass (`EloqNativeTest`): table DDL, CRUD, binary data, range scan, transactions, cross-table isolation.

**Files created:**
- `hugegraph-eloq/pom.xml` — Maven module with antrun (native build), resources (copy .so), surefire (test config)
- `src/main/java/.../eloq/EloqNative.java` — JNI bridge declarations
- `src/main/native/EloqJNIBridge.cpp` — C++ JNI implementation using `EloqRocksDB::Open()` library API
- `src/main/native/Makefile` — builds libeloqjni.so (~237 MB)
- `src/test/java/.../eloq/EloqNativeTest.java` — 6 smoke tests

**Build prerequisites:**
1. EloqRocks C++ must be built first with `-DCMAKE_POSITION_INDEPENDENT_CODE=ON`:
   ```bash
   cd hugegraph-server/hugegraph-eloq/eloqrocks && mkdir -p bld && cd bld
   cmake -DELOQROCKS_SANITY_CHECK=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON .. && cmake --build . -j
   ```
2. Then build the Java module + JNI bridge:
   ```bash
   mvn clean package -pl hugegraph-server/hugegraph-eloq -Dmaven.test.skip=true
   ```
3. Run smoke tests:
   ```bash
   mvn test -pl hugegraph-server/hugegraph-eloq -Dtest=EloqNativeTest
   ```

### JNI + mimalloc Allocator Conflict (LD_PRELOAD)

EloqRocks depends on mimalloc as its memory allocator. When `libeloqjni.so` is loaded into the JVM via `System.loadLibrary()` (which calls `dlopen()`), a critical allocator conflict occurs. This section documents the problem, what was tried, and the working solution.

**The Problem:**
When the JVM loads `libeloqjni.so` via `dlopen()`, mimalloc's initialization code runs and overrides the global `malloc`/`free` functions. This means the JVM's own allocator (which already allocated memory during startup) is replaced mid-flight. When mimalloc later tries to manage or free memory that was allocated by the JVM's original allocator, it encounters invalid heap metadata and crashes with `SIGSEGV` in functions like `mi_usable_size` or `_mi_free_block_mt`.

Additionally, `libeloqjni.so` is very large (~237 MB) due to statically linking EloqRocks, data_substrate, and abseil. This can exhaust glibc's static TLS (Thread-Local Storage) block when loaded via `dlopen()`, causing "cannot allocate memory in static TLS block" errors.

**What Was Tried:**

1. **Static mimalloc linking with `--exclude-libs`** — Linked `/usr/local/lib/mimalloc-2.1/libmimalloc.a` statically and used `-Wl,--exclude-libs,libmimalloc.a` to hide its symbols from the dynamic symbol table. This prevented mimalloc's `malloc`/`free` from being exported, but mimalloc's internal constructor still ran during `dlopen()` and set up internal allocator state that conflicted with the JVM. Result: SIGSEGV in `_mi_free_block_mt` during library loading.

2. **GLIBC_TUNABLES for TLS** — Set `GLIBC_TUNABLES=glibc.rtld.optional_static_tls=16384` to increase the static TLS reservation. This fixed the TLS error but did not fix the allocator conflict on its own.

3. **LD_PRELOAD (working solution)** — Preload `libmimalloc.so.2` before the JVM starts. This makes mimalloc the global allocator from the very beginning, so all allocations (JVM internal + native code) go through mimalloc consistently. No allocator mismatch occurs because there is never a switch from one allocator to another.

**Working Configuration (in `pom.xml` surefire plugin):**
```xml
<environmentVariables>
    <LD_PRELOAD>/usr/local/lib/libmimalloc.so.2</LD_PRELOAD>
    <GLIBC_TUNABLES>glibc.rtld.optional_static_tls=16384</GLIBC_TUNABLES>
</environmentVariables>
```

Both environment variables are required:
- `LD_PRELOAD` — makes mimalloc the global allocator before JVM starts, preventing the allocator conflict
- `GLIBC_TUNABLES` — increases static TLS space so the large .so can be loaded via `dlopen()` without TLS exhaustion

**For production deployment:** Any process that loads `libeloqjni.so` (HugeGraph server, tests, etc.) must set these two environment variables before the JVM starts. This applies to `start-hugegraph.sh`, Maven surefire, and any other launcher.

### Key Files for Reference
| Purpose | Path |
|---------|------|
| Backend interfaces | `hugegraph-server/hugegraph-core/src/main/java/org/apache/hugegraph/backend/store/` |
| Provider factory | `backend/store/BackendProviderFactory.java` |
| Binary serializer | `backend/serializer/BinarySerializer.java` |
| RocksDB backend (reference) | `hugegraph-server/hugegraph-rocksdb/src/main/java/org/apache/hugegraph/backend/store/rocksdb/` |
| KVT backend (JNI reference) | `hugegraph-server/hugegraph-kvt/src/main/java/org/apache/hugegraph/backend/store/kvt/` |
| EloqRocks C++ source | `hugegraph-server/hugegraph-eloq/eloqrocks/` |
| EloqRocks architecture docs | `hugegraph-server/hugegraph-eloq/eloqrocks/docs/architecture.md` |
