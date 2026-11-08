package io.geoverse.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.MessageType;

public final class ParquetJavaR2GeographicJumpBench {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                "BUCKET OBJECT WORKLOAD ENDPOINT REGION"
            );
        }
        List<String> lines = Files.readAllLines(Path.of(args[2]));
        Configuration configuration = ParquetJavaR2ComparableBench.s3aConfiguration(
            args[3],
            args[4]
        );
        InputFile input = ParquetJavaR2ComparableBench.inputFile(
            configuration,
            args[0],
            args[1]
        );
        MessageType projection = ParquetJavaR2ComparableBench.projection(input);
        System.out.println("position,query,rows,elapsed_ms,result_checksum");
        for (int index = 1; index < lines.size(); index++) {
            String[] fields = lines.get(index).split(",");
            long start = System.nanoTime();
            ParquetJavaR2ComparableBench.QueryResult result = ParquetJavaR2ComparableBench.consume(
                input,
                projection,
                Double.parseDouble(fields[1]),
                Double.parseDouble(fields[2]),
                Double.parseDouble(fields[3]),
                Double.parseDouble(fields[4]),
                "materialize"
            );
            System.out.printf(
                Locale.ROOT,
                "%d,%s,%d,%.6f,%s%n",
                index,
                fields[0],
                result.rows(),
                (System.nanoTime() - start) / 1e6,
                result.checksum()
            );
        }
    }
}
