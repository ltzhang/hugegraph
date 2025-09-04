# KVT (Key-Value Transaction) Backend for HugeGraph

## Overview
KVT is a C++ transactional key-value store integrated as a backend for HugeGraph. It provides full ACID transaction support with high performance and scalability.

## Building KVT

### Prerequisites
- C++ compiler with C++11 support (g++ 4.8+ or clang 3.4+)
- Java 11+ with JNI headers
- Maven 3.5+
- CMake 3.10+ (optional, for advanced builds)

### Build Instructions

#### 1. Build the C++ Library
```bash
cd hugegraph-server/hugegraph-kvt/kvt

# Compile the KVT implementation to object file
g++ -c -fPIC -g -O2 -std=c++11 kvt_mem.cpp -o kvt_memory.o

# Create shared library
# Linux:
g++ -shared -fPIC kvt_memory.o -o libkvt.so

# macOS:
g++ -shared -fPIC kvt_memory.o -o libkvt.dylib

# Windows (using MinGW):
g++ -shared -fPIC kvt_memory.o -o kvt.dll -Wl,--out-implib,libkvt.a
```

#### 2. Build the JNI Bridge
```bash
cd hugegraph-server/hugegraph-kvt

# The JNI bridge will be built automatically by Maven
mvn clean package -DskipTests
```

#### 3. Install the Library
```bash
# Copy the shared library to system library path or set LD_LIBRARY_PATH
# Linux:
sudo cp kvt/libkvt.so /usr/local/lib/
sudo ldconfig

# Or set library path:
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$(pwd)/kvt

# macOS:
sudo cp kvt/libkvt.dylib /usr/local/lib/
# Or set library path:
export DYLD_LIBRARY_PATH=$DYLD_LIBRARY_PATH:$(pwd)/kvt
```

## Configuration

### Using KVT Backend
Add the following to your HugeGraph configuration file (`conf/graphs/hugegraph.properties`):

```properties
# Use KVT as the backend
backend=kvt

# KVT-specific configurations (optional)
kvt.data_path=/path/to/kvt/data
kvt.wal_path=/path/to/kvt/wal
kvt.cache_size=8388608  # 8MB cache
kvt.write_buffer_size=4194304  # 4MB write buffer
kvt.max_open_files=1000
kvt.compression=snappy  # Options: none, snappy, zlib, lz4
```

## Required KVT Properties

The KVT backend implementation assumes the following properties from the underlying KVT store:

### 1. **ACID Transactions**
- **Atomicity**: All operations in a transaction succeed or fail together
- **Consistency**: Data integrity is maintained across transactions
- **Isolation**: Concurrent transactions don't interfere with each other
- **Durability**: Committed data persists even after system failures

### 2. **Concurrent Transaction Support**
- Multiple transactions can execute concurrently
- Proper isolation levels (at minimum Read Committed)
- Deadlock detection and resolution mechanisms

### 3. **Key-Value Operations**
- Basic operations: get, set, delete
- Range scan for ordered traversal
- Batch operations for performance

### 4. **Table Management**
- Support for multiple named tables
- Hash and range partitioning methods
- Dynamic table creation and deletion

### 5. **Performance Requirements**
- Low-latency single key operations (< 1ms)
- High throughput for batch operations
- Efficient range scans
- Scalable to billions of key-value pairs

### 6. **Reliability Features**
- Write-ahead logging (WAL) for durability
- Crash recovery mechanisms
- Optional replication support
- Backup and restore capabilities

## Testing

### Unit Tests
```bash
cd hugegraph-server/hugegraph-kvt
mvn test
```

### Simple Integration Test
```bash
# Compile and run the simple KVT test
javac -cp "src/main/java:target/classes" src/test/java/SimpleKVTTest.java -d target/test-classes
java -Djava.library.path=src/main/resources/native -cp "src/main/java:target/classes:target/test-classes" SimpleKVTTest
```

### Batch Operations Test
```bash
# Compile and run the batch operations test
javac -cp "src/main/java:target/classes" src/test/java/TestBatchOperations.java -d target/test-classes
java -Djava.library.path=src/main/resources/native -cp "src/main/java:target/classes:target/test-classes" TestBatchOperations
```

### Integration Tests
```bash
# Run HugeGraph tests with KVT backend
cd hugegraph-server/hugegraph-test
mvn test -Dbackend=kvt
```

### Performance Benchmarks
```bash
# Run performance tests
cd hugegraph-server/hugegraph-test
mvn test -Dtest=KVTPerformanceTest -Dbackend=kvt
```

