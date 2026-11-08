#!/usr/bin/env python3
import argparse
import csv
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("dataset")
parser.add_argument("engine")
parser.add_argument("input", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
with (
    args.input.open(encoding="utf-8") as source,
    args.output.open("a", newline="", encoding="utf-8") as target,
):
    writer = csv.writer(target)
    for line in source:
        name, row_count, id_hash, null_count, duplicate_count, error = line.rstrip(
            "\n"
        ).split("\t", 5)
        writer.writerow(
            [
                args.dataset,
                name,
                args.engine,
                row_count,
                id_hash,
                null_count,
                duplicate_count,
                error,
            ]
        )
