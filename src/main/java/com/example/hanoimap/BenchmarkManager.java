package com.example.hanoimap;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
public class BenchmarkManager {
    public enum State {
        RUNNING,
        DONE,
        ERROR
    }
    public static final class Snapshot {
        public final String runId;
        public final State state;
        public final int total;
        public final int completed;
        public final long startedAtEpochMs;
        public final long finishedAtEpochMs;
        public final long elapsedMs;
        public final long minMs;
        public final long maxMs;
        public final double avgMs;
        public final String outputFile;
        public final String error;
        private Snapshot(String runId, State state, int total, int completed, long startedAtEpochMs, long finishedAtEpochMs,
                         long elapsedMs, long minMs, long maxMs, double avgMs, String outputFile, String error) {
            this.runId = runId;
            this.state = state;
            this.total = total;
            this.completed = completed;
            this.startedAtEpochMs = startedAtEpochMs;
            this.finishedAtEpochMs = finishedAtEpochMs;
            this.elapsedMs = elapsedMs;
            this.minMs = minMs;
            this.maxMs = maxMs;
            this.avgMs = avgMs;
            this.outputFile = outputFile;
            this.error = error;
        }
    }
    public static final class RunState {
        private final String runId;
        private final int total;
        private final long startedAtEpochMs;
        private final AtomicInteger completed = new AtomicInteger(0);
        private final AtomicBoolean done = new AtomicBoolean(false);
        private volatile long finishedAtEpochMs = 0;
        private volatile State state = State.RUNNING;
        private volatile String error = null;
        private volatile String outputFile = null;
        private volatile long sumMs = 0;
        private volatile long minMs = Long.MAX_VALUE;
        private volatile long maxMs = 0;
        private volatile Future<?> future;
        private RunState(String runId, int total) {
            this.runId = runId;
            this.total = total;
            this.startedAtEpochMs = Instant.now().toEpochMilli();
        }
        private Snapshot snapshot() {
            int doneCount = completed.get();
            long now = Instant.now().toEpochMilli();
            long finished = finishedAtEpochMs;
            long elapsed = (finished > 0 ? finished : now) - startedAtEpochMs;
            double avg = doneCount == 0 ? 0.0 : (double) sumMs / (double) doneCount;
            long min = minMs == Long.MAX_VALUE ? 0 : minMs;
            return new Snapshot(runId, state, total, doneCount, startedAtEpochMs, finished, elapsed, min, maxMs, avg, outputFile, error);
        }
    }
    private final RoutingService routingService;
    private final File benchmarkDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "benchmark-runner");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    public BenchmarkManager(RoutingService routingService, String dataDir) {
        this.routingService = routingService;
        this.benchmarkDir = new File(dataDir, "benchmarks");
    }
    public String startBenchmark(int totalTrips, long seed, String baseEngine) {
        int total = Math.max(1, totalTrips);
        String runId = UUID.randomUUID().toString();
        RunState run = new RunState(runId, total);
        runs.put(runId, run);
        run.future = executor.submit(() -> {
            try {
                benchmarkDir.mkdirs();
                File out = new File(benchmarkDir, "trips-" + runId + ".csv");
                run.outputFile = out.getAbsolutePath();
                Random rnd = new Random(seed);
                RoutingEngine manual = new SimpleRoutingEngine(routingService.getOsmFile(), routingService.getGraphDir());
                RoutingEngine gh = new GraphHopperRoutingEngine(routingService.getOsmFile(), routingService.getGraphDir());
                manual.init();
                gh.init();
                String base = baseEngine == null ? "manual" : baseEngine.toLowerCase();
                RoutingEngine baseEng = base.equals("graphhopper") ? gh : manual;
                RoutingEngine otherEng = base.equals("graphhopper") ? manual : gh;
                try (BufferedWriter writer = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
                    writer.write("trip_id,start_node,end_node,start_lat,start_lon,end_lat,end_lon,manual_distance_meters,manual_time_ms,gh_distance_meters,gh_time_ms,time_diff_percent,distance_diff_percent");
                    writer.newLine();
                    for (int w = 0; w < 200; w++) {
                        LatLon wa = baseEng.randomNodeLatLon(rnd);
                        LatLon wb = baseEng.randomNodeLatLon(rnd);
                        manual.distanceMeters(wa, wb);
                        gh.distanceMeters(wa, wb);
                    }
                    for (int i = 0; i < total; i++) {
                        LatLon a, b;
                        double manualDist, ghDist;
                        double manualMs, ghMs;
                        int aBaseNode, bBaseNode;
                        int attempts = 0;
                        while (true) {
                            attempts++;
                            a = baseEng.randomNodeLatLon(rnd);
                            b = baseEng.randomNodeLatLon(rnd);
                            aBaseNode = baseEng.nearestNodeIndex(a.lat(), a.lon());
                            bBaseNode = baseEng.nearestNodeIndex(b.lat(), b.lon());
                            long t0 = System.nanoTime();
                            manualDist = manual.distanceMeters(a, b);
                            manualMs = (System.nanoTime() - t0) / 1_000_000.0;
                            long t1 = System.nanoTime();
                            ghDist = gh.distanceMeters(a, b);
                            ghMs = (System.nanoTime() - t1) / 1_000_000.0;
                            boolean okManual = Double.isFinite(manualDist) && manualDist >= 0.0;
                            boolean okGh = Double.isFinite(ghDist) && ghDist >= 0.0;
                            if (okManual && okGh) break;
                            if (attempts >= 50) {
                                manualDist = 0.0;
                                ghDist = 0.0;
                                manualMs = 0.0;
                                ghMs = 0.0;
                                aBaseNode = -1;
                                bBaseNode = -1;
                                break;
                            }
                        }
                        double minMs = 0.001;
                        if (manualMs < minMs) manualMs = minMs;
                        if (ghMs < minMs) ghMs = minMs;
                        double baseTime = base.equals("graphhopper") ? ghMs : manualMs;
                        double otherTime = base.equals("graphhopper") ? manualMs : ghMs;
                        double baseDist = base.equals("graphhopper") ? ghDist : manualDist;
                        double otherDist = base.equals("graphhopper") ? manualDist : ghDist;
                        double eps = 1e-9;
                        double timeDiffPercent = (baseTime <= eps) ? 0.0 : ((otherTime - baseTime) / baseTime) * 100.0;
                        double distDiffPercent = (baseDist <= eps) ? 0.0 : ((otherDist - baseDist) / baseDist) * 100.0;
                        writer.write(i + "," +
                                aBaseNode + "," +
                                bBaseNode + "," +
                                a.lat() + "," + a.lon() + "," +
                                b.lat() + "," + b.lon() + "," +
                                String.format(Locale.US, "%.3f", manualDist) + "," +
                                String.format(Locale.US, "%.3f", manualMs) + "," +
                                String.format(Locale.US, "%.3f", ghDist) + "," +
                                String.format(Locale.US, "%.3f", ghMs) + "," +
                                String.format(Locale.US, "%.6f", timeDiffPercent) + "," +
                                String.format(Locale.US, "%.6f", distDiffPercent));
                        writer.newLine();
                        run.completed.incrementAndGet();
                    }
                }
                run.state = State.DONE;
                run.finishedAtEpochMs = Instant.now().toEpochMilli();
                run.done.set(true);
            } catch (Exception e) {
                run.state = State.ERROR;
                run.error = e.getMessage();
                run.finishedAtEpochMs = Instant.now().toEpochMilli();
                run.done.set(true);
            }
        });
        return runId;
    }
    public Snapshot getStatus(String runId) {
        RunState run = runs.get(runId);
        if (run == null) {
            return null;
        }
        return run.snapshot();
    }
    public File getOutputFile(String runId) {
        RunState run = runs.get(runId);
        if (run == null || run.outputFile == null) {
            return null;
        }
        File file = new File(run.outputFile);
        if (!file.exists()) {
            return null;
        }
        return file;
    }
}