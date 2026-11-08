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
import io.tileverse.storage.s3.S3StorageProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

public final class ParquetryR2Validation {
    private static final ColumnPath ID = ColumnPath.of("id");
    private static final ColumnPath XMIN = ColumnPath.of("bbox", "xmin");
    private static final ColumnPath YMIN = ColumnPath.of("bbox", "ymin");
    private static final ColumnPath XMAX = ColumnPath.of("bbox", "xmax");
    private static final ColumnPath YMAX = ColumnPath.of("bbox", "ymax");

    public static void main(String[] args) throws Exception {
        if (args.length != 7) throw new IllegalArgumentException("BUCKET OBJECT WORKLOAD ENDPOINT REGION LIMIT CACHE");
        try (S3AsyncClient client = S3AsyncClient.builder().endpointOverride(URI.create(args[3]))
                .region(Region.of(args[4])).credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(System.getenv("AWS_ACCESS_KEY_ID"), System.getenv("AWS_SECRET_ACCESS_KEY"))))
                .forcePathStyle(true).build();
            Storage storage = S3StorageProvider.open(URI.create("s3://" + args[0] + "/"), client);
            RangeReader ranges = storage.openRangeReader(args[1]);
            ByteRangeSource bytes = ByteRangeSource.of(ranges)) {
            ParquetSource dataset = ParquetSource.open(bytes);
            List<String> lines = Files.readAllLines(Path.of(args[2]));
            int limit = Integer.parseInt(args[5]);
            int done = 0;
            for (int i = 1; i < lines.size() && (limit == 0 || done < limit); i++) {
                String[] p = lines.get(i).split(",");
                if (p.length < 5) continue;
                Predicate predicate = new Predicate.And(List.of(
                    new Predicate.LtEq(XMIN, new Value.FloatVal(Float.parseFloat(p[3]))),
                    new Predicate.GtEq(XMAX, new Value.FloatVal(Float.parseFloat(p[1]))),
                    new Predicate.LtEq(YMIN, new Value.FloatVal(Float.parseFloat(p[4]))),
                    new Predicate.GtEq(YMAX, new Value.FloatVal(Float.parseFloat(p[2])))));
                CanonicalIdSummary summary = new CanonicalIdSummary();
                try (Stream<ParquetRecord> records = dataset.read(predicate, Projection.ofPhysical(List.of(ID)), ReadOptions.DEFAULTS)) {
                    records.forEach(record -> summary.add(record.getString(ID)));
                }
                CanonicalIdSummary.Result result = summary.finish();
                System.out.printf("%s\t%d\t%s\t%d\t%d\t%n", p[0], result.rowCount(), result.hash(), result.nullCount(), result.duplicateCount());
                done++;
            }
        }
    }
}
