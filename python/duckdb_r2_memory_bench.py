#!/usr/bin/env python3
import argparse, csv, os, resource, time
import duckdb


def rss_bytes():
    # Linux: ru_maxrss is KiB and is a peak, not current RSS.
    with open("/proc/self/status") as f:
        vals = {}
        for line in f:
            if ":" in line:
                k, v = line.split(":", 1)
                vals[k] = v.strip()

    def kb(name):
        v = vals.get(name, "0 kB").split()[0]
        return int(v) * 1024

    return kb("VmRSS"), kb("VmHWM")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--workload", required=True)
    ap.add_argument("--layout", required=True)
    args = ap.parse_args()

    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs")
    endpoint = os.environ["R2_ENDPOINT"].replace("https://", "").replace("http://", "")
    con.execute("SET s3_endpoint=?", [endpoint])
    con.execute(
        "SET s3_region=?",
        [os.getenv("AWS_REGION", os.getenv("AWS_DEFAULT_REGION", "auto"))],
    )
    con.execute("SET s3_access_key_id=?", [os.environ["AWS_ACCESS_KEY_ID"]])
    con.execute("SET s3_secret_access_key=?", [os.environ["AWS_SECRET_ACCESS_KEY"]])
    con.execute("SET s3_url_style='path'")
    con.execute("SET s3_use_ssl=true")

    with open(args.workload) as f:
        qs = list(csv.DictReader(f))

    base_rss, _ = rss_bytes()
    print(
        "engine,layout,position,query,rows,elapsed_ms,rss_bytes,rss_delta_bytes,peak_rss_bytes"
    )
    for i, q in enumerate(qs, 1):
        # Match the comparable benchmark projection and stored bbox predicate.
        sql = f"""
        SELECT id, geometry, bbox
        FROM read_parquet('{args.url}')
        WHERE bbox.xmax >= ? AND bbox.xmin <= ?
          AND bbox.ymax >= ? AND bbox.ymin <= ?
        """
        params = [
            float(q["minx"]),
            float(q["maxx"]),
            float(q["miny"]),
            float(q["maxy"]),
        ]
        t = time.perf_counter()
        rows = 0
        cur = con.execute(sql, params)
        while True:
            batch = cur.fetch_record_batch(65536)
            # fetch_record_batch returns a RecordBatchReader in current DuckDB Python
            for rb in batch:
                rows += rb.num_rows
            break
        ms = (time.perf_counter() - t) * 1000
        rss, peak = rss_bytes()
        print(
            f"duckdb,{args.layout},{i},{q['name']},{rows},{ms:.6f},{rss},{rss - base_rss},{peak}",
            flush=True,
        )


if __name__ == "__main__":
    main()
