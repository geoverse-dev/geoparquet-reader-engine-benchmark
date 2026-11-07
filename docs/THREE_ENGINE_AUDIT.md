# Three-engine benchmark audit

Status: Phase 1 technical assessment. This document describes the implementation
before the common query contract and equivalent operations are introduced.

## Scope

Readers:

- DuckDB with `httpfs`.
- Parquetry with Tileverse Storage and optional `CachingRangeReader`.
- Apache parquet-java with Hadoop S3A defaults.

The audit covers the primary sequential and concurrent BBOX runners. Specialized
cache and I/O diagnostics intentionally remain engine-specific.

## Current behavior

| Concern | DuckDB/httpfs | Parquetry/Tileverse | parquet-java/S3A |
|---|---|---|---|
| Spatial predicate | Four inclusive comparisons over stored `bbox` leaves | `geometry.bboxIntersects`, evaluated as the geometry WKB envelope with inclusive edges | Four inclusive `FilterCompat` comparisons over stored `bbox` leaves |
| Predicate value type | Python `float` parameters | `double` query envelope | Query coordinates cast to `float` to match physical bbox leaves |
| Selected columns | `id`, `geometry`, `bbox` | Physical projection of `id`, `geometry`, `bbox` | Requested schema containing `id`, `geometry`, `bbox` |
| Result consumption | `fetchall()` creates Python results | `stream.count()` | Iterates `Group` records and counts them |
| Dataset lifecycle | One in-memory database and Parquet relation per process/layout | One `ParquetSource` per process/layout | One persistent S3A `InputFile`; a new filtered `ParquetReader` per query |
| Sequential cache | DuckDB defaults | Tileverse cache on by default | Hadoop S3A defaults |
| Concurrent sharing | One database with a cursor per worker thread | One shared `ParquetSource` and cache | One shared S3A filesystem/input; independent `ParquetReader` per query |
| Remote I/O metrics | Not exposed in the primary runner | Delegate range calls and bytes below the optional cache | Not exposed per query |
| Runtime | Python process with native DuckDB | Java 25 | Separate Java 17 process |

## Predicate assessment

The intended common contract is stored-BBOX intersection:

```text
bbox.xmin <= query.maxx
AND bbox.xmax >= query.minx
AND bbox.ymin <= query.maxy
AND bbox.ymax >= query.miny
```

DuckDB and parquet-java already implement this contract. Parquetry currently does
not: its spatial predicate derives each row's envelope from geometry WKB for final
record-level evaluation, even when covering columns can assist pruning.

Parquetry can address nested physical leaves through `ColumnPath.of("bbox",
"xmin")` and its predicate ADT supports `LtEq` and `GtEq`. The current fluent
builder has ordered overloads for `double`, but not `float`; the benchmark files
use physical `FLOAT` bbox leaves. The implementation spike should therefore use
typed `Predicate` records with `Value.FloatVal`, or add an upstream fluent overload,
and verify pushdown plus record-level evaluation on local fixtures.

### Null and exceptional values

- Ordinary ordered comparisons reject nulls in SQL, Parquetry's documented scalar
  semantics, and parquet-java filters. This is the proposed common null behavior.
- NaN, infinities, incomplete structs and invalid boxes (`min > max`) are not yet
  covered by shared fixtures. Their behavior must not be assumed equivalent.
- Boundary equality is intended to match because all four comparisons are
  inclusive. It still needs a cross-engine fixture test.

## Result-consumption assessment

The current primary result is not an equivalent-work comparison:

- DuckDB fully fetches the selected rows into Python objects.
- Parquetry counts a stream. The projection requests all three fields, but the
  benchmark does not explicitly access their values or geometry bytes.
- parquet-java constructs projected `Group` records but does not explicitly access
  their fields before counting.

Consequently, the current results must retain historical/scan-like semantics and
must not be relabeled as equivalent materialization. The proposed `materialize`
operation needs explicit field access and a shared checksum contract. `scan` should
remain a separate diagnostic operation.

## Lifecycle and timing

All three exclude process and engine initialization from per-query latency.
However, dataset-opening work is not identical:

- DuckDB initializes the database and extension before query timing.
- Parquetry opens one byte source and `ParquetSource` before query timing.
- parquet-java opens S3A and reads the schema before timing, but constructs a new
  `ParquetReader` inside every timed query.

The per-query parquet-java reader construction is part of its current conventional
API path and should not be removed silently. Phase 2 must either keep it as an
explicit architectural difference or demonstrate a lower-level reusable reader
whose observable contract is unchanged.

Warmups reuse the process-level state described above. A `cold` concurrency run
means no explicit application warmup in a new process; it does not imply empty OS,
CDN, object-store or network caches.

## Concurrency assessment

- All runners use the same query count, ordered index-to-window mapping and worker
  count for a given scenario.
- `warm` cycles over the first six windows; `mixed` uses the full workload after
  warming six; `cold` skips explicit warmup.
- Per-query timers begin inside worker tasks. Executor queue waiting is excluded
  from query latency for all three, while total wall time includes completion of
  the submitted workload.
- Query completion currently means fully fetched DuckDB rows, counted Parquetry
  records, or iterated parquet-java groups. This becomes equivalent only after the
  shared operation contract is implemented.

## Cache and I/O assessment

The benchmark compares native configurations rather than identical cache layers:

- DuckDB retains `httpfs` defaults.
- Parquetry uses a persistent Tileverse cache per layout by default; existing
  counters measure calls and bytes reaching its delegate, not physical HTTP.
- parquet-java uses Hadoop S3A defaults and currently exposes no reliable per-query
  remote request or byte counters.

Missing values must remain `not_available`; they must not be inferred from logical
reads. A future DuckDB `tuned` profile must be additive and must not replace the
default baseline.

## Decisions for the first implementation slice

1. Introduce a versioned stored-BBOX query contract shared by sequential,
   concurrent and validation code.
2. Preserve current benchmark behavior as `legacy_unspecified` until operation
   migration is explicit.
3. Implement local cross-engine fixtures before changing remote runners.
4. Define canonical ID multiset encoding before implementing `make validate`.
5. Define canonical materialization bytes before adding checksums.
6. Keep validation opt-in initially; performance results should carry a compatible
   validation status or `not_validated` rather than being deleted on failure.

## First fixture matrix

The local fixture suite should cover:

- Strictly internal overlap.
- Equality on each boundary.
- Disjoint boxes on each side.
- Null bbox struct and null bbox leaves where representable.
- NaN and infinity.
- Invalid min/max ordering.
- Empty result.
- Same IDs in different physical orders.
- Duplicate IDs.
- Same row count with different IDs.

Remote R2 checks remain opt-in and are not substitutes for these deterministic
fixtures.
