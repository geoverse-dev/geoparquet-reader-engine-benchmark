# Experimental methodology and provenance

## Source archive and scope

The repository contains Java and Python reader benchmarks, workload definitions, execution scripts, and result aggregation.

**Original Parquetry commit recorded during the study:** `876687bc865fb3fc0526bd3c7d69d030db6a7d44` (main at that time). To try this exact version:

```bash
make setup
git -C vendor/parquetry checkout 876687bc865fb3fc0526bd3c7d69d030db6a7d44
make build
```

Because Parquetry's upstream build and dependency graph may evolve, the historical commit may require adjustments. The default setup fetches the upstream repository and checks out the latest `origin/main` commit. Set `PARQUETRY_REF` to a branch, tag, or commit for a reproducible run. Always record the resolved commit printed by setup with published benchmark results. The benchmark source and Maven coordinates are from the provided archive.

## Dataset

Overture Maps buildings / Argentina. Original ~26,765,980 features, file size ~2,455,532,869 bytes. Optimized files are ~2.46 GB each, ZSTD compressed and Hilbert ordered. The optimization script uses `gpio sort hilbert` with `--row-group-size 50000`; 1.1 includes explicit `--add-bbox`, 2.0 uses native geometry metadata.

Input/output SHA256 checksums from the study are preserved in `results/reference/artifacts.json`. The input was originally a local NFS file, not bundled in the ZIP.

## Main benchmark: `make bench`

- Files: raw, optimized 1.1, optimized 2.0.
- Readers: DuckDB `read_parquet` with unchanged `httpfs` defaults and Parquetry with a Tileverse `CachingRangeReader` over the S3 range reader by default.
- Same 150 named BBOX windows from `config/workload-persistent.csv` (50 small, 50 medium, 50 large).
- Persistent process and reader for a full workload per layout; `WARMUPS` full warmup passes, then measured pass. The same Parquetry cache and `ParquetSource` survive both phases.
- Both select `id`, `geometry`, and `bbox`, and fully consume results.
- CSV records configuration labels, row count and elapsed time per BBOX. Parquetry also reports delegate range calls and bytes below the cache. Summary reports median/mean/p95/p99 and row-count validation.
- Important limitation: DuckDB uses `bbox` column intersection while Parquetry uses geometry bbox predicate. The outputs are not guaranteed row-identical.

`PARQUETRY_CACHE=off make bench` is an explicitly labeled no-cache diagnostic and writes `r2-comparable-nocache-*` outputs by default. It is not merged into the primary cache-aligned result. `WARMUPS=0` means no deliberate session prewarming, not a guarantee that external caches are empty. This benchmark compares two reader configurations as systems; it does not claim identical cache implementations or isolate intrinsic decoder performance.

## Diagnostics

- `make io-diagnostics`: first small window; DuckDB HTTP/FileSystem logs vs Parquetry logical ranges.
- `make duckdb-cache`: measure DuckDB I/O at selected query positions.
- `make geographic-jumps`: six separated Argentine regions and a Tandil repeat.
- `make cache`: compare Tileverse `CachingRangeReader` and uncached source, two rounds.
- `make memory`: per-query process RSS, JVM heap and cache metrics.

The Tileverse cache is a byte-range cache **below** the reader, not GeoWebCache tile caching. In the study, optimized data with cached ranges gave repeat BBOX queries in tens of milliseconds, while DuckDB's first remote query incurred significant startup I/O. Subsequent DuckDB queries benefit from internal state and previously accessed data.

## Provenance and reproducibility caveats

- `results/reference` contains outputs from the original uploaded archive; these are **historical data**, not results from this repackaging.
- No Cloudflare R2 credentials are included.
- The R2 API endpoint requires valid credentials; the public r2.dev links are for downloading and can be throttled.
- `INSTALL httpfs` can require network access on first run; DuckDB version comes from `uv.lock`.
- Some scripts log remote I/O through different layers, so logical Parquetry range calls are not directly equivalent to DuckDB physical GETs.
- Cache, network and process startup must be separated in any claims of speedup.
- Linux-specific RSS `/proc` instrumentation may not work on macOS; Linux is recommended for memory comparisons.

## Experimental concurrent workload

`make bench-concurrent` is a separate concurrent workload. Its measurements are **not** historical paper results. It uses shared engine state and per-worker queries, with `cold`, `warm`, and `mixed` scenarios. Parquetry uses a shared Tileverse `CachingRangeReader`; `cold` skips explicit warmup but is not guaranteed to have a cold OS/network cache. Each concurrency level runs in a fresh process. Results include throughput, latency distribution and sampled RSS. Verify Parquetry thread safety and compare row counts before drawing performance conclusions.

## Repeated concurrency runs

`make bench-concurrent-warm`, `make bench-concurrent-mixed`, and `make bench-concurrent-cold` each run the same engine pair at the configured concurrency levels three times by default. Each engine/level is a separate process. The output CSVs retain individual run metrics; `<scenario>-summary.csv` records the median of each metric across repetitions. These medians must not be interpreted as pooled request-level p95/p99 values.

The Parquetry path is `ParquetSource` → `ByteRangeSource` → `CachingRangeReader` → S3 `RangeReader`. DuckDB uses `httpfs` and its own cache behavior. Cache configuration is an essential part of the benchmark definition. `warm` repeats the first six spatial windows after explicit warmup; `mixed` queries the full workload; `cold` omits warmup only. Memory is peak sampled process RSS, not isolated engine heap.
