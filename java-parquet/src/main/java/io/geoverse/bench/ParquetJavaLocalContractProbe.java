package io.geoverse.bench;

import static org.apache.parquet.filter2.predicate.FilterApi.and;
import static org.apache.parquet.filter2.predicate.FilterApi.floatColumn;
import static org.apache.parquet.filter2.predicate.FilterApi.gtEq;
import static org.apache.parquet.filter2.predicate.FilterApi.ltEq;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.MessageType;

/** Local bbox-v1 conformance probe; emits one encoded ID per matching row. */
public final class ParquetJavaLocalContractProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("FILE MINX MINY MAXX MAXY");
        }
        float minx = Float.parseFloat(args[1]);
        float miny = Float.parseFloat(args[2]);
        float maxx = Float.parseFloat(args[3]);
        float maxy = Float.parseFloat(args[4]);
        FilterPredicate predicate = and(
            and(
                ltEq(floatColumn("bbox.xmin"), maxx),
                gtEq(floatColumn("bbox.xmax"), minx)
            ),
            and(
                ltEq(floatColumn("bbox.ymin"), maxy),
                gtEq(floatColumn("bbox.ymax"), miny)
            )
        );
        InputFile input = new LocalInputFile(Path.of(args[0]).toAbsolutePath());
        MessageType projection = ParquetJavaR2ComparableBench.projection(input);
        ParquetReader.Builder<Group> builder = new ParquetReader.Builder<>(input) {
            @Override
            protected ReadSupport<Group> getReadSupport() {
                return new GroupReadSupport();
            }
        };
        try (
            ParquetReader<Group> reader = builder
                .withFilter(FilterCompat.get(predicate))
                .set(
                    ReadSupport.PARQUET_READ_SCHEMA,
                    ParquetJavaR2ComparableBench.readSchema(projection)
                )
                .build()
        ) {
            Group group;
            while ((group = reader.read()) != null) {
                emitId(group);
            }
        }
    }

    private static void emitId(Group group) {
        if (group.getFieldRepetitionCount("id") == 0) {
            System.out.println("N");
        } else {
            String id = group.getString("id", 0);
            String encoded = Base64.getEncoder().encodeToString(
                id.getBytes(StandardCharsets.UTF_8)
            );
            System.out.println("S" + encoded);
        }
    }
}
