#!/usr/bin/env python3
import argparse, csv, time
import geoparquet_io as gpio

p = argparse.ArgumentParser()
p.add_argument("file")
p.add_argument("workload")
p.add_argument("--warmups", type=int, default=5)
a = p.parse_args()
t = gpio.read(a.file)


def run(emit=False):
    with open(a.workload, newline="") as f:
        for r in csv.DictReader(f):
            bbox = tuple(float(r[k]) for k in ("minx", "miny", "maxx", "maxy"))
            # t0=time.perf_counter_ns(); out=t.extract(bbox=bbox); ms=(time.perf_counter_ns()-t0)/1_000_000
            t0 = time.perf_counter_ns()
            out = t.extract(
                columns=["id"],
                bbox=bbox,
                repair_geometry=False,
            )
            ms = (time.perf_counter_ns() - t0) / 1_000_000
            if emit:
                print(f"{r['name']},{out.num_rows},{ms:.6f}")


for _ in range(a.warmups):
    run(False)
run(True)
