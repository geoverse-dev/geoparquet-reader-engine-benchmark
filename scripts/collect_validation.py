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
        name, is_null, identifier = line.rstrip("\n").split("\t", 2)
        marker = "marker" if is_null == "2" else ("true" if is_null == "1" else "false")
        writer.writerow([args.dataset, name, args.engine, identifier, marker, ""])
