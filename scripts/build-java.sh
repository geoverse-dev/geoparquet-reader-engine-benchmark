#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f vendor/parquetry/pom.xml ]]; then
	echo "Missing Parquetry source. Run make setup first." >&2
	exit 2
fi

(
	cd vendor/parquetry

	if [[ -x ./mvnw ]]; then
		MVN=(./mvnw)
	else
		MVN=(mvn)
	fi

	# Install Parquetry and its dependency-management POM for the benchmark.
	# Tileverse Storage is now an external dependency managed by Parquetry.
	"${MVN[@]}" -q -pl :parquetry-catalog -am \
		-Dmaven.test.skip=true install
)

(
	cd java
	mvn -q -Dmaven.test.skip=true package dependency:build-classpath \
		-Dmdep.outputFile=target/classpath.txt
)
