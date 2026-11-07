import csv
import tempfile
import unittest
from pathlib import Path

from scripts.validate_results import write_reports


class ValidationReportTest(unittest.TestCase):
    def write_raw(self, root, rows):
        path = root / "raw.csv"
        with path.open("w", newline="") as stream:
            writer = csv.DictWriter(
                stream,
                fieldnames=[
                    "dataset",
                    "bbox_name",
                    "engine",
                    "id",
                    "id_is_null",
                    "error",
                ],
            )
            writer.writeheader()
            writer.writerows(rows)
        return path

    def test_order_does_not_matter_and_duplicates_do(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = []
            for engine, ids in {
                "duckdb": ["a", "b", "b"],
                "parquetry": ["b", "a", "b"],
                "parquet-java": ["a", "b"],
            }.items():
                rows.extend(
                    {
                        "dataset": "d",
                        "bbox_name": "q",
                        "engine": engine,
                        "id": value,
                        "id_is_null": "false",
                        "error": "",
                    }
                    for value in ids
                )
            with self.assertRaises(SystemExit):
                write_reports(self.write_raw(root, rows), root / "out")
            summary = list(csv.DictReader((root / "out/validation-summary.csv").open()))
            self.assertEqual("row_count_mismatch", summary[0]["validation_status"])

    def test_same_count_different_ids_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = []
            for engine, value in {
                "duckdb": "a",
                "parquetry": "b",
                "parquet-java": "a",
            }.items():
                rows.append(
                    {
                        "dataset": "d",
                        "bbox_name": "q",
                        "engine": engine,
                        "id": value,
                        "id_is_null": "false",
                        "error": "",
                    }
                )
            write_reports(self.write_raw(root, rows), root / "out", strict=False)
            summary = list(csv.DictReader((root / "out/validation-summary.csv").open()))
            self.assertEqual(
                "id_multiplicity_mismatch", summary[0]["validation_status"]
            )

    def test_all_empty_query_is_retained_and_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = [
                {
                    "dataset": "d",
                    "bbox_name": "empty",
                    "engine": engine,
                    "id": "",
                    "id_is_null": "marker",
                    "error": "",
                }
                for engine in ("duckdb", "parquetry", "parquet-java")
            ]
            write_reports(self.write_raw(root, rows), root / "out")
            summary = list(csv.DictReader((root / "out/validation-summary.csv").open()))
            self.assertEqual(["match"], [row["validation_status"] for row in summary])


if __name__ == "__main__":
    unittest.main()
