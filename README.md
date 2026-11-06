# Remote GeoParquet Reader Benchmark: DuckDB vs Parquetry vs parquet-java

[Leer en español](README.es.md)

A reproducible benchmark harness comparing **DuckDB/httpfs**, **Parquetry**, and **Apache parquet-java**, reading remote GeoParquet over S3-compatible object storage (Cloudflare R2 in the original experiments). Parquetry uses Tileverse Storage; parquet-java uses its conventional Hadoop S3A backend and defaults. No benchmark arm downloads the whole object locally. The repository evaluates the **underlying readers**, not GeoServer WMS/WFS or DataStore implementations.

The original study uses Overture Maps **Argentina buildings** (~26.77 million records) in three layouts: `raw`, Hilbert/ZSTD GeoParquet 1.1, and Hilbert/ZSTD GeoParquet 2.0. The principal benchmark uses **150 BBOX queries** and compares DuckDB/httpfs defaults, Parquetry with Tileverse `CachingRangeReader`, and parquet-java with Hadoop S3A defaults. It measures latency, row counts, percentiles, and the range metrics each backend exposes; diagnostic runs examine first-read I/O, cache reuse, geographic jumps, and process memory.

## Requirements

Linux or macOS shell, `git`, `curl`, `make`, **Java 25** (including preview features) for Parquetry, a **Java 17 runtime** for parquet-java/Hadoop S3A, Maven, Python 3.11+, and [uv](https://docs.astral.sh/uv/). Set `JAVA17_HOME` or `PARQUET_JAVA_BIN` if Java 17 is not installed at the common Linux path. The separate runtime is required because current Hadoop S3A is incompatible with Java 25; it does not change the workload. AWS CLI is optional if you supply R2 credentials through environment variables.

**Parquetry is a source dependency** (snapshot artifacts). `make setup` clones `https://github.com/tileverse-io/parquetry` into `vendor/parquetry`, fetches the remote, and builds the latest commit from `origin/main` by default. This repository deliberately does not contain the upstream library or precompiled JARs. For a reproducible run, pin a branch, tag, or commit with `PARQUETRY_REF`, for example `PARQUETRY_REF=876687bc865fb3fc0526bd3c7d69d030db6a7d44 make setup`. The exact resolved commit is printed by setup and should be recorded with published results.

## Quick start (remote comparison)

```bash
make check
make setup
# Supply your own S3-compatible credentials (read-only permissions suffice):
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."
make smoke
make bench
```

If you already have an AWS CLI profile called `r2-benchmark`, the scripts attempt to load its access key and secret automatically. Otherwise export them. **Never commit credentials.**

The default bucket and endpoint match the original study:

```text
R2_BUCKET=geoparquet-benchmarks
R2_ENDPOINT=https://3e5316dddf480d9077bcc2009d58aa10.r2.cloudflarestorage.com
R2_REGION=auto
```

This is an S3 API endpoint, **not an anonymous URL**: access requires valid credentials for that bucket. For public downloads, the original R2 development endpoint is:

- https://pub-f31f4e8d3c804f2bab542268b9915ad7.r2.dev/raw.parquet
- https://pub-f31f4e8d3c804f2bab542268b9915ad7.r2.dev/optimized-v1.1.parquet
- https://pub-f31f4e8d3c804f2bab542268b9915ad7.r2.dev/optimized-v2.0.parquet

These URLs are recorded from the original experiments, **not revalidated here**. The public `r2.dev` endpoint can be rate-limited; it is primarily suitable for downloads, not controlled latency benchmarks. If you do not have R2 S3 credentials, run `make data`, upload the three objects to your own S3-compatible bucket, then set `R2_BUCKET`, `R2_ENDPOINT`, and credentials. See `docs/DATA.md`.

## Commands

| Command | Purpose | Main output |
|---|---|---|
| `make check` | Check tools and local files | Console |
| `make setup` | `uv sync`, clone/build Parquetry, build benchmark | Maven artifacts |
| `make build` | Rebuild Java benchmark and upstream snapshot | `java/target/` |
| `make smoke` | First BBOX, 0 warmups, all 3 readers and layouts | `results/smoke-*.csv` |
| `make bench` | DuckDB/httpfs, cached Parquetry, and parquet-java/S3A defaults; 150 BBOX per layout | `results/r2-comparable-*.csv` |
| `PARQUETRY_CACHE=off make bench` | Uncached Parquetry diagnostic | `results/r2-comparable-nocache-*.csv` |
| `make report` | Regenerate summary from benchmark runs | `results/r2-comparable-summary.csv` |
| `make io-diagnostics` | First-query HTTP/filesystem vs logical range reads | Console |
| `make duckdb-cache` | DuckDB cache behavior across query positions | `results/r2-duckdb-cache-diag.csv` |
| `make geographic-jumps` | Tandil → BA → Córdoba → Mendoza → Bariloche → Salta → Tandil | CSVs |
| `make cache` | Parquetry with/without Tileverse caching over repeated regions | CSV |
| `make memory` | RSS/heap/cache metrics during geographic jumps | CSVs |
| `make benchmark-suite` | Full sequential, geographic, memory, and concurrent comparison for all 3 readers | `results/` |
| `make data` | Download all three public GeoParquet files | `data/` |
| `make optimize` | Recreate optimized layouts from `data/raw/raw.parquet` | `data/optimized/` |
| `make all` | Main comparison + cache + geography + memory | `results/` |

For a quick small run:

```bash
WARMUPS=0 LIMIT=3 make bench
```

For the full original persistent workload:

```bash
WARMUPS=2 LIMIT=0 make bench
```

`LIMIT=0` means all 150 rows. `WARMUPS` repeats the full workload before the measured pass. These tests use a **persistent reader process per engine/layout**; they are not cold-process CLI benchmarks. First-read diagnostics are separate.

`PARQUETRY_CACHE=on` is the default for Parquetry. One Tileverse `CachingRangeReader` and one `ParquetSource` are reused per layout. parquet-java constructs a `ParquetReader` per filtered query over a persistent Hadoop S3A filesystem and leaves S3A buffering, readahead, and connection behavior at their native defaults. `PARQUETRY_CACHE=off` only changes Parquetry and writes separate diagnostic files. DuckDB keeps its existing `httpfs` defaults.

With `WARMUPS=0`, the measured pass is labeled `first_access_session`, but this does not guarantee empty OS, network, CDN, or other shared caches. With one or more warmups, it is labeled `persistent_warm_session`.

### Outputs and interpretation

`results/r2-comparable-runs.csv` records the reader configuration, benchmark/cache mode, lifecycle, warmup count, query rows and latency. Parquetry rows also record delegate range calls and bytes. parquet-java leaves those fields empty because Hadoop S3A does not expose equivalent per-query counters through parquet-java. The summary groups query names by size, reports median/mean/p95/p99, and validates every non-DuckDB reader against DuckDB per query.

The headline result is a comparison of complete, conventional configurations—DuckDB/httpfs defaults, Parquetry with Tileverse cache, and parquet-java with Hadoop S3A defaults—not an isolated comparison of decoding engines or caches.

**Important:** DuckDB and parquet-java use the same stored `bbox` comparisons. Parquetry uses `geometry.bboxIntersects`, so its result can differ by a few boundary features. parquet-java pushes the numeric BBOX predicate through `FilterCompat`, projects `id`, `geometry`, and `bbox`, and counts materialized records. I/O counters across the three native backends are not treated as equivalent.

See [Methodology](docs/METHODOLOGY.md) and [Data and credentials](docs/DATA.md).

## Limitations

- No end-to-end GeoServer benchmarking; results measure reader libraries.
- Results depend on R2 region, network, dependency versions, caches, JVM/Python configuration and workload ordering.
- First-query cost and steady-state performance must be reported separately.
- GeoParquet-IO is used to **prepare** optimized datasets; the sequential remote comparison covers **DuckDB, Parquetry, and parquet-java**.
- Java 25 + preview is required for Parquetry; parquet-java/S3A runs separately on Java 17.
- Run `make smoke` on your machine before publishing new measurements. Java 25 and access to the configured S3-compatible bucket are required.

## Concurrent requests (experimental)

`make bench-concurrent` runs all three readers: DuckDB shares its database across worker cursors, Parquetry shares a `ParquetSource`, and parquet-java creates independent `ParquetReader` instances over the same persistent Hadoop S3A filesystem. It reports throughput (QPS), p50/p95/p99 query latency, peak process RSS on Linux, and total row counts. Workloads, scenarios, warmups, concurrency levels, and metrics are unchanged across readers.

```bash
# Requires the same S3 credentials and `make setup` as the main benchmark
SCENARIO=warm LEVELS="1 2 4 8 16" QUERIES=40 make bench-concurrent
SCENARIO=mixed LEVELS="1 2 4 8" QUERIES=40 make bench-concurrent
SCENARIO=cold LEVELS="1 2 4" QUERIES=20 make bench-concurrent
```

Each direct `make bench-concurrent` invocation overwrites `results/r2-concurrent.csv`. For repeatable, consolidated runs, use the dedicated commands below. `OBJECT` selects the dataset (default `optimized-v1.1.parquet`). `WORKLOAD`, `WARMUP`, `OUT`, `LEVELS`, `QUERIES` are configurable.

**Interpretation:** `warm` pre-queries the first six BBOXes and repeatedly queries only those; `mixed` pre-queries six BBOXes but cycles over the entire workload; `cold` skips warmup (new process, but OS/CDN caches may still be warm). RSS is sampled every 25 ms, so very brief peaks may be missed. Each concurrency level starts a **new process**, and warmup is performed again. This is *closed-loop*, fixed-total-query testing; it is not a fixed arrival-rate load generator. Query latency includes time spent executing in a worker, not time waiting in the executor queue. The workload uses shared readers and is designed to measure concurrent read behavior. DuckDB and Parquetry use slightly different spatial predicates, as in the original study. Do not compare concurrency figures to the sequential paper results without noting workload and cache differences.

## Repeated concurrent benchmark suite

The three scenario scripts execute **three independent runs by default** and save one CSV per run plus a median-across-runs summary. The complete suite produces nine runs.

```bash
make bench-concurrent-warm
make bench-concurrent-mixed
make bench-concurrent-cold
# Or run all scenarios and package the results:
make bench-concurrent-report
```

| Scenario | Default workers | Queries per worker-level run | Pattern |
|---|---|---:|---|
| `warm` | 1, 2, 4, 8, 16 | 150 | Six repeated windows, explicitly warmed |
| `mixed` | 1, 2, 4, 8, 16 | 150 | Entire workload, first six windows warmed |
| `cold` | 1, 2, 4, 8 | 40 | No explicit warmup (not guaranteed cold network/cache) |

Overrides: `RUNS=5`, `LEVELS="1 2 4"`, `QUERIES=100`, `REPORT_DIR=results/custom`, and `OBJECT=raw.parquet`. Each run launches fresh processes for each of the three readers and worker level. Existing run CSVs for the selected scenario are replaced, preventing stale data from affecting summaries.

Outputs are in `results/report-concurrency/`: `<scenario>-run-<N>.csv` and `<scenario>-summary.csv`. `make bench-concurrent-report` also creates `results/geoparquet-concurrency-report-data.tar.gz`. The summary reports the median of the per-run metrics (including each run's p95/p99), not percentiles pooled over all requests.

## GitHub Actions

`.github/workflows/benchmark.yml` runs shell and Python syntax checks on pushes and pull requests. Full remote benchmarks are **manual only** (`Actions` → `Benchmark checks` → `Run workflow`), because they require credentials, network access, and substantial execution time. Configure repository secrets `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY`. Optional repository variables: `R2_BUCKET`, `R2_ENDPOINT`, `R2_REGION`. The workflow installs Java 25, sets up the source dependencies, runs smoke, executes the selected scenario(s), and uploads the generated CSVs as a workflow artifact.

The remote job uses GitHub-hosted runners; for comparable performance numbers, use a consistent runner class or a dedicated self-hosted runner.
