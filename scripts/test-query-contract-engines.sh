#!/usr/bin/env bash
set -euo pipefail
[[ -f java/target/classpath.txt && -f java-parquet/target/classpath.txt ]] || {
	echo "Run make build first" >&2
	exit 2
}

fixture_dir="$(mktemp -d)"
trap 'rm -r "$fixture_dir"' EXIT
fixture="$fixture_dir/query-contract.parquet"
uv run --frozen python tests/fixtures/generate_query_contract_fixture.py "$fixture"

parquet_java_bin="${PARQUET_JAVA_BIN:-java}"

parquetry_cp="java/target/classes:$(cat java/target/classpath.txt)"
parquet_java_cp="java-parquet/target/classes:$(cat java-parquet/target/classpath.txt)"
uv run --frozen python scripts/check-query-contract-engines.py \
	"$fixture" \
	--java java \
	--parquetry-classpath "$parquetry_cp" \
	--parquet-java-bin "$parquet_java_bin" \
	--parquet-java-classpath "$parquet_java_cp"
