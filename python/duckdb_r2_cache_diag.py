#!/usr/bin/env python3
import argparse
import csv
import os
import time

import duckdb

p = argparse.ArgumentParser()
p.add_argument("--bucket", required=True)
p.add_argument("--object", required=True)
p.add_argument("--workload", required=True)
p.add_argument("--endpoint", required=True)
p.add_argument("--region", default="auto")
p.add_argument("--positions", default="1,2,10,50")
a = p.parse_args()
with open(a.workload, newline="") as f:
    workload = list(csv.DictReader(f))
wanted = {int(x) for x in a.positions.split(",") if x.strip()}
if not workload or min(wanted) < 1 or max(wanted) > len(workload):
    raise RuntimeError("Invalid workload/positions")

con = duckdb.connect()
endpoint = a.endpoint.replace("https://", "").replace("http://", "").rstrip("/")
con.execute("INSTALL httpfs")
con.execute("LOAD httpfs")
con.execute(f"SET s3_endpoint='{endpoint}'")
con.execute(f"SET s3_region='{a.region}'")
con.execute(f"SET s3_access_key_id='{os.environ['AWS_ACCESS_KEY_ID']}'")
con.execute(f"SET s3_secret_access_key='{os.environ['AWS_SECRET_ACCESS_KEY']}'")
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
    "position,query,rows,elapsed_ms,http_requests,http_gets,http_heads,fs_read_ops,fs_bytes"
)

for pos, row in enumerate(workload[: max(wanted)], 1):
    sql = f"""
SELECT "id","geometry","bbox"
FROM read_parquet('{uri}')
WHERE ("bbox".xmax >= CAST(? AS REAL) AND "bbox".xmin <= CAST(? AS REAL)
   AND "bbox".ymax >= CAST(? AS REAL) AND "bbox".ymin <= CAST(? AS REAL))
"""
    params = [
        float(row["minx"]),
        float(row["maxx"]),
        float(row["miny"]),
        float(row["maxy"]),
    ]
    con.execute("CALL truncate_duckdb_logs()")
    t = time.perf_counter()
    result = con.execute(sql, params).fetchall()
    ms = (time.perf_counter() - t) * 1000
    h = con.execute("""SELECT count(*)::BIGINT,
      count(*) FILTER (WHERE p.request.type='GET')::BIGINT,
      count(*) FILTER (WHERE p.request.type='HEAD')::BIGINT
      FROM duckdb_logs_parsed('HTTP') p""").fetchone()
    fs = con.execute("""SELECT
      count(*) FILTER (WHERE p.bytes IS NOT NULL AND p.bytes>0)::BIGINT,
      coalesce(sum(p.bytes),0)::BIGINT
      FROM duckdb_logs_parsed('FileSystem') p""").fetchone()
    if pos in wanted:
        print(
            f"{pos},{row['name']},{len(result)},{ms:.6f},{h[0]},{h[1]},{h[2]},{fs[0]},{fs[1]}"
        )
