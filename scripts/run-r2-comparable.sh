#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
WORKLOAD="${WORKLOAD:-config/workload-persistent.csv}"
WARMUPS="${WARMUPS:-2}"
LIMIT="${LIMIT:-0}"
OUT="${OUT:-results/r2-comparable-runs.csv}"
SUMMARY="${SUMMARY:-results/r2-comparable-summary.csv}"
[[ -f java/target/classpath.txt ]] || {
	echo "Run make build first" >&2
	exit 2
}
CP="java/target/classes:$(cat java/target/classpath.txt)"
echo 'layout,reader,query,rows,elapsed_ms' >"$OUT"
for spec in "raw:raw.parquet" "optimized-v1.1:optimized-v1.1.parquet" "optimized-v2.0:optimized-v2.0.parquet"; do
	layout="${spec%%:*}"
	object="${spec#*:}"
	echo "DuckDB: $layout"
	uv run --frozen python python/duckdb_r2_persistent_bench.py --bucket "$R2_BUCKET" --object "$object" --workload "$WORKLOAD" --warmups "$WARMUPS" --limit "$LIMIT" |
		awk -F, -v layout="$layout" '{print layout",duckdb,"$0}' >>"$OUT"
	echo "Parquetry: $layout"
	java --enable-preview --enable-native-access=ALL-UNNAMED -cp "$CP" io.multiversio.bench.ParquetryR2ComparableBench "$R2_BUCKET" "$object" "$WORKLOAD" "$WARMUPS" "$R2_ENDPOINT" "$R2_REGION" "$LIMIT" |
		awk -F, -v layout="$layout" '{print layout",parquetry,"$0}' >>"$OUT"
done
uv run --frozen python scripts/report-r2-comparable.py "$OUT" "$SUMMARY"
echo "Results: $OUT / $SUMMARY"
