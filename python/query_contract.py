"""Shared primitives for the versioned three-reader query contract."""

import hashlib
import struct

CONTRACT_VERSION = "bbox-v1"
BBOX_SQL_PREDICATE = (
    "bbox.xmin <= CAST(? AS REAL) AND bbox.xmax >= CAST(? AS REAL) "
    "AND bbox.ymin <= CAST(? AS REAL) AND bbox.ymax >= CAST(? AS REAL)"
)


def encode_id(identifier):
    """Encode one nullable ID using the bbox-v1 canonical framing."""
    if identifier is None:
        return b"\x00"
    value = str(identifier).encode("utf-8")
    return b"\x01" + struct.pack(">Q", len(value)) + value


def id_multiset_hash(identifiers):
    """Hash IDs independently of iteration order while retaining duplicates."""
    digest = hashlib.sha256()
    for value in sorted(encode_id(identifier) for identifier in identifiers):
        digest.update(value)
    return digest.hexdigest()


def _nullable_bytes(value):
    if value is None:
        return b"\x00"
    return b"\x01" + struct.pack(">Q", len(value)) + value


def encode_materialized_row(identifier, geometry, bbox):
    """Canonical bbox-v1 bytes for id, physical WKB and four FLOAT leaves."""
    encoded = bytearray(encode_id(identifier))
    encoded.extend(_nullable_bytes(geometry))
    if bbox is None:
        encoded.extend(b"\x00")
    else:
        encoded.extend(b"\x01")
        values = (
            bbox.get("xmin"),
            bbox.get("ymin"),
            bbox.get("xmax"),
            bbox.get("ymax"),
        )
        for value in values:
            encoded.extend(
                b"\x00" if value is None else b"\x01" + struct.pack(">f", value)
            )
    return bytes(encoded)


def materialized_multiset_hash(rows):
    """Hash fully consumed rows independent of reader iteration order."""
    digest = hashlib.sha256()
    encoded = sorted(encode_materialized_row(*row) for row in rows)
    for row in encoded:
        digest.update(struct.pack(">Q", len(row)))
        digest.update(row)
    return digest.hexdigest()
