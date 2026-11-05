package io.multiversio.bench;

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

public final class ParquetryR2IoDiag {

    /**
     * Decorates the Tileverse RangeReader so we can observe the logical
     * range reads requested by Parquetry.
     *
     * Note:
     * range_calls != necessarily physical HTTP requests, since a lower
     * storage layer may cache/coalesce requests.
     */
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

            ByteBuffer buffer = delegate.readRange(offset, length);

            returned.addAndGet(buffer.remaining());

            return buffer;
        }

        @Override
        public int readRange(long offset, int length, ByteBuffer target) {
            calls.incrementAndGet();
            requested.addAndGet(length);

            int n = delegate.readRange(offset, length, target);

            if (n > 0) {
                returned.addAndGet(n);
            }

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

        if (args.length != 5) {
            System.err.println(
                    "usage: ParquetryR2IoDiag " +
                    "BUCKET OBJECT WORKLOAD ENDPOINT REGION"
            );
            System.exit(2);
        }

        String bucket = args[0];
        String object = args[1];
        Path workload = Path.of(args[2]);
        URI endpoint = URI.create(args[3]);
        String region = args[4];

        /*
         * Diagnostic intentionally uses only the first BBOX
         * in workload-persistent.csv (small-01).
         */
        List<String> workloadLines = Files.readAllLines(workload);

        if (workloadLines.size() < 2) {
            throw new IllegalArgumentException(
                    "Workload does not contain any queries: " + workload
            );
        }

        String[] parts = workloadLines.get(1).split(",");

        if (parts.length < 5) {
            throw new IllegalArgumentException(
                    "Invalid workload row: " + workloadLines.get(1)
            );
        }

        String queryName = parts[0];

        double minx = Double.parseDouble(parts[1]);
        double miny = Double.parseDouble(parts[2]);
        double maxx = Double.parseDouble(parts[3]);
        double maxy = Double.parseDouble(parts[4]);

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
                                        AwsBasicCredentials.create(
                                                accessKey,
                                                secretKey
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

            CountingRangeReader countingReader =
                    new CountingRangeReader(
                            storage.openRangeReader(object)
                    );

            try (
                    countingReader;
                    ByteRangeSource byteRangeSource =
                            ByteRangeSources.from(countingReader)
            ) {

                /*
                 * Opening the source may read footer/metadata.
                 *
                 * We intentionally exclude that from the query metrics,
                 * because in the persistent GeoServer-like scenario the
                 * dataset would already be open.
                 */
                ParquetSource dataset =
                        ParquetSource.open(byteRangeSource);

                countingReader.reset();

                /*
                 * Same logical projection as the DuckDB diagnostic:
                 *
                 *   id
                 *   geometry
                 *   bbox
                 */
                Projection projection =
                        Projection.ofPhysical(
                                List.of(
                                        ColumnPath.of("id"),
                                        ColumnPath.of("geometry"),
                                        ColumnPath.of("bbox")
                                )
                        );

                Predicate predicate =
                        col("geometry").bboxIntersects(
                                Bbox.of2d(
                                        minx,
                                        miny,
                                        maxx,
                                        maxy
                                )
                        );

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
                    /*
                     * count() fully consumes the stream.
                     *
                     * Since projection contains id + geometry + bbox,
                     * Parquetry must actually read/materialize those
                     * projected values while iterating the records.
                     */
                    rows = stream.count();
                }

                double elapsedMs =
                        (System.nanoTime() - start) / 1_000_000.0;

                System.out.println(
                        "reader,query,rows,elapsed_ms," +
                        "range_calls," +
                        "range_bytes_requested," +
                        "range_bytes_returned"
                );

                System.out.printf(
                        Locale.ROOT,
                        "parquetry,%s,%d,%.6f,%d,%d,%d%n",
                        queryName,
                        rows,
                        elapsedMs,
                        countingReader.calls(),
                        countingReader.requestedBytes(),
                        countingReader.returnedBytes()
                );
            }
        }
    }
}
