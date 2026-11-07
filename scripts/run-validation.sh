#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"

WORKLOAD="${WORKLOAD:-config/workload-persistent.csv}"
LIMIT="${LIMIT:-0}"
OBJECT="${OBJECT:-}"
STRICT="${STRICT:-on}"
out_dir="results/validation"
raw="$out_dir/validation-raw.csv"
mkdir -p "$out_dir"
printf 'dataset,bbox_name,engine,id,id_is_null,error\n' >"$raw"

[[ -f java/target/classpath.txt && -f java-parquet/target/classpath.txt ]] || { echo "Run make build first" >&2; exit 2; }
parquetry_cp="java/target/classes:$(cat java/target/classpath.txt)"
parquet_java_cp="java-parquet/target/classes:$(cat java-parquet/target/classpath.txt)"

objects=(raw.parquet optimized-v1.1.parquet optimized-v2.0.parquet)
if [[ -n "$OBJECT" ]]; then objects=("$OBJECT"); fi
for object in "${objects[@]}"; do
    for engine in duckdb parquetry parquet-java; do
        tmp="$(mktemp)"
        case "$engine" in
        duckdb) uv run --frozen python python/duckdb_r2_validation.py --bucket "$R2_BUCKET" --object "$object" --workload "$WORKLOAD" --limit "$LIMIT" >"$tmp" ;;
        parquetry) java --enable-preview --enable-native-access=ALL-UNNAMED -cp "$parquetry_cp" io.geoverse.bench.ParquetryR2Validation "$R2_BUCKET" "$object" "$WORKLOAD" "$R2_ENDPOINT" "$R2_REGION" "$LIMIT" on >"$tmp" ;;
        parquet-java) "$PARQUET_JAVA_BIN" -cp "$parquet_java_cp" io.geoverse.bench.ParquetJavaR2Validation "$R2_BUCKET" "$object" "$WORKLOAD" "$R2_ENDPOINT" "$R2_REGION" "$LIMIT" >"$tmp" ;;
        esac
        uv run --frozen python scripts/collect_validation.py "$object" "$engine" "$tmp" "$raw"
        rm "$tmp"
    done
done

strict_args=()
if [[ "$STRICT" == off ]]; then strict_args+=(--no-strict); fi
uv run --frozen python scripts/validate_results.py "$raw" --output-dir "$out_dir" "${strict_args[@]}"
