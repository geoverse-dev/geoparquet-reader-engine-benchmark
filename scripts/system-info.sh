#!/usr/bin/env bash
set -euo pipefail
mkdir -p results
{
	echo "date_utc=$(date -u +%FT%TZ)"
	echo "uname=$(uname -a)"
	echo "cores=$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)"
	echo "python=$(python3 --version 2>&1 || true)"
	echo "java=$(java -version 2>&1 | head -1 || true)"
	echo "gpio=$(uv run gpio --version 2>&1 || true)"
	echo "parquetry_commit=$(git -C .parquetry-src rev-parse HEAD 2>/dev/null || true)"
} | tee results/system-info.txt
