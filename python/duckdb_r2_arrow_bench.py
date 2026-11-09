#!/usr/bin/env python3
"""Persistent DuckDB benchmark materializing results through Arrow."""

import argparse
import csv
import os
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

import duckdb

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from python.arrow_materialization import materialized_arrow_hash
from python.cache_profile import apply_duckdb_profile

parser = argparse.ArgumentParser()
parser.add_argument("--bucket", required=True)
parser.add_argument("--object", required=True)
parser.add_argument("--workload", required=True)
parser.add_argument("--warmups", type=int, default=2)
parser.add_argument("--limit", type=int, default=0)
parser.add_argument("--operation", choices=["scan", "materialize"], default="materialize")
parser.add_argument("--profile", choices=["default", "tuned"], default="default")
args = parser.parse_args()

endpoint = urlparse(os.environ["R2_ENDPOINT"])
host = endpoint.netloc or endpoint.path
uri = f"s3://{args.bucket}/{args.object}"
started = time.perf_counter_ns()
connection = duckdb.connect(":memory:")
connection.execute("INSTALL httpfs")
connection.execute("LOAD httpfs")
connection.execute("SET enable_geoparquet_conversion=false")
for name, value in (
    ("s3_endpoint", host),
    ("s3_access_key_id", os.environ["AWS_ACCESS_KEY_ID"]),
    ("s3_secret_access_key", os.environ["AWS_SECRET_ACCESS_KEY"]),
    ("s3_region", os.environ.get("R2_REGION", "auto")),
    ("s3_url_style", "path"),
):
    connection.execute(f"SET {name}=?", [value])
connection.execute("SET s3_use_ssl=true")
resolved = apply_duckdb_profile(connection, args.profile)
print(f"# duckdb_profile={args.profile} cache_configuration={resolved}", file=sys.stderr)
print(f"# init_ms={(time.perf_counter_ns() - started) / 1e6:.3f}", file=sys.stderr)

with open(args.workload, newline="", encoding="utf-8") as handle:
    workload = list(csv.DictReader(handle))
if args.limit:
    workload = workload[: args.limit]

selected = '"id","geometry","bbox"' if args.operation == "materialize" else '"id"'
sql = f"""SELECT {selected} FROM read_parquet('{uri}')
WHERE ("bbox".xmax >= CAST(? AS REAL) AND "bbox".xmin <= CAST(? AS REAL)
AND "bbox".ymax >= CAST(? AS REAL) AND "bbox".ymin <= CAST(? AS REAL))"""


def run(emit):
    for row in workload:
        total_start = time.perf_counter_ns()
        table = connection.execute(
            sql,
            [float(row["minx"]), float(row["maxx"]), float(row["miny"]), float(row["maxy"])],
        ).to_arrow_table()
        read_ms = (time.perf_counter_ns() - total_start) / 1e6
        validation_start = time.perf_counter_ns()
        checksum = (
            materialized_arrow_hash(table)
            if args.operation == "materialize"
            else "not_applicable"
        )
        validation_ms = (time.perf_counter_ns() - validation_start) / 1e6
        total_ms = (time.perf_counter_ns() - total_start) / 1e6
        if emit:
            print(
                f"{row['name']},{table.num_rows},{total_ms:.6f},{checksum},"
                f"{read_ms:.6f},{validation_ms:.6f}"
            )


for _ in range(args.warmups):
    run(False)
run(True)
