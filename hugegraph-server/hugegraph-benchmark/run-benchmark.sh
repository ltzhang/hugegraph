#!/bin/bash
#
# Run graphdb-benchmarks with HugeGraph backends (eloq and/or rocksdb)
#
# Usage:
#   ./run-benchmark.sh [OPTIONS] [BACKEND...]
#
# Backends:
#   rocksdb     Run benchmark with RocksDB backend
#   eloq        Run benchmark with EloqRocks backend
#   all         Run both backends (default)
#
# Options:
#   --dataset NUM     Dataset number (1=Enron, 2=Amazon, 3=YouTube, 4=LiveJournal)
#                     Default: 1 (Enron — smallest, good for quick tests)
#   --workload TYPES  Comma-separated workloads: MIW,SIW,FN,FA,FS,CW,DEL
#                     Default: MIW,FN,FA,FS (massive insertion + all queries)
#   --skip-build      Skip Maven build of HugeGraph and benchmark
#   --skip-patch      Skip patching the benchmark fork
#   --help            Show this help message
#
# Examples:
#   ./run-benchmark.sh                          # Enron dataset, both backends
#   ./run-benchmark.sh rocksdb                  # Enron dataset, RocksDB only
#   ./run-benchmark.sh --dataset 2 eloq        # Amazon dataset, EloqRocks only
#   ./run-benchmark.sh --dataset 3 all         # YouTube dataset, both backends
#   ./run-benchmark.sh --workload MIW rocksdb  # Massive insertion only, RocksDB

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HUGEGRAPH_ROOT="${SCRIPT_DIR}/../.."
HUGEGRAPH_SERVER="${SCRIPT_DIR}/.."
BENCH_DIR="${SCRIPT_DIR}/graphdb-benchmarks"
CONF_DIR="${SCRIPT_DIR}/conf"
DATA_DIR="${SCRIPT_DIR}/data"
RESULTS_DIR="${SCRIPT_DIR}/results"
LOGS_DIR="${SCRIPT_DIR}/logs"

# Default options
DATASET_NUM=1
WORKLOADS="MIW,FN,FA,FS"
SKIP_BUILD=false
SKIP_PATCH=false
BACKENDS=()

