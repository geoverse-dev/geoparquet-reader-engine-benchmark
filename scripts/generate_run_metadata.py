#!/usr/bin/env python3
import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from python.run_metadata import build_manifest, dataset_identity

parser = argparse.ArgumentParser()
parser.add_argument("--workload", type=Path, required=True)
parser.add_argument("--bucket", required=True)
parser.add_argument("--endpoint", required=True)
parser.add_argument("--object", action="append", required=True)
parser.add_argument("--operation", default="materialize")
parser.add_argument("--scenario", default="sequential")
parser.add_argument("--concurrency", default="1")
parser.add_argument("--query-count", type=int, required=True)
parser.add_argument("--warmup-count", type=int, default=0)
parser.add_argument("--cache-configuration", required=True)
parser.add_argument("--output", type=Path, default=Path("results/run-metadata.json"))
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
identities = [
    dataset_identity(args.bucket, value, args.endpoint) for value in args.object
]
configuration = {
    "operation": args.operation,
    "scenario": args.scenario,
    "concurrency": args.concurrency,
    "query_count": args.query_count,
    "warmup_count": args.warmup_count,
    "cache_configuration": args.cache_configuration,
}
manifest = build_manifest(root, args.workload, args.object, configuration, identities)
args.output.parent.mkdir(parents=True, exist_ok=True)
encoded = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
args.output.write_text(encoded, encoding="utf-8")
archive = args.output.parent / "manifests"
archive.mkdir(exist_ok=True)
stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")  # noqa: UP017 -- Python 3.10
(archive / f"{stamp}-{args.scenario}.json").write_text(encoded, encoding="utf-8")
print(args.output)
