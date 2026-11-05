#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${PUBLIC_BASE_URL:-https://pub-f31f4e8d3c804f2bab542268b9915ad7.r2.dev}"
for spec in "raw.parquet:data/raw/raw.parquet" "optimized-v1.1.parquet:data/optimized/optimized-v1.1.parquet" "optimized-v2.0.parquet:data/optimized/optimized-v2.0.parquet"; do
	object="${spec%%:*}"
	dest="${spec#*:}"
	[[ -s "$dest" ]] && {
		echo "Already exists: $dest"
		continue
	}
	echo "Downloading $object (approx. 2.3 GiB)..."
	mkdir -p "$(dirname "$dest")"
	curl -fL --retry 5 --retry-delay 3 -C - "$BASE/$object" -o "$dest"
done
