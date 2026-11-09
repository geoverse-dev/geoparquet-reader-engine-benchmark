import csv
import tempfile
import unittest
from pathlib import Path

from scripts.generate_stress_workload import generate


class GenerateStressWorkloadTest(unittest.TestCase):
    def test_expands_deterministically_without_changing_bboxes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.csv"
            source.write_text(
                "name,minx,miny,maxx,maxy\n"
                "a,0,1,2,3\n"
                "b,4,5,6,7\n"
                "c,8,9,10,11\n",
                encoding="utf-8",
            )
            first = root / "first.csv"
            second = root / "second.csv"
            generate(source, first, 8)
            generate(source, second, 8)

            self.assertEqual(first.read_bytes(), second.read_bytes())
            with first.open(newline="", encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual(8, len(rows))
            self.assertEqual(8, len({row["name"] for row in rows}))
            source_boxes = {
                ("0", "1", "2", "3"),
                ("4", "5", "6", "7"),
                ("8", "9", "10", "11"),
            }
            self.assertTrue(
                all(
                    (row["minx"], row["miny"], row["maxx"], row["maxy"])
                    in source_boxes
                    for row in rows
                )
            )

    def test_rejects_empty_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.csv"
            source.write_text("name,minx,miny,maxx,maxy\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "empty"):
                generate(source, root / "output.csv", 500)


if __name__ == "__main__":
    unittest.main()
