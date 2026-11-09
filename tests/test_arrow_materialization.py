import unittest

import duckdb

from python.arrow_materialization import materialized_arrow_hash
from python.query_contract import materialized_multiset_hash


class ArrowMaterializationTest(unittest.TestCase):
    def test_matches_python_row_contract(self):
        connection = duckdb.connect()
        table = connection.execute(
            """
            SELECT * FROM (VALUES
              ('b', blob 'wkb-b', {'xmin': 1::FLOAT, 'ymin': 2::FLOAT,
                                    'xmax': 3::FLOAT, 'ymax': 4::FLOAT}),
              ('a', blob 'wkb-a', {'xmin': -1::FLOAT, 'ymin': -2::FLOAT,
                                    'xmax': 5::FLOAT, 'ymax': 6::FLOAT})
            ) AS rows(id, geometry, bbox)
            """
        ).to_arrow_table()
        expected = materialized_multiset_hash(
            [
                ("b", b"wkb-b", {"xmin": 1.0, "ymin": 2.0, "xmax": 3.0, "ymax": 4.0}),
                ("a", b"wkb-a", {"xmin": -1.0, "ymin": -2.0, "xmax": 5.0, "ymax": 6.0}),
            ]
        )
        self.assertEqual(expected, materialized_arrow_hash(table))


if __name__ == "__main__":
    unittest.main()
