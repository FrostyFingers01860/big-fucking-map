package districts.terrain;

import districts.model.World;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The elevation model for the 3D view.
 *
 * <p>Elevation is in "relief units": 0 is the water line, 1 the highest source peak, and negative
 * values are below water (sea and lake floors, and river channels). It combines:
 * <ul>
 *   <li>the source map's cell heights, smoothed (source heights run 0–100 with 20 as the water line);</li>
 *   <li>fine fractal Perlin noise whose strength depends on the biome under it (from the texture
 *       map: glaciers, tundra and taiga are rough, grassland gentle, wetland nearly flat) and rises
 *       with altitude — much finer than the source heightmap, down to a few hundred metres;</li>
 *   <li>river valleys: the land slopes down (at most 15 degrees at x25) to shallow water.</li>
 * </ul>
 */
public final class HeightField {
    public static final int RESOLUTION = 1024;
    /** True scale: world units per relief unit (the highest source peak, relief ≈ 1, is about 1.5 km). */
    public static final double WORLD_PER_RELIEF = 0.001;
    /** Rivers are shallow: at most this deep (relief units below the water line). */
    private static final double RIVER_DEPTH = 0.0015;
    /**
     * Channel half-width of the smallest source river (world units, about 365 m). Source widths are
     * stylised (tens of kilometres wide), so they only set how much wider bigger rivers are: rivers
     * run from about 730 m to 3.2 km across.
     */
    private static final double SMALL_RIVER_HALF_WIDTH = 0.00024;
    private static final double SMALL_RIVER_SOURCE_WIDTH = 0.00168;
    /** Strength of the biome roughness noise, in relief units. */
    private static final double ROUGHNESS_AMPLITUDE = 0.16;
    /** Noise octaves: wavelengths from 1/16 of a district (≈100 km) down to 1/4096 (≈375 m). */
    private static final double BASE_FREQUENCY = 16;
    private static final int OCTAVES = 9;
    /** Resolution of the nearest-river index (one cell is about 6 km). */
    private static final int RIVER_GRID = 2048;
    /**
     * Steepest river bank (relief units per world unit): 15 degrees as seen in the 3D view at its
     * default x25 relief. A drop of one relief unit is WORLD_PER_RELIEF * 25 world units there, so the
     * grade is tan(15 deg) / (0.001 * 25), about 10.7. Ground slopes down toward a river at no more
     * than this, so in high ground the valley begins far from the water.
     */
    public static final double MAX_BANK_DEGREES = 15;
    public static final double DEFAULT_EXAGGERATION = 25;
    public static final double BANK_SLOPE =
            Math.tan(Math.toRadians(MAX_BANK_DEGREES)) / (WORLD_PER_RELIEF * DEFAULT_EXAGGERATION);
    /** Coasts appear no steeper than this at the default exaggerated relief. */
    public static final double MAX_COAST_DEGREES = 6;
    private static final double COAST_SLOPE =
            Math.tan(Math.toRadians(MAX_COAST_DEGREES)) / (WORLD_PER_RELIEF * DEFAULT_EXAGGERATION);
    /** Covers the uncertainty of the 1024-square shore distance field with a level beach shelf. */
    private static final double COAST_SHELF = 1.5 * World.GRID_SIZE / RESOLUTION;
    /** Keeps the exact wet/dry test unambiguous without making another visible cliff. */
    private static final double SHORE_EPSILON = 0.00005;
    /** Widest a channel is drawn when the mesh is too coarse to show its true width (world units). */
    private static final double MAX_SAMPLED_HALF_WIDTH = 0.004;

    /** Roughness per source biome id (Marine, Hot desert, Cold desert, Savanna, Grassland, Tropical
     *  seasonal forest, Temperate deciduous forest, Tropical rainforest, Temperate rainforest, Taiga,
     *  Tundra, Glacier, Wetland). */
    private static final double[] BIOME_ROUGHNESS =
            {0.0, 0.32, 0.38, 0.22, 0.18, 0.34, 0.38, 0.42, 0.42, 0.5, 0.58, 0.85, 0.06};

