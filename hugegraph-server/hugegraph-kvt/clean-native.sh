#!/bin/bash

# Clean script for KVT native libraries
# Removes all compiled native libraries and object files

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Get the script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

print_info "Cleaning KVT native build artifacts..."

# Clean KVT C++ artifacts
if [ -d "$SCRIPT_DIR/kvt" ]; then
    print_info "Cleaning KVT C++ build files..."
    rm -f "$SCRIPT_DIR/kvt/kvt_memory.o"
    rm -f "$SCRIPT_DIR/kvt/libkvt.so"
    rm -f "$SCRIPT_DIR/kvt/*.o"
    print_info "  Removed object files and libraries from kvt/"
fi

# Clean JNI artifacts
if [ -d "$SCRIPT_DIR/src/main/native" ]; then
    print_info "Cleaning JNI build files..."
    rm -f "$SCRIPT_DIR/src/main/native/*.o"
    rm -f "$SCRIPT_DIR/src/main/native/KVTJNIBridge.o"
    print_info "  Removed object files from src/main/native/"
fi

# Clean target directory
if [ -d "$SCRIPT_DIR/target/native" ]; then
    print_info "Cleaning target/native directory..."
    rm -rf "$SCRIPT_DIR/target/native"
    print_info "  Removed target/native/"
fi

# Clean resources directory (optional, controlled by parameter)
if [ "$1" == "--all" ] || [ "$1" == "--include-resources" ]; then
    if [ -d "$SCRIPT_DIR/src/main/resources/native" ]; then
        print_warning "Removing installed libraries from resources/native..."
        rm -f "$SCRIPT_DIR/src/main/resources/native/libkvt.so"
        rm -f "$SCRIPT_DIR/src/main/resources/native/libkvtjni.so"
        print_info "  Removed libraries from src/main/resources/native/"
    fi
else
    print_info "Keeping installed libraries in resources/native"
    print_info "  (use --all to also remove installed libraries)"
fi

print_info "========================================="
print_info "Native build cleanup complete!"
print_info "========================================="

if [ "$1" != "--all" ] && [ "$1" != "--include-resources" ]; then
    print_info "Note: Installed libraries in resources/native were preserved"
    print_info "Run with --all to remove everything including installed libraries"
fi

exit 0