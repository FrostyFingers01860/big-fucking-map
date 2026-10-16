package districts.travel;

import districts.model.World;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Finds the fastest journey between any two stations using four public-transport layers:
 * buses on local roads (one route per region), ferries where a road meets water, subways on
 * highways (one line per district), and trains on rail services between district capitals.
 *
 * <p>Every boarding costs the mode's average wait. Every intermediate stop costs a short dwell.
 * The graph separates station "places" from per-line vehicle nodes, so changing lines is only
 * possible by alighting at a station and paying the next line's wait.
 */
public final class TicketPlanner {
    /** Bullet-train cruising speed; the fastest mode. */
    public static final double TRAIN_SPEED_KMH = 300.0;
    /** A straight train ride from one map corner to the opposite corner takes this long. */
    public static final double CORNER_TO_CORNER_TRAIN_HOURS = 58.0;
    /** One map unit (one district cell) represents this many kilometres (about 1,538 km). */
    public static final double KM_PER_MAP_UNIT = TRAIN_SPEED_KMH * CORNER_TO_CORNER_TRAIN_HOURS
            / Math.hypot(World.GRID_SIZE, World.GRID_SIZE);

    public enum Mode {
        BUS("Bus", 50.0, 5.0, 0.5),
        SUBWAY("Subway", 100.0, 3.0, 0.75),
        TRAIN("Train", TRAIN_SPEED_KMH, 8.0, 2.0),
        FERRY("Ferry", 30.0, 15.0, 0.0);

        public final String label;
        public final double speedKmh;
        public final double averageWaitMinutes;
        public final double dwellMinutes;

        Mode(String label, double speedKmh, double averageWaitMinutes, double dwellMinutes) {
            this.label = label;
            this.speedKmh = speedKmh;
            this.averageWaitMinutes = averageWaitMinutes;
            this.dwellMinutes = dwellMinutes;
        }

        double travelMinutes(double mapDistance) { return mapDistance * KM_PER_MAP_UNIT / speedKmh * 60.0; }
    }

    public record StationRef(World.District district, World.Region region, World.Station station) {
        public String label() {
            return station.name + "  —  District " + district.id + ", " + region.name;
        }
    }

    /** One continuous ride on a single vehicle line. */
    public record Leg(Mode mode, String line, String toward, List<StationRef> stops, List<Point2D.Double> path,
                      double waitMinutes, double rideMinutes, double distanceKm) {
        public StationRef boardAt() { return stops.get(0); }
        public StationRef alightAt() { return stops.get(stops.size() - 1); }
        /** Number of stops the passenger rides through, counting the stop where they get off. */
        public int stopsToRide() { return stops.size() - 1; }
    }

    public record Ticket(StationRef from, StationRef to, List<Leg> legs, double totalMinutes, double distanceKm) {
        public int transfers() { return Math.max(0, legs.size() - 1); }
    }

    private enum EdgeKind { BOARD, RIDE, ALIGHT }

    private record Line(Mode mode, String name, List<StationRef> serviceStops) {}

    private record QueueEntry(int node, double cost) {}

    private final List<StationRef> stations = new ArrayList<>();
    private final Map<World.Station, Integer> placeByStation = new IdentityHashMap<>();
    private final List<Line> lines = new ArrayList<>();

    // Node table. Places are station-level waiting areas; vehicle nodes belong to exactly one line.
    private int nodeCount;
    private int[] nodeLine = new int[1024];
    private int[] nodeServiceIndex = new int[1024];
    private double[] nodeX = new double[1024];
    private double[] nodeY = new double[1024];
    private StationRef[] nodeStop = new StationRef[1024];

    // Forward-star adjacency.
    private int edgeCount;
    private int[] head = new int[1024];
    private int[] edgeTo = new int[4096];
    private int[] edgeNext = new int[4096];
    private double[] edgeCost = new double[4096];
    private double[] edgeDistance = new double[4096];
    private EdgeKind[] edgeKind = new EdgeKind[4096];

