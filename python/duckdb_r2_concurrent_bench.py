#!/usr/bin/env python3
"""Persistent DuckDB benchmark with per-worker cursors and R2 configuration."""

import argparse
import concurrent.futures
import csv
import os
import statistics
import threading
import time
from urllib.parse import urlparse

import duckdb


parser = argparse.ArgumentParser()

for key in ("bucket", "object", "workload"):
    parser.add_argument("--" + key, required=True)

for key, default in [
    ("concurrency", 4),
    ("queries", 40),
    ("warmup", 1),
]:
    parser.add_argument("--" + key, type=int, default=default)

parser.add_argument(
    "--scenario",
    choices=["cold", "warm", "mixed"],
    default="warm",
)

args = parser.parse_args()

if min(args.concurrency, args.queries) < 1:
    parser.error("concurrency and queries must be positive")

with open(args.workload, newline="") as f:
    windows = list(csv.DictReader(f))

if not windows:
    parser.error("empty workload")


# ------------------------------------------------------------
# Cloudflare R2 configuration
# ------------------------------------------------------------

endpoint = urlparse(os.environ["R2_ENDPOINT"])
s3_endpoint = endpoint.netloc or endpoint.path

s3_settings = {
    "s3_endpoint": s3_endpoint,
    "s3_access_key_id": os.environ["AWS_ACCESS_KEY_ID"],
    "s3_secret_access_key": os.environ["AWS_SECRET_ACCESS_KEY"],
    "s3_region": os.environ.get("R2_REGION", "auto"),
    "s3_url_style": "path",
}


def configure_r2(connection):
    for key, value in s3_settings.items():
        connection.execute(f"SET {key}=?", [value])

    connection.execute("SET s3_use_ssl=true")


# ------------------------------------------------------------
# Shared DuckDB instance
# ------------------------------------------------------------

con = duckdb.connect(":memory:")

con.execute("INSTALL httpfs")
con.execute("LOAD httpfs")

configure_r2(con)

sql = (
    "SELECT id, geometry, bbox "
    f"FROM read_parquet('s3://{args.bucket}/{args.object}') "
    "WHERE bbox.xmax >= ? "
    "AND bbox.xmin <= ? "
    "AND bbox.ymax >= ? "
    "AND bbox.ymin <= ?"
)


def params(row):
    return [
        float(row["minx"]),
        float(row["maxx"]),
        float(row["miny"]),
        float(row["maxy"]),
    ]


def rss():
    try:
        with open("/proc/self/status") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) * 1024
    except OSError:
        pass

    return 0


# ------------------------------------------------------------
# Warmup
# ------------------------------------------------------------

hot = min(6, len(windows))

if args.scenario != "cold":
    for _ in range(args.warmup):
        for row in windows[:hot]:
            con.execute(sql, params(row)).fetchall()


# ------------------------------------------------------------
# Memory monitoring
# ------------------------------------------------------------

stop = threading.Event()
peak = [rss()]


def monitor():
    while not stop.wait(0.025):
        peak[0] = max(peak[0], rss())


monitor_thread = threading.Thread(
    target=monitor,
    daemon=True,
)
monitor_thread.start()


# ------------------------------------------------------------
# Concurrent queries
# ------------------------------------------------------------

local = threading.local()


def query(index):
    if not hasattr(local, "cursor"):
        local.cursor = con.cursor()
        configure_r2(local.cursor)

    if args.scenario == "warm":
        row = windows[index % hot]
    else:
        row = windows[index % len(windows)]

    start = time.perf_counter_ns()

    result = local.cursor.execute(
        sql,
        params(row),
    ).fetchall()

    elapsed_ms = (time.perf_counter_ns() - start) / 1e6

    return len(result), elapsed_ms


start = time.perf_counter()

try:
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(query, range(args.queries)))
finally:
    stop.set()
    monitor_thread.join()

wall = time.perf_counter() - start

latencies = sorted(result[1] for result in results)


def percentile(value):
    return latencies[round((len(latencies) - 1) * value)]


# ------------------------------------------------------------
# CSV output
# ------------------------------------------------------------

print(
    "engine,scenario,concurrency,queries,wall_ms,qps,"
    "p50_ms,p95_ms,p99_ms,peak_rss_mb,total_rows"
)

print(
    f"duckdb,"
    f"{args.scenario},"
    f"{args.concurrency},"
    f"{args.queries},"
    f"{wall * 1000:.3f},"
    f"{args.queries / wall:.3f},"
    f"{statistics.median(latencies):.3f},"
    f"{percentile(0.95):.3f},"
    f"{percentile(0.99):.3f},"
    f"{peak[0] / 1048576:.2f},"
    f"{sum(result[0] for result in results)}"
)

con.close()
