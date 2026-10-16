package districts.generator;

import districts.model.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;

/** Builds a sparse national rail system between the 64 district capital cities. */
final class RailNetworkBuilder {
    private static final int DISTRICT_COUNT = 64;
    private static final int MAX_VALUE_SHORTCUTS = 10;
    private static final double MAX_CORRIDOR_STOP_OFFSET = 0.15;
    private static final double MAX_CORRIDOR_DETOUR_RATIO = 1.025;

    private record Node(int districtId, double x, double y, double importance) {}
    private record Candidate(int from, int to, double distance, double constructionScore) {}
    private record Segment(int from, int to, double distance, String kind,
                           double stressBefore, double valueScore) {}
    private record StopCandidate(int node, double projection, double offset) {}
    private record ServiceRoute(int id, Segment service, List<Integer> stops,
                                double trackLength, double maximumOffset) {}
    private record Link(int to, double length) {}
    private record QueueEntry(int node, double distance) {}

    private RailNetworkBuilder() {}

    static void generate(World world) {
        if (world.rails == null) world.rails = new ArrayList<>();
        else world.rails.clear();

        List<Node> nodes = new ArrayList<>(DISTRICT_COUNT);
        for (int districtId = 1; districtId <= DISTRICT_COUNT; districtId++) {
            World.District district = world.districtById(districtId);
            World.Station capital = district.capitalStation();
            if (capital == null) throw new IllegalStateException("District " + districtId + " has no capital city.");
            double importance = importance(districtId);
            district.metadata.put("railImportance", format(importance));
            district.metadata.put("railPriorityRank", Integer.toString(districtId));
            nodes.add(new Node(districtId, capital.x, capital.y, importance));
        }

        List<Candidate> candidates = allCandidates(nodes);
        List<Segment> segments = new ArrayList<>();
        Set<String> edges = new HashSet<>();
        int[] degree = new int[DISTRICT_COUNT];
        DisjointSet components = new DisjointSet(DISTRICT_COUNT);

        addDistrictOneFarReach(nodes, segments, edges, degree, components);
        candidates.stream().sorted(Comparator.comparingDouble(Candidate::constructionScore)
                        .thenComparingDouble(Candidate::distance))
                .forEach(candidate -> {
                    if (components.connected(candidate.from, candidate.to)) return;
                    if (degree[candidate.from] >= backboneDegreeCap(candidate.from + 1)
                            || degree[candidate.to] >= backboneDegreeCap(candidate.to + 1)) return;
                    addSegment(segments, edges, degree, components, candidate,
                            "national-backbone", 0.0, 0.0);
                });

        // A second pass guarantees connectivity if the preferred degree caps blocked the final component.
        candidates.stream().sorted(Comparator.comparingDouble(Candidate::constructionScore)
                        .thenComparingDouble(Candidate::distance))
                .forEach(candidate -> {
                    if (!components.connected(candidate.from, candidate.to)) {
                        addSegment(segments, edges, degree, components, candidate,
                                "national-backbone", 0.0, 0.0);
                    }
                });
        if (segments.size() != DISTRICT_COUNT - 1) {
            throw new IllegalStateException("Rail backbone did not form a spanning tree.");
        }

        int valueShortcuts = addValueShortcuts(nodes, candidates, segments, edges, degree);
        int accessibilityLinks = ensureDistrictOneIsMostAccessible(nodes, candidates, segments, edges, degree);
        writeRailsAndMetadata(world, nodes, segments, degree, valueShortcuts, accessibilityLinks);
    }

    private static List<Candidate> allCandidates(List<Node> nodes) {
        List<Candidate> candidates = new ArrayList<>();
        for (int from = 0; from < nodes.size(); from++) {
            for (int to = from + 1; to < nodes.size(); to++) {
                Node a = nodes.get(from);
                Node b = nodes.get(to);
                double distance = Math.hypot(a.x - b.x, a.y - b.y);
                double score = distance / Math.sqrt(a.importance * b.importance);
                candidates.add(new Candidate(from, to, distance, score));
            }
        }
        return candidates;
    }

