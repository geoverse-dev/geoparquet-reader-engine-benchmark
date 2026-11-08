#!/usr/bin/env python3
"""Build symmetric validation reports from compact per-engine summaries."""

import argparse
import csv
from collections import defaultdict
from pathlib import Path

ENGINES = ("duckdb", "parquetry", "parquet-java")
METRICS = ("row_count", "id_set_hash", "null_count", "duplicate_count")


def classify(groups):
    if any(engine not in groups for engine in ENGINES):
        return "execution_error"
    if len({groups[engine]["row_count"] for engine in ENGINES}) > 1:
        return "row_count_mismatch"
    if len({groups[engine]["null_count"] for engine in ENGINES}) > 1:
        return "null_mismatch"
    if len({groups[engine]["duplicate_count"] for engine in ENGINES}) > 1:
        return "id_multiplicity_mismatch"
    if len({groups[engine]["id_set_hash"] for engine in ENGINES}) > 1:
        return "id_multiplicity_mismatch"
    return "match"


def write_reports(raw_path, output_dir, strict=True):
    grouped = defaultdict(dict)
    with raw_path.open(newline="", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    for row in rows:
        for field in ("row_count", "null_count", "duplicate_count"):
            row[field] = int(row[field])
        grouped[(row["dataset"], row["bbox_name"])][row["engine"]] = row

    output_dir.mkdir(parents=True, exist_ok=True)
    result_rows, summary_rows, mismatch_rows = [], [], []
    for key in sorted(grouped):
        dataset, bbox_name = key
        engines = grouped[key]
        status = (
            "execution_error"
            if any(row.get("error") for row in engines.values())
            else classify(engines)
        )
        summary_rows.append(
            {"dataset": dataset, "bbox_name": bbox_name, "validation_status": status}
        )
        for engine in ENGINES:
            row = engines.get(engine, {})
            result_rows.append(
                {
                    "dataset": dataset,
                    "bbox_name": bbox_name,
                    "engine": engine,
                    **{field: row.get(field, "") for field in METRICS},
                    "validation_status": status,
                    "error": row.get("error", "missing_engine_result"),
                }
            )
        if status != "match":
            mismatch_rows.append(
                {
                    "dataset": dataset,
                    "bbox_name": bbox_name,
                    "validation_status": status,
                    **{
                        f"{engine}_{field}": engines.get(engine, {}).get(field, "")
                        for engine in ENGINES
                        for field in METRICS
                    },
                }
            )

    def emit(name, fieldnames, output_rows):
        with (output_dir / name).open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(output_rows)

    result_fields = [
        "dataset",
        "bbox_name",
        "engine",
        *METRICS,
        "validation_status",
        "error",
    ]
    mismatch_fields = [
        "dataset",
        "bbox_name",
        "validation_status",
        *[f"{engine}_{field}" for engine in ENGINES for field in METRICS],
    ]
    emit("validation-results.csv", result_fields, result_rows)
    emit(
        "validation-summary.csv",
        ["dataset", "bbox_name", "validation_status"],
        summary_rows,
    )
    emit("mismatches.csv", mismatch_fields, mismatch_rows)
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
