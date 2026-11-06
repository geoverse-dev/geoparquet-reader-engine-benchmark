#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
WORKLOAD="${WORKLOAD:-config/workload-persistent.csv}"
OBJECT="${OBJECT:-optimized-v1.1.parquet}"
QUERIES="${QUERIES:-40}"
WARMUP="${WARMUP:-1}"
SCENARIO="${SCENARIO:-warm}"
LEVELS="${LEVELS:-1 2 4 8 16}"
OUT="${OUT:-results/r2-concurrent.csv}"
[[ -f java/target/classpath.txt ]] || {
	echo 'Run make build first' >&2
	exit 2
}
CP="java/target/classes:$(cat java/target/classpath.txt)"
case "$SCENARIO" in cold | warm | mixed) ;; *)
	echo 'SCENARIO must be cold, warm or mixed' >&2
	exit 2
	;;
esac
mkdir -p "$(dirname "$OUT")"
[[ "$QUERIES" =~ ^[1-9][0-9]*$ ]] || {
	echo "QUERIES must be positive" >&2
	exit 2
}
[[ "$WARMUP" =~ ^[0-9]+$ ]] || {
	echo "WARMUP must be non-negative" >&2
	exit 2
}
for level in $LEVELS; do
	[[ "$level" =~ ^[1-9][0-9]*$ ]] || {
		echo "Invalid concurrency level: $level" >&2
		exit 2
	}
done
TMP_OUT="$(mktemp "${OUT}.tmp.XXXXXX")"
trap 'rm -f "$TMP_OUT"' EXIT
printf '%s\n' 'engine,scenario,concurrency,queries,wall_ms,qps,p50_ms,p95_ms,p99_ms,peak_rss_mb,total_rows' >"$TMP_OUT"
for level in $LEVELS; do
	for engine in duckdb parquetry; do
		echo "Running $engine scenario=$SCENARIO concurrency=$level queries=$QUERIES" >&2
		if [[ "$engine" == duckdb ]]; then
			uv run --frozen python python/duckdb_r2_concurrent_bench.py --bucket "$R2_BUCKET" --object "$OBJECT" --workload "$WORKLOAD" --concurrency "$level" --queries "$QUERIES" --warmup "$WARMUP" --scenario "$SCENARIO" | tail -n +2 >>"$TMP_OUT"
		else
			java --enable-preview --enable-native-access=ALL-UNNAMED -cp "$CP" io.geoverse.bench.ParquetryR2ConcurrentBench "$R2_BUCKET" "$OBJECT" "$WORKLOAD" "$R2_ENDPOINT" "$R2_REGION" "$level" "$QUERIES" "$WARMUP" "$SCENARIO" | tail -n +2 >>"$TMP_OUT"
		fi
	done
done
mv "$TMP_OUT" "$OUT"
echo "Results: $OUT"