    private final float[] base;
    private final float[] roughness;
    private final float[] segments;      // river segments: x0, y0, x1, y1, halfWidth
    /** For each cell of a RIVER_GRID x RIVER_GRID raster, the segment whose channel edge is nearest (-1: none). */
    private final int[] nearestRiver;
    /** Exact land and water, used near coasts where the raster is too coarse to say. */
    private final TerrainMap terrain;

    private HeightField(TerrainMap terrain, float[] base, float[] roughness, float[] segments, int[] nearestRiver) {
        this.terrain = terrain;
        this.base = base;
        this.roughness = roughness;
        this.segments = segments;
        this.nearestRiver = nearestRiver;
    }

    public static HeightField build(TerrainMap terrain) {
        int size = RESOLUTION;
        double pixel = (double) World.GRID_SIZE / size;
        float[] raw = new float[size * size];
        float[] rough = new float[size * size];
        IntStream.range(0, size).parallel().forEach(row -> {
            for (int column = 0; column < size; column++) {
                double x = (column + 0.5) * pixel;
                double y = (row + 0.5) * pixel;
                TerrainMap.Sample sample = terrain.sample(x, y);
                int index = row * size + column;
                double height = (sample.height() - 20) / 80.0;
                // Water is always at least a little below the water line (lake outlines can cover height-20 cells).
                raw[index] = (float) (sample.water() ? Math.min(-0.012, height) : Math.max(0.0, height));
                int biome = sample.biomeId();
                rough[index] = sample.water() ? 0f
                        : (float) (biome >= 0 && biome < BIOME_ROUGHNESS.length ? BIOME_ROUGHNESS[biome] : 0.3);
            }
        });
        float[] smooth = blur(blur(raw, size, 2), size, 2);
        float[] smoothRough = blur(rough, size, 3);
        // Keep coastlines where the source puts them: no smoothing may lift water above the line or sink land.
        for (int i = 0; i < smooth.length; i++) {
            if (raw[i] < 0) smooth[i] = Math.min(smooth[i], -0.004f);
            else smooth[i] = Math.max(smooth[i], 0.002f);
        }

        List<float[]> list = new ArrayList<>();
        if (terrain.display() != null) {
            for (TerrainMap.River river : terrain.display().rivers()) {
                float half = (float) riverHalfWidth(river.width());
                float[] line = riverLine(river);
                for (int i = 0; i + 3 < line.length; i += 2) {
                    list.add(new float[]{line[i], line[i + 1], line[i + 2], line[i + 3], half});
                }
            }
        }
        float[] segments = new float[list.size() * 5];
        for (int i = 0; i < list.size(); i++) System.arraycopy(list.get(i), 0, segments, i * 5, 5);
        return new HeightField(terrain, smooth, smoothRough, segments, nearestRiverIndex(segments));
    }

    /** Real channel half-width (world units) for a source river of this (stylised) width. */
    public static double riverHalfWidth(double sourceWidth) {
        return SMALL_RIVER_HALF_WIDTH * Math.pow(Math.max(1, sourceWidth / SMALL_RIVER_SOURCE_WIDTH), 0.7);
    }

    /**
     * The river's centre line as drawn on the map: quadratic curves through the midpoints between
     * source points (with the points as control points), flattened to a polyline [x0, y0, x1, y1, ...].
     */
    public static float[] riverLine(TerrainMap.River river) {
        float[] xs = river.xs();
        float[] ys = river.ys();
        List<Float> out = new ArrayList<>();
        double cx = xs[0], cy = ys[0];
        out.add((float) cx);
        out.add((float) cy);
        for (int i = 1; i < xs.length; i++) {
            double px = xs[i - 1], py = ys[i - 1];
            double ex = (xs[i - 1] + xs[i]) / 2.0, ey = (ys[i - 1] + ys[i]) / 2.0;
            int steps = 4;
            for (int k = 1; k <= steps; k++) {
                double t = (double) k / steps;
                double a = (1 - t) * (1 - t), b = 2 * (1 - t) * t, c = t * t;
                out.add((float) (a * cx + b * px + c * ex));
                out.add((float) (a * cy + b * py + c * ey));
            }
            cx = ex;
            cy = ey;
        }
        out.add(xs[xs.length - 1]);
        out.add(ys[ys.length - 1]);
        float[] line = new float[out.size()];
        for (int i = 0; i < line.length; i++) line[i] = out.get(i);
        return line;
    }

