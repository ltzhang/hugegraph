# KVT Backend User Guide for HugeGraph

## Table of Contents
1. [Introduction](#introduction)
2. [Installation](#installation)
3. [Configuration](#configuration)
4. [Getting Started](#getting-started)
5. [Performance Tuning](#performance-tuning)
6. [Monitoring](#monitoring)
7. [Troubleshooting](#troubleshooting)
8. [API Reference](#api-reference)

## Introduction

The KVT (Key-Value Transaction) backend is a high-performance storage engine for HugeGraph that provides:

- **Full ACID Transactions**: Complete transactional support with atomicity, consistency, isolation, and durability
- **High Performance**: Optimized for both read and write operations with batching support
- **Query Optimization**: Built-in query cache and optimizer for improved performance
- **Index Support**: Secondary, range, and unique indexes for efficient queries
- **Scalability**: Designed to handle large graphs with billions of vertices and edges

### Key Features

- Native C++ implementation with JNI bridge for maximum performance
- Transaction isolation levels from READ_UNCOMMITTED to SERIALIZABLE
- Batch operations for bulk loading
- Query result caching with TTL
- Comprehensive query statistics and slow query detection
- Multiple index types for optimized queries

## Installation

### Prerequisites

- Java 11 or higher
- Linux/Unix system (Windows support via WSL)
- GCC 7+ for compiling native libraries
- Maven 3.6+ for building

### Building from Source

1. Clone the HugeGraph repository:
```bash
git clone https://github.com/apache/incubator-hugegraph.git
cd incubator-hugegraph/hugegraph-server/hugegraph-kvt
```

2. Build the native KVT library:
```bash
cd kvt
make clean all
cd ..
```

3. Build the JNI bridge:
```bash
cd src/main/native
make clean all
cd ../../..
```

4. Build the Java module:
```bash
mvn clean package
```

### Binary Installation

Download the pre-built package from the releases page and extract:

```bash
tar -xzf hugegraph-kvt-1.0.0.tar.gz
cd hugegraph-kvt-1.0.0
```

## Configuration

### Basic Configuration

Create or modify `conf/kvt.properties`:

```properties
# Backend selection
backend=kvt

# Basic settings
kvt.storage.data_path=/data/hugegraph/kvt
kvt.transaction.isolation_level=READ_COMMITTED
kvt.batch.enabled=true
kvt.cache.enabled=true
```

### Advanced Configuration

#### Transaction Settings

```properties
# Isolation levels: DEFAULT, READ_UNCOMMITTED, READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE
kvt.transaction.isolation_level=REPEATABLE_READ
kvt.transaction.timeout=30000
kvt.transaction.retry.times=3
```

#### Performance Tuning

```properties
# Batch operations
kvt.batch.size=5000
kvt.batch.auto_flush=true

# Query cache
kvt.cache.max_size=50000
kvt.cache.ttl=300000

# Thread pools
kvt.performance.read_threads=32
kvt.performance.write_threads=16
```

#### Memory Settings

```properties
# Buffer and cache sizes in bytes
kvt.memory.buffer_size=268435456  # 256MB
kvt.memory.cache_size=1073741824   # 1GB
kvt.memory.direct_memory=true
```

### HugeGraph Integration

Add to your HugeGraph configuration (`conf/graphs/hugegraph.properties`):

```properties
# Use KVT backend
backend=kvt
serializer=binary

# KVT specific settings
store=kvt
kvt.config.file=conf/kvt.properties
```

## Getting Started

### Starting HugeGraph with KVT

```bash
# Start HugeGraph server
bin/start-hugegraph.sh

# Verify KVT backend is loaded
grep "KVT backend initialized" logs/hugegraph-server.log
```

### Basic Operations

#### Creating a Graph

```groovy
// Gremlin console
graph = HugeFactory.open('conf/graphs/hugegraph.properties')
schema = graph.schema()

// Create schema
schema.propertyKey("name").asText().create()
schema.propertyKey("age").asInt().create()

schema.vertexLabel("person")
    .properties("name", "age")
    .primaryKeys("name")
    .create()

schema.edgeLabel("knows")
    .sourceLabel("person")
    .targetLabel("person")
    .create()
```

#### Adding Data

```groovy
// Add vertices
graph.addVertex(T.label, "person", "name", "Alice", "age", 30)
graph.addVertex(T.label, "person", "name", "Bob", "age", 25)

// Add edge
alice = g.V().has("name", "Alice").next()
bob = g.V().has("name", "Bob").next()
alice.addEdge("knows", bob)

// Commit transaction
graph.tx().commit()
```

### Batch Loading

For large-scale data import:

```java
// Java example
HugeGraph graph = HugeFactory.open(config);
GraphManager manager = graph.graphManager();

// Enable batch mode
manager.mode(GraphMode.LOADING);

// Batch insert vertices
List<Vertex> vertices = new ArrayList<>();
for (int i = 0; i < 10000; i++) {
    Vertex v = graph.addVertex(
        T.label, "person",
        "name", "Person" + i,
        "age", 20 + (i % 50)
    );
    vertices.add(v);
    
    if (vertices.size() >= 1000) {
        graph.tx().commit();
        vertices.clear();
    }
}

// Final commit
graph.tx().commit();
manager.mode(GraphMode.NONE);
```

## Performance Tuning

### Query Optimization

1. **Enable Query Cache**:
```properties
kvt.cache.enabled=true
kvt.cache.max_size=100000
```

2. **Use Indexes**:
```groovy
// Create indexes for frequently queried properties
schema.indexLabel("personByAge")
    .onV("person")
    .by("age")
    .range()
    .create()
```

3. **Optimize Batch Size**:
```properties
# For bulk loading
kvt.batch.size=10000

# For normal operations
kvt.batch.size=1000
```

### Memory Optimization

1. **Adjust Cache Sizes**:
```properties
# Increase for read-heavy workloads
kvt.memory.cache_size=2147483648  # 2GB

# Increase for write-heavy workloads
kvt.memory.buffer_size=536870912  # 512MB
```

2. **Use Direct Memory**:
```properties
kvt.memory.direct_memory=true
```

### Thread Pool Tuning

```properties
# For read-heavy workloads
kvt.performance.read_threads=64
kvt.performance.write_threads=8

# For write-heavy workloads
kvt.performance.read_threads=16
kvt.performance.write_threads=32
```

## Monitoring

### Metrics Collection

Enable metrics:
```properties
kvt.metrics.enabled=true
kvt.metrics.interval=60000
```

### Query Statistics

Access query statistics programmatically:

```java
KVTQueryStats stats = KVTQueryStats.getInstance();
StatsSummary summary = stats.getSummary();

System.out.println("Total queries: " + summary.totalQueries);
System.out.println("Cache hit ratio: " + summary.cacheHitRatio);
System.out.println("Average query time: " + summary.averageTime + "ms");
```

### Slow Query Detection

```properties
kvt.query.slow_threshold=1000  # Log queries slower than 1 second
```

Check slow queries in logs:
```bash
grep "slow query" logs/hugegraph-server.log
```

### JMX Monitoring

Enable JMX:
```properties
kvt.metrics.export.jmx=true
```

Connect with JConsole or VisualVM to monitor:
- Cache statistics
- Transaction metrics
- Query performance
- Memory usage

## Troubleshooting

### Common Issues

#### 1. Native Library Loading Error

**Error**: `UnsatisfiedLinkError: no kvtjni in java.library.path`

**Solution**:
```bash
export LD_LIBRARY_PATH=$HUGEGRAPH_HOME/lib/native:$LD_LIBRARY_PATH
# Or set in JVM options
-Djava.library.path=$HUGEGRAPH_HOME/lib/native
```

#### 2. Transaction Timeout

**Error**: `Transaction timeout after 30000ms`

**Solution**:
```properties
# Increase timeout
kvt.transaction.timeout=60000
```

#### 3. Out of Memory

**Error**: `OutOfMemoryError: Direct buffer memory`

**Solution**:
```bash
# Increase direct memory in JVM options
-XX:MaxDirectMemorySize=4g
```

#### 4. Poor Query Performance

**Diagnosis**:
```java
// Check cache hit ratio
KVTQueryCache.CacheStatistics cacheStats = table.getCacheStatistics();
System.out.println("Hit ratio: " + cacheStats.getHitRatio());

// Check slow queries
Map<String, SlowQuery> slowQueries = stats.getSlowQueries();
```

**Solutions**:
- Increase cache size
- Create appropriate indexes
- Optimize query patterns

### Debug Mode

Enable debug logging:
```properties
kvt.debug.enabled=true
kvt.debug.log_level=DEBUG
kvt.debug.trace_transactions=true
```

### Performance Profiling

1. **Enable Statistics**:
```properties
kvt.query.stats.enabled=true
kvt.debug.dump_stats_on_close=true
```

2. **Analyze Logs**:
```bash
# Extract performance metrics
grep "PERF" logs/hugegraph-server.log | awk '{print $5, $7, $9}'
```

3. **Use Profiling Tools**:
```bash
# CPU profiling with async-profiler
./profiler.sh -d 30 -f profile.html <pid>
```

## API Reference

### KVT-Specific APIs

#### Transaction Management

```java
// Custom isolation level
KVTSession session = new KVTSessionV2(config, database);
session.beginTx(IsolationLevel.SERIALIZABLE, false);
// ... operations ...
session.commitTx();
```

#### Batch Operations

```java
// Enable batch mode
session.enableBatch(5000);

// Perform operations (automatically batched)
for (Entry entry : entries) {
    session.set(tableId, entry.key, entry.value);
}

// Flush remaining
session.flushBatch();
```

#### Cache Management

```java
// Clear cache
KVTQueryCache cache = table.getCache();
cache.clear();

// Get cache statistics
CacheStatistics stats = cache.getStatistics();
```

#### Index Operations

```java
// Create composite index
KVTIndexManager indexManager = new KVTIndexManager(session);
indexManager.buildCompositeIndex(indexId, Arrays.asList("field1", "field2"));

// Query using index
Iterator<Id> results = indexManager.queryIndex(
    IndexType.SECONDARY, indexId, indexValue, limit);
```

### Configuration API

```java
// Programmatic configuration
HugeConfig config = new PropertiesConfiguration();
config.setProperty(KVTConfig.CACHE_ENABLED.name(), true);
config.setProperty(KVTConfig.BATCH_SIZE.name(), 5000);
```

## Best Practices

1. **Use Appropriate Isolation Levels**: Use READ_COMMITTED for most operations, SERIALIZABLE only when necessary

2. **Batch Operations**: Always use batching for bulk operations

3. **Index Strategy**: Create indexes before loading data for better performance

4. **Cache Management**: Monitor cache hit ratio and adjust size accordingly

5. **Resource Cleanup**: Always close transactions and sessions properly

6. **Error Handling**: Implement retry logic for transient failures

7. **Monitoring**: Regularly monitor metrics and adjust configuration

## Migration Guide

### From RocksDB to KVT

1. **Export Data**:
```bash
bin/hugegraph-export.sh -g hugegraph -f backup.json
```

2. **Update Configuration**:
```properties
# Change backend
backend=kvt
# Remove RocksDB settings
# Add KVT settings
```

3. **Import Data**:
```bash
bin/hugegraph-import.sh -g hugegraph -f backup.json
```

## Support

- GitHub Issues: https://github.com/apache/incubator-hugegraph/issues
- Documentation: https://hugegraph.apache.org/docs/
- Community: dev@hugegraph.apache.org