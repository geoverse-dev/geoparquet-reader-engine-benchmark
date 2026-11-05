#!/usr/bin/env python3
import argparse, csv, os, time, sys
from urllib.parse import urlparse
import duckdb

p = argparse.ArgumentParser()
p.add_argument("--bucket", required=True)
p.add_argument("--object", required=True)
p.add_argument("--workload", required=True)
a = p.parse_args()
with open(a.workload, newline="") as f:
    r = next(csv.DictReader(f))
u = urlparse(os.environ["R2_ENDPOINT"])
host = u.netloc or u.path
uri = f"s3://{a.bucket}/{a.object}"
con = duckdb.connect(":memory:")
con.execute("INSTALL httpfs")
con.execute("LOAD httpfs")
for k, v in [
    ("s3_endpoint", host),
    ("s3_access_key_id", os.environ["AWS_ACCESS_KEY_ID"]),
    ("s3_secret_access_key", os.environ["AWS_SECRET_ACCESS_KEY"]),
    ("s3_region", os.environ.get("R2_REGION", "auto")),
    ("s3_url_style", "path"),
]:
    con.execute("SET " + k + "=?", [v])
con.execute("SET s3_use_ssl=true")
con.execute(
    "CALL enable_logging(['HTTP','FileSystem'], storage_config = {'buffer_size': 0})"
)
con.execute("CALL truncate_duckdb_logs()")
sql = f"""
SELECT "id", "geometry", "bbox"
FROM read_parquet('{uri}')
WHERE (
    "bbox".xmax >= ?
    AND "bbox".xmin <= ?
    AND "bbox".ymax >= ?
    AND "bbox".ymin <= ?
)
"""
params = [float(r["minx"]), float(r["maxx"]), float(r["miny"]), float(r["maxy"])]
t0 = time.perf_counter_ns()
out = con.execute(sql, params).fetchall()
ms = (time.perf_counter_ns() - t0) / 1e6
http = con.execute(
    "SELECT count(*)::BIGINT, count(*) FILTER (WHERE p.request.type='GET')::BIGINT, count(*) FILTER (WHERE p.request.type='HEAD')::BIGINT FROM duckdb_logs_parsed('HTTP') p"
).fetchone()
fs = con.execute(
    "SELECT coalesce(sum(p.bytes),0)::BIGINT, count(*) FILTER (WHERE p.bytes IS NOT NULL AND p.bytes>0)::BIGINT FROM duckdb_logs_parsed('FileSystem') p"
).fetchone()
print(
    "reader,query,rows,elapsed_ms,http_requests,http_gets,http_heads,fs_read_ops,fs_bytes"
)
print(
    f"duckdb,{r['name']},{len(out)},{ms:.6f},{http[0]},{http[1]},{http[2]},{fs[1]},{fs[0]}"
)