    private static void addDistrictOneFarReach(List<Node> nodes, List<Segment> segments, Set<String> edges,
                                               int[] degree, DisjointSet components) {
        Node capital = nodes.get(0);
        Candidate[] bestBySector = new Candidate[4];
        double[] scoreBySector = new double[4];
        Arrays.fill(scoreBySector, -Double.MAX_VALUE);
        for (int to = 1; to < nodes.size(); to++) {
            Node other = nodes.get(to);
            double angle = Math.atan2(other.y - capital.y, other.x - capital.x);
            if (angle < 0.0) angle += Math.PI * 2.0;
            int sector = Math.min(3, (int) (angle / (Math.PI / 2.0)));
            double distance = Math.hypot(other.x - capital.x, other.y - capital.y);
            double priorityFactor = 0.72 + 0.28 * other.importance / importance(1);
            double reachScore = distance * priorityFactor;
            if (reachScore > scoreBySector[sector]) {
                scoreBySector[sector] = reachScore;
                bestBySector[sector] = new Candidate(0, to, distance,
                        distance / Math.sqrt(capital.importance * other.importance));
            }
        }
        for (Candidate candidate : bestBySector) {
            if (candidate != null && !components.connected(candidate.from, candidate.to)) {
                addSegment(segments, edges, degree, components, candidate,
                        "district-1-far-reach", 0.0, scoreBySector[sectorOf(nodes, candidate.to)]);
            }
        }
    }

    private static int sectorOf(List<Node> nodes, int nodeIndex) {
        Node capital = nodes.get(0);
        Node other = nodes.get(nodeIndex);
        double angle = Math.atan2(other.y - capital.y, other.x - capital.x);
        if (angle < 0.0) angle += Math.PI * 2.0;
        return Math.min(3, (int) (angle / (Math.PI / 2.0)));
    }

    private static int addValueShortcuts(List<Node> nodes, List<Candidate> candidates, List<Segment> segments,
                                         Set<String> edges, int[] degree) {
        RailGraph graph = new RailGraph(nodes.size(), segments);
        List<Segment> proposals = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (edges.contains(edgeKey(candidate.from, candidate.to))) continue;
            double route = graph.shortestDistance(candidate.from, candidate.to);
            double stress = (route - candidate.distance) / candidate.distance;
            double value = stress * Math.sqrt(nodes.get(candidate.from).importance * nodes.get(candidate.to).importance);
            if (stress >= 0.70 && value >= 1.80) {
                proposals.add(new Segment(candidate.from, candidate.to, candidate.distance,
                        "high-value-express", stress, value));
            }
        }
        proposals.sort(Comparator.comparingDouble(Segment::valueScore).reversed()
                .thenComparingDouble(Segment::distance));

