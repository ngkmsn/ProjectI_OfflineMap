package com.example.hanoimap;
public class App {
    private static boolean serverStarted = false;
    private static RoutingService routingService;
    private static BenchmarkManager benchmarkManager;
    public static void startServer() {
        if (serverStarted) {
            return;
        }
        String dataDir = System.getenv().getOrDefault("GRAPH_DATA_DIR", "data");
        String osmFile = System.getenv().getOrDefault("OSM_PBF_FILE", dataDir + "/hanoi.osm.pbf");
        String graphDir = System.getenv().getOrDefault("GRAPH_CACHE_DIR", dataDir + "/graph-cache");
        spark.Spark.port(4567);
        spark.Spark.staticFiles.location("/public");  
        routingService = new RoutingService(osmFile, graphDir);
        routingService.init();
        System.out.println("Routing engine: " + routingService.getEngineName());
        benchmarkManager = new BenchmarkManager(routingService, dataDir);
        new RoutingController(routingService, benchmarkManager).registerRoutes();
        serverStarted = true;
        System.out.println("Server started at http://localhost:4567");
    }
    public static RoutingService getRoutingService() {
        return routingService;
    }
    public static BenchmarkManager getBenchmarkManager() {
        return benchmarkManager;
    }
    public static void main(String[] args) {
        startServer();
    }
}