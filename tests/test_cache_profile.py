import unittest

from python.cache_profile import apply_duckdb_profile


class FakeConnection:
    def __init__(self):
        self.statements = []

    def execute(self, statement):
        self.statements.append(statement)


class CacheProfileTest(unittest.TestCase):
    def test_default_does_not_change_engine_settings(self):
        connection = FakeConnection()
        self.assertEqual("engine_defaults", apply_duckdb_profile(connection, "default"))
        self.assertEqual([], connection.statements)

    def test_tuned_records_resolved_settings(self):
        connection = FakeConnection()
        resolved = apply_duckdb_profile(connection, "tuned")
        self.assertEqual(4, len(connection.statements))
        self.assertIn("httpfs_connection_caching=true", resolved)

    def test_unknown_profile_fails(self):
        with self.assertRaises(ValueError):
            apply_duckdb_profile(FakeConnection(), "fastest")