    public TicketPlanner(World world) {
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                for (World.Station station : region.stations) {
                    StationRef ref = new StationRef(district, region, station);
                    int place = addNode(-1, station.x, station.y, ref, -1);
                    placeByStation.put(station, place);
                    stations.add(ref);
                }
            }
        }
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) buildBusRoute(district, region);
            buildSubway(district);
        }
        buildTrains(world);
    }

    public List<StationRef> stations() { return stations; }

    public StationRef find(World.Station station) {
        Integer place = placeByStation.get(station);
        return place == null ? null : nodeStop[place];
    }

    public Ticket plan(StationRef from, StationRef to) {
        int source = placeByStation.get(from.station());
        int target = placeByStation.get(to.station());
        if (source == target) return new Ticket(from, to, List.of(), 0.0, 0.0);

        double[] best = new double[nodeCount];
        int[] viaEdge = new int[nodeCount];
        int[] viaNode = new int[nodeCount];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        best[source] = 0.0;
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::cost));
        queue.add(new QueueEntry(source, 0.0));
        while (!queue.isEmpty()) {
            QueueEntry entry = queue.remove();
            if (entry.cost > best[entry.node]) continue;
            if (entry.node == target) break;
            for (int edge = head[entry.node]; edge >= 0; edge = edgeNext[edge]) {
                double candidate = entry.cost + edgeCost[edge];
                int next = edgeTo[edge];
                if (candidate < best[next]) {
                    best[next] = candidate;
                    viaEdge[next] = edge;
                    viaNode[next] = entry.node;
                    queue.add(new QueueEntry(next, candidate));
                }
            }
        }
        if (Double.isInfinite(best[target])) return null;

        List<Integer> edges = new ArrayList<>();
        List<Integer> froms = new ArrayList<>();
        for (int node = target; node != source; node = viaNode[node]) {
            edges.add(viaEdge[node]);
            froms.add(viaNode[node]);
        }
        java.util.Collections.reverse(edges);
        java.util.Collections.reverse(froms);
        return toTicket(from, to, edges, froms, best[target]);
    }

    private Ticket toTicket(StationRef from, StationRef to, List<Integer> edges, List<Integer> froms, double total) {
        List<Leg> legs = new ArrayList<>();
        int legLine = -1;
        int firstServiceIndex = -1;
        int lastServiceIndex = -1;
        List<StationRef> stops = null;
        List<Point2D.Double> path = null;
        double legCost = 0.0;
        double legDistance = 0.0;
        double totalDistance = 0.0;
        for (int index = 0; index < edges.size(); index++) {
            int edge = edges.get(index);
            int node = edgeTo[edge];
            switch (edgeKind[edge]) {
                case BOARD -> {
                    legLine = nodeLine[node];
                    firstServiceIndex = nodeServiceIndex[node];
                    lastServiceIndex = firstServiceIndex;
                    stops = new ArrayList<>(List.of(nodeStop[node]));
                    path = new ArrayList<>(List.of(new Point2D.Double(nodeX[node], nodeY[node])));
                    legCost = edgeCost[edge];
                    legDistance = 0.0;
                }
                case RIDE -> {
                    legCost += edgeCost[edge];
                    legDistance += edgeDistance[edge];
                    path.add(new Point2D.Double(nodeX[node], nodeY[node]));
                    if (nodeStop[node] != null) stops.add(nodeStop[node]);
                    if (nodeServiceIndex[node] >= 0) lastServiceIndex = nodeServiceIndex[node];
                }
                case ALIGHT -> {
                    Line line = lines.get(legLine);
                    String toward = toward(line, firstServiceIndex, lastServiceIndex, stops);
                    double wait = line.mode.averageWaitMinutes;
                    double km = legDistance * KM_PER_MAP_UNIT;
                    legs.add(new Leg(line.mode, line.name, toward, List.copyOf(stops), List.copyOf(path),
                            wait, legCost - wait, km));
                    totalDistance += km;
                    legLine = -1;
                }
            }
        }
        return new Ticket(from, to, List.copyOf(legs), total, totalDistance);
    }

    private static String toward(Line line, int firstIndex, int lastIndex, List<StationRef> stops) {
        if (line.serviceStops != null && firstIndex >= 0 && lastIndex >= 0) {
            StationRef terminus = lastIndex >= firstIndex
                    ? line.serviceStops.get(line.serviceStops.size() - 1) : line.serviceStops.get(0);
            return terminus.station.name + " (District " + terminus.district.id + ")";
        }
        return stops.get(stops.size() - 1).station.name;
    }

    private void buildBusRoute(World.District district, World.Region region) {
        int line = addLine(new Line(Mode.BUS, "Bus " + district.id + "-" + region.id, null));
        Map<String, Integer> nodes = new HashMap<>();
        for (World.Station station : region.stations) {
            StationRef ref = nodeStop[placeByStation.get(station)];
            int node = addNode(line, station.x, station.y, ref, -1);
            nodes.put("station:" + station.id, node);
            connectPlace(placeByStation.get(station), node, Mode.BUS);
        }
        for (World.Junction junction : region.roadJunctions) {
            nodes.put("junction:" + junction.id, addNode(line, junction.x, junction.y, null, -1));
        }
        // Ferry ports are bus stops too: passengers get off there to change to the ferry.
        Map<String, Integer> landingPlaces = new HashMap<>();
        for (World.Station station : region.stations) landingPlaces.put("station:" + station.id, placeByStation.get(station));
        if (region.ferryPorts != null) {
            for (World.Junction port : region.ferryPorts) {
                StationRef ref = new StationRef(district, region,
                        new World.Station(-port.id, portName(region, port), World.StationKind.TOWN, false, port.x, port.y));
                int place = addNode(-1, port.x, port.y, ref, -1);
                int node = addNode(line, port.x, port.y, ref, -1);
                connectPlace(place, node, Mode.BUS);
                nodes.put("port:" + port.id, node);
                landingPlaces.put("port:" + port.id, place);
            }
        }
        for (World.Road road : region.roads) {
            Integer a = nodes.get(road.fromNode);
            Integer b = nodes.get(road.toNode);
            if (a != null && b != null) addRide(a, b, Mode.BUS);
        }
        if (region.ferries != null) {
            for (World.Road ferry : region.ferries) {
                Integer fromPlace = landingPlaces.get(ferry.fromNode);
                Integer toPlace = landingPlaces.get(ferry.toNode);
                if (fromPlace == null || toPlace == null) continue;
                StationRef from = nodeStop[fromPlace];
                StationRef to = nodeStop[toPlace];
                int ferryLine = addLine(new Line(Mode.FERRY, "Ferry " + district.id + "-" + region.id + "-" + ferry.id,
                        List.of(from, to)));
                int a = addNode(ferryLine, from.station().x, from.station().y, from, 0);
                int b = addNode(ferryLine, to.station().x, to.station().y, to, 1);
                connectPlace(fromPlace, a, Mode.FERRY);
                connectPlace(toPlace, b, Mode.FERRY);
                addRide(a, b, Mode.FERRY);
            }
        }
    }

    /** Ports are named after the nearest station, e.g. "Erast Hill Ferry Port". */
    private static String portName(World.Region region, World.Junction port) {
        World.Station nearest = region.stations.get(0);
        for (World.Station station : region.stations) {
            if (Math.hypot(station.x - port.x, station.y - port.y) < Math.hypot(nearest.x - port.x, nearest.y - port.y)) {
                nearest = station;
            }
        }
        return nearest.name + " Ferry Port";
    }

    private void buildSubway(World.District district) {
        int line = addLine(new Line(Mode.SUBWAY, "District " + district.id + " Subway", null));
        Map<String, Integer> nodes = new HashMap<>();
        for (World.Region region : district.regions) {
            World.Station hub = hubStation(region);
            if (hub == null) continue;
            int place = placeByStation.get(hub);
            int node = addNode(line, region.x, region.y, nodeStop[place], -1);
            nodes.put("region:" + region.id, node);
            connectPlace(place, node, Mode.SUBWAY);
        }
        for (World.Junction junction : district.highwayJunctions) {
            nodes.put("junction:" + junction.id, addNode(line, junction.x, junction.y, null, -1));
        }
        for (World.Highway highway : district.highways) {
            Integer a = nodes.get(highway.fromNode);
            Integer b = nodes.get(highway.toNode);
            if (a != null && b != null) addRide(a, b, Mode.SUBWAY);
        }
    }

    /** The subway platform of a region sits at its hub: the station placed exactly on the region centre. */
    private static World.Station hubStation(World.Region region) {
        World.Station best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (World.Station station : region.stations) {
            double distance = Math.hypot(station.x - region.x, station.y - region.y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = station;
            }
        }
        return best;
    }

    private void buildTrains(World world) {
        Map<Integer, List<World.Rail>> services = new HashMap<>();
        for (World.Rail rail : world.rails) {
            String serviceId = rail.metadata.get("serviceId");
            int id = serviceId == null ? rail.id : Integer.parseInt(serviceId);
            services.computeIfAbsent(id, ignored -> new ArrayList<>()).add(rail);
        }
        services.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            List<World.Rail> segments = entry.getValue();
            segments.sort(Comparator.comparingInt(rail -> Integer.parseInt(
                    rail.metadata.getOrDefault("segmentIndex", "1"))));
            List<Integer> districts = new ArrayList<>();
            districts.add(segments.get(0).fromDistrictId);
            for (World.Rail rail : segments) districts.add(rail.toDistrictId);
            List<StationRef> serviceStops = new ArrayList<>();
            for (int districtId : districts) {
                World.Station capital = world.capitalStation(districtId);
                serviceStops.add(nodeStop[placeByStation.get(capital)]);
            }
            String name = "Rail Line " + entry.getKey() + "  (" + districts.get(0) + " ⇄ "
                    + districts.get(districts.size() - 1) + ")";
            int line = addLine(new Line(Mode.TRAIN, name, List.copyOf(serviceStops)));
            int previous = -1;
            for (int index = 0; index < serviceStops.size(); index++) {
                StationRef stop = serviceStops.get(index);
                int node = addNode(line, stop.station.x, stop.station.y, stop, index);
                connectPlace(placeByStation.get(stop.station), node, Mode.TRAIN);
                if (previous >= 0) addRide(previous, node, Mode.TRAIN);
                previous = node;
            }
        });
    }

    private int addLine(Line line) {
        lines.add(line);
        return lines.size() - 1;
    }

    private void connectPlace(int place, int vehicleNode, Mode mode) {
        // Boarding pays the average wait. The first departure's dwell is already part of that wait,
        // so it is subtracted here and charged on each ride edge instead (see addRide).
        addEdge(place, vehicleNode, EdgeKind.BOARD, mode.averageWaitMinutes - mode.dwellMinutes, 0.0);
        addEdge(vehicleNode, place, EdgeKind.ALIGHT, 0.0, 0.0);
    }

    private void addRide(int a, int b, Mode mode) {
        double distance = Math.hypot(nodeX[a] - nodeX[b], nodeY[a] - nodeY[b]);
        double travel = mode.travelMinutes(distance);
        addEdge(a, b, EdgeKind.RIDE, travel + (nodeStop[a] != null ? mode.dwellMinutes : 0.0), distance);
        addEdge(b, a, EdgeKind.RIDE, travel + (nodeStop[b] != null ? mode.dwellMinutes : 0.0), distance);
    }

    private int addNode(int line, double x, double y, StationRef stop, int serviceIndex) {
        if (nodeCount == nodeLine.length) {
            int size = nodeCount * 2;
            nodeLine = Arrays.copyOf(nodeLine, size);
            nodeServiceIndex = Arrays.copyOf(nodeServiceIndex, size);
            nodeX = Arrays.copyOf(nodeX, size);
            nodeY = Arrays.copyOf(nodeY, size);
            nodeStop = Arrays.copyOf(nodeStop, size);
            int oldHead = head.length;
            head = Arrays.copyOf(head, size);
            Arrays.fill(head, oldHead, size, -1);
        }
        if (nodeCount == 0) Arrays.fill(head, -1);
        nodeLine[nodeCount] = line;
        nodeServiceIndex[nodeCount] = serviceIndex;
        nodeX[nodeCount] = x;
        nodeY[nodeCount] = y;
        nodeStop[nodeCount] = stop;
        return nodeCount++;
    }

    private void addEdge(int from, int to, EdgeKind kind, double cost, double distance) {
        if (edgeCount == edgeTo.length) {
            int size = edgeCount * 2;
            edgeTo = Arrays.copyOf(edgeTo, size);
            edgeNext = Arrays.copyOf(edgeNext, size);
            edgeCost = Arrays.copyOf(edgeCost, size);
            edgeDistance = Arrays.copyOf(edgeDistance, size);
            edgeKind = Arrays.copyOf(edgeKind, size);
        }
        edgeTo[edgeCount] = to;
        edgeKind[edgeCount] = kind;
        edgeCost[edgeCount] = cost;
        edgeDistance[edgeCount] = distance;
        edgeNext[edgeCount] = head[from];
        head[from] = edgeCount++;
    }

    public static String formatMinutes(double minutes) {
        long rounded = Math.max(1, Math.round(minutes));
        if (rounded < 60) return rounded + " min";
        return String.format(Locale.ROOT, "%d h %02d min", rounded / 60, rounded % 60);
    }
}
