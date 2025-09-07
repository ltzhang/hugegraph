#!/bin/bash

# Build script for KVT native libraries
# This script builds both the KVT C++ library and the JNI wrapper

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored messages
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Get the script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

print_info "Starting KVT native library build..."

# Check for required tools
print_info "Checking build requirements..."

if ! command -v g++ &> /dev/null; then
    print_error "g++ is not installed. Please install g++ first."
    exit 1
fi

if [ -z "$JAVA_HOME" ]; then
    print_warning "JAVA_HOME is not set. Trying to detect..."
    
    # Try to detect JAVA_HOME
    if [ -d "/usr/lib/jvm/java-11-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-11-openjdk-amd64"
        print_info "Using detected JAVA_HOME: $JAVA_HOME"
    elif [ -d "/usr/lib/jvm/java-8-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-8-openjdk-amd64"
        print_info "Using detected JAVA_HOME: $JAVA_HOME"
    else
        print_error "Could not detect JAVA_HOME. Please set it manually."
        echo "Example: export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64"
        exit 1
    fi
fi

if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    print_error "JNI headers not found in $JAVA_HOME/include"
    print_error "Please ensure you have JDK (not just JRE) installed"
    exit 1
fi

print_info "Build requirements satisfied"

# Step 1: Build KVT C++ library
print_info "Building KVT C++ library..."

cd "$SCRIPT_DIR/kvt"

# Clean old files
print_info "Cleaning old KVT build files..."
rm -f kvt_memory.o libkvt.so

# Compile KVT C++ code
print_info "Compiling kvt_mem.cpp..."
g++ -Wall -O2 -fPIC -std=c++17 -c kvt_mem.cpp -o kvt_memory.o

if [ $? -ne 0 ]; then
    print_error "Failed to compile kvt_mem.cpp"
    exit 1
fi

# Create shared library (optional, for standalone testing)
print_info "Creating libkvt.so..."
g++ -shared -fPIC -std=c++17 -O2 -o libkvt.so kvt_mem.cpp

if [ $? -ne 0 ]; then
    print_error "Failed to create libkvt.so"
    exit 1
fi

print_info "KVT C++ library built successfully"
ls -lh kvt_memory.o libkvt.so

# Step 2: Build JNI wrapper library
print_info "Building JNI wrapper library..."

cd "$SCRIPT_DIR/src/main/native"

# Clean old files
print_info "Cleaning old JNI build files..."
rm -f KVTJNIBridge.o
rm -f "$SCRIPT_DIR/target/native/libkvtjni.so"

# Create target directory if it doesn't exist
mkdir -p "$SCRIPT_DIR/target/native"

# Compile JNI bridge
print_info "Compiling KVTJNIBridge.cpp..."
g++ -Wall -O2 -fPIC -std=c++17 \
    -I"$JAVA_HOME/include" \
    -I"$JAVA_HOME/include/linux" \
    -I"$SCRIPT_DIR/kvt" \
    -c KVTJNIBridge.cpp -o KVTJNIBridge.o

if [ $? -ne 0 ]; then
    print_error "Failed to compile KVTJNIBridge.cpp"
    exit 1
fi

# Link JNI library with KVT
print_info "Linking libkvtjni.so..."
g++ -shared -fPIC -o "$SCRIPT_DIR/target/native/libkvtjni.so" \
    KVTJNIBridge.o "$SCRIPT_DIR/kvt/kvt_memory.o"

if [ $? -ne 0 ]; then
    print_error "Failed to link libkvtjni.so"
    exit 1
fi

print_info "JNI wrapper library built successfully"
ls -lh "$SCRIPT_DIR/target/native/libkvtjni.so"

# Step 3: Copy libraries to resources directory
print_info "Copying libraries to resources directory..."

RESOURCES_DIR="$SCRIPT_DIR/src/main/resources/native"
mkdir -p "$RESOURCES_DIR"

# Copy the libraries
cp "$SCRIPT_DIR/kvt/libkvt.so" "$RESOURCES_DIR/"
cp "$SCRIPT_DIR/target/native/libkvtjni.so" "$RESOURCES_DIR/"

if [ $? -ne 0 ]; then
    print_error "Failed to copy libraries to resources directory"
    exit 1
fi

print_info "Libraries copied to $RESOURCES_DIR"
ls -lh "$RESOURCES_DIR"/*.so

# Step 4: Clean up intermediate files (optional)
if [ "$1" != "--keep-objects" ]; then
    print_info "Cleaning up intermediate object files..."
    rm -f "$SCRIPT_DIR/kvt/kvt_memory.o"
    rm -f "$SCRIPT_DIR/src/main/native/KVTJNIBridge.o"
    print_info "Cleanup complete (use --keep-objects to preserve .o files)"
fi

print_info "========================================="
print_info "Native library build completed successfully!"
print_info "========================================="
print_info "Libraries built:"
print_info "  - libkvt.so: Core KVT C++ library"
print_info "  - libkvtjni.so: JNI wrapper for Java integration"
print_info ""
print_info "Libraries installed to:"
print_info "  - $RESOURCES_DIR"
print_info ""
print_info "To run tests:"
print_info "  cd $SCRIPT_DIR"
print_info "  mvn test -DargLine=\"-Djava.library.path=$RESOURCES_DIR\""

exit 0