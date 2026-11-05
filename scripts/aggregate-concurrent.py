#!/usr/bin/env python3
"""Aggregate repeated concurrency runs; standard library only."""

import argparse
import csv
import re
import statistics
from collections import defaultdict
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--input-dir", type=Path, required=True)
parser.add_argument("--scenario", choices=("warm", "mixed", "cold"), required=True)
args = parser.parse_args()

files = sorted(
    args.input_dir.glob(f"{args.scenario}-run-*.csv"),
    key=lambda f: int(re.search(r"-run-(\d+)\.csv$", f.name).group(1)),
)
if not files:
    parser.error("No run CSVs found")

metrics = ("wall_ms", "qps", "p50_ms", "p95_ms", "p99_ms", "peak_rss_mb", "total_rows")
groups = defaultdict(list)
for path in files:
    with path.open(newline="") as handle:
        for row in csv.DictReader(handle):
            if row["scenario"] != args.scenario:
                raise ValueError(f"Scenario mismatch in {path}")
            groups[(row["engine"], int(row["concurrency"]))].append(row)

out = args.input_dir / f"{args.scenario}-summary.csv"
with out.open("w", newline="") as handle:
    writer = csv.writer(handle)
    writer.writerow(
        [
            "scenario",
            "engine",
            "concurrency",
            "runs",
            "queries_per_run",
            *[f"median_{metric}" for metric in metrics],
        ]
    )
    for (engine, concurrency), rows in sorted(
        groups.items(), key=lambda kv: (kv[0][1], kv[0][0])
    ):
        query_counts = {int(r["queries"]) for r in rows}
        if len(query_counts) != 1:
            raise ValueError(f"Inconsistent query counts for {engine}/{concurrency}")
        writer.writerow(
            [
                args.scenario,
                engine,
                concurrency,
                len(rows),
                query_counts.pop(),
                *[
                    f"{statistics.median(float(r[m]) for r in rows):.3f}"
                    for m in metrics
                ],
            ]
        )
print(f"Summary: {out}")
