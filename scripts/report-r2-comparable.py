#!/usr/bin/env python3
import csv
import math
import statistics
import sys
from collections import defaultdict


def percentile(values, fraction):
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return ordered[low]
    return ordered[low] * (high - position) + ordered[high] * (position - low)


def normalize(row):
    reader = row["reader"]
    legacy = "reader_config" not in row
    if legacy:
        reader_config = (
            "DuckDB legacy unspecified"
            if reader == "duckdb"
            else "Parquetry legacy unspecified"
        )
    else:
        reader_config = row["reader_config"]

    return {
        **row,
        "reader_config": reader_config,
        "benchmark_mode": row.get("benchmark_mode") or "legacy_unspecified",
        "cache_mode": row.get("cache_mode") or "legacy_unspecified",
        "cache_lifecycle": row.get("cache_lifecycle") or "legacy_unspecified",
        "warmups": row.get("warmups") or "unknown",
        "remote_range_calls": row.get("remote_range_calls") or "",
        "remote_bytes_requested": row.get("remote_bytes_requested") or "",
        "remote_bytes_returned": row.get("remote_bytes_returned") or "",
    }


def optional_sum(rows, field):
    values = [int(row[field]) for row in rows if row[field] != ""]
    return sum(values) if values else ""


with open(sys.argv[1], newline="") as source:
    observations = [normalize(row) for row in csv.DictReader(source)]

if not observations:
    raise SystemExit(f"No benchmark observations found in {sys.argv[1]}")

by_query = defaultdict(dict)
for row in observations:
    by_query[(row["layout"], row["query"])][row["reader"]] = row

validation = defaultdict(lambda: {"compared": 0, "mismatches": 0, "missing": 0})
for (layout, query), readers in by_query.items():
    size = query.split("-")[0]
    state = validation[(layout, size)]
    if "duckdb" not in readers or "parquetry" not in readers:
        state["missing"] += 1
        continue
    state["compared"] += 1
    if int(readers["duckdb"]["rows"]) != int(readers["parquetry"]["rows"]):
        state["mismatches"] += 1


def validation_status(layout, size):
    state = validation[(layout, size)]
    if state["missing"]:
        return "incomplete"
    if state["mismatches"]:
        return "row_count_mismatch"
    return "row_counts_match"


groups = defaultdict(list)
for row in observations:
    size = row["query"].split("-")[0]
    key = (
        row["layout"],
        row["reader"],
        row["reader_config"],
        row["benchmark_mode"],
        row["cache_mode"],
        row["cache_lifecycle"],
        row["warmups"],
        size,
    )
    groups[key].append(row)

summary = []
for key, group_rows in sorted(groups.items()):
    (
        layout,
        reader,
        reader_config,
        benchmark_mode,
        cache_mode,
        cache_lifecycle,
        warmups,
        size,
    ) = key
    elapsed = [float(row["elapsed_ms"]) for row in group_rows]
    state = validation[(layout, size)]
    status = validation_status(layout, size)

    if benchmark_mode == "cache_aligned":
        comparison_status = (
            "cache_aligned_validated"
            if status == "row_counts_match"
            else "cache_aligned_with_row_count_differences"
        )
    elif benchmark_mode == "nocache_diagnostic":
        comparison_status = "nocache_diagnostic"
    else:
        comparison_status = "legacy_unspecified"

    summary.append(
        {
            "layout": layout,
            "reader": reader,
            "reader_config": reader_config,
            "benchmark_mode": benchmark_mode,
            "cache_mode": cache_mode,
            "cache_lifecycle": cache_lifecycle,
            "warmups": warmups,
            "size": size,
            "n": len(elapsed),
            "total_rows": sum(int(row["rows"]) for row in group_rows),
            "validation_status": status,
            "validated_queries": state["compared"],
            "mismatch_queries": state["mismatches"],
            "comparison_status": comparison_status,
            "median_ms": statistics.median(elapsed),
            "mean_ms": statistics.mean(elapsed),
            "p95_ms": percentile(elapsed, 0.95),
            "p99_ms": percentile(elapsed, 0.99),
            "remote_range_calls": optional_sum(group_rows, "remote_range_calls"),
            "remote_bytes_requested": optional_sum(
                group_rows, "remote_bytes_requested"
            ),
            "remote_bytes_returned": optional_sum(group_rows, "remote_bytes_returned"),
        }
    )

with open(sys.argv[2], "w", newline="") as destination:
    writer = csv.DictWriter(destination, fieldnames=summary[0].keys())
    writer.writeheader()
    writer.writerows(summary)

modes = {row["benchmark_mode"] for row in observations}
if "legacy_unspecified" in modes:
    print(
        "WARNING: legacy input has no cache metadata and is excluded from "
        "cache-aligned claims.",
        file=sys.stderr,
    )
if any(state["mismatches"] for state in validation.values()):
    details = ", ".join(
        f"{layout}/{size}={state['mismatches']}/{state['compared']}"
        for (layout, size), state in sorted(validation.items())
        if state["mismatches"]
    )
    print(
        "WARNING: DuckDB bbox-column and Parquetry geometry-bbox row counts "
        f"differ for queries: {details}.",
        file=sys.stderr,
    )

print(
    f"{'layout':18} {'reader configuration':34} {'size':7} {'n':>4} "
    f"{'median':>10} {'p95':>10} {'validation':24}"
)
for row in summary:
    print(
        f"{row['layout']:18} {row['reader_config']:34} {row['size']:7} "
        f"{row['n']:4d} {row['median_ms']:10.2f} {row['p95_ms']:10.2f} "
        f"{row['validation_status']:24}"
    )
