package districts.terrain;

import districts.model.World;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Geography sampled from the source terrain cells (see tools/extract-terrain-cells.py).
 *
 * <p>All public methods take world coordinates: the 8 x 8 district grid spans the whole source map.
 * Lookups are exact point-in-polygon tests against the source cells, so "is this on water" agrees
 * with the coastlines drawn on the terrain image.
 */
public final class TerrainMap {
    public static final String FILE_NAME = "terrain-cells.bin";

    /** Step used when walking along a line to find water crossings (about 1.5 km). */
    public static final double LINE_STEP = 0.001;

    private static final int BUCKETS = 256;
    private static final int DISTANCE_RASTER = 1024;

    public record Sample(boolean water, boolean ocean, boolean lake, String lakeType, int height,
                         int biomeId, String biomeName, boolean river, boolean coastal) {}

    /** A stretch of water along a line, as fractions 0..1 of the line's length. */
    public record WaterRun(double start, double end) {}

    private final double sourceWidth;
    private final double sourceHeight;
    private final String[] biomeNames;
    private final byte[] height;
    private final byte[] biome;
    private final byte[] water;
    private final byte[] lakeGroup;
    private final byte[] flags;
    private final int[] polygonStart;
    private final float[] polygonX;
    private final float[] polygonY;
    private final int[][] buckets;
    private final float[] waterDistance;
    private final float[] landDistance;
    private final Display display;
    /** Per bucket, the drawn lake outlines overlapping it (null when there are none). */
    private final int[][] lakeBuckets;

    /**
     * Everything needed to draw the terrain exactly like the source renderer, at any resolution.
     * Coordinates are world units. Present only in version-2 terrain files.
     */
    public record Display(int[] cellColors, int backgroundColor, long textureSeed, double sourceUnitsPerWorldUnit,
                          List<Lake> lakes, List<River> rivers) {}

    public record Lake(int color, float[] xs, float[] ys, float minX, float minY, float maxX, float maxY) {}

    /** A river through source cell centres; width is in world units. */
    public record River(double width, float[] xs, float[] ys, float minX, float minY, float maxX, float maxY) {}