        int accepted = 0;
        for (Segment proposal : proposals) {
            if (accepted >= MAX_VALUE_SHORTCUTS) break;
            if (edges.contains(edgeKey(proposal.from, proposal.to))) continue;
            if (degree[proposal.from] >= finalDegreeCap(proposal.from + 1)
                    || degree[proposal.to] >= finalDegreeCap(proposal.to + 1)) continue;
            double currentRoute = graph.shortestDistance(proposal.from, proposal.to);
            double stress = (currentRoute - proposal.distance) / proposal.distance;
            double value = stress * Math.sqrt(nodes.get(proposal.from).importance * nodes.get(proposal.to).importance);
            if (stress < 0.70 || value < 1.80) continue;
            Segment acceptedSegment = new Segment(proposal.from, proposal.to, proposal.distance,
                    "high-value-express", stress, value);
            segments.add(acceptedSegment);
            edges.add(edgeKey(proposal.from, proposal.to));
            degree[proposal.from]++;
            degree[proposal.to]++;
            graph.add(acceptedSegment);
            accepted++;
        }
        return accepted;
    }

    private static int ensureDistrictOneIsMostAccessible(List<Node> nodes, List<Candidate> candidates,
                                                          List<Segment> segments, Set<String> edges, int[] degree) {
        RailGraph graph = new RailGraph(nodes.size(), segments);
        int added = 0;
        while (!districtOneIsMostAccessible(graph) && degree[0] < finalDegreeCap(1)) {
            Candidate best = null;
            double bestScore = -Double.MAX_VALUE;
            for (Candidate candidate : candidates) {
                if (candidate.from != 0 || edges.contains(edgeKey(candidate.from, candidate.to))) continue;
                double route = graph.shortestDistance(0, candidate.to);
                double stress = (route - candidate.distance) / candidate.distance;
                double score = stress * nodes.get(candidate.to).importance;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best == null || bestScore <= 0.0) break;
            double route = graph.shortestDistance(0, best.to);
            double stress = (route - best.distance) / best.distance;
            Segment segment = new Segment(0, best.to, best.distance,
                    "district-1-access-express", stress, bestScore);
            segments.add(segment);
            edges.add(edgeKey(0, best.to));
            degree[0]++;
            degree[best.to]++;
            graph.add(segment);
            added++;
        }
        return added;
    }

    private static boolean districtOneIsMostAccessible(RailGraph graph) {
        double districtOneMean = graph.meanDistance(0);
        for (int node = 1; node < DISTRICT_COUNT; node++) {
            if (graph.meanDistance(node) + 1e-8 < districtOneMean) return false;
        }
        return true;
    }

    private static void writeRailsAndMetadata(World world, List<Node> nodes, List<Segment> segments,
                                              int[] degree, int valueShortcuts, int accessibilityLinks) {
        segments.sort(Comparator.comparingInt((Segment segment) -> Math.min(segment.from, segment.to))
                .thenComparingInt(segment -> Math.max(segment.from, segment.to)));
        List<ServiceRoute> services = new ArrayList<>();
        int serviceId = 1;
        for (Segment service : segments) {
            List<Integer> stops = corridorStops(nodes, service);
            double trackLength = routeLength(nodes, stops);
            double maximumOffset = maximumStopOffset(nodes, service, stops);
            services.add(new ServiceRoute(serviceId++, service, stops, trackLength, maximumOffset));
        }

        List<Segment> physicalSegments = new ArrayList<>();
        int[] trackDegree = new int[DISTRICT_COUNT];
        int[] stoppingServices = new int[DISTRICT_COUNT];
        int[] intermediateStops = new int[DISTRICT_COUNT];
        double totalLength = 0.0;
        double directServiceLength = 0.0;
        int intermediateStopTotal = 0;
        int servicesWithStops = 0;
        int railId = 1;
        for (ServiceRoute route : services) {
            Segment service = route.service;
            int intermediateCount = route.stops.size() - 2;
            intermediateStopTotal += intermediateCount;
            if (intermediateCount > 0) servicesWithStops++;
            directServiceLength += service.distance;
            totalLength += route.trackLength;
            String stopSequence = route.stops.stream()
                    .map(index -> Integer.toString(index + 1)).collect(java.util.stream.Collectors.joining(","));
            for (int stopIndex = 0; stopIndex < route.stops.size(); stopIndex++) {
                int node = route.stops.get(stopIndex);
                stoppingServices[node]++;
                if (stopIndex > 0 && stopIndex < route.stops.size() - 1) intermediateStops[node]++;
            }
            for (int segmentIndex = 0; segmentIndex < route.stops.size() - 1; segmentIndex++) {
                int from = route.stops.get(segmentIndex);
                int to = route.stops.get(segmentIndex + 1);
                double distance = distance(nodes.get(from), nodes.get(to));
                trackDegree[from]++;
                trackDegree[to]++;
                physicalSegments.add(new Segment(from, to, distance, service.kind,
                        service.stressBefore, service.valueScore));
                World.Rail rail = new World.Rail(railId++, from + 1, to + 1);
                rail.metadata.put("type", "rail");
                rail.metadata.put("kind", service.kind);
                rail.metadata.put("algorithm", "distance-measured-through-service-v2");
                rail.metadata.put("distance", format(distance));
                rail.metadata.put("constructionCost", format(distance));
                rail.metadata.put("serviceId", Integer.toString(route.id));
                rail.metadata.put("serviceFromDistrict", Integer.toString(service.from + 1));
                rail.metadata.put("serviceToDistrict", Integer.toString(service.to + 1));
                rail.metadata.put("serviceStopSequence", stopSequence);
                rail.metadata.put("intermediateStopCount", Integer.toString(intermediateCount));
                rail.metadata.put("segmentIndex", Integer.toString(segmentIndex + 1));
                rail.metadata.put("segmentCount", Integer.toString(route.stops.size() - 1));
                rail.metadata.put("serviceDirectDistance", format(service.distance));
                rail.metadata.put("serviceTrackLength", format(route.trackLength));
                rail.metadata.put("serviceDetourRatio", format(route.trackLength / service.distance));
                rail.metadata.put("maximumCorridorStopOffset", format(route.maximumOffset));
                rail.metadata.put("throughService", Boolean.toString(intermediateCount > 0));
                rail.metadata.put("serviceFromImportance", format(nodes.get(service.from).importance));
                rail.metadata.put("serviceToImportance", format(nodes.get(service.to).importance));
                if (service.stressBefore > 0.0) {
                    rail.metadata.put("railTravelStressBefore", format(service.stressBefore));
                    rail.metadata.put("travelValueScore", format(service.valueScore));
                }
                world.rails.add(rail);
            }
        }

        RailGraph graph = new RailGraph(nodes.size(), physicalSegments);
        int mostAccessibleDistrict = 1;
        double bestMean = graph.meanDistance(0);
        for (int index = 0; index < nodes.size(); index++) {
            double mean = graph.meanDistance(index);
            World.District district = world.districtById(index + 1);
            district.metadata.put("railDegree", Integer.toString(degree[index]));
            district.metadata.put("railServiceEndpointDegree", Integer.toString(degree[index]));
            district.metadata.put("railStoppingServiceCount", Integer.toString(stoppingServices[index]));
            district.metadata.put("railIntermediateStopCount", Integer.toString(intermediateStops[index]));
            district.metadata.put("railTrackDegree", Integer.toString(trackDegree[index]));
            district.metadata.put("railAverageTravelDistance", format(mean));
            if (mean < bestMean) {
                bestMean = mean;
                mostAccessibleDistrict = index + 1;
            }
        }
        world.metadata.put("railAlgorithm", "importance-weighted-through-services-with-distance-stops-v2");
        world.metadata.put("railCount", Integer.toString(world.rails.size()));
        world.metadata.put("railServiceCount", Integer.toString(services.size()));
        world.metadata.put("railSegmentCount", Integer.toString(world.rails.size()));
        world.metadata.put("railIntermediateStopCount", Integer.toString(intermediateStopTotal));
        world.metadata.put("railServicesWithIntermediateStops", Integer.toString(servicesWithStops));
        world.metadata.put("railBackboneCount", Integer.toString(DISTRICT_COUNT - 1));
        world.metadata.put("railValueShortcutCount", Integer.toString(valueShortcuts));
        world.metadata.put("railDistrictOneAccessibilityLinks", Integer.toString(accessibilityLinks));
        world.metadata.put("railTotalLength", format(totalLength));
        world.metadata.put("railDirectServiceLength", format(directServiceLength));
        world.metadata.put("railStopDetourLength", format(totalLength - directServiceLength));
        world.metadata.put("railStopDetourRatio", format(totalLength / directServiceLength));
        world.metadata.put("railMostAccessibleDistrict", Integer.toString(mostAccessibleDistrict));
        world.metadata.put("railDistrictOneDegree", Integer.toString(degree[0]));
        world.metadata.put("railDistrictOneAverageTravelDistance", format(graph.meanDistance(0)));
    }

    private static List<Integer> corridorStops(List<Node> nodes, Segment service) {
        Node from = nodes.get(service.from);
        Node to = nodes.get(service.to);
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double squaredLength = dx * dx + dy * dy;
        List<StopCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            if (index == service.from || index == service.to) continue;
            Node point = nodes.get(index);
            double projection = ((point.x - from.x) * dx + (point.y - from.y) * dy) / squaredLength;
            if (projection <= 0.08 || projection >= 0.92) continue;
            double closestX = from.x + projection * dx;
            double closestY = from.y + projection * dy;
            double offset = Math.hypot(point.x - closestX, point.y - closestY);
            if (offset <= MAX_CORRIDOR_STOP_OFFSET) {
                candidates.add(new StopCandidate(index, projection, offset));
            }
        }

        List<Integer> best = List.of(service.from, service.to);
        double bestLength = service.distance;
        for (int first = 0; first < candidates.size(); first++) {
            List<Integer> oneStop = orderedStops(service, List.of(candidates.get(first)));
            double oneLength = routeLength(nodes, oneStop);
            if (oneLength / service.distance <= MAX_CORRIDOR_DETOUR_RATIO
                    && (best.size() < oneStop.size()
                    || (best.size() == oneStop.size() && oneLength < bestLength))) {
                best = oneStop;
                bestLength = oneLength;
            }
            for (int second = first + 1; second < candidates.size(); second++) {
                StopCandidate a = candidates.get(first);
                StopCandidate b = candidates.get(second);
                if (Math.abs(a.projection - b.projection) < 0.08) continue;
                List<Integer> twoStops = orderedStops(service, List.of(a, b));
                double twoLength = routeLength(nodes, twoStops);
                if (twoLength / service.distance <= MAX_CORRIDOR_DETOUR_RATIO
                        && (best.size() < twoStops.size()
                        || (best.size() == twoStops.size() && twoLength < bestLength))) {
                    best = twoStops;
                    bestLength = twoLength;
                }
            }
        }
        return best;
    }

    private static List<Integer> orderedStops(Segment service, List<StopCandidate> candidates) {
        List<StopCandidate> ordered = candidates.stream()
                .sorted(Comparator.comparingDouble(StopCandidate::projection)).toList();
        List<Integer> result = new ArrayList<>();
        result.add(service.from);
        for (StopCandidate candidate : ordered) result.add(candidate.node);
        result.add(service.to);
        return List.copyOf(result);
    }

    private static double routeLength(List<Node> nodes, List<Integer> stops) {
        double length = 0.0;
        for (int index = 0; index < stops.size() - 1; index++) {
            length += distance(nodes.get(stops.get(index)), nodes.get(stops.get(index + 1)));
        }
        return length;
    }

    private static double maximumStopOffset(List<Node> nodes, Segment service, List<Integer> stops) {
        if (stops.size() <= 2) return 0.0;
        Node from = nodes.get(service.from);
        Node to = nodes.get(service.to);
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double squaredLength = dx * dx + dy * dy;
        double maximum = 0.0;
        for (int index = 1; index < stops.size() - 1; index++) {
            Node point = nodes.get(stops.get(index));
            double projection = ((point.x - from.x) * dx + (point.y - from.y) * dy) / squaredLength;
            double closestX = from.x + projection * dx;
            double closestY = from.y + projection * dy;
            maximum = Math.max(maximum, Math.hypot(point.x - closestX, point.y - closestY));
        }
        return maximum;
    }

    private static double distance(Node a, Node b) { return Math.hypot(a.x - b.x, a.y - b.y); }

    private static void addSegment(List<Segment> segments, Set<String> edges, int[] degree,
                                   DisjointSet components, Candidate candidate, String kind,
                                   double stress, double value) {
        segments.add(new Segment(candidate.from, candidate.to, candidate.distance, kind, stress, value));
        edges.add(edgeKey(candidate.from, candidate.to));
        degree[candidate.from]++;
        degree[candidate.to]++;
        components.union(candidate.from, candidate.to);
    }

    static double importance(int districtId) {
        double rank = (65.0 - districtId) / 64.0;
        double importance = 1.0 + 4.0 * rank * rank;
        return districtId == 1 ? importance + 4.0 : importance;
    }

    private static int backboneDegreeCap(int districtId) {
        if (districtId == 1) return 7;
        if (districtId <= 8) return 5;
        if (districtId <= 24) return 4;
        if (districtId <= 48) return 3;
        return 2;
    }

    private static int finalDegreeCap(int districtId) {
        return districtId == 1 ? 10 : backboneDegreeCap(districtId);
    }

    private static String edgeKey(int a, int b) {
        return Math.min(a, b) + "|" + Math.max(a, b);
    }

    private static String format(double value) { return String.format(Locale.ROOT, "%.7f", value); }

    private static final class DisjointSet {
        private final int[] parent;
        private final int[] rank;

        DisjointSet(int size) {
            parent = new int[size];
            rank = new int[size];
            for (int i = 0; i < size; i++) parent[i] = i;
        }

        int find(int value) {
            if (parent[value] != value) parent[value] = find(parent[value]);
            return parent[value];
        }

        boolean connected(int a, int b) { return find(a) == find(b); }

        void union(int a, int b) {
            int rootA = find(a);
            int rootB = find(b);
            if (rootA == rootB) return;
            if (rank[rootA] < rank[rootB]) parent[rootA] = rootB;
            else if (rank[rootA] > rank[rootB]) parent[rootB] = rootA;
            else { parent[rootB] = rootA; rank[rootA]++; }
        }
    }

    private static final class RailGraph {
        private final List<List<Link>> adjacency;

        RailGraph(int size, List<Segment> segments) {
            adjacency = new ArrayList<>(size);
            for (int i = 0; i < size; i++) adjacency.add(new ArrayList<>());
            for (Segment segment : segments) add(segment);
        }

        void add(Segment segment) {
            adjacency.get(segment.from).add(new Link(segment.to, segment.distance));
            adjacency.get(segment.to).add(new Link(segment.from, segment.distance));
        }

        double shortestDistance(int from, int to) { return distancesFrom(from)[to]; }

        double meanDistance(int from) {
            double[] distances = distancesFrom(from);
            return Arrays.stream(distances).sum() / (distances.length - 1);
        }

        private double[] distancesFrom(int source) {
            double[] distances = new double[adjacency.size()];
            Arrays.fill(distances, Double.POSITIVE_INFINITY);
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