## Architecture

### JNI Layer
The JNI (Java Native Interface) layer provides the bridge between Java and C++ code:
- `KVTNative.java`: Java class with native method declarations
- `KVTJNIBridge.cpp`: C++ implementation of JNI functions (fully implemented)
  - All KVT operations are bridged: get, set, delete, scan
  - Batch operations supported via `nativeBatchExecute`
  - Table management functions: create, drop, list tables
  - Full transaction support: start, commit, rollback
- Handles data type conversions and memory management

### Storage Model
```
HugeGraph Data Model          KVT Storage
┌─────────────────┐           ┌─────────────────┐
│  Vertex/Edge    │  ──────>  │  Key: TypeId    │
│  Properties     │           │  Value: Blob    │
└─────────────────┘           └─────────────────┘
        │                              │
        ▼                              ▼
┌─────────────────┐           ┌─────────────────┐
│  BackendEntry   │  ──────>  │  Serialized KV  │
│  - ID           │           │  - Composite Key│
│  - Columns      │           │  - Binary Value │
└─────────────────┘           └─────────────────┘
```

### Transaction Flow
1. HugeGraph begins transaction → KVT starts transaction
2. Graph operations → Batched KVT operations
3. HugeGraph commits → KVT commits with durability
4. Error handling → Automatic rollback

## Troubleshooting

### Common Issues

#### 1. Library Loading Errors
```
java.lang.UnsatisfiedLinkError: no kvt in java.library.path
```
**Solution**: Ensure the shared library is in the library path:
```bash
java -Djava.library.path=/path/to/kvt/lib -jar hugegraph-server.jar
```

#### 2. Transaction Conflicts
```
KVTError: TRANSACTION_HAS_STALE_DATA
```
**Solution**: This indicates optimistic concurrency control (OCC) validation failed. Retry the transaction or use pessimistic locking.

#### 3. Memory Issues
```
std::bad_alloc
```
**Solution**: Increase JVM heap size and native memory limits:
```bash
java -Xmx8g -XX:MaxDirectMemorySize=4g -jar hugegraph-server.jar
```

#### 4. Performance Degradation
- Check for lock contention in concurrent scenarios
- Monitor memory usage and GC activity
- Verify disk I/O is not a bottleneck
- Consider adjusting cache and buffer sizes

## Performance Tuning

### Memory Configuration
- `kvt.cache_size`: Increase for better read performance
- `kvt.write_buffer_size`: Larger buffers improve write throughput
- JVM heap: Ensure adequate heap for Java objects

### Concurrency Settings
- `kvt.max_background_threads`: Number of background compaction threads
- `kvt.max_concurrent_transactions`: Limit concurrent transactions

### I/O Optimization
- Use SSD for data and WAL paths
- Separate data and WAL to different disks
- Enable compression for large datasets

## Development

### Adding New Features
1. Update `kvt_inc.h` with new functions
2. Implement in KVT C++ code
3. Add JNI wrapper methods
4. Update Java backend implementation
5. Add tests

### Debugging
Enable debug logging:
```properties
# In log4j2.xml
<Logger name="org.apache.hugegraph.backend.store.kvt" level="DEBUG"/>
```

Use GDB for native debugging:
```bash
gdb java
(gdb) set environment LD_LIBRARY_PATH /path/to/kvt
(gdb) run -jar hugegraph-server.jar
```

## Implementation Status

### Completed Features
- ✅ Full JNI bridge implementation (KVTJNIBridge.cpp)
- ✅ Batch operations support
- ✅ Table management (create, drop, list)
- ✅ Transaction management (ACID compliance)
- ✅ Basic CRUD operations (get, set, delete)
- ✅ Range scans for ordered traversal
- ✅ Build scripts for native libraries

### Test Coverage
- ✅ SimpleKVTTest: Basic operations and transaction flow
- ✅ TestBatchOperations: Batch execute and list tables
- ✅ Native library loading and JNI integration

### Known Limitations (kvt_memory.o implementation)
- In-memory only (no persistence) - This is specific to the test implementation
- Single-process only - Production implementations should support distributed access
- No WAL/durability - Production implementations should provide durability guarantees

Note: These limitations apply only to the kvt_memory.o test implementation. Production KVT implementations should provide full durability, scalability, and distributed capabilities as specified in the interface requirements.

## Contributing
Please follow the HugeGraph contribution guidelines when submitting patches or features for the KVT backend.

## License
The KVT backend follows the same Apache 2.0 license as HugeGraph.