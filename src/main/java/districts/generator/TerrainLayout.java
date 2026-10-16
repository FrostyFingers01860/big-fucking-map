package districts.generator;

import districts.model.World;
import districts.terrain.TerrainMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Terrain rules for placing regions and stations on land and keeping roads off water. */
final class TerrainLayout {
    /** Region spacing is stretched 10% so stations can spread further across each district. */
    static final double REGION_STRETCH = 1.10;
    /** The capital city keeps at least this much land (world units, ~90 km) between it and any water. */
    static final double CAPITAL_WATER_CLEARANCE = 0.06;
    /** Two ferry ports this close together (~6 km) on the same shore are shared instead of duplicated. */
    static final double PORT_MERGE_DISTANCE = 0.004;
    /** Ferries up to this long (~30 km) are "short hops"; longer crossings are built only when unavoidable. */
    static final double SHORT_FERRY = 0.02;
    /** A road stops this far (~0.9 km) before the waterline, where its ferry port stands. */
    private static final double PORT_SETBACK = 0.0006;
    /** If a station is already within this distance of the waterline it serves as its own ferry landing. */
    private static final double SELF_PORT_DISTANCE = 0.0012;

    private final TerrainMap terrain;

    TerrainLayout(TerrainMap terrain) { this.terrain = terrain; }

    TerrainMap terrain() { return terrain; }

    // ------------------------------------------------------------------ water tests

    /** True when the whole segment is certainly dry, judged from the distance-to-water raster alone. */
    boolean certainlyDry(double ax, double ay, double bx, double by) {
        double length = Math.hypot(bx - ax, by - ay);
        double margin = 0.012;
        return terrain.distanceToWater(ax, ay) - margin >= length
                || terrain.distanceToWater(bx, by) - margin >= length
                || terrain.distanceToWater((ax + bx) / 2, (ay + by) / 2) - margin >= length / 2;
    }

    boolean crossesWater(double ax, double ay, double bx, double by) {
        return !certainlyDry(ax, ay, bx, by) && terrain.crossesWater(ax, ay, bx, by);
    }

    List<TerrainMap.WaterRun> waterRuns(double ax, double ay, double bx, double by) {
        return certainlyDry(ax, ay, bx, by) ? List.of() : terrain.waterRuns(ax, ay, bx, by);
    }

    /** Road costs: water must be crossed by ferry, which costs far more than building road. */
    EfficientNetworkBuilder.Routing roadRouting() {
        return new EfficientNetworkBuilder.Routing() {
            @Override public double cost(double ax, double ay, double bx, double by) {
                double length = Math.hypot(ax - bx, ay - by);
                List<TerrainMap.WaterRun> runs = waterRuns(ax, ay, bx, by);
                if (runs.isEmpty()) return length;
                double cost = length;
                for (TerrainMap.WaterRun run : runs) {
                    double water = (run.end() - run.start()) * length;
                    cost += 0.02 + water * 3.0;                       // two ports plus the slow crossing
                    if (water > SHORT_FERRY) cost += water * 40.0;    // long ferries only when unavoidable
                }
                return cost;
            }

            @Override public boolean junctionAllowed(double x, double y, List<double[]> endpoints) {
                if (terrain.isWater(x, y)) return false;
                for (double[] endpoint : endpoints) {
                    if (crossesWater(x, y, endpoint[0], endpoint[1])) return false;
                }
                return true;
            }
        };
    }

    // ------------------------------------------------------------------ region placement

    record RegionNode(double x, double y, int parent, int depth) {}

