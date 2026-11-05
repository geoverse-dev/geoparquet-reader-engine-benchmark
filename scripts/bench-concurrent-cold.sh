#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RUNS="${RUNS:-3}"
LEVELS="${LEVELS:-1 2 4 8}"
QUERIES="${QUERIES:-40}"
DEST="${REPORT_DIR:-results/report-concurrency}"
[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || {
	echo "RUNS must be positive" >&2
	exit 2
}
mkdir -p "$DEST"
# Remove only prior results for this scenario, avoiding stale runs in summaries.
rm -f "$DEST"/cold-run-*.csv "$DEST"/cold-summary.csv

for ((run = 1; run <= RUNS; run++)); do
	printf '=== COLD RUN %s/%s ===\n' "$run" "$RUNS"
	SCENARIO=cold LEVELS="$LEVELS" QUERIES="$QUERIES" \
		OUT="$DEST/cold-run-${run}.csv" bash scripts/run-r2-concurrent.sh
done

python3 scripts/aggregate-concurrent.py --input-dir "$DEST" --scenario cold
echo "Saved cold runs and summary in $DEST"
