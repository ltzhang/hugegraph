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


## The current effort is to integrate kvt under hugegraph-server/hugegraph-kvt/ into the hugegraph framework.

- In this effort, you only need to care about hugegraph-kvt/kvt/kvt_inc.h file, which is the interface for the C++ transactional key-value store.

- You should assume kvt_inc.h can interface with very powerful, transactional key value stores. Do not assume any limitations (such as durability, scalability, ACID, and so on). Assume the store has all the capabilities. But do point out if certain property is needed for certain functionalities in the document file KVT_README.md

- To link the store, you just link hugegraph-kvt/kvt/kvt_memory.o All other files in the directory are irrelevant, more over, kvt_memory.o (which is produced with "g++ -c -fPIC -g -O0 kvt_memory.cpp") implements a in-memory version for test purposes. But you should not concern with its limitations, since other implementations have different capabilities. Stick to the interface.

- Use hugegraph-kvt/ as the main directory we operate on. When you need to understand how integration works, look at other directories. Do understand that kvt is a C++ codebase, so we need JNI integration, but we want to treat it as a distributed, scalable, persistent, transactional and so on, and implement all the features of hugegraph on top of it.

- hugegraph-kvt/KVT_README.md contains instructions and build methods. Update it if you see fi
- remember all shortcuts that needs to be fixed in production code and write them into plan as well as readme document so we do not forget to improve on them.