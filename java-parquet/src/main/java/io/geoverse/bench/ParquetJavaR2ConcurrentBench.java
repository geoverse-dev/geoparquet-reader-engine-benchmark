package io.geoverse.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.MessageType;

public final class ParquetJavaR2ConcurrentBench {
    record Window(String name, double minx, double miny, double maxx, double maxy) {}
    record Result(long rows, double ms) {}

    static long rss() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.trim().split("\\s+")[1]) * 1024;
                }
            }
        } catch (Exception ignored) {
            // RSS is optional on non-Linux hosts.
        }
        return 0;
    }

    static double percentile(List<Double> sorted, double fraction) {
        return sorted.get((int) Math.round((sorted.size() - 1) * fraction));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 9) {
            throw new IllegalArgumentException(
                "Expected bucket object workload endpoint region concurrency queries warmup scenario"
            );
        }
        int concurrency = Integer.parseInt(args[5]);
        int queries = Integer.parseInt(args[6]);
        int warmup = Integer.parseInt(args[7]);
        String scenario = args[8];
        List<Window> windows = new ArrayList<>();
        List<String> lines = Files.readAllLines(Path.of(args[2]));
        for (int i = 1; i < lines.size(); i++) {
            String[] fields = lines.get(i).split(",");
            if (fields.length >= 5) {
                windows.add(
                    new Window(
                        fields[0],
                        Double.parseDouble(fields[1]),
                        Double.parseDouble(fields[2]),
                        Double.parseDouble(fields[3]),
                        Double.parseDouble(fields[4])
                    )
                );
            }
        }
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("Empty workload");
        }

        Configuration configuration = ParquetJavaR2ComparableBench.s3aConfiguration(
            args[3], args[4]
        );
        InputFile input = ParquetJavaR2ComparableBench.inputFile(
            configuration, args[0], args[1]
        );
        MessageType projection = ParquetJavaR2ComparableBench.projection(input);
        int hot = Math.min(6, windows.size());
        if (!scenario.equals("cold")) {
            for (int pass = 0; pass < warmup; pass++) {
                for (int index = 0; index < hot; index++) {
                    Window window = windows.get(index);
                    count(input, projection, window);
                }
            }
        }

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong peak = new AtomicLong(rss());
        Thread sampler = new Thread(() -> {
            while (running.get()) {
                peak.accumulateAndGet(rss(), Math::max);
                try {
                    Thread.sleep(25);
                } catch (InterruptedException exception) {
                    return;
                }
            }
        }, "rss-sampler");
        sampler.setDaemon(true);
        sampler.start();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Future<Result>> futures = new ArrayList<>();
        long start = System.nanoTime();
        try {
            for (int index = 0; index < queries; index++) {
                Window window = windows.get(
                    index % (scenario.equals("warm") ? hot : windows.size())
                );
                futures.add(pool.submit(() -> {
                    long queryStart = System.nanoTime();
                    long rows = count(input, projection, window);
                    return new Result(
                        rows,
                        (System.nanoTime() - queryStart) / 1e6
                    );
                }));
            }
            List<Double> latencies = new ArrayList<>();
            long rows = 0;
            for (Future<Result> future : futures) {
                Result result = future.get();
                latencies.add(result.ms());
                rows += result.rows();
            }
            double wall = (System.nanoTime() - start) / 1e6;
            Collections.sort(latencies);
            System.out.println(
                "engine,scenario,concurrency,queries,wall_ms,qps," +
                "p50_ms,p95_ms,p99_ms,peak_rss_mb,total_rows"
            );
            System.out.printf(
                Locale.ROOT,
                "parquet-java,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%d%n",
                scenario,
                concurrency,
                queries,
                wall,
                queries * 1000 / wall,
                percentile(latencies, 0.5),
                percentile(latencies, 0.95),
                percentile(latencies, 0.99),
                peak.get() / 1048576.0,
                rows
            );
        } finally {
            pool.shutdownNow();
            running.set(false);
            sampler.join(1000);
        }
    }

    private static long count(
        InputFile input,
        MessageType projection,
        Window window
    ) throws Exception {
        return ParquetJavaR2ComparableBench.count(
            input,
            projection,
            window.minx(),
            window.miny(),
            window.maxx(),
            window.maxy()
        );
    }
}