    /** Grows a district's region tree on land. The first node is the capital, kept well away from water. */
    List<RegionNode> growRegionTree(int column, int row, int count, Random random) {
        List<RegionNode> nodes = new ArrayList<>();
        nodes.add(capitalNode(column, row, random));
        int[] degree = new int[count];
        double minimumSpacing = 0.055 * REGION_STRETCH;

        for (int i = 1; i < count; i++) {
            RegionNode best = null;
            double bestScore = -Double.MAX_VALUE;
            for (int attempt = 0; attempt < 420; attempt++) {
                int parentIndex = chooseParent(nodes, degree, random);
                RegionNode parent = nodes.get(parentIndex);
                double angle = random.nextDouble() * Math.PI * 2.0;
                double step = (0.075 + random.nextDouble() * 0.12) * REGION_STRETCH;
                double x = parent.x + Math.cos(angle) * step;
                double y = parent.y + Math.sin(angle) * step;
                if (x < column + 0.06 || x > column + 0.94 || y < row + 0.06 || y > row + 0.94) continue;
                if (terrain.isWater(x, y)) continue;
                double nearest = nodes.stream().mapToDouble(n -> Math.hypot(n.x - x, n.y - y)).min().orElse(1.0);
                double score = nearest + Math.hypot(x - nodes.get(0).x, y - nodes.get(0).y) * 0.06;
                if (nearest >= minimumSpacing) {
                    best = new RegionNode(x, y, parentIndex, parent.depth + 1);
                    break;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = new RegionNode(x, y, parentIndex, parent.depth + 1);
                }
            }
            if (best == null) {
                double[] point = randomLand(column, row, 0.08, random);
                int parent = nearestNode(nodes, point[0], point[1]);
                best = new RegionNode(point[0], point[1], parent, nodes.get(parent).depth + 1);
            }
            degree[best.parent]++;
            degree[i]++;
            nodes.add(best);
        }
        return nodes;
    }

