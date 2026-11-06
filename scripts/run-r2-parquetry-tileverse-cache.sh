#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"

: "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID is required}"
: "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY is required}"

BUCKET="${R2_BUCKET:-geoparquet-benchmarks}"
ENDPOINT="${R2_ENDPOINT:-https://3e5316dddf480d9077bcc2009d58aa10.r2.cloudflarestorage.com}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-auto}}"
WORKLOAD="${WORKLOAD:-config/workload-cache-geographic.csv}"
ROUNDS="${ROUNDS:-2}"

mkdir -p results

echo "Compiling benchmark..."
(
	cd java
	mvn -q -DskipTests package dependency:build-classpath \
		-Dmdep.outputFile=target/classpath.txt
)
CP="java/target/classes:$(cat java/target/classpath.txt)"

OUT="results/r2-parquetry-tileverse-cache.csv"
echo "layout,mode,round,position,query,rows,elapsed_ms,remote_range_calls,remote_bytes_requested,remote_bytes_returned" >"$OUT"

run_case() {
	local layout="$1"
	local object="$2"
	local mode="$3"
	local tmp
	tmp="$(mktemp)"

	echo
	echo "=== ${layout} / ${mode} / ${ROUNDS} rounds ==="

	java --enable-preview \
		-cp "$CP" \
		io.geoverse.bench.ParquetryR2TileverseCacheBench \
		"$BUCKET" "$object" "$WORKLOAD" "$ENDPOINT" "$REGION" \
		"$mode" "$ROUNDS" | tee "$tmp"

	grep -E '^(nocache|cache),' "$tmp" |
		sed "s/^/${layout},/" >>"$OUT"

	rm -f "$tmp"
}

for mode in nocache cache; do
	run_case raw raw.parquet "$mode"
	run_case optimized-v1.1 optimized-v1.1.parquet "$mode"
	run_case optimized-v2.0 optimized-v2.0.parquet "$mode"
done

echo
echo "Results: $OUT"
cat "$OUT"
