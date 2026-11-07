"""Shared primitives for the versioned three-reader query contract."""

import hashlib
import struct

CONTRACT_VERSION = "bbox-v1"
BBOX_SQL_PREDICATE = (
    "bbox.xmin <= ? AND bbox.xmax >= ? AND bbox.ymin <= ? AND bbox.ymax >= ?"
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
