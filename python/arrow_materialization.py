"""Canonical bbox-v1 consumption of DuckDB Arrow results."""

import hashlib
import struct

from python.query_contract import encode_materialized_row


def materialized_arrow_hash(table):
    """Consume an Arrow table and hash id, physical WKB, and bbox values."""
    encoded = []
    for batch in table.to_batches():
        ids = batch.column(batch.schema.get_field_index("id"))
        geometries = batch.column(batch.schema.get_field_index("geometry"))
        bboxes = batch.column(batch.schema.get_field_index("bbox"))
        for index in range(batch.num_rows):
            identifier = ids[index].as_py()
            geometry = geometries[index].as_py()
            bbox = bboxes[index].as_py()
            encoded.append(encode_materialized_row(identifier, geometry, bbox))
    digest = hashlib.sha256()
    for row in sorted(encoded):
        digest.update(struct.pack(">Q", len(row)))
        digest.update(row)
    return digest.hexdigest()
