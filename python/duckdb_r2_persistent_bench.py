#!/usr/bin/env python3
import argparse, csv, os, time, sys
from urllib.parse import urlparse
import duckdb

p = argparse.ArgumentParser()
p.add_argument("--bucket", required=True)
p.add_argument("--object", required=True)
p.add_argument("--workload", required=True)
p.add_argument("--warmups", type=int, default=2)
p.add_argument("--limit", type=int, default=0)
a = p.parse_args()
u = urlparse(os.environ["R2_ENDPOINT"])
host = u.netloc or u.path
uri = f"s3://{a.bucket}/{a.object}"
t0 = time.perf_counter_ns()
con = duckdb.connect(":memory:")
con.execute("INSTALL httpfs")
con.execute("LOAD httpfs")


def setting(k, v):
    con.execute("SET " + k + "=?", [v])


setting("s3_endpoint", host)
setting("s3_access_key_id", os.environ["AWS_ACCESS_KEY_ID"])
setting("s3_secret_access_key", os.environ["AWS_SECRET_ACCESS_KEY"])
setting("s3_region", os.environ.get("R2_REGION", "auto"))
setting("s3_url_style", "path")
con.execute("SET s3_use_ssl=true")
print(f"# init_ms={(time.perf_counter_ns() - t0) / 1e6:.3f}", file=sys.stderr)
with open(a.workload, newline="") as f:
    rows = list(csv.DictReader(f))
if a.limit:
    rows = rows[: a.limit]
sql = f"""SELECT "id","geometry","bbox" FROM read_parquet('{uri}')
WHERE ("bbox".xmax >= ? AND "bbox".xmin <= ? AND "bbox".ymax >= ? AND "bbox".ymin <= ?)"""


def run(emit):
    for r in rows:
        q0 = time.perf_counter_ns()
        out = con.execute(
            sql,
            [float(r["minx"]), float(r["maxx"]), float(r["miny"]), float(r["maxy"])],
        ).fetchall()
        ms = (time.perf_counter_ns() - q0) / 1e6
        if emit:
            print(f"{r['name']},{len(out)},{ms:.6f}")


for _ in range(a.warmups):
    run(False)
run(True)
