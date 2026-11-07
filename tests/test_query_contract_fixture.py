import tempfile
import unittest
from pathlib import Path

import duckdb

from python.query_contract import (
    BBOX_SQL_PREDICATE,
    CONTRACT_VERSION,
    id_multiset_hash,
    materialized_multiset_hash,
)
from tests.fixtures.generate_query_contract_fixture import generate


class QueryContractFixtureTest(unittest.TestCase):
    def test_bbox_v1_boundary_null_nan_and_invalid_semantics(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "query-contract.parquet"
            generate(fixture)
            connection = duckdb.connect(":memory:")
            rows = connection.execute(
                f"""
                SELECT id
                FROM read_parquet(?)
                WHERE {BBOX_SQL_PREDICATE}
                """,
                [str(fixture), 10.0, 0.0, 10.0, 0.0],
            ).fetchall()
            connection.close()

        identifiers = [row[0] for row in rows]
        self.assertEqual("bbox-v1", CONTRACT_VERSION)
        self.assertCountEqual(
            identifiers,
            [
                "inside-a",
                "duplicate",
                "duplicate",
                "touch-left",
                "touch-right",
                "touch-bottom",
                "touch-top",
                "infinite-bbox",
                "inverted-bbox",
                None,
            ],
        )

    def test_id_hash_is_order_independent_and_multiplicity_sensitive(self):
        first = ["b", None, "a", "a", ""]
        second = ["", "a", "b", "a", None]
        without_duplicate = ["", "a", "b", None]

        self.assertEqual(id_multiset_hash(first), id_multiset_hash(second))
        self.assertNotEqual(
            id_multiset_hash(first), id_multiset_hash(without_duplicate)
        )

    def test_same_count_with_different_ids_has_different_hash(self):
        self.assertNotEqual(id_multiset_hash(["a", "b"]), id_multiset_hash(["a", "c"]))

    def test_duckdb_predicate_rounds_query_coordinates_to_physical_float(self):
        self.assertEqual(4, BBOX_SQL_PREDICATE.count("CAST(? AS REAL)"))

    def test_materialized_hash_consumes_geometry_bbox_and_is_order_independent(self):
        bbox = {"xmin": 1.0, "ymin": 2.0, "xmax": 3.0, "ymax": 4.0}
        rows = [("a", b"wkb-a", bbox), (None, b"wkb-b", None)]
        self.assertEqual(
            materialized_multiset_hash(rows), materialized_multiset_hash(reversed(rows))
        )
        changed = [("a", b"changed", bbox), (None, b"wkb-b", None)]
        self.assertNotEqual(
            materialized_multiset_hash(rows), materialized_multiset_hash(changed)
        )


if __name__ == "__main__":
    unittest.main()
