package districts.generator;

import districts.model.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Adds a small number of cheap links where geographic closeness and network detour create high stress. */
final class RoadStressConnector {
    private static final double MIN_BENEFIT_COST = 1.35;

    record Result(int candidateCount, int connectorCount, double maximumStress,
                  double constructionLength, double travelDistanceSaved) {}

    private record Point(double x, double y) {}
    private record Link(int to, double length) {}
    private record QueueEntry(int node, double distance) {}
    private record Candidate(World.Station from, World.Station to, double directDistance,
                             double initialRouteDistance, double initialStress, double priorityScore) {}

    private RoadStressConnector() {}

    static Result addBeneficialConnectors(World.Region region) { return addBeneficialConnectors(region, null); }

    /** With a terrain layout, no connector may cross water; ferries count as part of the existing network. */
    static Result addBeneficialConnectors(World.Region region, TerrainLayout layout) {
        Map<String, Point> points = new LinkedHashMap<>();
        for (World.Station station : region.stations) {
            points.put(stationKey(station), new Point(station.x, station.y));
            station.metadata.remove("roadStressReliefConnections");
            station.metadata.remove("maximumRoadLocationStress");
        }
        for (World.Junction junction : region.roadJunctions) {
            points.put("junction:" + junction.id, new Point(junction.x, junction.y));
        }
        if (region.ferryPorts != null) {
            for (World.Junction port : region.ferryPorts) points.put("port:" + port.id, new Point(port.x, port.y));
        }

        Set<String> directEdges = new HashSet<>();
        List<World.Road> links = new ArrayList<>(region.roads);
        if (region.ferries != null) links.addAll(region.ferries);
        for (World.Road road : links) directEdges.add(edgeKey(road.fromNode, road.toNode));
        Graph graph = new Graph(points, links);
        List<Candidate> candidates = new ArrayList<>();
        double maximumConstructionDistance = region.stationRadius * 0.58;

        for (int i = 0; i < region.stations.size(); i++) {
            World.Station from = region.stations.get(i);
            double[] distances = graph.distancesFrom(stationKey(from));
            for (int j = i + 1; j < region.stations.size(); j++) {
                World.Station to = region.stations.get(j);
                String fromKey = stationKey(from);
                String toKey = stationKey(to);
                if (directEdges.contains(edgeKey(fromKey, toKey))) continue;
                double direct = Math.hypot(from.x - to.x, from.y - to.y);
                if (direct < 1e-7 || direct > maximumConstructionDistance) continue;
                double route = distances[graph.indexOf(toKey)];
                if (!Double.isFinite(route)) continue;
                double stress = (route - direct) / direct;
                if (stress < MIN_BENEFIT_COST) continue;
                if (layout != null && layout.crossesWater(from.x, from.y, to.x, to.y)) continue;
                double priority = Math.sqrt(routePriority(from) * routePriority(to));
                candidates.add(new Candidate(from, to, direct, route, stress, stress * priority));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::priorityScore).reversed()
                .thenComparingDouble(Candidate::directDistance));

        int maximumConnectors = Math.max(1, Math.min(8, (int) Math.round(region.stations.size() / 14.0)));
        int roadId = region.roads.stream().mapToInt(road -> road.id).max().orElse(0) + 1;
        int accepted = 0;
        double maximumStress = 0.0;
        double constructionLength = 0.0;
        double travelSaved = 0.0;

        for (Candidate candidate : candidates) {
            if (accepted >= maximumConnectors) break;
            String fromKey = stationKey(candidate.from);
            String toKey = stationKey(candidate.to);
            if (directEdges.contains(edgeKey(fromKey, toKey))) continue;
            double currentRoute = graph.shortestDistance(fromKey, toKey);
            double currentStress = (currentRoute - candidate.directDistance) / candidate.directDistance;
            if (currentStress < MIN_BENEFIT_COST) continue;

            World.Road road = new World.Road(roadId++, fromKey, toKey);
            road.metadata.put("type", "road");
            road.metadata.put("kind", "location-stress-relief");
            road.metadata.put("algorithm", "benefit-cost-location-stress-v1");
            road.metadata.put("distance", format(candidate.directDistance));
            road.metadata.put("roadLocationStress", format(currentStress));
            road.metadata.put("travelDistanceBefore", format(currentRoute));
            road.metadata.put("travelDistanceAfter", format(candidate.directDistance));
            road.metadata.put("travelDistanceSaved", format(currentRoute - candidate.directDistance));
            road.metadata.put("benefitCostRatio", format(currentStress));
            boolean servesCities = candidate.from.kind == World.StationKind.CITY
                    || candidate.to.kind == World.StationKind.CITY;
            boolean servesTowns = candidate.from.kind == World.StationKind.TOWN
                    || candidate.to.kind == World.StationKind.TOWN;
            road.metadata.put("servesCities", Boolean.toString(servesCities));
            road.metadata.put("servesTowns", Boolean.toString(servesTowns));
            road.metadata.put("routeUse", servesCities && servesTowns ? "shared"
                    : servesCities ? "city" : "town");
            region.roads.add(road);
            directEdges.add(edgeKey(fromKey, toKey));
            graph.addEdge(fromKey, toKey, candidate.directDistance);
            recordStationRelief(candidate.from, currentStress);
            recordStationRelief(candidate.to, currentStress);
            accepted++;
            maximumStress = Math.max(maximumStress, currentStress);
            constructionLength += candidate.directDistance;
            travelSaved += currentRoute - candidate.directDistance;
        }

        return new Result(candidates.size(), accepted, maximumStress, constructionLength, travelSaved);
    }

