# KVT Module Test Execution Plan

## Prerequisites

### 1. Native Library Setup
The KVT module requires the native C++ library (`libkvt.so`) to be available:

```bash
# Check if the native library exists
ls -la /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-kvt/src/main/resources/native/libkvt.so

# If not present, options:
# a) Use the prebuilt library (if available)
# b) Build from source (requires JNI headers):
export JAVA_HOME=/path/to/java
cd hugegraph-server/hugegraph-kvt/src/main/native
make clean && make
```

### 2. Environment Setup
```bash
# Set library path for native library loading
export LD_LIBRARY_PATH=/home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-kvt/src/main/resources/native:$LD_LIBRARY_PATH

# Or use Java system property
-Djava.library.path=/home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-kvt/src/main/resources/native
```

## Test Execution Approaches

### Approach 1: Maven Test Execution (Recommended)

```bash
# Run all tests in KVT module
cd /home/lintaoz/work/hugegraph/hugegraph-server/hugegraph-kvt
mvn test -Drat.skip=true -Dcheckstyle.skip=true

# Run specific test class
mvn test -Dtest=TestKVTLibrary -Drat.skip=true -Dcheckstyle.skip=true

# Run with specific native library path
mvn test -Djava.library.path=/path/to/native/lib -Drat.skip=true -Dcheckstyle.skip=true

# Skip native compilation but run tests
mvn test -Dmaven.antrun.skip=true -Drat.skip=true -Dcheckstyle.skip=true
```

### Approach 2: Direct Java Execution

```bash
# Compile first
mvn test-compile -Drat.skip=true -Dcheckstyle.skip=true

# Run individual test as Java application
java -cp target/test-classes:target/classes:$(mvn dependency:build-classpath | grep -v INFO) \
     -Djava.library.path=src/main/resources/native \
     TestKVTLibrary

# Run with assertions enabled
java -ea -cp target/test-classes:target/classes:$(mvn dependency:build-classpath | grep -v INFO) \
     -Djava.library.path=src/main/resources/native \
     TestKVTConnectivity
```

### Approach 3: JUnit Test Runner

```bash
# Using JUnit runner for proper test execution
mvn test -Dtest=org.apache.hugegraph.backend.store.kvt.KVTBasicTest \
         -DfailIfNoTests=false \
         -Drat.skip=true \
         -Dcheckstyle.skip=true
```

## Test Execution Plan

### Phase 1: Native Library Verification
**Goal**: Ensure native library loads and basic JNI communication works

```bash
# Test 1: Library Loading Test
mvn test -Dtest=TestKVTLibrary -DfailIfNoTests=false

# Test 2: Basic Native Calls
mvn test -Dtest=TestKVTConnectivity -DfailIfNoTests=false

# Expected: Both tests should pass, confirming native library is accessible
```

### Phase 2: Core Functionality Tests
**Goal**: Verify basic KVT operations work correctly

```bash
# Test 3: Simple CRUD Operations
mvn test -Dtest=SimpleKVTTest -DfailIfNoTests=false

# Test 4: Serialization/Deserialization
mvn test -Dtest=TestKVTSerialization -DfailIfNoTests=false

# Test 5: Basic Backend Operations
mvn test -Dtest=KVTBasicTest -DfailIfNoTests=false
```

### Phase 3: Integration Tests
**Goal**: Test KVT integration with HugeGraph components

```bash
# Test 6: Full Backend Integration
mvn test -Dtest=TestKVTBackend -DfailIfNoTests=false

# Test 7: Comprehensive Integration Suite
mvn test -Dtest=TestKVTIntegration -DfailIfNoTests=false
```

### Phase 4: Performance Tests
**Goal**: Benchmark and stress test the KVT backend

```bash
# Test 8: Performance Benchmarks
mvn test -Dtest=TestKVTPerformance -DfailIfNoTests=false
```

### Phase 5: Transaction Tests (Need Rewrite)
**Goal**: Test transaction management (currently disabled)

```bash
# Test 9: Transaction Management (needs rewrite)
# mvn test -Dtest=TestKVTTransaction -DfailIfNoTests=false
```

## Test Verification Strategy

