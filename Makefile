.DEFAULT_GOAL := help
SHELL := /bin/bash
.PHONY: help check setup build test-contract-engines validate data optimize smoke bench report cache io-diagnostics duckdb-cache geographic-jumps memory bench-concurrent bench-concurrent-warm bench-concurrent-mixed bench-concurrent-cold bench-concurrent-report benchmark-suite all
help:
	@echo "GeoParquet reader benchmark (DuckDB vs Parquetry vs parquet-java)"
	@echo "  make check              Check requirements"
	@echo "  make setup              Install Python deps, clone/build Parquetry + Java"
	@echo "  make build              Rebuild Parquetry + Java benchmark"
	@echo "  make test-contract-engines  Local bbox-v1 check across all readers"
	@echo "  make validate           Validate IDs for all layouts and readers"
	@echo "  make smoke              1 remote query per layout/reader"
	@echo "  make bench              Compare all 3 readers (150 BBOX/layout)"
	@echo "  PARQUETRY_CACHE=off make bench  Uncached Parquetry diagnostic"
	@echo "  make report             Summarize existing benchmark CSV"
	@echo "  make cache              Tileverse cache vs no-cache"
	@echo "  make io-diagnostics     Remote I/O comparison"
	@echo "  make duckdb-cache       Inspect DuckDB remote cache reuse"
	@echo "  make geographic-jumps   Query across six regions"
	@echo "  make memory             Process memory comparison"
	@echo "  make bench-concurrent   Single shared-reader concurrent workload"
	@echo "  make bench-concurrent-warm  3 warm repetitions and summary"
	@echo "  make bench-concurrent-mixed 3 mixed repetitions and summary"
	@echo "  make bench-concurrent-cold  3 cold repetitions and summary"
	@echo "  make bench-concurrent-report All 3 scenarios (9 runs)"
	@echo "  make benchmark-suite    Full 3-reader sequential/concurrent suite"
	@echo "  make data               Download public datasets (about 7 GiB total)"
	@echo "  make optimize           Generate optimized layouts from local raw"
	@echo "  make all                Run main benchmark and diagnostics"
check:
	@bash scripts/check.sh
setup:
	@bash scripts/setup.sh
build:
	@bash scripts/build-java.sh
test-contract-engines:
	@bash scripts/test-query-contract-engines.sh
validate:
	@bash scripts/run-validation.sh
data:
	@bash scripts/download-data.sh
optimize:
	@bash scripts/optimize.sh
smoke:
	@WARMUPS=0 LIMIT=1 OUT=results/smoke-runs.csv SUMMARY=results/smoke-summary.csv bash scripts/run-r2-comparable.sh
bench:
	@bash scripts/run-r2-comparable.sh
report:
	@uv run --frozen python scripts/report-r2-comparable.py results/r2-comparable-runs.csv results/r2-comparable-summary.csv
cache:
	@bash scripts/run-r2-parquetry-tileverse-cache.sh
io-diagnostics:
	@bash scripts/run-r2-io-diag.sh
duckdb-cache:
	@bash scripts/run-r2-duckdb-cache-diag.sh
geographic-jumps:
	@bash scripts/run-r2-geographic-jumps.sh
memory:
	@bash scripts/run-r2-memory-comparison.sh
bench-concurrent:
	@bash scripts/run-r2-concurrent.sh
all: bench cache geographic-jumps memory

bench-concurrent-warm:
	@bash scripts/bench-concurrent-warm.sh
bench-concurrent-mixed:
	@bash scripts/bench-concurrent-mixed.sh
bench-concurrent-cold:
	@bash scripts/bench-concurrent-cold.sh
bench-concurrent-report: bench-concurrent-warm bench-concurrent-mixed bench-concurrent-cold
	@tar -czf results/geoparquet-concurrency-report-data.tar.gz -C results report-concurrency
	@echo "Archive: results/geoparquet-concurrency-report-data.tar.gz"
benchmark-suite: bench geographic-jumps memory bench-concurrent-report