    private static void recordStationRelief(World.Station station, double stress) {
        int count = Integer.parseInt(station.metadata.getOrDefault("roadStressReliefConnections", "0"));
        double maximum = Double.parseDouble(station.metadata.getOrDefault("maximumRoadLocationStress", "0"));
        station.metadata.put("roadStressReliefConnections", Integer.toString(count + 1));
        station.metadata.put("maximumRoadLocationStress", format(Math.max(maximum, stress)));
    }

    private static double routePriority(World.Station station) {
        return station.capital ? 1.48 : station.kind == World.StationKind.CITY ? 1.24 : 1.0;
    }

    private static String stationKey(World.Station station) { return "station:" + station.id; }

    private static String edgeKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static String format(double value) { return String.format(Locale.ROOT, "%.7f", value); }

    private static final class Graph {
        private final List<String> keys;
        private final Map<String, Integer> indexes = new HashMap<>();
        private final List<List<Link>> adjacency;

        Graph(Map<String, Point> points, List<World.Road> roads) {
            keys = new ArrayList<>(points.keySet());
            adjacency = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                indexes.put(keys.get(i), i);
                adjacency.add(new ArrayList<>());
            }
            for (World.Road road : roads) {
                Point from = points.get(road.fromNode);
                Point to = points.get(road.toNode);
                addEdge(road.fromNode, road.toNode, Math.hypot(from.x - to.x, from.y - to.y));
            }
        }

        int indexOf(String key) { return indexes.get(key); }

        void addEdge(String fromKey, String toKey, double length) {
            int from = indexes.get(fromKey);
            int to = indexes.get(toKey);
            adjacency.get(from).add(new Link(to, length));
            adjacency.get(to).add(new Link(from, length));
        }

        double shortestDistance(String from, String to) {
            return distancesFrom(from)[indexOf(to)];
        }

        double[] distancesFrom(String fromKey) {
            double[] distances = new double[keys.size()];
            Arrays.fill(distances, Double.POSITIVE_INFINITY);
            int source = indexOf(fromKey);
            distances[source] = 0.0;
            PriorityQueue<QueueEntry> queue = new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::distance));
            queue.add(new QueueEntry(source, 0.0));
            while (!queue.isEmpty()) {
                QueueEntry current = queue.remove();
                if (current.distance > distances[current.node] + 1e-12) continue;
                for (Link link : adjacency.get(current.node)) {
                    double next = current.distance + link.length;
                    if (next + 1e-12 < distances[link.to]) {
                        distances[link.to] = next;
                        queue.add(new QueueEntry(link.to, next));
                    }
                }
            }
            return distances;
        }
    }
}
