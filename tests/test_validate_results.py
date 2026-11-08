import csv
import tempfile
import unittest
from pathlib import Path

from scripts.validate_results import write_reports

ENGINES = ("duckdb", "parquetry", "parquet-java")


class ValidationReportTest(unittest.TestCase):
    def write_raw(self, root, rows):
        path = root / "raw.csv"
        fields = [
            "dataset",
            "bbox_name",
            "engine",
            "row_count",
            "id_set_hash",
            "null_count",
            "duplicate_count",
            "error",
        ]
        with path.open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def rows(self, **overrides):
        base = {
            "dataset": "d",
            "bbox_name": "q",
            "row_count": 2,
            "id_set_hash": "abc",
            "null_count": 0,
            "duplicate_count": 0,
            "error": "",
        }
        return [
            {**base, "engine": engine, **overrides.get(engine, {})}
            for engine in ENGINES
        ]

    def status(self, root):
        return next(csv.DictReader((root / "out/validation-summary.csv").open()))[
            "validation_status"
        ]

    def test_matching_compact_summaries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_reports(self.write_raw(root, self.rows()), root / "out")
            self.assertEqual("match", self.status(root))

    def test_same_count_different_hash_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_reports(
                self.write_raw(root, self.rows(parquetry={"id_set_hash": "other"})),
                root / "out",
                strict=False,
            )
            self.assertEqual("id_multiplicity_mismatch", self.status(root))

    def test_duplicate_and_null_differences_are_distinguished(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(SystemExit):
                write_reports(
                    self.write_raw(root, self.rows(parquetry={"null_count": 1})),
                    root / "out",
                )
            self.assertEqual("null_mismatch", self.status(root))

    def test_all_empty_query_is_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = self.rows(
                **{
                    engine: {
                        "row_count": 0,
                        "id_set_hash": "e3b0c442",
                        "null_count": 0,
                        "duplicate_count": 0,
                    }
                    for engine in ENGINES
                }
            )
            write_reports(self.write_raw(root, rows), root / "out")
            self.assertEqual("match", self.status(root))


if __name__ == "__main__":
    unittest.main()
