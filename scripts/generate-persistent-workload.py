#!/usr/bin/env python3
import csv, random

random.seed(20260921)
# Focus on the Tandil area already validated by the cold workload, with jittered centers.
CX, CY = -59.145, -37.315
specs = [("small", 0.05, 0.05), ("medium", 0.30, 0.30), ("large", 1.00, 1.00)]
with open("config/workload-persistent.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["name", "minx", "miny", "maxx", "maxy"])
    for label, dx, dy in specs:
        for i in range(1, 51):
            cx = CX + random.uniform(-0.18, 0.18)
            cy = CY + random.uniform(-0.18, 0.18)
            w.writerow(
                [
                    f"{label}-{i:02d}",
                    f"{cx - dx / 2:.8f}",
                    f"{cy - dy / 2:.8f}",
                    f"{cx + dx / 2:.8f}",
                    f"{cy + dy / 2:.8f}",
                ]
            )
