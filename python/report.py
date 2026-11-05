#!/usr/bin/env python3
import csv, json, math, statistics, pathlib, collections

src = pathlib.Path("results/runs.csv")
if not src.exists():
    raise SystemExit("results/runs.csv not found; run ./scripts/run.sh first")
rows = list(csv.DictReader(src.open()))
groups = collections.defaultdict(list)
for r in rows:
    if r["phase"] == "measure" and r["status"] == "0":
        groups[(r["layout"], r["reader"])].append(float(r["elapsed_ms"]))


def pctl(v, p):
    s = sorted(v)
    i = (len(s) - 1) * p
    lo = math.floor(i)
    hi = math.ceil(i)
    return s[lo] if lo == hi else s[lo] * (hi - i) + s[hi] * (i - lo)


summary = []
for (layout, reader), vals in sorted(groups.items()):
    summary.append(
        {
            "layout": layout,
            "reader": reader,
            "n": len(vals),
            "median_ms": statistics.median(vals),
            "mean_ms": statistics.mean(vals),
            "p95_ms": pctl(vals, 0.95),
            "stdev_ms": statistics.stdev(vals) if len(vals) > 1 else 0.0,
        }
    )
pathlib.Path("results/summary.json").write_text(json.dumps(summary, indent=2))
with open("results/summary.csv", "w", newline="") as f:
    w = csv.DictWriter(
        f,
        fieldnames=summary[0].keys()
        if summary
        else ["layout", "reader", "n", "median_ms", "mean_ms", "p95_ms", "stdev_ms"],
    )
    w.writeheader()
    w.writerows(summary)
print("\nGeoParquet reader benchmark (milliseconds; lower is better)")
print(f"{'layout':12} {'reader':12} {'n':>5} {'median':>12} {'mean':>12} {'p95':>12}")
for x in summary:
    print(
        f"{x['layout']:12} {x['reader']:12} {x['n']:5d} {x['median_ms']:12.2f} {x['mean_ms']:12.2f} {x['p95_ms']:12.2f}"
    )
lookup = {(x["layout"], x["reader"]): x for x in summary}
print("\nDerived ratios (median):")
for reader in ("gpio", "parquetry"):
    a = lookup.get(("raw", reader))
    b = lookup.get(("optimized", reader))
    if a and b:
        print(f"  {reader}: optimized is {a['median_ms'] / b['median_ms']:.2f}x vs raw")
for layout in ("raw", "optimized"):
    g = lookup.get((layout, "gpio"))
    p = lookup.get((layout, "parquetry"))
    if g and p:
        print(
            f"  {layout}: parquetry/gpio latency ratio = {p['median_ms'] / g['median_ms']:.2f}x"
        )
