#!/usr/bin/env python3
"""Expand the validated BBOX workload for deterministic sustained-load runs."""

import argparse
import csv
import math
from pathlib import Path


def generate(source: Path, output: Path, count: int) -> None:
    if count < 1:
        raise ValueError("count must be positive")

    with source.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        fieldnames = reader.fieldnames
        rows = list(reader)
    if fieldnames != ["name", "minx", "miny", "maxx", "maxy"]:
        raise ValueError(f"unexpected workload columns: {fieldnames}")
    if not rows:
        raise ValueError("source workload is empty")

    rotation = 37
    while math.gcd(rotation, len(rows)) != 1:
        rotation += 1

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for index in range(count):
            cycle, position = divmod(index, len(rows))
            source_row = rows[(position + cycle * rotation) % len(rows)]
            writer.writerow(
                {
                    **source_row,
                    # Preserve the small/medium/large prefix consumed by the
                    # existing report while keeping every execution distinct.
                    "name": f"{source_row['name']}-stress-{index + 1:04d}",
                }
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count", type=int, required=True)
    args = parser.parse_args()
    generate(args.source, args.output, args.count)


if __name__ == "__main__":
    main()
