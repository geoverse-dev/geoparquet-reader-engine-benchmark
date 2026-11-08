#!/usr/bin/env python3
import argparse
import csv
import os
import sys
from collections import Counter
from pathlib import Path
from urllib.parse import urlparse

import duckdb

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from python.query_contract import id_multiset_hash

parser = argparse.ArgumentParser()
parser.add_argument("--bucket", required=True)
parser.add_argument("--object", required=True)
parser.add_argument("--workload", required=True)
parser.add_argument("--limit", type=int, default=0)
args = parser.parse_args()
endpoint = urlparse(os.environ["R2_ENDPOINT"])
connection = duckdb.connect(":memory:")
connection.execute("INSTALL httpfs")
connection.execute("LOAD httpfs")
for key, value in {
    "s3_endpoint": endpoint.netloc or endpoint.path,
    "s3_access_key_id": os.environ["AWS_ACCESS_KEY_ID"],
    "s3_secret_access_key": os.environ["AWS_SECRET_ACCESS_KEY"],
    "s3_region": os.environ.get("R2_REGION", "auto"),
    "s3_url_style": "path",
}.items():
    connection.execute(f"SET {key}=?", [value])
connection.execute("SET s3_use_ssl=true")
sql = f"""SELECT id FROM read_parquet('s3://{args.bucket}/{args.object}')
WHERE bbox.xmin <= CAST(? AS REAL) AND bbox.xmax >= CAST(? AS REAL)
  AND bbox.ymin <= CAST(? AS REAL) AND bbox.ymax >= CAST(? AS REAL)"""
with open(args.workload, newline="", encoding="utf-8") as stream:
    workload = list(csv.DictReader(stream))
for query in workload[: args.limit or None]:
    values = connection.execute(
        sql,
        [
            float(query["maxx"]),
            float(query["minx"]),
            float(query["maxy"]),
            float(query["miny"]),
        ],
    ).fetchall()
    identifiers = [identifier for (identifier,) in values]
    counts = Counter(identifiers)
    duplicates = sum(max(0, count - 1) for count in counts.values())
    print(
        f"{query['name']}\t{len(identifiers)}\t{id_multiset_hash(identifiers)}\t"
        f"{counts.get(None, 0)}\t{duplicates}\t"
    )
