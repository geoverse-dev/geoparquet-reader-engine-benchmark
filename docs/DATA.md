# Data, remote access and credentials

## Published public objects (recorded during original study)

Base URL: `https://pub-f31f4e8d3c804f2bab542268b9915ad7.r2.dev/`

- `raw.parquet` (~2.3 GiB)
- `optimized-v1.1.parquet` (~2.3 GiB)
- `optimized-v2.0.parquet` (~2.3 GiB)

Run `make data` to download all three to `data/raw/` and `data/optimized/`. Set `PUBLIC_BASE_URL` if you mirror the objects. Public endpoint availability was not rechecked while packaging.

## Remote benchmark requirements

Default R2 API endpoint: `https://3e5316dddf480d9077bcc2009d58aa10.r2.cloudflarestorage.com`, bucket `geoparquet-benchmarks`, region `auto`.

The scripts use S3-compatible authentication. A publicly downloadable object is **not automatically accessible through the authenticated S3 API without credentials**. To run on the original bucket you need read-only API credentials issued by its owner. Otherwise:

1. Download the three files (`make data`).
2. Upload them under the same object names to an S3-compatible bucket you control.
3. Export `R2_BUCKET`, `R2_ENDPOINT`, `R2_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`.
4. Run `make smoke`, then `make bench`.

Example environment (values are placeholders):

```bash
export R2_BUCKET=my-geoparquet-benchmark
export R2_ENDPOINT=https://my-account-id.r2.cloudflarestorage.com
export R2_REGION=auto
export AWS_ACCESS_KEY_ID=YOUR_READ_ONLY_KEY
export AWS_SECRET_ACCESS_KEY=YOUR_READ_ONLY_SECRET
```

Do not commit credentials or generated datasets. For faithful latency comparison, prefer an endpoint in the same region/network as the original test or disclose the difference.

## Local optimization

`make optimize` requires `data/raw/raw.parquet`, and writes both optimized variants to `data/optimized/`. The original archive had an NFS symlink at `data/raw/raw.parquet` that pointed to a private machine path; it is intentionally not shipped.

The `config/` directory contains original workload coordinates. The historical generated files' hashes are in `results/reference/artifacts.json`.
