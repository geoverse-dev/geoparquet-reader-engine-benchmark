
package io.geoverse.bench;

import static io.tileverse.parquetry.filter.Pred.col;

import io.tileverse.parquetry.data.ReadOptions;
import io.tileverse.parquetry.dataset.ParquetSource;
import io.tileverse.parquetry.filter.*;
import io.tileverse.parquetry.io.ByteRangeSource;
import io.tileverse.parquetry.record.ParquetRecord;
import io.tileverse.parquetry.schema.ColumnPath;
import io.tileverse.parquetry.tileverse.ByteRangeSources;

import io.tileverse.storage.RangeReader;
import io.tileverse.storage.Storage;
import io.tileverse.storage.cache.CachingRangeReader;
import io.tileverse.storage.s3.S3StorageProvider;

import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

/**
 * Concurrent Parquetry benchmark with Tileverse caching.
 *
 * Shared across workers:
 * - S3AsyncClient
 * - Storage
 * - CachingRangeReader
 * - ParquetSource
 *
 * Reader stack:
 * Parquetry -> ByteRangeSource -> CachingRangeReader
 *           -> S3 RangeReader -> Cloudflare R2
 *
 * Reader thread safety must be validated under concurrent load.
 */
public final class ParquetryR2ConcurrentBench {

    record Window(
        String name,
        double minx,
        double miny,
        double maxx,
        double maxy
    ) {}

    record Result(long rows, double ms) {}

    static long rss() {
        try {
            for (String s : Files.readAllLines(
                    Path.of("/proc/self/status"))) {
                if (s.startsWith("VmRSS:")) {
                    return Long.parseLong(
                        s.trim().split("\\s+")[1]
                    ) * 1024;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    static long count(
        ParquetSource ds,
        Window w,
        Projection projection
    ) throws Exception {

        Predicate predicate = col("geometry").bboxIntersects(
            Bbox.of2d(w.minx, w.miny, w.maxx, w.maxy)
        );

        try (Stream<ParquetRecord> stream =
                 ds.read(predicate, projection, ReadOptions.DEFAULTS)) {
            return stream.count();
        }
    }

    static double pct(List<Double> sorted, double p) {
        return sorted.get(
            (int) Math.round((sorted.size() - 1) * p)
        );
    }

    public static void main(String[] a) throws Exception {

        if (a.length != 9) {
            throw new IllegalArgumentException(
                "Expected bucket object workload endpoint region " +
                "concurrency queries warmup scenario"
            );
        }

        int concurrency = Integer.parseInt(a[5]);
        int queries = Integer.parseInt(a[6]);
        int warmup = Integer.parseInt(a[7]);
        String scenario = a[8];

        if (concurrency < 1 || queries < 1) {
            throw new IllegalArgumentException(
                "Invalid concurrency or query count"
            );
        }

        List<Window> windows = new ArrayList<>();
        List<String> lines = Files.readAllLines(Path.of(a[2]));

        for (int i = 1; i < lines.size(); i++) {
            String[] s = lines.get(i).split(",");

            if (s.length >= 5) {
                windows.add(new Window(
                    s[0],
                    Double.parseDouble(s[1]),
                    Double.parseDouble(s[2]),
                    Double.parseDouble(s[3]),
                    Double.parseDouble(s[4])
                ));
            }
        }

        if (windows.isEmpty()) {
            throw new IllegalArgumentException("Empty workload");
        }

        try (
            S3AsyncClient client = S3AsyncClient.builder()
                .endpointOverride(URI.create(a[3]))
                .region(Region.of(a[4]))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                            System.getenv("AWS_ACCESS_KEY_ID"),
                            System.getenv("AWS_SECRET_ACCESS_KEY")
                        )
                    )
                )
                .forcePathStyle(true)
                .build();

            Storage storage = S3StorageProvider.open(
                URI.create("s3://" + a[0] + "/"),
                client
            );

            RangeReader physical = storage.openRangeReader(a[1]);

            RangeReader cached = CachingRangeReader
                .builder(physical)
                .build();

            ByteRangeSource bytes = ByteRangeSources.from(cached)
        ) {

            // One ParquetSource shared by all workers.
            ParquetSource ds = ParquetSource.open(bytes);

            Projection projection = Projection.ofPhysical(
                List.of(
                    ColumnPath.of("id"),
                    ColumnPath.of("geometry"),
                    ColumnPath.of("bbox")
                )
            );

            int hot = Math.min(6, windows.size());

            // Preload the same windows used by the warm scenario.
            if (!scenario.equals("cold")) {
                for (int j = 0; j < warmup; j++) {
                    for (int i = 0; i < hot; i++) {
                        count(ds, windows.get(i), projection);
                    }
                }
            }

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicLong peak = new AtomicLong(rss());

            Thread sampler = new Thread(() -> {
                while (running.get()) {
                    peak.accumulateAndGet(rss(), Math::max);

                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "rss-sampler");

            sampler.setDaemon(true);
            sampler.start();

            ExecutorService pool =
                Executors.newFixedThreadPool(concurrency);

            List<Future<Result>> futures = new ArrayList<>();
            long start = System.nanoTime();

            try {
                for (int i = 0; i < queries; i++) {

                    Window w = windows.get(
                        i % (scenario.equals("warm")
                            ? hot
                            : windows.size())
                    );

                    futures.add(pool.submit(() -> {
                        long q = System.nanoTime();
                        long rows = count(ds, w, projection);

                        return new Result(
                            rows,
                            (System.nanoTime() - q) / 1e6
                        );
                    }));
                }

                List<Double> lat = new ArrayList<>();
                long rows = 0;

                for (Future<Result> future : futures) {
                    Result result = future.get();
                    lat.add(result.ms);
                    rows += result.rows;
                }

                double wall = (System.nanoTime() - start) / 1e6;
                Collections.sort(lat);

                System.out.println(
                    "engine,scenario,concurrency,queries,wall_ms,qps," +
                    "p50_ms,p95_ms,p99_ms,peak_rss_mb,total_rows"
                );

                System.out.printf(
                    Locale.ROOT,
                    "parquetry,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%d%n",
                    scenario,
                    concurrency,
                    queries,
                    wall,
                    queries * 1000 / wall,
                    pct(lat, .5),
                    pct(lat, .95),
                    pct(lat, .99),
                    peak.get() / 1048576.0,
                    rows
                );

            } finally {
                pool.shutdownNow();
                running.set(false);
                sampler.join(1000);
            }
        }
    }
}