### 1. Smoke Test
Quick verification that basic functionality works:
```bash
#!/bin/bash
echo "Running KVT Smoke Tests..."

# Check native library
if [ ! -f "src/main/resources/native/libkvt.so" ]; then
    echo "ERROR: Native library not found!"
    exit 1
fi

# Run basic tests
mvn test -Dtest=TestKVTLibrary,TestKVTConnectivity \
         -DfailIfNoTests=false \
         -Drat.skip=true \
         -Dcheckstyle.skip=true \
         -Dmaven.antrun.skip=true

if [ $? -eq 0 ]; then
    echo "Smoke tests PASSED"
else
    echo "Smoke tests FAILED"
    exit 1
fi
```

### 2. Full Test Suite
Complete test execution with reporting:
```bash
#!/bin/bash
echo "Running Full KVT Test Suite..."

# Clean previous results
rm -rf target/surefire-reports

# Run all tests with detailed reporting
mvn clean test \
    -Drat.skip=true \
    -Dcheckstyle.skip=true \
    -Dmaven.antrun.skip=true \
    -DreportFormat=plain \
    -Dsurefire.printSummary=true \
    2>&1 | tee test-output.log

# Generate test report
mvn surefire-report:report-only

echo "Test report available at: target/site/surefire-report.html"
```

### 3. Debug Test Execution
For troubleshooting failing tests:
```bash
# Run with debug output
mvn test -Dtest=TestKVTIntegration \
         -DfailIfNoTests=false \
         -Drat.skip=true \
         -Dcheckstyle.skip=true \
         -Dmaven.antrun.skip=true \
         -X

# Run with remote debugging enabled
mvn test -Dmaven.surefire.debug="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
```

## Expected Test Results

### Working Tests (Should Pass)
- TestKVTLibrary - Native library loading
- TestKVTConnectivity - Basic connectivity
- SimpleKVTTest - Simple operations
- TestKVTSerialization - Data serialization
- KVTBasicTest - Basic CRUD
- TestKVTBackend - Backend operations

### Partially Working Tests
- TestKVTIntegration - Some methods disabled (batch operations)
- TestKVTPerformance - Transaction benchmarks disabled

### Needs Rewrite
- TestKVTTransaction - KVTTransaction class removed

## Troubleshooting Guide

### Issue 1: Native Library Not Found
```
java.lang.UnsatisfiedLinkError: no kvtjni in java.library.path
```
**Solution**: Set LD_LIBRARY_PATH or java.library.path correctly

### Issue 2: Native Library Architecture Mismatch
```
wrong ELF class: ELFCLASS32 (Possible cause: architecture word width mismatch)
```
**Solution**: Rebuild native library for correct architecture (64-bit)

### Issue 3: Test Timeout
```
org.junit.runners.model.TestTimedOutException
```
**Solution**: Increase timeout or check for deadlocks
```bash
mvn test -Dsurefire.timeout=600 # 10 minutes timeout
```

### Issue 4: Memory Issues
```
java.lang.OutOfMemoryError
```
**Solution**: Increase heap size
```bash
mvn test -DargLine="-Xmx2g -Xms1g"
```

## Continuous Integration Setup

### GitHub Actions Workflow
```yaml
name: KVT Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v2
    
    - name: Set up JDK 8
      uses: actions/setup-java@v2
      with:
        java-version: '8'
        
    - name: Cache Maven dependencies
      uses: actions/cache@v2
      with:
        path: ~/.m2
        key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
        
    - name: Build native library
      run: |
        cd hugegraph-server/hugegraph-kvt/src/main/native
        make clean && make
        
    - name: Run tests
      run: |
        cd hugegraph-server/hugegraph-kvt
        mvn test -B \
            -Drat.skip=true \
            -Dcheckstyle.skip=true \
            -Dmaven.antrun.skip=true
            
    - name: Upload test results
      if: always()
      uses: actions/upload-artifact@v2
      with:
        name: test-results
        path: hugegraph-server/hugegraph-kvt/target/surefire-reports
```

## Test Coverage Analysis

```bash
# Run tests with coverage
mvn clean test jacoco:report \
    -Drat.skip=true \
    -Dcheckstyle.skip=true

# View coverage report
open target/site/jacoco/index.html
```

## Performance Profiling

```bash
# Run with profiling
mvn test -Dtest=TestKVTPerformance \
         -DargLine="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder"

# Or with async-profiler
java -agentpath:/path/to/async-profiler/build/libasyncProfiler.so=start,svg,file=profile.svg \
     -cp [classpath] TestKVTPerformance
```