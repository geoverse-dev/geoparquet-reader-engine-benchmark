#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
: "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID is required}"
: "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY is required}"
BUCKET="${R2_BUCKET:-geoparquet-benchmarks}"
ENDPOINT="${R2_ENDPOINT:-https://3e5316dddf480d9077bcc2009d58aa10.r2.cloudflarestorage.com}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-auto}}"
WORKLOAD="${WORKLOAD:-config/workload-geographic-jumps.csv}"
mkdir -p results
DOUT=results/r2-geographic-jumps-duckdb.csv
POUT=results/r2-geographic-jumps-parquetry.csv
echo "layout,position,query,rows,elapsed_ms,http_requests,http_gets,http_heads,fs_read_ops,fs_bytes" >"$DOUT"
echo "layout,position,query,rows,elapsed_ms,range_calls,range_bytes_requested,range_bytes_returned" >"$POUT"
(cd java && mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt)
CP="java/target/classes:$(cat java/target/classpath.txt)"
run_one() {
	local layout="$1" object="$2" tmp
	echo "=== $layout / duckdb ==="
	tmp=$(mktemp)
	uv run python python/duckdb_r2_geographic_jump_bench.py --bucket "$BUCKET" --object "$object" --workload "$WORKLOAD" --endpoint "$ENDPOINT" --region "$REGION" | tee "$tmp"
	tail -n +2 "$tmp" | sed "s/^/${layout},/" >>"$DOUT"
	rm "$tmp"
	echo "=== $layout / parquetry ==="
	tmp=$(mktemp)
	java --enable-preview -cp "$CP" io.geoverse.bench.ParquetryR2GeographicJumpBench "$BUCKET" "$object" "$WORKLOAD" "$ENDPOINT" "$REGION" | tee "$tmp"
	grep -E '^[0-9]+,' "$tmp" | sed "s/^/${layout},/" >>"$POUT"
	rm "$tmp"
}
run_one raw raw.parquet
run_one optimized-v1.1 optimized-v1.1.parquet
run_one optimized-v2.0 optimized-v2.0.parquet
echo
echo "DuckDB: $DOUT"
cat "$DOUT"
echo
echo "Parquetry: $POUT"
cat "$POUT"
