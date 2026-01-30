# HugeGraph Benchmark

Performance benchmark comparing HugeGraph backends (RocksDB vs EloqRocks) using the
[graphdb-benchmarks](https://github.com/hugegraph/graphdb-benchmarks) framework from SocialSensor.

## Directory Structure

```
hugegraph-benchmark/
├── graphdb-benchmarks/     # Git submodule (hugegraph/graphdb-benchmarks fork)
├── data/                   # SNAP datasets (downloaded by script)
├── conf/                   # Backend-specific HugeGraph config files
│   ├── hugegraph-rocksdb.properties
│   └── hugegraph-eloq.properties
├── results/                # Benchmark results (per backend/dataset)
├── logs/                   # Benchmark execution logs
├── download-datasets.sh    # Download SNAP datasets
├── patch-benchmark.sh      # Patch benchmark fork for HugeGraph v1.5.0
├── run-benchmark.sh        # Main benchmark runner
└── README.md
```

## Prerequisites

- Java 11+
- Maven 3.5+
- `wget` and `gunzip` (for dataset download)
- HugeGraph source tree built locally
- For EloqRocks: native library built, `libmimalloc.so.2` installed at `/usr/local/lib/`

## Quick Start

```bash
cd hugegraph-server/hugegraph-benchmark

# 1. Download datasets
./download-datasets.sh

# 2. Run benchmark (both backends, Enron dataset)
./run-benchmark.sh

# 3. Check results
ls results/
```

## Usage

```bash
# Run with specific backend
./run-benchmark.sh rocksdb
./run-benchmark.sh eloq

# Run with larger dataset
./run-benchmark.sh --dataset 2 all    # Amazon (403K vertices, 3.4M edges)
./run-benchmark.sh --dataset 3 eloq   # YouTube (1.2M vertices, 3M edges)

# Run specific workloads only
./run-benchmark.sh --workload MIW rocksdb        # Massive insertion only
./run-benchmark.sh --workload MIW,FN,FS all      # Insertion + neighbor + shortest path

# Skip rebuild (faster iteration)
./run-benchmark.sh --skip-build --skip-patch rocksdb
```

### Datasets

| # | Name | Vertices | Edges | Source |
|---|------|----------|-------|--------|
| 1 | Enron (email) | 36,691 | 367,661 | [SNAP](https://snap.stanford.edu/data/email-Enron.html) |
| 2 | Amazon (products) | 403,393 | 3,387,388 | [SNAP](https://snap.stanford.edu/data/amazon0601.html) |
| 3 | YouTube (social) | 1,157,806 | 2,987,624 | [SNAP](https://snap.stanford.edu/data/com-Youtube.html) |
| 4 | LiveJournal (social) | 3,997,961 | 34,681,189 | [SNAP](https://snap.stanford.edu/data/com-LiveJournal.html) |

### Workloads

| Code | Full Name | Description |
|------|-----------|-------------|
| MIW | MASSIVE_INSERTION | Batch vertex/edge insertion |
| SIW | SINGLE_INSERTION | Per-commit vertex/edge insertion |
| FN | FIND_NEIGHBOURS | Neighbor discovery across all nodes |
| FA | FIND_ADJACENT_NODES | Adjacent node identification |
| FS | FIND_SHORTEST_PATH | Shortest path (first node to 100 random nodes) |
| CW | CLUSTERING | Louvain community detection |
| DEL | DELETION | Database deletion time |

## Step-by-Step Setup

### 1. Build HugeGraph

```bash
# From repository root
cd hugegraph-server/..
mvn clean install -Dmaven.test.skip=true -Dcheckstyle.skip=true \
    -Deditorconfig.skip=true -Dlicense.skip=true -pl hugegraph-commons -am

cd hugegraph-server
mvn clean install -Dmaven.test.skip=true -Dcheckstyle.skip=true \
    -Deditorconfig.skip=true -Dlicense.skip=true
```

### 2. For EloqRocks Backend

Ensure the native library is built:

```bash
# Build EloqRocks C++ (if not already done)
cd hugegraph-server/hugegraph-eloq/eloqrocks
mkdir -p bld && cd bld
cmake -DELOQROCKS_SANITY_CHECK=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON ..
cmake --build . -j

# Build the Java module + JNI bridge
cd hugegraph-server
mvn clean package -pl hugegraph-eloq -Dmaven.test.skip=true
```

Verify mimalloc is installed:

```bash
ls /usr/local/lib/libmimalloc.so.2
```

### 3. Download Datasets

```bash
cd hugegraph-server/hugegraph-benchmark
./download-datasets.sh
```

### 4. Patch and Build Benchmark

```bash
./patch-benchmark.sh
cd graphdb-benchmarks
mvn clean package -Dmaven.test.skip=true
mvn dependency:copy-dependencies
```

### 5. Run

```bash
./run-benchmark.sh all
```

## Results

Results are saved to `results/<backend>/<dataset>_<timestamp>/`.

The benchmark framework outputs CSV files with execution times for each workload.
Logs are saved to `logs/<backend>_<dataset>_<timestamp>.log`.

## Troubleshooting

### Build failures in graphdb-benchmarks

The benchmark fork targets an old HugeGraph API (v0.10.4). The `patch-benchmark.sh`
script handles the package rename (`com.baidu.hugegraph` -> `org.apache.hugegraph`),
but if there are additional API incompatibilities, check the build log and fix the
Java source in `graphdb-benchmarks/src/`.

### EloqRocks JVM crashes

See `CLAUDE.md` for the LD_PRELOAD and GLIBC_TUNABLES requirements. The run script
handles these automatically, but if running manually:

```bash
export LD_PRELOAD=/usr/local/lib/libmimalloc.so.2
export GLIBC_TUNABLES=glibc.rtld.optional_static_tls=16384
```

### Stale EloqRocks data

If EloqRocks crashes on startup, clear stale data:

```bash
rm -rf /tmp/eloq_data
rm -rf graphdb-benchmarks/storage
```
