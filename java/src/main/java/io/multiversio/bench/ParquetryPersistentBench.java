package io.multiversio.bench;

import static io.tileverse.parquetry.filter.Pred.col;

import io.tileverse.parquetry.catalog.CatalogOptions;
import io.tileverse.parquetry.catalog.FilesetCatalog;
import io.tileverse.parquetry.data.ReadOptions;
import io.tileverse.parquetry.dataset.ParquetDataset;
import io.tileverse.parquetry.filter.Bbox;
import io.tileverse.parquetry.filter.Predicate;
import io.tileverse.parquetry.io.LocalFileSource;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class ParquetryPersistentBench {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: FILE WORKLOAD WARMUPS");
            System.exit(2);
        }
        Path file = Path.of(args[0]).toAbsolutePath();
        Path workload = Path.of(args[1]).toAbsolutePath();
        int warmups = Integer.parseInt(args[2]);

        try (FilesetCatalog catalog = FilesetCatalog.open(LocalFileSource.file(file), CatalogOptions.defaults())) {
            ParquetDataset dataset = catalog.dataset(catalog.datasets().get(0));
            for (int w = 0; w < warmups; w++) runWorkload(dataset, workload, false, w);
            runWorkload(dataset, workload, true, 0);
        }
    }

    private static void runWorkload(ParquetDataset dataset, Path workload, boolean emit, int pass) throws Exception {
        try (BufferedReader br = Files.newBufferedReader(workload)) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                if (line.isBlank()) continue;
                String[] p = line.split(",");
                String name = p[0];
                double minx = Double.parseDouble(p[1]);
                double miny = Double.parseDouble(p[2]);
                double maxx = Double.parseDouble(p[3]);
                double maxy = Double.parseDouble(p[4]);
                Predicate predicate = col("geometry").bboxIntersects(Bbox.of2d(minx, miny, maxx, maxy));
                long t0 = System.nanoTime();
                long rows = dataset.count(predicate, ReadOptions.DEFAULTS);
                double elapsedMs = (System.nanoTime() - t0) / 1_000_000.0;
                if (emit) {
                    System.out.printf(Locale.ROOT, "%s,%d,%.6f%n", name, rows, elapsedMs);
                }
            }
        }
    }
}
