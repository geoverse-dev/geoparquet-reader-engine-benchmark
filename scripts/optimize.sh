#!/usr/bin/env bash
set -euo pipefail

IN="${RAW_FILE:-data/raw/raw.parquet}"
OUT_DIR="${OPT_DIR:-data/optimized}"
OUT_V11="${OPT_V11_FILE:-$OUT_DIR/optimized-v1.1.parquet}"
OUT_V20="${OPT_V20_FILE:-$OUT_DIR/optimized-v2.0.parquet}"
ROW_GROUP_SIZE="${ROW_GROUP_SIZE:-50000}"

[ -f "$IN" ] || {
	echo "Missing input: $IN"
	exit 1
}

mkdir -p "$OUT_DIR" results

sha() {
	sha256sum "$1" | awk '{print $1}'
}

size() {
	stat -c %s "$1"
}

echo
echo "============================================================"
echo "Input"
echo "============================================================"
echo "$IN"
ls -lh "$IN"
echo

# ------------------------------------------------------------
# GeoParquet 1.1
# ------------------------------------------------------------

echo "============================================================"
echo "Generating optimized GeoParquet 1.1"
echo "Hilbert + bbox + ZSTD + row-group-size=$ROW_GROUP_SIZE"
echo "============================================================"

START_V11=$(date +%s%N)

uv run gpio sort hilbert \
	"$IN" \
	"$OUT_V11" \
	--add-bbox \
	--compression ZSTD \
	--row-group-size "$ROW_GROUP_SIZE" \
	--geoparquet-version 1.1 \
	--overwrite

END_V11=$(date +%s%N)

echo
echo "GeoParquet 1.1 generated:"
ls -lh "$OUT_V11"

echo
echo "Checking GeoParquet 1.1 optimization..."
uv run gpio check optimization "$OUT_V11" || true
uv run gpio check spatial "$OUT_V11" || true

# ------------------------------------------------------------
# GeoParquet 2.0
# ------------------------------------------------------------

echo
echo "============================================================"
echo "Generating optimized GeoParquet 2.0"
echo "Hilbert + native geo_bbox + ZSTD + row-group-size=$ROW_GROUP_SIZE"
echo "============================================================"

START_V20=$(date +%s%N)

uv run gpio sort hilbert \
	"$IN" \
	"$OUT_V20" \
	--compression ZSTD \
	--row-group-size "$ROW_GROUP_SIZE" \
	--geoparquet-version 2.0 \
	--overwrite

END_V20=$(date +%s%N)

echo
echo "GeoParquet 2.0 generated:"
ls -lh "$OUT_V20"

echo
echo "Checking GeoParquet 2.0 optimization..."
uv run gpio check optimization "$OUT_V20" || true
uv run gpio check spatial "$OUT_V20" || true

# ------------------------------------------------------------
# Artifact metadata
# ------------------------------------------------------------

python3 - \
	"$IN" \
	"$OUT_V11" \
	"$OUT_V20" \
	"$START_V11" \
	"$END_V11" \
	"$START_V20" \
	"$END_V20" \
	"$ROW_GROUP_SIZE" <<'PY'
import hashlib
import json
import os
import sys

(
    raw,
    v11,
    v20,
    start_v11,
    end_v11,
    start_v20,
    end_v20,
    row_group_size,
) = sys.argv[1:]

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def info(path):
    return {
        "path": path,
        "bytes": os.path.getsize(path),
        "sha256": sha256(path),
    }

result = {
    "raw": info(raw),

    "optimized_v1_1": {
        **info(v11),
        "pipeline": "Hilbert + bbox + ZSTD",
        "geoparquet_version": "1.1",
        "optimization_ms": (int(end_v11) - int(start_v11)) / 1e6,
    },

    "optimized_v2_0": {
        **info(v20),
        "pipeline": "Hilbert + native geo_bbox + ZSTD",
        "geoparquet_version": "2.0",
        "optimization_ms": (int(end_v20) - int(start_v20)) / 1e6,
    },

    "row_group_size": int(row_group_size),
}

with open("results/artifacts.json", "w") as f:
    json.dump(result, f, indent=2)

print()
print("============================================================")
print("Artifacts")
print("============================================================")
print(json.dumps(result, indent=2))
PY

echo
echo "============================================================"
echo "Optimization complete"
echo "============================================================"
echo
echo "RAW:"
ls -lh "$IN"
echo
echo "GeoParquet 1.1:"
ls -lh "$OUT_V11"
echo
echo "GeoParquet 2.0:"
ls -lh "$OUT_V20"
