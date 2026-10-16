package districts.generator;

import districts.model.World;
import districts.terrain.TerrainMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class WorldGenerator {
    private static final int[][] NEIGHBORS = {
            {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}
    };

    private WorldGenerator() {}

    public static List<String> loadBundledTownNames() throws IOException {
        InputStream stream = WorldGenerator.class.getResourceAsStream("/all_town_names_unique.txt");
        if (stream == null) throw new IOException("Missing bundled resource: all_town_names_unique.txt");
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = line.strip();
                if (!clean.isEmpty()) names.add(clean);
            }
        }
        return names;
    }

    public static World generate(List<String> sourceNames, long seed) {
        return generate(sourceNames, seed, null);
    }

    /** Generates a new world. With terrain, regions and stations stay on land and roads use ferries over water. */
    public static World generate(List<String> sourceNames, long seed, TerrainMap terrain) {
        TerrainLayout terrainLayout = terrain == null ? null : new TerrainLayout(terrain);
        Random random = new Random(seed);
        NameAllocator names = new NameAllocator(sourceNames, random);
        int[][] layout = generateDistrictLayout(random);
        World world = new World(seed, names.sourceNameCount());
        world.metadata.put("generator", "branch-frontier-v1");
        world.metadata.put("namePolicy", "balanced-regional-mix-v3");
        world.metadata.put("routeAlgorithm", "distance-measured-hierarchical-transport-v6");
        world.metadata.put("districtGrid", "8x8");
        world.metadata.put("coordinateSystem", "world units; one district equals one unit square");

        int rootRow = 0;
        int rootColumn = 0;
        for (int row = 0; row < World.GRID_SIZE; row++) {
            for (int column = 0; column < World.GRID_SIZE; column++) {
                if (layout[row][column] == 1) {
                    rootRow = row;
                    rootColumn = column;
                }
            }
        }
        double maxDistance = 0.0;
        for (int row = 0; row < World.GRID_SIZE; row++) {
            for (int column = 0; column < World.GRID_SIZE; column++) {
                maxDistance = Math.max(maxDistance, Math.hypot(column - rootColumn, row - rootRow));
            }
        }

        World.District[] byId = new World.District[World.GRID_SIZE * World.GRID_SIZE + 1];
        for (int row = 0; row < World.GRID_SIZE; row++) {
            for (int column = 0; column < World.GRID_SIZE; column++) {
                int id = layout[row][column];
                World.District district = new World.District(id, column, row);
                double distance = Math.hypot(column - rootColumn, row - rootRow);
                district.metadata.put("distanceFromDistrict1", String.format(Locale.ROOT, "%.3f", distance));
                district.metadata.put("generationOrder", Integer.toString(id));
                byId[id] = district;
            }
        }

        for (int id = 1; id < byId.length; id++) {
            World.District district = byId[id];
            double distance = Math.hypot(district.column - rootColumn, district.row - rootRow);
            double closeness = 1.0 - distance / maxDistance;
            int regionCount = clamp((int) Math.round(4 + 29 * closeness * closeness
                    + random.nextGaussian() * 2.0 + (random.nextDouble() * 2.0 - 1.0)), 4, 33);
            generateDistrictContents(district, regionCount, names, random, terrainLayout);
            world.districts.add(district);
        }
        RailNetworkBuilder.generate(world);
        if (terrainLayout != null) recordTerrainMetadata(world, terrainLayout);
        world.metadata.put("stationNames", "all_town_names_unique.txt with constrained variants");
        return world;
    }

    /** Upgrades an alpha save without changing any settlement, name, type, or coordinate. */
    public static boolean upgradeEfficientNetworks(World world) {
        if (world.formatVersion >= 6) return false;
        if (world.formatVersion != 3 && world.formatVersion != 4 && world.formatVersion != 5) {
            throw new IllegalArgumentException("Cannot upgrade world format " + world.formatVersion
                    + "; expected version 3, 4, 5, or " + World.FORMAT_VERSION + ".");
        }
        int previousFormat = world.formatVersion;
        if (previousFormat == 5) {
            RailNetworkBuilder.generate(world);
            world.metadata.put("routeAlgorithm", "distance-measured-hierarchical-transport-v6");
            world.metadata.put("upgradedFromFormat", "5");
            world.formatVersion = 6;
            return true;
        }
        for (World.District district : world.districts) {
            if (district.highwayJunctions == null) district.highwayJunctions = new ArrayList<>();
            else district.highwayJunctions.clear();
            district.highways.clear();
            generateHighways(district);
            district.metadata.put("highwayCount", Integer.toString(district.highways.size()));
            district.metadata.put("highwayIntersectionCount", Integer.toString(district.highwayJunctions.size()));
            for (World.Region region : district.regions) {
                if (region.roadJunctions == null) region.roadJunctions = new ArrayList<>();
                else region.roadJunctions.clear();
                region.roads.clear();
                generateStationRoads(region);
            }
            int localRoadCount = district.regions.stream().mapToInt(region -> region.roads.size()).sum();
            district.metadata.put("roadCount", Integer.toString(localRoadCount));
        }
        RailNetworkBuilder.generate(world);
        world.metadata.put("routeAlgorithm", "distance-measured-hierarchical-transport-v6");
        world.metadata.put("upgradedFromFormat", Integer.toString(previousFormat));
        world.formatVersion = 6;
        return true;
    }

    /**
     * Re-places every region and station of an existing world on the terrain and rebuilds its
     * highways, roads, ferries and rails. District numbering, region ids and capitals, and every
     * station's id, name, type and naming metadata are kept, so counts never change.
     */
    public static void relayoutOnTerrain(World world, TerrainMap terrain) {
        TerrainLayout layout = new TerrainLayout(terrain);
        Random random = new Random(world.seed * 0x5DEECE66DL + 0x7E44A1);
        List<World.District> ordered = new ArrayList<>(world.districts);
        ordered.sort(Comparator.comparingInt(district -> district.id));
        for (World.District district : ordered) {
            List<World.Region> oldRegions = new ArrayList<>(district.regions);
            oldRegions.sort(Comparator.comparingInt(region -> region.id));
            World.Region oldCapital = oldRegions.stream().filter(region -> region.capital).findFirst().orElseThrow();
            List<Integer> otherIds = new ArrayList<>();
            for (World.Region region : oldRegions) if (!region.capital) otherIds.add(region.id);
            Collections.shuffle(otherIds, random);

            List<TerrainLayout.RegionNode> nodes = layout.growRegionTree(district.column, district.row,
                    oldRegions.size(), random);
            district.regions.clear();
            for (int i = 0; i < nodes.size(); i++) {
                TerrainLayout.RegionNode node = nodes.get(i);
                int regionId = i == 0 ? oldCapital.id : otherIds.get(i - 1);
                World.Region old = oldRegions.stream().filter(region -> region.id == regionId).findFirst().orElseThrow();
                World.Region region = new World.Region(regionId, old.name, i == 0, node.x(), node.y(),
                        TerrainLayout.stationRadius(nearestOther(nodes, node)));
                region.metadata.putAll(old.metadata);
                region.metadata.put("branchDepth", Integer.toString(node.depth()));
                region.metadata.put("role", region.capital ? "capital-region" : "region");
                district.regions.add(region);
            }
            district.regions.sort(Comparator.comparingInt(region -> region.id));
            district.highwayJunctions = new ArrayList<>();
            district.highways.clear();
            generateHighways(district);

            TerrainLayout.RegionNode capitalNode = nodes.get(0);
            for (World.Region region : district.regions) {
                World.Region old = oldRegions.stream().filter(previous -> previous.id == region.id).findFirst().orElseThrow();
                List<World.Station> oldStations = new ArrayList<>(old.stations);
                oldStations.sort(Comparator.comparingInt(station -> station.id));
                for (int i = 0; i < oldStations.size(); i++) {
                    World.Station previous = oldStations.get(i);
                    double[] proposed = proposeStation(i, district, region, capitalNode, random);
                    TerrainLayout.Placement placement = layout.placeOnLand(proposed[0], proposed[1], district, region);
                    World.Station station = new World.Station(previous.id, previous.name, previous.kind,
                            previous.capital, placement.x(), placement.y());
                    station.metadata.putAll(previous.metadata);
                    layout.recordTerrain(station, placement.movedToShore());
                    region.stations.add(station);
                }
                region.metadata.put("stationCount", Integer.toString(region.stations.size()));
                generateStationRoads(region, layout);
            }
            updateDistrictCounts(district);
        }
        RailNetworkBuilder.generate(world);
        recordTerrainMetadata(world, layout);
        world.metadata.put("upgradedFromFormat", Integer.toString(world.formatVersion));
        world.formatVersion = World.FORMAT_VERSION;
    }

    /** Rebuilds one region's roads and ferries after some of its stations moved. */
    static void rebuildRegionRoads(World.District district, World.Region region, TerrainLayout layout) {
        region.roads.clear();
        region.roadJunctions = new ArrayList<>();
        generateStationRoads(region, layout);
        updateDistrictCounts(district);
    }

    private static void recordTerrainMetadata(World world, TerrainLayout layout) {
        world.metadata.put("terrain", layout.terrain().describe());
        world.metadata.put("terrainLayout", "land-only-regions-and-stations-with-ferries-v1");
        world.metadata.put("regionStretch", String.format(Locale.ROOT, "%.2f", TerrainLayout.REGION_STRETCH));
        int shore = 0;
        int ports = 0;
        int ferries = 0;
        for (World.District district : world.districts) {
            district.metadata.put("landFraction", String.format(Locale.ROOT, "%.3f",
                    layout.terrain().landFraction(district.column, district.row)));
            for (World.Region region : district.regions) {
                for (World.Station station : region.stations) if ("true".equals(station.metadata.get("shoreSide"))) shore++;
                ports += region.ferryPorts == null ? 0 : region.ferryPorts.size();
                ferries += region.ferries == null ? 0 : region.ferries.size();
            }
        }
        world.metadata.put("shoreSideStationCount", Integer.toString(shore));
        world.metadata.put("ferryPortCount", Integer.toString(ports));
        world.metadata.put("ferryCount", Integer.toString(ferries));
    }

    private static double nearestOther(List<TerrainLayout.RegionNode> nodes, TerrainLayout.RegionNode node) {
        return nodes.stream().filter(other -> other != node)
                .mapToDouble(other -> Math.hypot(other.x() - node.x(), other.y() - node.y()))
                .min().orElse(0.18);
    }

    private static void updateDistrictCounts(World.District district) {
        district.metadata.put("regionCount", Integer.toString(district.regions.size()));
        district.metadata.put("highwayCount", Integer.toString(district.highways.size()));
        district.metadata.put("highwayIntersectionCount", Integer.toString(district.highwayJunctions.size()));
        int localRoadCount = district.regions.stream().mapToInt(region -> region.roads.size()).sum();
        district.metadata.put("roadCount", Integer.toString(localRoadCount));
        int ferryCount = district.regions.stream()
                .mapToInt(region -> region.ferries == null ? 0 : region.ferries.size()).sum();
        district.metadata.put("ferryCount", Integer.toString(ferryCount));
    }

    /** Proposed station position before terrain: index 0 is the region hub, others scatter around it. */
    private static double[] proposeStation(int index, World.District district, World.Region region,
                                           TerrainLayout.RegionNode capitalNode, Random random) {
        if (index == 0) return new double[]{region.x, region.y};
        double angle = random.nextDouble() * Math.PI * 2.0;
        double radial = region.stationRadius * Math.pow(random.nextDouble(), 1.45);
        double x = region.x + Math.cos(angle) * radial;
        double y = region.y + Math.sin(angle) * radial;
        // A subtle pull toward the district capital keeps the overall settlement pattern coherent.
        x += (capitalNode.x() - region.x) * 0.025 * random.nextDouble();
        y += (capitalNode.y() - region.y) * 0.025 * random.nextDouble();
        x = clamp(x, district.column + 0.015, district.column + 0.985);
        y = clamp(y, district.row + 0.015, district.row + 0.985);
        return new double[]{x, y};
    }

    private static int[][] generateDistrictLayout(Random random) {
        int[][] grid = new int[World.GRID_SIZE][World.GRID_SIZE];
        int startColumn = random.nextBoolean() ? 3 : 4;
        int startRow = random.nextBoolean() ? 3 : 4;
        grid[startRow][startColumn] = 1;

        for (int id = 2; id <= World.GRID_SIZE * World.GRID_SIZE; id++) {
            List<Cell> frontier = new ArrayList<>();
            for (int row = 0; row < World.GRID_SIZE; row++) {
                for (int column = 0; column < World.GRID_SIZE; column++) {
                    if (grid[row][column] == 0 && touchesNumbered(grid, column, row)) {
                        frontier.add(new Cell(column, row));
                    }
                }
            }

            Cell choice;
            if (random.nextDouble() < 0.10) {
                double farthest = frontier.stream()
                        .mapToDouble(cell -> Math.hypot(cell.column - startColumn, cell.row - startRow))
                        .max().orElseThrow();
                List<Cell> farthestCells = frontier.stream()
                        .filter(cell -> Math.abs(Math.hypot(cell.column - startColumn, cell.row - startRow) - farthest) < 1e-9)
                        .toList();
                choice = farthestCells.get(random.nextInt(farthestCells.size()));
            } else {
                choice = frontier.get(random.nextInt(frontier.size()));
            }
            grid[choice.row][choice.column] = id;
        }
        return grid;
    }

    private static boolean touchesNumbered(int[][] grid, int column, int row) {
        for (int[] delta : NEIGHBORS) {
            int x = column + delta[0];
            int y = row + delta[1];
            if (x >= 0 && y >= 0 && x < World.GRID_SIZE && y < World.GRID_SIZE && grid[y][x] > 0) return true;
        }
        return false;
    }

    private static void generateDistrictContents(World.District district, int regionCount,
                                                  NameAllocator names, Random random, TerrainLayout layout) {
        List<TerrainLayout.RegionNode> nodes = layout != null
                ? layout.growRegionTree(district.column, district.row, regionCount, random)
                : growRegionTree(district.column, district.row, regionCount, random);
        List<Integer> regionNumbers = new ArrayList<>();
        for (int i = 1; i <= regionCount; i++) regionNumbers.add(i);
        Collections.shuffle(regionNumbers, random);

        for (int i = 0; i < nodes.size(); i++) {
            TerrainLayout.RegionNode node = nodes.get(i);
            int regionId = regionNumbers.get(i);
            double nearest = nearestOther(nodes, node);
            double stationRadius = layout != null ? TerrainLayout.stationRadius(nearest)
                    : clamp(nearest * 0.38, 0.025, 0.085);
            World.Region region = new World.Region(regionId, "Region " + regionId, i == 0,
                    node.x(), node.y(), stationRadius);
            region.metadata.put("districtId", Integer.toString(district.id));
            region.metadata.put("role", region.capital ? "capital-region" : "region");
            region.metadata.put("branchDepth", Integer.toString(node.depth()));
            district.regions.add(region);
        }
        district.regions.sort(Comparator.comparingInt(region -> region.id));

        generateHighways(district);

        TerrainLayout.RegionNode capitalNode = nodes.get(0);
        for (World.Region region : district.regions) {
            int stationCount = 11 + (int) Math.round(Math.pow(random.nextDouble(), 2.0) * 99.0);
            List<NameAllocator.GeneratedName> allocated = names.allocateBatch(stationCount, district.id, region.id);
            generateStations(district, region, capitalNode, allocated, random, layout);
            generateStationRoads(region, layout);
        }
        updateDistrictCounts(district);
    }

    private static List<TerrainLayout.RegionNode> growRegionTree(int column, int row, int count, Random random) {
        List<TerrainLayout.RegionNode> nodes = new ArrayList<>();
        nodes.add(new TerrainLayout.RegionNode(column + 0.20 + random.nextDouble() * 0.60,
                row + 0.20 + random.nextDouble() * 0.60, -1, 0));
        int[] degree = new int[count];

        for (int i = 1; i < count; i++) {
            TerrainLayout.RegionNode best = null;
            double bestScore = -Double.MAX_VALUE;
            for (int attempt = 0; attempt < 180; attempt++) {
                int parentIndex = chooseParent(nodes, degree, random);
                TerrainLayout.RegionNode parent = nodes.get(parentIndex);
                double angle = random.nextDouble() * Math.PI * 2.0;
                double step = 0.075 + random.nextDouble() * 0.12;
                double x = parent.x() + Math.cos(angle) * step;
                double y = parent.y() + Math.sin(angle) * step;
                if (x < column + 0.07 || x > column + 0.93 || y < row + 0.07 || y > row + 0.93) continue;
                double nearest = nodes.stream().mapToDouble(n -> Math.hypot(n.x() - x, n.y() - y)).min().orElse(1.0);
                double score = nearest + Math.hypot(x - nodes.get(0).x(), y - nodes.get(0).y()) * 0.06;
                if (nearest >= 0.055) {
                    best = new TerrainLayout.RegionNode(x, y, parentIndex, parent.depth() + 1);
                    break;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = new TerrainLayout.RegionNode(x, y, parentIndex, parent.depth() + 1);
                }
            }
            if (best == null) {
                double x = column + 0.08 + random.nextDouble() * 0.84;
                double y = row + 0.08 + random.nextDouble() * 0.84;
                int parent = nearestNode(nodes, x, y);
                best = new TerrainLayout.RegionNode(x, y, parent, nodes.get(parent).depth() + 1);
            }
            degree[best.parent()]++;
            degree[i]++;
            nodes.add(best);
        }
        return nodes;
    }

    private static int chooseParent(List<TerrainLayout.RegionNode> nodes, int[] degree, Random random) {
        int totalWeight = 0;
        for (int i = 0; i < nodes.size(); i++) totalWeight += Math.max(1, 5 - degree[i]);
        int pick = random.nextInt(totalWeight);
        for (int i = 0; i < nodes.size(); i++) {
            pick -= Math.max(1, 5 - degree[i]);
            if (pick < 0) return i;
        }
        return nodes.size() - 1;
    }

    private static int nearestNode(List<TerrainLayout.RegionNode> nodes, double x, double y) {
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            double distance = Math.hypot(nodes.get(i).x() - x, nodes.get(i).y() - y);
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    private static void generateHighways(World.District district) {
        for (World.Region region : district.regions) {
            region.metadata.put("highwayRoutePriority", region.capital ? "1.28" : "1.00");
            region.metadata.put("highwayPriorityRole", region.capital ? "capital-region" : "standard-region");
        }
        List<EfficientNetworkBuilder.Terminal> terminals = district.regions.stream()
                .map(region -> new EfficientNetworkBuilder.Terminal("region:" + region.id,
                        region.x, region.y, region.capital ? 1.28 : 1.0))
                .toList();
        EfficientNetworkBuilder.Network network = EfficientNetworkBuilder.build(terminals);
        for (EfficientNetworkBuilder.JunctionSpec spec : network.junctions()) {
            World.Junction junction = new World.Junction(spec.id(), spec.x(), spec.y());
            junction.metadata.put("type", "highway-intersection");
            junction.metadata.put("algorithm", "steiner-geometric-median");
            junction.metadata.put("savedLength", formatDistance(spec.savedLength()));
            district.highwayJunctions.add(junction);
        }
        int highwayId = 1;
        for (EfficientNetworkBuilder.SegmentSpec segment : network.segments()) {
            World.Highway highway = new World.Highway(highwayId++, segment.fromNode(), segment.toNode());
            highway.metadata.put("type", "highway");
            highway.metadata.put("kind", "efficient-tree-segment");
            highway.metadata.put("distance", formatDistance(segment.length()));
            highway.metadata.put("algorithm", "priority-mst-steiner");
            district.highways.add(highway);
        }
        addNetworkMetadata(district.metadata, "highway", network);
    }

    private static void generateStations(World.District district, World.Region region,
                                         TerrainLayout.RegionNode capitalNode,
                                         List<NameAllocator.GeneratedName> names, Random random,
                                         TerrainLayout layout) {
        for (int i = 0; i < names.size(); i++) {
            boolean capital = region.capital && i == 0;
            World.StationKind kind;
            if (capital) kind = World.StationKind.CITY;
            else if ((!region.capital && i == 0) || (region.capital && i == 1)) {
                kind = World.StationKind.TOWN;
            }
            else {
                double typeRoll = random.nextDouble();
                kind = typeRoll < 0.16 ? World.StationKind.CITY : World.StationKind.TOWN;
            }

            double[] proposed = proposeStation(i, district, region, capitalNode, random);
            TerrainLayout.Placement placement = layout == null
                    ? new TerrainLayout.Placement(proposed[0], proposed[1], false)
                    : layout.placeOnLand(proposed[0], proposed[1], district, region);
            NameAllocator.GeneratedName generatedName = names.get(i);
            World.Station station = new World.Station(i + 1, generatedName.value(), kind, capital,
                    placement.x(), placement.y());
            station.metadata.putAll(generatedName.metadata());
            station.metadata.put("districtId", Integer.toString(district.id));
            station.metadata.put("regionId", Integer.toString(region.id));
            station.metadata.put("poiType", capital ? "capital-station" : kind.name().toLowerCase(Locale.ROOT));
            if (layout != null) layout.recordTerrain(station, placement.movedToShore());
            region.stations.add(station);
        }
        region.metadata.put("stationCount", Integer.toString(region.stations.size()));
    }

    private static void generateStationRoads(World.Region region) { generateStationRoads(region, null); }

    private static void generateStationRoads(World.Region region, TerrainLayout layout) {
        for (World.Station station : region.stations) {
            double priority = station.capital ? 1.48
                    : station.kind == World.StationKind.CITY ? 1.24 : 1.0;
            station.metadata.put("roadRoutePriority", String.format(Locale.ROOT, "%.2f", priority));
            station.metadata.put("roadPriorityRole", station.capital ? "capital-city"
                    : station.kind == World.StationKind.CITY ? "city" : "town");
        }
        List<EfficientNetworkBuilder.Terminal> terminals = region.stations.stream()
                .map(station -> new EfficientNetworkBuilder.Terminal("station:" + station.id,
                        station.x, station.y, station.capital ? 1.48
                                : station.kind == World.StationKind.CITY ? 1.24 : 1.0))
                .toList();
        EfficientNetworkBuilder.Network network = EfficientNetworkBuilder.build(terminals,
                layout == null ? EfficientNetworkBuilder.Routing.GEOMETRIC : layout.roadRouting());
        region.ferryPorts = new ArrayList<>();
        region.ferries = new ArrayList<>();
        for (EfficientNetworkBuilder.JunctionSpec spec : network.junctions()) {
            World.Junction junction = new World.Junction(spec.id(), spec.x(), spec.y());
            junction.metadata.put("type", "road-intersection");
            junction.metadata.put("algorithm", "steiner-geometric-median");
            junction.metadata.put("savedLength", formatDistance(spec.savedLength()));
            region.roadJunctions.add(junction);
        }
        java.util.Map<String, String> treeMetadata = new java.util.LinkedHashMap<>();
        treeMetadata.put("type", "road");
        treeMetadata.put("kind", "efficient-tree-segment");
        treeMetadata.put("algorithm", "priority-mst-steiner");
        if (layout != null) {
            java.util.Map<String, double[]> points = new java.util.HashMap<>();
            for (World.Station station : region.stations) points.put("station:" + station.id, new double[]{station.x, station.y});
            for (World.Junction junction : region.roadJunctions) points.put("junction:" + junction.id, new double[]{junction.x, junction.y});
            TerrainLayout.FerryBuilder ferries = layout.new FerryBuilder(region, points);
            for (EfficientNetworkBuilder.SegmentSpec segment : network.segments()) {
                ferries.addSegment(segment.fromNode(), segment.toNode(), treeMetadata);
            }
        } else {
            int roadId = 1;
            for (EfficientNetworkBuilder.SegmentSpec segment : network.segments()) {
                World.Road road = new World.Road(roadId++, segment.fromNode(), segment.toNode());
                road.metadata.putAll(treeMetadata);
                road.metadata.put("distance", formatDistance(segment.length()));
                region.roads.add(road);
            }
        }
        classifyRoadPurposes(region);
        int treeSegmentCount = region.roads.size();
        RoadStressConnector.Result stress = RoadStressConnector.addBeneficialConnectors(region, layout);
        region.metadata.put("roadTreeSegmentCount", Integer.toString(treeSegmentCount));
        region.metadata.put("roadStressCandidateCount", Integer.toString(stress.candidateCount()));
        region.metadata.put("roadStressConnectorCount", Integer.toString(stress.connectorCount()));
        region.metadata.put("maximumRoadLocationStress", formatDistance(stress.maximumStress()));
        region.metadata.put("roadStressConstructionLength", formatDistance(stress.constructionLength()));
        region.metadata.put("roadStressTravelDistanceSaved", formatDistance(stress.travelDistanceSaved()));
        double finalLength = region.roads.stream()
                .mapToDouble(road -> Double.parseDouble(road.metadata.get("distance"))).sum();
        region.metadata.put("roadFinalNetworkLength", formatDistance(finalLength));
        region.metadata.put("roadCount", Integer.toString(region.roads.size()));
        region.metadata.put("roadIntersectionCount", Integer.toString(region.roadJunctions.size()));
        region.metadata.put("ferryPortCount", Integer.toString(region.ferryPorts.size()));
        region.metadata.put("ferryCount", Integer.toString(region.ferries.size()));
        region.metadata.put("cityRoadSegmentCount", Integer.toString((int) region.roads.stream()
                .filter(road -> Boolean.parseBoolean(road.metadata.get("servesCities"))).count()));
        region.metadata.put("townRoadSegmentCount", Integer.toString((int) region.roads.stream()
                .filter(road -> Boolean.parseBoolean(road.metadata.get("servesTowns"))).count()));
        addNetworkMetadata(region.metadata, "road", network);
    }

    /** Marks the exact subtrees that connect cities and towns so either road layer can be filtered. */
    private static void classifyRoadPurposes(World.Region region) {
        java.util.Map<String, List<RouteNeighbor>> graph = new java.util.LinkedHashMap<>();
        for (World.Station station : region.stations) graph.put("station:" + station.id, new ArrayList<>());
        for (World.Junction junction : region.roadJunctions) graph.put("junction:" + junction.id, new ArrayList<>());
        if (region.ferryPorts != null) {
            for (World.Junction port : region.ferryPorts) graph.put("port:" + port.id, new ArrayList<>());
        }
        List<World.Road> links = new ArrayList<>(region.roads);
        if (region.ferries != null) links.addAll(region.ferries);
        for (World.Road road : links) {
            graph.get(road.fromNode).add(new RouteNeighbor(road.toNode, road));
            graph.get(road.toNode).add(new RouteNeighbor(road.fromNode, road));
        }

        String root = "station:" + region.stations.get(0).id;
        countRoutePurposes(root, null, graph, region);
        region.metadata.put("cityRoadSegmentCount", Integer.toString((int) region.roads.stream()
                .filter(road -> Boolean.parseBoolean(road.metadata.get("servesCities"))).count()));
        region.metadata.put("townRoadSegmentCount", Integer.toString((int) region.roads.stream()
                .filter(road -> Boolean.parseBoolean(road.metadata.get("servesTowns"))).count()));
    }

    private static RouteCounts countRoutePurposes(String node, String parent,
                                                   java.util.Map<String, List<RouteNeighbor>> graph,
                                                   World.Region region) {
        int cities = 0;
        int towns = 0;
        if (node.startsWith("station:")) {
            World.Station station = region.stationById(Integer.parseInt(node.substring("station:".length())));
            if (station.kind == World.StationKind.CITY) cities = 1;
            else towns = 1;
        }
        for (RouteNeighbor neighbor : graph.getOrDefault(node, List.of())) {
            if (neighbor.node.equals(parent)) continue;
            RouteCounts child = countRoutePurposes(neighbor.node, node, graph, region);
            // Each layer is the connected subtree from the region's central station to that place type.
            boolean servesCities = child.cities > 0;
            boolean servesTowns = child.towns > 0;
            neighbor.road.metadata.put("servesCities", Boolean.toString(servesCities));
            neighbor.road.metadata.put("servesTowns", Boolean.toString(servesTowns));
            neighbor.road.metadata.put("routeUse", servesCities && servesTowns ? "shared"
                    : servesCities ? "city" : servesTowns ? "town" : "local-access");
            cities += child.cities;
            towns += child.towns;
        }
        return new RouteCounts(cities, towns);
    }

    private static void addNetworkMetadata(java.util.Map<String, String> metadata, String prefix,
                                           EfficientNetworkBuilder.Network network) {
        metadata.put(prefix + "Algorithm", "priority-weighted-mst-with-steiner-junctions");
        metadata.put(prefix + "AbsoluteMstLength", formatDistance(network.minimumPossibleLength()));
        metadata.put(prefix + "PriorityTreeLength", formatDistance(network.priorityTreeLength()));
        metadata.put(prefix + "OptimizedLength", formatDistance(network.optimizedLength()));
        metadata.put(prefix + "CityBiasRatio", String.format(Locale.ROOT, "%.5f",
                network.priorityTreeLength() / network.minimumPossibleLength()));
        metadata.put(prefix + "SteinerSavings", formatDistance(
                network.priorityTreeLength() - network.optimizedLength()));
    }

    private static String formatDistance(double distance) {
        return String.format(Locale.ROOT, "%.7f", distance);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private record Cell(int column, int row) {}
    private record RouteNeighbor(String node, World.Road road) {}
    private record RouteCounts(int cities, int towns) {}
}
