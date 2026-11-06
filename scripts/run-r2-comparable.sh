#!/usr/bin/env bash
set -euo pipefail

WORKLOAD="${WORKLOAD:-config/workload-persistent.csv}"
WARMUPS="${WARMUPS:-2}"
LIMIT="${LIMIT:-0}"
PARQUETRY_CACHE="${PARQUETRY_CACHE:-on}"

case "$PARQUETRY_CACHE" in
on)
	BENCHMARK_MODE="cache_aligned"
	DEFAULT_OUT="results/r2-comparable-runs.csv"
	DEFAULT_SUMMARY="results/r2-comparable-summary.csv"
	PARQUETRY_CONFIG="Parquetry + Tileverse Cache"
	PARQUETRY_ROW_CACHE_MODE="tileverse_on"
	;;
off)
	BENCHMARK_MODE="nocache_diagnostic"
	DEFAULT_OUT="results/r2-comparable-nocache-runs.csv"
	DEFAULT_SUMMARY="results/r2-comparable-nocache-summary.csv"
	PARQUETRY_CONFIG="Parquetry no Tileverse Cache"
	PARQUETRY_ROW_CACHE_MODE="tileverse_off"
	echo "WARNING: PARQUETRY_CACHE=off is a diagnostic run, not the primary comparison." >&2
	;;
*)
	echo "PARQUETRY_CACHE must be 'on' or 'off', got: $PARQUETRY_CACHE" >&2
	exit 2
	;;
esac

source "$(dirname "$0")/env.sh"

OUT="${OUT:-$DEFAULT_OUT}"
SUMMARY="${SUMMARY:-$DEFAULT_SUMMARY}"
CACHE_LIFECYCLE="persistent_warm_session"
if [[ "$WARMUPS" == 0 ]]; then
	CACHE_LIFECYCLE="first_access_session"
fi

[[ -f java/target/classpath.txt ]] || {
	echo "Run make build first" >&2
	exit 2
}

CP="java/target/classes:$(cat java/target/classpath.txt)"
HEADER='layout,reader,reader_config,benchmark_mode,cache_mode,cache_lifecycle,warmups,query,rows,elapsed_ms,remote_range_calls,remote_bytes_requested,remote_bytes_returned'
echo "$HEADER" >"$OUT"

for spec in "raw:raw.parquet" "optimized-v1.1:optimized-v1.1.parquet" "optimized-v2.0:optimized-v2.0.parquet"; do
	layout="${spec%%:*}"
	object="${spec#*:}"

	echo "DuckDB default: $layout"
	uv run --frozen python python/duckdb_r2_persistent_bench.py \
		--bucket "$R2_BUCKET" \
		--object "$object" \
		--workload "$WORKLOAD" \
		--warmups "$WARMUPS" \
		--limit "$LIMIT" |
		awk -F, \
			-v layout="$layout" \
			-v benchmark_mode="$BENCHMARK_MODE" \
			-v lifecycle="$CACHE_LIFECYCLE" \
			-v warmups="$WARMUPS" \
			'{print layout",duckdb,DuckDB default,"benchmark_mode",duckdb_default,"lifecycle","warmups","$0",,,"}' >>"$OUT"

	echo "$PARQUETRY_CONFIG: $layout"
	java --enable-preview --enable-native-access=ALL-UNNAMED \
		-cp "$CP" \
		io.geoverse.bench.ParquetryR2ComparableBench \
		"$R2_BUCKET" "$object" "$WORKLOAD" "$WARMUPS" \
		"$R2_ENDPOINT" "$R2_REGION" "$LIMIT" "$PARQUETRY_CACHE" |
		awk -F, \
			-v layout="$layout" \
			-v reader_config="$PARQUETRY_CONFIG" \
			-v benchmark_mode="$BENCHMARK_MODE" \
			-v cache_mode="$PARQUETRY_ROW_CACHE_MODE" \
			-v lifecycle="$CACHE_LIFECYCLE" \
			-v warmups="$WARMUPS" \
			'{print layout",parquetry,"reader_config","benchmark_mode","cache_mode","lifecycle","warmups","$0}' >>"$OUT"
done

uv run --frozen python scripts/report-r2-comparable.py "$OUT" "$SUMMARY"
echo "Results: $OUT / $SUMMARY"
