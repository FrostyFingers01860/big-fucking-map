package districts.generator;

import districts.model.World;
import districts.terrain.TerrainMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public final class WorldValidator {
    private static final int[][] NEIGHBORS = {
            {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}
    };

    private WorldValidator() {}

    public record Report(int districts, int regions, int stations, int railServices, int rails,
                         int highways, int roads,
                         int highwayIntersections, int roadIntersections, int roadStressConnectors,
                         int lakeNames) {
        @Override public String toString() {
            return "VALID: " + districts + " districts, " + regions + " regions, "
                    + stations + " stations, " + railServices + " rail services, " + rails
                    + " rail segments, " + highways + " highways, "
                    + roads + " roads, "
                    + highwayIntersections + " highway intersections, "
                    + roadIntersections + " road intersections, " + roadStressConnectors
                    + " stress-relief roads, "
                    + lakeNames + " Lake suffixes";
        }
    }

    /** Validates structure, then (with terrain) that settlements are on land and no road crosses water. */
    public static Report validate(World world, TerrainMap terrain) {
        Report report = validate(world);
        if (terrain != null) validateTerrain(world, new TerrainLayout(terrain));
        return report;
    }

    private static void validateTerrain(World world, TerrainLayout layout) {
        TerrainMap terrain = layout.terrain();
        // Once the site rule has been applied, every town must keep clear of rivers and steep ground.
        districts.terrain.HeightField heights = world.metadata.containsKey("siteRule")
                ? districts.terrain.HeightField.build(terrain) : null;
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                String where = "District " + district.id + " / " + region.name;
                require(!terrain.isWater(region.x, region.y), "Region centre is on water in " + where);
                Map<String, double[]> points = new HashMap<>();
                for (World.Station station : region.stations) {
                    require(!terrain.isWater(station.x, station.y), "Station " + station.name + " is on water");
                    require(station.metadata.containsKey("terrainBiome"), "Station " + station.name + " lacks terrain metadata");
                    if (heights != null && station.metadata.containsKey("townRadius")) {
                        double radius = Double.parseDouble(station.metadata.get("townRadius"));
                        require(TownSpacing.goodSite(terrain, heights, station.x, station.y, radius),
                                "Station " + station.name + " stands on a river bank or steep ground in " + where);
                    }
                    points.put("station:" + station.id, new double[]{station.x, station.y});
                }
                for (World.Junction junction : region.roadJunctions) {
                    require(!terrain.isWater(junction.x, junction.y), "Road intersection on water in " + where);
                    points.put("junction:" + junction.id, new double[]{junction.x, junction.y});
                }
                for (World.Junction port : region.ferryPorts) {
                    require(!terrain.isWater(port.x, port.y), "Ferry port on water in " + where);
                    points.put("port:" + port.id, new double[]{port.x, port.y});
                }
                for (World.Road road : region.roads) {
                    double[] a = points.get(road.fromNode);
                    double[] b = points.get(road.toNode);
                    require(!layout.crossesWater(a[0], a[1], b[0], b[1]),
                            "Road " + road.fromNode + "–" + road.toNode + " crosses water in " + where);
                }
                for (World.Road ferry : region.ferries) {
                    require(points.containsKey(ferry.fromNode) && points.containsKey(ferry.toNode),
                            "Ferry endpoint missing in " + where);
                    require(!ferry.fromNode.startsWith("junction:") && !ferry.toNode.startsWith("junction:"),
                            "A ferry must land at a port or a shore-side station in " + where);
                }
            }
        }
    }

    public static Report validate(World world) {
        require(world != null, "World is null");
        require(world.districts.size() == 64, "Expected 64 districts");
        Set<Integer> districtIds = new HashSet<>();
        Set<Long> cells = new HashSet<>();
        Set<String> names = new HashSet<>();
        Map<String, String> cardinalPairRegion = new HashMap<>();
        Map<String, Integer> cardinalPairCounts = new HashMap<>();
        int regionTotal = 0;
        int stationTotal = 0;
        int highwayTotal = 0;
        int roadTotal = 0;
        int highwayIntersectionTotal = 0;
        int roadIntersectionTotal = 0;
        int roadStressConnectorTotal = 0;
        int lakeTotal = 0;

        for (World.District district : world.districts) {
            require(districtIds.add(district.id), "Duplicate district id " + district.id);
            require(cells.add((((long) district.column) << 32) | (district.row & 0xffffffffL)),
                    "Duplicate district grid cell");
            require(district.id >= 1 && district.id <= 64, "District id outside 1..64");
            require(district.column >= 0 && district.column < 8 && district.row >= 0 && district.row < 8,
                    "District outside grid");
            require(district.metadata != null, "District metadata is null");
            require(district.regions.size() >= 4 && district.regions.size() <= 33,
                    "District " + district.id + " region count outside 4..33");

            if (district.id == 1) {
                require((district.column == 3 || district.column == 4) && (district.row == 3 || district.row == 4),
                        "District 1 is not in a middle cell");
            } else {
                boolean lowerNeighbor = false;
                for (int[] delta : NEIGHBORS) {
                    World.District neighbor = world.districtAt(district.column + delta[0], district.row + delta[1]);
                    if (neighbor != null && neighbor.id < district.id) lowerNeighbor = true;
                }
                require(lowerNeighbor, "District " + district.id + " has no lower-numbered neighbor");
            }

            long capitalRegions = district.regions.stream().filter(region -> region.capital).count();
            require(capitalRegions == 1, "District " + district.id + " must have one capital region");
            validateHighwayConnectivity(district);
            highwayTotal += district.highways.size();
            highwayIntersectionTotal += district.highwayJunctions.size();
            regionTotal += district.regions.size();

            for (World.Region region : district.regions) {
                require(region.metadata != null, "Region metadata is null");
                require(region.name.equals("Region " + region.id), "Region name/id mismatch");
                require(region.stations.size() >= 11 && region.stations.size() <= 110,
                        "Station count outside 11..110 in District " + district.id + " / " + region.name);
                long capitalStations = region.stations.stream().filter(station -> station.capital).count();
                require(capitalStations == (region.capital ? 1 : 0), "Capital station mismatch in " + region.name);
                validateRoadConnectivity(district, region);
                roadTotal += region.roads.size();
                roadIntersectionTotal += region.roadJunctions.size();
                roadStressConnectorTotal += Integer.parseInt(region.metadata.get("roadStressConnectorCount"));
                Set<String> adjectivePrefixes = new HashSet<>();
                Map<String, Integer> adjectivePrefixCounts = new HashMap<>();
                Map<String, Integer> strategyCounts = new HashMap<>();
                int adjectiveNames = 0;
                int newNames = 0;
                int townNames = 0;

                for (World.Station station : region.stations) {
                    require(station.metadata != null, "Station metadata is null");
                    if (world.metadata.containsKey("townPlan")) {
                        String townClass = station.metadata.get("townClass");
                        require(townClass != null, "Station " + station.name + " has no town layout plan");
                        if (station.kind == World.StationKind.TOWN) {
                            require(townClass.equals("largeTown"), "Town " + station.name + " must be a Large Town");
                        }
                        if (station.capital) require(townClass.equals("largeCity"), "Capital " + station.name + " must be a Large City");
                    }
                    require(names.add(station.name.toLowerCase(Locale.ROOT)), "Duplicate station name: " + station.name);
                    require(station.kind == World.StationKind.CITY || station.kind == World.StationKind.TOWN,
                            "A station must be either a city or a town");
                    if (station.kind == World.StationKind.TOWN) townNames++;
                    if (station.capital) require(station.kind == World.StationKind.CITY,
                            "Capital station is not a city");
                    if (Boolean.parseBoolean(station.metadata.get("lakeSuffix"))) lakeTotal++;
                    String strategy = station.metadata.get("nameStrategy");
                    require(strategy != null && !strategy.isBlank(), "Station lacks a naming strategy");
                    strategyCounts.merge(strategy, 1, Integer::sum);
                    if ("new".equals(strategy)) newNames++;
                    if ("adjective".equals(strategy)) {
                        adjectiveNames++;
                        String prefix = station.metadata.get("adjectivePrefix");
                        require(prefix != null && !prefix.isBlank(), "Adjective station lacks prefix metadata");
                        adjectivePrefixes.add(prefix);
                        adjectivePrefixCounts.merge(prefix, 1, Integer::sum);
                    }
                    String pairId = station.metadata.get("cardinalPairId");
                    if (pairId != null) {
                        String regionKey = district.id + "/" + region.id;
                        String priorRegion = cardinalPairRegion.putIfAbsent(pairId, regionKey);
                        require(priorRegion == null || priorRegion.equals(regionKey),
                                "Directional counterparts cross region boundaries");
                        cardinalPairCounts.merge(pairId, 1, Integer::sum);
                    }
                    stationTotal++;
                }
                require(townNames >= 1, "Every region must contain at least one town");
                require(newNames <= 2, "Too many New-prefixed names in District "
                        + district.id + " / " + region.name);
                require(strategyCounts.containsKey("derived")
                                && strategyCounts.containsKey("adjective")
                                && strategyCounts.containsKey("descriptor-suffix"),
                        "Region does not contain the required mix of naming styles");
                int dominantStrategyCount = strategyCounts.values().stream().mapToInt(Integer::intValue)
                        .max().orElse(0);
                require(dominantStrategyCount <= Math.ceil(region.stations.size() * 0.48),
                        "One naming strategy dominates District " + district.id + " / " + region.name);
                if (adjectiveNames >= 3) {
                    require(adjectivePrefixes.size() >= 3,
                            "Adjective prefixes are not varied in District " + district.id + " / " + region.name);
                }
                int repeatedAdjective = adjectivePrefixCounts.values().stream().mapToInt(Integer::intValue)
                        .max().orElse(0);
                require(repeatedAdjective <= 2, "One adjective prefix repeats too often in District "
                        + district.id + " / " + region.name);
            }
        }
        validateRails(world);
        if (world.metadata.containsKey("townSpacing")) validateTownSpacing(world);
        for (Map.Entry<String, Integer> pair : cardinalPairCounts.entrySet()) {
            require(pair.getValue() == 2, "Directional pair " + pair.getKey() + " is incomplete");
        }
        require(lakeTotal <= Math.floor(stationTotal * 0.05), "More than 5% of stations use Lake suffix");
        return new Report(world.districts.size(), regionTotal, stationTotal,
                Integer.parseInt(world.metadata.get("railServiceCount")), world.rails.size(),
                highwayTotal, roadTotal, highwayIntersectionTotal, roadIntersectionTotal,
                roadStressConnectorTotal, lakeTotal);
    }

    /** Hard proximity cap: no two stations' town layouts may come closer than the spacing rule allows. */
    private static void validateTownSpacing(World world) {
        double cell = 0.01;
        Map<Long, List<World.Station>> grid = new HashMap<>();
        List<World.Station> all = new ArrayList<>();
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                for (World.Station station : region.stations) {
                    require(station.metadata.containsKey("townRadius"), "Station " + station.name + " lacks townRadius");
                    all.add(station);
                    long key = ((long) Math.floor(station.x / cell) << 32) ^ ((long) Math.floor(station.y / cell) & 0xffffffffL);
                    grid.computeIfAbsent(key, k -> new ArrayList<>()).add(station);
                }
            }
        }
        for (World.Station station : all) {
            double radius = Double.parseDouble(station.metadata.get("townRadius"));
            long cx = (long) Math.floor(station.x / cell);
            long cy = (long) Math.floor(station.y / cell);
            for (long dy = -1; dy <= 1; dy++) {
                for (long dx = -1; dx <= 1; dx++) {
                    for (World.Station other : grid.getOrDefault(((cx + dx) << 32) ^ ((cy + dy) & 0xffffffffL), List.of())) {
                        if (other == station) continue;
                        double need = (radius + Double.parseDouble(other.metadata.get("townRadius")))
                                * districts.towns.TownScale.SPACING_FACTOR;
                        // townRadius is stored to 7 decimals; allow for that rounding.
                        require(Math.hypot(other.x - station.x, other.y - station.y) >= need - 5e-7,
                                "Stations " + station.name + " and " + other.name + " are too close for their town layouts");
                    }
                }
            }
        }
    }

    private static void validateHighwayConnectivity(World.District district) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (World.Region region : district.regions) graph.put("region:" + region.id, new HashSet<>());
        for (World.Junction junction : district.highwayJunctions) {
            require(junction.metadata != null, "Highway intersection metadata is null");
            graph.put("junction:" + junction.id, new HashSet<>());
        }
        Set<String> edges = new HashSet<>();
        for (World.Highway highway : district.highways) {
            require(highway.metadata != null, "Highway metadata is null");
            require(graph.containsKey(highway.fromNode) && graph.containsKey(highway.toNode),
                    "Highway endpoint is missing");
            require(!highway.fromNode.equals(highway.toNode), "Highway loops back to the same point");
            require(edges.add(edgeKey(highway.fromNode, highway.toNode)), "Duplicate highway segment");
            graph.get(highway.fromNode).add(highway.toNode);
            graph.get(highway.toNode).add(highway.fromNode);
        }
        require(district.highways.size() == graph.size() - 1,
                "Highways contain a redundant segment in District " + district.id);
        for (World.Junction junction : district.highwayJunctions) {
            require(graph.get("junction:" + junction.id).size() >= 3,
                    "Highway intersection has fewer than three branches");
        }
        Queue<String> queue = new ArrayDeque<>();
        Set<String> reached = new HashSet<>();
        String first = "region:" + district.regions.get(0).id;
        queue.add(first);
        reached.add(first);
        while (!queue.isEmpty()) {
            for (String next : graph.get(queue.remove())) if (reached.add(next)) queue.add(next);
        }
        require(reached.size() == graph.size(), "Region highways are disconnected in District " + district.id);
        validateEfficiency(district.metadata, "highway", "District " + district.id);
    }

    private static void validateRoadConnectivity(World.District district, World.Region region) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (World.Station station : region.stations) graph.put("station:" + station.id, new HashSet<>());
        for (World.Junction junction : region.roadJunctions) {
            require(junction.metadata != null, "Road intersection metadata is null");
            graph.put("junction:" + junction.id, new HashSet<>());
        }
        List<World.Junction> ports = region.ferryPorts == null ? List.of() : region.ferryPorts;
        List<World.Road> ferries = region.ferries == null ? List.of() : region.ferries;
        for (World.Junction port : ports) graph.put("port:" + port.id, new HashSet<>());
        Set<String> edges = new HashSet<>();
        Map<String, Integer> portFerries = new HashMap<>();
        for (World.Road ferry : ferries) {
            require("ferry".equals(ferry.metadata.get("type")), "Ferry metadata is missing its type");
            require(graph.containsKey(ferry.fromNode) && graph.containsKey(ferry.toNode), "Ferry endpoint is missing");
            require(!ferry.fromNode.equals(ferry.toNode), "Ferry loops back to the same port");
            require(edges.add(edgeKey(ferry.fromNode, ferry.toNode)), "Duplicate ferry crossing");
            graph.get(ferry.fromNode).add(ferry.toNode);
            graph.get(ferry.toNode).add(ferry.fromNode);
            portFerries.merge(ferry.fromNode, 1, Integer::sum);
            portFerries.merge(ferry.toNode, 1, Integer::sum);
        }
        for (World.Junction port : ports) {
            require(portFerries.getOrDefault("port:" + port.id, 0) >= 1, "Ferry port without a ferry");
        }
        for (World.Road road : region.roads) {
            require(road.metadata != null, "Road metadata is null");
            require(graph.containsKey(road.fromNode) && graph.containsKey(road.toNode),
                    "Road endpoint is missing");
            require(!road.fromNode.equals(road.toNode), "Road loops back to the same point");
            require(edges.add(edgeKey(road.fromNode, road.toNode)), "Duplicate road segment");
            require(road.metadata.containsKey("servesCities") && road.metadata.containsKey("servesTowns"),
                    "Road is missing its city/town filter classification");
            require(Boolean.parseBoolean(road.metadata.get("servesCities"))
                            || Boolean.parseBoolean(road.metadata.get("servesTowns")),
                    "Road is absent from both road filters");
            graph.get(road.fromNode).add(road.toNode);
            graph.get(road.toNode).add(road.fromNode);
        }
        int connectorCount = Integer.parseInt(region.metadata.get("roadStressConnectorCount"));
        require(connectorCount >= 0 && connectorCount <= 8,
                "Invalid stress-relief road count in District " + district.id + " / " + region.name);
        require(region.roads.size() + ferries.size() == graph.size() - 1 + connectorCount,
                "Road cycle count does not match justified stress links in District "
                        + district.id + " / " + region.name);
        long classifiedConnectors = region.roads.stream()
                .filter(road -> "location-stress-relief".equals(road.metadata.get("kind"))).count();
        require(classifiedConnectors == connectorCount,
                "Stress-relief road metadata count mismatch");
        for (World.Road road : region.roads) {
            if (!"location-stress-relief".equals(road.metadata.get("kind"))) continue;
            require(road.fromNode.startsWith("station:") && road.toNode.startsWith("station:"),
                    "Stress-relief road must directly connect two stations");
            require(Double.parseDouble(road.metadata.get("roadLocationStress")) >= 1.35 - 1e-6,
                    "Stress-relief road was not valuable enough to build");
        }
        for (World.Junction junction : region.roadJunctions) {
            require(graph.get("junction:" + junction.id).size() >= 3,
                    "Road intersection has fewer than three branches");
        }
        Queue<String> queue = new ArrayDeque<>();
        Set<String> reached = new HashSet<>();
        String first = "station:" + region.stations.get(0).id;
        queue.add(first);
        reached.add(first);
        while (!queue.isEmpty()) {
            for (String next : graph.get(queue.remove())) if (reached.add(next)) queue.add(next);
        }
        require(reached.size() == graph.size(), "Station roads are disconnected in District "
                + district.id + " / " + region.name);
        validateEfficiency(region.metadata, "road", "District " + district.id + " / " + region.name);
    }

    private static void validateRails(World world) {
        require(world.rails != null, "Rail network is null");
        int serviceCount = Integer.parseInt(world.metadata.get("railServiceCount"));
        require(serviceCount >= 63 && serviceCount <= 83,
                "Rail service network is not sparse enough for its construction cost");
        require(world.rails.size() >= serviceCount && world.rails.size() <= serviceCount * 3,
                "Rail services contain an invalid number of physical segments");
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        for (int districtId = 1; districtId <= 64; districtId++) graph.put(districtId, new HashSet<>());
        Set<String> edges = new HashSet<>();
        Map<Integer, List<World.Rail>> services = new HashMap<>();
        double[][] distances = new double[64][64];
        for (double[] row : distances) Arrays.fill(row, Double.POSITIVE_INFINITY);
        for (int index = 0; index < 64; index++) distances[index][index] = 0.0;

        for (World.Rail rail : world.rails) {
            require(rail.metadata != null, "Rail metadata is null");
            require(graph.containsKey(rail.fromDistrictId) && graph.containsKey(rail.toDistrictId),
                    "Rail endpoint district is missing");
            require(rail.fromDistrictId != rail.toDistrictId, "Rail loops within one district");
            int serviceId = Integer.parseInt(rail.metadata.get("serviceId"));
            require(edges.add(serviceId + ":" + edgeKey(Integer.toString(rail.fromDistrictId),
                    Integer.toString(rail.toDistrictId))), "Duplicate segment within one rail service");
            services.computeIfAbsent(serviceId, ignored -> new ArrayList<>()).add(rail);
            World.Station from = world.capitalStation(rail.fromDistrictId);
            World.Station to = world.capitalStation(rail.toDistrictId);
            require(from != null && to != null && from.capital && to.capital,
                    "Rail does not terminate at capital cities");
            require(from.kind == World.StationKind.CITY && to.kind == World.StationKind.CITY,
                    "Rail endpoint is not a city");
            double length = Double.parseDouble(rail.metadata.get("distance"));
            require(length > 0.0, "Rail has invalid construction length");
            graph.get(rail.fromDistrictId).add(rail.toDistrictId);
            graph.get(rail.toDistrictId).add(rail.fromDistrictId);
            distances[rail.fromDistrictId - 1][rail.toDistrictId - 1] = Math.min(
                    distances[rail.fromDistrictId - 1][rail.toDistrictId - 1], length);
            distances[rail.toDistrictId - 1][rail.fromDistrictId - 1] = Math.min(
                    distances[rail.toDistrictId - 1][rail.fromDistrictId - 1], length);
        }
        require(services.size() == serviceCount, "Rail service count metadata is incorrect");
        int intermediateStopTotal = 0;
        for (Map.Entry<Integer, List<World.Rail>> entry : services.entrySet()) {
            List<World.Rail> segments = entry.getValue();
            segments.sort(Comparator.comparingInt(rail -> Integer.parseInt(rail.metadata.get("segmentIndex"))));
            World.Rail firstSegment = segments.get(0);
            int fromDistrict = Integer.parseInt(firstSegment.metadata.get("serviceFromDistrict"));
            int toDistrict = Integer.parseInt(firstSegment.metadata.get("serviceToDistrict"));
            List<Integer> stops = parseDistrictSequence(firstSegment.metadata.get("serviceStopSequence"));
            require(stops.size() >= 2 && stops.size() <= 4, "Rail service has more than two intermediate stops");
            require(stops.get(0) == fromDistrict && stops.get(stops.size() - 1) == toDistrict,
                    "Rail service endpoint metadata does not match its stop sequence");
            require(segments.size() == stops.size() - 1, "Rail service segment count is incomplete");
            double trackLength = 0.0;
            for (int index = 0; index < segments.size(); index++) {
                World.Rail rail = segments.get(index);
                require(Integer.parseInt(rail.metadata.get("segmentIndex")) == index + 1,
                        "Rail service segment indexes are not continuous");
                require(rail.fromDistrictId == stops.get(index) && rail.toDistrictId == stops.get(index + 1),
                        "Rail service does not follow its declared stop sequence");
                require(Integer.parseInt(rail.metadata.get("serviceId")) == entry.getKey(),
                        "Rail service id changes between segments");
                require(Integer.parseInt(rail.metadata.get("serviceFromDistrict")) == fromDistrict
                                && Integer.parseInt(rail.metadata.get("serviceToDistrict")) == toDistrict,
                        "Rail service endpoints change between segments");
                trackLength += Double.parseDouble(rail.metadata.get("distance"));
            }
            World.Station serviceFrom = world.capitalStation(fromDistrict);
            World.Station serviceTo = world.capitalStation(toDistrict);
            double direct = Math.hypot(serviceFrom.x - serviceTo.x, serviceFrom.y - serviceTo.y);
            require(trackLength / direct <= 1.025001,
                    "Intermediate rail stops add too much track distance");
            for (int index = 1; index < stops.size() - 1; index++) {
                double offset = distanceToSegment(world.capitalStation(stops.get(index)), serviceFrom, serviceTo);
                require(offset <= 0.150001, "Intermediate rail stop is not close enough to the corridor");
            }
            int intermediate = stops.size() - 2;
            intermediateStopTotal += intermediate;
        }
        require(intermediateStopTotal == Integer.parseInt(world.metadata.get("railIntermediateStopCount")),
                "Rail intermediate-stop metadata count is incorrect");

        Queue<Integer> queue = new ArrayDeque<>();
        Set<Integer> reached = new HashSet<>();
        queue.add(1);
        reached.add(1);
        while (!queue.isEmpty()) {
            for (int next : graph.get(queue.remove())) if (reached.add(next)) queue.add(next);
        }
        require(reached.size() == 64, "Not every district capital is connected by rail");
        int districtOneDegree = Integer.parseInt(world.districtById(1).metadata.get("railServiceEndpointDegree"));
        require(districtOneDegree >= 4 && districtOneDegree <= 10,
                "District 1 must have special reach without becoming a universal hub");

        for (int via = 0; via < 64; via++) {
            for (int from = 0; from < 64; from++) {
                for (int to = 0; to < 64; to++) {
                    double candidate = distances[from][via] + distances[via][to];
                    if (candidate < distances[from][to]) distances[from][to] = candidate;
                }
            }
        }
        double districtOneMean = Arrays.stream(distances[0]).sum() / 63.0;
        for (int district = 1; district < 64; district++) {
            double mean = Arrays.stream(distances[district]).sum() / 63.0;
            require(districtOneMean <= mean + 1e-6,
                    "District 1 is not the easiest rail origin");
        }

        double importantDegree = 0.0;
        double lowPriorityDegree = 0.0;
        double priorImportance = Double.POSITIVE_INFINITY;
        for (int districtId = 1; districtId <= 64; districtId++) {
            World.District district = world.districtById(districtId);
            double importance = Double.parseDouble(district.metadata.get("railImportance"));
            require(importance <= priorImportance + 1e-7, "Rail importance does not fall with district number");
            priorImportance = importance;
            double serviceDegree = Double.parseDouble(district.metadata.get("railServiceEndpointDegree"));
            if (districtId <= 16) importantDegree += serviceDegree;
            if (districtId >= 49) lowPriorityDegree += serviceDegree;
        }
        require(importantDegree / 16.0 > lowPriorityDegree / 16.0,
                "Lower-numbered districts are not receiving more direct rail service");
        require("1".equals(world.metadata.get("railMostAccessibleDistrict")),
                "Rail accessibility metadata does not identify District 1");
    }

    private static List<Integer> parseDistrictSequence(String value) {
        return Arrays.stream(value.split(",")).map(Integer::parseInt).toList();
    }

    private static double distanceToSegment(World.Station point, World.Station from, World.Station to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double projection = ((point.x - from.x) * dx + (point.y - from.y) * dy) / (dx * dx + dy * dy);
        double closestX = from.x + projection * dx;
        double closestY = from.y + projection * dy;
        return Math.hypot(point.x - closestX, point.y - closestY);
    }

    private static void validateEfficiency(Map<String, String> metadata, String prefix, String location) {
        double minimum = Double.parseDouble(metadata.get(prefix + "AbsoluteMstLength"));
        double priority = Double.parseDouble(metadata.get(prefix + "PriorityTreeLength"));
        double optimized = Double.parseDouble(metadata.get(prefix + "OptimizedLength"));
        require(minimum > 0.0 && priority > 0.0 && optimized > 0.0,
                "Invalid route length metadata in " + location);
        require(priority <= minimum * 1.20,
                "Priority routing is too inefficient in " + location);
        require(optimized <= priority + 1e-6,
                "Intersection optimization increased route length in " + location);
    }

    private static String edgeKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
