#!/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

# Environment variables with defaults
export HUGEGRAPH_HOME=${HUGEGRAPH_HOME:-/opt/hugegraph-kvt}
export JAVA_HOME=${JAVA_HOME:-/usr/local/openjdk-11}
export HEAP_SIZE=${HEAP_SIZE:-2g}
export DIRECT_MEMORY=${DIRECT_MEMORY:-1g}

# KVT specific settings
export KVT_DATA_PATH=${KVT_DATA_PATH:-${HUGEGRAPH_HOME}/data/kvt}
export KVT_WAL_PATH=${KVT_WAL_PATH:-${HUGEGRAPH_HOME}/data/kvt/wal}
export KVT_CACHE_SIZE=${KVT_CACHE_SIZE:-536870912}
export KVT_BATCH_SIZE=${KVT_BATCH_SIZE:-1000}
export KVT_THREADS_READ=${KVT_THREADS_READ:-16}
export KVT_THREADS_WRITE=${KVT_THREADS_WRITE:-8}

# JVM options
export JAVA_OPTS="${JAVA_OPTS} \
    -Xms${HEAP_SIZE} \
    -Xmx${HEAP_SIZE} \
    -XX:MaxDirectMemorySize=${DIRECT_MEMORY} \
    -XX:+UseG1GC \
    -XX:+ParallelRefProcEnabled \
    -XX:MaxGCPauseMillis=200 \
    -XX:+UnlockExperimentalVMOptions \
    -XX:+DisableExplicitGC \
    -XX:+AlwaysPreTouch \
    -XX:G1NewSizePercent=30 \
    -XX:G1MaxNewSizePercent=40 \
    -XX:G1HeapRegionSize=8M \
    -XX:G1ReservePercent=20 \
    -XX:G1MixedGCCountTarget=4 \
    -XX:InitiatingHeapOccupancyPercent=15 \
    -XX:G1MixedGCLiveThresholdPercent=90 \
    -XX:G1RSetUpdatingPauseTimePercent=5 \
    -XX:SurvivorRatio=32 \
    -XX:+PerfDisableSharedMem \
    -XX:MaxTenuringThreshold=1 \
    -Djava.library.path=${HUGEGRAPH_HOME}/lib/native \
    -Dlog4j.configurationFile=${HUGEGRAPH_HOME}/conf/log4j2.xml"

# Function to wait for dependencies
wait_for_service() {
    local host=$1
    local port=$2
    local service=$3
    
    echo "Waiting for $service at $host:$port..."
    
    for i in {1..30}; do
        if nc -z "$host" "$port"; then
            echo "$service is available"
            return 0
        fi
        echo "Attempt $i/30: $service not ready, waiting..."
        sleep 2
    done
    
    echo "ERROR: $service at $host:$port did not become available"
    return 1
}

# Update configuration from environment variables
update_config() {
    local config_file="${HUGEGRAPH_HOME}/conf/kvt.properties"
    
    # Update data paths
    sed -i "s|kvt.storage.data_path=.*|kvt.storage.data_path=${KVT_DATA_PATH}|g" "$config_file"
    sed -i "s|kvt.storage.wal_path=.*|kvt.storage.wal_path=${KVT_WAL_PATH}|g" "$config_file"
    
    # Update memory settings
    sed -i "s|kvt.memory.cache_size=.*|kvt.memory.cache_size=${KVT_CACHE_SIZE}|g" "$config_file"
    
    # Update batch settings
    sed -i "s|kvt.batch.size=.*|kvt.batch.size=${KVT_BATCH_SIZE}|g" "$config_file"
    
    # Update thread settings
    sed -i "s|kvt.performance.read_threads=.*|kvt.performance.read_threads=${KVT_THREADS_READ}|g" "$config_file"
    sed -i "s|kvt.performance.write_threads=.*|kvt.performance.write_threads=${KVT_THREADS_WRITE}|g" "$config_file"
    
    echo "Configuration updated from environment variables"
}

# Initialize data directories
init_directories() {
    mkdir -p "${KVT_DATA_PATH}"
    mkdir -p "${KVT_WAL_PATH}"
    mkdir -p "${HUGEGRAPH_HOME}/logs"
    mkdir -p "${HUGEGRAPH_HOME}/backup/kvt"
    
    echo "Data directories initialized"
}

# Main execution
main() {
    local cmd=$1
    shift
    
    case "$cmd" in
        server)
            echo "Starting HugeGraph with KVT backend..."
            
            # Initialize
            init_directories
            update_config
            
            # Wait for any dependencies (e.g., if using external services)
            # wait_for_service cassandra 9042 "Cassandra"
            
            # Start server
            exec java ${JAVA_OPTS} \
                -cp "${HUGEGRAPH_HOME}/lib/*" \
                org.apache.hugegraph.HugeGraphServer \
                "$@"
            ;;
            
        console)
            echo "Starting Gremlin console..."
            exec java ${JAVA_OPTS} \
                -cp "${HUGEGRAPH_HOME}/lib/*" \
                org.apache.tinkerpop.gremlin.console.Console \
                "$@"
            ;;
            
        backup)
            echo "Starting backup..."
            exec java ${JAVA_OPTS} \
                -cp "${HUGEGRAPH_HOME}/lib/*" \
                org.apache.hugegraph.tools.Backup \
                --backend kvt \
                --data-path "${KVT_DATA_PATH}" \
                --backup-path "${HUGEGRAPH_HOME}/backup/kvt" \
                "$@"
            ;;
            
        restore)
            echo "Starting restore..."
            exec java ${JAVA_OPTS} \
                -cp "${HUGEGRAPH_HOME}/lib/*" \
                org.apache.hugegraph.tools.Restore \
                --backend kvt \
                --data-path "${KVT_DATA_PATH}" \
                --backup-path "${HUGEGRAPH_HOME}/backup/kvt" \
                "$@"
            ;;
            
        test)
            echo "Running tests..."
            exec java ${JAVA_OPTS} \
                -cp "${HUGEGRAPH_HOME}/lib/*" \
                TestKVTIntegration \
                "$@"
            ;;
            
        benchmark)
            echo "Running benchmarks..."
            exec java ${JAVA_OPTS} \
                -cp "${HUGEGRAPH_HOME}/lib/*" \
                TestKVTPerformance \
                "$@"
            ;;
            
        shell)
            echo "Starting shell..."
            exec /bin/bash "$@"
            ;;
            
        *)
            echo "Usage: $0 {server|console|backup|restore|test|benchmark|shell} [args...]"
            exit 1
            ;;
    esac
}

# Handle signals
trap 'echo "Shutting down..."; exit 0' SIGTERM SIGINT

# Run main function
main "$@"