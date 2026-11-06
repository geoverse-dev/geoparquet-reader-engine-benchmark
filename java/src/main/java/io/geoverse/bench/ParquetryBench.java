package io.geoverse.bench;

import static io.tileverse.parquetry.filter.Pred.col;

import io.tileverse.parquetry.catalog.CatalogOptions;
import io.tileverse.parquetry.catalog.FilesetCatalog;
import io.tileverse.parquetry.data.ReadOptions;
import io.tileverse.parquetry.dataset.ParquetDataset;
import io.tileverse.parquetry.filter.Bbox;
import io.tileverse.parquetry.filter.Predicate;
import io.tileverse.parquetry.io.LocalFileSource;

import java.nio.file.Path;
import java.util.Locale;

public final class ParquetryBench {

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: FILE MINX MINY MAXX MAXY");
            System.exit(2);
        }

        Path file = Path.of(args[0]).toAbsolutePath();

        double minx = Double.parseDouble(args[1]);
        double miny = Double.parseDouble(args[2]);
        double maxx = Double.parseDouble(args[3]);
        double maxy = Double.parseDouble(args[4]);

        Predicate predicate =
                col("geometry")
                        .bboxIntersects(Bbox.of2d(minx, miny, maxx, maxy));

        long t0 = System.nanoTime();
        long rows;

        try (FilesetCatalog catalog =
                     FilesetCatalog.open(
                             LocalFileSource.file(file),
                             CatalogOptions.defaults())) {

            ParquetDataset dataset =
                    catalog.dataset(catalog.datasets().get(0));

            rows = dataset.count(
                    predicate,
                    ReadOptions.DEFAULTS);
        }

        double elapsedMs =
                (System.nanoTime() - t0) / 1_000_000.0;

        System.out.printf(
                Locale.ROOT,
                "{\"rows\":%d,\"elapsed_ms\":%.6f}%n",
                rows,
                elapsedMs);
    }
}
