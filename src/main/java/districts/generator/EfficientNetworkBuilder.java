package districts.generator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a minimum-length connected tree, then shortens eligible branches with shared junctions. */
final class EfficientNetworkBuilder {
    record Terminal(String key, double x, double y, double priority) {
        Terminal {
            if (priority <= 0.0) throw new IllegalArgumentException("Terminal priority must be positive.");
        }
    }
    record JunctionSpec(int id, String key, double x, double y, double savedLength) {}
    record SegmentSpec(String fromNode, String toNode, double length) {}
    record Network(List<JunctionSpec> junctions, List<SegmentSpec> segments,
                   double minimumPossibleLength, double priorityTreeLength, double optimizedLength) {}

    /** Decides what a straight link costs to build and where shared junctions may go. */
    interface Routing {
        /** Construction cost of a straight link (its length when unobstructed). */
        double cost(double ax, double ay, double bx, double by);

        /** Whether a junction at (x, y) may replace direct links to the given endpoints. */
        boolean junctionAllowed(double x, double y, List<double[]> endpoints);

        Routing GEOMETRIC = new Routing() {
            @Override public double cost(double ax, double ay, double bx, double by) {
                return Math.hypot(ax - bx, ay - by);
            }

            @Override public boolean junctionAllowed(double x, double y, List<double[]> endpoints) { return true; }
        };
    }

    private record Edge(String from, String to) {}
    private record Candidate(String center, List<String> neighbors, List<String> incidentEdges,
                             double x, double y, double saving) {}
    private record Point(double x, double y) {}

    private EfficientNetworkBuilder() {}

    static Network build(List<Terminal> terminals) { return build(terminals, Routing.GEOMETRIC); }

    static Network build(List<Terminal> terminals, Routing routing) {
        if (terminals.size() < 2) throw new IllegalArgumentException("A route network needs at least two terminals.");
        Map<String, Point> points = new LinkedHashMap<>();
        for (Terminal terminal : terminals) {
            if (points.put(terminal.key, new Point(terminal.x, terminal.y)) != null) {
                throw new IllegalArgumentException("Duplicate terminal key: " + terminal.key);
            }
        }

        List<Edge> absoluteMinimum = minimumSpanningTree(terminals, false, routing);
        double minimumPossibleLength = totalLength(absoluteMinimum, points);
        List<Edge> mst = minimumSpanningTree(terminals, true, routing);
        double mstLength = totalLength(mst, points);
        Map<String, Set<String>> adjacency = adjacency(mst);
        List<Candidate> candidates = new ArrayList<>();

        for (Terminal terminal : terminals) {
            List<String> neighbors = new ArrayList<>(adjacency.getOrDefault(terminal.key, Set.of()));
            if (neighbors.size() < 2) continue;
            List<Point> localPoints = new ArrayList<>();
            localPoints.add(points.get(terminal.key));
            for (String neighbor : neighbors) localPoints.add(points.get(neighbor));
            Point junction = geometricMedian(localPoints);
            double original = 0.0;
            for (String neighbor : neighbors) original += distance(points.get(terminal.key), points.get(neighbor));
            double proposed = distance(points.get(terminal.key), junction);
            for (String neighbor : neighbors) proposed += distance(points.get(neighbor), junction);
            double saving = original - proposed;
            if (saving <= Math.max(1e-8, original * 0.015)) continue;
            List<double[]> endpoints = new ArrayList<>();
            endpoints.add(new double[]{terminal.x, terminal.y});
            for (String neighbor : neighbors) endpoints.add(new double[]{points.get(neighbor).x, points.get(neighbor).y});
            if (!routing.junctionAllowed(junction.x, junction.y, endpoints)) continue;

            List<String> incidentEdges = new ArrayList<>();
            for (String neighbor : neighbors) incidentEdges.add(edgeKey(terminal.key, neighbor));
            candidates.add(new Candidate(terminal.key, neighbors, incidentEdges,
                    junction.x, junction.y, saving));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::saving).reversed());

        Map<String, Edge> currentEdges = new LinkedHashMap<>();
        for (Edge edge : mst) currentEdges.put(edgeKey(edge.from, edge.to), edge);
        List<JunctionSpec> junctions = new ArrayList<>();
        int junctionId = 1;

