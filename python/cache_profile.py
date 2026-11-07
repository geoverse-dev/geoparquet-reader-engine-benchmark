"""DuckDB cache profiles; the default profile deliberately changes nothing."""

import os

PROFILE_SETTINGS = {
    "httpfs_connection_caching": "true",
    "enable_http_metadata_cache": "true",
    "parquet_metadata_cache": "true",
    "validate_external_file_cache": "NO_VALIDATION",
}


def apply_duckdb_profile(connection, profile):
    if profile == "default":
        return "engine_defaults"
    if profile != "tuned":
        raise ValueError("DUCKDB_PROFILE must be default or tuned")
    resolved = []
    for name, default in PROFILE_SETTINGS.items():
        value = os.environ.get("DUCKDB_" + name.upper(), default)
        sql_value = value if value.lower() in {"true", "false"} else f"'{value}'"
        connection.execute(f"SET {name}={sql_value}")
        resolved.append(f"{name}={value}")
    return ";".join(resolved)
