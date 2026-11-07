# Query contract

## `bbox-v1`

Status: initial contract for local conformance fixtures. Remote benchmark runners
must not claim this contract until their conformance tests pass.

### Inputs

- One Parquet or GeoParquet object.
- One named 2D query BBOX: `minx`, `miny`, `maxx`, `maxy`.
- The physical feature BBOX leaves `bbox.xmin`, `bbox.ymin`, `bbox.xmax`,
  `bbox.ymax`.

Query boxes are required to contain finite coordinates and to satisfy
`minx <= maxx` and `miny <= maxy`.

### Predicate

A feature matches when all four ordered comparisons are true:

```text
bbox.xmin <= query.maxx
AND bbox.xmax >= query.minx
AND bbox.ymin <= query.maxy
AND bbox.ymax >= query.miny
```

Edges are inclusive. This is BBOX intersection, not exact geometry intersection.
The geometry WKB must not be used to replace the stored BBOX in this contract.

### Null, NaN, infinity, and invalid feature boxes

- A null BBOX struct or null BBOX leaf does not match because an ordered
  comparison with null is unknown/false.
- A NaN BBOX leaf does not match an ordered comparison.
- Infinite feature coordinates participate in ordinary ordered comparisons.
- Feature boxes with `xmin > xmax` or `ymin > ymax` are not normalized or silently
  rejected by `bbox-v1`. The four comparisons above remain authoritative. Dataset
  quality validation may report them separately.

The last rule deliberately follows the canonical predicate literally. A future
contract may add validity constraints, but doing so is a semantic version change.

### Operations

`scan` applies the predicate, traverses every matched record and emits a row count.
It may avoid decoding non-predicate output columns.

`materialize` applies the same predicate, retrieves `id`, `geometry`, and `bbox`,
accesses every selected value, and emits row count plus a canonical checksum. The
materialization byte format will be specified separately before this operation is
used for rankings.

### Canonical ID multiset

Validation compares a multiset: ordering is ignored and duplicates are retained.
Each ID is encoded as:

```text
null ID:       0x00
non-null ID:   0x01 || uint64_be(utf8_length) || utf8_bytes
```

Encoded values are sorted lexicographically, concatenated without separators, and
hashed with SHA-256. This framing distinguishes null, empty strings, concatenation
boundaries, and duplicate multiplicity. Engines must also emit row count and
duplicate count; a hash alone is not sufficient diagnostic output.

### Selected values

- `id`
- `geometry` as its physical WKB bytes
- `bbox` with its four physical coordinates

The sequential, concurrent, and validation implementations must refer to the same
contract version and predicate construction code within each language.
