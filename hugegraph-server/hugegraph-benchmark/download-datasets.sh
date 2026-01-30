#!/bin/bash
#
# Download SNAP datasets for graphdb-benchmarks
#
# Datasets:
#   1. email-Enron.txt     (36,691 vertices; 367,661 edges)
#   2. amazon0601.txt       (403,393 vertices; 3,387,388 edges)
#   3. com-youtube.ungraph.txt (1,157,806 vertices; 2,987,624 edges)
#   4. com-lj.ungraph.txt   (3,997,961 vertices; 34,681,189 edges)
#
# Source: Stanford Large Network Dataset Collection (SNAP)
# https://snap.stanford.edu/data/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/data"

mkdir -p "${DATA_DIR}"

# Dataset URLs and expected filenames
declare -A DATASETS
DATASETS["email-Enron.txt"]="https://snap.stanford.edu/data/email-Enron.txt.gz"
DATASETS["amazon0601.txt"]="https://snap.stanford.edu/data/amazon0601.txt.gz"
DATASETS["com-youtube.ungraph.txt"]="https://snap.stanford.edu/data/bigdata/communities/com-youtube.ungraph.txt.gz"
DATASETS["com-lj.ungraph.txt"]="https://snap.stanford.edu/data/bigdata/communities/com-lj.ungraph.txt.gz"

download_dataset() {
    local filename="$1"
    local url="$2"
    local output_path="${DATA_DIR}/${filename}"
    local gz_path="${output_path}.gz"

    if [ -f "${output_path}" ]; then
        echo "[SKIP] ${filename} already exists"
        return 0
    fi

    echo "[DOWNLOAD] ${filename} from ${url}"
    if ! wget -q --show-progress -O "${gz_path}" "${url}"; then
        echo "[ERROR] Failed to download ${filename}" >&2
        rm -f "${gz_path}"
        return 1
    fi

    echo "[EXTRACT] ${gz_path}"
    gunzip -f "${gz_path}"
    echo "[DONE] ${filename} ($(wc -l < "${output_path}") lines)"
}

echo "=== Downloading SNAP datasets for graphdb-benchmarks ==="
echo "Target directory: ${DATA_DIR}"
echo ""

failed=0
for filename in "email-Enron.txt" "amazon0601.txt" "com-youtube.ungraph.txt" "com-lj.ungraph.txt"; do
    url="${DATASETS[${filename}]}"
    if ! download_dataset "${filename}" "${url}"; then
        failed=$((failed + 1))
    fi
    echo ""
done

if [ "${failed}" -gt 0 ]; then
    echo "=== WARNING: ${failed} dataset(s) failed to download ==="
    exit 1
fi

echo "=== All datasets downloaded successfully ==="
echo ""
echo "Dataset files:"
ls -lh "${DATA_DIR}"/*.txt 2>/dev/null || echo "(no files found)"