# --- Parse arguments ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dataset)
            DATASET_NUM="$2"
            shift 2
            ;;
        --workload)
            WORKLOADS="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --skip-patch)
            SKIP_PATCH=true
            shift
            ;;
        --help|-h)
            head -30 "$0" | grep '^#' | sed 's/^# \?//'
            exit 0
            ;;
        rocksdb|eloq|all)
            BACKENDS+=("$1")
            shift
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Default: run both backends
if [ ${#BACKENDS[@]} -eq 0 ] || [[ " ${BACKENDS[*]} " == *" all "* ]]; then
    BACKENDS=(rocksdb eloq)
fi

# --- Dataset mapping ---
declare -A DATASET_FILES
DATASET_FILES[1]="email-Enron.txt"
DATASET_FILES[2]="amazon0601.txt"
DATASET_FILES[3]="com-youtube.ungraph.txt"
DATASET_FILES[4]="com-lj.ungraph.txt"

declare -A DATASET_NAMES
DATASET_NAMES[1]="Enron"
DATASET_NAMES[2]="Amazon"
DATASET_NAMES[3]="YouTube"
DATASET_NAMES[4]="LiveJournal"

if [[ -z "${DATASET_FILES[$DATASET_NUM]+x}" ]]; then
    echo "ERROR: Invalid dataset number: ${DATASET_NUM} (valid: 1-4)"
    exit 1
fi

DATASET_FILE="${DATASET_FILES[$DATASET_NUM]}"
DATASET_NAME="${DATASET_NAMES[$DATASET_NUM]}"
DATASET_PREFIX="${DATASET_FILE%%.*}"

# --- Workload mapping ---
declare -A WORKLOAD_MAP
WORKLOAD_MAP[MIW]="MASSIVE_INSERTION"
WORKLOAD_MAP[SIW]="SINGLE_INSERTION"
WORKLOAD_MAP[FN]="FIND_NEIGHBOURS"
WORKLOAD_MAP[FA]="FIND_ADJACENT_NODES"
WORKLOAD_MAP[FS]="FIND_SHORTEST_PATH"
WORKLOAD_MAP[CW]="CLUSTERING"
WORKLOAD_MAP[DEL]="DELETION"

echo "============================================="
echo " HugeGraph Benchmark Runner"
echo "============================================="
echo " Dataset:   ${DATASET_NAME} (${DATASET_FILE})"
echo " Backends:  ${BACKENDS[*]}"
echo " Workloads: ${WORKLOADS}"
echo "============================================="
echo ""

# --- Step 1: Check dataset ---
if [ ! -f "${DATA_DIR}/${DATASET_FILE}" ]; then
    echo "ERROR: Dataset not found: ${DATA_DIR}/${DATASET_FILE}"
    echo "Run: ./download-datasets.sh"
    exit 1
fi

# --- Step 2: Patch benchmark fork ---
if [ "${SKIP_PATCH}" = false ]; then
    echo ">>> Patching benchmark fork for HugeGraph v1.5.0..."
    bash "${SCRIPT_DIR}/patch-benchmark.sh"
    echo ""
fi

# --- Step 3: Build HugeGraph (install to local maven repo) ---
if [ "${SKIP_BUILD}" = false ]; then
    echo ">>> Building HugeGraph (install to local Maven repo)..."
    cd "${HUGEGRAPH_ROOT}"
    mvn clean install -Dmaven.test.skip=true -Dcheckstyle.skip=true \
        -Deditorconfig.skip=true -Dlicense.skip=true -pl hugegraph-commons -am 2>&1 | tail -5
    cd "${HUGEGRAPH_SERVER}"
    mvn clean install -Dmaven.test.skip=true -Dcheckstyle.skip=true \
        -Deditorconfig.skip=true -Dlicense.skip=true 2>&1 | tail -5
    echo "  HugeGraph installed to local Maven repo"
    echo ""

    echo ">>> Building graphdb-benchmarks..."
    cd "${BENCH_DIR}"
    mvn clean package -Dmaven.test.skip=true -DskipTests 2>&1 | tail -5
    mvn dependency:copy-dependencies 2>&1 | tail -5
    echo "  Benchmark built successfully"
    echo ""
fi

# --- Step 4: Create symlink for data directory ---
if [ ! -e "${BENCH_DIR}/data" ]; then
    ln -sf "${DATA_DIR}" "${BENCH_DIR}/data"
    echo "  Linked data directory: ${BENCH_DIR}/data -> ${DATA_DIR}"
fi

# --- Helper: generate input.properties ---
generate_input_properties() {
    local backend="$1"
    local props_file="${BENCH_DIR}/src/test/resources/META-INF/input.properties"

    # Build workload lines
    local workload_lines=""
    IFS=',' read -ra WL_ARRAY <<< "${WORKLOADS}"
    for wl in "${WL_ARRAY[@]}"; do
        wl=$(echo "${wl}" | tr -d ' ')
        local full_name="${WORKLOAD_MAP[$wl]:-}"
        if [ -z "${full_name}" ]; then
            echo "WARNING: Unknown workload '${wl}', skipping"
            continue
        fi
        workload_lines="${workload_lines}eu.socialsensor.benchmarks=${full_name}"$'\n'
    done

    cat > "${props_file}" << PROPEOF
# Auto-generated by run-benchmark.sh
# Backend: ${backend}, Dataset: ${DATASET_NAME}

eu.socialsensor.dataset=data/${DATASET_FILE}

eu.socialsensor.database-storage-directory=storage
eu.socialsensor.metrics.csv.interval=1000
eu.socialsensor.metrics.csv.directory=./metrics

# Only use hugegraphcore (embedded mode)
eu.socialsensor.databases=hugegraphcore

eu.socialsensor.permute-benchmarks=false

# Workloads
${workload_lines}
eu.socialsensor.shortest-path-random-nodes=100

# Clustering config (used only if CW workload selected)
eu.socialsensor.randomize-clustering=false
eu.socialsensor.nodes-count=1000
eu.socialsensor.cache-values=25
eu.socialsensor.cache-increment-factor=1
eu.socialsensor.cache-values-count=6

# Results
eu.socialsensor.results-path=results
PROPEOF
    echo "  Generated input.properties for backend=${backend}"
}

# --- Helper: copy backend-specific hugegraph.properties ---
setup_backend_config() {
    local backend="$1"
    local src_conf="${CONF_DIR}/hugegraph-${backend}.properties"
    local dest_main="${BENCH_DIR}/src/main/resources/hugegraph.properties"
    local dest_test="${BENCH_DIR}/src/test/resources/hugegraph.properties"

    if [ ! -f "${src_conf}" ]; then
        echo "ERROR: Backend config not found: ${src_conf}"
        exit 1
    fi

    cp "${src_conf}" "${dest_main}"
    cp "${src_conf}" "${dest_test}"
    echo "  Configured hugegraph.properties for backend=${backend}"
}

# --- Helper: run benchmark for a single backend ---
run_single_backend() {
    local backend="$1"
    local timestamp
    timestamp=$(date +%Y%m%d_%H%M%S)
    local result_dir="${RESULTS_DIR}/${backend}/${DATASET_PREFIX}_${timestamp}"
    local log_file="${LOGS_DIR}/${backend}_${DATASET_PREFIX}_${timestamp}.log"

    echo ">>> Running benchmark: backend=${backend}, dataset=${DATASET_NAME}"
    echo "    Results: ${result_dir}"
    echo "    Log:     ${log_file}"

    mkdir -p "${result_dir}" "${LOGS_DIR}"

    # Setup configuration
    generate_input_properties "${backend}"
    setup_backend_config "${backend}"

    # Clear previous storage
    rm -rf "${BENCH_DIR}/storage"

    # Build JVM arguments
    local jvm_args="-Xmx8g -Xms2g"
    local env_vars=""

    if [ "${backend}" = "eloq" ]; then
        local eloq_native_dir="${HUGEGRAPH_SERVER}/hugegraph-eloq/target/native"
        jvm_args="${jvm_args} -Djava.library.path=${eloq_native_dir}"
        env_vars="LD_PRELOAD=/usr/local/lib/libmimalloc.so.2 GLIBC_TUNABLES=glibc.rtld.optional_static_tls=16384"
        echo "    EloqRocks JNI: ${eloq_native_dir}"
        echo "    LD_PRELOAD:    /usr/local/lib/libmimalloc.so.2"
    fi

    # Run the benchmark
    cd "${BENCH_DIR}"

    local start_time
    start_time=$(date +%s)

    local mvn_cmd="mvn test -Pbench -DargLine=\"${jvm_args}\" -Dlog4j.configurationFile=src/test/resources/META-INF/log4j2.xml"

    if [ -n "${env_vars}" ]; then
        eval "env ${env_vars} ${mvn_cmd}" > "${log_file}" 2>&1 || true
    else
        eval "${mvn_cmd}" > "${log_file}" 2>&1 || true
    fi

    local end_time
    end_time=$(date +%s)
    local elapsed=$((end_time - start_time))

    # Copy results
    if [ -d "${BENCH_DIR}/results" ]; then
        cp -r "${BENCH_DIR}/results/"* "${result_dir}/" 2>/dev/null || true
    fi

    # Check for success
    if grep -q "BUILD SUCCESS" "${log_file}"; then
        echo "    [PASS] Completed in ${elapsed}s"
    else
        echo "    [FAIL] Check log: ${log_file}"
        echo "    Last 20 lines:"
        tail -20 "${log_file}" | sed 's/^/      /'
    fi

    echo ""
    return 0
}

# --- Step 5: Run benchmarks ---
echo ""
echo "============================================="
echo " Starting Benchmarks"
echo "============================================="
echo ""

for backend in "${BACKENDS[@]}"; do
    run_single_backend "${backend}"
done

# --- Step 6: Summary ---
echo "============================================="
echo " Benchmark Complete"
echo "============================================="
echo ""
echo "Results directory: ${RESULTS_DIR}/"
if [ -d "${RESULTS_DIR}" ]; then
    echo ""
    find "${RESULTS_DIR}" -name "*.csv" -o -name "*.txt" 2>/dev/null | head -20 | while read -r f; do
        echo "  ${f}"
    done
fi
echo ""
echo "Logs directory: ${LOGS_DIR}/"
ls -lt "${LOGS_DIR}/"*.log 2>/dev/null | head -5 | while read -r line; do
    echo "  ${line}"
done
