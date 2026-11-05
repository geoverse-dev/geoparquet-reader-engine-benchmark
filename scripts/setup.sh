#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
for cmd in uv git java mvn; do command -v "$cmd" >/dev/null || {
	echo "Missing dependency: $cmd" >&2
	exit 2
}; done
java -version
uv sync --frozen
if [[ ! -f vendor/parquetry/pom.xml ]]; then
	git clone https://github.com/tileverse-io/parquetry.git vendor/parquetry
fi
PARQUETRY_REF="${PARQUETRY_REF:-876687bc865fb3fc0526bd3c7d69d030db6a7d44}"
git -C vendor/parquetry checkout "$PARQUETRY_REF"
git -C vendor/parquetry submodule update --init --recursive
echo "Parquetry source: $(git -C vendor/parquetry rev-parse HEAD)"
bash scripts/build-java.sh
