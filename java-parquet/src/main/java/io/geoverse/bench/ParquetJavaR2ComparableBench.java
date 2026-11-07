package io.geoverse.bench;

import static org.apache.parquet.filter2.predicate.FilterApi.and;
import static org.apache.parquet.filter2.predicate.FilterApi.floatColumn;
import static org.apache.parquet.filter2.predicate.FilterApi.gtEq;
import static org.apache.parquet.filter2.predicate.FilterApi.ltEq;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;

/** Apache parquet-java using its conventional Hadoop S3A remote I/O path. */
public final class ParquetJavaR2ComparableBench {
    record QueryResult(long rows, String checksum) {}
    public static void main(String[] args) throws Exception {
        if (args.length < 7 || args.length > 8) {
            throw new IllegalArgumentException(
                "BUCKET OBJECT WORKLOAD WARMUPS ENDPOINT REGION LIMIT [OPERATION]"
            );
        }
        Path workload = Path.of(args[2]);
        int warmups = Integer.parseInt(args[3]);
        int limit = Integer.parseInt(args[6]);
        String operation = args.length == 8 ? args[7] : "materialize";
        if (!operation.equals("scan") && !operation.equals("materialize")) throw new IllegalArgumentException("OPERATION must be scan or materialize");
        long start = System.nanoTime();
        Configuration configuration = s3aConfiguration(args[4], args[5]);
        InputFile input = inputFile(configuration, args[0], args[1]);
        MessageType projection = projection(input, operation);
        List<String> lines = Files.readAllLines(workload);
        System.err.printf(
            Locale.ROOT,
            "# init_ms=%.3f transport=hadoop_s3a cache=s3a_default%n",
            (System.nanoTime() - start) / 1e6
        );
        for (int index = 0; index < warmups; index++) {
            run(input, projection, lines, false, limit, operation);
        }
        run(input, projection, lines, true, limit, operation);
    }

    static Configuration s3aConfiguration(String endpoint, String region) {
        URI uri = URI.create(endpoint);
        Configuration configuration = new Configuration(false);
        configuration.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
        configuration.set("fs.s3a.endpoint", uri.getHost());
        configuration.setBoolean(
            "fs.s3a.connection.ssl.enabled",
            "https".equalsIgnoreCase(uri.getScheme())
        );
        configuration.setBoolean("fs.s3a.path.style.access", true);
        configuration.set("fs.s3a.endpoint.region", region);
        configuration.set(
            "fs.s3a.aws.credentials.provider",
            "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
        );
        configuration.set("fs.s3a.access.key", System.getenv("AWS_ACCESS_KEY_ID"));
        configuration.set("fs.s3a.secret.key", System.getenv("AWS_SECRET_ACCESS_KEY"));
        return configuration;
    }

    static InputFile inputFile(
        Configuration configuration,
        String bucket,
        String object
    ) throws Exception {
        org.apache.hadoop.fs.Path path = new org.apache.hadoop.fs.Path(
            "s3a://" + bucket + "/" + object
        );
        return HadoopInputFile.fromPath(path, configuration);
    }

    static MessageType projection(InputFile input) throws Exception {
        return projection(input, "materialize");
    }

    static MessageType projection(InputFile input, String operation) throws Exception {
        MessageType full;
        try (ParquetFileReader reader = ParquetFileReader.open(input)) {
            full = reader.getFileMetaData().getSchema();
        }
        Set<String> wanted = new HashSet<>(operation.equals("scan") ? List.of("id", "bbox") : List.of("id", "geometry", "bbox"));
        List<Type> fields = full.getFields()
            .stream()
            .filter(field -> wanted.contains(field.getName()))
            .toList();
        return new MessageType(full.getName(), fields);
    }