    private TerrainMap(double sourceWidth, double sourceHeight, String[] biomeNames, byte[] height, byte[] biome,
                       byte[] water, byte[] lakeGroup, byte[] flags, int[] polygonStart,
                       float[] polygonX, float[] polygonY, Display display) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.biomeNames = biomeNames;
        this.height = height;
        this.biome = biome;
        this.water = water;
        this.lakeGroup = lakeGroup;
        this.flags = flags;
        this.polygonStart = polygonStart;
        this.polygonX = polygonX;
        this.polygonY = polygonY;
        this.display = display;
        this.buckets = buildBuckets();
        this.lakeBuckets = buildLakeBuckets();
        float[][] shoreDistances = buildShoreDistances();
        this.waterDistance = shoreDistances[0];
        this.landDistance = shoreDistances[1];
    }

    public static TerrainMap load(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 16))) {
            byte[] magic = in.readNBytes(4);
            if (!"DTRC".equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException(path + " is not a Districts terrain cell file");
            }
            int version = in.readInt();
            if (version != 1 && version != 2) throw new IOException("Unsupported terrain cell file version " + version);
            double width = in.readFloat();
            double heightUnits = in.readFloat();
            in.readFloat();
            String[] biomeNames = new String[in.readInt()];
            for (int index = 0; index < biomeNames.length; index++) {
                biomeNames[index] = new String(in.readNBytes(in.readUnsignedShort()), StandardCharsets.UTF_8);
            }
            int count = in.readInt();
            byte[] heights = new byte[count];
            byte[] biomes = new byte[count];
            byte[] waters = new byte[count];
            byte[] lakes = new byte[count];
            byte[] cellFlags = new byte[count];
            int[] starts = new int[count + 1];
            int[] colors = version >= 2 ? new int[count] : null;
            float[] xs = new float[count * 7];
            float[] ys = new float[count * 7];
            int used = 0;
            for (int cell = 0; cell < count; cell++) {
                heights[cell] = (byte) in.readUnsignedByte();
                biomes[cell] = (byte) in.readUnsignedByte();
                waters[cell] = (byte) in.readUnsignedByte();
                lakes[cell] = (byte) in.readUnsignedByte();
                cellFlags[cell] = (byte) in.readUnsignedByte();
                int vertices = in.readUnsignedShort();
                starts[cell] = used;
                if (used + vertices > xs.length) {
                    xs = Arrays.copyOf(xs, Math.max(xs.length * 2, used + vertices));
                    ys = Arrays.copyOf(ys, xs.length);
                }
                for (int vertex = 0; vertex < vertices; vertex++) {
                    // Convert source units to world units once, at load time.
                    xs[used] = (float) (in.readFloat() / width * World.GRID_SIZE);
                    ys[used] = (float) (in.readFloat() / heightUnits * World.GRID_SIZE);
                    used++;
                }
                if (colors != null) colors[cell] = rgb(in);
            }
            starts[count] = used;
            Display display = null;
            if (version >= 2) {
                long seed = in.readInt() & 0xFFFFFFFFL;
                int background = rgb(in);
                List<Lake> lakeOutlines = new ArrayList<>();
                int lakeCount = in.readInt();
                for (int lake = 0; lake < lakeCount; lake++) {
                    int color = rgb(in);
                    float[][] points = readPoints(in, in.readInt(), width, heightUnits);
                    float[] box = bounds(points[0], points[1]);
                    lakeOutlines.add(new Lake(color, points[0], points[1], box[0], box[1], box[2], box[3]));
                }
                List<River> riverPaths = new ArrayList<>();
                int riverCount = in.readInt();
                for (int river = 0; river < riverCount; river++) {
                    double riverWidth = in.readFloat() / width * World.GRID_SIZE;
                    float[][] points = readPoints(in, in.readInt(), width, heightUnits);
                    float[] box = bounds(points[0], points[1]);
                    riverPaths.add(new River(riverWidth, points[0], points[1], box[0], box[1], box[2], box[3]));
                }
                display = new Display(colors, background, seed, width / World.GRID_SIZE, lakeOutlines, riverPaths);
            }
            return new TerrainMap(width, heightUnits, biomeNames, heights, biomes, waters, lakes, cellFlags,
                    starts, Arrays.copyOf(xs, used), Arrays.copyOf(ys, used), display);
        }
    }

    private static int rgb(DataInputStream in) throws IOException {
        return (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
    }

    private static float[][] readPoints(DataInputStream in, int count, double width, double heightUnits) throws IOException {
        float[] xs = new float[count];
        float[] ys = new float[count];
        for (int index = 0; index < count; index++) {
            xs[index] = (float) (in.readFloat() / width * World.GRID_SIZE);
            ys[index] = (float) (in.readFloat() / heightUnits * World.GRID_SIZE);
        }
        return new float[][]{xs, ys};
    }

    private static float[] bounds(float[] xs, float[] ys) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int index = 0; index < xs.length; index++) {
            minX = Math.min(minX, xs[index]);
            maxX = Math.max(maxX, xs[index]);
            minY = Math.min(minY, ys[index]);
            maxY = Math.max(maxY, ys[index]);
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    /** Display data for drawing terrain tiles, or null for an older file without it. */
    public Display display() { return display; }

    /** Receives one cell's outline (world units) and its index. */
    public interface CellVisitor {
        void visit(int cell, float[] xs, float[] ys, int start, int end);
    }

    /** Visits every cell whose bounds overlap the given world-space box, each once. */
    public void forEachCell(double minX, double minY, double maxX, double maxY, CellVisitor visitor) {
        int x0 = bucket(minX), x1 = bucket(maxX), y0 = bucket(minY), y1 = bucket(maxY);
        java.util.BitSet seen = new java.util.BitSet(height.length);
        for (int by = y0; by <= y1; by++) {
            for (int bx = x0; bx <= x1; bx++) {
                for (int cell : buckets[by * BUCKETS + bx]) {
                    if (seen.get(cell)) continue;
                    seen.set(cell);
                    visitor.visit(cell, polygonX, polygonY, polygonStart[cell], polygonStart[cell + 1]);
                }
            }
        }
    }

    public int cellCount() { return height.length; }

    /** Index of the source cell containing the point, or -1 outside the map. */
    public int cellAt(double x, double y) {
        if (x < 0 || y < 0 || x >= World.GRID_SIZE || y >= World.GRID_SIZE) return -1;
        int bx = Math.min(BUCKETS - 1, (int) (x / World.GRID_SIZE * BUCKETS));
        int by = Math.min(BUCKETS - 1, (int) (y / World.GRID_SIZE * BUCKETS));
        for (int cell : buckets[by * BUCKETS + bx]) {
            if (contains(cell, x, y)) return cell;
        }
        // Points exactly on a shared edge can miss every polygon; fall back to the nearest centroid.
        int nearest = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int cell : buckets[by * BUCKETS + bx]) {
            double distance = Math.hypot(centroidX(cell) - x, centroidY(cell) - y);
            if (distance < best) {
                best = distance;
                nearest = cell;
            }
        }
        return nearest;
    }

    /** Everything outside the source map counts as open ocean. */
    /**
     * Water is every cell below height 20 (ocean and lakes) plus everything inside a drawn lake
     * outline. The outlines can reach over cells that sit exactly at the land threshold, so testing
     * them too keeps "is this water" identical to what the map shows.
     */
    /** Whether a cell (as passed to {@link #forEachCell}) is a water cell. */
    public boolean isWaterCell(int cell) { return water[cell] != 0; }

    public boolean isWater(double x, double y) {
        int cell = cellAt(x, y);
        return cell < 0 || water[cell] != 0 || insideLakeOutline(x, y);
    }

    private boolean insideLakeOutline(double x, double y) {
        if (lakeBuckets == null || x < 0 || y < 0 || x >= World.GRID_SIZE || y >= World.GRID_SIZE) return false;
        int[] lakes = lakeBuckets[bucket(y) * BUCKETS + bucket(x)];
        if (lakes == null) return false;
        for (int index : lakes) {
            Lake lake = display.lakes().get(index);
            if (x < lake.minX() || x > lake.maxX() || y < lake.minY() || y > lake.maxY()) continue;
            float[] xs = lake.xs();
            float[] ys = lake.ys();
            boolean inside = false;
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside;
            }
            if (inside) return true;
        }
        return false;
    }

    private int[][] buildLakeBuckets() {
        if (display == null || display.lakes().isEmpty()) return null;
        List<List<Integer>> lists = new ArrayList<>(BUCKETS * BUCKETS);
        for (int i = 0; i < BUCKETS * BUCKETS; i++) lists.add(null);
        for (int index = 0; index < display.lakes().size(); index++) {
            Lake lake = display.lakes().get(index);
            for (int by = bucket(lake.minY()); by <= bucket(lake.maxY()); by++) {
                for (int bx = bucket(lake.minX()); bx <= bucket(lake.maxX()); bx++) {
                    int key = by * BUCKETS + bx;
                    if (lists.get(key) == null) lists.set(key, new ArrayList<>());
                    lists.get(key).add(index);
                }
            }
        }
        int[][] result = new int[BUCKETS * BUCKETS][];
        for (int i = 0; i < result.length; i++) {
            List<Integer> list = lists.get(i);
            if (list != null) result[i] = list.stream().mapToInt(Integer::intValue).toArray();
        }
        return result;
    }

    public Sample sample(double x, double y) {
        int cell = cellAt(x, y);
        if (cell < 0) return new Sample(true, true, false, null, 0, 0, biomeNames[0], false, false);
        int biomeId = biome[cell] & 0xff;
        int lake = lakeGroup[cell];
        boolean outlined = water[cell] == 0 && insideLakeOutline(x, y);
        return new Sample(water[cell] != 0 || outlined, water[cell] == 1, water[cell] == 2 || outlined,
                lake == 0 ? null : LAKE_TYPES[lake], height[cell] & 0xff, biomeId,
                biomeId < biomeNames.length ? biomeNames[biomeId] : "Unknown",
                (flags[cell] & 1) != 0, (flags[cell] & 2) != 0);
    }

    private static final String[] LAKE_TYPES = {null, "freshwater", "salt", "sinkhole", "frozen", "lava", "dry"};

    /** Water stretches crossed by the straight line a→b, in order along the line. */
    public List<WaterRun> waterRuns(double ax, double ay, double bx, double by) {
        double length = Math.hypot(bx - ax, by - ay);
        int steps = Math.max(2, (int) Math.ceil(length / LINE_STEP));
        List<WaterRun> runs = new ArrayList<>();
        double runStart = -1;
        boolean previousWet = false;
        for (int step = 0; step <= steps; step++) {
            double t = (double) step / steps;
            double previousT = (double) (step - 1) / steps;
            boolean wet = isWater(ax + (bx - ax) * t, ay + (by - ay) * t);
            if (wet && !previousWet) {
                runStart = step == 0 ? 0.0 : shoreBetween(ax, ay, bx, by, previousT, t);
            } else if (!wet && previousWet) {
                runs.add(new WaterRun(runStart, shoreBetween(ax, ay, bx, by, t, previousT)));
            }
            previousWet = wet;
        }
        if (previousWet) runs.add(new WaterRun(runStart, 1.0));
        return runs;
    }

    public boolean crossesWater(double ax, double ay, double bx, double by) {
        double length = Math.hypot(bx - ax, by - ay);
        int steps = Math.max(2, (int) Math.ceil(length / LINE_STEP));
        for (int step = 0; step <= steps; step++) {
            double t = (double) step / steps;
            if (isWater(ax + (bx - ax) * t, ay + (by - ay) * t)) return true;
        }
        return false;
    }

    /** Total length of water crossed by the line, in world units. */
    public double waterLength(double ax, double ay, double bx, double by) {
        double length = Math.hypot(bx - ax, by - ay);
        double total = 0.0;
        for (WaterRun run : waterRuns(ax, ay, bx, by)) total += (run.end - run.start) * length;
        return total;
    }

    /** Binary search between a dry and a wet fraction for the shoreline; returns the wet side. */
    private double shoreBetween(double ax, double ay, double bx, double by, double dryT, double wetT) {
        for (int iteration = 0; iteration < 18; iteration++) {
            double middle = (dryT + wetT) / 2.0;
            if (isWater(ax + (bx - ax) * middle, ay + (by - ay) * middle)) wetT = middle;
            else dryT = middle;
        }
        return (dryT + wetT) / 2.0;
    }

    /**
     * Nearest land point to (x, y) inside the given bounds, placed just inside the shoreline.
     * Returns null when no land exists within maxRadius.
     */
    public double[] nearestShore(double x, double y, double maxRadius,
                                 double minX, double minY, double maxX, double maxY) {
        double ringStep = 0.0012;
        for (double radius = ringStep; radius <= maxRadius; radius += ringStep) {
            int samples = Math.max(16, (int) Math.ceil(2 * Math.PI * radius / (ringStep * 0.75)));
            double bestDistance = Double.POSITIVE_INFINITY;
            double[] best = null;
            for (int sample = 0; sample < samples; sample++) {
                double angle = 2 * Math.PI * sample / samples;
                double px = x + Math.cos(angle) * radius;
                double py = y + Math.sin(angle) * radius;
                if (px < minX || py < minY || px > maxX || py > maxY || isWater(px, py)) continue;
                // Walk back toward the water to sit right on the shore.
                double dry = 1.0;
                double wet = 0.0;
                for (int iteration = 0; iteration < 16; iteration++) {
                    double middle = (dry + wet) / 2.0;
                    if (isWater(x + (px - x) * middle, y + (py - y) * middle)) wet = middle;
                    else dry = middle;
                }
                double sx = x + (px - x) * dry;
                double sy = y + (py - y) * dry;
                double distance = Math.hypot(sx - x, sy - y);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new double[]{sx, sy};
                }
            }
            if (best != null) {
                // Nudge slightly inland so the station is unambiguously on land.
                double dx = best[0] - x;
                double dy = best[1] - y;
                double norm = Math.max(1e-9, Math.hypot(dx, dy));
                for (double nudge = 0.0004; nudge <= 0.004; nudge += 0.0004) {
                    double nx = best[0] + dx / norm * nudge;
                    double ny = best[1] + dy / norm * nudge;
                    if (!isWater(nx, ny) && nx >= minX && ny >= minY && nx <= maxX && ny <= maxY) {
                        return new double[]{nx, ny};
                    }
                }
                return best;
            }
        }
        return null;
    }

    /** Approximate distance to the nearest water, in world units (raster accurate to ~0.008 units). */
    public double distanceToWater(double x, double y) {
        return sampleDistance(waterDistance, x, y);
    }

    /** Approximate distance to the nearest land, used to make a gently deepening seabed. */
    public double distanceToLand(double x, double y) {
        return sampleDistance(landDistance, x, y);
    }

    /** Fraction of a district cell that is land, sampled on a grid. */
    public double landFraction(int column, int row) {
        int land = 0;
        int total = 0;
        for (int iy = 0; iy < 48; iy++) {
            for (int ix = 0; ix < 48; ix++) {
                total++;
                if (!isWater(column + (ix + 0.5) / 48.0, row + (iy + 0.5) / 48.0)) land++;
            }
        }
        return (double) land / total;
    }

    /** Receives one water cell's outline (world units) for drawing. */
    public interface WaterCellVisitor {
        void visit(float[] xs, float[] ys, int start, int end, boolean ocean, String lakeType);
    }

    /** Visits every water cell whose bounds overlap the given world-space box. */
    public void forEachWaterCell(double minX, double minY, double maxX, double maxY, WaterCellVisitor visitor) {
        int x0 = bucket(minX), x1 = bucket(maxX), y0 = bucket(minY), y1 = bucket(maxY);
        java.util.BitSet seen = new java.util.BitSet(height.length);
        for (int by = y0; by <= y1; by++) {
            for (int bx = x0; bx <= x1; bx++) {
                for (int cell : buckets[by * BUCKETS + bx]) {
                    if (water[cell] == 0 || seen.get(cell)) continue;
                    seen.set(cell);
                    visitor.visit(polygonX, polygonY, polygonStart[cell], polygonStart[cell + 1],
                            water[cell] == 1, LAKE_TYPES[lakeGroup[cell]]);
                }
            }
        }
    }

    public String describe() {
        int waterCells = 0;
        for (byte value : water) if (value != 0) waterCells++;
        return String.format(Locale.ROOT, "%d terrain cells (%d water) from a %.0fx%.0f source map",
                height.length, waterCells, sourceWidth, sourceHeight);
    }

    private boolean contains(int cell, double x, double y) {
        int start = polygonStart[cell];
        int end = polygonStart[cell + 1];
        boolean inside = false;
        for (int i = start, j = end - 1; i < end; j = i++) {
            double xi = polygonX[i];
            double yi = polygonY[i];
            double xj = polygonX[j];
            double yj = polygonY[j];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }

    private double centroidX(int cell) {
        double sum = 0;
        for (int i = polygonStart[cell]; i < polygonStart[cell + 1]; i++) sum += polygonX[i];
        return sum / Math.max(1, polygonStart[cell + 1] - polygonStart[cell]);
    }

    private double centroidY(int cell) {
        double sum = 0;
        for (int i = polygonStart[cell]; i < polygonStart[cell + 1]; i++) sum += polygonY[i];
        return sum / Math.max(1, polygonStart[cell + 1] - polygonStart[cell]);
    }

    private int[][] buildBuckets() {
        int[] counts = new int[BUCKETS * BUCKETS];
        int cells = height.length;
        int[][] ranges = new int[cells][];
        for (int cell = 0; cell < cells; cell++) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = polygonStart[cell]; i < polygonStart[cell + 1]; i++) {
                minX = Math.min(minX, polygonX[i]);
                maxX = Math.max(maxX, polygonX[i]);
                minY = Math.min(minY, polygonY[i]);
                maxY = Math.max(maxY, polygonY[i]);
            }
            int x0 = bucket(minX), x1 = bucket(maxX), y0 = bucket(minY), y1 = bucket(maxY);
            ranges[cell] = new int[]{x0, x1, y0, y1};
            for (int by = y0; by <= y1; by++) for (int bx = x0; bx <= x1; bx++) counts[by * BUCKETS + bx]++;
        }
        int[][] result = new int[BUCKETS * BUCKETS][];
        for (int index = 0; index < result.length; index++) result[index] = new int[counts[index]];
        int[] fill = new int[BUCKETS * BUCKETS];
        for (int cell = 0; cell < cells; cell++) {
            int[] range = ranges[cell];
            for (int by = range[2]; by <= range[3]; by++) {
                for (int bx = range[0]; bx <= range[1]; bx++) {
                    int index = by * BUCKETS + bx;
                    result[index][fill[index]++] = cell;
                }
            }
        }
        return result;
    }

    private static int bucket(double value) {
        return (int) clamp(Math.floor(value / World.GRID_SIZE * BUCKETS), 0, BUCKETS - 1);
    }

    /** Land and water distance fields made from one exact rasterisation of the source coastline. */
    private float[][] buildShoreDistances() {
        int size = DISTANCE_RASTER;
        double pixel = (double) World.GRID_SIZE / size;
        boolean[] wet = new boolean[size * size];
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                wet[py * size + px] = isWater((px + 0.5) * pixel, (py + 0.5) * pixel);
            }
        }
        return new float[][]{distanceTransform(wet, true, pixel), distanceTransform(wet, false, pixel)};
    }

    /** Two-pass chamfer distance to cells whose wetness equals {@code targetWet}. */
    private static float[] distanceTransform(boolean[] wet, boolean targetWet, double pixel) {
        int size = DISTANCE_RASTER;
        float[] distance = new float[size * size];
        float far = Float.MAX_VALUE / 4;
        for (int index = 0; index < distance.length; index++) distance[index] = wet[index] == targetWet ? 0f : far;
        float straight = (float) pixel;
        float diagonal = (float) (pixel * Math.sqrt(2));
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int index = py * size + px;
                float value = distance[index];
                if (px > 0) value = Math.min(value, distance[index - 1] + straight);
                if (py > 0) value = Math.min(value, distance[index - size] + straight);
                if (px > 0 && py > 0) value = Math.min(value, distance[index - size - 1] + diagonal);
                if (px < size - 1 && py > 0) value = Math.min(value, distance[index - size + 1] + diagonal);
                distance[index] = value;
            }
        }
        for (int py = size - 1; py >= 0; py--) {
            for (int px = size - 1; px >= 0; px--) {
                int index = py * size + px;
                float value = distance[index];
                if (px < size - 1) value = Math.min(value, distance[index + 1] + straight);
                if (py < size - 1) value = Math.min(value, distance[index + size] + straight);
                if (px < size - 1 && py < size - 1) value = Math.min(value, distance[index + size + 1] + diagonal);
                if (px > 0 && py < size - 1) value = Math.min(value, distance[index + size - 1] + diagonal);
                distance[index] = value;
            }
        }
        return distance;
    }

    /** Bilinear lookup keeps the generated coastal slope smooth between distance-raster cells. */
    private static double sampleDistance(float[] distance, double x, double y) {
        double gx = clamp(x / World.GRID_SIZE * DISTANCE_RASTER - 0.5, 0, DISTANCE_RASTER - 1.0);
        double gy = clamp(y / World.GRID_SIZE * DISTANCE_RASTER - 0.5, 0, DISTANCE_RASTER - 1.0);
        int x0 = (int) Math.floor(gx);
        int y0 = (int) Math.floor(gy);
        int x1 = Math.min(DISTANCE_RASTER - 1, x0 + 1);
        int y1 = Math.min(DISTANCE_RASTER - 1, y0 + 1);
        double fx = gx - x0;
        double fy = gy - y0;
        double a = distance[y0 * DISTANCE_RASTER + x0];
        double b = distance[y0 * DISTANCE_RASTER + x1];
        double c = distance[y1 * DISTANCE_RASTER + x0];
        double d = distance[y1 * DISTANCE_RASTER + x1];
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
