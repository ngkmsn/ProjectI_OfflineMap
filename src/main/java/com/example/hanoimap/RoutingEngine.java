package com.example.hanoimap;
import java.util.List;
import java.util.Random;
public interface RoutingEngine {
    void init();
    List<LatLon> route(double fromLat, double fromLon, double toLat, double toLon);
    LatLon randomNodeLatLon(Random random);
    double distanceMeters(LatLon a, LatLon b);
    int nearestNodeIndex(double lat, double lon);
}