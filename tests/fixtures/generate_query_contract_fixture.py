#!/usr/bin/env python3
"""Generate the deterministic local bbox-v1 conformance fixture."""

import argparse
import struct
from pathlib import Path

import duckdb


def point_wkb(x, y):
    return struct.pack("<BIdd", 1, 1, x, y)


ROWS = [
    ("inside-a", point_wkb(1, 1), (1.0, 1.0, 2.0, 2.0)),
    ("duplicate", point_wkb(3, 3), (3.0, 3.0, 4.0, 4.0)),
    ("duplicate", point_wkb(5, 5), (5.0, 5.0, 6.0, 6.0)),
    ("touch-left", point_wkb(0, 2), (-1.0, 2.0, 0.0, 3.0)),
    ("touch-right", point_wkb(10, 2), (10.0, 2.0, 11.0, 3.0)),
    ("touch-bottom", point_wkb(2, 0), (2.0, -1.0, 3.0, 0.0)),
    ("touch-top", point_wkb(2, 10), (2.0, 10.0, 3.0, 11.0)),
    ("outside-left", point_wkb(-2, 2), (-2.0, 2.0, -1.0, 3.0)),
    ("outside-right", point_wkb(12, 2), (11.0, 2.0, 12.0, 3.0)),
    ("outside-bottom", point_wkb(2, -2), (2.0, -2.0, 3.0, -1.0)),
    ("outside-top", point_wkb(2, 12), (2.0, 11.0, 3.0, 12.0)),
    ("null-bbox", point_wkb(1, 1), None),
    ("nan-bbox", point_wkb(1, 1), (float("nan"), 1.0, 2.0, 2.0)),
    ("infinite-bbox", point_wkb(1, 1), (-float("inf"), 1.0, float("inf"), 2.0)),
    ("inverted-bbox", point_wkb(5, 5), (8.0, 8.0, 2.0, 2.0)),
    (None, point_wkb(7, 7), (7.0, 7.0, 8.0, 8.0)),
]


def generate(destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    connection = duckdb.connect(":memory:")
    connection.execute(
        """
        CREATE TABLE fixture (
            id VARCHAR,
            geometry BLOB,
            bbox STRUCT(xmin FLOAT, ymin FLOAT, xmax FLOAT, ymax FLOAT)
        )
        """
    )
    for identifier, geometry, bbox in ROWS:
        value = (
            None
            if bbox is None
            else {"xmin": bbox[0], "ymin": bbox[1], "xmax": bbox[2], "ymax": bbox[3]}
        )
        connection.execute(
            "INSERT INTO fixture VALUES (?, ?, ?)", [identifier, geometry, value]
        )
    escaped = str(destination).replace("'", "''")
    connection.execute(
        f"COPY fixture TO '{escaped}' (FORMAT PARQUET, COMPRESSION UNCOMPRESSED)"
    )
    connection.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    generate(args.destination)


if __name__ == "__main__":
    main()
