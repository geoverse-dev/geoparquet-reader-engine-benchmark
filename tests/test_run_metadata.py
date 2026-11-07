import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from python.run_metadata import build_manifest


class RunMetadataTest(unittest.TestCase):
    def test_manifest_is_complete_and_has_no_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workload = root / "workload.csv"
            workload.write_text("name,minx,miny,maxx,maxy\nq,0,0,1,1\n")
            (root / "vendor/parquetry").mkdir(parents=True)
            config = {
                "operation": "materialize",
                "scenario": "sequential",
                "concurrency": 1,
                "query_count": 1,
                "warmup_count": 0,
                "cache_configuration": "defaults",
            }
            with patch.dict(
                os.environ,
                {"AWS_SECRET_ACCESS_KEY": "super-secret", "AWS_ACCESS_KEY_ID": "key"},
            ):
                manifest = build_manifest(
                    root, workload, ["sample.parquet"], config, [{"etag": "x"}]
                )
            required = {
                "benchmark_git_commit",
                "benchmark_timestamp_utc",
                "engine",
                "engine_version",
                "java_version",
                "python_version",
                "duckdb_version",
                "parquetry_commit",
                "parquet_java_version",
                "dataset",
                "dataset_object_identity",
                "workload_file",
                "workload_sha256",
                "operation",
                "scenario",
                "concurrency",
                "query_count",
                "warmup_count",
                "cache_configuration",
            }
            self.assertFalse(required - manifest.keys())
            encoded = json.dumps(manifest)
            self.assertNotIn("super-secret", encoded)
            self.assertNotIn("AWS_SECRET_ACCESS_KEY", encoded)


if __name__ == "__main__":
    unittest.main()