    static long count(
        InputFile input,
        MessageType projection,
        double minX,
        double minY,
        double maxX,
        double maxY
    ) throws Exception {
        FilterPredicate predicate = and(
            and(
                ltEq(floatColumn("bbox.xmin"), (float) maxX),
                gtEq(floatColumn("bbox.xmax"), (float) minX)
            ),
            and(
                ltEq(floatColumn("bbox.ymin"), (float) maxY),
                gtEq(floatColumn("bbox.ymax"), (float) minY)
            )
        );
        long rows = 0;
        ParquetReader.Builder<Group> builder = new ParquetReader.Builder<>(input) {
            @Override
            protected ReadSupport<Group> getReadSupport() {
                return new GroupReadSupport();
            }
        };
        try (
            ParquetReader<Group> reader = builder
                .withFilter(FilterCompat.get(predicate))
                .set(ReadSupport.PARQUET_READ_SCHEMA, readSchema(projection))
                .build()
        ) {
            while (reader.read() != null) {
                rows++;
            }
        }
        return rows;
    }

    static QueryResult consume(InputFile input, MessageType projection, double minX, double minY, double maxX, double maxY, String operation) throws Exception {
        FilterPredicate predicate = and(and(ltEq(floatColumn("bbox.xmin"), (float) maxX), gtEq(floatColumn("bbox.xmax"), (float) minX)), and(ltEq(floatColumn("bbox.ymin"), (float) maxY), gtEq(floatColumn("bbox.ymax"), (float) minY)));
        long rows = 0; MaterializedChecksum checksum = new MaterializedChecksum();
        ParquetReader.Builder<Group> builder = new ParquetReader.Builder<>(input) { protected ReadSupport<Group> getReadSupport() { return new GroupReadSupport(); } };
        try (ParquetReader<Group> reader = builder.withFilter(FilterCompat.get(predicate)).set(ReadSupport.PARQUET_READ_SCHEMA, readSchema(projection)).build()) {
            Group group;
            while ((group = reader.read()) != null) {
                rows++;
                if (operation.equals("materialize")) {
                    String id = group.getFieldRepetitionCount("id") == 0 ? null : group.getString("id", 0);
                    byte[] geometry = group.getFieldRepetitionCount("geometry") == 0 ? null : group.getBinary("geometry", 0).getBytes();
                    Group bbox = group.getFieldRepetitionCount("bbox") == 0 ? null : group.getGroup("bbox", 0);
                    checksum.add(id, geometry, floatValue(bbox, "xmin"), floatValue(bbox, "ymin"), floatValue(bbox, "xmax"), floatValue(bbox, "ymax"));
                }
            }
        }
        return new QueryResult(rows, operation.equals("materialize") ? checksum.finish() : "not_applicable");
    }

    private static Float floatValue(Group bbox, String field) {
        return bbox == null || bbox.getFieldRepetitionCount(field) == 0 ? null : bbox.getFloat(field, 0);
    }

    /**
     * parquet-java 1.17's textual schema parser only accepts the original
     * one-argument GEOMETRY annotation. GeoParquet 2.0 stores the extended
     * six-argument form. The annotation is metadata, so remove it only from
     * the requested read schema while retaining the physical binary column.
     */
    static String readSchema(MessageType projection) {
        return projection.toString().replaceAll(
            "(?i)\\s+\\(GEOMETRY\\([^;\\r\\n]*\\)\\)(?=;)",
            ""
        );
    }

    private static void run(
        InputFile input,
        MessageType projection,
        List<String> lines,
        boolean emit,
        int limit,
        String operation
    ) throws Exception {
        int done = 0;
        for (int index = 1; index < lines.size(); index++) {
            if (limit > 0 && done >= limit) {
                break;
            }
            String[] fields = lines.get(index).split(",");
            if (fields.length < 5) {
                continue;
            }
            long start = System.nanoTime();
            QueryResult result = consume(
                input,
                projection,
                Double.parseDouble(fields[1]),
                Double.parseDouble(fields[2]),
                Double.parseDouble(fields[3]),
                Double.parseDouble(fields[4]),
                operation
            );
            if (emit) {
                System.out.printf(
                    Locale.ROOT,
                    "%s,%d,%.6f,%s,,,%n",
                    fields[0],
                    result.rows(),
                    (System.nanoTime() - start) / 1e6
                    ,result.checksum()
                );
            }
            done++;
        }
    }
}
