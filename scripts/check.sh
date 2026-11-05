#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for cmd in uv java mvn git curl; do command -v "$cmd" >/dev/null && echo "OK $cmd" || echo "MISSING $cmd"; done
java -version 2>&1 | head -n 1
uv --version || true
[[ -f vendor/parquetry/pom.xml ]] && echo "OK vendor/parquetry" || echo "MISSING vendor/parquetry (make setup)"
[[ -f java/target/classpath.txt ]] && echo "OK java build" || echo "MISSING java build (make build)"
for f in data/raw/raw.parquet data/optimized/optimized-v1.1.parquet data/optimized/optimized-v2.0.parquet; do
	[[ -f "$f" ]] && echo "LOCAL $f" || echo "NOT LOCAL $f"
done
