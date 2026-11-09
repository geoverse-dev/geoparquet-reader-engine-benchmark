#!/usr/bin/env python3
import argparse
import csv
import os
import sys
import time
from pathlib import Path

import duckdb

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from python.query_contract import materialized_multiset_hash
from python.arrow_materialization import materialized_arrow_hash


def rss_bytes():
    # Linux: ru_maxrss is KiB and is a peak, not current RSS.
    with open("/proc/self/status") as f:
        vals = {}
        for line in f:
            if ":" in line:
                k, v = line.split(":", 1)
                vals[k] = v.strip()

    def kb(name):
        v = vals.get(name, "0 kB").split()[0]
        return int(v) * 1024

    return kb("VmRSS"), kb("VmHWM")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--workload", required=True)
    ap.add_argument("--layout", required=True)
    ap.add_argument("--materialization", choices=["rows", "arrow"], default="rows")
    args = ap.parse_args()

    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs")
    con.execute("SET enable_geoparquet_conversion=false")
    endpoint = os.environ["R2_ENDPOINT"].replace("https://", "").replace("http://", "")
    con.execute("SET s3_endpoint=?", [endpoint])
    con.execute(
        "SET s3_region=?",
        [os.getenv("AWS_REGION", os.getenv("AWS_DEFAULT_REGION", "auto"))],
    )
    con.execute("SET s3_access_key_id=?", [os.environ["AWS_ACCESS_KEY_ID"]])
    con.execute("SET s3_secret_access_key=?", [os.environ["AWS_SECRET_ACCESS_KEY"]])
    con.execute("SET s3_url_style='path'")
    con.execute("SET s3_use_ssl=true")
    memory_limit = con.execute("SELECT current_setting('memory_limit')").fetchone()[0]

    with open(args.workload) as f:
        qs = list(csv.DictReader(f))

    base_rss, _ = rss_bytes()
    header = (
        "engine,layout,position,query,rows,elapsed_ms,result_checksum,rss_bytes,rss_delta_bytes,peak_rss_bytes,"
        "duckdb_memory_limit,jvm_heap_used_bytes,jvm_heap_committed_bytes,jvm_non_heap_used_bytes,"
        "jvm_direct_buffer_bytes,gc_count,gc_pause_ms,sampling_interval_ms"
    )
    if args.materialization == "arrow":
        header += ",read_materialize_ms,validation_ms"
    print(header)
    for i, q in enumerate(qs, 1):
        # Match the comparable benchmark projection and stored bbox predicate.
        sql = f"""
        SELECT id, geometry, bbox
        FROM read_parquet('{args.url}')
        WHERE bbox.xmax >= CAST(? AS REAL) AND bbox.xmin <= CAST(? AS REAL)
          AND bbox.ymax >= CAST(? AS REAL) AND bbox.ymin <= CAST(? AS REAL)
        """
        params = [
            float(q["minx"]),
            float(q["maxx"]),
            float(q["miny"]),
            float(q["maxy"]),
        ]
        t = time.perf_counter()
        result = con.execute(sql, params)
        if args.materialization == "arrow":
            materialized = result.to_arrow_table()
            read_ms = (time.perf_counter() - t) * 1000
            validation_start = time.perf_counter()
            checksum = materialized_arrow_hash(materialized)
            validation_ms = (time.perf_counter() - validation_start) * 1000
            rows = materialized.num_rows
            engine = "duckdb-arrow"
        else:
            materialized = result.fetchall()
            rows = len(materialized)
            checksum = materialized_multiset_hash(materialized)
            engine = "duckdb"
        ms = (time.perf_counter() - t) * 1000
        rss, peak = rss_bytes()
        line = (
            f"{engine},{args.layout},{i},{q['name']},{rows},{ms:.6f},{checksum},{rss},{rss - base_rss},{peak},"
            f"{memory_limit},not_available,not_available,not_available,not_available,not_available,"
            "not_available,not_applicable"
        )
        if args.materialization == "arrow":
            line += f",{read_ms:.6f},{validation_ms:.6f}"
        print(line, flush=True)


if __name__ == "__main__":
    main()