        for (Candidate candidate : candidates) {
            if (!candidate.incidentEdges.stream().allMatch(currentEdges::containsKey)) continue;
            for (String key : candidate.incidentEdges) currentEdges.remove(key);

            String junctionKey = "junction:" + junctionId;
            Point junctionPoint = new Point(candidate.x, candidate.y);
            points.put(junctionKey, junctionPoint);
            junctions.add(new JunctionSpec(junctionId, junctionKey,
                    candidate.x, candidate.y, candidate.saving));

            Edge centerEdge = new Edge(candidate.center, junctionKey);
            currentEdges.put(edgeKey(centerEdge.from, centerEdge.to), centerEdge);
            for (String neighbor : candidate.neighbors) {
                Edge branch = new Edge(neighbor, junctionKey);
                currentEdges.put(edgeKey(branch.from, branch.to), branch);
            }
            junctionId++;
        }

        List<SegmentSpec> segments = currentEdges.values().stream()
                .map(edge -> new SegmentSpec(edge.from, edge.to,
                        distance(points.get(edge.from), points.get(edge.to))))
                .sorted(Comparator.comparing(SegmentSpec::fromNode).thenComparing(SegmentSpec::toNode))
                .toList();
        double optimizedLength = segments.stream().mapToDouble(SegmentSpec::length).sum();
        return new Network(List.copyOf(junctions), segments,
                minimumPossibleLength, mstLength, optimizedLength);
    }

    private static List<Edge> minimumSpanningTree(List<Terminal> terminals, boolean usePriority, Routing routing) {
        int count = terminals.size();
        boolean[] included = new boolean[count];
        double[] bestDistance = new double[count];
        int[] parent = new int[count];
        Arrays.fill(bestDistance, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);
        bestDistance[0] = 0.0;
        List<Edge> edges = new ArrayList<>(count - 1);

        for (int step = 0; step < count; step++) {
            int selected = -1;
            for (int index = 0; index < count; index++) {
                if (!included[index] && (selected < 0 || bestDistance[index] < bestDistance[selected])) {
                    selected = index;
                }
            }
            if (selected < 0) throw new IllegalStateException("Could not complete route tree.");
            included[selected] = true;
            if (parent[selected] >= 0) {
                edges.add(new Edge(terminals.get(parent[selected]).key, terminals.get(selected).key));
            }

            for (int candidate = 0; candidate < count; candidate++) {
                if (included[candidate]) continue;
                double distance = routing.cost(terminals.get(selected).x, terminals.get(selected).y,
                        terminals.get(candidate).x, terminals.get(candidate).y);
                double score = usePriority ? distance / Math.sqrt(
                        terminals.get(selected).priority * terminals.get(candidate).priority) : distance;
                if (score < bestDistance[candidate]) {
                    bestDistance[candidate] = score;
                    parent[candidate] = selected;
                }
            }
        }
        return edges;
    }

    private static Map<String, Set<String>> adjacency(List<Edge> edges) {
        Map<String, Set<String>> result = new HashMap<>();
        for (Edge edge : edges) {
            result.computeIfAbsent(edge.from, ignored -> new LinkedHashSet<>()).add(edge.to);
            result.computeIfAbsent(edge.to, ignored -> new LinkedHashSet<>()).add(edge.from);
        }
        return result;
    }

    private static Point geometricMedian(List<Point> points) {
        double x = points.stream().mapToDouble(Point::x).average().orElseThrow();
        double y = points.stream().mapToDouble(Point::y).average().orElseThrow();
        for (int iteration = 0; iteration < 120; iteration++) {
            double weightedX = 0.0;
            double weightedY = 0.0;
            double totalWeight = 0.0;
            boolean landedOnPoint = false;
            for (Point point : points) {
                double distance = Math.hypot(x - point.x, y - point.y);
                if (distance < 1e-10) {
                    x = point.x;
                    y = point.y;
                    landedOnPoint = true;
                    break;
                }
                double weight = 1.0 / distance;
                weightedX += point.x * weight;
                weightedY += point.y * weight;
                totalWeight += weight;
            }
            if (landedOnPoint) break;
            double nextX = weightedX / totalWeight;
            double nextY = weightedY / totalWeight;
            if (Math.hypot(nextX - x, nextY - y) < 1e-10) {
                x = nextX;
                y = nextY;
                break;
            }
            x = nextX;
            y = nextY;
        }
        return new Point(x, y);
    }

    private static double totalLength(List<Edge> edges, Map<String, Point> points) {
        return edges.stream().mapToDouble(edge -> distance(points.get(edge.from), points.get(edge.to))).sum();
    }

    private static double distance(Point a, Point b) { return Math.hypot(a.x - b.x, a.y - b.y); }

    private static String edgeKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
