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
OPERATION="${OPERATION:-materialize}"
DUCKDB_PROFILE="${DUCKDB_PROFILE:-default}"
RUN_RETRIES="${RUN_RETRIES:-3}"
[[ -f java/target/classpath.txt ]] || {
	echo 'Run make build first' >&2
	exit 2
}
CP="java/target/classes:$(cat java/target/classpath.txt)"
[[ -f java-parquet/target/classpath.txt ]] || {
	echo 'Run make build first' >&2
	exit 2
}
PARQUET_JAVA_CP="java-parquet/target/classes:$(cat java-parquet/target/classpath.txt)"
case "$SCENARIO" in cold | warm | mixed) ;; *)
	echo 'SCENARIO must be cold, warm or mixed' >&2
	exit 2
	;;
esac
case "$OPERATION" in scan | materialize) ;; *) echo 'OPERATION must be scan or materialize' >&2; exit 2;; esac
mkdir -p "$(dirname "$OUT")"
[[ "$QUERIES" =~ ^[1-9][0-9]*$ ]] || {
	echo "QUERIES must be positive" >&2
	exit 2
}
[[ "$WARMUP" =~ ^[0-9]+$ ]] || {
	echo "WARMUP must be non-negative" >&2
	exit 2
}
[[ "$RUN_RETRIES" =~ ^[1-9][0-9]*$ ]] || {
	echo "RUN_RETRIES must be positive" >&2
	exit 2
}
for level in $LEVELS; do
	[[ "$level" =~ ^[1-9][0-9]*$ ]] || {
		echo "Invalid concurrency level: $level" >&2
		exit 2
	}
done
TMP_OUT="$(mktemp "${OUT}.tmp.XXXXXX")"
ENGINE_OUT=""
trap 'rm -f "$TMP_OUT" ${ENGINE_OUT:+"$ENGINE_OUT"}' EXIT
printf '%s\n' 'engine,scenario,operation,query_contract,concurrency,queries,wall_ms,qps,p50_ms,p95_ms,p99_ms,peak_rss_mb,total_rows,cache_profile,cache_implementation,sampling_interval_ms,execution_attempt' >"$TMP_OUT"
uv run --frozen python scripts/generate_run_metadata.py \
	--workload "$WORKLOAD" --bucket "$R2_BUCKET" --endpoint "$R2_ENDPOINT" --object "$OBJECT" \
	--operation "$OPERATION" --scenario "$SCENARIO" --concurrency "$LEVELS" \
	--query-count "$QUERIES" --warmup-count "$WARMUP" \
	--cache-configuration "duckdb=$DUCKDB_PROFILE;parquetry=tileverse_on;parquet-java=s3a_default"
for level in $LEVELS; do
	for engine in duckdb parquetry parquet-java; do
		echo "Running $engine scenario=$SCENARIO concurrency=$level queries=$QUERIES" >&2
		ENGINE_OUT="$(mktemp)"
		for ((attempt = 1; attempt <= RUN_RETRIES; attempt++)); do
			if [[ "$engine" == duckdb ]]; then
				command=(uv run --frozen python python/duckdb_r2_concurrent_bench.py --bucket "$R2_BUCKET" --object "$OBJECT" --workload "$WORKLOAD" --concurrency "$level" --queries "$QUERIES" --warmup "$WARMUP" --scenario "$SCENARIO" --operation "$OPERATION" --profile "$DUCKDB_PROFILE")
			elif [[ "$engine" == parquetry ]]; then
				command=(java --enable-preview --enable-native-access=ALL-UNNAMED -cp "$CP" io.geoverse.bench.ParquetryR2ConcurrentBench "$R2_BUCKET" "$OBJECT" "$WORKLOAD" "$R2_ENDPOINT" "$R2_REGION" "$level" "$QUERIES" "$WARMUP" "$SCENARIO" "$OPERATION")
			else
				command=("$PARQUET_JAVA_BIN" -cp "$PARQUET_JAVA_CP" io.geoverse.bench.ParquetJavaR2ConcurrentBench "$R2_BUCKET" "$OBJECT" "$WORKLOAD" "$R2_ENDPOINT" "$R2_REGION" "$level" "$QUERIES" "$WARMUP" "$SCENARIO" "$OPERATION")
			fi
			if "${command[@]}" >"$ENGINE_OUT"; then
				break
			fi
			if ((attempt == RUN_RETRIES)); then
				echo "FAILED: $engine concurrency=$level after $RUN_RETRIES attempts" >&2
				exit 1
			fi
			echo "Retrying $engine concurrency=$level after transient failure (attempt $((attempt + 1))/$RUN_RETRIES)" >&2
		done
		if [[ "$engine" == duckdb ]]; then
			tail -n +2 "$ENGINE_OUT" | awk -v profile="$DUCKDB_PROFILE" -v attempt="$attempt" '{printf "%s,%s,duckdb_internal,25,%d\n", $0, profile, attempt}' >>"$TMP_OUT"
		elif [[ "$engine" == parquetry ]]; then
			tail -n +2 "$ENGINE_OUT" | awk -v attempt="$attempt" '{printf "%s,tileverse_on,tileverse_caching_range_reader,25,%d\n", $0, attempt}' >>"$TMP_OUT"
		else
			tail -n +2 "$ENGINE_OUT" | awk -v attempt="$attempt" '{printf "%s,s3a_default,hadoop_s3a_default,25,%d\n", $0, attempt}' >>"$TMP_OUT"
		fi
		rm -f "$ENGINE_OUT"
		ENGINE_OUT=""
	done
done
mv "$TMP_OUT" "$OUT"
echo "Results: $OUT"
