#!/usr/bin/env python3
"""Build symmetric three-engine validation reports from canonical result rows."""

import argparse
import csv
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

ENGINES = ("duckdb", "parquetry", "parquet-java")


def classify(groups):
    counters = {engine: Counter(groups.get(engine, [])) for engine in ENGINES}
    values = list(counters.values())
    if values[0] == values[1] == values[2]:
        return "match"
    if len({sum(counter.values()) for counter in values}) > 1:
        return "row_count_mismatch"
    if len({counter.get(None, 0) for counter in values}) > 1:
        return "null_mismatch"
    return "id_multiplicity_mismatch"


def write_reports(raw_path, output_dir, strict=True):
    from python.query_contract import id_multiset_hash

    grouped = defaultdict(lambda: defaultdict(list))
    errors = {}
    with raw_path.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            key = (row["dataset"], row["bbox_name"])
            grouped[key][row["engine"]]
            if row["id_is_null"] == "marker":
                continue
            if row.get("error"):
                errors[(key, row["engine"])] = row["error"]
            elif row["id_is_null"] == "true":
                grouped[key][row["engine"]].append(None)
            else:
                grouped[key][row["engine"]].append(row["id"])

    output_dir.mkdir(parents=True, exist_ok=True)
    result_rows = []
    mismatch_rows = []
    summary_rows = []
    for key in sorted(grouped):
        dataset, bbox_name = key
        status = (
            "execution_error"
            if any((key, e) in errors for e in ENGINES)
            else classify(grouped[key])
        )
        summary_rows.append(
            {"dataset": dataset, "bbox_name": bbox_name, "validation_status": status}
        )
        baseline = Counter(grouped[key].get(ENGINES[0], []))
        for engine in ENGINES:
            identifiers = grouped[key].get(engine, [])
            result_rows.append(
                {
                    "dataset": dataset,
                    "bbox_name": bbox_name,
                    "engine": engine,
                    "row_count": len(identifiers),
                    "id_set_hash": id_multiset_hash(identifiers),
                    "null_count": Counter(identifiers).get(None, 0),
                    "duplicate_count": sum(
                        max(0, n - 1) for n in Counter(identifiers).values()
                    ),
                    "validation_status": status,
                    "error": errors.get((key, engine), ""),
                }
            )
            current = Counter(identifiers)
            if current != baseline:
                mismatch_rows.append(
                    {
                        "dataset": dataset,
                        "bbox_name": bbox_name,
                        "engine": engine,
                        "missing_ids": repr(list((baseline - current).elements())),
                        "unexpected_ids": repr(list((current - baseline).elements())),
                        "validation_status": status,
                    }
                )

    def emit(name, fieldnames, rows):
        with (output_dir / name).open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)

    emit(
        "validation-results.csv",
        list(result_rows[0]) if result_rows else ["dataset"],
        result_rows,
    )
    emit(
        "validation-summary.csv",
        ["dataset", "bbox_name", "validation_status"],
        summary_rows,
    )
    emit(
        "mismatches.csv",
        [
            "dataset",
            "bbox_name",
            "engine",
            "missing_ids",
            "unexpected_ids",
            "validation_status",
        ],
        mismatch_rows,
    )
    failures = sum(row["validation_status"] != "match" for row in summary_rows)
    print(f"validated={len(summary_rows)} mismatches={failures}")
    if strict and failures:
        raise SystemExit(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("raw", type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path("results/validation"))
    parser.add_argument("--no-strict", action="store_true")
    args = parser.parse_args()
    write_reports(args.raw, args.output_dir, strict=not args.no_strict)


if __name__ == "__main__":
    main()
