# KVT Backend for HugeGraph

## Overview
KVT is a C++ transactional key-value store integrated as a HugeGraph backend via JNI. The KVT integration follows the same query model as RocksDB: scans, prefix/range queries, and key-based CRUD. Operator/predicate/aggregation pushdown is intentionally not used.

## Build

### Prerequisites
- Java 11+
- Maven 3.5+
- C++ compiler with C++11+ support

### Build Native Library
```bash
cd hugegraph-server/hugegraph-kvt

# Build the in-memory KVT implementation (test use)
cd kvt
# Build object file used by JNI
# (Production implementations should provide a compatible object/lib)
g++ -c -fPIC -g -O2 -std=c++11 kvt_memory.cpp -o kvt_memory.o
cd ..

# Build JNI library + Java classes
mvn clean compile
```

### Verify Native Library
```bash
ls -la target/native/
# Expect: libkvtjni.so (Linux) or libkvtjni.dylib (macOS)
```

## Configuration
Add the following to `conf/graphs/hugegraph.properties`:

```properties
backend=kvt
```

## Running Tests
```bash
cd hugegraph-server/hugegraph-kvt
mvn test
```

For native library path:
```bash
mvn test -Djava.library.path=target/native
```

## Query Support
- Supported: key lookup, prefix scans, ID range scans.
- Not supported: filter predicate pushdown, aggregation pushdown, custom operator pushdown.
- Condition queries with non-range filters are rejected to avoid incorrect results.

## Required KVT Properties
The KVT backend assumes the underlying store provides:
- ACID transactions with durable commit.
- Concurrent transactions with isolation (at least Read Committed).
- Ordered key scans for prefix/range queries.
- Stable table ID/name mapping and table lifecycle operations.

## Known Shortcuts / TODOs
- `KVTBatch` uses a placeholder native batch executor and does not apply writes.
- Column elimination is not implemented in `KVTTable.eliminate()`.

## Notes
- `kvt_memory.o` is an in-memory test implementation only. Production deployments must provide a persistent KVT implementation with the same API.

