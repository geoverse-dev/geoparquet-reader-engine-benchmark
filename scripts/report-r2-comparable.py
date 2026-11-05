#!/usr/bin/env python3
import csv, sys, statistics, math

groups = {}
with open(sys.argv[1]) as f:
    for r in csv.DictReader(f):
        size = r["query"].split("-")[0]
        groups.setdefault((r["layout"], r["reader"], size), []).append(
            float(r["elapsed_ms"])
        )


def pct(x, p):
    x = sorted(x)
    k = (len(x) - 1) * p
    lo = math.floor(k)
    hi = math.ceil(k)
    return x[lo] if lo == hi else x[lo] * (hi - k) + x[hi] * (k - lo)


rows = []
for (l, r, s), x in sorted(groups.items()):
    rows.append(
        {
            "layout": l,
            "reader": r,
            "size": s,
            "n": len(x),
            "median_ms": statistics.median(x),
            "mean_ms": statistics.mean(x),
            "p95_ms": pct(x, 0.95),
            "p99_ms": pct(x, 0.99),
        }
    )
with open(sys.argv[2], "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=rows[0].keys())
    w.writeheader()
    w.writerows(rows)
print(
    f"{'layout':18} {'reader':10} {'size':7} {'n':>4} {'median':>10} {'mean':>10} {'p95':>10} {'p99':>10}"
)
for x in rows:
    print(
        f"{x['layout']:18} {x['reader']:10} {x['size']:7} {x['n']:4d} {x['median_ms']:10.2f} {x['mean_ms']:10.2f} {x['p95_ms']:10.2f} {x['p99_ms']:10.2f}"
    )
