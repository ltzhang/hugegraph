#!/bin/bash
#
# Patch the graphdb-benchmarks submodule for HugeGraph v1.5.0
#
# The upstream fork (hugegraph/graphdb-benchmarks) targets HugeGraph v0.10.4
# with com.baidu.hugegraph.* package names. This script patches the source
# to work with our local v1.5.0 build (org.apache.hugegraph.*).
#
# Changes applied:
#   1. pom.xml: Update groupId, version, Java target, remove stale deps
#   2. Java sources: com.baidu.hugegraph -> org.apache.hugegraph
#   3. Special: com.baidu.hugegraph.structure.constant.T -> org.apache.tinkerpop.gremlin.structure.T
#   4. Properties: HugeFactory class path update
#
# This script is idempotent — running it multiple times is safe.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCH_DIR="${SCRIPT_DIR}/graphdb-benchmarks"

if [ ! -d "${BENCH_DIR}/src" ]; then
    echo "ERROR: graphdb-benchmarks submodule not found at ${BENCH_DIR}"
    echo "Run: git submodule update --init"
    exit 1
fi

echo "=== Patching graphdb-benchmarks for HugeGraph v1.5.0 ==="

# --- 1. Patch pom.xml ---
echo "[1/4] Patching pom.xml..."
POM="${BENCH_DIR}/pom.xml"

# Update HugeGraph version property
sed -i 's|<hugegraph.dist.version>0.10.4</hugegraph.dist.version>|<hugegraph.dist.version>1.5.0</hugegraph.dist.version>|g' "${POM}"
sed -i 's|<hugegraph.client.version>1.8.0</hugegraph.client.version>|<hugegraph.client.version>1.5.0</hugegraph.client.version>|g' "${POM}"

# Update groupId from com.baidu.hugegraph to org.apache.hugegraph
sed -i 's|<groupId>com.baidu.hugegraph</groupId>|<groupId>org.apache.hugegraph</groupId>|g' "${POM}"

# Update artifact names: hugegraph-dist -> hugegraph-dist, hugegraph-api stays
# Replace hugegraph-dist with hugegraph-dist (same name, different group)
# Add hugegraph-eloq dependency for eloq backend support

# Update Java source/target from 1.8 to 11
sed -i 's|<jdk.version>1.8</jdk.version>|<jdk.version>11</jdk.version>|g' "${POM}"

# Remove hugegraph-client dependency block (client API incompatible with v1.5.0)
# We only use hugegraphcore (embedded) mode, not hugegraphclient (REST) mode
python3 - "${POM}" << 'PYEOF'
import sys, re

pom_path = sys.argv[1]
with open(pom_path, "r") as f:
    content = f.read()

# Remove the hugegraph-client dependency block
pattern = r"\s*<dependency>\s*<groupId>org\.apache\.hugegraph</groupId>\s*<artifactId>hugegraph-client</artifactId>.*?</dependency>"
content = re.sub(pattern, "", content, flags=re.DOTALL)

# Remove hugegraph-api dependency (not needed for core mode, causes resolution issues)
pattern = r"\s*<dependency>\s*<groupId>org\.apache\.hugegraph</groupId>\s*<artifactId>hugegraph-api</artifactId>.*?</dependency>"
content = re.sub(pattern, "", content, flags=re.DOTALL)

# Add hugegraph-eloq dependency after hugegraph-dist closing tag (if not already present)
if "hugegraph-eloq" not in content:
    eloq_dep = (
        "\n        <dependency>\n"
        "            <groupId>org.apache.hugegraph</groupId>\n"
        "            <artifactId>hugegraph-eloq</artifactId>\n"
        "            <version>${hugegraph.dist.version}</version>\n"
        "        </dependency>"
    )
    # Insert after the first </dependency> that follows hugegraph-dist
    content = re.sub(
        r"(<artifactId>hugegraph-dist</artifactId>\s*"
        r"<version>[^<]+</version>\s*</dependency>)",
        r"\1" + eloq_dep,
        content,
        count=1,
    )

with open(pom_path, "w") as f:
    f.write(content)

print("  pom.xml: groupId, version, dependencies updated")
PYEOF

# --- 2. Patch Java sources: special T class import first ---
echo "[2/4] Patching T class import..."
find "${BENCH_DIR}/src" -name "*.java" -exec \
    sed -i 's|import com\.baidu\.hugegraph\.structure\.constant\.T;|import org.apache.tinkerpop.gremlin.structure.T;|g' {} +

# --- 3. Patch Java sources: general package rename ---
echo "[3/4] Patching com.baidu.hugegraph -> org.apache.hugegraph..."
find "${BENCH_DIR}/src" -name "*.java" -exec \
    sed -i 's|com\.baidu\.hugegraph|org.apache.hugegraph|g' {} +

# --- 4. Patch properties files ---
echo "[4/4] Patching properties files..."
find "${BENCH_DIR}/src" -name "*.properties" -exec \
    sed -i 's|com\.baidu\.hugegraph|org.apache.hugegraph|g' {} +

echo ""
echo "=== Patch complete ==="
echo ""
echo "Verify with: cd ${BENCH_DIR} && git diff --stat"
