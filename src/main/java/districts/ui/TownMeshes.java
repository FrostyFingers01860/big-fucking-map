package districts.ui;

import districts.terrain.HeightField;
import districts.towns.StationTowns;
import districts.towns.TownScale;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Builds 3D house meshes from the station town layouts, one town at a time and only for towns close
 * enough to the camera to be seen; with about a million buildings in the world, nothing is built up
 * front. Meshes are built on a background thread and kept in a small least-recently-used cache.
 *
 * <p>Every building footprint becomes a house: walls from a foundation (down to the lowest ground
 * under the footprint) up to a storey count chosen by ward type, a gabled roof on some four-sided
 * houses (never on triangular ones), procedural windows, and a door on the longest wall whose
 * outside is not blocked by another building. Each house's floor is at its highest corner, with a
 * foundation down to below its lowest, so towns on slopes climb them in steps. Town walls become
 * stone walls with towers, and each gate an arched gatehouse.
 *
 * <p>Heights are stored as (ground elevation in relief units, metres above it) so the view's height
 * exaggeration can change without rebuilding.
 */
final class TownMeshes {
    /** Metres per generator unit (see TownScale: one unit ≈ 3.8 m). */
    static final double METRES_PER_UNIT = 3.8;
    static final double WORLD_PER_METRE = TownScale.WORLD_PER_UNIT / METRES_PER_UNIT;
    static final double STOREY = 3.2;
    private static final int CACHE = 48;

    static final byte FLAT = 0;      // roofs, gables, wall tops: no windows
    static final byte WALL = 1;      // house walls: windows, maybe a door
    static final byte STONE = 2;     // town walls and towers

    /** A town's triangles. Per vertex: x, y (world), relief elevation, metres above it, u, v (metres). */
    static final class Mesh {
        final float[] vertices;   // 9 floats per vertex: x, y (relative to the centre), elevation, metres, u, v, nx, ny, nz (normal per triangle repeated)
        final int[] colors;       // per triangle
        final byte[] kinds;       // per triangle
        final float[] doors;      // per triangle: door start and end along u (NaN when none)
        final int triangles;
        final double centerX;
        final double centerY;
        /** How far (world units) the furthest vertex is from the centre. */
        final double radius;
        /**
         * Solid blocks for first-person collision: each a footprint polygon [x0, y0, x1, y1, ...]
         * relative to the centre, with its bounding box (minX, minY, maxX, maxY) and vertical extent
         * (bottom elevation, bottom metres, top elevation, top metres; height = elevation + metres).
         */
        final float[][] solids;
        final float[] solidBoxes;
        final float[] solidHeights;

        Mesh(float[] vertices, int[] colors, byte[] kinds, float[] doors, int triangles, double centerX, double centerY,
             float[][] solids, float[] solidBoxes, float[] solidHeights) {
            this.solids = solids;
            this.solidBoxes = solidBoxes;
            this.solidHeights = solidHeights;
            double furthest = 0;
            for (int i = 0; i < triangles * 3; i++) {
                furthest = Math.max(furthest, Math.hypot(vertices[i * 9], vertices[i * 9 + 1]));
            }
            this.radius = furthest;
            this.vertices = vertices;
            this.colors = colors;
            this.kinds = kinds;
            this.doors = doors;
            this.triangles = triangles;
            this.centerX = centerX;
            this.centerY = centerY;
        }
    }

