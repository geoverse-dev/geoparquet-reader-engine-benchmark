#!/usr/bin/env bash
set -euo pipefail

WORKLOAD="${WORKLOAD:-config/workload-persistent.csv}"
WARMUPS="${WARMUPS:-2}"
LIMIT="${LIMIT:-0}"
PARQUETRY_CACHE="${PARQUETRY_CACHE:-on}"
OPERATION="${OPERATION:-materialize}"
QUERY_CONTRACT="bbox-v1"
DUCKDB_PROFILE="${DUCKDB_PROFILE:-default}"
case "$OPERATION" in scan|materialize) ;; *) echo "OPERATION must be scan or materialize" >&2; exit 2;; esac

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

PARQUET_JAVA_CONFIG="parquet-java + Hadoop S3A defaults"
PARQUET_JAVA_ROW_CACHE_MODE="s3a_default"

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
[[ -f java-parquet/target/classpath.txt ]] || {
	echo "Run make build first" >&2
	exit 2
}

CP="java/target/classes:$(cat java/target/classpath.txt)"
PARQUET_JAVA_CP="java-parquet/target/classes:$(cat java-parquet/target/classpath.txt)"
HEADER='layout,reader,reader_config,benchmark_mode,cache_mode,cache_lifecycle,warmups,operation,query_contract,query,rows,elapsed_ms,result_checksum,delegate_read_calls,delegate_bytes_requested,delegate_bytes_returned,cache_enabled,cache_implementation,cache_capacity_bytes,cache_configuration,http_requests,network_bytes_transferred'
echo "$HEADER" >"$OUT"

query_count="$LIMIT"
if [[ "$query_count" == 0 ]]; then query_count="$(($(wc -l <"$WORKLOAD") - 1))"; fi
uv run --frozen python scripts/generate_run_metadata.py \
	--workload "$WORKLOAD" --bucket "$R2_BUCKET" --endpoint "$R2_ENDPOINT" \
	--object raw.parquet --object optimized-v1.1.parquet --object optimized-v2.0.parquet \
	--operation "$OPERATION" --scenario sequential --concurrency 1 \
	--query-count "$query_count" --warmup-count "$WARMUPS" \
	--cache-configuration "duckdb=$DUCKDB_PROFILE;parquetry=$PARQUETRY_CACHE;parquet-java=s3a_default"

for spec in "raw:raw.parquet" "optimized-v1.1:optimized-v1.1.parquet" "optimized-v2.0:optimized-v2.0.parquet"; do
	layout="${spec%%:*}"
	object="${spec#*:}"

	echo "DuckDB default: $layout"
	uv run --frozen python python/duckdb_r2_persistent_bench.py \
		--bucket "$R2_BUCKET" \
		--object "$object" \
		--workload "$WORKLOAD" \
		--warmups "$WARMUPS" \
		--limit "$LIMIT" --operation "$OPERATION" --profile "$DUCKDB_PROFILE" |
		awk -F, \
			-v layout="$layout" \
			-v benchmark_mode="$BENCHMARK_MODE" \
			-v lifecycle="$CACHE_LIFECYCLE" \
			-v warmups="$WARMUPS" \
			-v operation="$OPERATION" -v contract="$QUERY_CONTRACT" \
			-v profile="$DUCKDB_PROFILE" '{print layout",duckdb,DuckDB "profile","benchmark_mode",duckdb_"profile","lifecycle","warmups","operation","contract","$0",not_available,not_available,not_available,true,duckdb_internal,not_available,"profile",not_available,not_available"}' >>"$OUT"

	echo "$PARQUETRY_CONFIG: $layout"
	java --enable-preview --enable-native-access=ALL-UNNAMED \
		-cp "$CP" \
		io.geoverse.bench.ParquetryR2ComparableBench \
		"$R2_BUCKET" "$object" "$WORKLOAD" "$WARMUPS" \
		"$R2_ENDPOINT" "$R2_REGION" "$LIMIT" "$PARQUETRY_CACHE" "$OPERATION" |
		awk -F, \
			-v layout="$layout" \
			-v reader_config="$PARQUETRY_CONFIG" \
			-v benchmark_mode="$BENCHMARK_MODE" \
			-v cache_mode="$PARQUETRY_ROW_CACHE_MODE" \
			-v lifecycle="$CACHE_LIFECYCLE" \
			-v warmups="$WARMUPS" \
			-v operation="$OPERATION" -v contract="$QUERY_CONTRACT" \
			'{print layout",parquetry,"reader_config","benchmark_mode","cache_mode","lifecycle","warmups","operation","contract","$0",true,tileverse_caching_range_reader,not_available,"cache_mode",not_available,not_available"}' >>"$OUT"

	echo "$PARQUET_JAVA_CONFIG: $layout"
	"$PARQUET_JAVA_BIN" \
		-cp "$PARQUET_JAVA_CP" \
		io.geoverse.bench.ParquetJavaR2ComparableBench \
		"$R2_BUCKET" "$object" "$WORKLOAD" "$WARMUPS" \
		"$R2_ENDPOINT" "$R2_REGION" "$LIMIT" "$OPERATION" |
		awk -F, \
			-v layout="$layout" \
			-v reader_config="$PARQUET_JAVA_CONFIG" \
			-v benchmark_mode="$BENCHMARK_MODE" \
			-v cache_mode="$PARQUET_JAVA_ROW_CACHE_MODE" \
			-v lifecycle="$CACHE_LIFECYCLE" \
			-v warmups="$WARMUPS" \
			-v operation="$OPERATION" -v contract="$QUERY_CONTRACT" \
			'{print layout",parquet-java,"reader_config","benchmark_mode","cache_mode","lifecycle","warmups","operation","contract","$0",not_available,hadoop_s3a_default,not_available,s3a_default,not_available,not_available"}' >>"$OUT"
done

uv run --frozen python scripts/report-r2-comparable.py "$OUT" "$SUMMARY"
echo "Results: $OUT / $SUMMARY"
