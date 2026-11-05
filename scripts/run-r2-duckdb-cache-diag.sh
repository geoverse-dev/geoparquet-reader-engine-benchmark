#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
: "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID is required}"
: "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY is required}"
BUCKET="${R2_BUCKET:-geoparquet-benchmarks}"
ENDPOINT="${R2_ENDPOINT:-https://3e5316dddf480d9077bcc2009d58aa10.r2.cloudflarestorage.com}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-auto}}"
WORKLOAD="${WORKLOAD:-config/workload-persistent.csv}"
POSITIONS="${POSITIONS:-1,2,10,50}"
mkdir -p results
OUT="results/r2-duckdb-cache-diag.csv"
echo "layout,position,query,rows,elapsed_ms,http_requests,http_gets,http_heads,fs_read_ops,fs_bytes" >"$OUT"
run_one() {
	local layout="$1" object="$2" tmp
	tmp="$(mktemp)"
	echo "=== $layout / duckdb ==="
	uv run python python/duckdb_r2_cache_diag.py --bucket "$BUCKET" --object "$object" \
		--workload "$WORKLOAD" --endpoint "$ENDPOINT" --region "$REGION" --positions "$POSITIONS" | tee "$tmp"
	tail -n +2 "$tmp" | sed "s/^/${layout},/" >>"$OUT"
	rm -f "$tmp"
}
run_one raw raw.parquet
run_one optimized-v1.1 optimized-v1.1.parquet
run_one optimized-v2.0 optimized-v2.0.parquet
echo
echo "Combined results: $OUT"
cat "$OUT"
