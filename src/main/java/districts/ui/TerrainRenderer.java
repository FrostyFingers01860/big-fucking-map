package districts.ui;

import districts.terrain.TerrainMap;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws the terrain from the source map's vector data at any resolution, following the same
 * layer order and colours as tools/render-fmg-terrain.html: shaded biome cells, lakes, rivers,
 * then a light paper texture. Because it works from polygons rather than a fixed picture, a
 * tile is equally sharp at every zoom level.
 */
final class TerrainRenderer {
    private static final int SPECKS = 26000;
    private static final double MAX_SHORE_PIXELS = 1.5;
    private static final double MAX_RIVER_PIXELS = 5.0;
    private static final double MAX_SPECK_PIXELS = 6.0;
    private static final Color LAKE_EDGE = new Color(207, 235, 239, 128);
    private static final Color RIVER_OUTLINE = new Color(24, 66, 82, 140);
    private static final Color RIVER = new Color(91, 177, 207, 242);
    // The source uses a soft-light blend for its texture; a faint normal blend looks the same here.
    /** Lake and sea beds in the 3D view's texture: the water sheet is drawn over them. */
    private static final Color SAND = new Color(0xd9c28c);
    private static final Color SPECK_LIGHT = new Color(255, 255, 255, 6);
    private static final Color SPECK_DARK = new Color(20, 23, 18, 4);

    private final TerrainMap map;
    private final TerrainMap.Display display;
    private final Color background;
    private final double sourceUnit;
    private final Map<Integer, Color> colors = new ConcurrentHashMap<>();
    private final float[] speckX = new float[SPECKS];
    private final float[] speckY = new float[SPECKS];
    private final float[] speckWidth = new float[SPECKS];
    private final float[] speckHeight = new float[SPECKS];
    private final boolean[] speckLight = new boolean[SPECKS];
    /**
     * Style for the 3D view: lake and sea beds are sand (the 3D water sheet covers them), and rivers
     * are left out entirely: the 3D view shows them as real water.
     */
    private final boolean for3D;

    TerrainRenderer(TerrainMap map) { this(map, false); }

    boolean for3D() { return for3D; }

    TerrainRenderer(TerrainMap map, boolean for3D) {
        this.map = map;
        this.for3D = for3D;
        this.display = map.display();
        if (display == null) throw new IllegalArgumentException("Terrain file has no display data; re-run the extractor");
        this.background = new Color(display.backgroundColor());
        this.sourceUnit = 1.0 / display.sourceUnitsPerWorldUnit();
        // Same linear congruential generator and call order as the source texture.
        long state = display.textureSeed();
        double size = display.sourceUnitsPerWorldUnit() * districts.model.World.GRID_SIZE;
        for (int index = 0; index < SPECKS; index++) {
            state = next(state);
            speckLight[index] = state / 4294967296.0 > 0.48;
            state = next(state);
            double x = state / 4294967296.0 * size;
            state = next(state);
            double y = state / 4294967296.0 * size;
            state = next(state);
            double radius = 0.15 + state / 4294967296.0 * 1.35;
            state = next(state);
            double aspect = 0.35 + state / 4294967296.0;
            speckX[index] = (float) (x * sourceUnit);
            speckY[index] = (float) (y * sourceUnit);
            speckWidth[index] = (float) (radius * sourceUnit);
            speckHeight[index] = (float) (radius * aspect * sourceUnit);
        }
    }

    private static long next(long state) { return (1664525L * state + 1013904223L) & 0xFFFFFFFFL; }

