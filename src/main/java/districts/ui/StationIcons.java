package districts.ui;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Transport icons for map points: bus stops (stations), subway stops (region hubs), rail
 * stations (capitals) and ferry landings. The source pictures (src/main/resources/icons) are
 * black glyphs; they are turned into masks, tinted, put on a small dark backing for contrast,
 * and cached per size and colour so drawing thousands of them stays cheap.
 */
final class StationIcons {
    enum Kind {
        BUS("bus.png"), SUBWAY("subway.png"), TRAIN("train.png"), FERRY("ferry.png");

        final String file;

        Kind(String file) { this.file = file; }
    }

    private static final Map<Kind, BufferedImage> MASKS = new EnumMap<>(Kind.class);
    private static final Map<String, BufferedImage> CACHE = new HashMap<>();
    private static final Color BACKING = new Color(14, 19, 26, 225);

    private StationIcons() {}

    /** Draws an icon of the given pixel size centred on (x, y). */
    static void draw(Graphics2D g, Kind kind, Color tint, double x, double y, int size) {
        BufferedImage icon = icon(kind, tint, size);
        if (icon != null) g.drawImage(icon, (int) Math.round(x - size / 2.0), (int) Math.round(y - size / 2.0), null);
    }

    private static synchronized BufferedImage icon(Kind kind, Color tint, int size) {
        String key = kind + "/" + tint.getRGB() + "/" + size;
        BufferedImage cached = CACHE.get(key);
        if (cached != null || CACHE.containsKey(key)) return cached;
        BufferedImage mask = mask(kind);
        BufferedImage icon = null;
        if (mask != null) {
            icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = icon.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BACKING);
            g.fill(new RoundRectangle2D.Double(0, 0, size, size, size * 0.4, size * 0.4));
            g.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 120));
            g.draw(new RoundRectangle2D.Double(0.5, 0.5, size - 1, size - 1, size * 0.4, size * 0.4));
            int inner = Math.max(4, Math.round(size * 0.76f));
            int offset = (size - inner) / 2;
            g.drawImage(tinted(downscale(mask, inner), tint), offset, offset, null);
            g.dispose();
        }
        CACHE.put(key, icon);
        return icon;
    }

    /** Alpha mask from the picture: dark, opaque pixels become solid; light or transparent ones clear. */
    private static BufferedImage mask(Kind kind) {
        if (MASKS.containsKey(kind)) return MASKS.get(kind);
        BufferedImage result = null;
        try (InputStream in = StationIcons.class.getResourceAsStream("/icons/" + kind.file)) {
            BufferedImage source = in == null ? null : ImageIO.read(in);
            if (source != null) {
                result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < source.getHeight(); y++) {
                    for (int x = 0; x < source.getWidth(); x++) {
                        int argb = source.getRGB(x, y);
                        int alpha = argb >>> 24;
                        int luminance = (((argb >> 16) & 255) * 299 + ((argb >> 8) & 255) * 587 + (argb & 255) * 114) / 1000;
                        int coverage = alpha * (255 - luminance) / 255;
                        result.setRGB(x, y, (coverage << 24) | 0xffffff);
                    }
                }
            }
        } catch (IOException exception) {
            System.err.println("Could not load icon " + kind.file + ": " + exception.getMessage());
        }
        MASKS.put(kind, result);
        return result;
    }

    /** Halves repeatedly, then scales to the final size, so small icons stay crisp and smooth. */
    private static BufferedImage downscale(BufferedImage image, int size) {
        BufferedImage current = image;
        while (current.getWidth() / 2 >= size) current = resize(current, current.getWidth() / 2);
        return resize(current, size);
    }

    private static BufferedImage resize(BufferedImage image, int size) {
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, size, size, null);
        g.dispose();
        return result;
    }

    private static BufferedImage tinted(BufferedImage mask, Color tint) {
        BufferedImage result = new BufferedImage(mask.getWidth(), mask.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int rgb = tint.getRGB() & 0xffffff;
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) result.setRGB(x, y, (mask.getRGB(x, y) & 0xff000000) | rgb);
        }
        return result;
    }
}
