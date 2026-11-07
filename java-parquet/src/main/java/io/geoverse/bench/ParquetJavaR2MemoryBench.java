package io.geoverse.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.MessageType;

public final class ParquetJavaR2MemoryBench {
    record Query(String name, double minx, double miny, double maxx, double maxy) {}

    static long[] processMemory() throws Exception {
        long rss = 0;
        long highWater = 0;
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS:")) {
                rss = Long.parseLong(line.trim().split("\\s+")[1]) * 1024;
            }
            if (line.startsWith("VmHWM:")) {
                highWater = Long.parseLong(line.trim().split("\\s+")[1]) * 1024;
            }
        }
        return new long[] {rss, highWater};
    }

    static long heapUsed() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                "BUCKET OBJECT WORKLOAD ENDPOINT REGION LAYOUT"
            );
        }
        List<Query> queries = Files.readAllLines(Path.of(args[2]))
            .stream()
            .skip(1)
            .filter(line -> !line.isBlank())
            .map(line -> {
                String[] fields = line.split(",");
                return new Query(
                    fields[0],
                    Double.parseDouble(fields[1]),
                    Double.parseDouble(fields[2]),
                    Double.parseDouble(fields[3]),
                    Double.parseDouble(fields[4])
                );
            })
            .toList();
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
        long baseRss = processMemory()[0];
        System.out.println(
            "engine,layout,position,query,rows,elapsed_ms,rss_bytes," +
            "rss_delta_bytes,peak_rss_bytes,jvm_heap_used_bytes,jvm_heap_committed_bytes," +
            "jvm_non_heap_used_bytes,jvm_direct_buffer_bytes,gc_count,gc_pause_ms,sampling_interval_ms"
        );
        int position = 0;
        for (Query query : queries) {
            position++;
            long start = System.nanoTime();
            long rows = ParquetJavaR2ComparableBench.count(
                input,
                projection,
                query.minx(),
                query.miny(),
                query.maxx(),
                query.maxy()
            );
            long[] memory = processMemory();
            ResourceMetrics.Snapshot resources = ResourceMetrics.snapshot();
            System.out.printf(
                Locale.ROOT,
                "parquet-java-s3a,%s,%d,%s,%d,%.6f,%d,%d,%d,%d,%d,%d,%d,%d,%d,not_applicable%n",
                args[5],
                position,
                query.name(),
                rows,
                (System.nanoTime() - start) / 1e6,
                memory[0],
                memory[0] - baseRss,
                memory[1],
                resources.heapUsed(), resources.heapCommitted(), resources.nonHeapUsed(),
                resources.directBufferBytes(), resources.gcCount(), resources.gcPauseMs()
            );
        }
    }
}
