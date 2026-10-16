package districts.generator;

import districts.model.World;
import districts.terrain.HeightField;
import districts.terrain.TerrainMap;
import districts.towns.StationTowns;
import districts.towns.TownScale;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Enforces a hard proximity cap so no two station town layouts overlap at the uniform town scale.
 *
 * <p>Stations are accepted in priority order — capitals and region hubs (which highways and rails
 * attach to) first, then cities, then towns. A station that is too close to one already accepted is
 * moved to the nearest valid spot: on land, inside its district, near its region, and clear of
 * every accepted station. Regions that lost or gained a station position get their roads and
 * ferries rebuilt. Each station records its layout footprint as {@code townRadius} (world units).
 */
public final class TownSpacing {
    private static final int GRID = 1024;
    private static final double RING_STEP = 0.0004;

    public record Result(int moved, int regionsRebuilt, double largestMove) {}

    private record Candidate(World.District district, World.Region region, World.Station station,
                             double radius, int priority) {}

    private TownSpacing() {}

    public static Result enforce(World world, TerrainMap terrain, StationTowns towns) throws IOException {
        TerrainLayout layout = new TerrainLayout(terrain);
        List<Candidate> candidates = new ArrayList<>();
        double maxRadius = 0;
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                World.Station hub = hub(region);
                for (World.Station station : region.stations) {
                    StationTowns.Town town = towns.load(district.id, region.id, station.id);
                    if (town == null) throw new IOException("No town layout for District " + district.id + " / "
                            + region.name + " / " + station.name + "; run the town generator first");
                    int priority = station.capital ? 0 : station == hub ? 1
                            : "largeCity".equals(station.metadata.get("townClass")) ? 2
                            : "smallCity".equals(station.metadata.get("townClass")) ? 3 : 4;
                    candidates.add(new Candidate(district, region, station, town.radius(), priority));
                    maxRadius = Math.max(maxRadius, town.radius());
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::priority)
                .thenComparingInt(c -> c.district.id).thenComparingInt(c -> c.region.id)
                .thenComparingInt(c -> c.station.id));

        Grid accepted = new Grid(TownScale.minimumDistance(maxRadius, maxRadius));
        Map<World.Station, World.Station> replacements = new HashMap<>();
        Set<World.Region> touched = new LinkedHashSet<>();
        Map<World.Region, World.District> owners = new HashMap<>();
        double largestMove = 0;
        for (Candidate candidate : candidates) {
            World.Station station = candidate.station;
            double x = station.x;
            double y = station.y;
            boolean fixed = candidate.priority <= 1;
            if (!fixed && !accepted.clear(x, y, candidate.radius)) {
                double[] spot = findSpot(candidate, accepted, (px, py) -> !terrain.isWater(px, py));
                if (spot == null) {
                    throw new IllegalStateException("No room for " + station.name + " in District "
                            + candidate.district.id + " / " + candidate.region.name);
                }
                largestMove = Math.max(largestMove, Math.hypot(spot[0] - x, spot[1] - y));
                x = spot[0];
                y = spot[1];
                World.Station moved = new World.Station(station.id, station.name, station.kind, station.capital, x, y);
                moved.metadata.putAll(station.metadata);
                moved.metadata.put("relocatedForTownSpacing", "true");
                layout.recordTerrain(moved, false);
                replacements.put(station, moved);
                touched.add(candidate.region);
                owners.put(candidate.region, candidate.district);
            }
            accepted.add(x, y, candidate.radius);
            World.Station current = replacements.getOrDefault(station, station);
            current.metadata.put("townRadius", String.format(Locale.ROOT, "%.7f",
                    candidate.radius * TownScale.WORLD_PER_UNIT));
        }