    private RegionNode capitalNode(int column, int row, Random random) {
        for (int attempt = 0; attempt < 600; attempt++) {
            double x = column + 0.20 + random.nextDouble() * 0.60;
            double y = row + 0.20 + random.nextDouble() * 0.60;
            if (!terrain.isWater(x, y) && terrain.distanceToWater(x, y) >= CAPITAL_WATER_CLEARANCE) {
                return new RegionNode(x, y, -1, 0);
            }
        }
        // Water-heavy district: take the inland-most point instead.
        double bestX = column + 0.5;
        double bestY = row + 0.5;
        double bestClearance = -1;
        for (int iy = 0; iy <= 40; iy++) {
            for (int ix = 0; ix <= 40; ix++) {
                double x = column + 0.12 + ix * 0.76 / 40;
                double y = row + 0.12 + iy * 0.76 / 40;
                if (terrain.isWater(x, y)) continue;
                double clearance = terrain.distanceToWater(x, y);
                if (clearance > bestClearance) {
                    bestClearance = clearance;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new RegionNode(bestX, bestY, -1, 0);
    }

    private double[] randomLand(int column, int row, double margin, Random random) {
        for (int attempt = 0; attempt < 5000; attempt++) {
            double x = column + margin + random.nextDouble() * (1 - 2 * margin);
            double y = row + margin + random.nextDouble() * (1 - 2 * margin);
            if (!terrain.isWater(x, y)) return new double[]{x, y};
        }
        throw new IllegalStateException("District at " + column + "," + row + " has no usable land");
    }

    private static int chooseParent(List<RegionNode> nodes, int[] degree, Random random) {
        int totalWeight = 0;
        for (int i = 0; i < nodes.size(); i++) totalWeight += Math.max(1, 5 - degree[i]);
        int pick = random.nextInt(totalWeight);
        for (int i = 0; i < nodes.size(); i++) {
            pick -= Math.max(1, 5 - degree[i]);
            if (pick < 0) return i;
        }
        return nodes.size() - 1;
    }

    private static int nearestNode(List<RegionNode> nodes, double x, double y) {
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            double distance = Math.hypot(nodes.get(i).x - x, nodes.get(i).y - y);
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    static double stationRadius(double nearestRegion) {
        return Math.max(0.025 * REGION_STRETCH, Math.min(0.085 * REGION_STRETCH, nearestRegion * 0.38 * REGION_STRETCH));
    }

    // ------------------------------------------------------------------ station placement

    record Placement(double x, double y, boolean movedToShore) {}

    /**
     * Keeps a proposed station on land. A station that would stand in water is moved to the nearest
     * shore inside its district; stations are never attracted to water otherwise.
     */
    Placement placeOnLand(double x, double y, World.District district, World.Region region) {
        if (!terrain.isWater(x, y)) return new Placement(x, y, false);
        double minX = district.column + 0.015;
        double minY = district.row + 0.015;
        double maxX = district.column + 0.985;
        double maxY = district.row + 0.985;
        double[] shore = terrain.nearestShore(x, y, 0.35, minX, minY, maxX, maxY);
        if (shore != null) return new Placement(shore[0], shore[1], true);
        return new Placement(region.x, region.y, true);
    }

    /** Terrain beneath a station, kept as metadata for later use. */
    void recordTerrain(World.Station station, boolean movedToShore) {
        TerrainMap.Sample sample = terrain.sample(station.x, station.y);
        station.metadata.put("terrainBiome", sample.biomeName());
        station.metadata.put("terrainBiomeId", Integer.toString(sample.biomeId()));
        station.metadata.put("terrainHeight", Integer.toString(sample.height()));
        station.metadata.put("terrainRiver", Boolean.toString(sample.river()));
        station.metadata.put("terrainCoastalCell", Boolean.toString(sample.coastal()));
        station.metadata.put("terrainDistanceToWater",
                String.format(Locale.ROOT, "%.5f", terrain.distanceToWater(station.x, station.y)));
        if (movedToShore) station.metadata.put("shoreSide", "true");
        else station.metadata.remove("shoreSide");
    }

    // ------------------------------------------------------------------ ferries

    /**
     * Rebuilds a region's road segments so none crosses water. Each crossing becomes
     * road → ferry port → ferry → ferry port → road. Ports within {@link #PORT_MERGE_DISTANCE}
     * of each other are shared, so a port can serve several ferries, provided sharing keeps the
     * network a tree.
     */
    final class FerryBuilder {
        private final World.Region region;
        private final Map<String, double[]> points;
        private final Map<String, String> parent = new HashMap<>();
        private int nextPortId = 1;
        private int nextRoadId = 1;
        private int nextFerryId = 1;

        FerryBuilder(World.Region region, Map<String, double[]> points) {
            this.region = region;
            this.points = points;
            region.ferryPorts = new ArrayList<>();
            region.ferries = new ArrayList<>();
        }

        /** Adds one network segment, splitting it at water. */
        void addSegment(String fromKey, String toKey, Map<String, String> roadMetadata) {
            addSegment(fromKey, toKey, roadMetadata, 0);
        }

        private void addSegment(String fromKey, String toKey, Map<String, String> roadMetadata, int depth) {
            double[] a = points.get(fromKey);
            double[] b = points.get(toKey);
            List<TerrainMap.WaterRun> runs = waterRuns(a[0], a[1], b[0], b[1]);
            if (runs.isEmpty() || depth > 3) {
                addRoad(fromKey, toKey, roadMetadata, depth > 0);
                return;
            }
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            String previous = fromKey;
            for (int index = 0; index < runs.size(); index++) {
                TerrainMap.WaterRun run = runs.get(index);
                double[] landing = shorePoint(a, b, length, run.start(), -1);
                boolean previousCanLand = previous.startsWith("station:") || previous.startsWith("port:");
                String inPort = previousCanLand && Math.hypot(landing[0] - points.get(previous)[0],
                        landing[1] - points.get(previous)[1]) < SELF_PORT_DISTANCE
                        ? previous : obtainPort(landing, previous, toKey, true);
                if (!inPort.equals(previous)) addSegment(previous, inPort, roadMetadata, depth + 1);
                boolean last = index == runs.size() - 1;
                double[] departure = shorePoint(a, b, length, run.end(), 1);
                String outPort;
                if (last && toKey.startsWith("station:")
                        && Math.hypot(departure[0] - b[0], departure[1] - b[1]) < SELF_PORT_DISTANCE) {
                    outPort = toKey;
                } else {
                    outPort = obtainPort(departure, inPort, toKey, false);
                }
                addFerry(inPort, outPort, (run.end() - run.start()) * length);
                previous = outPort;
            }
            if (!previous.equals(toKey)) addSegment(previous, toKey, roadMetadata, depth + 1);
        }

        /** Point on the segment just on the land side of a waterline at fraction t. */
        private double[] shorePoint(double[] a, double[] b, double length, double t, int direction) {
            double fraction = t + direction * PORT_SETBACK / length;
            fraction = Math.max(0, Math.min(1, fraction));
            double x = a[0] + (b[0] - a[0]) * fraction;
            double y = a[1] + (b[1] - a[1]) * fraction;
            // Step further back if the setback still lands in water (narrow spit, etc.).
            for (int step = 0; step < 10 && terrain.isWater(x, y); step++) {
                fraction = Math.max(0, Math.min(1, fraction + direction * PORT_SETBACK / length));
                x = a[0] + (b[0] - a[0]) * fraction;
                y = a[1] + (b[1] - a[1]) * fraction;
            }
            return new double[]{x, y};
        }

        /**
         * Reuses a nearby port when that keeps the network a tree and (for the landward side) its
         * access road stays dry; otherwise creates a new port.
         */
        private String obtainPort(double[] point, String linkFrom, String segmentEnd, boolean viaRoad) {
            String best = null;
            double bestDistance = PORT_MERGE_DISTANCE;
            for (World.Junction port : region.ferryPorts) {
                String key = "port:" + port.id;
                double distance = Math.hypot(port.x - point[0], port.y - point[1]);
                if (distance > bestDistance || connected(key, linkFrom) || connected(key, segmentEnd)) continue;
                double[] from = points.get(linkFrom);
                if (viaRoad && crossesWater(from[0], from[1], port.x, port.y)) continue;
                best = key;
                bestDistance = distance;
            }
            if (best != null) return best;
            World.Junction port = new World.Junction(nextPortId++, point[0], point[1]);
            port.metadata.put("type", "ferry-port");
            port.metadata.put("algorithm", "road-water-crossing-v1");
            region.ferryPorts.add(port);
            String key = "port:" + port.id;
            points.put(key, point);
            return key;
        }

        private void addRoad(String from, String to, Map<String, String> metadata, boolean ferryAccess) {
            double[] a = points.get(from);
            double[] b = points.get(to);
            World.Road road = new World.Road(nextRoadId++, from, to);
            road.metadata.putAll(metadata);
            road.metadata.put("distance", format(Math.hypot(a[0] - b[0], a[1] - b[1])));
            if (ferryAccess) road.metadata.put("ferryAccess", "true");
            region.roads.add(road);
            union(from, to);
        }

        private void addFerry(String from, String to, double waterLength) {
            double[] a = points.get(from);
            double[] b = points.get(to);
            markDock(from);
            markDock(to);
            World.Road ferry = new World.Road(nextFerryId++, from, to);
            ferry.metadata.put("type", "ferry");
            ferry.metadata.put("kind", "ferry-crossing");
            ferry.metadata.put("algorithm", "road-water-crossing-v1");
            ferry.metadata.put("distance", format(Math.hypot(a[0] - b[0], a[1] - b[1])));
            ferry.metadata.put("waterLength", format(waterLength));
            ferry.metadata.put("shortHop", Boolean.toString(waterLength <= SHORT_FERRY));
            region.ferries.add(ferry);
            union(from, to);
        }

        /** A shore-side station that a ferry lands at directly is its own ferry port. */
        private void markDock(String key) {
            if (!key.startsWith("station:")) return;
            World.Station station = region.stationById(Integer.parseInt(key.substring("station:".length())));
            if (station != null) station.metadata.put("ferryDock", "true");
        }

        private String find(String key) {
            String root = key;
            while (parent.containsKey(root)) root = parent.get(root);
            String node = key;
            while (parent.containsKey(node)) {
                String next = parent.get(node);
                parent.put(node, root);
                node = next;
            }
            return root;
        }

        private boolean connected(String a, String b) { return find(a).equals(find(b)); }

        private void union(String a, String b) {
            String rootA = find(a);
            String rootB = find(b);
            if (!rootA.equals(rootB)) parent.put(rootA, rootB);
        }
    }

    static String format(double value) { return String.format(Locale.ROOT, "%.7f", value); }
}
