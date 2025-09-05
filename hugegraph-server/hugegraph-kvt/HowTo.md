# HugeGraph KVT Backend - Complete Setup and Testing Guide

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Single-Node Setup](#single-node-setup)
3. [Running Basic Tests](#running-basic-tests)
4. [Performance Benchmarks](#performance-benchmarks)
5. [Stress Testing](#stress-testing)
6. [Multi-Node Deployment](#multi-node-deployment)
7. [Monitoring and Troubleshooting](#monitoring-and-troubleshooting)
8. [Common Issues and Solutions](#common-issues-and-solutions)

## Prerequisites

### System Requirements
- **OS**: Linux (Ubuntu 20.04+ or CentOS 7+)
- **RAM**: Minimum 8GB, Recommended 16GB+
- **Disk**: 50GB+ free space for data and logs
- **CPU**: 4+ cores recommended

### Software Requirements
- **Java**: JDK 11 (required)
- **Maven**: 3.5+ (for building)
- **GCC**: 4.8+ (for native libraries)
- **Git**: For cloning repositories

### Verify Prerequisites
```bash
# Check Java version
java -version
# Should show: openjdk version "11.x.x"

# Check Maven
mvn -version
# Should show: Apache Maven 3.x.x

# Check GCC
g++ --version
# Should show: g++ 4.8 or higher

# Set JAVA_HOME if not set
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
echo "export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64" >> ~/.bashrc
```

## Single-Node Setup

### Step 1: Build HugeGraph with KVT Backend

```bash
# Navigate to HugeGraph root directory
cd /home/lintaoz/work/hugegraph

# Build the entire HugeGraph project (skip tests for faster build)
mvn clean package -DskipTests

# Build KVT native libraries
cd hugegraph-server/hugegraph-kvt
./build-native.sh

# Verify native libraries are built
ls -la src/main/resources/native/
# Should see: libkvt.so and libkvtjni.so
```

### Step 2: Configure HugeGraph Server

```bash
# Navigate to the distribution directory
cd /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-dist/target
tar -xzf apache-hugegraph-server-incubating-*.tar.gz
cd apache-hugegraph-server-incubating-*/

# Create KVT configuration
cat > conf/graphs/kvt-graph.properties << 'EOF'
# Graph name
gremlin.graph=org.apache.hugegraph.HugeFactory

# Backend selection
backend=kvt
serializer=kvt

# KVT specific settings
kvt.data_path=/tmp/hugegraph-kvt/data
kvt.wal_path=/tmp/hugegraph-kvt/wal
kvt.cache_size=8388608
kvt.write_buffer_size=4194304
kvt.max_open_files=1000
kvt.compression=none

# Graph settings
store=hugegraph
graph.name=kvt-graph
vertex.check_customized_id_exist=false
EOF

# Update gremlin-server configuration
cat > conf/gremlin-server.yaml << 'EOF'
host: 127.0.0.1
port: 8182
evaluationTimeout: 30000
graphs: {
  kvt-graph: conf/graphs/kvt-graph.properties
}
scriptEngines: {
  gremlin-groovy: {
    plugins: {
      org.apache.hugegraph.plugin.HugeGraphGremlinPlugin: {},
      org.apache.tinkerpop.gremlin.server.jsr223.GremlinServerGremlinPlugin: {},
      org.apache.tinkerpop.gremlin.jsr223.ImportGremlinPlugin: {
        classImports: [
          java.lang.Math,
          org.apache.hugegraph.backend.id.IdGenerator,
          org.apache.hugegraph.type.define.Directions,
          org.apache.hugegraph.type.define.NodeRole,
          org.apache.hugegraph.traversal.algorithm.*
        ],
        methodImports: [java.lang.Math#*]
      }
    }
  }
}
serializers:
  - { className: org.apache.tinkerpop.gremlin.driver.ser.GraphBinaryMessageSerializerV1 }
  - { className: org.apache.tinkerpop.gremlin.driver.ser.GraphSONMessageSerializerV3d0 }
metrics: {
  consoleReporter: {enabled: false, interval: 180000},
  csvReporter: {enabled: false, interval: 180000, fileName: /tmp/gremlin-server-metrics.csv},
  jmxReporter: {enabled: false},
  slf4jReporter: {enabled: false, interval: 180000}
}
maxInitialLineLength: 4096
maxHeaderSize: 8192
maxChunkSize: 8192
maxContentLength: 65536
maxAccumulationBufferComponents: 1024
resultIterationBatchSize: 64
EOF

# Copy KVT libraries to lib directory
cp /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-kvt/src/main/resources/native/*.so lib/
cp /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-kvt/target/*.jar lib/

# Set library path
export LD_LIBRARY_PATH=$PWD/lib:$LD_LIBRARY_PATH
```

### Step 3: Start HugeGraph Server

```bash
# Start the server
bin/start-hugegraph.sh

# Check if server is running
bin/checksocket.sh
# Should show: "The port 8182 is in use"

# Check logs for any errors
tail -f logs/hugegraph-server.log

# To stop the server (when needed)
# bin/stop-hugegraph.sh
```

### Step 4: Initialize Graph Schema

```bash
# Create a simple test schema using Gremlin console
bin/gremlin-console.sh << 'EOF'
:remote connect tinkerpop.server conf/remote.yaml
:remote console

// Create property keys
graph.schema().propertyKey("name").asText().ifNotExist().create()
graph.schema().propertyKey("age").asInt().ifNotExist().create()
graph.schema().propertyKey("city").asText().ifNotExist().create()
graph.schema().propertyKey("weight").asDouble().ifNotExist().create()

// Create vertex labels
graph.schema().vertexLabel("person").properties("name", "age", "city").primaryKeys("name").ifNotExist().create()
graph.schema().vertexLabel("software").properties("name").primaryKeys("name").ifNotExist().create()

// Create edge labels
graph.schema().edgeLabel("knows").sourceLabel("person").targetLabel("person").properties("weight").ifNotExist().create()
graph.schema().edgeLabel("created").sourceLabel("person").targetLabel("software").properties("weight").ifNotExist().create()

// Add some test data
g.addV("person").property("name", "marko").property("age", 29).property("city", "Beijing")
g.addV("person").property("name", "vadas").property("age", 27).property("city", "Shanghai")
g.addV("person").property("name", "josh").property("age", 32).property("city", "Shenzhen")
g.addV("person").property("name", "peter").property("age", 35).property("city", "Guangzhou")
g.addV("software").property("name", "lop")
g.addV("software").property("name", "ripple")

g.V().has("person", "name", "marko").as("a").V().has("person", "name", "vadas").addE("knows").from("a").property("weight", 0.5)
g.V().has("person", "name", "marko").as("a").V().has("person", "name", "josh").addE("knows").from("a").property("weight", 1.0)
g.V().has("person", "name", "marko").as("a").V().has("software", "name", "lop").addE("created").from("a").property("weight", 0.4)
g.V().has("person", "name", "josh").as("a").V().has("software", "name", "lop").addE("created").from("a").property("weight", 0.4)
g.V().has("person", "name", "josh").as("a").V().has("software", "name", "ripple").addE("created").from("a").property("weight", 1.0)
g.V().has("person", "name", "peter").as("a").V().has("software", "name", "lop").addE("created").from("a").property("weight", 0.2)

// Verify data
g.V().count()
g.E().count()
:exit
EOF
```

## Running Basic Tests

### KVT Unit Tests

```bash
cd /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-kvt

# Run simple connectivity test
javac -cp "src/main/java:target/classes" src/test/java/SimpleKVTTest.java -d target/test-classes
java -Djava.library.path=src/main/resources/native \
     -cp "src/main/java:target/classes:target/test-classes" SimpleKVTTest

# Run batch operations test
javac -cp "src/main/java:target/classes" src/test/java/TestBatchOperations.java -d target/test-classes
java -Djava.library.path=src/main/resources/native \
     -cp "src/main/java:target/classes:target/test-classes" TestBatchOperations

# Run all Maven tests
mvn test -DargLine="-Djava.library.path=src/main/resources/native"
```

### Graph Functional Tests

```bash
cd /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-test

# Run core tests with KVT backend
mvn test -Dbackend=kvt -Dtest=*CoreTest

# Run API tests
mvn test -Dbackend=kvt -Dtest=*ApiTest

# Run specific test suites
mvn test -Dbackend=kvt -Dtest=VertexCoreTest
mvn test -Dbackend=kvt -Dtest=EdgeCoreTest
mvn test -Dbackend=kvt -Dtest=PropertyCoreTest
```

## Performance Benchmarks

### Install Benchmark Tools

```bash
# Install Apache JMeter for load testing
cd /tmp
wget https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.5.tgz
tar -xzf apache-jmeter-5.5.tgz
export JMETER_HOME=/tmp/apache-jmeter-5.5
export PATH=$JMETER_HOME/bin:$PATH
```

### Download Test Datasets

```bash
# Create benchmark data directory
mkdir -p /home/lintaoz/work/hugegraph/benchmark-data
cd /home/lintaoz/work/hugegraph/benchmark-data

# Download sample datasets

# 1. Small dataset (for quick tests)
wget https://snap.stanford.edu/data/facebook_combined.txt.gz
gunzip facebook_combined.txt.gz
# ~88K edges, ~4K vertices

# 2. Medium dataset (Enron email network)
wget https://snap.stanford.edu/data/email-Enron.txt.gz
gunzip email-Enron.txt.gz
# ~367K edges, ~37K vertices

# 3. Large dataset (Amazon product co-purchasing network)
wget https://snap.stanford.edu/data/amazon0601.txt.gz
gunzip amazon0601.txt.gz
# ~3.3M edges, ~400K vertices
```

### Import Test Data

```bash
cd /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-dist/target/apache-hugegraph-server-incubating-*/

# Create data import script
cat > bin/import-data.groovy << 'EOF'
import java.io.File

// Connect to graph
graph = graph.traversal().getGraph()
g = graph.traversal()

// Import function
def importEdgeList(String filename, String vertexLabel, String edgeLabel) {
    File file = new File(filename)
    int count = 0
    int batchSize = 5000
    def batch = []
    
    file.eachLine { line ->
        if (line.startsWith("#")) return
        
        def parts = line.split("\\s+")
        if (parts.length >= 2) {
            batch.add([parts[0], parts[1]])
            
            if (batch.size() >= batchSize) {
                processBatch(batch, vertexLabel, edgeLabel)
                batch.clear()
                count += batchSize
                println("Imported ${count} edges...")
            }
        }
    }
    
    if (!batch.isEmpty()) {
        processBatch(batch, vertexLabel, edgeLabel)
        count += batch.size()
    }
    
    println("Total imported: ${count} edges")
}

def processBatch(batch, vertexLabel, edgeLabel) {
    batch.each { edge ->
        def v1 = g.V().has(vertexLabel, "name", edge[0]).tryNext().orElseGet {
            g.addV(vertexLabel).property("name", edge[0]).next()
        }
        def v2 = g.V().has(vertexLabel, "name", edge[1]).tryNext().orElseGet {
            g.addV(vertexLabel).property("name", edge[1]).next()
        }
        g.V(v1).as("a").V(v2).addE(edgeLabel).from("a").iterate()
    }
    g.tx().commit()
}

// Import data
importEdgeList("/home/lintaoz/work/hugegraph/benchmark-data/facebook_combined.txt", "user", "friend")
EOF

# Run import
bin/gremlin-console.sh -e bin/import-data.groovy
```

### Run Performance Benchmarks

```bash
# Create benchmark script
cat > bin/benchmark.groovy << 'EOF'
import java.util.concurrent.*
import java.util.concurrent.atomic.*

// Configuration
int numThreads = 10
int numOperations = 10000
int warmupOperations = 1000

// Metrics
AtomicLong totalOps = new AtomicLong(0)
AtomicLong totalTime = new AtomicLong(0)

// Connect to graph
graph = graph.traversal().getGraph()

// Benchmark functions
def benchmarkVertexCreation(int ops) {
    long start = System.currentTimeMillis()
    for (int i = 0; i < ops; i++) {
        graph.addVertex("benchmark_vertex").property("id", "bench_" + i).property("timestamp", System.currentTimeMillis())
        if (i % 100 == 0) graph.tx().commit()
    }
    graph.tx().commit()
    return System.currentTimeMillis() - start
}

def benchmarkVertexQuery(int ops) {
    def g = graph.traversal()
    long start = System.currentTimeMillis()
    for (int i = 0; i < ops; i++) {
        g.V().has("name", "bench_" + (i % 1000)).tryNext()
    }
    return System.currentTimeMillis() - start
}

def benchmarkTraversal(int ops) {
    def g = graph.traversal()
    long start = System.currentTimeMillis()
    for (int i = 0; i < ops; i++) {
        g.V().limit(10).out().out().values("name").toList()
    }
    return System.currentTimeMillis() - start
}

// Run benchmarks
println("Starting benchmark...")
println("Threads: ${numThreads}")
println("Operations per thread: ${numOperations}")

// Warmup
println("\nWarmup phase...")
benchmarkVertexCreation(warmupOperations)

// Actual benchmark
println("\nBenchmark phase...")

ExecutorService executor = Executors.newFixedThreadPool(numThreads)
CountDownLatch latch = new CountDownLatch(numThreads)

long startTime = System.currentTimeMillis()

for (int t = 0; t < numThreads; t++) {
    final int threadId = t
    executor.submit {
        try {
            // Mix of operations
            for (int i = 0; i < numOperations; i++) {
                if (i % 3 == 0) {
                    benchmarkVertexCreation(1)
                } else if (i % 3 == 1) {
                    benchmarkVertexQuery(1)
                } else {
                    benchmarkTraversal(1)
                }
                totalOps.incrementAndGet()
            }
        } finally {
            latch.countDown()
        }
    }
}

latch.await()
executor.shutdown()

long endTime = System.currentTimeMillis()
long duration = endTime - startTime

// Results
println("\n=== Benchmark Results ===")
println("Total time: ${duration} ms")
println("Total operations: ${totalOps.get()}")
println("Throughput: ${totalOps.get() * 1000.0 / duration} ops/sec")
println("Average latency: ${duration / (double)totalOps.get()} ms/op")
EOF

# Run benchmark
bin/gremlin-console.sh -e bin/benchmark.groovy
```

## Stress Testing

### Run KVT Stress Test

```bash
cd /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-kvt

# Compile stress test
javac -cp "src/main/java:target/classes" src/test/java/KVTStressTest.java -d target/test-classes

# Run with different modes

# 1. Non-interleaved mode (sequential transactions)
java -Djava.library.path=src/main/resources/native \
     -cp "src/main/java:target/classes:target/test-classes" \
     KVTStressTest 1000 non-interleaved

# 2. Interleaved mode (concurrent transactions, single thread)
java -Djava.library.path=src/main/resources/native \
     -cp "src/main/java:target/classes:target/test-classes" \
     KVTStressTest 500 interleaved

# 3. Multi-threaded mode (true concurrency)
java -Djava.library.path=src/main/resources/native \
     -cp "src/main/java:target/classes:target/test-classes" \
     KVTStressTest 200 multi-threaded

# Long-running stress test
java -Djava.library.path=src/main/resources/native \
     -Xmx4g -XX:+UseG1GC \
     -cp "src/main/java:target/classes:target/test-classes" \
     KVTStressTest 10000 multi-threaded
```

### Graph-Level Stress Test

```bash
cd /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-dist/target/apache-hugegraph-server-incubating-*/

# Create graph stress test
cat > bin/graph-stress.groovy << 'EOF'
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.Random

// Configuration
int numThreads = 20
int duration = 60000 // 60 seconds
int checkInterval = 10000 // Check every 10 seconds

// Metrics
AtomicLong successOps = new AtomicLong(0)
AtomicLong failedOps = new AtomicLong(0)
AtomicBoolean running = new AtomicBoolean(true)

graph = graph.traversal().getGraph()
Random rand = new Random()

// Operation types
def createVertex() {
    try {
        graph.addVertex("stress_test")
            .property("id", UUID.randomUUID().toString())
            .property("value", rand.nextInt(1000))
            .property("timestamp", System.currentTimeMillis())
        graph.tx().commit()
        successOps.incrementAndGet()
    } catch (Exception e) {
        graph.tx().rollback()
        failedOps.incrementAndGet()
    }
}

def createEdge() {
    try {
        def g = graph.traversal()
        def vertices = g.V().limit(2).toList()
        if (vertices.size() == 2) {
            g.V(vertices[0]).as("a").V(vertices[1]).addE("stress_edge").from("a").iterate()
            graph.tx().commit()
            successOps.incrementAndGet()
        }
    } catch (Exception e) {
        graph.tx().rollback()
        failedOps.incrementAndGet()
    }
}

def queryVertex() {
    try {
        def g = graph.traversal()
        g.V().has("value", rand.nextInt(1000)).limit(10).toList()
        successOps.incrementAndGet()
    } catch (Exception e) {
        failedOps.incrementAndGet()
    }
}

def traversalQuery() {
    try {
        def g = graph.traversal()
        g.V().limit(5).out().out().values().toList()
        successOps.incrementAndGet()
    } catch (Exception e) {
        failedOps.incrementAndGet()
    }
}

// Run stress test
println("Starting graph stress test...")
println("Threads: ${numThreads}")
println("Duration: ${duration/1000} seconds")

ExecutorService executor = Executors.newFixedThreadPool(numThreads)
ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor()

// Monitor task
monitor.scheduleAtFixedRate({
    println("Progress - Success: ${successOps.get()}, Failed: ${failedOps.get()}, Rate: ${successOps.get()*1000.0/checkInterval} ops/sec")
}, checkInterval, checkInterval, TimeUnit.MILLISECONDS)

// Worker threads
for (int t = 0; t < numThreads; t++) {
    executor.submit {
        while (running.get()) {
            int op = rand.nextInt(4)
            switch(op) {
                case 0: createVertex(); break
                case 1: createEdge(); break
                case 2: queryVertex(); break
                case 3: traversalQuery(); break
            }
        }
    }
}

// Run for specified duration
Thread.sleep(duration)
running.set(false)

// Shutdown
executor.shutdown()
monitor.shutdown()
executor.awaitTermination(10, TimeUnit.SECONDS)

// Final results
println("\n=== Stress Test Results ===")
println("Total successful operations: ${successOps.get()}")
println("Total failed operations: ${failedOps.get()}")
println("Success rate: ${successOps.get() * 100.0 / (successOps.get() + failedOps.get())}%")
println("Average throughput: ${successOps.get() * 1000.0 / duration} ops/sec")
EOF

# Run stress test
bin/gremlin-console.sh -e bin/graph-stress.groovy
```

## Multi-Node Deployment

### Prerequisites for Multi-Node
- Multiple machines with network connectivity
- Shared storage or distributed file system (optional)
- Load balancer (optional)

### Node Configuration

#### Master Node Setup (node1.example.com)

```bash
# On master node
cd /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-dist/target
tar -xzf apache-hugegraph-server-incubating-*.tar.gz
cd apache-hugegraph-server-incubating-*/

# Configure for master node
cat > conf/graphs/kvt-cluster.properties << 'EOF'
gremlin.graph=org.apache.hugegraph.HugeFactory
backend=kvt
serializer=kvt

# Cluster settings
cluster.role=master
cluster.id=node1
cluster.members=node1.example.com:8090,node2.example.com:8090,node3.example.com:8090

# KVT settings with distributed paths
kvt.data_path=/shared/hugegraph-kvt/node1/data
kvt.wal_path=/shared/hugegraph-kvt/node1/wal
kvt.cache_size=16777216
kvt.write_buffer_size=8388608

store=hugegraph
graph.name=kvt-cluster
EOF

# Update server configuration for cluster
sed -i 's/host: 127.0.0.1/host: 0.0.0.0/' conf/gremlin-server.yaml
sed -i 's/kvt-graph/kvt-cluster/' conf/gremlin-server.yaml

# Start master node
bin/start-hugegraph.sh
```

#### Worker Node Setup (node2 and node3)

```bash
# On each worker node
cd /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-dist/target
tar -xzf apache-hugegraph-server-incubating-*.tar.gz
cd apache-hugegraph-server-incubating-*/

# Configure for worker node (adjust node ID)
cat > conf/graphs/kvt-cluster.properties << 'EOF'
gremlin.graph=org.apache.hugegraph.HugeFactory
backend=kvt
serializer=kvt

# Cluster settings (change cluster.id for each node)
cluster.role=worker
cluster.id=node2  # Change to node3 for third node
cluster.members=node1.example.com:8090,node2.example.com:8090,node3.example.com:8090

# KVT settings with distributed paths
kvt.data_path=/shared/hugegraph-kvt/node2/data  # Change path for each node
kvt.wal_path=/shared/hugegraph-kvt/node2/wal
kvt.cache_size=16777216
kvt.write_buffer_size=8388608

store=hugegraph
graph.name=kvt-cluster
EOF

# Update server configuration
sed -i 's/host: 127.0.0.1/host: 0.0.0.0/' conf/gremlin-server.yaml
sed -i 's/kvt-graph/kvt-cluster/' conf/gremlin-server.yaml

# Start worker node
bin/start-hugegraph.sh
```

### Load Balancer Setup (HAProxy)

```bash
# Install HAProxy
sudo apt-get install haproxy

# Configure HAProxy
sudo cat > /etc/haproxy/haproxy.cfg << 'EOF'
global
    daemon
    maxconn 4096

defaults
    mode tcp
    timeout connect 5000ms
    timeout client 50000ms
    timeout server 50000ms

frontend hugegraph_frontend
    bind *:8182
    default_backend hugegraph_backend

backend hugegraph_backend
    balance roundrobin
    server node1 node1.example.com:8182 check
    server node2 node2.example.com:8182 check
    server node3 node3.example.com:8182 check
EOF

# Start HAProxy
sudo systemctl restart haproxy
sudo systemctl enable haproxy
```

### Distributed Testing

```bash
# Test cluster connectivity from any node
bin/gremlin-console.sh << 'EOF'
:remote connect tinkerpop.server conf/remote.yaml
:remote console

// Check cluster status
graph.variables().get("cluster.status")

// Distributed write test
(1..1000).each { i ->
    g.addV("distributed_test").property("id", "dist_" + i).property("node", InetAddress.getLocalHost().getHostName())
    if (i % 100 == 0) {
        g.tx().commit()
        println("Created ${i} vertices")
    }
}
g.tx().commit()

// Verify distribution
g.V().has("label", "distributed_test").values("node").groupCount()
:exit
EOF
```

## Monitoring and Troubleshooting

### Enable Monitoring

```bash
# Enable JMX monitoring
export JAVA_OPTIONS="-Dcom.sun.management.jmxremote \
    -Dcom.sun.management.jmxremote.port=9999 \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false"

# Start server with monitoring
bin/start-hugegraph.sh

# Connect with JConsole or VisualVM
jconsole localhost:9999
```

### Log Analysis

```bash
# Check server logs
tail -f logs/hugegraph-server.log

# Check Gremlin server logs
tail -f logs/gremlin-server.log

# Search for errors
grep -i error logs/*.log

# KVT specific debug logging
echo "log4j.logger.org.apache.hugegraph.backend.store.kvt=DEBUG" >> conf/log4j2.xml
```

### Performance Monitoring Script

```bash
cat > bin/monitor.sh << 'EOF'
#!/bin/bash

while true; do
    clear
    echo "=== HugeGraph KVT Monitor ==="
    echo "Time: $(date)"
    echo ""
    
    # Process info
    PID=$(pgrep -f hugegraph)
    if [ -n "$PID" ]; then
        echo "Server PID: $PID"
        echo "CPU: $(ps -p $PID -o %cpu= | xargs)"
        echo "Memory: $(ps -p $PID -o rss= | awk '{print $1/1024 " MB"}')"
    else
        echo "Server not running"
    fi
    echo ""
    
    # Disk usage
    echo "Disk Usage:"
    df -h /tmp/hugegraph-kvt/
    echo ""
    
    # Connection count
    echo "Connections: $(netstat -an | grep :8182 | grep ESTABLISHED | wc -l)"
    echo ""
    
    # Recent errors
    echo "Recent Errors:"
    tail -5 logs/hugegraph-server.log | grep -i error || echo "No recent errors"
    
    sleep 5
done
EOF

chmod +x bin/monitor.sh
./bin/monitor.sh
```

## Common Issues and Solutions

### Issue 1: Native Library Not Found
```
java.lang.UnsatisfiedLinkError: no kvtjni in java.library.path
```

**Solution:**
```bash
# Check library exists
ls -la lib/*.so

# Set library path explicitly
export LD_LIBRARY_PATH=$PWD/lib:$LD_LIBRARY_PATH

# Or add to Java options
export JAVA_OPTIONS="-Djava.library.path=$PWD/lib"
```

### Issue 2: Transaction Conflicts
```
KVTError: TRANSACTION_HAS_STALE_DATA
```

**Solution:**
```bash
# Reduce concurrent transactions
# Adjust in your application code or configuration
kvt.max_concurrent_transactions=10

# Enable retry logic in application
```

### Issue 3: Out of Memory
```
java.lang.OutOfMemoryError: Java heap space
```

**Solution:**
```bash
# Increase heap size in conf/hugegraph-server.env
export JAVA_OPTIONS="-Xms4g -Xmx8g -XX:+UseG1GC"

# Restart server
bin/stop-hugegraph.sh
bin/start-hugegraph.sh
```

### Issue 4: Slow Performance
**Diagnosis:**
```bash
# Check I/O wait
iostat -x 5

# Check memory usage
free -h

# Profile with JVM tools
jmap -heap $PID
```

**Solutions:**
```bash
# Tune KVT cache
kvt.cache_size=33554432  # 32MB
kvt.write_buffer_size=16777216  # 16MB

# Use SSD for data directories
# Move to faster storage
mv /tmp/hugegraph-kvt /ssd/hugegraph-kvt
ln -s /ssd/hugegraph-kvt /tmp/hugegraph-kvt

# Optimize batch sizes in application
```

### Issue 5: Port Already in Use
```
java.net.BindException: Address already in use
```

**Solution:**
```bash
# Find process using port
lsof -i :8182

# Kill if necessary
kill -9 $(lsof -t -i :8182)

# Or change port in conf/gremlin-server.yaml
```

## Performance Tuning Tips

### JVM Tuning
```bash
# Optimal JVM settings for production
export JAVA_OPTIONS="-server \
    -Xms8g -Xmx8g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+ParallelRefProcEnabled \
    -XX:+DisableExplicitGC \
    -XX:+AlwaysPreTouch \
    -XX:+UnlockExperimentalVMOptions \
    -XX:+UseNUMA"
```

### KVT Backend Tuning
```properties
# For write-heavy workloads
kvt.write_buffer_size=33554432  # 32MB
kvt.max_write_threads=8

# For read-heavy workloads
kvt.cache_size=134217728  # 128MB
kvt.max_read_threads=16

# For mixed workloads
kvt.cache_size=67108864  # 64MB
kvt.write_buffer_size=16777216  # 16MB
kvt.compression=lz4  # Fast compression
```

### Network Tuning (Linux)
```bash
# Add to /etc/sysctl.conf
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.core.netdev_max_backlog = 65535
net.ipv4.tcp_fin_timeout = 30
net.ipv4.tcp_keepalive_time = 300

# Apply settings
sudo sysctl -p
```

## Useful Commands Reference

```bash
# Server Management
bin/start-hugegraph.sh              # Start server
bin/stop-hugegraph.sh               # Stop server
bin/checksocket.sh                  # Check if running

# Testing
mvn test -Dbackend=kvt              # Run all tests
java -cp ... SimpleKVTTest          # Run specific test

# Monitoring
tail -f logs/hugegraph-server.log   # Watch logs
jconsole localhost:9999             # JMX monitoring
top -p $(pgrep hugegraph)           # Process monitoring

# Data Management
bin/gremlin-console.sh              # Interactive console
bin/export-graph.sh                 # Export data
bin/import-graph.sh                 # Import data

# Troubleshooting
lsof -i :8182                       # Check port usage
netstat -an | grep 8182             # Network connections
strace -p $PID                      # System call trace
```

## Next Steps

1. **Security**: Enable authentication and SSL/TLS
2. **Backup**: Implement regular backup strategy
3. **Monitoring**: Set up Prometheus/Grafana dashboards
4. **Scaling**: Add more nodes as needed
5. **Optimization**: Profile and tune based on workload

For more information, refer to:
- [HugeGraph Documentation](https://hugegraph.apache.org/docs/)
- [KVT Backend README](KVT_README.md)
- [Apache TinkerPop Documentation](https://tinkerpop.apache.org/docs/)