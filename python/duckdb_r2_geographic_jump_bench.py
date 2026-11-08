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

p = argparse.ArgumentParser()
for x in ["bucket", "object", "workload", "endpoint"]:
    p.add_argument("--" + x, required=True)
p.add_argument("--region", default="auto")
a = p.parse_args()
with open(a.workload, newline="") as f:
    workload = list(csv.DictReader(f))
con = duckdb.connect()
ep = a.endpoint.replace("https://", "").replace("http://", "").rstrip("/")
con.execute("INSTALL httpfs")
con.execute("LOAD httpfs")
con.execute("SET enable_geoparquet_conversion=false")
for k, v in [
    ("s3_endpoint", ep),
    ("s3_region", a.region),
    ("s3_access_key_id", os.environ["AWS_ACCESS_KEY_ID"]),
    ("s3_secret_access_key", os.environ["AWS_SECRET_ACCESS_KEY"]),
]:
    con.execute(f"SET {k}='{v}'")
if os.environ.get("AWS_SESSION_TOKEN"):
    con.execute(f"SET s3_session_token='{os.environ['AWS_SESSION_TOKEN']}'")
con.execute(
    f"SET s3_use_ssl={'true' if a.endpoint.startswith('https://') else 'false'}"
)
con.execute("SET s3_url_style='path'")
con.execute(
    "CALL enable_logging(['HTTP','FileSystem'], storage_config = {'buffer_size': 0})"
)
uri = f"s3://{a.bucket}/{a.object}"
print(
    "position,query,rows,elapsed_ms,result_checksum,http_requests,http_gets,http_heads,fs_read_ops,fs_bytes"
)
for pos, r in enumerate(workload, 1):
    sql = f"""SELECT "id","geometry","bbox" FROM read_parquet('{uri}')
WHERE ("bbox".xmax >= CAST(? AS REAL) AND "bbox".xmin <= CAST(? AS REAL)
AND "bbox".ymax >= CAST(? AS REAL) AND "bbox".ymin <= CAST(? AS REAL))"""
    params = [float(r["minx"]), float(r["maxx"]), float(r["miny"]), float(r["maxy"])]
    con.execute("CALL truncate_duckdb_logs()")
    t = time.perf_counter()
    rows = con.execute(sql, params).fetchall()
    checksum = materialized_multiset_hash(rows)
    ms = (time.perf_counter() - t) * 1000
    h = con.execute(
        """SELECT count(*)::BIGINT,count(*) FILTER (WHERE p.request.type='GET')::BIGINT,count(*) FILTER (WHERE p.request.type='HEAD')::BIGINT FROM duckdb_logs_parsed('HTTP') p"""
    ).fetchone()
    fs = con.execute(
        """SELECT count(*) FILTER (WHERE p.bytes IS NOT NULL AND p.bytes>0)::BIGINT,coalesce(sum(p.bytes),0)::BIGINT FROM duckdb_logs_parsed('FileSystem') p"""
    ).fetchone()
    print(
        f"{pos},{r['name']},{len(rows)},{ms:.6f},{checksum},{h[0]},{h[1]},{h[2]},{fs[0]},{fs[1]}"
    )
