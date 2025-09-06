# HugeGraph KVT Backend - Quick Start Guide

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Quick Start (5 minutes)](#quick-start-5-minutes)
3. [Running Tests](#running-tests)
4. [Using HugeGraph with KVT](#using-hugegraph-with-kvt)
5. [Performance Testing](#performance-testing)
6. [Troubleshooting](#troubleshooting)

## Prerequisites

### Required Software
- **Java 11** (OpenJDK or Oracle JDK)
- **Maven 3.5+**
- **GCC/G++ 4.8+** (for building native libraries)

### Quick Check
```bash
# Verify Java 11
java -version
# Should show: openjdk version "11.x.x"

# Verify Maven
mvn -version
# Should show: Apache Maven 3.x.x

# Verify C++ compiler
g++ --version
# Should show: g++ 4.8 or higher

# Set JAVA_HOME if needed
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
```

## Quick Start (5 minutes)

### Step 1: Build KVT Backend
```bash
# Navigate to KVT directory
cd hugegraph-server/hugegraph-kvt

# Build the C++ memory implementation
cd kvt
g++ -c -fPIC -g -O2 -std=c++11 kvt_memory.cpp -o kvt_memory.o
cd ..

# Build Java components and JNI bridge
mvn clean compile

# Verify native library was built
ls -la target/native/
# Should see: libkvtjni.so (Linux) or libkvtjni.dylib (macOS)
```

### Step 2: Run Quick Test
```bash
# Test basic KVT operations
java -Djava.library.path=target/native -cp target/classes SimpleKVTTest

# Expected output:
# ✓ KVT initialized
# ✓ Transaction started
# ✓ Data written and read successfully
# ✓ All tests passed!
```

### Step 3: Run Maven Tests
```bash
# Run all KVT tests
mvn test

# Run specific test
mvn test -Dtest=TestPrefixScanOptimization

# Expected: BUILD SUCCESS
```

## Running Tests

### Unit Tests
```bash
cd hugegraph-server/hugegraph-kvt

# Run all tests
mvn test

# Run with verbose output
mvn test -X

# Run specific test class
mvn test -Dtest=ComprehensivePrefixScanTest
mvn test -Dtest=KVTBasicTest
```

### Integration Tests
```bash
# Run HugeGraph integration test
mvn test -Dtest=HugeGraphKVTIntegrationTest

# Run stress test (takes longer)
mvn test -Dtest=KVTStressTest
```

### Manual Test Programs
```bash
# Compile and run prefix scan optimization test
javac -cp target/classes TestPrefixScanOptimization.java
java -Djava.library.path=target/native -cp .:target/classes TestPrefixScanOptimization

# Compile and run comprehensive test
javac -cp target/classes ComprehensivePrefixScanTest.java
java -Djava.library.path=target/native -cp .:target/classes ComprehensivePrefixScanTest
```

## Using HugeGraph with KVT

### Step 1: Build HugeGraph Server
```bash
# From HugeGraph root directory
cd ../..  # Go to hugegraph root
mvn clean package -DskipTests

# Extract the distribution
cd hugegraph-server/hugegraph-dist/target
tar -xzf apache-hugegraph-server-incubating-*.tar.gz
cd apache-hugegraph-server-incubating-*/
```

### Step 2: Configure KVT Backend
```bash
# Create KVT configuration
cat > conf/graphs/kvt-test.properties << 'EOF'
gremlin.graph=org.apache.hugegraph.HugeFactory
backend=kvt
serializer=binary
store=hugegraph
graph.name=kvt-test
vertex.check_customized_id_exist=false
EOF

# Update server configuration to use KVT
sed -i 's/hugegraph: conf\/graphs\/hugegraph.properties/kvt-test: conf\/graphs\/kvt-test.properties/' conf/gremlin-server.yaml

# Copy KVT libraries
cp ../../hugegraph-kvt/target/hugegraph-kvt-*.jar lib/
cp ../../hugegraph-kvt/target/native/*.so lib/

# Set library path
export LD_LIBRARY_PATH=$PWD/lib:$LD_LIBRARY_PATH
```

### Step 3: Start HugeGraph Server
```bash
# Start server
bin/start-hugegraph.sh

# Check if running
bin/checksocket.sh
# Should show: "The port 8182 is in use"

# Check logs
tail -f logs/hugegraph-server.log

# Stop server (when done)
# bin/stop-hugegraph.sh
```

### Step 4: Test with Gremlin Console
```bash
# Start Gremlin console
bin/gremlin-console.sh

# In the console:
:remote connect tinkerpop.server conf/remote.yaml
:remote console

// Create schema
graph.schema().propertyKey("name").asText().ifNotExist().create()
graph.schema().propertyKey("age").asInt().ifNotExist().create()
graph.schema().vertexLabel("person").properties("name", "age").primaryKeys("name").ifNotExist().create()
graph.schema().edgeLabel("knows").sourceLabel("person").targetLabel("person").ifNotExist().create()

// Add test data
g.addV("person").property("name", "Alice").property("age", 30)
g.addV("person").property("name", "Bob").property("age", 25)
g.V().has("person", "name", "Alice").as("a").V().has("person", "name", "Bob").addE("knows").from("a")

// Query data
g.V().count()  // Should return 2
g.E().count()  // Should return 1
g.V().has("name", "Alice").out("knows").values("name")  // Should return "Bob"

:exit
```

## Performance Testing

### Quick Performance Test
```bash
cd hugegraph-server/hugegraph-kvt

# Create and run performance test
cat > TestPerformance.java << 'EOF'
import org.apache.hugegraph.backend.store.kvt.KVTNative;

public class TestPerformance {
    public static void main(String[] args) {
        // Initialize
        KVTNative.nativeInitialize();
        
        // Create table
        Object[] tableResult = KVTNative.nativeCreateTable("perf_test", "range");
        long tableId = (long)tableResult[1];
        
        // Benchmark writes
        long start = System.currentTimeMillis();
        Object[] txResult = KVTNative.nativeStartTransaction();
        long txId = (long)txResult[1];
        
        for (int i = 0; i < 10000; i++) {
            String key = "key_" + i;
            String value = "value_" + i;
            KVTNative.nativeSet(txId, tableId, key.getBytes(), value.getBytes());
        }
        
        KVTNative.nativeCommitTransaction(txId);
        long writeTime = System.currentTimeMillis() - start;
        
        // Benchmark reads
        start = System.currentTimeMillis();
        txResult = KVTNative.nativeStartTransaction();
        txId = (long)txResult[1];
        
        for (int i = 0; i < 10000; i++) {
            String key = "key_" + i;
            KVTNative.nativeGet(txId, tableId, key.getBytes());
        }
        
        KVTNative.nativeCommitTransaction(txId);
        long readTime = System.currentTimeMillis() - start;
        
        // Results
        System.out.println("=== Performance Results ===");
        System.out.println("Write 10K records: " + writeTime + " ms");
        System.out.println("Write throughput: " + (10000 * 1000 / writeTime) + " ops/sec");
        System.out.println("Read 10K records: " + readTime + " ms");
        System.out.println("Read throughput: " + (10000 * 1000 / readTime) + " ops/sec");
        
        KVTNative.nativeShutdown();
    }
}
EOF

javac -cp target/classes TestPerformance.java
java -Djava.library.path=target/native -cp .:target/classes TestPerformance
```

### Prefix Scan Performance Test
```bash
# This test is already included in the test suite
mvn test -Dtest=TestPrefixScanOptimization

# Or run the comprehensive test which includes performance benchmarks
mvn test -Dtest=ComprehensivePrefixScanTest
```

## Troubleshooting

### Common Issues and Solutions

#### 1. Native Library Not Found
```
Error: java.lang.UnsatisfiedLinkError: no kvtjni in java.library.path
```

**Solution:**
```bash
# Rebuild native library
mvn clean compile

# Set library path explicitly
export LD_LIBRARY_PATH=$(pwd)/target/native:$LD_LIBRARY_PATH

# Or use Java option
java -Djava.library.path=target/native YourClass
```

#### 2. JNI Header Not Found During Build
```
Error: jni.h: No such file or directory
```

**Solution:**
```bash
# Set JAVA_HOME correctly
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64

# Verify
ls $JAVA_HOME/include/jni.h

# Rebuild
mvn clean compile
```

#### 3. Test Failures
```
Error: Test failed with error code 22 (SCAN_LIMIT_REACHED)
```

**Solution:**
This is not an error - it means the scan reached the configured limit. The tests handle this correctly.

#### 4. Out of Memory
```
Error: java.lang.OutOfMemoryError: Java heap space
```

**Solution:**
```bash
# Increase heap size
export MAVEN_OPTS="-Xmx2g"
mvn test

# Or for running directly
java -Xmx2g -Djava.library.path=target/native -cp target/classes YourClass
```

#### 5. C++ Compilation Errors
```
Error: kvt_memory.cpp: error: 'xyz' was not declared
```

**Solution:**
```bash
# Ensure you have C++11 support
g++ --version  # Should be 4.8+

# Compile with correct flags
cd kvt
g++ -c -fPIC -g -O2 -std=c++11 kvt_memory.cpp -o kvt_memory.o
cd ..
mvn clean compile
```

### Debug Mode

Enable debug logging for more information:
```bash
# Add to your Java command
-Dlog4j.logger.org.apache.hugegraph.backend.store.kvt=DEBUG

# Or in Maven
mvn test -Dlog4j.logger.org.apache.hugegraph.backend.store.kvt=DEBUG
```

### Verify Installation

Run this verification script:
```bash
cat > verify.sh << 'EOF'
#!/bin/bash
echo "=== KVT Installation Verification ==="

# Check Java
echo -n "Java 11: "
if java -version 2>&1 | grep -q "version \"11"; then
    echo "✓"
else
    echo "✗ (Java 11 required)"
fi

# Check Maven
echo -n "Maven: "
if mvn -version > /dev/null 2>&1; then
    echo "✓"
else
    echo "✗ (Maven required)"
fi

# Check G++
echo -n "G++: "
if g++ --version > /dev/null 2>&1; then
    echo "✓"
else
    echo "✗ (G++ required)"
fi

# Check KVT object file
echo -n "KVT object: "
if [ -f kvt/kvt_memory.o ]; then
    echo "✓"
else
    echo "✗ (Run: cd kvt && g++ -c -fPIC -g -O2 -std=c++11 kvt_memory.cpp)"
fi

# Check native library
echo -n "Native library: "
if [ -f target/native/libkvtjni.so ] || [ -f target/native/libkvtjni.dylib ]; then
    echo "✓"
else
    echo "✗ (Run: mvn clean compile)"
fi

# Check Java classes
echo -n "Java classes: "
if [ -d target/classes/org/apache/hugegraph/backend/store/kvt ]; then
    echo "✓"
else
    echo "✗ (Run: mvn compile)"
fi

echo ""
echo "=== Quick Test ==="
if [ -f target/native/libkvtjni.so ] || [ -f target/native/libkvtjni.dylib ]; then
    echo "Running SimpleKVTTest..."
    java -Djava.library.path=target/native -cp target/classes SimpleKVTTest 2>/dev/null
    if [ $? -eq 0 ]; then
        echo "✓ KVT is working correctly!"
    else
        echo "✗ Test failed. Check error messages above."
    fi
else
    echo "Cannot run test - native library not found"
fi
EOF

chmod +x verify.sh
./verify.sh
```

## Next Steps

1. **Explore the API**: Look at `src/main/java/org/apache/hugegraph/backend/store/kvt/KVTNative.java` for available operations

2. **Run More Tests**: 
   ```bash
   mvn test  # Run all tests
   ```

3. **Read Documentation**:
   - [KVT_README.md](KVT_README.md) - Detailed KVT documentation
   - [BUG_STATUS_REPORT.md](BUG_STATUS_REPORT.md) - Current status and known issues
   - [KVT_INTEGRATION_GUIDE.md](KVT_INTEGRATION_GUIDE.md) - Integration details

4. **Performance Tuning**: See KVT_README.md for configuration options

5. **Production Deployment**: Currently KVT uses an in-memory implementation (kvt_memory.o). For production, you'll need a persistent KVT implementation.

## Support

For issues or questions:
1. Check the [Troubleshooting](#troubleshooting) section
2. Review test output for error messages
3. Enable debug logging for detailed information
4. Check HugeGraph documentation at https://hugegraph.apache.org/docs/