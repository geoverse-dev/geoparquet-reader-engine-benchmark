#!/usr/bin/env python3
import csv
from collections import defaultdict

path = "results/r2-parquetry-tileverse-cache.csv"
rows = list(csv.DictReader(open(path)))

groups = defaultdict(list)
for r in rows:
    groups[(r["layout"], r["mode"], int(r["round"]))].append(r)

print(
    f"{'layout':18} {'mode':8} {'round':>5} {'queries':>7} {'time_s':>10} {'ranges':>10} {'MB_remote':>12}"
)
for (layout, mode, rnd), rs in sorted(groups.items()):
    time_s = sum(float(x["elapsed_ms"]) for x in rs) / 1000
    ranges = sum(int(x["remote_range_calls"]) for x in rs)
    mb = sum(int(x["remote_bytes_returned"]) for x in rs) / 1_000_000
    print(
        f"{layout:18} {mode:8} {rnd:5d} {len(rs):7d} {time_s:10.3f} {ranges:10d} {mb:12.2f}"
    )

print("\nPer-query cache effect (round 2 cache vs round 2 nocache):")
index = {(r["layout"], r["mode"], r["round"], r["query"]): r for r in rows}
for layout in ["raw", "optimized-v1.1", "optimized-v2.0"]:
    print(f"\n{layout}")
    print(f"{'query':16} {'nocache_ms':>12} {'cache_ms':>12} {'cache_remote_MB':>16}")
    for q in ["tandil", "buenos-aires", "cordoba", "mendoza", "bariloche", "salta"]:
        n = index.get((layout, "nocache", "2", q))
        c = index.get((layout, "cache", "2", q))
        if n and c:
            print(
                f"{q:16} {float(n['elapsed_ms']):12.2f} {float(c['elapsed_ms']):12.2f} {int(c['remote_bytes_returned']) / 1_000_000:16.3f}"
            )
