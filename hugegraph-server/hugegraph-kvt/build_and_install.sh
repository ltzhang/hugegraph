#!/bin/bash

# Build and install script for HugeGraph KVT module
# This script builds the KVT JAR and native libraries, then installs them to the HugeGraph distribution

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== HugeGraph KVT Build and Install Script ===${NC}"
echo

# Find the HugeGraph distribution directory
DIST_DIR=""
if [ -d "../../apache-hugegraph-incubating-1.5.0/apache-hugegraph-server-incubating-1.5.0" ]; then
    DIST_DIR="../../apache-hugegraph-incubating-1.5.0/apache-hugegraph-server-incubating-1.5.0"
elif [ -d "../apache-hugegraph-server-incubating-1.5.0" ]; then
    DIST_DIR="../apache-hugegraph-server-incubating-1.5.0"
else
    echo -e "${YELLOW}Warning: Could not find HugeGraph distribution directory${NC}"
    echo "Please specify the path to apache-hugegraph-server-incubating-1.5.0 as an argument"
    echo "Usage: $0 [path-to-hugegraph-server-dist]"
    if [ -n "$1" ]; then
        DIST_DIR="$1"
    else
        exit 1
    fi
fi

if [ ! -d "$DIST_DIR/lib" ]; then
    echo -e "${RED}Error: Invalid distribution directory - lib folder not found${NC}"
    echo "Path: $DIST_DIR"
    exit 1
fi

echo -e "${GREEN}[1/5] Building native KVT library...${NC}"
./build-native.sh
if [ $? -ne 0 ]; then
    echo -e "${RED}Error: Native library build failed${NC}"
    exit 1
fi

echo
echo -e "${GREEN}[2/5] Building KVT JAR...${NC}"
mvn clean package -DskipTests -ntp
if [ $? -ne 0 ]; then
    echo -e "${RED}Error: Maven build failed${NC}"
    exit 1
fi

echo
echo -e "${GREEN}[3/5] Copying KVT JAR to distribution...${NC}"
cp target/hugegraph-kvt-*.jar "$DIST_DIR/lib/"
echo "  ✓ Copied hugegraph-kvt-*.jar to $DIST_DIR/lib/"

echo
echo -e "${GREEN}[4/5] Copying native libraries to distribution...${NC}"
if [ -d "target/native" ]; then
    # Copy .so files (Linux)
    if ls target/native/*.so 1> /dev/null 2>&1; then
        cp target/native/*.so "$DIST_DIR/lib/native/" 2>/dev/null || mkdir -p "$DIST_DIR/lib/native" && cp target/native/*.so "$DIST_DIR/lib/native/"
        echo "  ✓ Copied .so files to $DIST_DIR/lib/native/"
    fi
    
    # Copy .dylib files (macOS)
    if ls target/native/*.dylib 1> /dev/null 2>&1; then
        cp target/native/*.dylib "$DIST_DIR/lib/native/" 2>/dev/null || mkdir -p "$DIST_DIR/lib/native" && cp target/native/*.dylib "$DIST_DIR/lib/native/"
        echo "  ✓ Copied .dylib files to $DIST_DIR/lib/native/"
    fi
    
    # Copy .dll files (Windows)
    if ls target/native/*.dll 1> /dev/null 2>&1; then
        cp target/native/*.dll "$DIST_DIR/lib/native/" 2>/dev/null || mkdir -p "$DIST_DIR/lib/native" && cp target/native/*.dll "$DIST_DIR/lib/native/"
        echo "  ✓ Copied .dll files to $DIST_DIR/lib/native/"
    fi
fi

echo
echo -e "${GREEN}[5/5] Copying configuration files...${NC}"
# Copy any KVT-specific configuration if needed
if [ -d "conf" ] && [ "$(ls -A conf)" ]; then
    cp conf/*.properties "$DIST_DIR/conf/" 2>/dev/null || echo "  ℹ No configuration files to copy"
fi

echo
echo -e "${GREEN}=== Installation Complete ===${NC}"
echo
echo "KVT backend has been installed to: $DIST_DIR"
echo
echo "To use KVT backend in HugeGraph:"
echo "1. Edit $DIST_DIR/conf/hugegraph.properties"
echo "2. Set: backend=kvt"
echo "3. Set: serializer=binary"
echo "4. Add KVT-specific properties as needed"
echo
echo -e "${GREEN}Done!${NC}"