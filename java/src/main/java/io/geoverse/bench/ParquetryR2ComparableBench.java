package io.geoverse.bench;

import io.tileverse.parquetry.data.ReadOptions;
import io.tileverse.parquetry.dataset.ParquetSource;
import io.tileverse.parquetry.filter.Predicate;
import io.tileverse.parquetry.filter.Projection;
import io.tileverse.parquetry.filter.Value;
import io.tileverse.parquetry.io.ByteRangeSource;
import io.tileverse.parquetry.record.ParquetRecord;
import io.tileverse.parquetry.schema.ColumnPath;
import io.tileverse.storage.RangeReader;
import io.tileverse.storage.Storage;
import io.tileverse.storage.cache.CachingRangeReader;
import io.tileverse.storage.s3.S3StorageProvider;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

public final class ParquetryR2ComparableBench {
    private static final ColumnPath ID = ColumnPath.of("id");
    private static final ColumnPath GEOMETRY = ColumnPath.of("geometry");
    private static final ColumnPath XMIN = ColumnPath.of("bbox", "xmin");
    private static final ColumnPath YMIN = ColumnPath.of("bbox", "ymin");
    private static final ColumnPath XMAX = ColumnPath.of("bbox", "xmax");
    private static final ColumnPath YMAX = ColumnPath.of("bbox", "ymax");
    record QueryResult(long rows, String checksum) {}

    private static final class CountingRangeReader implements RangeReader {
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
            ByteBuffer buffer = delegate.readRange(offset, length);
            returned.addAndGet(buffer.remaining());
            return buffer;
        }

        @Override
        public int readRange(long offset, int length, ByteBuffer target) {
            calls.incrementAndGet();
            requested.addAndGet(length);
            int count = delegate.readRange(offset, length, target);
            if (count > 0) {
                returned.addAndGet(count);
            }
            return count;
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

        long calls() {
            return calls.get();
        }

        long requestedBytes() {
            return requested.get();
        }

        long returnedBytes() {
            return returned.get();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 7 || args.length > 9) {
            throw new IllegalArgumentException(
                "BUCKET OBJECT WORKLOAD WARMUPS ENDPOINT REGION LIMIT [CACHE_MODE:on|off] [OPERATION]"
            );
        }

        String bucket = args[0];
        String object = args[1];
        Path workload = Path.of(args[2]);
        int warmups = Integer.parseInt(args[3]);
        URI endpoint = URI.create(args[4]);
        String region = args[5];
        int limit = Integer.parseInt(args[6]);
        String cacheMode = args.length == 8 ? args[7] : "on";
        if (args.length >= 8) cacheMode = args[7];
        String operation = args.length == 9 ? args[8] : "materialize";
        if (!operation.equals("scan") && !operation.equals("materialize")) throw new IllegalArgumentException("OPERATION must be scan or materialize");

        if (!cacheMode.equals("on") && !cacheMode.equals("off")) {
            throw new IllegalArgumentException("CACHE_MODE must be on or off");
        }

        long start = System.nanoTime();
        try (
            S3AsyncClient client = S3AsyncClient.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
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
                URI.create("s3://" + bucket + "/"),
                client
            )
        ) {
            CountingRangeReader physical = new CountingRangeReader(
                storage.openRangeReader(object)
            );
            RangeReader effective = cacheMode.equals("on")
                ? CachingRangeReader.builder(physical).build()
                : physical;

            // One effective reader, byte source, and ParquetSource live for every
            // warmup and measured query in this dataset session. The counters sit
            // below the optional cache and therefore measure only delegate reads.
            try (
                effective;
                ByteRangeSource bytes = ByteRangeSource.of(effective)
            ) {
                ParquetSource dataset = ParquetSource.open(bytes);
                System.err.printf(
                    Locale.ROOT,
                    "# init_ms=%.3f cache_mode=%s%n",
                    (System.nanoTime() - start) / 1e6,
                    cacheMode
                );
                List<String> lines = Files.readAllLines(workload);
                for (int i = 0; i < warmups; i++) {
                    run(dataset, lines, false, limit, physical, operation);
                }
                run(dataset, lines, true, limit, physical, operation);
            }
        }
    }

    private static void run(
        ParquetSource dataset,
        List<String> lines,
        boolean emit,
        int limit,
        CountingRangeReader physical,
        String operation
    ) throws Exception {
        Projection projection = Projection.ofPhysical(
            operation.equals("scan") ? List.of(ID) : List.of(
                ColumnPath.of("id"),
                ColumnPath.of("geometry"),
                ColumnPath.of("bbox")
            )
        );
        int done = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (limit > 0 && done >= limit) {
                break;
            }
            String[] parts = lines.get(i).split(",");
            if (parts.length < 5) {
                continue;
            }
            Predicate predicate = new Predicate.And(List.of(
                new Predicate.LtEq(XMIN, new Value.FloatVal(Float.parseFloat(parts[3]))),
                new Predicate.GtEq(XMAX, new Value.FloatVal(Float.parseFloat(parts[1]))),
                new Predicate.LtEq(YMIN, new Value.FloatVal(Float.parseFloat(parts[4]))),
                new Predicate.GtEq(YMAX, new Value.FloatVal(Float.parseFloat(parts[2])))));
            physical.reset();
            long queryStart = System.nanoTime();
            long rows = 0;
            MaterializedChecksum checksum = new MaterializedChecksum();
            try (
                Stream<ParquetRecord> stream = dataset.read(
                    predicate,
                    projection,
                    ReadOptions.DEFAULTS
                )
            ) {
                for (var iterator = stream.iterator(); iterator.hasNext();) {
                    ParquetRecord record = iterator.next(); rows++;
                    if (operation.equals("materialize")) checksum.add(
                        record.getString(ID), record.getBinary(GEOMETRY),
                        record.isNull(XMIN) ? null : record.getFloat(XMIN),
                        record.isNull(YMIN) ? null : record.getFloat(YMIN),
                        record.isNull(XMAX) ? null : record.getFloat(XMAX),
                        record.isNull(YMAX) ? null : record.getFloat(YMAX));
                }
            }
            double elapsedMs = (System.nanoTime() - queryStart) / 1e6;
            if (emit) {
                System.out.printf(
                    Locale.ROOT,
                    "%s,%d,%.6f,%s,%d,%d,%d%n",
                    parts[0],
                    rows,
                    elapsedMs,
                    operation.equals("materialize") ? checksum.finish() : "not_applicable",
                    physical.calls(),
                    physical.requestedBytes(),
                    physical.returnedBytes()
                );
            }
            done++;
        }
    }
}