    /**
     * Nearest river segment for every raster cell: segments are stamped into the cells they cross,
     * then swept across the raster (forward and backward, twice) keeping whichever neighbour's
     * segment has the nearer channel edge. Lookups then only test the few segments around a point.
     */
    private static int[] nearestRiverIndex(float[] segments) {
        int n = RIVER_GRID;
        double cell = (double) World.GRID_SIZE / n;
        int[] nearest = new int[n * n];
        float[] best = new float[n * n];
        java.util.Arrays.fill(nearest, -1);
        java.util.Arrays.fill(best, Float.POSITIVE_INFINITY);
        int count = segments.length / 5;
        for (int s = 0; s < count; s++) {
            int o = s * 5;
            double length = Math.hypot(segments[o + 2] - segments[o], segments[o + 3] - segments[o + 1]);
            int steps = Math.max(1, (int) Math.ceil(length / (cell * 0.5)));
            for (int k = 0; k <= steps; k++) {
                double t = (double) k / steps;
                int gx = (int) Math.floor((segments[o] + (segments[o + 2] - segments[o]) * t) / cell);
                int gy = (int) Math.floor((segments[o + 1] + (segments[o + 3] - segments[o + 1]) * t) / cell);
                if (gx < 0 || gy < 0 || gx >= n || gy >= n) continue;
                offer(segments, nearest, best, n, cell, gx, gy, s);
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (x > 0) offer(segments, nearest, best, n, cell, x, y, nearest[y * n + x - 1]);
                    if (y > 0) {
                        offer(segments, nearest, best, n, cell, x, y, nearest[(y - 1) * n + x]);
                        if (x > 0) offer(segments, nearest, best, n, cell, x, y, nearest[(y - 1) * n + x - 1]);
                        if (x + 1 < n) offer(segments, nearest, best, n, cell, x, y, nearest[(y - 1) * n + x + 1]);
                    }
                }
                for (int x = n - 2; x >= 0; x--) offer(segments, nearest, best, n, cell, x, y, nearest[y * n + x + 1]);
            }
            for (int y = n - 1; y >= 0; y--) {
                for (int x = n - 1; x >= 0; x--) {
                    if (x + 1 < n) offer(segments, nearest, best, n, cell, x, y, nearest[y * n + x + 1]);
                    if (y + 1 < n) {
                        offer(segments, nearest, best, n, cell, x, y, nearest[(y + 1) * n + x]);
                        if (x + 1 < n) offer(segments, nearest, best, n, cell, x, y, nearest[(y + 1) * n + x + 1]);
                        if (x > 0) offer(segments, nearest, best, n, cell, x, y, nearest[(y + 1) * n + x - 1]);
                    }
                }
                for (int x = 1; x < n; x++) offer(segments, nearest, best, n, cell, x, y, nearest[y * n + x - 1]);
            }
        }
        return nearest;
    }

    private static void offer(float[] segments, int[] nearest, float[] best, int n, double cell, int x, int y, int segment) {
        int index = y * n + x;
        if (segment < 0 || nearest[index] == segment) return;
        int o = segment * 5;
        double edge = distanceToSegment((x + 0.5) * cell, (y + 0.5) * cell,
                segments[o], segments[o + 1], segments[o + 2], segments[o + 3]) - segments[o + 4];
        if (edge < best[index]) {
            best[index] = (float) edge;
            nearest[index] = segment;
        }
    }

    /**
     * Distance from a point to the nearest river channel's edge (world units, negative inside the
     * water), with every channel at least {@code minimumHalf} wide; infinite when there are no rivers.
     */
    private double riverEdge(double x, double y, double minimumHalf) {
        int n = RIVER_GRID;
        double cell = (double) World.GRID_SIZE / n;
        int gx = (int) Math.floor(x / cell);
        int gy = (int) Math.floor(y / cell);
        double result = Double.POSITIVE_INFINITY;
        for (int dy = -1; dy <= 1; dy++) {
            int yy = Math.max(0, Math.min(n - 1, gy + dy));
            for (int dx = -1; dx <= 1; dx++) {
                int xx = Math.max(0, Math.min(n - 1, gx + dx));
                int segment = nearestRiver[yy * n + xx];
                if (segment < 0) continue;
                int o = segment * 5;
                double edge = distanceToSegment(x, y, segments[o], segments[o + 1], segments[o + 2], segments[o + 3])
                        - Math.max(segments[o + 4], minimumHalf);
                if (edge < result) result = edge;
            }
        }
        return result;
    }

    /** Full-detail elevation (relief units). */
    public double elevation(double x, double y) { return elevation(x, y, 0); }

    /**
     * Elevation, leaving out noise detail finer than {@code minimumWavelength} (world units) — a mesh
     * whose vertices are further apart than that could not show it, and skipping it is faster.
     */
    public double elevation(double x, double y, double minimumWavelength) {
        double ground = sample(base, x, y);
        boolean water = terrain.isWater(x, y);
        // Exact source-map classification is retained even where the smoothed height raster disagrees.
        ground = water ? Math.min(ground, -SHORE_EPSILON) : Math.max(ground, SHORE_EPSILON);
        if (!water) {
            double rough = sample(roughness, x, y);
            if (rough > 0.001) {
                // Rougher with altitude, and fading out right at the shore so coastlines stay put.
                double strength = rough * (0.45 + 1.4 * ground) * Math.min(1, ground / 0.02);
                ground += strength * ROUGHNESS_AMPLITUDE * fbm(x, y, minimumWavelength);
                ground = Math.max(SHORE_EPSILON, ground);
            }
        }
        ground = shapeCoast(x, y, ground, water);
        return carveRivers(x, y, ground, minimumWavelength);
    }

    /**
     * Forms a broad coastal shelf. Heights approach the water line across land, then the seabed
     * continues down at the same gentle maximum grade instead of dropping as a vertical cell wall.
     */
    private double shapeCoast(double x, double y, double ground, boolean water) {
        double distance = water ? terrain.distanceToLand(x, y) : terrain.distanceToWater(x, y);
        double fromShelf = Math.max(0, distance - COAST_SHELF);
        double limit = SHORE_EPSILON + fromShelf * COAST_SLOPE;
        if (water) return Math.min(-SHORE_EPSILON, Math.max(ground, -limit));
        return Math.max(SHORE_EPSILON, Math.min(ground, limit));
    }

    /**
     * Lowers the ground toward rivers. The land slopes down at no more than {@link #BANK_SLOPE} to the
     * water's edge, where it crosses the water line, and the channel itself is only a shallow dip, so
     * a river is exactly as wide as the stretch below the water line. A coarse mesh (large
     * {@code minimumWavelength}) gets channels at least as wide as its cells, so distant rivers stay
     * continuous instead of beaded.
     */
    private double carveRivers(double x, double y, double ground, double minimumWavelength) {
        double edge = riverEdge(x, y, Math.min(MAX_SAMPLED_HALF_WIDTH, minimumWavelength * 0.45));
        if (edge == Double.POSITIVE_INFINITY) return ground;
        double valley = edge * BANK_SLOPE;
        if (valley < 0) valley = Math.max(-RIVER_DEPTH, valley);
        if (ground <= 0) return Math.min(ground, valley);
        // Round off the crease where the valley side meets the land.
        double k = 0.3 * ground;
        double h = Math.max(k - Math.abs(ground - valley), 0) / k;
        return Math.min(ground, valley) - h * h * k / 4;
    }

    /** Distance (world units) from a point to the edge of the nearest river, negative in the water. */
    public double riverClearance(double x, double y) { return riverEdge(x, y, 0); }

    /**
     * Steepest true-scale slope (rise over run) across a circle of the given radius around a point:
     * the largest height difference between the centre and points on the circle, or across it.
     */
    public double slopeAcross(double x, double y, double radius) {
        int samples = 12;
        double[] ring = new double[samples];
        double centre = elevation(x, y);
        double steepest = 0;
        for (int i = 0; i < samples; i++) {
            double angle = 2 * Math.PI * i / samples;
            ring[i] = elevation(x + Math.cos(angle) * radius, y + Math.sin(angle) * radius);
            steepest = Math.max(steepest, Math.abs(ring[i] - centre) / radius);
        }
        for (int i = 0; i < samples / 2; i++) {
            steepest = Math.max(steepest, Math.abs(ring[i] - ring[i + samples / 2]) / (2 * radius));
        }
        return steepest * WORLD_PER_RELIEF;
    }

    private static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
        return Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }

    private static double sample(float[] grid, double x, double y) {
        double gx = x / World.GRID_SIZE * RESOLUTION - 0.5;
        double gy = y / World.GRID_SIZE * RESOLUTION - 0.5;
        int x0 = (int) Math.floor(gx);
        int y0 = (int) Math.floor(gy);
        double fx = gx - x0;
        double fy = gy - y0;
        double a = at(grid, x0, y0);
        double b = at(grid, x0 + 1, y0);
        double c = at(grid, x0, y0 + 1);
        double d = at(grid, x0 + 1, y0 + 1);
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    private static double at(float[] grid, int x, int y) {
        x = Math.max(0, Math.min(RESOLUTION - 1, x));
        y = Math.max(0, Math.min(RESOLUTION - 1, y));
        return grid[y * RESOLUTION + x];
    }

    // ---------------------------------------------------------------- Perlin noise

    private static final int[] PERMUTATION = new int[512];

    static {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        java.util.Random random = new java.util.Random(0x5EED_D157L);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = p[i];
            p[i] = p[j];
            p[j] = swap;
        }
        for (int i = 0; i < 512; i++) PERMUTATION[i] = p[i & 255];
    }

    private static final double FULL_NORM = 1 - Math.pow(0.5, OCTAVES);

    /** Fractal Brownian motion of 2D Perlin noise, roughly in [-1, 1]. */
    private static double fbm(double x, double y, double minimumWavelength) {
        double sum = 0;
        double amplitude = 0.5;
        double frequency = BASE_FREQUENCY;
        for (int octave = 0; octave < OCTAVES; octave++) {
            if (minimumWavelength > 0 && 1.0 / frequency < minimumWavelength) break;
            sum += amplitude * perlin(x * frequency + octave * 17.3, y * frequency - octave * 9.1);
            amplitude *= 0.5;
            frequency *= 2;
        }
        // Normalised by all octaves, so leaving out fine detail does not change the coarse shape.
        return sum / FULL_NORM * 1.4;
    }

    private static double perlin(double x, double y) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        double xf = x - xi;
        double yf = y - yi;
        xi &= 255;
        yi &= 255;
        double u = fade(xf);
        double v = fade(yf);
        int aa = PERMUTATION[PERMUTATION[xi] + yi];
        int ab = PERMUTATION[PERMUTATION[xi] + yi + 1];
        int ba = PERMUTATION[PERMUTATION[xi + 1] + yi];
        int bb = PERMUTATION[PERMUTATION[xi + 1] + yi + 1];
        double x1 = lerp(grad(aa, xf, yf), grad(ba, xf - 1, yf), u);
        double x2 = lerp(grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1), u);
        return lerp(x1, x2, v);
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static double grad(int hash, double x, double y) {
        return switch (hash & 7) {
            case 0 -> x + y;
            case 1 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x;
            case 5 -> -x;
            case 6 -> y;
            default -> -y;
        };
    }

    /** Separable box blur. */
    private static float[] blur(float[] source, int size, int radius) {
        float[] horizontal = new float[source.length];
        IntStream.range(0, size).parallel().forEach(row -> {
            for (int column = 0; column < size; column++) {
                float sum = 0;
                int count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = column + k;
                    if (c < 0 || c >= size) continue;
                    sum += source[row * size + c];
                    count++;
                }
                horizontal[row * size + column] = sum / count;
            }
        });
        float[] result = new float[source.length];
        IntStream.range(0, size).parallel().forEach(row -> {
            for (int column = 0; column < size; column++) {
                float sum = 0;
                int count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int r = row + k;
                    if (r < 0 || r >= size) continue;
                    sum += horizontal[r * size + column];
                    count++;
                }
                result[row * size + column] = sum / count;
            }
        });
        return result;
    }
}
