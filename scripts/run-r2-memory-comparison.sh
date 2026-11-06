#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
: "${AWS_ACCESS_KEY_ID:?export R2 credentials first}"
: "${AWS_SECRET_ACCESS_KEY:?export R2 credentials first}"
export R2_ENDPOINT="${R2_ENDPOINT:-https://3e5316dddf480d9077bcc2009d58aa10.r2.cloudflarestorage.com}"
BUCKET="${R2_BUCKET:-geoparquet-benchmarks}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-auto}}"
WORKLOAD="${WORKLOAD:-config/workload-memory.csv}"
mkdir -p results

(cd java && mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt)
CP="java/target/classes:$(cat java/target/classpath.txt)"

echo "engine,layout,position,query,rows,elapsed_ms,rss_bytes,rss_delta_bytes,peak_rss_bytes" >results/r2-duckdb-memory.csv
echo "engine,layout,position,query,rows,elapsed_ms,rss_bytes,rss_delta_bytes,peak_rss_bytes,heap_used_bytes,cache_estimated_bytes,cache_entries,cache_hits,cache_misses,cache_stats" >results/r2-parquetry-memory.csv

for spec in "raw:raw.parquet" "optimized-v1.1:optimized-v1.1.parquet" "optimized-v2.0:optimized-v2.0.parquet"; do
	layout="${spec%%:*}"
	obj="${spec#*:}"
	echo "=== DuckDB $layout ==="
	uv run python python/duckdb_r2_memory_bench.py \
		--url "s3://${BUCKET}/${obj}" --workload "$WORKLOAD" --layout "$layout" |
		tee /tmp/duck-mem.txt
	grep '^duckdb,' /tmp/duck-mem.txt >>results/r2-duckdb-memory.csv

	echo "=== Parquetry+cache $layout ==="
	java --enable-preview -cp "$CP" io.geoverse.bench.ParquetryR2MemoryBench \
		"$BUCKET" "$obj" "$WORKLOAD" "$R2_ENDPOINT" "$REGION" "$layout" |
		tee /tmp/parq-mem.txt
	grep '^parquetry-cache,' /tmp/parq-mem.txt >>results/r2-parquetry-memory.csv
done

echo "DuckDB:    results/r2-duckdb-memory.csv"
echo "Parquetry: results/r2-parquetry-memory.csv"
