package io.geoverse.bench;

import static io.tileverse.parquetry.filter.Pred.col;

import io.tileverse.parquetry.data.ReadOptions;
import io.tileverse.parquetry.dataset.ParquetSource;
import io.tileverse.parquetry.filter.Bbox;
import io.tileverse.parquetry.filter.Predicate;
import io.tileverse.parquetry.filter.Projection;
import io.tileverse.parquetry.io.ByteRangeSource;
import io.tileverse.parquetry.record.ParquetRecord;
import io.tileverse.parquetry.schema.ColumnPath;
import io.tileverse.parquetry.tileverse.ByteRangeSources;

import io.tileverse.storage.RangeReader;
import io.tileverse.storage.Storage;
import io.tileverse.storage.cache.CachingRangeReader;
import io.tileverse.storage.s3.S3StorageProvider;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Compares Parquetry with and without Tileverse CachingRangeReader.
 *
 * Reader stack in cache mode:
 *
 * Parquetry -> ByteRangeSource -> CachingRangeReader
 *           -> CountingRangeReader -> S3 RangeReader -> R2
 *
 * Therefore range_calls/range_bytes_* count only cache misses that actually
 * pass through the cache toward the R2-backed RangeReader.
 *
 * Two rounds are executed over the same geographically-separated workload.
 * The same ParquetSource and cache live for the whole run.
 */
public final class ParquetryR2TileverseCacheBench {

    static final class CountingRangeReader implements RangeReader {
        private final RangeReader delegate;
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong requested = new AtomicLong();
        private final AtomicLong returned = new AtomicLong();

        CountingRangeReader(RangeReader delegate) {
            this.delegate = delegate;
        }

        @Override
        public ByteBuffer readRange(long offset, int length) {
            calls.incrementAndGet();
            requested.addAndGet(length);
            ByteBuffer b = delegate.readRange(offset, length);
            returned.addAndGet(b.remaining());
            return b;
        }

        @Override
        public int readRange(long offset, int length, ByteBuffer target) {
            calls.incrementAndGet();
            requested.addAndGet(length);
            int n = delegate.readRange(offset, length, target);
            if (n > 0) returned.addAndGet(n);
            return n;
        }

        @Override
        public OptionalLong size() {
            return delegate.size();
        }

        @Override
        public String getSourceIdentifier() {
            return delegate.getSourceIdentifier();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        void reset() {
            calls.set(0);
            requested.set(0);
            returned.set(0);
        }

        long calls() { return calls.get(); }
        long requestedBytes() { return requested.get(); }
        long returnedBytes() { return returned.get(); }
    }

    record Query(String name, double minx, double miny, double maxx, double maxy) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            System.err.println(
                "usage: ParquetryR2TileverseCacheBench " +
                "BUCKET OBJECT WORKLOAD ENDPOINT REGION MODE ROUNDS\n" +
                "MODE: nocache|cache"
            );
            System.exit(2);
        }

        String bucket = args[0];
        String object = args[1];
        Path workloadPath = Path.of(args[2]);
        URI endpoint = URI.create(args[3]);
        String region = args[4];
        String mode = args[5];
        int rounds = Integer.parseInt(args[6]);

        if (!mode.equals("nocache") && !mode.equals("cache")) {
            throw new IllegalArgumentException("MODE must be nocache or cache");
        }

        List<Query> workload = Files.readAllLines(workloadPath).stream()
            .skip(1)
            .filter(s -> !s.isBlank())
            .map(line -> {
                String[] x = line.split(",");
                return new Query(
                    x[0],
                    Double.parseDouble(x[1]),
                    Double.parseDouble(x[2]),
                    Double.parseDouble(x[3]),
                    Double.parseDouble(x[4])
                );
            })
            .toList();

        String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        if (accessKey == null || secretKey == null) {
            throw new IllegalStateException(
                "AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY are required"
            );
        }

        try (
            S3AsyncClient client = S3AsyncClient.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                    )
                )
                .forcePathStyle(true)
                .build();

            Storage storage = S3StorageProvider.open(
                URI.create("s3://" + bucket + "/"),
                client
            )
        ) {
            CountingRangeReader physical =
                new CountingRangeReader(storage.openRangeReader(object));

            /*
             * IMPORTANT:
             * The counter is BELOW the cache. Thus a cache hit never reaches
             * CountingRangeReader and is reported as zero remote logical I/O.
             *
             * Current Tileverse CachingRangeReader uses the default shared
             * CacheManager. In the current Tileverse implementation that
             * RangeReader cache is Caffeine-backed, bounded to 20% of heap,
             * and expires entries after 60 seconds of inactivity.
             */
            RangeReader effective =
                mode.equals("cache")
                    ? CachingRangeReader.builder(physical).build()
                    : physical;

            try (effective) {
                ByteRangeSource byteSource = ByteRangeSources.from(effective);
                try (byteSource) {
                    ParquetSource dataset = ParquetSource.open(byteSource);

                    Projection projection = Projection.ofPhysical(
                        List.of(
                            ColumnPath.of("id"),
                            ColumnPath.of("geometry"),
                            ColumnPath.of("bbox")
                        )
                    );

                    System.out.println(
                        "mode,round,position,query,rows,elapsed_ms," +
                        "remote_range_calls,remote_bytes_requested," +
                        "remote_bytes_returned"
                    );

                    for (int round = 1; round <= rounds; round++) {
                        int position = 0;
                        for (Query q : workload) {
                            position++;

                            Predicate predicate =
                                col("geometry").bboxIntersects(
                                    Bbox.of2d(
                                        q.minx(), q.miny(),
                                        q.maxx(), q.maxy()
                                    )
                                );

                            physical.reset();

                            long start = System.nanoTime();
                            long rows;
                            try (
                                Stream<ParquetRecord> stream =
                                    dataset.read(
                                        predicate,
                                        projection,
                                        ReadOptions.DEFAULTS
                                    )
                            ) {
                                rows = stream.count();
                            }

                            double elapsedMs =
                                (System.nanoTime() - start) / 1_000_000.0;

                            System.out.printf(
                                Locale.ROOT,
                                "%s,%d,%d,%s,%d,%.6f,%d,%d,%d%n",
                                mode,
                                round,
                                position,
                                q.name(),
                                rows,
                                elapsedMs,
                                physical.calls(),
                                physical.requestedBytes(),
                                physical.returnedBytes()
                            );
                        }
                    }
                }
            }
        }
    }
}
