#!/usr/bin/env python3
import argparse, json, time
import geoparquet_io as gpio

p = argparse.ArgumentParser()
p.add_argument("file")
p.add_argument("--bbox", nargs=4, type=float, required=True)
a = p.parse_args()

t0 = time.perf_counter_ns()
table = gpio.read(a.file).extract(bbox=tuple(a.bbox))
rows = table.num_rows
elapsed_ms = (time.perf_counter_ns() - t0) / 1_000_000
print(json.dumps({"rows": rows, "elapsed_ms": elapsed_ms}))
