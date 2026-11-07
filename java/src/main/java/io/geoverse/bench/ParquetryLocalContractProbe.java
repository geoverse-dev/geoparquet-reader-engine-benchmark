package io.geoverse.bench;

import io.tileverse.parquetry.catalog.CatalogOptions;
import io.tileverse.parquetry.catalog.FilesetCatalog;
import io.tileverse.parquetry.data.ReadOptions;
import io.tileverse.parquetry.dataset.ParquetDataset;
import io.tileverse.parquetry.filter.Predicate;
import io.tileverse.parquetry.filter.Projection;
import io.tileverse.parquetry.filter.Value;
import io.tileverse.parquetry.io.FileSource;
import io.tileverse.parquetry.record.ParquetRecord;
import io.tileverse.parquetry.schema.ColumnPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

/** Local bbox-v1 conformance probe; emits one encoded ID per matching row. */
public final class ParquetryLocalContractProbe {
    private static final ColumnPath ID = ColumnPath.of("id");
    private static final ColumnPath XMIN = ColumnPath.of("bbox", "xmin");
    private static final ColumnPath YMIN = ColumnPath.of("bbox", "ymin");
    private static final ColumnPath XMAX = ColumnPath.of("bbox", "xmax");
    private static final ColumnPath YMAX = ColumnPath.of("bbox", "ymax");

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("FILE MINX MINY MAXX MAXY");
        }
        float minx = Float.parseFloat(args[1]);
        float miny = Float.parseFloat(args[2]);
        float maxx = Float.parseFloat(args[3]);
        float maxy = Float.parseFloat(args[4]);
        Predicate predicate = new Predicate.And(
            List.of(
                new Predicate.LtEq(XMIN, new Value.FloatVal(maxx)),
                new Predicate.GtEq(XMAX, new Value.FloatVal(minx)),
                new Predicate.LtEq(YMIN, new Value.FloatVal(maxy)),
                new Predicate.GtEq(YMAX, new Value.FloatVal(miny))
            )
        );
        Projection projection = Projection.ofPhysical(List.of(ID));
        try (
            FilesetCatalog catalog = FilesetCatalog.open(
                FileSource.file(Path.of(args[0]).toAbsolutePath()),
                CatalogOptions.defaults()
            )
        ) {
            ParquetDataset dataset = catalog.dataset(catalog.datasets().get(0));
            try (
                Stream<ParquetRecord> records = dataset.read(
                    predicate,
                    projection,
                    ReadOptions.DEFAULTS
                )
            ) {
                records.forEach(ParquetryLocalContractProbe::emitId);
            }
        }
    }

    private static void emitId(ParquetRecord record) {
        String id = record.getString(ID);
        if (id == null) {
            System.out.println("N");
        } else {
            String encoded = Base64.getEncoder().encodeToString(
                id.getBytes(StandardCharsets.UTF_8)
            );
            System.out.println("S" + encoded);
        }
    }
}
