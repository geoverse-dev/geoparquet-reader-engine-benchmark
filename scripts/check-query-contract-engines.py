#!/usr/bin/env python3
"""Compare bbox-v1 IDs from DuckDB, Parquetry, and parquet-java locally."""

import argparse
import base64
import collections
import subprocess
import sys
from pathlib import Path

import duckdb

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from python.query_contract import BBOX_SQL_PREDICATE, id_multiset_hash


def decode_probe(stdout):
    identifiers = []
    for line in stdout.splitlines():
        if line == "N":
            identifiers.append(None)
        elif line.startswith("S"):
            identifiers.append(base64.b64decode(line[1:]).decode("utf-8"))
        elif line:
            raise ValueError(f"Unexpected probe output: {line}")
    return identifiers


def run_probe(command):
    process = subprocess.run(command, check=True, capture_output=True, text=True)
    if process.stderr:
        print(process.stderr, file=sys.stderr, end="")
    return decode_probe(process.stdout)


def duckdb_ids(fixture, bbox):
    connection = duckdb.connect(":memory:")
    try:
        rows = connection.execute(
            f"SELECT id FROM read_parquet(?) WHERE {BBOX_SQL_PREDICATE}",
            [str(fixture), bbox[2], bbox[0], bbox[3], bbox[1]],
        ).fetchall()
    finally:
        connection.close()
    return [row[0] for row in rows]


def describe(name, identifiers):
    duplicates = sum(count - 1 for count in collections.Counter(identifiers).values())
    print(
        f"{name}: rows={len(identifiers)} duplicates={duplicates} "
        f"id_set_hash={id_multiset_hash(identifiers)}"
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture", type=Path)
    parser.add_argument("--java", required=True)
    parser.add_argument("--parquetry-classpath", required=True)
    parser.add_argument("--parquet-java-bin", required=True)
    parser.add_argument("--parquet-java-classpath", required=True)
    parser.add_argument("--bbox", nargs=4, type=float, default=(0, 0, 10, 10))
    args = parser.parse_args()
    bbox = tuple(args.bbox)
    common = [str(args.fixture), *(str(value) for value in bbox)]
    results = {
        "duckdb": duckdb_ids(args.fixture, bbox),
        "parquetry": run_probe(
            [
                args.java,
                "--enable-preview",
                "-cp",
                args.parquetry_classpath,
                "io.geoverse.bench.ParquetryLocalContractProbe",
                *common,
            ]
        ),
        "parquet-java": run_probe(
            [
                args.parquet_java_bin,
                "-cp",
                args.parquet_java_classpath,
                "io.geoverse.bench.ParquetJavaLocalContractProbe",
                *common,
            ]
        ),
    }
    expected = collections.Counter(results["duckdb"])
    failed = False
    for name, identifiers in results.items():
        describe(name, identifiers)
        if collections.Counter(identifiers) != expected:
            missing = list((expected - collections.Counter(identifiers)).elements())
            unexpected = list((collections.Counter(identifiers) - expected).elements())
            print(f"{name}: missing={missing} unexpected={unexpected}", file=sys.stderr)
            failed = True
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