    /** Renders the square world area (x, y, size) into a pixels × pixels image. */
    BufferedImage render(double worldX, double worldY, double worldSize, int pixels) {
        BufferedImage image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(background);
            g.fillRect(0, 0, pixels, pixels);
            double scale = pixels / worldSize;
            // Rivers and lake edges can reach a little beyond the cells that own them.
            double margin = 4 * sourceUnit + 2 / scale;
            double minX = worldX - margin;
            double minY = worldY - margin;
            double maxX = worldX + worldSize + margin;
            double maxY = worldY + worldSize + margin;

            Path2D.Double path = new Path2D.Double();
            int[] cellColors = display.cellColors();
            map.forEachCell(minX, minY, maxX, maxY, (cell, xs, ys, start, end) -> {
                path.reset();
                path.moveTo((xs[start] - worldX) * scale, (ys[start] - worldY) * scale);
                for (int i = start + 1; i < end; i++) path.lineTo((xs[i] - worldX) * scale, (ys[i] - worldY) * scale);
                path.closePath();
                g.setColor(for3D && map.isWaterCell(cell) ? SAND : color(cellColors[cell]));
                g.fill(path);
            });

            // Shorelines and rivers are line work: they scale with zoom like the source picture, but are
            // capped on screen so that, zoomed far in, they stay lines instead of spreading over the land.
            BasicStroke lakeEdge = new BasicStroke((float) Math.min(0.42 * sourceUnit * scale, MAX_SHORE_PIXELS),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            for (TerrainMap.Lake lake : display.lakes()) {
                if (lake.maxX() < minX || lake.minX() > maxX || lake.maxY() < minY || lake.minY() > maxY) continue;
                path.reset();
                path.moveTo((lake.xs()[0] - worldX) * scale, (lake.ys()[0] - worldY) * scale);
                for (int i = 1; i < lake.xs().length; i++) {
                    path.lineTo((lake.xs()[i] - worldX) * scale, (lake.ys()[i] - worldY) * scale);
                }
                path.closePath();
                g.setColor(for3D ? SAND : color(lake.color()));
                g.fill(path);
                if (for3D) continue;
                g.setColor(LAKE_EDGE);
                g.setStroke(lakeEdge);
                g.draw(path);
            }

            // In 3D, rivers are only the real water: no map line or texture is drawn for them.
            for (boolean outline : new boolean[]{true, false}) {
                if (for3D) break;
                g.setColor(outline ? RIVER_OUTLINE : RIVER);
                for (TerrainMap.River river : display.rivers()) {
                    if (river.maxX() < minX || river.minX() > maxX || river.maxY() < minY || river.minY() > maxY) continue;
                    double width = Math.min(river.width() * scale, MAX_RIVER_PIXELS)
                            + (outline ? Math.min(0.8 * sourceUnit * scale, MAX_SHORE_PIXELS) : 0);
                    g.setStroke(new BasicStroke((float) width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(riverPath(river, worldX, worldY, scale));
                }
            }

            // The texture is paper grain at map scale; once a speck would be more than a few pixels wide
            // it stops reading as grain and starts looking like a stray square, so it is left out.
            for (int index = 0; index < SPECKS; index++) {
                if (speckWidth[index] * scale > MAX_SPECK_PIXELS) continue;
                float x = speckX[index];
                float y = speckY[index];
                if (x > maxX || y > maxY || x + speckWidth[index] < minX || y + speckHeight[index] < minY) continue;
                g.setColor(speckLight[index] ? SPECK_LIGHT : SPECK_DARK);
                g.fill(new Rectangle2D.Double((x - worldX) * scale, (y - worldY) * scale,
                        speckWidth[index] * scale, speckHeight[index] * scale));
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Smooth river line through the source cell centres, built exactly like the source renderer. */
    private static Path2D.Double riverPath(TerrainMap.River river, double worldX, double worldY, double scale) {
        float[] xs = river.xs();
        float[] ys = river.ys();
        Path2D.Double path = new Path2D.Double();
        path.moveTo((xs[0] - worldX) * scale, (ys[0] - worldY) * scale);
        for (int i = 1; i < xs.length; i++) {
            double previousX = (xs[i - 1] - worldX) * scale;
            double previousY = (ys[i - 1] - worldY) * scale;
            double currentX = (xs[i] - worldX) * scale;
            double currentY = (ys[i] - worldY) * scale;
            path.quadTo(previousX, previousY, (previousX + currentX) / 2, (previousY + currentY) / 2);
        }
        path.lineTo((xs[xs.length - 1] - worldX) * scale, (ys[ys.length - 1] - worldY) * scale);
        return path;
    }

    private Color color(int rgb) { return colors.computeIfAbsent(rgb, Color::new); }
}
