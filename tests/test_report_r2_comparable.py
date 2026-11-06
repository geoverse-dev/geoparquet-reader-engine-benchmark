import csv
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "scripts" / "report-r2-comparable.py"


class ReportComparableTest(unittest.TestCase):
    def run_report(self, contents):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "runs.csv"
            summary = Path(directory) / "summary.csv"
            source.write_text(contents)
            process = subprocess.run(
                [sys.executable, REPORT, source, summary],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=True,
            )
            with summary.open(newline="") as stream:
                rows = list(csv.DictReader(stream))
        return process, rows

    def test_cache_aligned_report_labels_and_validates_rows(self):
        process, rows = self.run_report(
            "layout,reader,reader_config,benchmark_mode,cache_mode,"
            "cache_lifecycle,warmups,query,rows,elapsed_ms,"
            "remote_range_calls,remote_bytes_requested,remote_bytes_returned\n"
            "raw,duckdb,DuckDB default,cache_aligned,duckdb_default,"
            "persistent_warm_session,2,small-01,10,2.0,,,\n"
            "raw,parquetry,Parquetry + Tileverse Cache,cache_aligned,"
            "tileverse_on,persistent_warm_session,2,small-01,11,1.0,2,200,180\n"
            "raw,parquet-java,parquet-java + Hadoop S3A defaults,cache_aligned,"
            "s3a_default,persistent_warm_session,2,small-01,10,1.5,,,\n"
        )

        self.assertIn("row counts differ", process.stderr)
        parquetry = next(row for row in rows if row["reader"] == "parquetry")
        self.assertEqual("tileverse_on", parquetry["cache_mode"])
        self.assertEqual("row_count_mismatch", parquetry["validation_status"])
        self.assertEqual("2", parquetry["remote_range_calls"])
        parquet_java = next(row for row in rows if row["reader"] == "parquet-java")
        self.assertEqual("row_counts_match", parquet_java["validation_status"])
        self.assertEqual("s3a_default", parquet_java["cache_mode"])

    def test_legacy_report_is_not_labeled_cache_aligned(self):
        process, rows = self.run_report(
            "layout,reader,query,rows,elapsed_ms\n"
            "raw,duckdb,small-01,10,2.0\n"
            "raw,parquetry,small-01,10,1.0\n"
        )

        self.assertIn("legacy input", process.stderr)
        self.assertTrue(
            all(row["comparison_status"] == "legacy_unspecified" for row in rows)
        )


if __name__ == "__main__":
    unittest.main()
