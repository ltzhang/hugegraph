#!/bin/bash

# Skip license checks for development build
export MAVEN_OPTS="-Drat.skip=true -Dcheckstyle.skip=true"

echo "=== Building KVT Backend for HugeGraph ==="
echo

# Set paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print status
print_status() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

print_info() {
    echo -e "${YELLOW}→${NC} $1"
}

# Check prerequisites
print_info "Checking prerequisites..."

if ! command -v javac &> /dev/null; then
    print_error "Java compiler not found. Please install JDK 11+"
    exit 1
fi

if ! command -v g++ &> /dev/null; then
    print_error "g++ not found. Please install build-essential"
    exit 1
fi

print_status "Prerequisites check passed"
echo

# Build native libraries using build-native.sh
print_info "Building native libraries..."
if [ -f "build-native.sh" ]; then
    chmod +x build-native.sh
    ./build-native.sh --keep-objects
    if [ $? -eq 0 ]; then
        print_status "Native libraries built successfully"
    else
        print_error "Failed to build native libraries"
        exit 1
    fi
else
    # Fallback to direct build if build-native.sh doesn't exist
    print_info "Building KVT library directly..."
    cd kvt
    g++ -shared -fPIC -std=c++17 -O2 kvt_mem.cpp -o libkvt.so
    if [ $? -eq 0 ]; then
        print_status "KVT library built successfully"
    else
        print_error "Failed to build KVT library"
        exit 1
    fi
    cd ..
fi
echo

# Create directories
print_info "Creating build directories..."
mkdir -p target/classes
mkdir -p target/test-classes
mkdir -p target/native
mkdir -p target/lib

print_status "Directories created"
echo

# Copy native libraries
print_info "Copying native libraries..."
if [ -f "src/main/resources/native/libkvtjni.so" ]; then
    cp src/main/resources/native/*.so target/native/
    print_status "Copied libkvtjni.so and libkvt.so from resources"
elif [ -f "kvt/libkvt.so" ]; then
    cp kvt/libkvt.so target/native/
    print_status "Copied libkvt.so from kvt directory"
fi

# Copy object files if they exist
if [ -f "kvt/kvt_memory.o" ]; then
    cp kvt/kvt_memory.o target/native/
    print_status "Copied kvt_memory.o"
fi

print_status "Native libraries copied"
echo

# Compile test classes if source exists
if [ -f "src/test/java/TestKVTLibrary.java" ]; then
    print_info "Compiling test classes..."
    javac -d target/test-classes src/test/java/TestKVTLibrary.java
    
    if [ $? -eq 0 ]; then
        print_status "Test classes compiled"
        
        # Run tests
        print_info "Running basic library test..."
        echo "----------------------------------------"
        java -cp target/test-classes -Djava.library.path=src/main/resources/native TestKVTLibrary
        if [ $? -eq 0 ]; then
            print_status "Library test passed"
        else
            print_error "Library test failed"
            exit 1
        fi
    else
        print_error "Failed to compile test classes"
        exit 1
    fi
else
    print_info "Skipping test compilation (TestKVTLibrary.java not found)"
fi
echo

# Create JAR package (without dependencies for now)
print_info "Creating JAR package..."
cd target/classes
if [ "$(ls -A 2>/dev/null)" ]; then
    jar cf ../hugegraph-kvt-1.5.0.jar *
    cd ../..
    print_status "JAR created: target/hugegraph-kvt-1.5.0.jar"
else
    cd ../..
    print_info "No Java classes to package yet (need hugegraph-core dependency)"
fi
echo

# Create distribution package
print_info "Creating distribution package..."
mkdir -p dist/hugegraph-kvt-1.5.0
cp -r target/native dist/hugegraph-kvt-1.5.0/
cp -r conf dist/hugegraph-kvt-1.5.0/ 2>/dev/null || true
cp -r docs dist/hugegraph-kvt-1.5.0/ 2>/dev/null || true
cp KVT_README.md dist/hugegraph-kvt-1.5.0/ 2>/dev/null || true
cp plan.md dist/hugegraph-kvt-1.5.0/ 2>/dev/null || true

cd dist
tar czf hugegraph-kvt-1.5.0.tar.gz hugegraph-kvt-1.5.0
cd ..

print_status "Distribution package created: dist/hugegraph-kvt-1.5.0.tar.gz"
echo

# Print summary
echo "=== Build Summary ==="
echo
if [ -f "src/main/resources/native/libkvtjni.so" ]; then
    print_status "libkvtjni.so: $(ls -lh src/main/resources/native/libkvtjni.so | awk '{print $5}')"
    print_status "libkvt.so: $(ls -lh src/main/resources/native/libkvt.so | awk '{print $5}')"
elif [ -f "kvt/libkvt.so" ]; then
    print_status "libkvt.so: $(ls -lh kvt/libkvt.so | awk '{print $5}')"
fi
if [ -f "src/test/java/TestKVTLibrary.java" ]; then
    print_status "Test status: PASSED"
fi
print_status "Distribution: dist/hugegraph-kvt-1.5.0.tar.gz"
echo
echo "Build completed successfully!"
echo
echo "Note: Full Java compilation requires hugegraph-core dependencies."
echo "Run 'mvn clean install' when dependencies are available."