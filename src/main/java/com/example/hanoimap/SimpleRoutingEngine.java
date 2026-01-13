package com.example.hanoimap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.zip.InflaterInputStream;
public class SimpleRoutingEngine implements RoutingEngine {
    private static final int CACHE_VERSION = 1;
    private static final String CACHE_FILE_NAME = "simple-routing-graph-v1.bin";
    private final String osmPbfFile;
    private final String cacheDir;
    private double[] nodeLat;
    private double[] nodeLon;
    private int[] head;
    private int[] edgeTo;
    private int[] edgeNext;
    private double[] edgeWeightMeters;
    private GridIndex gridIndex;
    private double[] dist;
    private int[] prev;
    private int[] seenStamp;
    private int[] closedStamp;
    private int runId = 1;
    public SimpleRoutingEngine(String osmPbfFile, String cacheDir) {
        this.osmPbfFile = osmPbfFile;
        this.cacheDir = cacheDir;
    }
    public void init() {
        File cacheFile = new File(cacheDir, CACHE_FILE_NAME);
        boolean loaded = false;
        if (cacheFile.exists()) {
            try {
                loadCache(cacheFile);
                loaded = true;
            } catch (Exception ignored) {
                loaded = false;
            }
        }
        if (!loaded) {
            buildFromOsmPbf();
            try {
                cacheFile.getParentFile().mkdirs();
                saveCache(cacheFile);
            } catch (Exception ignored) {
            }
        }
        gridIndex = new GridIndex(nodeLat, nodeLon);
        dist = new double[nodeLat.length];
        prev = new int[nodeLat.length];
        seenStamp = new int[nodeLat.length];
        closedStamp = new int[nodeLat.length];
    }
    public synchronized List<LatLon> route(double fromLat, double fromLon, double toLat, double toLon) {
        int start = gridIndex.findNearestNode(fromLat, fromLon);
        int goal = gridIndex.findNearestNode(toLat, toLon);
        if (start < 0 || goal < 0) {
            return List.of();
        }
        int[] path = shortestPathAStar(start, goal);
        if (path == null || path.length == 0) {
            return List.of();
        }
        List<LatLon> points = new ArrayList<>(path.length);
        for (int idx : path) {
            points.add(new LatLon(nodeLat[idx], nodeLon[idx]));
        }
        return points;
    }
    public synchronized double distanceMeters(LatLon a, LatLon b) {
        int start = gridIndex.findNearestNode(a.lat(), a.lon());
        int goal = gridIndex.findNearestNode(b.lat(), b.lon());
        if (start < 0 || goal < 0) {
            return Double.POSITIVE_INFINITY;
        }
        int[] path = shortestPathAStar(start, goal);
        if (path == null || path.length < 2) {
            return Double.POSITIVE_INFINITY;
        }
        double total = 0.0;
        for (int i = 0; i < path.length - 1; i++) {
            int u = path[i];
            int v = path[i + 1];
            total += haversineMeters(nodeLat[u], nodeLon[u], nodeLat[v], nodeLon[v]);
        }
        return total;
    }
    public LatLon randomNodeLatLon(Random random) {
        if (nodeLat == null || nodeLat.length == 0) {
            return new LatLon(0, 0);
        }
        int idx = random.nextInt(nodeLat.length);
        return new LatLon(nodeLat[idx], nodeLon[idx]);
    }
    public int nearestNodeIndex(double lat, double lon) {
        return gridIndex.findNearestNode(lat, lon);
    }
    private int[] shortestPathAStar(int start, int goal) {
        int currentRun = runId++;
        PriorityQueue<NodeEntry> pq = new PriorityQueue<>();
        setDist(start, 0.0, currentRun);
        setPrev(start, -1, currentRun);
        pq.add(new NodeEntry(start, heuristicMeters(start, goal)));
        while (!pq.isEmpty()) {
            NodeEntry entry = pq.poll();
            int u = entry.node;
            if (closedStamp[u] == currentRun) {
                continue;
            }
            closedStamp[u] = currentRun;
            if (u == goal) {
                return reconstructPath(goal, currentRun);
            }
            double distU = getDist(u, currentRun);
            for (int e = head[u]; e != -1; e = edgeNext[e]) {
                int v = edgeTo[e];
                double cand = distU + edgeWeightMeters[e];
                double cur = getDist(v, currentRun);
                if (cand < cur) {
                    setDist(v, cand, currentRun);
                    setPrev(v, u, currentRun);
                    pq.add(new NodeEntry(v, cand + heuristicMeters(v, goal)));
                }
            }
        }
        return null;
    }
    private int[] reconstructPath(int goal, int currentRun) {
        int count = 0;
        for (int at = goal; at != -1; at = getPrev(at, currentRun)) {
            count++;
        }
        int[] path = new int[count];
        int i = count - 1;
        for (int at = goal; at != -1; at = getPrev(at, currentRun)) {
            path[i--] = at;
        }
        return path;
    }
    private double heuristicMeters(int node, int goal) {
        return haversineMeters(nodeLat[node], nodeLon[node], nodeLat[goal], nodeLon[goal]);
    }
    private double getDist(int node, int currentRun) {
        if (seenStamp[node] != currentRun) {
            return Double.POSITIVE_INFINITY;
        }
        return dist[node];
    }
    private void setDist(int node, double value, int currentRun) {
        dist[node] = value;
        seenStamp[node] = currentRun;
    }
    private int getPrev(int node, int currentRun) {
        if (seenStamp[node] != currentRun) {
            return -1;
        }
        return prev[node];
    }
    private void setPrev(int node, int value, int currentRun) {
        prev[node] = value;
        seenStamp[node] = currentRun;
    }
    private void buildFromOsmPbf() {
        List<WayData> ways = new ArrayList<>();
        LongHashSet neededNodeIds = new LongHashSet(1 << 20);
        try {
            forEachPrimitiveBlock(osmPbfFile, primitiveBlock -> {
                PrimitiveBlockParser.parseWays(primitiveBlock, (refs, onewayMode) -> {
                    WayData way = new WayData(refs, onewayMode);
                    ways.add(way);
                    for (long ref : refs) {
                        neededNodeIds.add(ref);
                    }
                });
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read OSM PBF ways: " + e.getMessage(), e);
        }
        LongIntHashMap nodeIdToIndex = new LongIntHashMap(Math.max(neededNodeIds.size() * 2, 16));
        nodeLat = new double[neededNodeIds.size()];
        nodeLon = new double[neededNodeIds.size()];
        int[] nodeCount = {0};
        try {
            forEachPrimitiveBlock(osmPbfFile, primitiveBlock -> {
                PrimitiveBlockParser.parseNodes(primitiveBlock, (id, lat, lon) -> {
                    if (!neededNodeIds.contains(id)) {
                        return;
                    }
                    if (nodeIdToIndex.containsKey(id)) {
                        return;
                    }
                    int idx = nodeCount[0]++;
                    nodeIdToIndex.put(id, idx);
                    nodeLat[idx] = lat;
                    nodeLon[idx] = lon;
                });
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read OSM PBF nodes: " + e.getMessage(), e);
        }
        if (nodeCount[0] != nodeLat.length) {
            nodeLat = Arrays.copyOf(nodeLat, nodeCount[0]);
            nodeLon = Arrays.copyOf(nodeLon, nodeCount[0]);
        }
        int edgeCount = 0;
        for (WayData way : ways) {
            int segments = Math.max(0, way.refs.length - 1);
            edgeCount += switch (way.onewayMode) {
                case BOTH -> segments * 2;
                case FORWARD, REVERSE -> segments;
            };
        }
        head = new int[nodeLat.length];
        Arrays.fill(head, -1);
        edgeTo = new int[edgeCount];
        edgeNext = new int[edgeCount];
        edgeWeightMeters = new double[edgeCount];
        int[] edgePtr = {0};
        for (WayData way : ways) {
            for (int i = 0; i < way.refs.length - 1; i++) {
                long aId = way.refs[i];
                long bId = way.refs[i + 1];
                int a = nodeIdToIndex.getOrDefault(aId, -1);
                int b = nodeIdToIndex.getOrDefault(bId, -1);
                if (a < 0 || b < 0) {
                    continue;
                }
                double w = haversineMeters(nodeLat[a], nodeLon[a], nodeLat[b], nodeLon[b]);
                switch (way.onewayMode) {
                    case BOTH -> {
                        addEdge(a, b, w, edgePtr);
                        addEdge(b, a, w, edgePtr);
                    }
                    case FORWARD -> addEdge(a, b, w, edgePtr);
                    case REVERSE -> addEdge(b, a, w, edgePtr);
                }
            }
        }
    }
    private void addEdge(int from, int to, double weightMeters, int[] edgePtr) {
        int e = edgePtr[0]++;
        edgeTo[e] = to;
        edgeWeightMeters[e] = weightMeters;
        edgeNext[e] = head[from];
        head[from] = e;
    }
    private void saveCache(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(CACHE_VERSION);
            out.writeInt(nodeLat.length);
            out.writeInt(edgeTo.length);
            for (int i = 0; i < nodeLat.length; i++) {
                out.writeDouble(nodeLat[i]);
                out.writeDouble(nodeLon[i]);
            }
            for (int i = 0; i < head.length; i++) {
                out.writeInt(head[i]);
            }
            for (int i = 0; i < edgeTo.length; i++) {
                out.writeInt(edgeTo[i]);
                out.writeInt(edgeNext[i]);
                out.writeDouble(edgeWeightMeters[i]);
            }
        }
    }
    private void loadCache(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int version = in.readInt();
            if (version != CACHE_VERSION) {
                throw new IOException("Cache version mismatch");
            }
            int nodeCount = in.readInt();
            int edgeCount = in.readInt();
            nodeLat = new double[nodeCount];
            nodeLon = new double[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                nodeLat[i] = in.readDouble();
                nodeLon[i] = in.readDouble();
            }
            head = new int[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                head[i] = in.readInt();
            }
            edgeTo = new int[edgeCount];
            edgeNext = new int[edgeCount];
            edgeWeightMeters = new double[edgeCount];
            for (int i = 0; i < edgeCount; i++) {
                edgeTo[i] = in.readInt();
                edgeNext[i] = in.readInt();
                edgeWeightMeters[i] = in.readDouble();
            }
            try {
                if (in.read() != -1) {
                    throw new IOException("Trailing bytes in cache");
                }
            } catch (EOFException ignored) {
            }
        }
    }
    private static void forEachPrimitiveBlock(String osmPbfFile, PrimitiveBlockConsumer consumer) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(osmPbfFile))) {
            while (true) {
                byte[] headerSizeBytes = raw.readNBytes(4);
                if (headerSizeBytes.length == 0) {
                    break;
                }
                if (headerSizeBytes.length < 4) {
                    throw new IOException("Unexpected EOF while reading block header size");
                }
                int headerSize = ((headerSizeBytes[0] & 0xFF) << 24)
                        | ((headerSizeBytes[1] & 0xFF) << 16)
                        | ((headerSizeBytes[2] & 0xFF) << 8)
                        | (headerSizeBytes[3] & 0xFF);
                byte[] headerBytes = raw.readNBytes(headerSize);
                if (headerBytes.length < headerSize) {
                    throw new IOException("Unexpected EOF while reading block header");
                }
                ProtoReader headerReader = new ProtoReader(headerBytes);
                String type = null;
                int dataSize = -1;
                while (!headerReader.isAtEnd()) {
                    int tag = headerReader.readTag();
                    int fieldNumber = tag >>> 3;
                    int wireType = tag & 7;
                    switch (fieldNumber) {
                        case 1 -> type = headerReader.readString(wireType);
                        case 3 -> dataSize = headerReader.readInt32(wireType);
                        default -> headerReader.skipField(wireType);
                    }
                }
                if (type == null || dataSize < 0) {
                    throw new IOException("Invalid OSM PBF block header");
                }
                byte[] blobBytes = raw.readNBytes(dataSize);
                if (blobBytes.length < dataSize) {
                    throw new IOException("Unexpected EOF while reading block blob");
                }
                if (!"OSMData".equals(type)) {
                    continue;
                }
                ProtoReader blobReader = new ProtoReader(blobBytes);
                byte[] rawData = null;
                byte[] zlibData = null;
                while (!blobReader.isAtEnd()) {
                    int tag = blobReader.readTag();
                    int fieldNumber = tag >>> 3;
                    int wireType = tag & 7;
                    switch (fieldNumber) {
                        case 1 -> rawData = blobReader.readBytes(wireType);
                        case 3 -> zlibData = blobReader.readBytes(wireType);
                        default -> blobReader.skipField(wireType);
                    }
                }
                byte[] primitiveBlockBytes;
                if (rawData != null) {
                    primitiveBlockBytes = rawData;
                } else if (zlibData != null) {
                    primitiveBlockBytes = inflate(zlibData);
                } else {
                    throw new IOException("Unsupported blob encoding");
                }
                consumer.accept(primitiveBlockBytes);
            }
        }
    }
    private static byte[] inflate(byte[] zlibData) throws IOException {
        try (InputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(zlibData))) {
            return in.readAllBytes();
        }
    }
    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLam = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLam / 2) * Math.sin(dLam / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }
    private record NodeEntry(int node, double fScore) implements Comparable<NodeEntry> {
        @Override
        public int compareTo(NodeEntry o) {
            return Double.compare(this.fScore, o.fScore);
        }
    }
    @FunctionalInterface
    private interface PrimitiveBlockConsumer {
        void accept(byte[] primitiveBlockBytes) throws IOException;
    }
    private enum OnewayMode {
        BOTH,
        FORWARD,
        REVERSE
    }
    private static final class WayData {
        private final long[] refs;
        private final OnewayMode onewayMode;
        private WayData(long[] refs, OnewayMode onewayMode) {
            this.refs = refs;
            this.onewayMode = onewayMode;
        }
    }
    private static final class GridIndex {
        private static final double CELL_SIZE_DEG = 0.002;
        private final double[] lat;
        private final double[] lon;
        private final double minLat;
        private final double minLon;
        private final LongIntHashMap cellHead;
        private final int[] nextInCell;
        private GridIndex(double[] lat, double[] lon) {
            this.lat = lat;
            this.lon = lon;
            double minLatTmp = Double.POSITIVE_INFINITY;
            double minLonTmp = Double.POSITIVE_INFINITY;
            for (int i = 0; i < lat.length; i++) {
                minLatTmp = Math.min(minLatTmp, lat[i]);
                minLonTmp = Math.min(minLonTmp, lon[i]);
            }
            this.minLat = minLatTmp;
            this.minLon = minLonTmp;
            this.cellHead = new LongIntHashMap(Math.max(lat.length / 2, 16));
            this.nextInCell = new int[lat.length];
            Arrays.fill(nextInCell, -1);
            for (int i = 0; i < lat.length; i++) {
                long key = cellKey(lat[i], lon[i]);
                int head = cellHead.getOrDefault(key, -1);
                nextInCell[i] = head;
                cellHead.put(key, i);
            }
        }
        int findNearestNode(double qLat, double qLon) {
            if (lat.length == 0) {
                return -1;
            }
            int baseX = cellX(qLon);
            int baseY = cellY(qLat);
            int best = -1;
            double bestDist = Double.POSITIVE_INFINITY;
            for (int r = 0; r <= 20; r++) {
                boolean anyCell = false;
                for (int dx = -r; dx <= r; dx++) {
                    int x = baseX + dx;
                    int y1 = baseY - r;
                    int y2 = baseY + r;
                    ScanResult res1 = scanCell(x, y1, qLat, qLon, best, bestDist);
                    anyCell |= res1.foundAny;
                    best = res1.bestNode;
                    bestDist = res1.bestDist;
                    if (r != 0) {
                        ScanResult res2 = scanCell(x, y2, qLat, qLon, best, bestDist);
                        anyCell |= res2.foundAny;
                        best = res2.bestNode;
                        bestDist = res2.bestDist;
                    }
                }
                for (int dy = -r + 1; dy <= r - 1; dy++) {
                    int y = baseY + dy;
                    int x1 = baseX - r;
                    int x2 = baseX + r;
                    ScanResult res1 = scanCell(x1, y, qLat, qLon, best, bestDist);
                    anyCell |= res1.foundAny;
                    best = res1.bestNode;
                    bestDist = res1.bestDist;
                    if (r != 0) {
                        ScanResult res2 = scanCell(x2, y, qLat, qLon, best, bestDist);
                        anyCell |= res2.foundAny;
                        best = res2.bestNode;
                        bestDist = res2.bestDist;
                    }
                }
                if (best != -1 && anyCell && r >= 1) {
                    double approxCellMeters = CELL_SIZE_DEG * 111320.0;
                    if (r * approxCellMeters > bestDist) {
                        break;
                    }
                }
            }
            return best;
        }
        private ScanResult scanCell(int cx, int cy, double qLat, double qLon, int bestNode, double bestDist) {
            long key = (((long) cx) << 32) ^ (cy & 0xFFFFFFFFL);
            int node = cellHead.getOrDefault(key, -1);
            if (node == -1) {
                return new ScanResult(bestNode, bestDist, false);
            }
            boolean found = false;
            while (node != -1) {
                double d = haversineMeters(qLat, qLon, lat[node], lon[node]);
                if (d < bestDist) {
                    bestDist = d;
                    bestNode = node;
                }
                found = true;
                node = nextInCell[node];
            }
            return new ScanResult(bestNode, bestDist, found);
        }
        private long cellKey(double lat, double lon) {
            int cx = cellX(lon);
            int cy = cellY(lat);
            return (((long) cx) << 32) ^ (cy & 0xFFFFFFFFL);
        }
        private int cellX(double lon) {
            return (int) Math.floor((lon - minLon) / CELL_SIZE_DEG);
        }
        private int cellY(double lat) {
            return (int) Math.floor((lat - minLat) / CELL_SIZE_DEG);
        }
        private record ScanResult(int bestNode, double bestDist, boolean foundAny) {
        }
    }
    private static final class PrimitiveBlockParser {
        static void parseWays(byte[] primitiveBlockBytes, WayHandler handler) throws IOException {
            ProtoReader block = new ProtoReader(primitiveBlockBytes);
            List<byte[]> stringTable = null;
            List<byte[]> primitiveGroups = new ArrayList<>();
            while (!block.isAtEnd()) {
                int tag = block.readTag();
                int fieldNumber = tag >>> 3;
                int wireType = tag & 7;
                switch (fieldNumber) {
                    case 1 -> stringTable = parseStringTable(block.readBytes(wireType));
                    case 2 -> primitiveGroups.add(block.readBytes(wireType));
                    default -> block.skipField(wireType);
                }
            }
            if (stringTable == null || primitiveGroups.isEmpty()) {
                return;
            }
            for (byte[] pgBytes : primitiveGroups) {
                ProtoReader pg = new ProtoReader(pgBytes);
                while (!pg.isAtEnd()) {
                    int tag = pg.readTag();
                    int fieldNumber = tag >>> 3;
                    int wireType = tag & 7;
                    if (fieldNumber == 3) {
                        byte[] wayBytes = pg.readBytes(wireType);
                        WayParsed way = parseWay(wayBytes);
                        if (way != null && isRoutableHighway(way.keys, way.vals, stringTable)) {
                            OnewayMode mode = getOnewayMode(way.keys, way.vals, stringTable);
                            handler.onWay(way.refs, mode);
                        }
                    } else {
                        pg.skipField(wireType);
                    }
                }
            }
        }
        static void parseNodes(byte[] primitiveBlockBytes, NodeHandler handler) throws IOException {
            ProtoReader block = new ProtoReader(primitiveBlockBytes);
            List<byte[]> primitiveGroups = new ArrayList<>();
            long granularity = 100;
            long latOffset = 0;
            long lonOffset = 0;
            while (!block.isAtEnd()) {
                int tag = block.readTag();
                int fieldNumber = tag >>> 3;
                int wireType = tag & 7;
                switch (fieldNumber) {
                    case 2 -> primitiveGroups.add(block.readBytes(wireType));
                    case 17 -> granularity = block.readInt64(wireType);
                    case 19 -> latOffset = block.readInt64(wireType);
                    case 20 -> lonOffset = block.readInt64(wireType);
                    default -> block.skipField(wireType);
                }
            }
            for (byte[] pgBytes : primitiveGroups) {
                ProtoReader pg = new ProtoReader(pgBytes);
                while (!pg.isAtEnd()) {
                    int tag = pg.readTag();
                    int fieldNumber = tag >>> 3;
                    int wireType = tag & 7;
                    if (fieldNumber == 1) {
                        byte[] nodeBytes = pg.readBytes(wireType);
                        parseNode(nodeBytes, granularity, latOffset, lonOffset, handler);
                    } else if (fieldNumber == 2) {
                        byte[] denseBytes = pg.readBytes(wireType);
                        parseDenseNodes(denseBytes, granularity, latOffset, lonOffset, handler);
                    } else {
                        pg.skipField(wireType);
                    }
                }
            }
        }
        private static void parseNode(byte[] nodeBytes, long granularity, long latOffset, long lonOffset, NodeHandler handler) throws IOException {
            ProtoReader n = new ProtoReader(nodeBytes);
            long id = 0;
            long lat = 0;
            long lon = 0;
            boolean has = false;
            while (!n.isAtEnd()) {
                int tag = n.readTag();
                int fieldNumber = tag >>> 3;
                int wireType = tag & 7;
                switch (fieldNumber) {
                    case 1 -> {
                        id = n.readInt64(wireType);
                        has = true;
                    }
                    case 8 -> lat = n.readSInt64(wireType);
                    case 9 -> lon = n.readSInt64(wireType);
                    default -> n.skipField(wireType);
                }
            }
            if (!has) {
                return;
            }
            double latDeg = 1e-9 * (latOffset + granularity * lat);
            double lonDeg = 1e-9 * (lonOffset + granularity * lon);
            handler.onNode(id, latDeg, lonDeg);
        }
        private static void parseDenseNodes(byte[] denseBytes, long granularity, long latOffset, long lonOffset, NodeHandler handler) throws IOException {
            ProtoReader dn = new ProtoReader(denseBytes);
            byte[] idPacked = null;
            byte[] latPacked = null;
            byte[] lonPacked = null;
            while (!dn.isAtEnd()) {
                int tag = dn.readTag();
                int fieldNumber = tag >>> 3;
                int wireType = tag & 7;
                switch (fieldNumber) {
                    case 1 -> idPacked = dn.readBytes(wireType);
                    case 8 -> latPacked = dn.readBytes(wireType);
                    case 9 -> lonPacked = dn.readBytes(wireType);
                    default -> dn.skipField(wireType);
                }
            }
            if (idPacked == null || latPacked == null || lonPacked == null) {
                return;
            }
            ProtoReader idR = new ProtoReader(idPacked);
            ProtoReader latR = new ProtoReader(latPacked);
            ProtoReader lonR = new ProtoReader(lonPacked);
            long id = 0;
            long lat = 0;
            long lon = 0;
            while (!idR.isAtEnd() && !latR.isAtEnd() && !lonR.isAtEnd()) {
                id += idR.readSInt64Packed();
                lat += latR.readSInt64Packed();
                lon += lonR.readSInt64Packed();
                double latDeg = 1e-9 * (latOffset + granularity * lat);
                double lonDeg = 1e-9 * (lonOffset + granularity * lon);
                handler.onNode(id, latDeg, lonDeg);
            }
        }
        private static List<byte[]> parseStringTable(byte[] stringTableBytes) throws IOException {
            ProtoReader st = new ProtoReader(stringTableBytes);
            List<byte[]> strings = new ArrayList<>();
            while (!st.isAtEnd()) {
                int tag = st.readTag();
                int fieldNumber = tag >>> 3;
                int wireType = tag & 7;
                if (fieldNumber == 1) {
                    strings.add(st.readBytes(wireType));
                } else {
                    st.skipField(wireType);
                }
            }
            return strings;
        }
        private static WayParsed parseWay(byte[] wayBytes) throws IOException {
            ProtoReader w = new ProtoReader(wayBytes);
            byte[] keysPacked = null;
            byte[] valsPacked = null;
            byte[] refsPacked = null;
            while (!w.isAtEnd()) {
                int tag = w.readTag();
                int fieldNumber = tag >>> 3;
                int wireType = tag & 7;
                switch (fieldNumber) {
                    case 2 -> keysPacked = w.readBytes(wireType);
                    case 3 -> valsPacked = w.readBytes(wireType);
                    case 8 -> refsPacked = w.readBytes(wireType);
                    default -> w.skipField(wireType);
                }
            }
            if (refsPacked == null) {
                return null;
            }
            int[] keys = keysPacked == null ? new int[0] : readPackedInt32(keysPacked);
            int[] vals = valsPacked == null ? new int[0] : readPackedInt32(valsPacked);
            long[] refs = readPackedSInt64Delta(refsPacked);
            return new WayParsed(keys, vals, refs);
        }
        private static int[] readPackedInt32(byte[] packedBytes) throws IOException {
            ProtoReader r = new ProtoReader(packedBytes);
            int[] tmp = new int[Math.max(4, packedBytes.length / 2)];
            int n = 0;
            while (!r.isAtEnd()) {
                if (n == tmp.length) {
                    tmp = Arrays.copyOf(tmp, tmp.length * 2);
                }
                tmp[n++] = r.readUInt32Packed();
            }
            return Arrays.copyOf(tmp, n);
        }
        private static long[] readPackedSInt64Delta(byte[] packedBytes) throws IOException {
            ProtoReader r = new ProtoReader(packedBytes);
            long[] tmp = new long[Math.max(4, packedBytes.length / 2)];
            int n = 0;
            long acc = 0;
            while (!r.isAtEnd()) {
                long delta = r.readSInt64Packed();
                acc += delta;
                if (n == tmp.length) {
                    tmp = Arrays.copyOf(tmp, tmp.length * 2);
                }
                tmp[n++] = acc;
            }
            return Arrays.copyOf(tmp, n);
        }
        private static boolean isRoutableHighway(int[] keys, int[] vals, List<byte[]> stringTable) {
            String highway = null;
            for (int i = 0; i < Math.min(keys.length, vals.length); i++) {
                String k = getString(stringTable, keys[i]);
                if ("highway".equals(k)) {
                    highway = getString(stringTable, vals[i]);
                    break;
                }
            }
            if (highway == null) {
                return false;
            }
            return switch (highway) {
                case "motorway", "trunk", "primary", "secondary", "tertiary",
                     "unclassified", "residential", "service", "living_street",
                     "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link" -> true;
                default -> false;
            };
        }
        private static boolean isAccessAllowed(int[] keys, int[] vals, List<byte[]> stringTable) {
            for (int i = 0; i < Math.min(keys.length, vals.length); i++) {
                String k = getString(stringTable, keys[i]);
                if ("access".equals(k) || "vehicle".equals(k) || "motor_vehicle".equals(k)) {
                    String v = getString(stringTable, vals[i]);
                    switch (v) {
                        case "no", "private", "agricultural", "forestry", "delivery" -> {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
        private static OnewayMode getOnewayMode(int[] keys, int[] vals, List<byte[]> stringTable) {
            String oneway = null;
            String junction = null;
            for (int i = 0; i < Math.min(keys.length, vals.length); i++) {
                String k = getString(stringTable, keys[i]);
                if ("oneway".equals(k)) {
                    oneway = getString(stringTable, vals[i]);
                } else if ("junction".equals(k)) {
                    junction = getString(stringTable, vals[i]);
                }
            }
            if (junction != null && "roundabout".equals(junction)) {
                return OnewayMode.FORWARD;
            }
            if (oneway == null) {
                return OnewayMode.BOTH;
            }
            String v = oneway.trim().toLowerCase();
            return switch (v) {
                case "yes", "true", "1" -> OnewayMode.FORWARD;
                case "-1", "reverse" -> OnewayMode.REVERSE;
                default -> OnewayMode.BOTH;
            };
        }
        private static String getString(List<byte[]> table, int idx) {
            if (idx < 0 || idx >= table.size()) {
                return "";
            }
            return new String(table.get(idx), StandardCharsets.UTF_8);
        }
        private record WayParsed(int[] keys, int[] vals, long[] refs) {
        }
        @FunctionalInterface
        interface WayHandler {
            void onWay(long[] refs, OnewayMode onewayMode);
        }
        @FunctionalInterface
        interface NodeHandler {
            void onNode(long id, double lat, double lon);
        }
    }
    private static final class ProtoReader {
        private final byte[] data;
        private int pos;
        private final int limit;
        ProtoReader(byte[] data) {
            this(data, 0, data.length);
        }
        ProtoReader(byte[] data, int offset, int length) {
            this.data = data;
            this.pos = offset;
            this.limit = offset + length;
        }
        boolean isAtEnd() {
            return pos >= limit;
        }
        int readTag() throws IOException {
            if (isAtEnd()) {
                return 0;
            }
            return (int) readVarint64();
        }
        void skipField(int wireType) throws IOException {
            switch (wireType) {
                case 0 -> readVarint64();
                case 1 -> pos += 8;
                case 2 -> {
                    int len = (int) readVarint64();
                    pos += len;
                }
                case 5 -> pos += 4;
                default -> throw new IOException("Unsupported wire type: " + wireType);
            }
            if (pos > limit) {
                throw new IOException("Truncated protobuf field");
            }
        }
        String readString(int wireType) throws IOException {
            byte[] bytes = readBytes(wireType);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        byte[] readBytes(int wireType) throws IOException {
            if (wireType != 2) {
                throw new IOException("Expected length-delimited field");
            }
            int len = (int) readVarint64();
            if (len < 0 || pos + len > limit) {
                throw new IOException("Invalid length");
            }
            byte[] out = Arrays.copyOfRange(data, pos, pos + len);
            pos += len;
            return out;
        }
        int readInt32(int wireType) throws IOException {
            if (wireType != 0) {
                throw new IOException("Expected varint field");
            }
            return (int) readVarint64();
        }
        long readInt64(int wireType) throws IOException {
            if (wireType != 0) {
                throw new IOException("Expected varint field");
            }
            return readVarint64();
        }
        long readSInt64(int wireType) throws IOException {
            if (wireType != 0) {
                throw new IOException("Expected varint field");
            }
            long n = readVarint64();
            return decodeZigZag64(n);
        }
        int readUInt32Packed() throws IOException {
            return (int) readVarint64();
        }
        long readSInt64Packed() throws IOException {
            long n = readVarint64();
            return decodeZigZag64(n);
        }
        private long readVarint64() throws IOException {
            long result = 0;
            int shift = 0;
            while (shift < 64) {
                if (pos >= limit) {
                    throw new IOException("Truncated varint");
                }
                int b = data[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            throw new IOException("Varint too long");
        }
        private static long decodeZigZag64(long n) {
            return (n >>> 1) ^ -(n & 1);
        }
    }
    private static final class LongHashSet {
        private static final long EMPTY = Long.MIN_VALUE;
        private long[] table;
        private int size;
        private int mask;
        private int resizeAt;
        LongHashSet(int capacity) {
            int cap = 1;
            while (cap < capacity) {
                cap <<= 1;
            }
            table = new long[cap];
            Arrays.fill(table, EMPTY);
            mask = cap - 1;
            resizeAt = (int) (cap * 0.7);
        }
        int size() {
            return size;
        }
        boolean contains(long key) {
            int idx = mix(key) & mask;
            while (true) {
                long cur = table[idx];
                if (cur == EMPTY) {
                    return false;
                }
                if (cur == key) {
                    return true;
                }
                idx = (idx + 1) & mask;
            }
        }
        void add(long key) {
            if (size >= resizeAt) {
                rehash(table.length << 1);
            }
            int idx = mix(key) & mask;
            while (true) {
                long cur = table[idx];
                if (cur == EMPTY) {
                    table[idx] = key;
                    size++;
                    return;
                }
                if (cur == key) {
                    return;
                }
                idx = (idx + 1) & mask;
            }
        }
        private void rehash(int newCap) {
            long[] old = table;
            table = new long[newCap];
            Arrays.fill(table, EMPTY);
            mask = newCap - 1;
            resizeAt = (int) (newCap * 0.7);
            size = 0;
            for (long k : old) {
                if (k != EMPTY) {
                    add(k);
                }
            }
        }
        private static int mix(long x) {
            long z = x * 0x9E3779B97F4A7C15L;
            return (int) (z ^ (z >>> 32));
        }
    }
    private static final class LongIntHashMap {
        private static final long EMPTY = Long.MIN_VALUE;
        private long[] keys;
        private int[] values;
        private int size;
        private int mask;
        private int resizeAt;
        LongIntHashMap(int capacity) {
            int cap = 1;
            while (cap < capacity) {
                cap <<= 1;
            }
            keys = new long[cap];
            values = new int[cap];
            Arrays.fill(keys, EMPTY);
            mask = cap - 1;
            resizeAt = (int) (cap * 0.7);
        }
        boolean containsKey(long key) {
            int idx = mix(key) & mask;
            while (true) {
                long cur = keys[idx];
                if (cur == EMPTY) {
                    return false;
                }
                if (cur == key) {
                    return true;
                }
                idx = (idx + 1) & mask;
            }
        }
        int getOrDefault(long key, int defaultValue) {
            int idx = mix(key) & mask;
            while (true) {
                long cur = keys[idx];
                if (cur == EMPTY) {
                    return defaultValue;
                }
                if (cur == key) {
                    return values[idx];
                }
                idx = (idx + 1) & mask;
            }
        }
        void put(long key, int value) {
            if (size >= resizeAt) {
                rehash(keys.length << 1);
            }
            int idx = mix(key) & mask;
            while (true) {
                long cur = keys[idx];
                if (cur == EMPTY) {
                    keys[idx] = key;
                    values[idx] = value;
                    size++;
                    return;
                }
                if (cur == key) {
                    values[idx] = value;
                    return;
                }
                idx = (idx + 1) & mask;
            }
        }
        private void rehash(int newCap) {
            long[] oldKeys = keys;
            int[] oldVals = values;
            keys = new long[newCap];
            values = new int[newCap];
            Arrays.fill(keys, EMPTY);
            mask = newCap - 1;
            resizeAt = (int) (newCap * 0.7);
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                long k = oldKeys[i];
                if (k != EMPTY) {
                    put(k, oldVals[i]);
                }
            }
        }
        private static int mix(long x) {
            long z = x * 0x9E3779B97F4A7C15L;
            return (int) (z ^ (z >>> 32));
        }
    }
}