package com.example.hanoimap;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import spark.Request;
import spark.Response;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static spark.Spark.get;
import static spark.Spark.post;
public class RoutingController {
    private final RoutingService routingService;
    private final BenchmarkManager benchmarkManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public RoutingController(RoutingService routingService, BenchmarkManager benchmarkManager) {
        this.routingService = routingService;
        this.benchmarkManager = benchmarkManager;
    }
    public void registerRoutes() {
        get("/api/health", (req, res) -> {
            res.type("application/json");
            return "{\"status\":\"ok\"}";
        });
        get("/api/route", this::handleRoute);
        post("/api/tsp", this::handleTsp);
        post("/api/benchmark/start", this::handleBenchmarkStart);
        get("/api/benchmark/status", this::handleBenchmarkStatus);
        get("/api/benchmark/download", this::handleBenchmarkDownload);
        get("/api/engine", this::handleEngineGet);
        post("/api/engine/set", this::handleEngineSet);
        get("/tiles/:source/:z/:x/:y.png", this::handleTileProxy);
    }
    private String handleEngineGet(Request req, Response res) {
        try {
            String name = routingService.getEngineName();
            Map<String, Object> out = new HashMap<>();
            out.put("engine", name);
            res.type("application/json");
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            res.status(500);
            res.type("application/json");
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getMessage());
            try {
                return objectMapper.writeValueAsString(err);
            } catch (Exception ex) {
                return "{\"error\":\"Internal server error\"}";
            }
        }
    }
    private String handleEngineSet(Request req, Response res) {
        try {
            String e = req.queryParams("engine");
            if (e == null || e.isBlank()) {
                res.status(400);
                res.type("application/json");
                return "{\"error\":\"Missing engine\"}";
            }
            boolean changed = routingService.setEngine(e);
            Map<String, Object> out = new HashMap<>();
            out.put("engine", routingService.getEngineName());
            out.put("changed", changed);
            res.type("application/json");
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            res.status(500);
            res.type("application/json");
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getMessage());
            try {
                return objectMapper.writeValueAsString(err);
            } catch (Exception ex) {
                return "{\"error\":\"Internal server error\"}";
            }
        }
    }
    private Object handleTileProxy(Request req, Response res) {
        try {
            String source = req.params("source");
            String z = req.params("z");
            String x = req.params("x");
            String y = req.params("y");
            if (y.endsWith(".png")) {
                y = y.substring(0, y.length() - 4);
            }
            String tileUrl;
            switch (source) {
                case "osm":
                    tileUrl = String.format("https://tile.openstreetmap.org/%s/%s/%s.png", z, x, y);
                    break;
                case "carto":
                    String subdomain = "abcd".charAt((int) (Math.random() * 4)) + "";
                    tileUrl = String.format("https://%s.basemaps.cartocdn.com/rastertiles/voyager/%s/%s/%s.png",
                            subdomain, z, x, y);
                    break;
                case "stamen":
                    subdomain = "abcd".charAt((int) (Math.random() * 4)) + "";
                    tileUrl = String.format("https://stamen-tiles-%s.a.ssl.fastly.net/terrain/%s/%s/%s.png",
                            subdomain, z, x, y);
                    break;
                default:
                    res.status(404);
                    res.type("text/plain");
                    return "Unknown tile source";
            }
            URL url = new URL(tileUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                res.type("image/png");
                res.header("Cache-Control", "public, max-age=86400");
                res.header("Access-Control-Allow-Origin", "*");
                try (InputStream is = conn.getInputStream()) {
                    java.io.OutputStream os = res.raw().getOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                    os.flush();
                }
                return "";
            } else {
                res.status(responseCode);
                res.type("text/plain");
                return "Tile not found (HTTP " + responseCode + ")";
            }
        } catch (Exception e) {
            e.printStackTrace();
            res.status(500);
            res.type("text/plain");
            return "Error fetching tile: " + e.getMessage();
        }
    }
    private String handleRoute(Request req, Response res) {
        try {
            double fromLat = Double.parseDouble(req.queryParams("fromLat"));
            double fromLon = Double.parseDouble(req.queryParams("fromLon"));
            double toLat = Double.parseDouble(req.queryParams("toLat"));
            double toLon = Double.parseDouble(req.queryParams("toLon"));
            long startNs = System.nanoTime();
            List<LatLon> path = routingService.route(fromLat, fromLon, toLat, toLon);
            long processingMs = (System.nanoTime() - startNs) / 1_000_000L;
            if (path == null || path.isEmpty()) {
                res.status(404);
                res.type("application/json");
                Map<String, Object> error = new HashMap<>();
                error.put("error", "No route found");
                error.put("processingMs", processingMs);
                return objectMapper.writeValueAsString(error);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("points", path);
            result.put("processingMs", processingMs);
            res.type("application/json");
            return objectMapper.writeValueAsString(result);
        } catch (NumberFormatException e) {
            res.status(400);
            res.type("application/json");
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid coordinates: " + e.getMessage());
            try {
                return objectMapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"error\":\"Invalid coordinates\"}";
            }
        } catch (Exception e) {
            e.printStackTrace();
            res.status(500);
            res.type("application/json");
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Routing error: " + e.getMessage());
            try {
                return objectMapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"error\":\"Internal server error\"}";
            }
        }
    }
    private String handleTsp(Request req, Response res) {
        try {
            List<LatLon> points = objectMapper.readValue(req.body(), new TypeReference<List<LatLon>>() {
            });
            if (points == null || points.isEmpty()) {
                res.status(400);
                res.type("application/json");
                Map<String, Object> error = new HashMap<>();
                error.put("error", "No points provided");
                return objectMapper.writeValueAsString(error);
            }
            if (points.size() > 10) {
                res.status(400);
                res.type("application/json");
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Too many points (max 10)");
                return objectMapper.writeValueAsString(error);
            }
            long startNs = System.nanoTime();
            List<LatLon> path = routingService.tsp(points);
            long processingMs = (System.nanoTime() - startNs) / 1_000_000L;
            if (path == null || path.isEmpty()) {
                res.status(404);
                res.type("application/json");
                Map<String, Object> error = new HashMap<>();
                error.put("error", "No TSP route found");
                error.put("processingMs", processingMs);
                return objectMapper.writeValueAsString(error);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("points", path);
            result.put("processingMs", processingMs);
            res.type("application/json");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            e.printStackTrace();
            res.status(500);
            res.type("application/json");
            Map<String, Object> error = new HashMap<>();
            error.put("error", "TSP error: " + e.getMessage());
            try {
                return objectMapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"error\":\"Internal server error\"}";
            }
        }
    }
    private String handleBenchmarkStart(Request req, Response res) {
        try {
            int count = 1_000_000;
            long seed = System.currentTimeMillis();
            String baseEngine = req.queryParams("baseEngine");
            if (req.queryParams("count") != null) {
                count = Integer.parseInt(req.queryParams("count"));
            }
            if (req.queryParams("seed") != null) {
                seed = Long.parseLong(req.queryParams("seed"));
            }
            String runId = benchmarkManager.startBenchmark(count, seed, baseEngine);
            Map<String, Object> result = new HashMap<>();
            result.put("runId", runId);
            result.put("count", count);
            result.put("baseEngine", baseEngine == null ? "manual" : baseEngine);
            res.type("application/json");
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            res.status(400);
            res.type("application/json");
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            try {
                return objectMapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"error\":\"Bad request\"}";
            }
        }
    }
    private String handleBenchmarkStatus(Request req, Response res) {
        try {
            String runId = req.queryParams("runId");
            if (runId == null || runId.isBlank()) {
                res.status(400);
                res.type("application/json");
                return "{\"error\":\"Missing runId\"}";
            }
            BenchmarkManager.Snapshot snap = benchmarkManager.getStatus(runId);
            if (snap == null) {
                res.status(404);
                res.type("application/json");
                return "{\"error\":\"Run not found\"}";
            }
            Map<String, Object> out = new HashMap<>();
            out.put("runId", snap.runId);
            out.put("state", snap.state.name());
            out.put("total", snap.total);
            out.put("completed", snap.completed);
            out.put("elapsedMs", snap.elapsedMs);
            out.put("minMs", snap.minMs);
            out.put("maxMs", snap.maxMs);
            out.put("avgMs", snap.avgMs);
            out.put("outputFile", snap.outputFile);
            out.put("error", snap.error);
            res.type("application/json");
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            res.status(500);
            res.type("application/json");
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            try {
                return objectMapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"error\":\"Internal server error\"}";
            }
        }
    }
    private Object handleBenchmarkDownload(Request req, Response res) {
        try {
            String runId = req.queryParams("runId");
            if (runId == null || runId.isBlank()) {
                res.status(400);
                res.type("text/plain");
                return "Missing runId";
            }
            java.io.File file = benchmarkManager.getOutputFile(runId);
            if (file == null) {
                res.status(404);
                res.type("text/plain");
                return "File not found";
            }
            res.type("text/csv");
            res.header("Content-Disposition", "attachment; filename=\"trips-" + runId + ".csv\"");
            try (java.io.InputStream is = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
                java.io.OutputStream os = res.raw().getOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();
            }
            return "";
        } catch (Exception e) {
            res.status(500);
            res.type("text/plain");
            return "Error: " + e.getMessage();
        }
    }
}