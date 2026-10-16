package districts.ui;

import districts.model.World;
import districts.terrain.HeightField;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Perspective 3D view of the map, rendered in software with a depth buffer: the heightmap terrain
 * (textured with the normal top-down map and hill-shaded), 3D houses and town walls, and a flat
 * water sheet at the water line that fills seas, lakes and the carved river channels.
 *
 * <p>The camera orbits a target point. Tilt 0 looks straight down (like the 2D map) and 90° looks
 * horizontally; heading 0 keeps north at the top. The scale at the target matches the 2D zoom, so
 * switching between 2D and 3D keeps the same place and size in the middle of the screen. For every
 * pixel the renderer also stores the ground point it shows, used for clicking and hovering.
 */
final class View3D {
    /** Relief scale: the highest source peak is about 1.5 km (world units; one district ≈ 1,538 km). */
    static final double RELIEF = HeightField.WORLD_PER_RELIEF;
    static final double MAX_TILT = Math.toRadians(90);
    static final double DEFAULT_TILT = Math.toRadians(45);
    private static final double DEFAULT_FIELD_OF_VIEW = Math.toRadians(40);
    /** Cells per side of each terrain ring; every ring is three times the size of the one inside it. */
    private static final int RING_CELLS = 128;
    /** First person spends its detail where it matters; 72 cells still gives an 8 m innermost mesh. */
    private static final int FIRST_PERSON_RING_CELLS = 72;
    private static final int MAX_TEXTURE = 2048;
    private static final int SKY_TOP = 0x0b111a;
    private static final int SKY_HORIZON = 0x6f8aa3;
    private static final int WATER = 0x235673;
    private static final int DOOR = 0x5a3c28;
    private static final int WINDOW = 0x30404f;
    private static final double LIGHT_X;
    private static final double LIGHT_Y;
    private static final double LIGHT_Z;

    static {
        double x = -0.55, y = -0.7, z = 0.9;
        double length = Math.sqrt(x * x + y * y + z * z);
        LIGHT_X = x / length;
        LIGHT_Y = y / length;
        LIGHT_Z = z / length;
    }

    volatile boolean enabled;
    /**
     * First-person mode: the camera is the eye at (target x, target y, {@link #eyeZ}), looking along
     * the heading, with tilt 90 degrees looking level (more looks up, less looks down).
     */
    volatile boolean firstPerson;
    volatile double eyeZ;
    /** User-adjustable vertical first-person field of view, in degrees. */
    private volatile double firstPersonFieldOfView = 40.0;
    /** Minimum first-person terrain radius before expanding to the edges of the map. */
    private static final double FIRST_PERSON_VIEW = 1.0;
    /** Minimum horizon for a nearly level 3D camera, independent of close map zoom (about 185 km). */
    private static final double LOW_CAMERA_VIEW = 0.12;
    /** Ground radius (world units, about 250 m) of the sharp map texture around a first-person eye. */
    private static final double FIRST_PERSON_NEAR_TEXTURE = 250 * TownMeshes.WORLD_PER_METRE;
    /** Pixels per world unit the map textures aim for: the view scale, or a walking scale in first person. */
    private double textureScale;
    double tilt = DEFAULT_TILT;
    double heading;
    double exaggeration = HeightField.DEFAULT_EXAGGERATION;
    private HeightField heights;

    // Camera for the current frame.
    private int width;
    private int height;
    private int rasterWidth;
    private int rasterHeight;
    private double renderScale = 1.0;
    private double firstPersonResolution = 0.78;
    private double averageFrameMillis = 8.0;
    private double lastRenderMillis;
    private long lastQualityAdjustment;
    private double targetX;
    private double targetY;
    private double targetZ;
    private double scale;
    private double focal;
    private double distance;
    private double cosHeading;
    private double sinHeading;
    private double cosTilt;
    private double sinTilt;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private double lastGroundRadius = Double.POSITIVE_INFINITY;

    // Frame buffers.
    private BufferedImage frameImage;
    private int[] frame;
    private float[] depth;
    private float[] pickX;
    private float[] pickY;
    private final Triangles triangleBuffer = new Triangles(RING_CELLS * RING_CELLS * 8);
    private final RingCache[] ringCaches = new RingCache[14];
    private double[] terrainScreen = new double[(RING_CELLS + 1) * (RING_CELLS + 1) * 3];
    private boolean[] terrainValid = new boolean[(RING_CELLS + 1) * (RING_CELLS + 1)];
    private int[] bandCounts = new int[0];
    private int[] bandFill = new int[0];
    private short[] firstBand = new short[0];
    private short[] lastBand = new short[0];
    private int[][] bandLists = new int[0][];
    private boolean fastTextureSampling;
    private final double[] visibilityProjection = new double[3];

    /** A top-down image of the map covering a square of ground. */
    private static final class MapTexture {
        final int[] pixels;
        final int size;
        final double x0;
        final double y0;
        final double scale;

        MapTexture(BufferedImage image, double x0, double y0, double scale) {
            this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            this.size = image.getWidth();
            this.x0 = x0;
            this.y0 = y0;
            this.scale = scale;
        }

        boolean covers(double x, double y, double radius) {
            return x - radius >= x0 && y - radius >= y0 && x + radius <= x0 + size / scale && y + radius <= y0 + size / scale;
        }
    }

    /**
     * Two map textures: a far one covering all visible ground, and — when that one is coarser than
     * the screen — a near one around the target at full screen resolution, so close-ups stay sharp.
     */
    private MapTexture far;
    private MapTexture near;
    volatile boolean textureDirty = true;
    private long textureMillis;

    boolean hasHeights() { return heights != null; }

    HeightField heights() { return heights; }

    void setHeights(HeightField heights) {
        this.heights = heights;
        textureDirty = true;
    }

    /** Ground height (world units) with the current exaggeration; negative below the water line. */
    double groundHeight(double x, double y) {
        return heights == null ? 0 : reliefToWorld(heights.elevation(x, y));
    }

