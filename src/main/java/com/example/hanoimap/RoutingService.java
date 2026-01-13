package com.example.hanoimap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
public class RoutingService {
    private final String osmFile;
    private final String graphDir;
    private RoutingEngine routingEngine;
    public RoutingService(String osmFile, String graphDir) {
        this.osmFile = osmFile;
        this.graphDir = graphDir;
    }
    public void init() {
        String engine = System.getenv().getOrDefault("ROUTING_ENGINE", "manual").toLowerCase();
        if (engine.equals("graphhopper")) {
            routingEngine = new GraphHopperRoutingEngine(osmFile, graphDir);
        } else {
            routingEngine = new SimpleRoutingEngine(osmFile, graphDir);
        }
        routingEngine.init();
    }
    public synchronized String getEngineName() {
        if (routingEngine instanceof GraphHopperRoutingEngine) return "graphhopper";
        return "manual";
    }
    public synchronized boolean setEngine(String engine) {
        String e = engine == null ? "manual" : engine.toLowerCase();
        String cur = getEngineName();
        if (cur.equals(e)) return false;
        if (e.equals("graphhopper")) {
            routingEngine = new GraphHopperRoutingEngine(osmFile, graphDir);
        } else {
            routingEngine = new SimpleRoutingEngine(osmFile, graphDir);
        }
        routingEngine.init();
        return true;
    }
    public List<LatLon> route(double fromLat, double fromLon, double toLat, double toLon) {
        return routingEngine.route(fromLat, fromLon, toLat, toLon);
    }
    public LatLon randomNodeLatLon(Random random) {
        return routingEngine.randomNodeLatLon(random);
    }
    public String getOsmFile() {
        return osmFile;
    }
    public String getGraphDir() {
        return graphDir;
    }
    public List<LatLon> tsp(List<LatLon> points) {
        if (points.size() < 2) {
            return points;
        }
        int n = points.size();
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            dist[i][i] = 0.0;
            for (int j = i + 1; j < n; j++) {
                double d = routingEngine.distanceMeters(points.get(i), points.get(j));
                dist[i][j] = d;
                dist[j][i] = d;
            }
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            idx.add(i);
        }
        List<Integer> bestOrder = new ArrayList<>(idx);
        double bestDistance = Double.POSITIVE_INFINITY;
        List<Integer> permIndices = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            permIndices.add(i);
        }
        List<Integer> current = new ArrayList<>(permIndices);
        do {
            List<Integer> order = new ArrayList<>();
            order.add(0);
            order.addAll(current);
            double d = pathDistance(order, dist);
            if (d < bestDistance) {
                bestDistance = d;
                bestOrder = new ArrayList<>(order);
            }
        } while (nextPermutation(current));
        List<LatLon> fullPath = new ArrayList<>();
        for (int i = 0; i < bestOrder.size() - 1; i++) {
            LatLon a = points.get(bestOrder.get(i));
            LatLon b = points.get(bestOrder.get(i + 1));
            List<LatLon> segment = route(a.lat(), a.lon(), b.lat(), b.lon());
            if (!fullPath.isEmpty()) {
                segment = segment.subList(1, segment.size());
            }
            fullPath.addAll(segment);
        }
        return fullPath;
    }
    private double pathDistance(List<Integer> order, double[][] dist) {
        double total = 0;
        for (int i = 0; i < order.size() - 1; i++) {
            total += dist[order.get(i)][order.get(i + 1)];
        }
        return total;
    }
    private boolean nextPermutation(List<Integer> arr) {
        int i = arr.size() - 2;
        while (i >= 0 && arr.get(i) > arr.get(i + 1)) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        int j = arr.size() - 1;
        while (arr.get(j) < arr.get(i)) {
            j--;
        }
        Collections.swap(arr, i, j);
        int start = i + 1;
        int end = arr.size() - 1;
        while (start < end) {
            Collections.swap(arr, start, end);
            start++;
            end--;
        }
        return true;
    }
}