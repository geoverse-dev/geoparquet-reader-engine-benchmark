#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
WORKLOAD="${WORKLOAD:-config/workload-persistent.csv}"
[[ -f java/target/classpath.txt ]] || {
	echo "Run make build first" >&2
	exit 2
}
CP="java/target/classes:$(cat java/target/classpath.txt)"
for spec in "raw:raw.parquet" "optimized-v1.1:optimized-v1.1.parquet" "optimized-v2.0:optimized-v2.0.parquet"; do
	layout="${spec%%:*}"
	object="${spec#*:}"
	echo "=== $layout / DuckDB ==="
	uv run --frozen python python/duckdb_r2_io_diag.py --bucket "$R2_BUCKET" --object "$object" --workload "$WORKLOAD"
	echo "=== $layout / Parquetry ==="
	java --enable-preview --enable-native-access=ALL-UNNAMED -cp "$CP" io.multiversio.bench.ParquetryR2IoDiag "$R2_BUCKET" "$object" "$WORKLOAD" "$R2_ENDPOINT" "$R2_REGION"
done
