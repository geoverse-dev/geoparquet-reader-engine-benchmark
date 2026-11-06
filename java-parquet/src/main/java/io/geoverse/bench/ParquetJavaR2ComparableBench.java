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
    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException(
                "BUCKET OBJECT WORKLOAD WARMUPS ENDPOINT REGION LIMIT"
            );
        }
        Path workload = Path.of(args[2]);
        int warmups = Integer.parseInt(args[3]);
        int limit = Integer.parseInt(args[6]);
        long start = System.nanoTime();
        Configuration configuration = s3aConfiguration(args[4], args[5]);
        InputFile input = inputFile(configuration, args[0], args[1]);
        MessageType projection = projection(input);
        List<String> lines = Files.readAllLines(workload);
        System.err.printf(
            Locale.ROOT,
            "# init_ms=%.3f transport=hadoop_s3a cache=s3a_default%n",
            (System.nanoTime() - start) / 1e6
        );
        for (int index = 0; index < warmups; index++) {
            run(input, projection, lines, false, limit);
        }
        run(input, projection, lines, true, limit);
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
        MessageType full;
        try (ParquetFileReader reader = ParquetFileReader.open(input)) {
            full = reader.getFileMetaData().getSchema();
        }
        Set<String> wanted = new HashSet<>(List.of("id", "geometry", "bbox"));
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
                .set(ReadSupport.PARQUET_READ_SCHEMA, projection.toString())
                .build()
        ) {
            while (reader.read() != null) {
                rows++;
            }
        }
        return rows;
    }

    private static void run(
        InputFile input,
        MessageType projection,
        List<String> lines,
        boolean emit,
        int limit
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
            long rows = count(
                input,
                projection,
                Double.parseDouble(fields[1]),
                Double.parseDouble(fields[2]),
                Double.parseDouble(fields[3]),
                Double.parseDouble(fields[4])
            );
            if (emit) {
                System.out.printf(
                    Locale.ROOT,
                    "%s,%d,%.6f,,,%n",
                    fields[0],
                    rows,
                    (System.nanoTime() - start) / 1e6
                );
            }
            done++;
        }
    }
}