    /** Height of whatever is on top: the ground, or the water surface over it. */
    double surfaceHeight(double x, double y) { return Math.max(0, groundHeight(x, y)); }

    double reliefToWorld(double elevation) { return elevation * RELIEF * exaggeration; }

    void setCamera(int width, int height, double targetX, double targetY, double scale) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        // Permit a deeper reduction on 4K displays, while never rendering below 640x360. The
        // complete-frame controller only reaches this floor when it is necessary for responsiveness.
        double minimumScale = Math.max(0.28, Math.max(640.0 / this.width, 360.0 / this.height));
        this.renderScale = firstPerson ? clamp(firstPersonResolution, minimumScale, 1.0) : 1.0;
        this.rasterWidth = Math.max(1, (int) Math.ceil(this.width * renderScale));
        this.rasterHeight = Math.max(1, (int) Math.ceil(this.height * renderScale));
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = surfaceHeight(targetX, targetY);
        this.scale = scale;
        double fieldOfView = firstPerson ? Math.toRadians(firstPersonFieldOfView) : DEFAULT_FIELD_OF_VIEW;
        this.focal = (this.height / 2.0) / Math.tan(fieldOfView / 2);
        this.distance = focal / scale;
        this.cosHeading = Math.cos(heading);
        this.sinHeading = Math.sin(heading);
        this.cosTilt = Math.cos(tilt);
        this.sinTilt = Math.sin(tilt);
        this.textureScale = scale;
        if (firstPerson) {
            // The eye is the camera; the orbit target is just a point half a metre in front of it.
            this.distance = 0.5 * TownMeshes.WORLD_PER_METRE;
            this.scale = focal / distance;
            this.textureScale = MAX_TEXTURE / 2.0 / 1.3 / FIRST_PERSON_NEAR_TEXTURE;
            this.cameraX = targetX;
            this.cameraY = targetY;
            this.cameraZ = eyeZ;
            this.targetX = targetX + forwardX() * distance * sinTilt;
            this.targetY = targetY + forwardY() * distance * sinTilt;
            this.targetZ = eyeZ - distance * cosTilt;
            return;
        }
        this.cameraX = targetX - forwardX() * distance * sinTilt;
        this.cameraY = targetY - forwardY() * distance * sinTilt;
        this.cameraZ = targetZ + distance * cosTilt;
        // Never go underground: when looking low over rising ground, lift the whole view.
        double clearance = Math.max(distance * 0.06, 1.7 * TownMeshes.WORLD_PER_METRE);
        double floor = surfaceHeight(cameraX, cameraY) + clearance;
        if (cameraZ < floor) {
            targetZ += floor - cameraZ;
            cameraZ = floor;
        }
    }

    double scale() { return scale; }
    double cameraX() { return cameraX; }
    double cameraY() { return cameraY; }

    void setFirstPersonFieldOfView(double degrees) {
        firstPersonFieldOfView = clamp(degrees, 25.0, 110.0);
    }

    int resolutionPercent() { return (int) Math.round(renderScale * 100); }

    double lastRenderMillis() { return lastRenderMillis; }

    void resetFirstPersonPerformance() {
        firstPersonResolution = 0.78;
        averageFrameMillis = 8.0;
        lastQualityAdjustment = 0;
    }

    /** Feeds the adaptive scaler the complete Swing frame cost, including enlargement and overlays. */
    void recordFrameTime(double millis) {
        if (!firstPerson) return;
        averageFrameMillis = averageFrameMillis * 0.86 + millis * 0.14;
        long now = System.nanoTime();
        if (now - lastQualityAdjustment < 250_000_000L) return;
        lastQualityAdjustment = now;
        if (averageFrameMillis > 9.0) firstPersonResolution = Math.max(0.28, firstPersonResolution - 0.06);
        else if (averageFrameMillis < 6.8) firstPersonResolution = Math.min(1.0, firstPersonResolution + 0.02);
    }

    /** World direction that points right on screen, and the ground direction that points up the screen. */
    double rightX() { return cosHeading; }
    double rightY() { return sinHeading; }
    double forwardX() { return sinHeading; }
    double forwardY() { return -cosHeading; }
    double cosTilt() { return cosTilt; }

    /** Screen position (x, y) and inverse depth of a world point, or null when it is behind the camera. */
    double[] project(double x, double y, double z) {
        double[] out = new double[3];
        return project(x, y, z, out, 0) ? out : null;
    }

    /** Allocation-free projection into {@code out[offset..offset+2]}; false when behind the camera. */
    private boolean project(double x, double y, double z, double[] out, int offset) {
        double rx = x - targetX;
        double ry = y - targetY;
        double a = rx * cosHeading + ry * sinHeading;
        double b = rx * sinHeading - ry * cosHeading;
        double c = z - targetZ;
        double depthAlong = b * sinTilt - c * cosTilt + distance;
        if (depthAlong < distance * 0.02) return false;
        out[offset] = width / 2.0 + focal * a / depthAlong;
        out[offset + 1] = height / 2.0 - focal * (b * cosTilt + c * sinTilt) / depthAlong;
        out[offset + 2] = 1.0 / depthAlong;
        return true;
    }

    /** Projection into the reduced first-person frame buffer. Public projection remains full-size for UI. */
    private boolean projectRaster(double x, double y, double z, double[] out, int offset) {
        if (!project(x, y, z, out, offset)) return false;
        out[offset] *= renderScale;
        out[offset + 1] *= renderScale;
        return true;
    }

    /** How far the view reaches toward the horizon, growing as the camera levels out. */
    private double farLimit() {
        if (firstPerson) return firstPersonTerrainRadius();
        double level = Math.max(0, Math.min(1, (tilt - Math.toRadians(50)) / Math.toRadians(40)));
        double smoothLevel = level * level * (3 - 2 * level);
        // At deep zoom distance is only a few metres. Do not let that collapse the whole horizon:
        // distant mountains still need to exist when the camera is standing near the ground.
        return Math.max(distance * (8 + 70 * level * level), LOW_CAMERA_VIEW * smoothLevel);
    }

    /** Where a screen ray meets the flat plane through the target, limited to a sensible distance. */
    Point2D.Double groundPoint(double sx, double sy) {
        double q = (height / 2.0 - sy) / focal;
        double denominator = cosTilt - q * sinTilt;
        double reach = farLimit();
        double b = denominator <= 1e-6 ? reach : Math.min(reach, q * distance / denominator);
        double depthAlong = b * sinTilt + distance;
        double a = (sx - width / 2.0) / focal * depthAlong;
        return new Point2D.Double(targetX + a * cosHeading + b * sinHeading, targetY + a * sinHeading - b * cosHeading);
    }

    /** Whether a world-space box lies within the ground the camera can currently see. */
    boolean nearGround(double minX, double minY, double maxX, double maxY) {
        double r = lastGroundRadius;
        return maxX >= targetX - r && minX <= targetX + r && maxY >= targetY - r && minY <= targetY + r;
    }

    /** Cheap broad-phase culling for billboard clusters before testing their individual points. */
    boolean horizontallyVisible(double x, double y, double radius) {
        if (!firstPerson) return true;
        double dx = x - cameraX;
        double dy = y - cameraY;
        double forward = dx * sinHeading - dy * cosHeading;
        double right = dx * cosHeading + dy * sinHeading;
        double sideSlope = width / (2.0 * focal) + 0.06;
        // The radius term keeps a cluster whose centre is just behind an edge of the view.
        return forward + radius > 0
                && Math.abs(right) <= Math.max(0, forward) * sideSlope + radius * (1 + sideSlope);
    }

    /**
     * First person has no fog or artificial horizon, so its progressively coarser outer rings must
     * reach every map edge. Otherwise a high viewpoint can see billboards whose ground was stopped
     * by an arbitrary radius. The nested rings make this cheap: distance adds a ring, not a dense
     * full-map mesh.
     */
    private double firstPersonTerrainRadius() {
        double edge = Math.max(Math.max(Math.abs(targetX), Math.abs(World.GRID_SIZE - targetX)),
                Math.max(Math.abs(targetY), Math.abs(World.GRID_SIZE - targetY)));
        return Math.max(FIRST_PERSON_VIEW, edge + 0.3);
    }

    /** Radius around the target covering everything the camera can see on the ground. */
    double groundRadius() {
        if (firstPerson) {
            lastGroundRadius = firstPersonTerrainRadius();
            return lastGroundRadius;
        }
        double radius = 0;
        double[][] corners = {{0, 0}, {width, 0}, {0, height}, {width, height}, {width / 2.0, 0}};
        for (double[] corner : corners) {
            Point2D.Double ground = groundPoint(corner[0], corner[1]);
            radius = Math.max(radius, Math.hypot(ground.x - targetX, ground.y - targetY));
        }
        // Raised ground can show a little beyond the flat-plane estimate.
        lastGroundRadius = Math.min(radius * 1.15 + reliefToWorld(0.3), World.GRID_SIZE * 1.5);
        return lastGroundRadius;
    }

    /** Whether the far texture no longer covers the visible ground well enough. */
    boolean needsTexture(double radius) {
        return needs(far, radius, Math.min(textureScale, MAX_TEXTURE / (2 * radius)));
    }

    /** Ground radius the near texture should cover, or 0 when the far texture is already sharp enough. */
    double nearRadius(double radius) {
        double farScale = Math.min(textureScale, MAX_TEXTURE / (2 * radius * 1.3));
        if (farScale >= textureScale * 0.8) return 0;
        return Math.min(radius, MAX_TEXTURE / 2.0 / 1.3 / textureScale);
    }

    boolean needsNearTexture(double radius) { return needs(near, radius, textureScale); }

    private boolean needs(MapTexture texture, double radius, double wanted) {
        if (texture == null) return true;
        boolean sharpEnough = texture.scale >= wanted * 0.75 && texture.scale <= wanted * 3.0;
        return !texture.covers(targetX, targetY, radius) || !sharpEnough;
    }

    void dropNearTexture() { near = null; }

    /** Texture size and scale to render for a view of this radius (the square is padded for panning). */
    double[] textureFrame(double radius) {
        double padded = radius * 1.3;
        double wanted = Math.min(textureScale, MAX_TEXTURE / (2 * padded));
        int size = (int) Math.min(MAX_TEXTURE, Math.max(256, Math.ceil(2 * padded * wanted)));
        return new double[]{size, size / (2 * padded), targetX - padded, targetY - padded};
    }

    /** Newly arrived tiles redraw the textures at most this often (less often while walking, to avoid stutter). */
    boolean textureRecentlyMade(long now) { return now - textureMillis < (firstPerson ? 1500 : 300); }

    void setTexture(BufferedImage image, double x0, double y0, double pixelsPerWorld, boolean nearTexture) {
        MapTexture texture = new MapTexture(image, x0, y0, pixelsPerWorld);
        if (nearTexture) {
            near = texture;
            return;
        }
        far = texture;
        textureDirty = false;
        textureMillis = System.currentTimeMillis();
    }

    /** Pixels per metre at a world point on the surface (used to choose which towns get 3D houses). */
    double pixelsPerMetreAt(double x, double y) {
        double[] p = project(x, y, surfaceHeight(x, y));
        return p == null ? 0 : focal * p[2] * TownMeshes.WORLD_PER_METRE;
    }

    // ================================================================== rendering

    /** Screen-space triangles waiting to be drawn. */
    private static final class Triangles {
        double[] sx, sy, iw, wx, wy, shade, u, v;   // 3 per triangle
        int[] color;
        byte[] kind;                                 // -1 terrain, else a TownMeshes kind
        float[] door;                                // 2 per triangle
        float[] pixelsPerMetre;
        int count;

        Triangles(int capacity) { allocate(Math.max(64, capacity)); }

        private void allocate(int capacity) {
            sx = grow(sx, capacity * 3); sy = grow(sy, capacity * 3); iw = grow(iw, capacity * 3);
            wx = grow(wx, capacity * 3); wy = grow(wy, capacity * 3); shade = grow(shade, capacity * 3);
            u = grow(u, capacity * 3); v = grow(v, capacity * 3);
            color = color == null ? new int[capacity] : Arrays.copyOf(color, capacity);
            kind = kind == null ? new byte[capacity] : Arrays.copyOf(kind, capacity);
            door = door == null ? new float[capacity * 2] : Arrays.copyOf(door, capacity * 2);
            pixelsPerMetre = pixelsPerMetre == null ? new float[capacity] : Arrays.copyOf(pixelsPerMetre, capacity);
        }

        private static double[] grow(double[] array, int size) {
            return array == null ? new double[size] : Arrays.copyOf(array, size);
        }

        int add() {
            if (count == color.length) allocate(color.length * 2);
            return count++;
        }

        void reset() { count = 0; }
    }

    /** Reuses expensive height/noise samples while walking inside the same snapped terrain grid. */
    private final class RingCache {
        int cells;
        long originX = Long.MIN_VALUE;
        long originY = Long.MIN_VALUE;
        double cell = Double.NaN;
        double cachedExaggeration = Double.NaN;
        double[] x;
        double[] y;
        double[] z;
        double[] shade;

        void prepare(int n, double minX, double minY, double cellSize) {
            long gridX = Math.round(minX / cellSize);
            long gridY = Math.round(minY / cellSize);
            if (cells == n && originX == gridX && originY == gridY
                    && Double.doubleToLongBits(cell) == Double.doubleToLongBits(cellSize)
                    && cachedExaggeration == exaggeration) return;

            int side = n + 1;
            int vertices = side * side;
            double[] oldZ = z;
            int oldCells = cells;
            long oldOriginX = originX;
            long oldOriginY = originY;
            double oldCell = cell;
            double oldExaggeration = cachedExaggeration;
            x = new double[vertices];
            y = new double[vertices];
            z = new double[vertices];
            boolean canReuse = oldZ != null && oldCells == n && oldCell == cellSize
                    && oldExaggeration == exaggeration;
            for (int j = 0; j < side; j++) {
                long gy = gridY + j;
                for (int i = 0; i < side; i++) {
                    long gx = gridX + i;
                    int index = j * side + i;
                    x[index] = gx * cellSize;
                    y[index] = gy * cellSize;
                    int oldI = (int) (gx - oldOriginX);
                    int oldJ = (int) (gy - oldOriginY);
                    if (canReuse && oldI >= 0 && oldJ >= 0 && oldI <= n && oldJ <= n) {
                        z[index] = oldZ[oldJ * side + oldI];
                    } else {
                        z[index] = heights == null ? 0
                                : reliefToWorld(heights.elevation(x[index], y[index], cellSize * 2.5));
                    }
                }
            }
            shade = new double[vertices];
            for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
                shade[j * side + i] = shade(x, y, z, i, j, n);
            }
            cells = n;
            originX = gridX;
            originY = gridY;
            cell = cellSize;
            cachedExaggeration = exaggeration;
        }
    }

    /** Draws terrain, houses and water into the frame buffer. */
    BufferedImage render(List<TownMeshes.Mesh> towns) {
        long renderStarted = System.nanoTime();
        if (frameImage == null || frameImage.getWidth() != rasterWidth || frameImage.getHeight() != rasterHeight) {
            frameImage = new BufferedImage(rasterWidth, rasterHeight, BufferedImage.TYPE_INT_RGB);
            frame = ((DataBufferInt) frameImage.getRaster().getDataBuffer()).getData();
            depth = new float[rasterWidth * rasterHeight];
            pickX = new float[rasterWidth * rasterHeight];
            pickY = new float[rasterWidth * rasterHeight];
        }
        // Sky: dark overhead, brightening toward the horizon line.
        double rasterFocal = focal * renderScale;
        double horizon = sinTilt > 1e-3 ? rasterHeight / 2.0 - rasterFocal * cosTilt / sinTilt : Double.NEGATIVE_INFINITY;
        for (int y = 0; y < rasterHeight; y++) {
            double t = Math.max(0, 1 - (horizon - y) / (rasterHeight * 0.9));
            int sky = y < horizon ? mix(SKY_TOP, SKY_HORIZON, t * t) : SKY_HORIZON;
            Arrays.fill(frame, y * rasterWidth, (y + 1) * rasterWidth, sky);
        }
        Arrays.fill(depth, 0f);
        Arrays.fill(pickX, Float.NaN);
        if (far != null) {
            double radius = groundRadius();
            triangleBuffer.reset();
            terrainRings(triangleBuffer, radius);
            houses(triangleBuffer, towns);
            fastTextureSampling = firstPerson && renderScale < 0.90;
            rasterize(triangleBuffer);
            water(radius);
        }
        recordRenderTime((System.nanoTime() - renderStarted) / 1_000_000.0);
        return frameImage;
    }

    private void recordRenderTime(double millis) {
        lastRenderMillis = millis;
    }

    /**
     * Nested square rings of terrain around the target, each three times the size (and cell size)
     * of the one inside it, so the detail near the camera is fine and the far ground stays cheap.
     */
    private void terrainRings(Triangles out, double radius) {
        int initialCells = firstPerson ? FIRST_PERSON_RING_CELLS : RING_CELLS;
        double[] screen = terrainScreen;
        boolean[] valid = terrainValid;
        double half = Math.max(distance * 1.2, 4 * TownMeshes.WORLD_PER_METRE * initialCells);
        double innerMinX = Double.NaN, innerMinY = 0, innerMaxX = 0, innerMaxY = 0;
        for (int ring = 0; ring < 14; ring++) {
            int n = ringCells(ring);
            int side = n + 1;
            int vertices = side * side;
            if (terrainScreen.length < vertices * 3) terrainScreen = new double[vertices * 3];
            if (terrainValid.length < vertices) terrainValid = new boolean[vertices];
            screen = terrainScreen;
            valid = terrainValid;
            double cell = 2 * half / n;
            // Snap to the ring's own grid so the surface does not shimmer while panning.
            double minX = Math.floor((targetX - half) / cell) * cell;
            double minY = Math.floor((targetY - half) / cell) * cell;
            RingCache cache = ringCaches[ring];
            if (cache == null) ringCaches[ring] = cache = new RingCache();
            cache.prepare(n, minX, minY, cell);
            double[] x = cache.x;
            double[] y = cache.y;
            double[] z = cache.z;
            double[] shade = cache.shade;
            for (int index = 0; index < vertices; index++) {
                valid[index] = projectRaster(x[index], y[index], z[index], screen, index * 3);
            }
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double qx = minX + i * cell;
                    double qy = minY + j * cell;
                    if (qx + cell < -0.3 || qy + cell < -0.3 || qx > World.GRID_SIZE + 0.3 || qy > World.GRID_SIZE + 0.3) continue;
                    // The middle belongs to the finer ring inside (with a one-cell overlap to hide seams).
                    if (!Double.isNaN(innerMinX) && qx >= innerMinX + cell && qx + cell <= innerMaxX - cell
                            && qy >= innerMinY + cell && qy + cell <= innerMaxY - cell) continue;
                    int a = j * side + i;
                    int b = a + 1;
                    int c = a + side;
                    int d = c + 1;
                    terrainTriangle(out, x, y, z, screen, valid, shade, a, b, c);
                    terrainTriangle(out, x, y, z, screen, valid, shade, b, d, c);
                }
            }
            innerMinX = minX;
            innerMinY = minY;
            innerMaxX = minX + n * cell;
            innerMaxY = minY + n * cell;
            if (half >= radius) break;
            half *= 3;
        }
    }

    /** Fine near the camera, progressively cheaper toward the horizon where only silhouettes matter. */
    private int ringCells(int ring) {
        if (firstPerson) {
            if (ring < 2) return FIRST_PERSON_RING_CELLS;
            if (ring == 2) return 64;
            return 48;
        }
        if (ring < 2) return RING_CELLS;
        if (ring == 2) return 96;
        if (ring == 3) return 72;
        if (ring == 4) return 56;
        return 48;
    }

    private void terrainTriangle(Triangles out, double[] x, double[] y, double[] z, double[] screen, boolean[] valid,
                                 double[] shade, int a, int b, int c) {
        if (!valid[a] || !valid[b] || !valid[c]) {
            if (!valid[a] && !valid[b] && !valid[c]) return;
            addClipped(out, new double[][]{{x[a], y[a], z[a], shade[a], 0, 0}, {x[b], y[b], z[b], shade[b], 0, 0},
                    {x[c], y[c], z[c], shade[c], 0, 0}}, (byte) -1, 0, Float.NaN, Float.NaN);
            return;
        }
        double ax = screen[a * 3], bx = screen[b * 3], cx = screen[c * 3];
        double ay = screen[a * 3 + 1], by = screen[b * 3 + 1], cy = screen[c * 3 + 1];
        if (Math.max(ax, Math.max(bx, cx)) < 0 || Math.min(ax, Math.min(bx, cx)) >= rasterWidth
                || Math.max(ay, Math.max(by, cy)) < 0 || Math.min(ay, Math.min(by, cy)) >= rasterHeight) return;
        int t = out.add();
        int[] ids = {a, b, c};
        for (int k = 0; k < 3; k++) {
            int id = ids[k];
            int o = t * 3 + k;
            out.sx[o] = screen[id * 3];
            out.sy[o] = screen[id * 3 + 1];
            out.iw[o] = screen[id * 3 + 2];
            out.wx[o] = x[id];
            out.wy[o] = y[id];
            out.shade[o] = shade[id];
        }
        out.kind[t] = -1;
    }

    /** Distance in front of the camera, along its view direction, of a world point. */
    private double depthOf(double x, double y, double z) {
        double rx = x - targetX;
        double ry = y - targetY;
        return (rx * sinHeading - ry * cosHeading) * sinTilt - (z - targetZ) * cosTilt + distance;
    }

    /**
     * Adds a triangle that crosses the near plane: only the part in front of it, as one or two
     * triangles. Each corner is {x, y, z, shade, u, v}; kind -1 is terrain.
     */
    private void addClipped(Triangles out, double[][] corners, byte kind, int color, float doorStart, float doorEnd) {
        double near = distance * 0.02 * 1.001;
        double[] depths = new double[3];
        for (int k = 0; k < 3; k++) depths[k] = depthOf(corners[k][0], corners[k][1], corners[k][2]);
        double[][] polygon = new double[4][];
        int count = 0;
        for (int k = 0; k < 3; k++) {
            double[] a = corners[k];
            double[] b = corners[(k + 1) % 3];
            double da = depths[k];
            double db = depths[(k + 1) % 3];
            if (da >= near) polygon[count++] = a;
            if ((da >= near) != (db >= near)) {
                double t = (near - da) / (db - da);
                double[] cut = new double[6];
                for (int i = 0; i < 6; i++) cut[i] = a[i] + (b[i] - a[i]) * t;
                polygon[count++] = cut;
            }
        }
        if (count < 3) return;
        double[] screen = new double[count * 3];
        for (int i = 0; i < count; i++) {
            if (!projectRaster(polygon[i][0], polygon[i][1], polygon[i][2], screen, i * 3)) return;
        }
        for (int i = 1; i + 1 < count; i++) {
            int index = out.add();
            int[] ids = {0, i, i + 1};
            double deepest = 0;
            for (int k = 0; k < 3; k++) {
                int id = ids[k];
                int w = index * 3 + k;
                out.sx[w] = screen[id * 3];
                out.sy[w] = screen[id * 3 + 1];
                out.iw[w] = screen[id * 3 + 2];
                out.wx[w] = polygon[id][0];
                out.wy[w] = polygon[id][1];
                out.shade[w] = polygon[id][3];
                out.u[w] = polygon[id][4];
                out.v[w] = polygon[id][5];
                deepest = Math.max(deepest, screen[id * 3 + 2]);
            }
            out.kind[index] = kind;
            out.color[index] = color;
            out.door[index * 2] = doorStart;
            out.door[index * 2 + 1] = doorEnd;
            out.pixelsPerMetre[index] = (float) (focal * deepest * TownMeshes.WORLD_PER_METRE);
        }
    }

    /** Hill shading from the grid's own neighbours, lit from the north-west, judged against flat ground. */
    private static double shade(double[] x, double[] y, double[] z, int i, int j, int n) {
        int side = n + 1;
        int id = j * side + i;
        int left = i > 0 ? id - 1 : id, right = i < n ? id + 1 : id;
        int up = j > 0 ? id - side : id, down = j < n ? id + side : id;
        double dzdx = (z[right] - z[left]) / Math.max(1e-15, x[right] - x[left]);
        double dzdy = (z[down] - z[up]) / Math.max(1e-15, y[down] - y[up]);
        double length = Math.sqrt(dzdx * dzdx + dzdy * dzdy + 1);
        double lit = (-dzdx * LIGHT_X - dzdy * LIGHT_Y + LIGHT_Z) / length;
        return Math.max(0.45, Math.min(1.3, 1 + 1.3 * (lit - LIGHT_Z)));
    }

    /** House and town-wall triangles that face the camera. */
    private void houses(Triangles out, List<TownMeshes.Mesh> towns) {
        if (towns == null || towns.isEmpty()) return;
        double metre = TownMeshes.WORLD_PER_METRE;
        double[] p = new double[9];
        for (TownMeshes.Mesh mesh : towns) {
            float[] v = mesh.vertices;
            double ox = mesh.centerX;
            double oy = mesh.centerY;
            for (int t = 0; t < mesh.triangles; t++) {
                int o = t * 27;
                double nx = v[o + 6], ny = v[o + 7], nz = v[o + 8];
                double ax = ox + v[o], ay = oy + v[o + 1];
                double az = reliefToWorld(v[o + 2]) + v[o + 3] * metre;
                // Back faces are never seen.
                if ((cameraX - ax) * nx + (cameraY - ay) * ny + (cameraZ - az) * nz <= 0) continue;
                boolean ok = true;
                for (int k = 0; k < 3 && ok; k++) {
                    int q = o + k * 9;
                    ok = projectRaster(ox + v[q], oy + v[q + 1], reliefToWorld(v[q + 2]) + v[q + 3] * metre, p, k * 3);
                }
                if (!ok) {
                    // Partly behind the eye (a wall right beside a walker): keep the part in front.
                    double light = 0.55 + 0.6 * Math.max(0, nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z);
                    double[][] corners = new double[3][];
                    for (int k = 0; k < 3; k++) {
                        int q = o + k * 9;
                        corners[k] = new double[]{ox + v[q], oy + v[q + 1], reliefToWorld(v[q + 2]) + v[q + 3] * metre,
                                light, v[q + 4], v[q + 5]};
                    }
                    addClipped(out, corners, mesh.kinds[t], mesh.colors[t], mesh.doors[t * 2], mesh.doors[t * 2 + 1]);
                    continue;
                }
                double minX = Math.min(p[0], Math.min(p[3], p[6])), maxX = Math.max(p[0], Math.max(p[3], p[6]));
                double minY = Math.min(p[1], Math.min(p[4], p[7])), maxY = Math.max(p[1], Math.max(p[4], p[7]));
                if (maxX < 0 || minX >= rasterWidth || maxY < 0 || minY >= rasterHeight) continue;
                // Too small to cover any pixel centre.
                if (Math.floor(maxX - 0.5) < Math.ceil(minX - 0.5) || Math.floor(maxY - 0.5) < Math.ceil(minY - 0.5)) continue;
                int index = out.add();
                double light = 0.55 + 0.6 * Math.max(0, nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z);
                for (int k = 0; k < 3; k++) {
                    int q = o + k * 9;
                    int w = index * 3 + k;
                    out.sx[w] = p[k * 3];
                    out.sy[w] = p[k * 3 + 1];
                    out.iw[w] = p[k * 3 + 2];
                    out.wx[w] = ox + v[q];
                    out.wy[w] = oy + v[q + 1];
                    out.u[w] = v[q + 4];
                    out.v[w] = v[q + 5];
                    out.shade[w] = light;
                }
                out.color[index] = mesh.colors[t];
                out.kind[index] = mesh.kinds[t];
                out.door[index * 2] = mesh.doors[t * 2];
                out.door[index * 2 + 1] = mesh.doors[t * 2 + 1];
                out.pixelsPerMetre[index] = (float) (focal * renderScale * Math.max(p[2], Math.max(p[5], p[8])) * metre);
            }
        }
    }

    /** Draws all triangles; each band of rows is handled by one thread over the triangles that touch it. */
    private void rasterize(Triangles triangles) {
        int bands = Math.max(1, Math.min(64, Runtime.getRuntime().availableProcessors() * 4));
        int total = triangles.count;
        if (bandCounts.length != bands) {
            bandCounts = new int[bands];
            bandFill = new int[bands];
            bandLists = new int[bands][];
        } else Arrays.fill(bandCounts, 0);
        if (firstBand.length < total) {
            firstBand = new short[Math.max(total, firstBand.length * 2 + 1024)];
            lastBand = new short[firstBand.length];
        }
        for (int t = 0; t < total; t++) {
            double minY = Math.min(triangles.sy[t * 3], Math.min(triangles.sy[t * 3 + 1], triangles.sy[t * 3 + 2]));
            double maxY = Math.max(triangles.sy[t * 3], Math.max(triangles.sy[t * 3 + 1], triangles.sy[t * 3 + 2]));
            firstBand[t] = (short) Math.max(0, Math.min(bands - 1, (long) Math.floor(minY) * bands / rasterHeight));
            lastBand[t] = (short) Math.max(0, Math.min(bands - 1, (long) Math.ceil(maxY) * bands / rasterHeight));
            for (int b = firstBand[t]; b <= lastBand[t]; b++) bandCounts[b]++;
        }
        for (int b = 0; b < bands; b++) {
            if (bandLists[b] == null || bandLists[b].length < bandCounts[b]) {
                bandLists[b] = new int[Math.max(bandCounts[b], bandLists[b] == null ? 256 : bandLists[b].length * 2)];
            }
            bandFill[b] = 0;
        }
        for (int t = 0; t < total; t++) for (int b = firstBand[t]; b <= lastBand[t]; b++) {
            bandLists[b][bandFill[b]++] = t;
        }
        IntStream.range(0, bands).parallel().forEach(band -> {
            int rowStart = band * rasterHeight / bands;
            int rowEnd = (band + 1) * rasterHeight / bands - 1;
            for (int index = 0; index < bandFill[band]; index++) {
                draw(triangles, bandLists[band][index], rowStart, rowEnd);
            }
        });
    }

    private void draw(Triangles tr, int t, int rowStart, int rowEnd) {
        int o = t * 3;
        double x0 = tr.sx[o], y0 = tr.sy[o], x1 = tr.sx[o + 1], y1 = tr.sy[o + 1], x2 = tr.sx[o + 2], y2 = tr.sy[o + 2];
        double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-9) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(rasterWidth - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(rowStart, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(rowEnd, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        double inverseArea = 1.0 / area;
        double iw0 = tr.iw[o], iw1 = tr.iw[o + 1], iw2 = tr.iw[o + 2];
        // Perspective-correct attributes: interpolate value / depth, then divide by interpolated 1 / depth.
        double ux0 = tr.wx[o] * iw0, ux1 = tr.wx[o + 1] * iw1, ux2 = tr.wx[o + 2] * iw2;
        double uy0 = tr.wy[o] * iw0, uy1 = tr.wy[o + 1] * iw1, uy2 = tr.wy[o + 2] * iw2;
        double s0 = tr.shade[o], s1 = tr.shade[o + 1], s2 = tr.shade[o + 2];
        byte kind = tr.kind[t];
        boolean house = kind >= 0;
        boolean facade = kind == TownMeshes.WALL && tr.pixelsPerMetre[t] >= 1.2f;
        double pu0 = tr.u[o] * iw0, pu1 = tr.u[o + 1] * iw1, pu2 = tr.u[o + 2] * iw2;
        double pv0 = tr.v[o] * iw0, pv1 = tr.v[o + 1] * iw1, pv2 = tr.v[o + 2] * iw2;
        float doorStart = tr.door[t * 2];
        float doorEnd = tr.door[t * 2 + 1];
        int base = tr.color[t];
        for (int py = minY; py <= maxY; py++) {
            double cy = py + 0.5;
            int row = py * rasterWidth;
            for (int px = minX; px <= maxX; px++) {
                double cx = px + 0.5;
                double w0 = ((x1 - cx) * (y2 - cy) - (x2 - cx) * (y1 - cy)) * inverseArea;
                double w1 = ((x2 - cx) * (y0 - cy) - (x0 - cx) * (y2 - cy)) * inverseArea;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-7 || w1 < -1e-7 || w2 < -1e-7) continue;
                double w = w0 * iw0 + w1 * iw1 + w2 * iw2;
                int index = row + px;
                if (w <= depth[index]) continue;
                depth[index] = (float) w;
                double worldX = (w0 * ux0 + w1 * ux1 + w2 * ux2) / w;
                double worldY = (w0 * uy0 + w1 * uy1 + w2 * uy2) / w;
                pickX[index] = (float) worldX;
                pickY[index] = (float) worldY;
                int color;
                if (!house) {
                    MapTexture source = near != null && near.covers(worldX, worldY, 0) ? near : far;
                    double textureX = (worldX - source.x0) * source.scale - 0.5;
                    double textureY = (worldY - source.y0) * source.scale - 0.5;
                    color = fastTextureSampling ? sampleNearest(source, textureX, textureY)
                            : sample(source, textureX, textureY);
                } else if (facade) {
                    double u = (w0 * pu0 + w1 * pu1 + w2 * pu2) / w;
                    double v = (w0 * pv0 + w1 * pv1 + w2 * pv2) / w;
                    color = facade(base, u, v, doorStart, doorEnd);
                } else {
                    color = base;
                }
                double light = w0 * s0 + w1 * s1 + w2 * s2;
                int r = (int) Math.min(255, ((color >> 16) & 255) * light);
                int g = (int) Math.min(255, ((color >> 8) & 255) * light);
                int b = (int) Math.min(255, (color & 255) * light);
                int lit = (r << 16) | (g << 8) | b;
                frame[index] = lit;
            }
        }
    }

    /** Lightweight procedural facade: rows of windows on every storey, and a plain rectangle for the door. */
    private static int facade(int wall, double u, double v, float doorStart, float doorEnd) {
        if (u >= doorStart && u <= doorEnd && v >= 0 && v <= 2.2) return DOOR;   // false when NaN
        if (v < 0.6) return wall;
        double inStorey = v - Math.floor(v / TownMeshes.STOREY) * TownMeshes.STOREY;
        double along = u - Math.floor(u / 2.6) * 2.6;
        if (inStorey >= 1.0 && inStorey <= 2.3 && along >= 0.8 && along <= 1.8) {
            // Keep windows clear of the door.
            if (!(u >= doorStart - 0.5 && u <= doorEnd + 0.5 && v <= 3.0)) return WINDOW;
        }
        return wall;
    }

    /**
     * The water sheet: a flat plane at the water line, drawn wherever it is nearer than the ground
     * (seas, lakes and river channels all lie below it). Shallow water lets the bed show through,
     * and at grazing angles the surface reflects the sky.
     */
    private void water(double radius) {
        double c = -targetZ;   // the water line, relative to the target
        // Depth (along the view) over which water turns from clear to fully coloured: rivers are shallow
        // and show their sandy bed, lakes and seas are deep blue.
        double shallow = reliefToWorld(0.008);
        double reach = Math.max(radius * 1.6, distance * 4);
        double rasterFocal = focal * renderScale;
        IntStream.range(0, rasterHeight).parallel().forEach(py -> {
            double q = (rasterHeight / 2.0 - (py + 0.5)) / rasterFocal;
            double denominator = cosTilt - q * sinTilt;
            if (Math.abs(denominator) < 1e-12) return;
            double b = (q * (distance - c * cosTilt) - c * sinTilt) / denominator;
            double depthAlong = b * sinTilt - c * cosTilt + distance;
            if (depthAlong <= distance * 0.02 || depthAlong > reach) return;
            float w = (float) (1.0 / depthAlong);
            int row = py * rasterWidth;
            double grazingBase = Math.max(0, cameraZ);
            for (int px = 0; px < rasterWidth; px++) {
                int index = row + px;
                if (w <= depth[index]) continue;
                double a = (px + 0.5 - rasterWidth / 2.0) / rasterFocal * depthAlong;
                double worldX = targetX + a * cosHeading + b * sinHeading;
                double worldY = targetY + a * sinHeading - b * cosHeading;
                if (worldX < 0 || worldY < 0 || worldX > World.GRID_SIZE || worldY > World.GRID_SIZE) continue;
                // How far the eye looks through water before reaching the bed.
                double through = depth[index] == 0 ? Double.POSITIVE_INFINITY : 1.0 / depth[index] - depthAlong;
                double opacity = 0.4 + 0.55 * Math.min(1, through / shallow);
                int color = mix(depth[index] == 0 ? WATER : frame[index], WATER, opacity);
                double ray = Math.sqrt(depthAlong * depthAlong + a * a);
                double steep = Math.min(1, grazingBase / Math.max(1e-15, ray));
                double reflection = 1 - steep;
                reflection *= reflection;
                reflection = reflection * reflection * reflection;
                color = mix(color, SKY_HORIZON, reflection * 0.65);
                frame[index] = color;
                depth[index] = w;
                pickX[index] = (float) worldX;
                pickY[index] = (float) worldY;
            }
        });
    }

    /** Bilinear texture lookup, so the map stays smooth where it is magnified near the camera. */
    private static int sample(MapTexture source, double u, double v) {
        int[] texture = source.pixels;
        int textureSize = source.size;
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        if (x0 < 0 || y0 < 0 || x0 + 1 >= textureSize || y0 + 1 >= textureSize) {
            int x = Math.max(0, Math.min(textureSize - 1, x0));
            int y = Math.max(0, Math.min(textureSize - 1, y0));
            return u < -1 || v < -1 || u > textureSize || v > textureSize ? 0x0d1117 : texture[y * textureSize + x];
        }
        double fx = u - x0;
        double fy = v - y0;
        int index = y0 * textureSize + x0;
        int c00 = texture[index];
        int c10 = texture[index + 1];
        int c01 = texture[index + textureSize];
        int c11 = texture[index + textureSize + 1];
        double w00 = (1 - fx) * (1 - fy);
        double w10 = fx * (1 - fy);
        double w01 = (1 - fx) * fy;
        double w11 = fx * fy;
        int r = (int) (((c00 >> 16) & 255) * w00 + ((c10 >> 16) & 255) * w10 + ((c01 >> 16) & 255) * w01 + ((c11 >> 16) & 255) * w11);
        int g = (int) (((c00 >> 8) & 255) * w00 + ((c10 >> 8) & 255) * w10 + ((c01 >> 8) & 255) * w01 + ((c11 >> 8) & 255) * w11);
        int b = (int) ((c00 & 255) * w00 + (c10 & 255) * w10 + (c01 & 255) * w01 + (c11 & 255) * w11);
        return (r << 16) | (g << 8) | b;
    }

    private static int sampleNearest(MapTexture source, double u, double v) {
        int x = Math.max(0, Math.min(source.size - 1, (int) (u + 0.5)));
        int y = Math.max(0, Math.min(source.size - 1, (int) (v + 0.5)));
        return source.pixels[y * source.size + x];
    }

    /** The ground, water or house point shown at a screen pixel, or null for sky. */
    Point2D.Double pick(double sx, double sy) {
        int x = (int) (sx * renderScale);
        int y = (int) (sy * renderScale);
        if (pickX == null || x < 0 || y < 0 || x >= rasterWidth || y >= rasterHeight || frameImage == null
                || frameImage.getWidth() != rasterWidth) return null;
        int index = y * rasterWidth + x;
        if (Float.isNaN(pickX[index])) return null;
        return new Point2D.Double(pickX[index], pickY[index]);
    }

    /** Whether a surface point is in view and not hidden behind nearer terrain (for icons and labels). */
    boolean visible(double x, double y) {
        // Billboards must never outlive the terrain mesh beneath them. This also protects against a
        // future reduced terrain-distance setting reintroducing towns floating in empty sky.
        if (firstPerson && (Math.abs(x - targetX) > lastGroundRadius
                || Math.abs(y - targetY) > lastGroundRadius)) return false;
        double[] p = visibilityProjection;
        if (!project(x, y, surfaceHeight(x, y), p, 0) || depth == null) return false;
        int px = (int) (p[0] * renderScale);
        int py = (int) (p[1] * renderScale);
        if (px < 0 || py < 0 || px >= rasterWidth || py >= rasterHeight) return true;
        float stored = depth[py * rasterWidth + px];
        // In first person, an empty depth pixel is sky. Never draw a place marker into it: doing so
        // is what made distant towns appear to float where an earlier terrain mesh had ended.
        if (firstPerson && stored == 0) return false;
        return stored == 0 || p[2] >= stored * 0.985;
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
