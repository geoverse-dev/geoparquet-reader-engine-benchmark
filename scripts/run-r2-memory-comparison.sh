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
(cd java-parquet && mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt)
CP="java/target/classes:$(cat java/target/classpath.txt)"
PARQUET_JAVA_CP="java-parquet/target/classes:$(cat java-parquet/target/classpath.txt)"

query_count="$(($(wc -l <"$WORKLOAD") - 1))"
uv run --frozen python scripts/generate_run_metadata.py \
	--workload "$WORKLOAD" --bucket "$BUCKET" --endpoint "$R2_ENDPOINT" \
	--object raw.parquet --object optimized-v1.1.parquet --object optimized-v2.0.parquet \
	--operation materialize --scenario memory --concurrency 1 --query-count "$query_count" \
	--warmup-count 0 --cache-configuration "duckdb=default;duckdb-arrow=default;parquetry=tileverse_on;parquet-java=s3a_default"

echo "engine,layout,position,query,rows,elapsed_ms,result_checksum,rss_bytes,rss_delta_bytes,peak_rss_bytes,duckdb_memory_limit,jvm_heap_used_bytes,jvm_heap_committed_bytes,jvm_non_heap_used_bytes,jvm_direct_buffer_bytes,gc_count,gc_pause_ms,sampling_interval_ms" >results/r2-duckdb-memory.csv
echo "engine,layout,position,query,rows,elapsed_ms,result_checksum,rss_bytes,rss_delta_bytes,peak_rss_bytes,duckdb_memory_limit,jvm_heap_used_bytes,jvm_heap_committed_bytes,jvm_non_heap_used_bytes,jvm_direct_buffer_bytes,gc_count,gc_pause_ms,sampling_interval_ms,read_materialize_ms,validation_ms" >results/r2-duckdb-arrow-memory.csv
echo "engine,layout,position,query,rows,elapsed_ms,result_checksum,rss_bytes,rss_delta_bytes,peak_rss_bytes,jvm_heap_used_bytes,jvm_heap_committed_bytes,jvm_non_heap_used_bytes,jvm_direct_buffer_bytes,gc_count,gc_pause_ms,sampling_interval_ms,cache_estimated_bytes,cache_entries,cache_hits,cache_misses,cache_stats" >results/r2-parquetry-memory.csv
echo "engine,layout,position,query,rows,elapsed_ms,result_checksum,rss_bytes,rss_delta_bytes,peak_rss_bytes,jvm_heap_used_bytes,jvm_heap_committed_bytes,jvm_non_heap_used_bytes,jvm_direct_buffer_bytes,gc_count,gc_pause_ms,sampling_interval_ms" >results/r2-parquet-java-memory.csv

for spec in "raw:raw.parquet" "optimized-v1.1:optimized-v1.1.parquet" "optimized-v2.0:optimized-v2.0.parquet"; do
	layout="${spec%%:*}"
	obj="${spec#*:}"
	echo "=== DuckDB $layout ==="
	uv run python python/duckdb_r2_memory_bench.py \
		--url "s3://${BUCKET}/${obj}" --workload "$WORKLOAD" --layout "$layout" |
		tee /tmp/duck-mem.txt
	grep '^duckdb,' /tmp/duck-mem.txt >>results/r2-duckdb-memory.csv

	echo "=== DuckDB Arrow $layout ==="
	uv run python python/duckdb_r2_memory_bench.py \
		--url "s3://${BUCKET}/${obj}" --workload "$WORKLOAD" --layout "$layout" \
		--materialization arrow | tee /tmp/duck-arrow-mem.txt
	grep '^duckdb-arrow,' /tmp/duck-arrow-mem.txt >>results/r2-duckdb-arrow-memory.csv

	echo "=== Parquetry+cache $layout ==="
	java --enable-preview -cp "$CP" io.geoverse.bench.ParquetryR2MemoryBench \
		"$BUCKET" "$obj" "$WORKLOAD" "$R2_ENDPOINT" "$REGION" "$layout" |
		tee /tmp/parq-mem.txt
	grep '^parquetry-cache,' /tmp/parq-mem.txt >>results/r2-parquetry-memory.csv

	echo "=== parquet-java+S3A $layout ==="
	"$PARQUET_JAVA_BIN" -cp "$PARQUET_JAVA_CP" io.geoverse.bench.ParquetJavaR2MemoryBench \
		"$BUCKET" "$obj" "$WORKLOAD" "$R2_ENDPOINT" "$REGION" "$layout" |
		tee /tmp/parquet-java-mem.txt
	grep '^parquet-java-s3a,' /tmp/parquet-java-mem.txt >>results/r2-parquet-java-memory.csv
done

echo "DuckDB:    results/r2-duckdb-memory.csv"
echo "DuckDB Arrow: results/r2-duckdb-arrow-memory.csv"
echo "Parquetry: results/r2-parquetry-memory.csv"
echo "parquet-java: results/r2-parquet-java-memory.csv"