    private final StationTowns towns;
    private final Runnable changed;
    private volatile HeightField heights;
    private final Map<String, Mesh> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Mesh> eldest) { return size() > CACHE; }
    };
    private final BlockingDeque<StationTownLayer.Entry> queue = new LinkedBlockingDeque<>();
    private final Map<String, Long> requested = new ConcurrentHashMap<>();

    TownMeshes(Path directory, Runnable changed) {
        this.towns = new StationTowns(directory);
        this.changed = changed;
        Thread worker = new Thread(this::work, "town-meshes");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    void setHeights(HeightField heights) {
        this.heights = heights;
        synchronized (cache) { cache.clear(); }
    }

    /** The mesh for a station's town, or null while it is being built (it is then requested). */
    Mesh get(StationTownLayer.Entry entry) {
        Mesh mesh;
        synchronized (cache) { mesh = cache.get(entry.key()); }
        if (mesh == null && heights != null && requested.put(entry.key(), System.nanoTime()) == null) queue.addLast(entry);
        return mesh;
    }

    private void work() {
        while (true) {
            StationTownLayer.Entry entry;
            try {
                entry = queue.takeLast();
            } catch (InterruptedException interrupted) {
                return;
            }
            Long at = requested.get(entry.key());
            if (at == null || System.nanoTime() - at > 2_000_000_000L) {
                requested.remove(entry.key());
                continue;
            }
            try {
                StationTowns.Town town = towns.load(entry.district(), entry.region(), entry.station());
                HeightField field = heights;
                if (town != null && field != null) {
                    Mesh mesh = build(town, entry.x(), entry.y(), field, entry.key().hashCode());
                    synchronized (cache) { cache.put(entry.key(), mesh); }
                }
            } catch (Exception exception) {
                System.err.println("Could not build 3D town " + entry.key() + ": " + exception);
            } finally {
                requested.remove(entry.key());
                changed.run();
            }
        }
    }

    // ------------------------------------------------------------------ mesh building

    private static final int[] PLASTER = {0xece2cd, 0xdecdb2, 0xcdbeaa, 0xe8dcc8, 0xbfb09f, 0xd9c7a8, 0xe3d6c0};
    private static final int[] ROOF = {0xa54834, 0x8c3c2d, 0x7a5a46, 0x5f5a5f, 0x93503a, 0x6e4a3a};
    private static final int FLAT_ROOF = 0x96918a;
    private static final int STONE_COLOR = 0x8a8478;
    private static final int FOUNDATION_COLOR = 0x77706a;
    /** Foundations reach this far (metres) below the lowest ground under a house or gate. */
    private static final double FOUNDATION = -1.5;

    private static final class Builder {
        float[] v = new float[9 * 3 * 4096];
        int[] colors = new int[4096];
        byte[] kinds = new byte[4096];
        float[] doors = new float[8192];
        int count;
        final List<float[]> solids = new ArrayList<>();
        final List<float[]> solidBoxes = new ArrayList<>();
        final List<float[]> solidHeights = new ArrayList<>();

        /** A solid block (world xs, ys) from bottom (elevation, metres) to top (elevation, metres). */
        void solid(double[] xs, double[] ys, double bottomElevation, double bottomMetres, double topElevation, double topMetres) {
            float[] poly = new float[xs.length * 2];
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < xs.length; i++) {
                poly[2 * i] = (float) (xs[i] - originX);
                poly[2 * i + 1] = (float) (ys[i] - originY);
                minX = Math.min(minX, poly[2 * i]);
                maxX = Math.max(maxX, poly[2 * i]);
                minY = Math.min(minY, poly[2 * i + 1]);
                maxY = Math.max(maxY, poly[2 * i + 1]);
            }
            solids.add(poly);
            solidBoxes.add(new float[]{minX, minY, maxX, maxY});
            solidHeights.add(new float[]{(float) bottomElevation, (float) bottomMetres, (float) topElevation, (float) topMetres});
        }
        /** A point inside the solid being built (x, y, metres); faces are flipped to point away from it. */
        double[] interior = {0, 0, 0};
        /** Vertices are stored relative to this point: world floats alone would snap to ~1.5 m. */
        double originX;
        double originY;

        void triangle(double[] a, double[] b, double[] c, int color, byte kind, double doorStart, double doorEnd) {
            if (count == colors.length) {
                v = java.util.Arrays.copyOf(v, v.length * 2);
                colors = java.util.Arrays.copyOf(colors, colors.length * 2);
                kinds = java.util.Arrays.copyOf(kinds, kinds.length * 2);
                doors = java.util.Arrays.copyOf(doors, doors.length * 2);
            }
            // Face normal in true proportions (world units for x, y and height).
            double ax = a[0], ay = a[1], az = a[3] * WORLD_PER_METRE;
            double bx = b[0] - ax, by = b[1] - ay, bz = b[3] * WORLD_PER_METRE - az;
            double cx = c[0] - ax, cy = c[1] - ay, cz = c[3] * WORLD_PER_METRE - az;
            double nx = by * cz - bz * cy;
            double ny = bz * cx - bx * cz;
            double nz = bx * cy - by * cx;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-20) return;
            nx /= length;
            ny /= length;
            nz /= length;
            // Face outward: away from the interior point of the house, wall or tower.
            double fx = (a[0] + b[0] + c[0]) / 3 - interior[0];
            double fy = (a[1] + b[1] + c[1]) / 3 - interior[1];
            double fz = ((a[3] + b[3] + c[3]) / 3 - interior[2]) * WORLD_PER_METRE;
            if (nx * fx + ny * fy + nz * fz < 0) {
                double[] swap = b;
                b = c;
                c = swap;
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            int o = count * 27;
            for (double[] p : new double[][]{a, b, c}) {
                v[o++] = (float) (p[0] - originX);
                v[o++] = (float) (p[1] - originY);
                v[o++] = (float) p[2];
                v[o++] = (float) p[3];
                v[o++] = (float) p[4];
                v[o++] = (float) p[5];
                v[o++] = (float) nx;
                v[o++] = (float) ny;
                v[o++] = (float) nz;
            }
            colors[count] = color;
            kinds[count] = kind;
            doors[count * 2] = (float) doorStart;
            doors[count * 2 + 1] = (float) doorEnd;
            count++;
        }

        /** Quad a-b-c-d (in order) as two triangles. */
        void quad(double[] a, double[] b, double[] c, double[] d, int color, byte kind, double doorStart, double doorEnd) {
            triangle(a, b, c, color, kind, doorStart, doorEnd);
            triangle(a, c, d, color, kind, doorStart, doorEnd);
        }
    }

    /** Point: x, y (world), relief elevation, metres above it, u, v. */
    private static double[] p(double x, double y, double elevation, double metres, double u, double v) {
        return new double[]{x, y, elevation, metres, u, v};
    }

    static Mesh build(StationTowns.Town town, double cx, double cy, HeightField heights, int seed) {
        double k = TownScale.WORLD_PER_UNIT;
        Builder out = new Builder();
        out.originX = cx;
        out.originY = cy;
        List<float[]> footprints = new ArrayList<>();
        List<Integer> wardTypes = new ArrayList<>();
        for (StationTowns.Ward ward : town.wards()) {
            if (ward.type() == StationTowns.PARK) continue;   // parks are groves, not houses
            for (float[] building : ward.buildings()) {
                if (building.length >= 6) {
                    footprints.add(building);
                    wardTypes.add(ward.type());
                }
            }
        }
        BuildingIndex index = new BuildingIndex(footprints);
        for (int b = 0; b < footprints.size(); b++) {
            house(out, footprints.get(b), wardTypes.get(b), b, cx, cy, k, heights, index, seed);
        }
        if (town.wall().length >= 6) walls(out, town, cx, cy, k, heights);

        float[] vertices = java.util.Arrays.copyOf(out.v, out.count * 27);
        int solidCount = out.solids.size();
        float[] boxes = new float[solidCount * 4];
        float[] solidHeights = new float[solidCount * 4];
        for (int i = 0; i < solidCount; i++) {
            System.arraycopy(out.solidBoxes.get(i), 0, boxes, i * 4, 4);
            System.arraycopy(out.solidHeights.get(i), 0, solidHeights, i * 4, 4);
        }
        return new Mesh(vertices, java.util.Arrays.copyOf(out.colors, out.count),
                java.util.Arrays.copyOf(out.kinds, out.count), java.util.Arrays.copyOf(out.doors, out.count * 2),
                out.count, cx, cy, out.solids.toArray(new float[0][]), boxes, solidHeights);
    }

    private static double hash(int seed, int index, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + index * 0xC2B2AE3D27D4EB4FL + salt * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) / (double) (1L << 53);
    }

    /** Storeys by ward type (see StationTowns.WARD_TYPES). */
    private static int storeys(int wardType, double roll) {
        return switch (wardType) {
            case 4 -> 4 + (int) (roll * 4);            // Cathedral
            case 13 -> 3 + (int) (roll * 3);           // Castle
            case 3, 5, 7 -> 2 + (int) (roll * 3);      // Merchant, Administration, Patriciate
            case 9 -> 2 + (int) (roll * 2);            // Military
            case 6, 8 -> 1 + (int) (roll * 2);         // Slum, Market
            case 12 -> 1;                              // Farm
            default -> 1 + (int) (roll * 3);           // Craftsmen, Gate, other
        };
    }

    private static void house(Builder out, float[] footprint, int wardType, int index, double cx, double cy, double k,
                              HeightField heights, BuildingIndex buildings, int seed) {
        int n = footprint.length / 2;
        double[] xs = new double[n];
        double[] ys = new double[n];
        double centroidX = 0;
        double centroidY = 0;
        double area = 0;
        for (int i = 0; i < n; i++) {
            xs[i] = cx + footprint[2 * i] * k;
            ys[i] = cy + footprint[2 * i + 1] * k;
            centroidX += xs[i];
            centroidY += ys[i];
            int j = (i + 1) % n;
            area += footprint[2 * i] * footprint[2 * j + 1] - footprint[2 * j] * footprint[2 * i + 1];
        }
        centroidX /= n;
        centroidY /= n;
        boolean counterClockwise = area > 0;   // in map coordinates (y down)
        // The floor sits at the highest corner, so no part of the house is buried on a slope; a
        // foundation runs from there down to below the lowest corner to anchor it to the ground.
        double floor = Double.NEGATIVE_INFINITY;
        double lowest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double e = heights.elevation(xs[i], ys[i]);
            floor = Math.max(floor, e);
            lowest = Math.min(lowest, e);
        }
        if (floor < 0) return;                 // never build in water
        double height = storeys(wardType, hash(seed, index, 1)) * STOREY;
        int wall = PLASTER[(int) (hash(seed, index, 2) * PLASTER.length)];
        out.interior = new double[]{centroidX, centroidY, height / 2};

        // Door: the longest wall whose outside is clear of other buildings.
        int doorEdge = -1;
        double doorLength = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ex = footprint[2 * j] - footprint[2 * i];
            double ey = footprint[2 * j + 1] - footprint[2 * i + 1];
            double length = Math.hypot(ex, ey);
            if (length * METRES_PER_UNIT < 2.2) continue;
            // Outward normal for this winding (map y points down).
            double nx = counterClockwise ? ey : -ey;
            double ny = counterClockwise ? -ex : ex;
            double mx = (footprint[2 * i] + footprint[2 * j]) / 2 + nx / length * 0.8;
            double my = (footprint[2 * i + 1] + footprint[2 * j + 1]) / 2 + ny / length * 0.8;
            if (buildings.inside(mx, my, index)) continue;
            if (length > doorLength) {
                doorLength = length;
                doorEdge = i;
            }
        }

        // Walls from the floor to the eaves, and below them the foundation down to the lowest ground.
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double length = Math.hypot(xs[j] - xs[i], ys[j] - ys[i]) / k * METRES_PER_UNIT;
            double doorStart = Double.NaN;
            double doorEnd = Double.NaN;
            if (i == doorEdge) {
                double middle = length * (0.3 + 0.4 * hash(seed, index, 3));
                doorStart = middle - 0.6;
                doorEnd = middle + 0.6;
            }
            double[] a0 = p(xs[i], ys[i], floor, 0, 0, 0);
            double[] b0 = p(xs[j], ys[j], floor, 0, length, 0);
            double[] b1 = p(xs[j], ys[j], floor, height, length, height);
            double[] a1 = p(xs[i], ys[i], floor, height, 0, height);
            double[] af = p(xs[i], ys[i], lowest, FOUNDATION, 0, 0);
            double[] bf = p(xs[j], ys[j], lowest, FOUNDATION, 0, 0);
            out.quad(a0, b0, b1, a1, wall, WALL, doorStart, doorEnd);
            out.quad(af, bf, b0, a0, FOUNDATION_COLOR, STONE, Double.NaN, Double.NaN);
        }

        // Roof: gabled on some four-sided houses, flat otherwise (and always on triangles).
        boolean gabled = n == 4 && hash(seed, index, 4) < 0.62;
        double roofRise = 0;
        if (gabled) {
            double side01 = Math.hypot(xs[1] - xs[0], ys[1] - ys[0]);
            double side12 = Math.hypot(xs[2] - xs[1], ys[2] - ys[1]);
            roofRise = Math.min(side01, side12) / k * METRES_PER_UNIT * 0.45;
        }
        out.solid(xs, ys, lowest, FOUNDATION, floor, height + roofRise * 0.5);
        if (gabled) {
            gabledRoof(out, xs, ys, floor, height, k, ROOF[(int) (hash(seed, index, 5) * ROOF.length)], wall, counterClockwise);
        } else {
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double[] c = p(centroidX, centroidY, floor, height, 0, 0);
                double[] a = p(xs[i], ys[i], floor, height, 0, 0);
                double[] b = p(xs[j], ys[j], floor, height, 0, 0);
                if (counterClockwise) out.triangle(c, b, a, FLAT_ROOF, FLAT, Double.NaN, Double.NaN);
                else out.triangle(c, a, b, FLAT_ROOF, FLAT, Double.NaN, Double.NaN);
            }
        }
    }

    private static void gabledRoof(Builder out, double[] xs, double[] ys, double floor, double height, double k,
                                   int roofColor, int wallColor, boolean counterClockwise) {
        // Ridge runs along the longer pair of sides, between the midpoints of the shorter pair.
        double side01 = Math.hypot(xs[1] - xs[0], ys[1] - ys[0]);
        double side12 = Math.hypot(xs[2] - xs[1], ys[2] - ys[1]);
        int s = side01 >= side12 ? 0 : 1;           // rotate so the ridge is parallel to v0-v1
        double[] x = new double[4];
        double[] y = new double[4];
        for (int i = 0; i < 4; i++) {
            x[i] = xs[(i + s) % 4];
            y[i] = ys[(i + s) % 4];
        }
        double span = Math.min(side01, side12) / k * METRES_PER_UNIT;
        double ridge = height + span * 0.45;
        double[] v0 = p(x[0], y[0], floor, height, 0, 0);
        double[] v1 = p(x[1], y[1], floor, height, 0, 0);
        double[] v2 = p(x[2], y[2], floor, height, 0, 0);
        double[] v3 = p(x[3], y[3], floor, height, 0, 0);
        double[] r12 = p((x[1] + x[2]) / 2, (y[1] + y[2]) / 2, floor, ridge, 0, 0);
        double[] r30 = p((x[3] + x[0]) / 2, (y[3] + y[0]) / 2, floor, ridge, 0, 0);
        boolean ccw = counterClockwise;
        if (ccw) {
            out.quad(v0, r30, r12, v1, roofColor, FLAT, Double.NaN, Double.NaN);
            out.quad(v2, r12, r30, v3, roofColor, FLAT, Double.NaN, Double.NaN);
            out.triangle(v1, r12, v2, wallColor, FLAT, Double.NaN, Double.NaN);
            out.triangle(v3, r30, v0, wallColor, FLAT, Double.NaN, Double.NaN);
        } else {
            out.quad(v1, r12, r30, v0, roofColor, FLAT, Double.NaN, Double.NaN);
            out.quad(v3, r30, r12, v2, roofColor, FLAT, Double.NaN, Double.NaN);
            out.triangle(v2, r12, v1, wallColor, FLAT, Double.NaN, Double.NaN);
            out.triangle(v0, r30, v3, wallColor, FLAT, Double.NaN, Double.NaN);
        }
    }

    // Gatehouse size: half-length along the wall and half-depth across it (generator units), the
    // passage's half-width (units), where its arch springs and the gatehouse's height (metres).
    private static final double GATE_HALF_LENGTH = 2.0;
    private static final double GATE_HALF_DEPTH = 1.5;
    private static final double GATE_OPENING = 0.6;
    private static final double GATE_SPRING = 3.2;
    private static final double GATE_HEIGHT = 10.0;
    private static final int ARCH_STEPS = 8;

    /** Stone town walls (two faces and a top along each side), round-ish towers, and arched gatehouses. */
    private static void walls(Builder out, StationTowns.Town town, double cx, double cy, double k, HeightField heights) {
        float[] wall = town.wall();
        int n = wall.length / 2;
        boolean[] gate = new boolean[n];
        float[] gates = town.gates();
        for (int g = 0; g + 1 < gates.length; g += 2) {
            for (int i = 0; i < n; i++) {
                if (Math.abs(wall[2 * i] - gates[g]) <= 0.1f && Math.abs(wall[2 * i + 1] - gates[g + 1]) <= 0.1f) {
                    gate[i] = true;
                    break;
                }
            }
        }
        double half = 0.9;          // generator units
        double wallHeight = 8.0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ax = wall[2 * i], ay = wall[2 * i + 1], bx = wall[2 * j], by = wall[2 * j + 1];
            double length = Math.hypot(bx - ax, by - ay);
            if (length < 1e-6) continue;
            // Leave room for the gatehouses at either end.
            double start = gate[i] ? GATE_HALF_LENGTH : 0;
            double end = gate[j] ? GATE_HALF_LENGTH : 0;
            if (start + end >= length - 0.2) continue;
            double ux = (bx - ax) / length, uy = (by - ay) / length;
            double sx = ax + ux * start, sy = ay + uy * start;
            double ex = bx - ux * end, ey = by - uy * end;
            double nx = -uy * half;
            double ny = ux * half;
            double gx0 = cx + sx * k, gy0 = cy + sy * k, gx1 = cx + ex * k, gy1 = cy + ey * k;
            double ea = heights.elevation(gx0, gy0);
            double eb = heights.elevation(gx1, gy1);
            if (ea < 0 && eb < 0) continue;
            ea = Math.max(0, ea);
            eb = Math.max(0, eb);
            out.interior = new double[]{(gx0 + gx1) / 2, (gy0 + gy1) / 2, wallHeight / 2};
            double[] la0 = p(gx0 + nx * k, gy0 + ny * k, ea, -2, 0, 0);
            double[] lb0 = p(gx1 + nx * k, gy1 + ny * k, eb, -2, 0, 0);
            double[] lb1 = p(gx1 + nx * k, gy1 + ny * k, eb, wallHeight, 0, 0);
            double[] la1 = p(gx0 + nx * k, gy0 + ny * k, ea, wallHeight, 0, 0);
            double[] ra0 = p(gx0 - nx * k, gy0 - ny * k, ea, -2, 0, 0);
            double[] rb0 = p(gx1 - nx * k, gy1 - ny * k, eb, -2, 0, 0);
            double[] rb1 = p(gx1 - nx * k, gy1 - ny * k, eb, wallHeight, 0, 0);
            double[] ra1 = p(gx0 - nx * k, gy0 - ny * k, ea, wallHeight, 0, 0);
            out.quad(la0, lb0, lb1, la1, STONE_COLOR, STONE, Double.NaN, Double.NaN);
            out.quad(rb0, ra0, ra1, rb1, STONE_COLOR, STONE, Double.NaN, Double.NaN);
            out.quad(la1, lb1, rb1, ra1, STONE_COLOR, STONE, Double.NaN, Double.NaN);
            out.solid(new double[]{la0[0], lb0[0], rb0[0], ra0[0]}, new double[]{la0[1], lb0[1], rb0[1], ra0[1]},
                    Math.min(ea, eb), -2, Math.max(ea, eb), wallHeight);
            // Close the wall's end where it meets a gatehouse.
            if (gate[i]) out.quad(ra0, la0, la1, ra1, STONE_COLOR, STONE, Double.NaN, Double.NaN);
            if (gate[j]) out.quad(lb0, rb0, rb1, lb1, STONE_COLOR, STONE, Double.NaN, Double.NaN);
        }
        for (int i = 0; i < n; i++) {
            if (!gate[i]) continue;
            int prev = (i + n - 1) % n;
            int next = (i + 1) % n;
            double dx = wall[2 * next] - wall[2 * prev];
            double dy = wall[2 * next + 1] - wall[2 * prev + 1];
            double length = Math.hypot(dx, dy);
            if (length < 1e-6) continue;
            gatehouse(out, cx + wall[2 * i] * k, cy + wall[2 * i + 1] * k, dx / length, dy / length, k, heights);
        }
        float[] towers = town.towers();
        for (int t = 0; t + 1 < towers.length; t += 2) {
            boolean atGate = false;
            for (int i = 0; i < n && !atGate; i++) {
                atGate = gate[i] && Math.hypot(wall[2 * i] - towers[t], wall[2 * i + 1] - towers[t + 1]) < 0.5;
            }
            if (atGate) continue;
            double tx = cx + towers[t] * k;
            double ty = cy + towers[t + 1] * k;
            double e = heights.elevation(tx, ty);
            if (e < 0) continue;
            double radius = 1.8 * k;
            double top = 12.0;
            int sides = 8;
            double[] centreTop = p(tx, ty, e, top, 0, 0);
            double[] towerXs = new double[sides];
            double[] towerYs = new double[sides];
            for (int side = 0; side < sides; side++) {
                towerXs[side] = tx + Math.cos(2 * Math.PI * side / sides) * radius;
                towerYs[side] = ty + Math.sin(2 * Math.PI * side / sides) * radius;
            }
            out.solid(towerXs, towerYs, e, -2, e, top);
            out.interior = new double[]{tx, ty, top / 2};
            for (int side = 0; side < sides; side++) {
                double a0 = 2 * Math.PI * side / sides;
                double a1 = 2 * Math.PI * (side + 1) / sides;
                double x0 = tx + Math.cos(a0) * radius, y0 = ty + Math.sin(a0) * radius;
                double x1 = tx + Math.cos(a1) * radius, y1 = ty + Math.sin(a1) * radius;
                double[] b0 = p(x0, y0, e, -2, 0, 0);
                double[] b1 = p(x1, y1, e, -2, 0, 0);
                double[] t1 = p(x1, y1, e, top, 0, 0);
                double[] t0 = p(x0, y0, e, top, 0, 0);
                out.quad(b0, b1, t1, t0, STONE_COLOR, STONE, Double.NaN, Double.NaN);
                out.triangle(centreTop, t1, t0, 0x7a7468, STONE, Double.NaN, Double.NaN);
            }
        }
    }

    /**
     * A gatehouse straddling the wall at (gx, gy) (world), with a round-arched passage through it,
     * across the wall. (ux, uy) runs along the wall. Like houses, it stands at the highest ground
     * under it with a foundation below.
     */
    private static void gatehouse(Builder out, double gx, double gy, double ux, double uy, double k, HeightField heights) {
        double vx = -uy, vy = ux;
        double l = GATE_HALF_LENGTH, t = GATE_HALF_DEPTH, w = GATE_OPENING;
        double r = w * METRES_PER_UNIT;     // arch radius in metres
        double a = GATE_SPRING, h = GATE_HEIGHT;
        double floor = Double.NEGATIVE_INFINITY;
        double lowest = Double.POSITIVE_INFINITY;
        for (double su : new double[]{-l, l}) {
            for (double sv : new double[]{-t, t}) {
                double e = heights.elevation(gx + (ux * su + vx * sv) * k, gy + (uy * su + vy * sv) * k);
                floor = Math.max(floor, e);
                lowest = Math.min(lowest, e);
            }
        }
        if (floor < 0) return;
        final double base = floor;
        final double ground = lowest;
        // Local point: u along the wall and v across it (generator units), z metres above the floor.
        java.util.function.BiFunction<double[], Double, double[]> at = (uv, z) ->
                p(gx + (ux * uv[0] + vx * uv[1]) * k, gy + (uy * uv[0] + vy * uv[1]) * k, base, z, 0, 0);
        java.util.function.Function<double[], double[]> below = uv ->
                p(gx + (ux * uv[0] + vx * uv[1]) * k, gy + (uy * uv[0] + vy * uv[1]) * k, ground, FOUNDATION, 0, 0);
        double[] centre = {gx, gy, h / 2};
        double[] archU = new double[ARCH_STEPS + 1];
        double[] archZ = new double[ARCH_STEPS + 1];
        for (int i = 0; i <= ARCH_STEPS; i++) {
            double angle = Math.PI * (1 - (double) i / ARCH_STEPS);     // from the left spring to the right
            archU[i] = w * Math.cos(angle);
            archZ[i] = a + r * Math.sin(angle);
        }
        for (double side : new double[]{-t, t}) {
            out.interior = centre;
            // Piers either side of the passage, and the stone above the arch.
            out.quad(at.apply(new double[]{-l, side}, 0.0), at.apply(new double[]{-w, side}, 0.0),
                    at.apply(new double[]{-w, side}, h), at.apply(new double[]{-l, side}, h), STONE_COLOR, STONE, Double.NaN, Double.NaN);
            out.quad(at.apply(new double[]{w, side}, 0.0), at.apply(new double[]{l, side}, 0.0),
                    at.apply(new double[]{l, side}, h), at.apply(new double[]{w, side}, h), STONE_COLOR, STONE, Double.NaN, Double.NaN);
            for (int i = 0; i < ARCH_STEPS; i++) {
                out.quad(at.apply(new double[]{archU[i], side}, archZ[i]), at.apply(new double[]{archU[i + 1], side}, archZ[i + 1]),
                        at.apply(new double[]{archU[i + 1], side}, h), at.apply(new double[]{archU[i], side}, h),
                        STONE_COLOR, STONE, Double.NaN, Double.NaN);
            }
            // Foundation under this face.
            out.quad(below.apply(new double[]{-l, side}), below.apply(new double[]{l, side}),
                    at.apply(new double[]{l, side}, 0.0), at.apply(new double[]{-l, side}, 0.0),
                    FOUNDATION_COLOR, STONE, Double.NaN, Double.NaN);
        }
        for (double end : new double[]{-l, l}) {
            out.interior = centre;
            out.quad(at.apply(new double[]{end, -t}, 0.0), at.apply(new double[]{end, t}, 0.0),
                    at.apply(new double[]{end, t}, h), at.apply(new double[]{end, -t}, h), STONE_COLOR, STONE, Double.NaN, Double.NaN);
            out.quad(below.apply(new double[]{end, -t}), below.apply(new double[]{end, t}),
                    at.apply(new double[]{end, t}, 0.0), at.apply(new double[]{end, -t}, 0.0),
                    FOUNDATION_COLOR, STONE, Double.NaN, Double.NaN);
        }
        // Collision: the two piers, and the stone above the passage (walk under it).
        for (double[] span : new double[][]{{-l, -w}, {w, l}, {-w, w}}) {
            double[] sx = new double[4];
            double[] sy = new double[4];
            double[][] corners = {{span[0], -t}, {span[1], -t}, {span[1], t}, {span[0], t}};
            for (int i = 0; i < 4; i++) {
                sx[i] = gx + (ux * corners[i][0] + vx * corners[i][1]) * k;
                sy[i] = gy + (uy * corners[i][0] + vy * corners[i][1]) * k;
            }
            boolean passage = span[0] == -w;
            out.solid(sx, sy, passage ? base : ground, passage ? a + r : FOUNDATION, base, h);
        }
        // Top.
        out.interior = centre;
        out.quad(at.apply(new double[]{-l, -t}, h), at.apply(new double[]{l, -t}, h),
                at.apply(new double[]{l, t}, h), at.apply(new double[]{-l, t}, h), 0x7a7468, STONE, Double.NaN, Double.NaN);
        // Inside the passage: its straight sides (the solid pier is behind each) and the arch's underside.
        for (double sideU : new double[]{-w, w}) {
            double[] pier = at.apply(new double[]{Math.signum(sideU) * (w + l) / 2, 0}, a / 2);
            out.interior = new double[]{pier[0], pier[1], a / 2};
            out.quad(at.apply(new double[]{sideU, -t}, 0.0), at.apply(new double[]{sideU, t}, 0.0),
                    at.apply(new double[]{sideU, t}, a), at.apply(new double[]{sideU, -t}, a), STONE_COLOR, STONE, Double.NaN, Double.NaN);
        }
        for (int i = 0; i < ARCH_STEPS; i++) {
            double angle = Math.PI * (1 - (i + 0.5) / ARCH_STEPS);
            // A point in the stone just outside the arch, so the underside faces into the passage.
            double[] solid = at.apply(new double[]{(w + 0.4) * Math.cos(angle), 0}, a + (r + 1.0) * Math.sin(angle));
            out.interior = new double[]{solid[0], solid[1], a + (r + 1.0) * Math.sin(angle)};
            out.quad(at.apply(new double[]{archU[i], -t}, archZ[i]), at.apply(new double[]{archU[i + 1], -t}, archZ[i + 1]),
                    at.apply(new double[]{archU[i + 1], t}, archZ[i + 1]), at.apply(new double[]{archU[i], t}, archZ[i]),
                    0x6f695f, STONE, Double.NaN, Double.NaN);
        }
    }

    /** Grid of building footprints (generator units) for "is this point inside another building". */
    private static final class BuildingIndex {
        private final List<float[]> footprints;
        private final Map<Long, List<Integer>> cells = new java.util.HashMap<>();
        private static final double CELL = 8;

        BuildingIndex(List<float[]> footprints) {
            this.footprints = footprints;
            for (int b = 0; b < footprints.size(); b++) {
                float[] f = footprints.get(b);
                double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                for (int i = 0; i + 1 < f.length; i += 2) {
                    minX = Math.min(minX, f[i]);
                    maxX = Math.max(maxX, f[i]);
                    minY = Math.min(minY, f[i + 1]);
                    maxY = Math.max(maxY, f[i + 1]);
                }
                for (long gy = (long) Math.floor(minY / CELL); gy <= (long) Math.floor(maxY / CELL); gy++) {
                    for (long gx = (long) Math.floor(minX / CELL); gx <= (long) Math.floor(maxX / CELL); gx++) {
                        cells.computeIfAbsent((gx << 32) ^ (gy & 0xffffffffL), key -> new ArrayList<>()).add(b);
                    }
                }
            }
        }

        boolean inside(double x, double y, int except) {
            long key = ((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(y / CELL) & 0xffffffffL);
            for (int b : cells.getOrDefault(key, List.of())) {
                if (b == except) continue;
                float[] f = footprints.get(b);
                int n = f.length / 2;
                boolean in = false;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    double xi = f[2 * i], yi = f[2 * i + 1], xj = f[2 * j], yj = f[2 * j + 1];
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
                }
                if (in) return true;
            }
            return false;
        }
    }
}
