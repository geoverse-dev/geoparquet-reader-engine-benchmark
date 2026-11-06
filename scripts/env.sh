#!/usr/bin/env bash
# Source this file from scripts, do not execute directly.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export R2_BUCKET="${R2_BUCKET:-geoparquet-benchmarks}"
export R2_ENDPOINT="${R2_ENDPOINT:-https://3e5316dddf480d9077bcc2009d58aa10.r2.cloudflarestorage.com}"
export R2_REGION="${R2_REGION:-auto}"
export AWS_REGION="${AWS_REGION:-$R2_REGION}"

# Hadoop S3A is not compatible with Java 24+, while Parquetry currently needs
# Java 25. Keep parquet-java on a conventional LTS runtime in its own process.
if [[ -z "${PARQUET_JAVA_BIN:-}" ]]; then
	if [[ -n "${JAVA17_HOME:-}" && -x "${JAVA17_HOME}/bin/java" ]]; then
		export PARQUET_JAVA_BIN="${JAVA17_HOME}/bin/java"
	elif [[ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]]; then
		export PARQUET_JAVA_BIN=/usr/lib/jvm/java-17-openjdk-amd64/bin/java
	else
		export PARQUET_JAVA_BIN=java
	fi
fi
if [[ -z "${AWS_ACCESS_KEY_ID:-}" || -z "${AWS_SECRET_ACCESS_KEY:-}" ]]; then
	if command -v aws >/dev/null && aws configure list-profiles | grep -Fxq "${R2_PROFILE:-r2-benchmark}"; then
		export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-$(aws configure get aws_access_key_id --profile "${R2_PROFILE:-r2-benchmark}")}"
		export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-$(aws configure get aws_secret_access_key --profile "${R2_PROFILE:-r2-benchmark}")}"
	fi
fi
if [[ -z "${AWS_ACCESS_KEY_ID:-}" || -z "${AWS_SECRET_ACCESS_KEY:-}" ]]; then
	echo "Missing R2 credentials. Export AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (or set up AWS profile r2-benchmark)." >&2
	exit 2
fi
mkdir -p results