        for (World.Region region : touched) {
            region.stations.replaceAll(station -> replacements.getOrDefault(station, station));
            WorldGenerator.rebuildRegionRoads(owners.get(region), region, layout);
        }
        world.metadata.put("townSpacing", String.format(Locale.ROOT,
                "uniform %.2e world units per generator unit; stations at least %.2f x the sum of layout radii apart",
                TownScale.WORLD_PER_UNIT, TownScale.SPACING_FACTOR));
        world.metadata.put("townSpacingRelocatedStations", Integer.toString(replacements.size()));
        world.metadata.put("townScale", Double.toString(TownScale.WORLD_PER_UNIT));
        return new Result(replacements.size(), touched.size(), largestMove);
    }

    /**
     * Brings stored footprints in line with a changed {@link TownScale#WORLD_PER_UNIT}. Stations stay
     * where they are; that is always valid when the scale shrinks. Returns true if anything changed.
     */
    public static boolean rescale(World world) {
        double stored = Double.parseDouble(world.metadata.getOrDefault("townScale", "1.0E-5"));
        if (stored == TownScale.WORLD_PER_UNIT) return false;
        if (TownScale.WORLD_PER_UNIT > stored) {
            throw new IllegalStateException("The town scale grew; restore world-v7-before-town-spacing.bin and re-run spacing");
        }
        double factor = TownScale.WORLD_PER_UNIT / stored;
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                for (World.Station station : region.stations) {
                    String radius = station.metadata.get("townRadius");
                    if (radius != null) {
                        station.metadata.put("townRadius", String.format(Locale.ROOT, "%.8f", Double.parseDouble(radius) * factor));
                    }
                }
            }
        }
        world.metadata.put("townScale", Double.toString(TownScale.WORLD_PER_UNIT));
        world.metadata.put("townSpacing", String.format(Locale.ROOT,
                "uniform %.2e world units per generator unit; stations at least %.2f x the sum of layout radii apart",
                TownScale.WORLD_PER_UNIT, TownScale.SPACING_FACTOR));
        return true;
    }

    public record Repair(int stationsMoved, int regionsRebuilt) {}

    /** Steepest ground (true-scale rise over run) a town may stand on: 45° in the 3D view at ×25 relief. */
    public static final double MAX_TOWN_SLOPE = 0.04;
    /** Room every town keeps between its footprint and a river channel (world units, about 150 m). */
    public static final double RIVER_MARGIN = 0.0001;

    /** Stored in the world once the rule holds; a changed rule (or height model) makes it run again. */
    public static final String SITE_RULE = String.format(Locale.ROOT,
            "v4: towns clear of 4x-wide rivers (15-degree valleys) by %.4f and on slopes up to %.3f", RIVER_MARGIN, MAX_TOWN_SLOPE);

    /** Whether a town of this footprint radius (world units) may stand here: dry, clear of rivers, not too steep. */
    public static boolean goodSite(TerrainMap terrain, HeightField heights, double x, double y, double radius) {
        if (terrain.isWater(x, y)) return false;
        if (heights.riverClearance(x, y) < radius + RIVER_MARGIN) return false;
        return heights.slopeAcross(x, y, radius) <= MAX_TOWN_SLOPE;
    }

    /**
     * One-time site rule for the 3D terrain: moves every station whose town would stand on a river
     * channel's banks or on ground steeper than {@link #MAX_TOWN_SLOPE} to the nearest good site
     * (inside its district, near its region, clear of every other town), then rebuilds roads and
     * ferries in the regions that changed.
     */
    public static Repair repairSites(World world, TerrainMap terrain, HeightField heights) {
        TerrainLayout layout = new TerrainLayout(terrain);
        double maxRadius = 0;
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                for (World.Station station : region.stations) maxRadius = Math.max(maxRadius, footprint(station));
            }
        }
        Grid accepted = new Grid(TownScale.minimumDistance(maxRadius, maxRadius));
        List<Candidate> bad = new ArrayList<>();
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                for (World.Station station : region.stations) {
                    Candidate candidate = new Candidate(district, region, station, footprint(station), 4);
                    double radius = candidate.radius * TownScale.WORLD_PER_UNIT;
                    if (goodSite(terrain, heights, station.x, station.y, radius)) accepted.add(station.x, station.y, candidate.radius);
                    else bad.add(candidate);
                }
            }
        }
        Map<World.Region, World.District> rebuild = new HashMap<>();
        for (Candidate candidate : bad) {
            double radius = candidate.radius * TownScale.WORLD_PER_UNIT;
            double[] spot = findSpot(candidate, accepted, (x, y) -> goodSite(terrain, heights, x, y, radius));
            if (spot == null) throw new IllegalStateException("No good site for " + candidate.station.name);
            World.Station station = candidate.station;
            World.Station moved = new World.Station(station.id, station.name, station.kind, station.capital, spot[0], spot[1]);
            moved.metadata.putAll(station.metadata);
            layout.recordTerrain(moved, "true".equals(station.metadata.get("shoreSide")));
            candidate.region.stations.replaceAll(existing -> existing == station ? moved : existing);
            accepted.add(spot[0], spot[1], candidate.radius);
            rebuild.put(candidate.region, candidate.district);
        }
        for (Map.Entry<World.Region, World.District> entry : rebuild.entrySet()) {
            WorldGenerator.rebuildRegionRoads(entry.getValue(), entry.getKey(), layout);
        }
        world.metadata.put("siteRule", SITE_RULE);
        return new Repair(bad.size(), rebuild.size());
    }

    private interface SpotTest { boolean ok(double x, double y); }

    /**
     * One-time fix after the water rule gained drawn lake outlines: moves any station now standing
     * in water to the nearest valid spot (land, inside its district, clear of every other station's
     * town footprint), tags it shore-side, and rebuilds roads and ferries in every region that has a
     * moved station or a road, junction or ferry port touching water.
     */
    public static Repair repairWater(World world, TerrainMap terrain) {
        TerrainLayout layout = new TerrainLayout(terrain);
        double maxRadius = 0;
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                if (terrain.isWater(region.x, region.y)) {
                    throw new IllegalStateException("Region centre on water in District " + district.id + " / " + region.name);
                }
                for (World.Station station : region.stations) maxRadius = Math.max(maxRadius, footprint(station));
            }
        }
        Grid accepted = new Grid(TownScale.minimumDistance(maxRadius, maxRadius));
        List<Candidate> wet = new ArrayList<>();
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                for (World.Station station : region.stations) {
                    Candidate candidate = new Candidate(district, region, station, footprint(station), 4);
                    if (terrain.isWater(station.x, station.y)) wet.add(candidate);
                    else accepted.add(station.x, station.y, candidate.radius);
                }
            }
        }
        Map<World.Region, World.District> rebuild = new HashMap<>();
        for (Candidate candidate : wet) {
            double[] spot = findSpot(candidate, accepted, (px, py) -> !terrain.isWater(px, py));
            if (spot == null) throw new IllegalStateException("No dry spot for " + candidate.station.name);
            World.Station station = candidate.station;
            World.Station moved = new World.Station(station.id, station.name, station.kind, station.capital, spot[0], spot[1]);
            moved.metadata.putAll(station.metadata);
            layout.recordTerrain(moved, true);
            candidate.region.stations.replaceAll(existing -> existing == station ? moved : existing);
            accepted.add(spot[0], spot[1], candidate.radius);
            rebuild.put(candidate.region, candidate.district);
        }
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                if (!rebuild.containsKey(region) && touchesWater(region, layout)) rebuild.put(region, district);
            }
        }
        for (Map.Entry<World.Region, World.District> entry : rebuild.entrySet()) {
            WorldGenerator.rebuildRegionRoads(entry.getValue(), entry.getKey(), layout);
        }
        world.metadata.put("waterRule", "cells below height 20, plus drawn lake outlines");
        return new Repair(wet.size(), rebuild.size());
    }

    /** A station's town footprint in generator units (from its stored world-unit radius). */
    private static double footprint(World.Station station) {
        String radius = station.metadata.get("townRadius");
        return radius == null ? 64 : Double.parseDouble(radius) / TownScale.WORLD_PER_UNIT;
    }

    private static boolean touchesWater(World.Region region, TerrainLayout layout) {
        TerrainMap terrain = layout.terrain();
        Map<String, double[]> points = new HashMap<>();
        for (World.Station station : region.stations) points.put("station:" + station.id, new double[]{station.x, station.y});
        for (World.Junction junction : region.roadJunctions) {
            if (terrain.isWater(junction.x, junction.y)) return true;
            points.put("junction:" + junction.id, new double[]{junction.x, junction.y});
        }
        for (World.Junction port : region.ferryPorts) {
            if (terrain.isWater(port.x, port.y)) return true;
            points.put("port:" + port.id, new double[]{port.x, port.y});
        }
        for (World.Road road : region.roads) {
            double[] a = points.get(road.fromNode);
            double[] b = points.get(road.toNode);
            if (a != null && b != null && layout.crossesWater(a[0], a[1], b[0], b[1])) return true;
        }
        return false;
    }

    /** The station at the region's centre, which its highway (subway) connects to. */
    private static World.Station hub(World.Region region) {
        World.Station best = region.stations.get(0);
        for (World.Station station : region.stations) {
            if (Math.hypot(station.x - region.x, station.y - region.y) < Math.hypot(best.x - region.x, best.y - region.y)) {
                best = station;
            }
        }
        return best;
    }

    /** Nearest valid spot to the station's current position, searched in widening rings. */
    private static double[] findSpot(Candidate candidate, Grid accepted, SpotTest site) {
        World.District district = candidate.district;
        World.Region region = candidate.region;
        World.Station station = candidate.station;
        double minX = district.column + 0.015;
        double minY = district.row + 0.015;
        double maxX = district.column + 0.985;
        double maxY = district.row + 0.985;
        // Stay near the region: within its station spread, widening only if the region is full.
        double limit = Math.max(region.stationRadius * 1.6, Math.hypot(station.x - region.x, station.y - region.y) + 0.01);
        for (double reach = limit; reach <= 0.6; reach *= 1.5) {
            for (double ring = RING_STEP; ring <= reach * 2; ring += RING_STEP) {
                int steps = Math.max(12, (int) Math.ceil(2 * Math.PI * ring / (RING_STEP * 0.8)));
                double phase = (station.id * 0.618034) % 1.0;
                for (int step = 0; step < steps; step++) {
                    double angle = 2 * Math.PI * (step + phase) / steps;
                    double x = station.x + Math.cos(angle) * ring;
                    double y = station.y + Math.sin(angle) * ring;
                    if (x < minX || y < minY || x > maxX || y > maxY) continue;
                    if (Math.hypot(x - region.x, y - region.y) > reach) continue;
                    if (!accepted.clear(x, y, candidate.radius)) continue;
                    if (site.ok(x, y)) return new double[]{x, y};
                }
            }
        }
        return null;
    }

    /** Spatial hash of accepted stations for fast proximity checks. */
    private static final class Grid {
        private final double cell;
        private final Map<Long, List<double[]>> cells = new HashMap<>();

        Grid(double largestSpacing) { this.cell = Math.max(1e-4, largestSpacing); }

        private long key(int cx, int cy) { return ((long) cx << 32) ^ (cy & 0xffffffffL); }

        void add(double x, double y, double radius) {
            cells.computeIfAbsent(key((int) Math.floor(x / cell), (int) Math.floor(y / cell)), k -> new ArrayList<>())
                    .add(new double[]{x, y, radius});
        }

        boolean clear(double x, double y, double radius) {
            int cx = (int) Math.floor(x / cell);
            int cy = (int) Math.floor(y / cell);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    List<double[]> list = cells.get(key(cx + dx, cy + dy));
                    if (list == null) continue;
                    for (double[] other : list) {
                        // A hair of extra room so stored, rounded radii never put a pair just inside the limit.
                        if (Math.hypot(other[0] - x, other[1] - y) < TownScale.minimumDistance(radius, other[2]) * 1.0005) return false;
                    }
                }
            }
            return true;
        }
    }
}
