#!/usr/bin/env bash
set -euo pipefail

COUNT="${STRESS_BBOX_COUNT:-500}"
case "$COUNT" in
500|1000) ;;
*)
	echo "STRESS_BBOX_COUNT must be 500 or 1000, got: $COUNT" >&2
	exit 2
	;;
esac

SOURCE_WORKLOAD="${SOURCE_WORKLOAD:-config/workload-persistent.csv}"
WORKLOAD="results/stress/workload-${COUNT}.csv"
OUT="${OUT:-results/stress/r2-stress-${COUNT}-runs.csv}"
SUMMARY="${SUMMARY:-results/stress/r2-stress-${COUNT}-summary.csv}"

uv run --frozen python scripts/generate_stress_workload.py \
	--source "$SOURCE_WORKLOAD" --output "$WORKLOAD" --count "$COUNT"

echo "Running explicit sustained BBOX stress suite: queries=$COUNT" >&2
echo "This suite is not part of benchmark-suite or the default make target." >&2
WORKLOAD="$WORKLOAD" LIMIT=0 WARMUPS="${WARMUPS:-0}" \
	OPERATION="${OPERATION:-materialize}" OUT="$OUT" SUMMARY="$SUMMARY" \
	bash scripts/run-r2-comparable.sh
