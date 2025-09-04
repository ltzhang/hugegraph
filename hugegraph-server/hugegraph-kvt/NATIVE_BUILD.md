# KVT Native Library Build Guide

## Overview
The KVT (Key-Value Transaction) module includes native C++ components that need to be compiled separately from the Java code. This guide explains how to build the native libraries.

## Components

### 1. Core KVT Library (`libkvt.so`)
- **Source**: `kvt/kvt_mem.cpp`
- **Purpose**: Implements the core transactional key-value store in C++
- **Features**: MVCC (Multi-Version Concurrency Control), transaction support, table management

### 2. JNI Wrapper Library (`libkvtjni.so`)
- **Source**: `src/main/native/KVTJNIBridge.cpp`
- **Purpose**: JNI bridge between Java and the C++ KVT implementation
- **Links with**: `kvt_memory.o` (compiled from kvt_mem.cpp)

## Prerequisites

### Required Software
- **GCC/G++**: Version 5.0 or higher (for C++17 support)
- **JDK**: Java 8 or 11 with development headers
- **Make**: GNU Make (optional, for Makefile)

### Environment Setup
```bash
# Set JAVA_HOME if not already set
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64

# Verify prerequisites
g++ --version
javac -version
ls $JAVA_HOME/include/jni.h
```

## Build Instructions

### Quick Build (Recommended)
Use the provided build script:
```bash
# Make the script executable (first time only)
chmod +x build-native.sh

# Build all native libraries
./build-native.sh

# Build and keep intermediate object files (for debugging)
./build-native.sh --keep-objects
```

### Manual Build
If you need to build manually:

#### Step 1: Build KVT C++ Library
```bash
cd kvt
g++ -Wall -O2 -fPIC -std=c++17 -c kvt_mem.cpp -o kvt_memory.o
g++ -shared -fPIC -std=c++17 -O2 -o libkvt.so kvt_mem.cpp
```

#### Step 2: Build JNI Wrapper
```bash
cd ../src/main/native
g++ -Wall -O2 -fPIC -std=c++11 \
    -I$JAVA_HOME/include \
    -I$JAVA_HOME/include/linux \
    -I../../kvt \
    -c KVTJNIBridge.cpp -o KVTJNIBridge.o

g++ -shared -fPIC -o ../../target/native/libkvtjni.so \
    KVTJNIBridge.o ../../kvt/kvt_memory.o
```

#### Step 3: Install Libraries
```bash
mkdir -p ../resources/native
cp ../../kvt/libkvt.so ../resources/native/
cp ../../target/native/libkvtjni.so ../resources/native/
```

## Clean Build Artifacts

### Using Clean Script
```bash
# Clean build artifacts but keep installed libraries
./clean-native.sh

# Clean everything including installed libraries
./clean-native.sh --all
```

### Manual Clean
```bash
# Remove object files
rm -f kvt/*.o src/main/native/*.o

# Remove compiled libraries
rm -f kvt/libkvt.so
rm -rf target/native

# Remove installed libraries (optional)
rm -f src/main/resources/native/*.so
```

## Testing the Build

After building, verify the libraries work:

```bash
# Run a simple connectivity test
mvn test -Dtest=TestKVTConnectivity \
    -DargLine="-Djava.library.path=src/main/resources/native"

# Run all KVT tests
mvn test -DargLine="-Djava.library.path=src/main/resources/native"
```

## Troubleshooting

### Common Issues

1. **"jni.h not found" Error**
   - Solution: Ensure JAVA_HOME is set correctly
   - Check: `ls $JAVA_HOME/include/jni.h`

2. **"undefined reference" Linking Errors**
   - Solution: Ensure kvt_memory.o is compiled before linking
   - Check: `ls kvt/kvt_memory.o`

3. **Library Not Found at Runtime**
   - Solution: Set java.library.path when running tests
   - Example: `-Djava.library.path=src/main/resources/native`

4. **C++ Compilation Warnings**
   - The `-Wreorder` warnings in kvt_mem.h are harmless
   - The signed/unsigned comparison warning is also harmless

### Debug Build

For debugging, compile with debug symbols:
```bash
# Debug build with symbols and no optimization
g++ -g -O0 -fPIC -std=c++17 -c kvt_mem.cpp -o kvt_memory_debug.o
```

## Library Sizes

Expected library sizes after successful build:
- `libkvt.so`: ~175-180 KB
- `libkvtjni.so`: ~185-195 KB
- `kvt_memory.o`: ~235-245 KB (if kept)

## Integration with Maven

The Maven build can also compile native code using the `maven-antrun-plugin`:
```bash
# Build everything including native libraries
mvn clean compile

# Skip native compilation (use pre-built libraries)
mvn compile -Dmaven.antrun.skip=true
```

## Platform Support

Currently supported platforms:
- **Linux x86_64**: Fully supported and tested
- **Linux ARM64**: Should work with appropriate JDK
- **macOS**: Requires minor Makefile modifications
- **Windows**: Not supported (would need significant changes)

## Contributing

When modifying native code:
1. Update `kvt_mem.cpp` or `KVTJNIBridge.cpp`
2. Run `./build-native.sh` to rebuild
3. Run tests to verify functionality
4. Commit both source and updated libraries

## License

The native components follow the same Apache 2.0 license as HugeGraph.