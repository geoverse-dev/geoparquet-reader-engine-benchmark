package io.geoverse.bench;

import static org.apache.parquet.filter2.predicate.FilterApi.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.MessageType;

public final class ParquetJavaR2Validation {
    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("BUCKET OBJECT WORKLOAD ENDPOINT REGION LIMIT");
        Configuration configuration = ParquetJavaR2ComparableBench.s3aConfiguration(args[3], args[4]);
        InputFile input = ParquetJavaR2ComparableBench.inputFile(configuration, args[0], args[1]);
        MessageType projection = ParquetJavaR2ComparableBench.projection(input);
        List<String> lines = Files.readAllLines(Path.of(args[2]));
        int limit = Integer.parseInt(args[5]);
        int done = 0;
        for (int i = 1; i < lines.size() && (limit == 0 || done < limit); i++) {
            String[] p = lines.get(i).split(",");
            if (p.length < 5) continue;
            FilterPredicate predicate = and(and(ltEq(floatColumn("bbox.xmin"), Float.parseFloat(p[3])), gtEq(floatColumn("bbox.xmax"), Float.parseFloat(p[1]))), and(ltEq(floatColumn("bbox.ymin"), Float.parseFloat(p[4])), gtEq(floatColumn("bbox.ymax"), Float.parseFloat(p[2]))));
            System.out.printf("%s\t2\t%n", p[0]);
            ParquetReader.Builder<Group> builder = new ParquetReader.Builder<>(input) { protected ReadSupport<Group> getReadSupport() { return new GroupReadSupport(); } };
            try (ParquetReader<Group> reader = builder.withFilter(FilterCompat.get(predicate)).set(ReadSupport.PARQUET_READ_SCHEMA, ParquetJavaR2ComparableBench.readSchema(projection)).build()) {
                Group group;
                while ((group = reader.read()) != null) {
                    boolean isNull = group.getFieldRepetitionCount("id") == 0;
                    System.out.printf("%s\t%d\t%s%n", p[0], isNull ? 1 : 0, isNull ? "" : group.getString("id", 0));
                }
            }
            done++;
        }
    }
}
