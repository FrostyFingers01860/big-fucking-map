package districts.ui;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Stream;

/**
 * Google-Maps-style terrain pyramid drawn from the source map's vector data.
 *
 * <p>Level z shows the whole map 256·2^z pixels wide, from level 0 (256 px) to level {@link #TOP_LEVEL}
 * (over two million pixels — sharp at the deepest zoom). Only on-screen tiles are made, newest
 * request first, on background threads. The zoomed-out levels are also saved to disk so later
 * launches open instantly. While a tile is being made, the best lower-resolution tile already in
 * memory is stretched in its place, so the view sharpens as tiles fill in.
 */
final class TerrainTiles {
    static final int TILE = 256;
    /** Deep enough for the maximum zoom (×120000): the whole map would be over 130 million pixels wide. */
    static final int TOP_LEVEL = 19;
    /** Levels up to this are kept on disk (1,365 tiles); deeper ones are cheap to redraw. */
    private static final int DISK_LEVEL = 5;
    private static final int MEMORY_TILES = 512;
    private static final long STALE_NANOS = 1_500_000_000L;
    private static final String RENDERER_VERSION = "vector-v3";

    private final TerrainRenderer renderer;
    private final Path directory;
    private final String stamp;
    private final Runnable repaint;
    private final BlockingDeque<int[]> queue = new LinkedBlockingDeque<>();
    private final Map<String, Long> requested = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> cache = new LinkedHashMap<>(512, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > MEMORY_TILES && !eldest.getKey().startsWith("0/");
        }
    };
    private volatile boolean ready;
    private volatile String problem;

    /**
     * @param sourceStamp identifies the terrain data; the disk cache is rebuilt when it changes
     */
    TerrainTiles(TerrainRenderer renderer, Path tileDirectory, String sourceStamp, Runnable repaint) {
        this.renderer = renderer;
        this.directory = tileDirectory;
        this.stamp = RENDERER_VERSION + ":" + TILE + ":" + sourceStamp + (renderer.for3D() ? ":3d-v2" : "");
        this.repaint = repaint;
        int workers = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));
        for (int index = 0; index < workers; index++) {
            Thread worker = new Thread(this::work, "terrain-tiles-" + index);
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
        Thread starter = new Thread(this::prepare, "terrain-tiles-start");
        starter.setDaemon(true);
        starter.start();
    }

    boolean ready() { return ready; }

    String problem() { return problem; }

    /** Clears the disk cache if the terrain data changed, then makes the whole-map overview tile. */
    private void prepare() {
        try {
            Files.createDirectories(directory);
            Path stampFile = directory.resolve("tiles.stamp");
            boolean current = Files.isRegularFile(stampFile) && Files.readString(stampFile).equals(stamp);
            if (!current) {
                try (Stream<Path> files = Files.walk(directory)) {
                    for (Path file : files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                        if (!file.equals(directory)) Files.deleteIfExists(file);
                    }
                }
                Files.writeString(stampFile, stamp);
            }
            BufferedImage overview = loadOrRender(0, 0, 0);
            synchronized (cache) { cache.put(key(0, 0, 0), overview); }
            ready = true;
            repaint.run();
        } catch (IOException | RuntimeException exception) {
            problem = exception.toString();
            System.err.println("Terrain tiles unavailable: " + exception);
        }
    }

    private void work() {
        while (true) {
            int[] tile;
            try {
                tile = queue.takeLast();
            } catch (InterruptedException interrupted) {
                return;
            }
            String key = key(tile[0], tile[1], tile[2]);
            Long requestedAt = requested.get(key);
            // Skip tiles the view has moved away from while they were waiting.
            if (requestedAt == null || System.nanoTime() - requestedAt > STALE_NANOS) {
                requested.remove(key);
                continue;
            }
            try {
                BufferedImage image = loadOrRender(tile[0], tile[1], tile[2]);
                synchronized (cache) { cache.put(key, image); }
            } catch (IOException | RuntimeException exception) {
                System.err.println("Could not make terrain tile " + key + ": " + exception);
            } finally {
                requested.remove(key);
                repaint.run();
            }
        }
    }

    private BufferedImage loadOrRender(int level, int tx, int ty) throws IOException {
        Path file = directory.resolve(Integer.toString(level)).resolve(tx + "_" + ty + ".png");
        if (level <= DISK_LEVEL && Files.isRegularFile(file)) {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image != null) return image;
        }
        double worldSize = (double) districts.model.World.GRID_SIZE / (1 << level);
        BufferedImage image = renderer.render(tx * worldSize, ty * worldSize, worldSize, TILE);
        if (level <= DISK_LEVEL) {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
            ImageIO.write(image, "png", temporary.toFile());
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return image;
    }

    /** Lowest level whose resolution covers the given on-screen size of the whole map. */
    static int levelFor(double mapScreenPixels) { return levelFor(mapScreenPixels, 0.9); }

    /** Lowest level whose tiles are drawn at no more than 1 / {@code fill} times their own size. */
    static int levelFor(double mapScreenPixels, double fill) {
        for (int z = 0; z < TOP_LEVEL; z++) {
            if ((long) TILE << z >= mapScreenPixels * fill) return z;
        }
        return TOP_LEVEL;
    }

    /**
     * Whether to choose coarser tiles (drawn up to 1.4 times their own size): the 3D view's map
     * textures do, which halves the tiles they need so both textures fit in the memory cache.
     */
    volatile boolean economical;

    /**
     * Draws the visible part of the map. (mapX, mapY) is the screen position of the map's top-left
     * corner and mapSize its on-screen width; only tiles intersecting the viewport are touched.
     */
    int draw(Graphics2D g, double mapX, double mapY, double mapSize, int viewWidth, int viewHeight) {
        if (!ready) return -1;
        int level = levelFor(mapSize, economical ? 0.72 : 0.9);
        int tiles = 1 << level;
        double tileSize = mapSize / tiles;
        int firstX = Math.max(0, (int) Math.floor(-mapX / tileSize));
        int firstY = Math.max(0, (int) Math.floor(-mapY / tileSize));
        int lastX = Math.min(tiles - 1, (int) Math.floor((viewWidth - mapX) / tileSize));
        int lastY = Math.min(tiles - 1, (int) Math.floor((viewHeight - mapY) / tileSize));
        double pixelScale = tileSize / TILE;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, Math.abs(pixelScale - 1) < 0.05
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (pixelScale >= 1.5) {
            drawMosaic(g, level, mapX, mapY, pixelScale, viewWidth, viewHeight);
            return level;
        }
        for (int ty = firstY; ty <= lastY; ty++) {
            for (int tx = firstX; tx <= lastX; tx++) {
                double x0 = mapX + tx * tileSize;
                double y0 = mapY + ty * tileSize;
                BufferedImage tile = cached(level, tx, ty);
                if (tile != null) {
                    drawRegion(g, tile, 0, 0, TILE, x0, y0, tileSize, viewWidth, viewHeight);
                    continue;
                }
                request(level, tx, ty);
                drawFallback(g, level, tx, ty, x0, y0, tileSize, viewWidth, viewHeight);
            }
        }
        return level;
    }

    /** Stretches the matching part of the nearest loaded ancestor tile over a missing tile. */
    private void drawFallback(Graphics2D g, int level, int tx, int ty, double x0, double y0, double tileSize,
                              int viewWidth, int viewHeight) {
        for (int up = 1; up <= level; up++) {
            BufferedImage ancestor = cached(level - up, tx >> up, ty >> up);
            if (ancestor == null) continue;
            double span = (double) TILE / (1 << up);
            double sx = (tx - ((tx >> up) << up)) * span;
            double sy = (ty - ((ty >> up) << up)) * span;
            drawRegion(g, ancestor, sx, sy, span, x0, y0, tileSize, viewWidth, viewHeight);
            return;
        }
    }

    private BufferedImage mosaic;

    /**
     * Beyond the deepest level: copies just the visible pixels of neighbouring tiles into one small
     * image and scales that once, so interpolation runs smoothly across tile boundaries.
     */
    private void drawMosaic(Graphics2D g, int level, double mapX, double mapY, double pixelScale,
                            int viewWidth, int viewHeight) {
        long levelSize = (long) TILE << level;
        int gx0 = (int) Math.max(0, Math.floor(-mapX / pixelScale) - 1);
        int gy0 = (int) Math.max(0, Math.floor(-mapY / pixelScale) - 1);
        int gx1 = (int) Math.min(levelSize, Math.ceil((viewWidth - mapX) / pixelScale) + 1);
        int gy1 = (int) Math.min(levelSize, Math.ceil((viewHeight - mapY) / pixelScale) + 1);
        if (gx1 <= gx0 || gy1 <= gy0) return;
        int width = gx1 - gx0;
        int height = gy1 - gy0;
        if (mosaic == null || mosaic.getWidth() != width || mosaic.getHeight() != height) {
            mosaic = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D m = mosaic.createGraphics();
        m.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int ty = gy0 / TILE; ty <= (gy1 - 1) / TILE; ty++) {
            for (int tx = gx0 / TILE; tx <= (gx1 - 1) / TILE; tx++) {
                int dx = tx * TILE - gx0;
                int dy = ty * TILE - gy0;
                BufferedImage tile = cached(level, tx, ty);
                if (tile != null) {
                    m.drawImage(tile, dx, dy, null);
                    continue;
                }
                request(level, tx, ty);
                for (int up = 1; up <= level; up++) {
                    BufferedImage ancestor = cached(level - up, tx >> up, ty >> up);
                    if (ancestor == null) continue;
                    int span = Math.max(1, TILE >> up);
                    int sx = (tx - ((tx >> up) << up)) * (TILE >> up);
                    int sy = (ty - ((ty >> up) << up)) * (TILE >> up);
                    m.drawImage(ancestor, dx, dy, dx + TILE, dy + TILE, sx, sy, sx + span, sy + span, null);
                    break;
                }
            }
        }
        m.dispose();
        AffineTransform transform = new AffineTransform();
        transform.translate(mapX + gx0 * pixelScale, mapY + gy0 * pixelScale);
        transform.scale(pixelScale, pixelScale);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(mosaic, transform, null);
    }

    /**
     * Draws the square (srcX, srcY, srcSize) of an image onto the screen square (destX, destY, destSize),
     * touching only the source pixels that are actually visible. Scaling a whole tile far beyond the
     * screen and letting Java2D clip it filters inconsistently, which showed as seams.
     */
    private static void drawRegion(Graphics2D g, BufferedImage image, double srcX, double srcY, double srcSize,
                                   double destX, double destY, double destSize, int viewWidth, int viewHeight) {
        double scale = destSize / srcSize;
        double visibleX0 = srcX + Math.max(0, -destX) / scale;
        double visibleY0 = srcY + Math.max(0, -destY) / scale;
        double visibleX1 = srcX + Math.min(destSize, viewWidth - destX) / scale;
        double visibleY1 = srcY + Math.min(destSize, viewHeight - destY) / scale;
        int sx0 = Math.max((int) Math.floor(srcX), (int) Math.floor(visibleX0) - 1);
        int sy0 = Math.max((int) Math.floor(srcY), (int) Math.floor(visibleY0) - 1);
        int sx1 = Math.min((int) Math.ceil(srcX + srcSize), (int) Math.ceil(visibleX1) + 1);
        int sy1 = Math.min((int) Math.ceil(srcY + srcSize), (int) Math.ceil(visibleY1) + 1);
        sx1 = Math.min(sx1, image.getWidth());
        sy1 = Math.min(sy1, image.getHeight());
        if (sx1 <= sx0 || sy1 <= sy0) return;
        BufferedImage part = image.getSubimage(sx0, sy0, sx1 - sx0, sy1 - sy0);
        AffineTransform transform = new AffineTransform();
        transform.translate(destX + (sx0 - srcX) * scale, destY + (sy0 - srcY) * scale);
        transform.scale(scale, scale);
        g.drawImage(part, transform, null);
    }

    private BufferedImage cached(int level, int tx, int ty) {
        synchronized (cache) { return cache.get(key(level, tx, ty)); }
    }

    private void request(int level, int tx, int ty) {
        String key = key(level, tx, ty);
        boolean fresh = requested.put(key, System.nanoTime()) == null;
        if (fresh) queue.addLast(new int[]{level, tx, ty});
    }

    private static String key(int level, int tx, int ty) { return level + "/" + tx + "/" + ty; }
}
