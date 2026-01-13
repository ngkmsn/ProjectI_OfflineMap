package com.example.hanoimap;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.util.PointList;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
public class GraphHopperRoutingEngine implements RoutingEngine {
    private final String osmFile;
    private final String graphDir;
    private GraphHopper hopper;
    public GraphHopperRoutingEngine(String osmFile, String graphDir) {
        this.osmFile = osmFile;
        this.graphDir = graphDir;
    }
    @Override
    public void init() {
        hopper = new GraphHopper()
                .setOSMFile(osmFile)
                .setGraphHopperLocation(graphDir)
                .setProfiles(new Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(true));
        hopper.importOrLoad();
    }
    @Override
    public List<LatLon> route(double fromLat, double fromLon, double toLat, double toLon) {
        GHRequest req = new GHRequest(fromLat, fromLon, toLat, toLon).setProfile("car");
        GHResponse rsp = hopper.route(req);
        if (rsp.hasErrors()) {
            Throwable err = rsp.getErrors().isEmpty() ? null : rsp.getErrors().get(0);
            if (err instanceof PointNotFoundException e) throw e;
            if (err instanceof RuntimeException e) throw e;
            if (err != null) throw new RuntimeException(err);
            throw new RuntimeException("GraphHopper routing error");
        }
        ResponsePath path = rsp.getBest();
        PointList pl = path.getPoints();
        List<LatLon> result = new ArrayList<>();
        for (int i = 0; i < pl.size(); i++) {
            result.add(new LatLon(pl.getLat(i), pl.getLon(i)));
        }
        return result;
    }
    @Override
    public LatLon randomNodeLatLon(Random random) {
        if (hopper == null) return new LatLon(0, 0);
        var storage = hopper.getGraphHopperStorage();
        if (storage == null) return new LatLon(0, 0);
        BBox bbox = storage.getBounds();
        if (bbox == null || !bbox.isValid()) return new LatLon(0, 0);
        var locationIndex = hopper.getLocationIndex();
        if (locationIndex != null) {
            for (int i = 0; i < 200; i++) {
                double lat = bbox.minLat + random.nextDouble() * (bbox.maxLat - bbox.minLat);
                double lon = bbox.minLon + random.nextDouble() * (bbox.maxLon - bbox.minLon);
                var qr = locationIndex.findClosest(lat, lon, EdgeFilter.ALL_EDGES);
                if (qr != null && qr.isValid()) {
                    GHPoint snapped = qr.getSnappedPoint();
                    return new LatLon(snapped.lat, snapped.lon);
                }
            }
        }
        int nodes = storage.getNodes();
        if (nodes <= 0) return new LatLon(0, 0);
        int idx = random.nextInt(nodes);
        var nodeAccess = storage.getNodeAccess();
        return new LatLon(nodeAccess.getLat(idx), nodeAccess.getLon(idx));
    }
    @Override
    public double distanceMeters(LatLon a, LatLon b) {
        GHRequest req = new GHRequest(a.lat(), a.lon(), b.lat(), b.lon()).setProfile("car");
        GHResponse rsp = hopper.route(req);
        if (rsp.hasErrors()) return Double.POSITIVE_INFINITY;
        return rsp.getBest().getDistance();
    }
    @Override
    public int nearestNodeIndex(double lat, double lon) {
        if (hopper == null) return -1;
        var locationIndex = hopper.getLocationIndex();
        if (locationIndex == null) return -1;
        var qr = locationIndex.findClosest(lat, lon, EdgeFilter.ALL_EDGES);
        if (qr == null || !qr.isValid()) return -1;
        return qr.getClosestNode();
    }
}