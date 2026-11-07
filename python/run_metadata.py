"""Build credential-free, machine-readable benchmark run manifests."""

import hashlib
import json
import platform
import subprocess
from datetime import datetime, timezone


def command(*args, cwd=None):
    try:
        result = subprocess.run(
            args, cwd=cwd, check=True, capture_output=True, text=True
        )
        return (result.stdout.strip() or result.stderr.strip()).splitlines()[0]
    except (OSError, subprocess.CalledProcessError):
        return "not_available"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dataset_identity(bucket, object_name, endpoint):
    raw = command(
        "aws",
        "s3api",
        "head-object",
        "--bucket",
        bucket,
        "--key",
        object_name,
        "--endpoint-url",
        endpoint,
        "--output",
        "json",
    )
    if raw == "not_available":
        return {"bucket": bucket, "object": object_name, "status": "not_available"}
    try:
        head = json.loads(raw)
    except json.JSONDecodeError:
        return {"bucket": bucket, "object": object_name, "status": "not_available"}
    return {
        "bucket": bucket,
        "object": object_name,
        "size_bytes": head.get("ContentLength"),
        "etag": head.get("ETag"),
        "version_id": head.get("VersionId"),
        "checksum_sha256": head.get("ChecksumSHA256"),
        "etag_is_content_hash": False,
    }


def build_manifest(root, workload, objects, configuration, identities):
    parquetry = root / "vendor/parquetry"
    return {
        "schema_version": "benchmark-run-v1",
        "benchmark_git_commit": command("git", "rev-parse", "HEAD", cwd=root),
        "benchmark_timestamp_utc": datetime.now(timezone.utc).isoformat(),  # noqa: UP017 -- Python 3.10
        "engine": ["duckdb", "parquetry", "parquet-java"],
        "engine_version": {
            "duckdb": command(
                "uv",
                "run",
                "--frozen",
                "python",
                "-c",
                "import duckdb; print(duckdb.__version__)",
                cwd=root,
            ),
            "parquetry": "1.0-SNAPSHOT",
            "parquet-java": "1.17.0",
        },
        "engine_git_commit": {
            "parquetry": command("git", "rev-parse", "HEAD", cwd=parquetry)
        },
        "java_version": command("java", "-version"),
        "python_version": platform.python_version(),
        "duckdb_version": command(
            "uv",
            "run",
            "--frozen",
            "python",
            "-c",
            "import duckdb; print(duckdb.__version__)",
            cwd=root,
        ),
        "parquetry_commit": command("git", "rev-parse", "HEAD", cwd=parquetry),
        "parquet_java_version": "1.17.0",
        "dataset": objects,
        "dataset_object_identity": identities,
        "workload_file": str(workload),
        "workload_sha256": sha256(workload),
        **configuration,
    }
