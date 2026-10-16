package districts.ui;

import districts.model.World;
import districts.towns.StationTowns;
import districts.towns.TownScale;
import districts.towns.TownPlan;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Draws each station's pregenerated Medieval Fantasy City Generator layout, in the generator's own
 * style, once the map is zoomed in far enough for it to be seen.
 *
 * <p>Like the terrain tiles, a town is drawn at a resolution matched to the zoom: it is rendered in
 * the background into an image whose radius is the next power of two above its on-screen radius,
 * cached, and scaled down slightly when drawn. Closer than {@link #VECTOR_RADIUS} pixels it is
 * drawn directly as vectors. While a town is being prepared nothing is drawn for it.
 */
final class StationTownLayer {
    static final double MIN_RADIUS = 6;
    private static final int MIN_BUCKET = 8;
    private static final int VECTOR_RADIUS = 512;
    private static final long CACHE_PIXELS = 48L * 1024 * 1024;
    private static final long STALE_NANOS = 1_500_000_000L;

    private static final Color LIGHT = new Color(0x99, 0x94, 0x8a);
    private static final Color MEDIUM = new Color(0x67, 0x63, 0x5c);
    private static final Color DARK = new Color(0x1a, 0x19, 0x17);
    private static final double NORMAL_STROKE = 0.3;
    private static final double THICK_STROKE = 1.8;

    /** A station's place on the map; every layout uses the same scale, {@link TownScale#WORLD_PER_UNIT}. */
    record Entry(int district, int region, int station, double x, double y, double scale, double radius, int rank) {
        String key() { return district + "/" + region + "/" + station; }
    }

    private final StationTowns towns;
    private final Runnable repaint;
    private final Map<World.Region, List<Entry>> entries = new ConcurrentHashMap<>();
    private final Map<String, StationTowns.Town> layouts = new LinkedHashMap<>(2048, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, StationTowns.Town> eldest) {
            return size() > 6000;
        }
    };
    private final Map<String, BufferedImage> images = new LinkedHashMap<>(1024, 0.75f, true);
    private long imagePixels;
    private final BlockingDeque<Object[]> queue = new LinkedBlockingDeque<>();
    private final Map<String, Long> requested = new ConcurrentHashMap<>();

    StationTownLayer(World world, Path directory, Runnable repaint) {
        this.towns = new StationTowns(directory);
        this.repaint = repaint;
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) entries.put(region, entriesFor(district, region));
        }
        for (int index = 0; index < 3; index++) {
            Thread worker = new Thread(this::work, "station-towns-" + index);
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
    }

    boolean available() { return towns.available(); }

    private static List<Entry> entriesFor(World.District district, World.Region region) {
        List<Entry> result = new ArrayList<>(region.stations.size());
        for (World.Station station : region.stations) {
            // Footprint recorded by the spacing pass; before that, the class's typical size.
            String stored = station.metadata.get("townRadius");
            double radius = stored != null ? Double.parseDouble(stored)
                    : typicalRadius(TownPlan.classOf(station)) * TownScale.WORLD_PER_UNIT;
            result.add(new Entry(district.id, region.id, station.id, station.x, station.y, TownScale.WORLD_PER_UNIT,
                    radius, rank(station)));
        }
        // Smaller places first, so bigger ones are drawn on top if footprints ever touch.
        result.sort(java.util.Comparator.comparingInt(Entry::rank));
        return result;
    }

    private static int rank(World.Station station) {
        if (station.capital) return 3;
        TownPlan.TownClass townClass = TownPlan.classOf(station);
        if (townClass == null) return 0;
        return switch (townClass) {
            case LARGE_TOWN -> 0;
            case SMALL_CITY -> 1;
            case LARGE_CITY -> 2;
        };
    }

    /** Typical generator radius per class, used before the layout itself is loaded. */
    private static double typicalRadius(TownPlan.TownClass townClass) {
        if (townClass == null) return 64;
        return switch (townClass) {
            case LARGE_TOWN -> 64;
            case SMALL_CITY -> 88;
            case LARGE_CITY -> 135;
        };
    }

    List<Entry> entries(World.Region region) { return entries.getOrDefault(region, List.of()); }

    /** Draws one station's town at screen position (sx, sy) with pxPerWorld pixels per world unit. */
    void draw(Graphics2D g, Entry entry, double sx, double sy, double pxPerWorld, float alpha) {
        double pxPerUnit = entry.scale * pxPerWorld;
        double radius = entry.radius * pxPerWorld;
        if (radius < MIN_RADIUS) return;
        String key = entry.key();
        if (radius > VECTOR_RADIUS) {
            StationTowns.Town town;
            synchronized (layouts) { town = layouts.get(key); }
            if (town == null) { request(entry, 0); return; }
            Graphics2D v = (Graphics2D) g.create();
            v.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            v.translate(sx, sy);
            paint(v, town, pxPerUnit);
            v.dispose();
            return;
        }
        int bucket = MIN_BUCKET;
        while (bucket < radius) bucket <<= 1;
        BufferedImage image = cached(key + "@" + bucket);
        if (image == null) {
            request(entry, bucket);
            // Show a lower- or higher-resolution version already in memory while this one is made.
            for (int other = bucket >> 1; image == null && other >= MIN_BUCKET; other >>= 1) image = cached(key + "@" + other);
            for (int other = bucket << 1; image == null && other <= VECTOR_RADIUS; other <<= 1) image = cached(key + "@" + other);
            if (image == null) return;
        }
        // An image of bucket b covers ±(b + margin) world-scaled pixels around the station.
        double imageRadius = image.getWidth() / 2.0;
        double imageBucket = (imageRadius - 2) / IMAGE_MARGIN;
        double factor = radius / imageBucket;
        int size = (int) Math.round(image.getWidth() * factor);
        Graphics2D d = (Graphics2D) g.create();
        d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        d.drawImage(image, (int) Math.round(sx - size / 2.0), (int) Math.round(sy - size / 2.0), size, size, null);
        d.dispose();
    }

    /** Images leave room for roads and walls reaching a little past the nominal radius. */
    private static final double IMAGE_MARGIN = 1.7;

    private BufferedImage cached(String key) {
        synchronized (images) { return images.get(key); }
    }

    private void request(Entry entry, int bucket) {
        String key = entry.key() + "@" + bucket;
        if (requested.put(key, System.nanoTime()) == null) queue.addLast(new Object[]{entry, bucket, key});
    }

    private void work() {
        while (true) {
            Object[] job;
            try {
                job = queue.takeLast();
            } catch (InterruptedException interrupted) {
                return;
            }
            Entry entry = (Entry) job[0];
            int bucket = (int) job[1];
            String key = (String) job[2];
            Long at = requested.get(key);
            if (at == null || System.nanoTime() - at > STALE_NANOS) {
                requested.remove(key);
                continue;
            }
            try {
                StationTowns.Town town = layout(entry);
                if (town != null && bucket > 0) store(key, render(town, entry, bucket));
            } catch (Exception exception) {
                System.err.println("Could not draw town " + entry.key() + ": " + exception);
            } finally {
                requested.remove(key);
                repaint.run();
            }
        }
    }

    private StationTowns.Town layout(Entry entry) throws java.io.IOException {
        String key = entry.key();
        synchronized (layouts) {
            StationTowns.Town town = layouts.get(key);
            if (town != null) return town;
        }
        StationTowns.Town town = towns.load(entry.district, entry.region, entry.station);
        if (town != null) synchronized (layouts) { layouts.put(key, town); }
        return town;
    }

    private void store(String key, BufferedImage image) {
        synchronized (images) {
            BufferedImage previous = images.put(key, image);
            if (previous != null) imagePixels -= (long) previous.getWidth() * previous.getHeight();
            imagePixels += (long) image.getWidth() * image.getHeight();
            var iterator = images.entrySet().iterator();
            while (imagePixels > CACHE_PIXELS && iterator.hasNext()) {
                var eldest = iterator.next();
                if (eldest.getKey().equals(key)) continue;
                imagePixels -= (long) eldest.getValue().getWidth() * eldest.getValue().getHeight();
                iterator.remove();
            }
        }
    }

    /** Renders a town into a transparent image sized for an on-screen radius of {@code bucket} pixels. */
    private static BufferedImage render(StationTowns.Town town, Entry entry, int bucket) {
        int half = (int) Math.ceil(bucket * IMAGE_MARGIN) + 2;
        BufferedImage image = new BufferedImage(half * 2, half * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.translate(half, half);
        // bucket pixels correspond to the layout's map radius, entry.radius world units.
        paint(g, town, bucket / entry.radius * entry.scale);
        g.dispose();
        return image;
    }

    /** Paints a layout's buildings, parks and walls centred on (0, 0), in the generator's colours. */
    static void paint(Graphics2D g, StationTowns.Town town, double pxPerUnit) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        // Outlines, towers and gates only once buildings are a few pixels wide; smaller than that
        // they would turn a distant town into a dark smudge.
        boolean detailed = pxPerUnit >= 0.6;

        // Only the settlement itself is drawn: no paper ground and none of the generator's roads,
        // which run off to nowhere outside the town. The terrain shows between the buildings.
        Path2D.Double buildings = new Path2D.Double(Path2D.WIND_NON_ZERO);
        Path2D.Double parks = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (StationTowns.Ward ward : town.wards()) {
            Path2D.Double target = ward.type() == StationTowns.PARK ? parks : buildings;
            for (float[] building : ward.buildings()) append(target, building, pxPerUnit, true);
        }
        g.setColor(MEDIUM);
        g.fill(parks);
        g.setColor(LIGHT);
        g.fill(buildings);
        if (detailed) {
            g.setColor(DARK);
            // Fine outlines like the generator's own maps, even when zoomed right in.
            g.setStroke(new BasicStroke((float) Math.min(1.6, NORMAL_STROKE * pxPerUnit), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g.draw(buildings);
        }

        double wallWidth = THICK_STROKE * pxPerUnit;
        if (town.wall().length >= 4 && wallWidth >= 0.35) {
            Path2D.Double wall = new Path2D.Double();
            append(wall, town.wall(), pxPerUnit, true);
            float thick = (float) wallWidth;
            g.setColor(DARK);
            g.setStroke(new BasicStroke(thick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(wall);
            if (detailed) {
                float[] towers = town.towers();
                double r = THICK_STROKE * pxPerUnit;
                for (int i = 0; i + 1 < towers.length; i += 2) {
                    g.fill(new Ellipse2D.Double(towers[i] * pxPerUnit - r, towers[i + 1] * pxPerUnit - r, 2 * r, 2 * r));
                }
                drawGates(g, town, pxPerUnit);
            }
        }
    }

    /** Short thick bars across the wall at each gate, as in the generator. */
    private static void drawGates(Graphics2D g, StationTowns.Town town, double pxPerUnit) {
        float[] wall = town.wall();
        float[] gates = town.gates();
        int n = wall.length / 2;
        g.setStroke(new BasicStroke((float) (THICK_STROKE * 2 * pxPerUnit), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        for (int gi = 0; gi + 1 < gates.length; gi += 2) {
            for (int i = 0; i < n; i++) {
                if (Math.abs(wall[2 * i] - gates[gi]) > 0.1f || Math.abs(wall[2 * i + 1] - gates[gi + 1]) > 0.1f) continue;
                int prev = (i + n - 1) % n;
                int next = (i + 1) % n;
                double dx = wall[2 * next] - wall[2 * prev];
                double dy = wall[2 * next + 1] - wall[2 * prev + 1];
                double length = Math.hypot(dx, dy);
                if (length == 0) break;
                double ux = dx / length * THICK_STROKE * 1.5;
                double uy = dy / length * THICK_STROKE * 1.5;
                g.draw(new Line2D.Double((gates[gi] - ux) * pxPerUnit, (gates[gi + 1] - uy) * pxPerUnit,
                        (gates[gi] + ux) * pxPerUnit, (gates[gi + 1] + uy) * pxPerUnit));
                break;
            }
        }
    }

    private static void append(Path2D.Double path, float[] flat, double pxPerUnit, boolean close) {
        if (flat.length < 4) return;
        path.moveTo(flat[0] * pxPerUnit, flat[1] * pxPerUnit);
        for (int i = 2; i + 1 < flat.length; i += 2) path.lineTo(flat[i] * pxPerUnit, flat[i + 1] * pxPerUnit);
        if (close) path.closePath();
    }
}
