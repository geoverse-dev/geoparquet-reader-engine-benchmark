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
git -C vendor/parquetry fetch --prune --tags origin
PARQUETRY_REF="${PARQUETRY_REF:-origin/main}"
git -C vendor/parquetry checkout --detach "$PARQUETRY_REF"
git -C vendor/parquetry submodule update --init --recursive
echo "Parquetry source: $(git -C vendor/parquetry rev-parse HEAD)"
bash scripts/build-java.sh
