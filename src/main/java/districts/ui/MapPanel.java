package districts.ui;

import districts.model.World;
import districts.terrain.TerrainMap;
import districts.travel.TicketPlanner;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

@SuppressWarnings("serial")
public final class MapPanel extends JPanel {
    private static final Color BACKGROUND_TOP = new Color(17, 23, 31);
    private static final Color BACKGROUND_BOTTOM = new Color(8, 12, 18);
    private static final Color HIGHWAY = new Color(216, 187, 129);
    private static final Color ROAD = new Color(65, 196, 111);
    private static final Color RAIL = new Color(93, 205, 235);
    private static final Color REGION = new Color(235, 225, 191);
    private static final Color CAPITAL = new Color(255, 197, 77);

    private final World world;
    private final Consumer<String> status;
    private final Consumer<StationSelection> stationSelection;
    private final TerrainTiles terrainTiles;
    /** The same terrain styled for the 3D view's texture (sand beds under the water sheet); made on first use. */
    private TerrainTiles terrainTiles3D;
    private final java.nio.file.Path tileDirectory;
    private final TerrainMap terrainMap;
    private StationSelection selectedStation;
    private double zoom = 1.0;
    private double panX;
    private double panY;
    private Point dragStart;
    private double dragPanX;
    private double dragPanY;
    private boolean showDistrictLabels = true;
    private boolean showRegions = true;
    private boolean showHighways = true;
    private boolean showRails = true;
    private boolean showCityRoads = true;
    private boolean showTownRoads = true;
    private boolean showCities = true;
    private boolean showTowns = true;
    private boolean showPlaceNames = true;
    private boolean showTerrain = true;
    private boolean showFerries = true;
    /**
     * What a paint pass draws. NORMAL is the 2D map. In 3D the map is drawn twice: TEXTURE draws the
     * ground layers (terrain, borders, rails, roads, ferries, towns, ticket route) top-down into the
     * texture laid over the terrain; BILLBOARD draws icons and names upright on top, at projected points.
     */
    private enum Pass { NORMAL, TEXTURE, BILLBOARD }
    private Pass pass = Pass.NORMAL;
    private int overrideWidth = -1;
    private int overrideHeight = -1;
    private final View3D view3d = new View3D();
    private Point2D.Double dragTarget;
    private Point rotateStart;
    private double rotateHeading;
    private double rotateTilt;
    private javax.swing.Timer textureTimer;
    /** Each region's centre station, where its subway (highway) line stops. */
    private final java.util.Set<World.Station> hubStations =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private boolean showStationTowns = true;
    private StationTownLayer townLayer;
    /** 3D houses and walls of the station towns near the camera, built on demand. */
    private TownMeshes townMeshes;
    /** The 3D towns drawn in the last frame, which the first-person walker collides with. */
    private List<TownMeshes.Mesh> lastTownMeshes = List.of();
    private FirstPerson firstPerson;
    /** North-up 2D map cached under the rotating first-person minimap frame. */
    private BufferedImage firstPersonMinimap;
    private int firstPersonMinimapSize;
    private double firstPersonMinimapX = Double.NaN;
    private double firstPersonMinimapY = Double.NaN;
    private double firstPersonMinimapZoom = Double.NaN;
    private long firstPersonMinimapMillis;
    private boolean firstPersonMinimapDirty = true;
    private static final double MINIMAP_PIXELS_PER_WORLD = 2_000.0;
    private static final long MINIMAP_REDRAW_MILLIS = 250L;
    /** Most towns given 3D houses at once (the nearest ones). */
    private static final int MAX_3D_TOWNS = 24;
    /** First-person marker ranges: small towns declutter first, while cities and capitals remain landmarks. */
    private static final double FIRST_PERSON_TOWN_MARKER_RANGE = 0.18;
    private static final double FIRST_PERSON_CITY_MARKER_RANGE = 0.38;
    private static final double FIRST_PERSON_CAPITAL_MARKER_RANGE = 1.6;
    private static final double FIRST_PERSON_REGION_MARKER_RANGE = 0.45;
    /** Beyond this (about 46 km), individual houses are far below the mesh visibility threshold. */
    private static final double FIRST_PERSON_TOWN_MESH_RANGE = 0.03;
    /** Deep enough to see individual buildings in the station town layouts. */
    private static final double MAX_ZOOM = 480000.0;
    private boolean showTicketRoute = true;
    private boolean focusTicketRoute;
    private TicketPlanner.Ticket ticket;

    public enum Layer {
        TERRAIN, DISTRICT_LABELS, RAILS, REGIONS, HIGHWAYS, CITY_ROADS, TOWN_ROADS, FERRIES, CITIES, TOWNS,
        PLACE_NAMES, STATION_TOWNS, TICKET_ROUTE, ROUTE_FOCUS
    }

    public record StationSelection(World.District district, World.Region region, World.Station station) {}

    public MapPanel(World world, Consumer<String> status) {
        this(world, status, ignored -> {}, null, null);
    }

    public MapPanel(World world, Consumer<String> status, Consumer<StationSelection> stationSelection) {
        this(world, status, stationSelection, null, null);
    }

    /**
     * @param tileDirectory where zoomed-out terrain tiles are cached between launches
     * @param terrainMap    the source map's terrain, drawn as vector tiles at every zoom level
     */
    public MapPanel(World world, Consumer<String> status, Consumer<StationSelection> stationSelection,
                    java.nio.file.Path tileDirectory, TerrainMap terrainMap) {
        this.world = world;
        this.status = status;
        this.stationSelection = stationSelection;
        this.terrainMap = terrainMap;
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                World.Station hub = null;
                for (World.Station station : region.stations) {
                    if (hub == null || Math.hypot(station.x - region.x, station.y - region.y)
                            < Math.hypot(hub.x - region.x, hub.y - region.y)) hub = station;
                }
                if (hub != null) hubStations.add(hub);
            }
        }
        this.tileDirectory = tileDirectory;
        this.terrainTiles = terrainMap != null && terrainMap.display() != null && tileDirectory != null
                ? new TerrainTiles(new TerrainRenderer(terrainMap), tileDirectory, terrainStamp(terrainMap), this::layerChanged)
                : null;
        setOpaque(true);
        setBackground(BACKGROUND_BOTTOM);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        setFocusable(true);
        firstPerson = new FirstPerson(this, view3d, null);

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (firstPerson.active()) return;
                if (SwingUtilities.isLeftMouseButton(event) || SwingUtilities.isMiddleMouseButton(event)) {
                    dragStart = event.getPoint();
                    dragPanX = panX;
                    dragPanY = panY;
                    dragTarget = target();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else if (SwingUtilities.isRightMouseButton(event) && view3d.enabled) {
                    rotateStart = event.getPoint();
                    rotateHeading = view3d.heading;
                    rotateTilt = view3d.tilt;
                }
            }

            @Override public void mouseReleased(MouseEvent event) {
                dragStart = null;
                rotateStart = null;
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }

            @Override public void mouseClicked(MouseEvent event) {
                if (firstPerson.active()) return;
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    Point2D.Double worldPoint = screenToWorld(event.getX(), event.getY());
                    if (zoom >= 6.0) {
                        StationSelection selection = nearestStationSelection(worldPoint.x, worldPoint.y, 16.0);
                        if (selection != null) {
                            selectedStation = selection;
                            stationSelection.accept(selection);
                            repaint();
                            return;
                        }
                    }
                    World.Region region = nearestRegion(worldPoint.x, worldPoint.y, 30.0);
                    if (region != null) focusOn(region.x, region.y, Math.max(zoom, 30.0));
                }
            }

            @Override public void mouseWheelMoved(MouseWheelEvent event) {
                if (firstPerson.active()) return;
                if (event.isControlDown() || event.isMetaDown()) {
                    double factor = Math.pow(1.23, -event.getPreciseWheelRotation());
                    zoomAt(event.getX(), event.getY(), factor);
                    event.consume();
                } else {
                    double delta = -event.getPreciseWheelRotation() * 38.0;
                    if (view3d.enabled) {
                        Point2D.Double target = target();
                        double s = scale();
                        if (event.isShiftDown()) setTarget(target.x - view3d.rightX() * delta / s, target.y - view3d.rightY() * delta / s);
                        else setTarget(target.x + view3d.forwardX() * delta / (s * view3d.cosTilt()),
                                target.y + view3d.forwardY() * delta / (s * view3d.cosTilt()));
                    } else if (event.isShiftDown()) panX += delta;
                    else panY += delta;
                    repaint();
                }
            }
        };
        addMouseListener(mouse);
        addMouseWheelListener(mouse);
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent event) {
                if (firstPerson.active()) {
                    firstPerson.mouseMoved(event);
                    return;
                }
                if (rotateStart != null && view3d.enabled) {
                    // Right-drag: sideways turns the view, up/down tilts it.
                    view3d.heading = rotateHeading + (event.getX() - rotateStart.x) * 0.006;
                    view3d.tilt = clamp(rotateTilt + (event.getY() - rotateStart.y) * 0.005, 0, View3D.MAX_TILT);
                    repaint();
                } else if (dragStart != null && view3d.enabled) {
                    // Move the ground with the mouse: sideways along the screen's right, up/down along its forward.
                    double s = scale();
                    double dx = event.getX() - dragStart.x;
                    double dy = event.getY() - dragStart.y;
                    double forward = dy / (s * Math.max(0.2, view3d.cosTilt()));
                    setTarget(dragTarget.x - view3d.rightX() * dx / s + view3d.forwardX() * forward,
                            dragTarget.y - view3d.rightY() * dx / s + view3d.forwardY() * forward);
                    repaint();
                } else if (dragStart != null) {
                    panX = dragPanX + event.getX() - dragStart.x;
                    panY = dragPanY + event.getY() - dragStart.y;
                    repaint();
                }
            }

            @Override public void mouseMoved(MouseEvent event) {
                if (firstPerson.active()) firstPerson.mouseMoved(event);
                else updateStatus(event.getX(), event.getY());
            }
        });
    }

    public void resetView() {
        zoom = 1.0;
        panX = 0.0;
        panY = 0.0;
        view3d.heading = 0;
        view3d.tilt = View3D.DEFAULT_TILT;
        repaint();
    }

    public boolean is3D() { return view3d.enabled; }

    /** Turns the 3D terrain view on or off; the heightmap is built the first time. */
    public void set3D(boolean enabled) {
        if (!enabled) firstPerson.exit();
        if (enabled && !view3d.hasHeights() && terrainMap != null) {
            view3d.setHeights(districts.terrain.HeightField.build(terrainMap));
            if (townMeshes != null) townMeshes.setHeights(view3d.heights());
        }
        view3d.enabled = enabled;
        view3d.textureDirty = true;
        repaint();
    }

    /** Where first-person settings are saved (created with these settings). */
    public void setFirstPersonSettingsFile(java.nio.file.Path file) {
        firstPerson.exit();
        firstPerson = new FirstPerson(this, view3d, file);
    }

    /** Walks the 3D map in first person from the middle of the view (turning 3D on if needed). */
    public void enterFirstPerson() {
        if (!view3d.enabled) set3D(true);
        Point2D.Double target = target();
        firstPerson.enter(target.x, target.y, view3d.heading);
    }

    public void exitFirstPerson() { firstPerson.exit(); }

    public boolean isFirstPerson() { return firstPerson.active(); }

    /** Called whenever first person starts or stops. */
    public void onFirstPersonChange(Runnable listener) { firstPerson.onChange(listener); }

    public void showFirstPersonSettings() { firstPerson.showSettings(this); }

    /** Rebuilds the cached 2D map on the next first-person frame. */
    void invalidateFirstPersonMinimap() { firstPersonMinimapDirty = true; }

    List<TownMeshes.Mesh> townMeshesInView() { return lastTownMeshes; }

    /** Moves the view's target (in first person, the walker's position) without changing the zoom. */
    void moveTarget(double x, double y) { setTarget(x, y); }

    /** Vertical exaggeration of the terrain in 3D (1 = true scale). */
    public void set3DExaggeration(double exaggeration) {
        view3d.exaggeration = exaggeration;
        repaint();
    }

    /** Called when terrain tiles or town images arrive: the 3D view's map texture must be redrawn. */
    private void layerChanged() {
        view3d.textureDirty = true;
        firstPersonMinimapDirty = true;
        repaint();
    }

    /** The ground point at the centre of the view. */
    private Point2D.Double target() {
        double scale = scale();
        return new Point2D.Double(4.0 - panX / scale, 4.0 - panY / scale);
    }

    private void setTarget(double x, double y) {
        double scale = scale();
        panX = -(x - 4.0) * scale;
        panY = -(y - 4.0) * scale;
    }

    public void zoomAtCenter(double factor) { zoomAt(viewWidth() / 2.0, viewHeight() / 2.0, factor); }

    public void setLayerVisible(Layer layer, boolean visible) {
        switch (layer) {
            case TERRAIN -> showTerrain = visible;
            case DISTRICT_LABELS -> showDistrictLabels = visible;
            case RAILS -> showRails = visible;
            case REGIONS -> showRegions = visible;
            case HIGHWAYS -> showHighways = visible;
            case CITY_ROADS -> showCityRoads = visible;
            case TOWN_ROADS -> showTownRoads = visible;
            case CITIES -> showCities = visible;
            case TOWNS -> showTowns = visible;
            case PLACE_NAMES -> showPlaceNames = visible;
            case FERRIES -> showFerries = visible;
            case STATION_TOWNS -> showStationTowns = visible;
            case TICKET_ROUTE -> showTicketRoute = visible;
            case ROUTE_FOCUS -> focusTicketRoute = visible;
        }
        firstPersonMinimapDirty = true;
        repaint();
    }

    public void showAllLayers() {
        for (Layer layer : Layer.values()) setLayerVisible(layer, layer != Layer.ROUTE_FOCUS);
    }

    public void setTicket(TicketPlanner.Ticket ticket) {
        this.ticket = ticket;
        firstPersonMinimapDirty = true;
        repaint();
    }

    /** Zooms and pans so the whole ticket route fills the view. */
    public void fitTicketRoute() {
        if (ticket == null || ticket.legs().isEmpty()) return;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (TicketPlanner.Leg leg : ticket.legs()) {
            for (Point2D.Double point : leg.path()) {
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
            }
        }
        double spanX = Math.max(maxX - minX, 0.004);
        double spanY = Math.max(maxY - minY, 0.004);
        double fitScale = Math.min((viewWidth() - 180.0) / spanX, (viewHeight() - 180.0) / spanY);
        focusOn((minX + maxX) / 2.0, (minY + maxY) / 2.0, Math.min(fitScale / baseScale(), 400.0));
    }

    private void zoomAt(double screenX, double screenY, double factor) {
        Point2D.Double anchor = screenToWorld(screenX, screenY);
        double newZoom = clamp(zoom * factor, 0.75, MAX_ZOOM);
        if (view3d.enabled) {
            Point2D.Double target = target();
            double applied = newZoom / zoom;
            zoom = newZoom;
            setTarget(anchor.x + (target.x - anchor.x) / applied, anchor.y + (target.y - anchor.y) / applied);
            repaint();
            return;
        }
        double newScale = baseScale() * newZoom;
        panX = screenX - viewWidth() / 2.0 - (anchor.x - 4.0) * newScale;
        panY = screenY - viewHeight() / 2.0 - (anchor.y - 4.0) * newScale;
        zoom = newZoom;
        repaint();
        updateStatus((int) screenX, (int) screenY);
    }

    private void focusOn(double worldX, double worldY, double targetZoom) {
        zoom = clamp(targetZoom, 0.75, MAX_ZOOM);
        double scale = scale();
        panX = -(worldX - 4.0) * scale;
        panY = -(worldY - 4.0) * scale;
        repaint();
    }

    /** Paint start times (ns) of recent frames, for the FPS counter. */
    private final java.util.ArrayDeque<Long> frameTimes = new java.util.ArrayDeque<>();
    private double lastFrameMillis;

    @Override protected void paintComponent(Graphics graphics) {
        // A 3D frame covers every pixel, so clearing the entire panel first only doubles the
        // full-screen memory traffic (particularly costly at 1440p and 4K).
        if (!view3d.enabled) super.paintComponent(graphics);
        long frameStart = System.nanoTime();
        frameTimes.addLast(frameStart);
        while (!frameTimes.isEmpty() && frameStart - frameTimes.peekFirst() > 1_000_000_000L) frameTimes.removeFirst();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (view3d.enabled) {
                paint3D(g);
                return;
            }
            g.setPaint(new GradientPaint(0, 0, BACKGROUND_TOP, 0, viewHeight(), BACKGROUND_BOTTOM));
            g.fillRect(0, 0, viewWidth(), viewHeight());

            double regionAlpha = smoothstep(1.25, 3.1, zoom);
            double stationAlpha = smoothstep(6.0, 10.5, zoom);
            drawLayers(g, true);
            drawOverlay(g, regionAlpha, stationAlpha);
            drawFps(g);
        } finally {
            g.dispose();
            lastFrameMillis = (System.nanoTime() - frameStart) / 1e6;
            view3d.recordFrameTime(lastFrameMillis);
        }
    }

    /** Every map layer in order; the current pass decides which parts of each are drawn. */
    private void drawLayers(Graphics2D g, boolean withTerrain) {
        if (withTerrain && showTerrain && terrainTiles != null) drawTerrain(g);
        drawDistricts(g);
        if (showRails) drawRails(g);
        double regionAlpha = smoothstep(1.25, 3.1, zoom);
        if (regionAlpha > 0.001 && (showRegions || showHighways)) drawRegionNetwork(g, regionAlpha);
        double stationAlpha = smoothstep(6.0, 10.5, zoom);
        if (stationAlpha > 0.001 && (showCityRoads || showTownRoads || showCities || showTowns || showFerries)) {
            drawStations(g, stationAlpha);
        }
        if (ticket != null && showTicketRoute && !ticket.legs().isEmpty()) drawTicketRoute(g);
    }

    /**
     * 3D frame: (1) when needed, draw the ground layers top-down into a texture covering the visible
     * ground; (2) render the heightmap surface with that texture; (3) draw icons and names upright on
     * top, hiding any that a nearer hill covers; (4) the usual overlay.
     */
    private void paint3D(Graphics2D g) {
        int width = getWidth();
        int height = getHeight();
        Point2D.Double target = target();
        view3d.setCamera(width, height, target.x, target.y, scale());
        double radius = view3d.groundRadius();
        long now = System.currentTimeMillis();
        boolean stale = view3d.textureDirty && !view3d.textureRecentlyMade(now);
        if (view3d.needsTexture(radius) || stale) renderTexture(view3d.textureFrame(radius), false);
        else if (view3d.textureDirty) scheduleTextureRefresh();
        double nearRadius = view3d.nearRadius(radius);
        if (nearRadius <= 0) view3d.dropNearTexture();
        else if (view3d.needsNearTexture(nearRadius) || stale) renderTexture(view3d.textureFrame(nearRadius), true);
        lastTownMeshes = townMeshes3D();
        BufferedImage rendered = view3d.render(lastTownMeshes);
        if (rendered.getWidth() == width && rendered.getHeight() == height) {
            g.drawImage(rendered, 0, 0, null);
        } else {
            // Bilinear enlargement is surprisingly expensive in Java2D's software path. At the
            // adaptive first-person resolutions a nearest-neighbour copy is both much faster and
            // sharper; use bilinear only when the source is already close to display size.
            Object interpolation = view3d.resolutionPercent() >= 90
                    ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.drawImage(rendered, 0, 0, width, height, null);
        }

        pass = Pass.BILLBOARD;
        try {
            drawLayers(g, false);
        } finally {
            pass = Pass.NORMAL;
        }
        double regionAlpha = smoothstep(1.25, 3.1, zoom);
        double stationAlpha = smoothstep(6.0, 10.5, zoom);
        drawOverlay(g, regionAlpha, stationAlpha);
        drawFirstPersonMinimap(g, width, height);
        firstPerson.drawHud(g, width, height);
        drawFps(g);
    }

    /**
     * Rotating first-person minimap. The cached image is drawn by the normal 2D layer pipeline, so
     * terrain, districts, transport, regions and stations match the main map and its filters. Only
     * the cheap image rotation is done every frame; the map itself is refreshed at most four times a
     * second while moving, preserving the first-person frame-rate target.
     */
    private void drawFirstPersonMinimap(Graphics2D g, int width, int height) {
        if (!firstPerson.active()) return;
        int size = Math.min(276, Math.max(160, Math.min(width, height) / 3));
        double mapZoom = firstPerson.minimapZoom();
        double pixelsPerWorld = MINIMAP_PIXELS_PER_WORLD * mapZoom;
        double playerX = firstPerson.minimapX();
        double playerY = firstPerson.minimapY();
        long now = System.currentTimeMillis();
        boolean moved = Double.isNaN(firstPersonMinimapX)
                || Math.hypot(playerX - firstPersonMinimapX, playerY - firstPersonMinimapY) * pixelsPerWorld >= 1.0;
        boolean scaleChanged = firstPersonMinimapSize != size
                || Double.compare(firstPersonMinimapZoom, mapZoom) != 0;
        if (firstPersonMinimap == null || firstPersonMinimapDirty || scaleChanged
                || moved && now - firstPersonMinimapMillis >= MINIMAP_REDRAW_MILLIS) {
            // Clear before rendering so an asynchronous terrain/town tile arriving during the draw
            // can set it back to true instead of having its refresh accidentally erased afterward.
            firstPersonMinimapDirty = false;
            renderFirstPersonMinimap(size, pixelsPerWorld, playerX, playerY);
            firstPersonMinimapSize = size;
            firstPersonMinimapX = playerX;
            firstPersonMinimapY = playerY;
            firstPersonMinimapZoom = mapZoom;
            firstPersonMinimapMillis = now;
        }

        int left = width - size - 18;
        int top = height - size - 18;
        double centerX = left + size / 2.0;
        double centerY = top + size / 2.0;
        Ellipse2D.Double circle = new Ellipse2D.Double(left, top, size, size);

        g.setColor(new Color(0, 0, 0, 115));
        g.fill(new Ellipse2D.Double(left - 5, top + 4, size + 10, size + 10));
        Graphics2D mapGraphics = (Graphics2D) g.create();
        try {
            mapGraphics.clip(circle);
            mapGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            AffineTransform transform = new AffineTransform();
            transform.translate(centerX, centerY);
            transform.rotate(-firstPerson.minimapHeading());
            transform.translate(-size / 2.0, -size / 2.0);
            mapGraphics.drawImage(firstPersonMinimap, transform, null);
            mapGraphics.setColor(new Color(255, 255, 255, 34));
            mapGraphics.drawLine(left, (int) centerY, left + size, (int) centerY);
            mapGraphics.drawLine((int) centerX, top, (int) centerX, top + size);
        } finally {
            mapGraphics.dispose();
        }

        g.setStroke(new BasicStroke(4f));
        g.setColor(new Color(9, 14, 20, 230));
        g.draw(circle);
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(new Color(203, 218, 226, 210));
        g.draw(new Ellipse2D.Double(left + 3, top + 3, size - 6, size - 6));

        // The player always points toward the top; the 2D map turns underneath them.
        Path2D.Double arrow = new Path2D.Double();
        arrow.moveTo(centerX, centerY - 13);
        arrow.lineTo(centerX - 8, centerY + 10);
        arrow.lineTo(centerX, centerY + 5);
        arrow.lineTo(centerX + 8, centerY + 10);
        arrow.closePath();
        g.setColor(new Color(0, 0, 0, 180));
        AffineTransform shadow = AffineTransform.getTranslateInstance(1.5, 2.0);
        g.fill(shadow.createTransformedShape(arrow));
        g.setColor(new Color(245, 250, 252));
        g.fill(arrow);
        g.setColor(new Color(35, 190, 235));
        g.draw(arrow);

        double heading = firstPerson.minimapHeading();
        double northRadius = size / 2.0 - 17.0;
        int northX = (int) Math.round(centerX - Math.sin(heading) * northRadius);
        int northY = (int) Math.round(centerY - Math.cos(heading) * northRadius);
        g.setFont(getFont().deriveFont(Font.BOLD, 11f));
        g.setColor(new Color(7, 12, 17, 210));
        g.fillOval(northX - 9, northY - 9, 18, 18);
        g.setColor(new Color(255, 116, 106));
        g.drawString("N", northX - 4, northY + 4);

        String hint = String.format(Locale.ROOT, "Map ×%.2f   = in   - out", mapZoom);
        g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        FontMetrics metrics = g.getFontMetrics();
        int hintWidth = metrics.stringWidth(hint) + 14;
        int hintX = (int) Math.round(centerX - hintWidth / 2.0);
        int hintY = top + size - 28;
        g.setColor(new Color(7, 12, 17, 205));
        g.fillRoundRect(hintX, hintY, hintWidth, 20, 8, 8);
        g.setColor(new Color(230, 238, 242));
        g.drawString(hint, hintX + 7, hintY + 14);
    }

    /** Draws a north-up snapshot with the regular 2D renderer, centred on the player. */
    private void renderFirstPersonMinimap(int size, double pixelsPerWorld, double playerX, double playerY) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D mini = image.createGraphics();
        double savedZoom = zoom;
        double savedPanX = panX;
        double savedPanY = panY;
        int savedWidth = overrideWidth;
        int savedHeight = overrideHeight;
        Pass savedPass = pass;
        try {
            overrideWidth = size;
            overrideHeight = size;
            pass = Pass.NORMAL;
            zoom = pixelsPerWorld / baseScale();
            panX = -(playerX - 4.0) * pixelsPerWorld;
            panY = -(playerY - 4.0) * pixelsPerWorld;
            mini.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            mini.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            mini.setPaint(new GradientPaint(0, 0, BACKGROUND_TOP, 0, size, BACKGROUND_BOTTOM));
            mini.fillRect(0, 0, size, size);
            drawLayers(mini, true);
        } finally {
            mini.dispose();
            zoom = savedZoom;
            panX = savedPanX;
            panY = savedPanY;
            overrideWidth = savedWidth;
            overrideHeight = savedHeight;
            pass = savedPass;
        }
        firstPersonMinimap = image;
    }

    /**
     * The 3D town meshes to draw this frame: the towns closest to the camera whose houses are big
     * enough to see. Meshes not built yet are requested and appear on a later frame.
     */
    private List<TownMeshes.Mesh> townMeshes3D() {
        if (townMeshes == null || townLayer == null || !showStationTowns) return List.of();
        List<Object[]> candidates = new ArrayList<>();
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                double reach = region.stationRadius * 1.6 + 0.005;
                if (!view3d.nearGround(region.x - reach, region.y - reach, region.x + reach, region.y + reach)
                        || !view3d.horizontallyVisible(region.x, region.y, reach)) continue;
                if (view3d.firstPerson) {
                    double dx = region.x - view3d.cameraX();
                    double dy = region.y - view3d.cameraY();
                    double meshReach = reach + FIRST_PERSON_TOWN_MESH_RANGE;
                    if (dx * dx + dy * dy > meshReach * meshReach) continue;
                }
                for (StationTownLayer.Entry entry : townLayer.entries(region)) {
                    // A town around (or right beside) the camera always counts, even if its centre is behind it.
                    double dx = entry.x() - view3d.cameraX();
                    double dy = entry.y() - view3d.cameraY();
                    double distanceSquared = dx * dx + dy * dy;
                    if (distanceSquared < entry.radius() * entry.radius() * 2.25) {
                        candidates.add(new Object[]{entry, Double.MAX_VALUE});
                        continue;
                    }
                    if (view3d.firstPerson && distanceSquared
                            > FIRST_PERSON_TOWN_MESH_RANGE * FIRST_PERSON_TOWN_MESH_RANGE) continue;
                    double pixelsPerMetre = view3d.pixelsPerMetreAt(entry.x(), entry.y());
                    if (pixelsPerMetre < 0.08) continue;
                    double[] p = view3d.project(entry.x(), entry.y(), view3d.surfaceHeight(entry.x(), entry.y()));
                    double reachPixels = entry.radius() * pixelsPerMetre / TownMeshes.WORLD_PER_METRE * 1.3;
                    if (p == null || p[0] < -reachPixels || p[1] < -reachPixels
                            || p[0] > getWidth() + reachPixels || p[1] > getHeight() + reachPixels) continue;
                    candidates.add(new Object[]{entry, pixelsPerMetre});
                }
            }
        }
        candidates.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
        List<TownMeshes.Mesh> meshes = new ArrayList<>();
        for (int i = 0; i < candidates.size() && i < MAX_3D_TOWNS; i++) {
            TownMeshes.Mesh mesh = townMeshes.get((StationTownLayer.Entry) candidates.get(i)[0]);
            if (mesh != null) meshes.add(mesh);
        }
        return meshes;
    }

    /** Draws the ground layers top-down into one of the 3D view's map textures. */
    private void renderTexture(double[] frame, boolean nearTexture) {
        int size = (int) frame[0];
        double pixelsPerWorld = frame[1];
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        double savedZoom = zoom;
        double savedPanX = panX;
        double savedPanY = panY;
        Point2D.Double target = target();
        overrideWidth = size;
        overrideHeight = size;
        pass = Pass.TEXTURE;
        try {
            zoom = pixelsPerWorld / baseScale();
            panX = -(target.x - 4.0) * pixelsPerWorld;
            panY = -(target.y - 4.0) * pixelsPerWorld;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BACKGROUND_BOTTOM);
            g.fillRect(0, 0, size, size);
            drawLayers(g, true);
        } finally {
            g.dispose();
            zoom = savedZoom;
            panX = savedPanX;
            panY = savedPanY;
            overrideWidth = -1;
            overrideHeight = -1;
            pass = Pass.NORMAL;
        }
        view3d.setTexture(image, target.x - size / 2.0 / pixelsPerWorld, target.y - size / 2.0 / pixelsPerWorld,
                pixelsPerWorld, nearTexture);
    }

    /** Tiles and towns arriving in quick succession are folded into one texture redraw. */
    private void scheduleTextureRefresh() {
        if (textureTimer != null && textureTimer.isRunning()) return;
        textureTimer = new javax.swing.Timer(320, event -> repaint());
        textureTimer.setRepeats(false);
        textureTimer.start();
    }

    private int terrainLevel = -1;

    private void drawTerrain(Graphics2D g) {
        Point2D.Double corner = worldToScreen(0.0, 0.0);
        double mapSize = scale() * World.GRID_SIZE;
        TerrainTiles tiles = terrainTiles;
        if (pass == Pass.TEXTURE) {
            if (terrainTiles3D == null) {
                terrainTiles3D = new TerrainTiles(new TerrainRenderer(terrainMap, true),
                        tileDirectory.resolveSibling(tileDirectory.getFileName() + "-3d"), terrainStamp(terrainMap), this::layerChanged);
            }
            tiles = terrainTiles3D;
            tiles.economical = true;
        }
        terrainLevel = tiles.draw(g, corner.x, corner.y, mapSize, viewWidth(), viewHeight());
    }

    /** Identifies the terrain data so cached tiles are redrawn whenever it changes. */
    private static String terrainStamp(TerrainMap terrain) {
        TerrainMap.Display display = terrain.display();
        return terrain.cellCount() + ":" + display.textureSeed() + ":" + java.util.Arrays.hashCode(display.cellColors())
                + ":" + display.lakes().size() + ":" + display.rivers().size();
    }

    private void drawDistricts(Graphics2D g) {
        double scale = scale();
        Rectangle viewport = new Rectangle(0, 0, viewWidth(), viewHeight());
        float labelAlpha = showDistrictLabels ? (float) (1.0 - smoothstep(2.3, 5.2, zoom)) : 0f;
        for (World.District district : world.districts) {
            if (pass == Pass.BILLBOARD) {
                drawDistrictLabel3D(g, district, labelAlpha);
                continue;
            }
            Point2D.Double corner = worldToScreen(district.column, district.row);
            Rectangle2D.Double tile = new Rectangle2D.Double(corner.x, corner.y, scale, scale);
            if (!tile.intersects(viewport)) continue;

            Color base = districtColor(district.id);
            int fillAlpha = terrainTiles != null && showTerrain ? 30 : 88;
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), fillAlpha));
            g.fill(tile);
            g.setColor(new Color(166, 181, 194, 125));
            g.setStroke(new BasicStroke(zoom < 2 ? 1.5f : 1.0f));
            g.draw(tile);

            if (labelAlpha > 0.01f && pass == Pass.NORMAL) {
                int fontSize = (int) clamp(scale * 0.14, 13, 36);
                Font font = getFont().deriveFont(Font.BOLD, fontSize);
                g.setFont(font);
                String label = "District " + district.id;
                FontMetrics metrics = g.getFontMetrics();
                float x = (float) (corner.x + (scale - metrics.stringWidth(label)) / 2.0);
                float y = (float) (corner.y + scale / 2.0 + metrics.getAscent() / 3.0);
                setAlpha(g, labelAlpha);
                g.setColor(new Color(236, 241, 244));
                g.drawString(label, x, y);
                setAlpha(g, 1f);
            }
        }
    }

    private void drawDistrictLabel3D(Graphics2D g, World.District district, float labelAlpha) {
        if (labelAlpha <= 0.01f || !view3d.visible(district.column + 0.5, district.row + 0.5)) return;
        Point2D.Double center = worldToScreen(district.column + 0.5, district.row + 0.5);
        if (!onScreen(center.x, center.y, 60)) return;
        g.setFont(getFont().deriveFont(Font.BOLD, (float) clamp(scale() * 0.12, 13, 30)));
        String label = "District " + district.id;
        FontMetrics metrics = g.getFontMetrics();
        setAlpha(g, labelAlpha);
        g.setColor(new Color(236, 241, 244));
        g.drawString(label, (float) (center.x - metrics.stringWidth(label) / 2.0), (float) center.y);
        setAlpha(g, 1f);
    }

    private void drawRails(Graphics2D g) {
        float alpha = (float) (0.88 * (1.0 - 0.48 * smoothstep(10.0, 30.0, zoom)));
        float innerWidth = (float) clamp(1.35 + Math.log1p(zoom) * 0.24, 1.5, 3.3);
        if (pass != Pass.BILLBOARD) drawRailLines(g, alpha, innerWidth);
        if (pass == Pass.TEXTURE) return;
        double endpointRadius = clamp(2.0 + Math.log1p(zoom) * 0.28, 2.2, 4.8);
        setAlpha(g, alpha);
        for (World.District district : world.districts) {
            World.Station capital = district.capitalStation();
            if (capital == null || !visibleHere(capital.x, capital.y)) continue;
            Point2D.Double point = worldToScreen(capital.x, capital.y);
            if (!onScreen(point.x, point.y, 12)) continue;
            StationIcons.draw(g, StationIcons.Kind.TRAIN, district.id == 1 ? CAPITAL : RAIL, point.x, point.y,
                    (int) Math.round(endpointRadius * 3.4));
        }
        setAlpha(g, 1f);
    }

    /** In 3D, icons and names are hidden when a nearer hill covers their spot. */
    private boolean visibleHere(double x, double y) {
        return pass != Pass.BILLBOARD || view3d.visible(x, y);
    }

    private void drawRailLines(Graphics2D g, float alpha, float innerWidth) {
        setAlpha(g, alpha * 0.78f);
        g.setColor(new Color(13, 35, 48));
        g.setStroke(new BasicStroke(innerWidth + 2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (World.Rail rail : world.rails) drawRailSegment(g, rail);

        setAlpha(g, alpha);
        g.setColor(RAIL);
        for (World.Rail rail : world.rails) {
            boolean districtOneExpress = "1".equals(rail.metadata.get("serviceFromDistrict"))
                    || "1".equals(rail.metadata.get("serviceToDistrict"));
            g.setStroke(new BasicStroke(districtOneExpress ? innerWidth * 1.28f : innerWidth,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawRailSegment(g, rail);
        }
        setAlpha(g, 1f);
    }

    private void drawRailSegment(Graphics2D g, World.Rail rail) {
        World.Station from = world.capitalStation(rail.fromDistrictId);
        World.Station to = world.capitalStation(rail.toDistrictId);
        if (from == null || to == null) return;
        Point2D.Double a = worldToScreen(from.x, from.y);
        Point2D.Double b = worldToScreen(to.x, to.y);
        drawSegment(g, a, b);
    }

    /**
     * Draws only the part of a straight segment that crosses the screen (plus a small margin).
     * A segment can cross the view with both ends far off it, and at deep zoom its ends can be
     * millions of pixels away, so it is clipped here rather than culled by its endpoints.
     */
    private void drawSegment(Graphics2D g, Point2D.Double a, Point2D.Double b) {
        double margin = 16;
        double minX = -margin;
        double minY = -margin;
        double maxX = viewWidth() + margin;
        double maxY = viewHeight() + margin;
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double t0 = 0;
        double t1 = 1;
        // Liang–Barsky clipping against the four screen edges.
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a.x - minX, maxX - a.x, a.y - minY, maxY - a.y};
        for (int edge = 0; edge < 4; edge++) {
            if (p[edge] == 0) {
                if (q[edge] < 0) return;
                continue;
            }
            double t = q[edge] / p[edge];
            if (p[edge] < 0) t0 = Math.max(t0, t);
            else t1 = Math.min(t1, t);
            if (t0 > t1) return;
        }
        java.awt.Stroke stroke = g.getStroke();
        if (t0 > 0 && stroke instanceof BasicStroke basic && basic.getDashArray() != null) {
            // Keep dashes anchored to the segment's real start so they don't crawl while panning.
            double skipped = Math.hypot(dx, dy) * t0;
            g.setStroke(new BasicStroke(basic.getLineWidth(), basic.getEndCap(), basic.getLineJoin(),
                    basic.getMiterLimit(), basic.getDashArray(), (float) ((basic.getDashPhase() + skipped)
                    % dashLength(basic.getDashArray()))));
        }
        g.draw(new java.awt.geom.Line2D.Double(a.x + dx * t0, a.y + dy * t0, a.x + dx * t1, a.y + dy * t1));
        g.setStroke(stroke);
    }

    private static float dashLength(float[] dashes) {
        float total = 0;
        for (float dash : dashes) total += dash;
        return Math.max(total, 1e-3f);
    }

    private void drawRegionNetwork(Graphics2D g, double alpha) {
        float roadAlpha = (float) (alpha * (1.0 - 0.35 * smoothstep(12.0, 22.0, zoom)));
        float lineWidth = (float) clamp(0.8 + Math.log1p(zoom) * 0.45, 1.0, 3.2);
        for (World.District district : world.districts) {
            if (!districtVisible(district, 40)) continue;
            setAlpha(g, roadAlpha);
            if (showHighways && pass != Pass.BILLBOARD) {
                g.setColor(HIGHWAY);
                for (World.Highway highway : district.highways) {
                    Point2D.Double from = resolveHighwayNode(district, highway.fromNode);
                    Point2D.Double to = resolveHighwayNode(district, highway.toNode);
                    if (from == null || to == null) continue;
                    Point2D.Double a = worldToScreen(from.x, from.y);
                    Point2D.Double b = worldToScreen(to.x, to.y);
                    g.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    drawSegment(g, a, b);
                }
                double junctionRadius = clamp(1.4 + Math.log1p(zoom) * 0.35, 1.8, 4.5);
                for (World.Junction junction : district.highwayJunctions) {
                    Point2D.Double point = worldToScreen(junction.x, junction.y);
                    if (!onScreen(point.x, point.y, 10)) continue;
                    g.fill(new Ellipse2D.Double(point.x - junctionRadius, point.y - junctionRadius,
                            junctionRadius * 2.0, junctionRadius * 2.0));
                }
            }

            if (showRegions && pass != Pass.TEXTURE) {
                setAlpha(g, (float) alpha);
                for (World.Region region : district.regions) {
                    if (!regionMarkerInRange(region) || !visibleHere(region.x, region.y)) continue;
                    Point2D.Double point = worldToScreen(region.x, region.y);
                    if (!onScreen(point.x, point.y, 30)) continue;
                    drawRegionIcon(g, point.x, point.y, region.capital);
                    if (showPlaceNames && zoom >= 2.0 && zoom < 11.0) {
                        float textAlpha = (float) (alpha * (1.0 - smoothstep(7.5, 11.0, zoom)));
                        setAlpha(g, textAlpha);
                        int fontSize = (int) clamp(scale() * 0.024, 10, 17);
                        g.setFont(getFont().deriveFont(region.capital ? Font.BOLD : Font.PLAIN, fontSize));
                        g.setColor(region.capital ? CAPITAL : new Color(225, 229, 219));
                        g.drawString(region.name, (float) point.x + 8, (float) point.y - 7);
                    }
                }
            }
        }
        setAlpha(g, 1f);
    }

    private void drawRegionIcon(Graphics2D g, double x, double y, boolean capital) {
        // A region's centre is where its subway (highway) line stops; a capital region's centre is the
        // district's rail station, so it shows the train.
        int size = (int) Math.round(clamp(scale() * 0.04, 11, 20));
        StationIcons.draw(g, capital ? StationIcons.Kind.TRAIN : StationIcons.Kind.SUBWAY, capital ? CAPITAL : REGION,
                x, y, capital ? size + 2 : size);
    }

    private void drawRegionDiamond(Graphics2D g, double x, double y, boolean capital) {
        double radius = clamp(scale() * 0.017, 4.5, 11.0);
        Path2D diamond = new Path2D.Double();
        diamond.moveTo(x, y - radius);
        diamond.lineTo(x + radius * 0.78, y);
        diamond.lineTo(x, y + radius);
        diamond.lineTo(x - radius * 0.78, y);
        diamond.closePath();
        g.setColor(capital ? CAPITAL : REGION);
        g.fill(diamond);
        g.setColor(new Color(31, 38, 44));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(diamond);
        g.fill(new Ellipse2D.Double(x - radius * 0.22, y - radius * 0.22,
                radius * 0.44, radius * 0.44));
        if (capital) {
            g.setColor(new Color(255, 244, 196));
            drawStar(g, x, y, radius * 0.55, radius * 0.24);
        }
    }

    private void drawStations(Graphics2D g, double alpha) {
        double labelAlpha = smoothstep(14.0, 23.0, zoom);
        List<Rectangle2D> placedLabels = new ArrayList<>();
        for (World.District district : world.districts) {
            if (!districtVisible(district, 60)) continue;
            for (World.Region region : district.regions) {
                if (!regionInView(region)) continue;
                if (pass != Pass.BILLBOARD) {
                    if (showCityRoads || showTownRoads) drawLocalRoads(g, region, alpha);
                    if (showFerries) drawFerries(region, g, alpha);
                    // In 3D the towns stand up as buildings, so their flat layouts are 2D only.
                    if (showStationTowns && townLayer != null && pass == Pass.NORMAL) drawStationTowns(g, region, (float) alpha);
                }
                if (pass == Pass.TEXTURE) continue;
                if (showFerries) drawFerryPorts(region, g, alpha);
                for (World.Station station : region.stations) {
                    if (!stationTypeVisible(station) || !stationMarkerInRange(station)
                            || !visibleHere(station.x, station.y)) continue;
                    Point2D.Double point = worldToScreen(station.x, station.y);
                    if (!onScreen(point.x, point.y, 20)) continue;
                    setAlpha(g, (float) alpha);
                    drawStation(g, station, point.x, point.y);
                    if (isSelected(district, region, station)) drawStationSelection(g, point.x, point.y);

                    boolean important = station.capital || station.kind == World.StationKind.CITY;
                    if (showPlaceNames && ((important && zoom >= 11.0) || labelAlpha > 0.05)) {
                        float actualAlpha = (float) (important ? Math.max(alpha * 0.75, labelAlpha) : labelAlpha);
                        setAlpha(g, actualAlpha);
                        int fontSize = (int) clamp(9 + Math.log(zoom), 10, 14);
                        g.setFont(getFont().deriveFont(important ? Font.BOLD : Font.PLAIN, fontSize));
                        g.setColor(station.capital ? CAPITAL : new Color(228, 235, 238));
                        FontMetrics metrics = g.getFontMetrics();
                        double labelX = point.x + stationIconSize(station) / 2.0 + 3;
                        Rectangle2D labelBounds = new Rectangle2D.Double(labelX - 1, point.y - 5 - metrics.getAscent(),
                                metrics.stringWidth(station.name) + 4, metrics.getHeight() + 2);
                        boolean overlaps = placedLabels.stream().anyMatch(existing -> existing.intersects(labelBounds));
                        if (!overlaps) {
                            g.drawString(station.name, (float) labelX, (float) point.y - 4);
                            placedLabels.add(labelBounds);
                        }
                    }
                }
            }
        }
        setAlpha(g, 1f);
    }

    /** Prevents a high viewpoint from turning all 33,000 places into fixed-size screen clutter. */
    private boolean stationMarkerInRange(World.Station station) {
        if (pass != Pass.BILLBOARD || selectedStation != null && selectedStation.station == station) return true;
        double range = station.capital ? FIRST_PERSON_CAPITAL_MARKER_RANGE
                : station.kind == World.StationKind.CITY ? FIRST_PERSON_CITY_MARKER_RANGE
                : FIRST_PERSON_TOWN_MARKER_RANGE;
        double dx = station.x - view3d.cameraX();
        double dy = station.y - view3d.cameraY();
        return dx * dx + dy * dy <= range * range;
    }

    private boolean regionMarkerInRange(World.Region region) {
        if (pass != Pass.BILLBOARD) return true;
        double range = region.capital ? FIRST_PERSON_CAPITAL_MARKER_RANGE : FIRST_PERSON_REGION_MARKER_RANGE;
        double dx = region.x - view3d.cameraX();
        double dy = region.y - view3d.cameraY();
        return dx * dx + dy * dy <= range * range;
    }

    private boolean regionInView(World.Region region) {
        if (pass == Pass.BILLBOARD) {
            double reach = region.stationRadius * 2 + 0.01;
            return view3d.nearGround(region.x - reach, region.y - reach, region.x + reach, region.y + reach)
                    && view3d.horizontallyVisible(region.x, region.y, reach);
        }
        Point2D.Double regionPoint = worldToScreen(region.x, region.y);
        // Stations can sit beyond the nominal radius (shore moves), and their town layouts reach further still.
        double clusterRadius = region.stationRadius * 1.6 * scale() + 0.0048 * scale() + 40;
        return onScreen(regionPoint.x, regionPoint.y, clusterRadius);
    }

    /** Station town layouts (Medieval Fantasy City Generator), drawn only once they are big enough to see. */
    public void setTownDirectory(java.nio.file.Path directory) {
        StationTownLayer layer = new StationTownLayer(world, directory, this::layerChanged);
        townLayer = layer.available() ? layer : null;
        townMeshes = townLayer != null ? new TownMeshes(directory, this::repaint) : null;
        if (townMeshes != null && view3d.hasHeights()) townMeshes.setHeights(view3d.heights());
        repaint();
    }

    private void drawStationTowns(Graphics2D g, World.Region region, float alpha) {
        double pxPerWorld = scale();
        for (StationTownLayer.Entry entry : townLayer.entries(region)) {
            double radius = entry.radius() * pxPerWorld;
            if (radius < StationTownLayer.MIN_RADIUS) continue;
            Point2D.Double point = worldToScreen(entry.x(), entry.y());
            if (!onScreen(point.x, point.y, radius * 1.8)) continue;
            townLayer.draw(g, entry, point.x, point.y, pxPerWorld, alpha);
        }
    }

    private boolean isSelected(World.District district, World.Region region, World.Station station) {
        return selectedStation != null && selectedStation.district.id == district.id
                && selectedStation.region.id == region.id && selectedStation.station.id == station.id;
    }

    private void drawStationSelection(Graphics2D g, double x, double y) {
        g.setColor(new Color(105, 224, 255));
        g.setStroke(new BasicStroke(2.0f));
        g.draw(new Ellipse2D.Double(x - 10.0, y - 10.0, 20.0, 20.0));
    }

    private void drawLocalRoads(Graphics2D g, World.Region region, double alpha) {
        setAlpha(g, (float) (alpha * 0.82));
        g.setColor(ROAD);
        g.setStroke(new BasicStroke((float) clamp(0.7 + Math.log1p(zoom) * 0.18, 0.8, 2.4),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (World.Road road : region.roads) {
            boolean cityRoad = Boolean.parseBoolean(road.metadata.get("servesCities"));
            boolean townRoad = Boolean.parseBoolean(road.metadata.get("servesTowns"));
            if (!(showCityRoads && cityRoad) && !(showTownRoads && townRoad)) continue;
            Point2D.Double from = resolveRoadNode(region, road.fromNode);
            Point2D.Double to = resolveRoadNode(region, road.toNode);
            if (from == null || to == null) continue;
            Point2D.Double a = worldToScreen(from.x, from.y);
            Point2D.Double b = worldToScreen(to.x, to.y);
            drawSegment(g, a, b);
        }
        setAlpha(g, (float) (alpha * 0.82));
        g.setColor(ROAD);
        double junctionRadius = clamp(1.0 + Math.log1p(zoom) * 0.16, 1.4, 3.2);
        for (World.Junction junction : region.roadJunctions) {
            if (!junctionVisible(region, junction)) continue;
            Point2D.Double point = worldToScreen(junction.x, junction.y);
            if (!onScreen(point.x, point.y, 10)) continue;
            g.fill(new Ellipse2D.Double(point.x - junctionRadius, point.y - junctionRadius,
                    junctionRadius * 2.0, junctionRadius * 2.0));
        }
    }

    private static final Color FERRY = new Color(150, 214, 255);

    private void drawFerries(World.Region region, Graphics2D g, double alpha) {
        if (region.ferries == null || region.ferries.isEmpty()) return;
        float width = (float) clamp(0.9 + Math.log1p(zoom) * 0.2, 1.0, 2.6);
        setAlpha(g, (float) alpha);
        g.setColor(FERRY);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                new float[]{width * 3.5f, width * 2.5f}, 0f));
        for (World.Road ferry : region.ferries) {
            Point2D.Double from = resolveRoadNode(region, ferry.fromNode);
            Point2D.Double to = resolveRoadNode(region, ferry.toNode);
            if (from == null || to == null) continue;
            Point2D.Double a = worldToScreen(from.x, from.y);
            Point2D.Double b = worldToScreen(to.x, to.y);
            drawSegment(g, a, b);
        }
    }

    /** Ferry port icons; stations that serve as their own dock get the ferry icon as stations. */
    private void drawFerryPorts(World.Region region, Graphics2D g, double alpha) {
        if (region.ferryPorts == null || region.ferryPorts.isEmpty()) return;
        setAlpha(g, (float) alpha);
        g.setColor(FERRY);
        double size = clamp(2.2 + Math.log1p(zoom) * 0.35, 3.0, 6.0);
        for (World.Junction port : region.ferryPorts) {
            if (!visibleHere(port.x, port.y)) continue;
            Point2D.Double point = worldToScreen(port.x, port.y);
            if (!onScreen(point.x, point.y, 20)) continue;
            StationIcons.draw(g, StationIcons.Kind.FERRY, FERRY, point.x, point.y, (int) Math.round(size * 3));
            if (showPlaceNames && zoom >= 40) {
                g.setFont(getFont().deriveFont(Font.ITALIC, 10f));
                g.drawString("Ferry port", (float) (point.x + size * 1.5 + 3), (float) (point.y + 4));
            }
        }
    }

    private boolean junctionVisible(World.Region region, World.Junction junction) {
        String key = "junction:" + junction.id;
        for (World.Road road : region.roads) {
            if (!road.fromNode.equals(key) && !road.toNode.equals(key)) continue;
            boolean cityRoad = Boolean.parseBoolean(road.metadata.get("servesCities"));
            boolean townRoad = Boolean.parseBoolean(road.metadata.get("servesTowns"));
            if ((showCityRoads && cityRoad) || (showTownRoads && townRoad)) return true;
        }
        return false;
    }

    private Point2D.Double resolveHighwayNode(World.District district, String key) {
        if (key.startsWith("region:")) {
            World.Region region = district.regionById(Integer.parseInt(key.substring("region:".length())));
            return region == null ? null : new Point2D.Double(region.x, region.y);
        }
        if (key.startsWith("junction:")) {
            World.Junction junction = district.highwayJunctionById(
                    Integer.parseInt(key.substring("junction:".length())));
            return junction == null ? null : new Point2D.Double(junction.x, junction.y);
        }
        return null;
    }

    private Point2D.Double resolveRoadNode(World.Region region, String key) {
        if (key.startsWith("station:")) {
            World.Station station = region.stationById(Integer.parseInt(key.substring("station:".length())));
            return station == null ? null : new Point2D.Double(station.x, station.y);
        }
        if (key.startsWith("junction:")) {
            World.Junction junction = region.roadJunctionById(
                    Integer.parseInt(key.substring("junction:".length())));
            return junction == null ? null : new Point2D.Double(junction.x, junction.y);
        }
        if (key.startsWith("port:")) {
            World.Junction port = region.ferryPortById(Integer.parseInt(key.substring("port:".length())));
            return port == null ? null : new Point2D.Double(port.x, port.y);
        }
        return null;
    }

    private boolean stationTypeVisible(World.Station station) {
        return switch (station.kind) {
            case CITY -> showCities;
            case TOWN -> showTowns;
        };
    }

    private static final Color CITY_ICON = new Color(241, 214, 135);
    private static final Color TOWN_ICON = new Color(183, 218, 206);

    /** Rail station for capitals, subway stop for region centres, ferry for docks, bus stop otherwise. */
    private StationIcons.Kind stationIconKind(World.Station station) {
        if (station.capital) return StationIcons.Kind.TRAIN;
        if (hubStations.contains(station)) return StationIcons.Kind.SUBWAY;
        if ("true".equals(station.metadata.get("ferryDock"))) return StationIcons.Kind.FERRY;
        return StationIcons.Kind.BUS;
    }

    /** Icons grow gently with zoom, and more important stops are a little larger. */
    private int stationIconSize(World.Station station) {
        int base = (int) Math.round(clamp(8 + Math.log(Math.max(1, zoom)) * 1.1, 10, 17));
        int boost = station.capital ? 5 : hubStations.contains(station) ? 3 : station.kind == World.StationKind.CITY ? 2 : 0;
        return base + boost;
    }

    private void drawStation(Graphics2D g, World.Station station, double x, double y) {
        Color tint = station.capital ? CAPITAL : station.kind == World.StationKind.CITY ? CITY_ICON : TOWN_ICON;
        StationIcons.draw(g, stationIconKind(station), tint, x, y, stationIconSize(station));
    }

    private void drawStationShape(Graphics2D g, World.Station station, double x, double y) {
        if (station.capital) {
            g.setColor(CAPITAL);
            drawStar(g, x, y, 6.5, 3.0);
            g.setColor(new Color(43, 35, 17));
            g.setStroke(new BasicStroke(0.8f));
            g.draw(new Ellipse2D.Double(x - 6.8, y - 6.8, 13.6, 13.6));
        } else if (station.kind == World.StationKind.CITY) {
            g.setColor(new Color(241, 214, 135));
            g.fill(new Rectangle2D.Double(x - 3.2, y - 3.2, 6.4, 6.4));
        } else {
            g.setColor(new Color(183, 218, 206));
            g.fill(new Ellipse2D.Double(x - 2.7, y - 2.7, 5.4, 5.4));
        }
    }

    private static Color modeColor(TicketPlanner.Mode mode) {
        return switch (mode) {
            case BUS -> new Color(92, 230, 137);
            case SUBWAY -> new Color(244, 205, 128);
            case TRAIN -> new Color(110, 222, 255);
            case FERRY -> new Color(176, 156, 255);
        };
    }

    private void drawTicketRoute(Graphics2D g) {
        if (pass == Pass.BILLBOARD) {
            drawTicketStops(g, (float) clamp(3.0 + Math.log1p(zoom) * 0.5, 3.5, 7.0));
            return;
        }
        if (focusTicketRoute) {
            setAlpha(g, 0.72f);
            g.setColor(new Color(6, 9, 14));
            g.fillRect(0, 0, viewWidth(), viewHeight());
        }
        setAlpha(g, 1f);
        float width = (float) clamp(3.0 + Math.log1p(zoom) * 0.5, 3.5, 7.0);
        List<Path2D.Double> paths = new ArrayList<>();
        for (TicketPlanner.Leg leg : ticket.legs()) {
            Path2D.Double path = new Path2D.Double();
            for (int index = 0; index < leg.path().size(); index++) {
                Point2D.Double point = worldToScreen(leg.path().get(index).x, leg.path().get(index).y);
                if (index == 0) path.moveTo(point.x, point.y);
                else path.lineTo(point.x, point.y);
            }
            paths.add(path);
        }
        g.setColor(new Color(5, 8, 12));
        g.setStroke(new BasicStroke(width + 4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Path2D.Double path : paths) g.draw(path);
        for (int index = 0; index < paths.size(); index++) {
            TicketPlanner.Mode mode = ticket.legs().get(index).mode();
            float legWidth = mode == TicketPlanner.Mode.TRAIN ? width
                    : width * (mode == TicketPlanner.Mode.BUS ? 0.8f : 0.92f);
            g.setColor(modeColor(mode));
            g.setStroke(mode == TicketPlanner.Mode.FERRY
                    ? new BasicStroke(legWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                    new float[]{legWidth * 2.2f, legWidth * 1.6f}, 0f)
                    : new BasicStroke(legWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(paths.get(index));
        }
        if (pass == Pass.NORMAL) drawTicketStops(g, width);
    }

    private void drawTicketStops(Graphics2D g, float width) {
        // Intermediate stops are small rings; boarding and alighting points are large labelled discs.
        List<Rectangle2D> placedLabels = new ArrayList<>();
        for (int index = 0; index < ticket.legs().size(); index++) {
            TicketPlanner.Leg leg = ticket.legs().get(index);
            drawRouteStop(g, leg.boardAt().station(), index == 0 ? "START  " : "", width, placedLabels);
            boolean last = index == ticket.legs().size() - 1;
            drawRouteStop(g, leg.alightAt().station(), last ? "END  " : "", width, placedLabels);
        }
        boolean labelIntermediate = zoom >= 12.0;
        for (TicketPlanner.Leg leg : ticket.legs()) {
            for (int index = 1; index < leg.stops().size() - 1; index++) {
                World.Station station = leg.stops().get(index).station();
                if (!visibleHere(station.x, station.y)) continue;
                Point2D.Double point = worldToScreen(station.x, station.y);
                if (!onScreen(point.x, point.y, 10)) continue;
                double radius = width * 0.55;
                Ellipse2D.Double ring = new Ellipse2D.Double(point.x - radius, point.y - radius, radius * 2, radius * 2);
                g.setColor(new Color(12, 17, 23));
                g.fill(ring);
                g.setColor(modeColor(leg.mode()));
                g.setStroke(new BasicStroke(1.6f));
                g.draw(ring);
                if (labelIntermediate) drawRouteLabel(g, station.name, point, false, placedLabels);
            }
        }
    }

    private void drawRouteStop(Graphics2D g, World.Station station, String prefix, float width,
                               List<Rectangle2D> placedLabels) {
        if (!visibleHere(station.x, station.y)) return;
        Point2D.Double point = worldToScreen(station.x, station.y);
        if (!onScreen(point.x, point.y, 40)) return;
        double radius = width * (prefix.isEmpty() ? 1.05 : 1.45);
        Ellipse2D.Double disc = new Ellipse2D.Double(point.x - radius, point.y - radius, radius * 2, radius * 2);
        g.setColor(prefix.startsWith("START") ? new Color(92, 230, 137)
                : prefix.startsWith("END") ? new Color(255, 112, 104) : new Color(245, 248, 250));
        g.fill(disc);
        g.setColor(new Color(5, 8, 12));
        g.setStroke(new BasicStroke(2f));
        g.draw(disc);
        drawRouteLabel(g, prefix + station.name, point, true, placedLabels);
    }

    private void drawRouteLabel(Graphics2D g, String text, Point2D.Double point, boolean major,
                                List<Rectangle2D> placedLabels) {
        g.setFont(getFont().deriveFont(major ? Font.BOLD : Font.PLAIN, major ? 12f : 11f));
        FontMetrics metrics = g.getFontMetrics();
        double x = point.x + 10;
        double y = point.y - 10;
        Rectangle2D box = new Rectangle2D.Double(x - 4, y - metrics.getAscent() - 2,
                metrics.stringWidth(text) + 8, metrics.getHeight() + 3);
        if (placedLabels.stream().anyMatch(existing -> existing.intersects(box))) return;
        placedLabels.add(box);
        setAlpha(g, 0.86f);
        g.setColor(new Color(10, 14, 20));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(box.getX(), box.getY(), box.getWidth(), box.getHeight(), 7, 7));
        setAlpha(g, 1f);
        g.setColor(major ? new Color(245, 248, 250) : new Color(196, 208, 216));
        g.drawString(text, (float) x, (float) y);
    }

    /** Frames drawn in the last second, and how long the previous frame took to draw. */
    private void drawFps(Graphics2D g) {
        String text = view3d.enabled && view3d.firstPerson
                ? String.format(Locale.ROOT, "%d fps  %.0f ms  3D %.1f ms @ %d%%",
                        frameTimes.size(), lastFrameMillis, view3d.lastRenderMillis(), view3d.resolutionPercent())
                : String.format(Locale.ROOT, "%d fps  %.0f ms", frameTimes.size(), lastFrameMillis);
        g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(text) + 12;
        int x = viewWidth() - width - 8;
        setAlpha(g, 0.8f);
        g.setColor(new Color(12, 17, 23));
        g.fillRoundRect(x, 8, width, metrics.getHeight() + 6, 8, 8);
        setAlpha(g, 1f);
        g.setColor(new Color(190, 200, 208));
        g.drawString(text, x + 6, 11 + metrics.getAscent());
    }

    private void drawOverlay(Graphics2D g, double regionAlpha, double stationAlpha) {
        int width = 292;
        int height = 101;
        setAlpha(g, 0.88f);
        g.setColor(new Color(12, 17, 23));
        g.fillRoundRect(14, 14, width, height, 12, 12);
        setAlpha(g, 1f);
        g.setColor(new Color(226, 232, 235));
        g.setFont(getFont().deriveFont(Font.BOLD, 13f));
        g.drawString(String.format(Locale.ROOT, "Atlas scale  ×%.2f", zoom), 28, 38);
        g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g.setColor(new Color(163, 174, 184));
        String layer = stationAlpha > 0.45 ? "Station layer" : regionAlpha > 0.45 ? "Region layer" : "District layer";
        g.drawString(layer + "  •  Ctrl + wheel to zoom", 28, 58);
        g.drawString(view3d.enabled ? "3D  •  right-drag to tilt and turn" : "Double-click a region or station", 28, 77);
        g.setColor(RAIL);
        g.drawString("Rails", 28, 94);
        g.setColor(new Color(216, 187, 129));
        g.drawString("Highways", 68, 94);
        g.setColor(ROAD);
        g.drawString("Roads", 137, 94);
    }

    private void updateStatus(int screenX, int screenY) {
        Point2D.Double point = screenToWorld(screenX, screenY);
        int column = (int) Math.floor(point.x);
        int row = (int) Math.floor(point.y);
        World.District district = world.districtAt(column, row);
        if (district == null) {
            status.accept(" Outside the mapped districts  •  zoom ×" + String.format(Locale.ROOT, "%.2f", zoom));
            return;
        }

        StationSelection hoveredStation = zoom >= 7.0
                ? nearestStationSelection(point.x, point.y, 12.0) : null;
        if (hoveredStation != null) {
            status.accept(" District " + district.id + "  >  " + hoveredStation.region.name + "  >  "
                    + hoveredStation.station.name + "  ["
                    + (hoveredStation.station.capital ? "CAPITAL CITY" : hoveredStation.station.kind) + "]");
            return;
        }

        World.Region region = nearestRegion(point.x, point.y, 24.0);
        if (zoom >= 7.0 && region != null) {
            World.Station station = nearestStation(region, point.x, point.y, 12.0);
            if (station != null) {
                status.accept(" District " + district.id + "  ›  " + region.name + "  ›  " + station.name
                        + "  [" + (station.capital ? "CAPITAL CITY" : station.kind) + "]");
                return;
            }
        }
        if (region != null && zoom >= 1.5) {
            status.accept(" District " + district.id + "  ›  " + region.name
                    + (region.capital ? "  [CAPITAL REGION]" : "") + "  •  " + region.stations.size()
                    + " stations  •  " + region.roads.size() + " roads");
        } else {
            status.accept(" District " + district.id + "  •  " + district.regions.size() + " regions  •  "
                    + district.highways.size() + " highways  •  zoom ×" + String.format(Locale.ROOT, "%.2f", zoom));
        }
    }

    private World.Region nearestRegion(double worldX, double worldY, double maxPixels) {
        int column = (int) Math.floor(worldX);
        int row = (int) Math.floor(worldY);
        World.District district = world.districtAt(column, row);
        if (district == null) return null;
        World.Region best = null;
        double bestDistance = maxPixels / scale();
        for (World.Region region : district.regions) {
            double distance = Math.hypot(region.x - worldX, region.y - worldY);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = region;
            }
        }
        return best;
    }

    private World.Station nearestStation(World.Region region, double worldX, double worldY, double maxPixels) {
        World.Station best = null;
        double bestDistance = maxPixels / scale();
        for (World.Station station : region.stations) {
            double distance = Math.hypot(station.x - worldX, station.y - worldY);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = station;
            }
        }
        return best;
    }

    private StationSelection nearestStationSelection(double worldX, double worldY, double maxPixels) {
        World.District district = world.districtAt((int) Math.floor(worldX), (int) Math.floor(worldY));
        if (district == null) return null;
        StationSelection best = null;
        double bestDistance = maxPixels / scale();
        for (World.Region region : district.regions) {
            for (World.Station station : region.stations) {
                double distance = Math.hypot(station.x - worldX, station.y - worldY);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new StationSelection(district, region, station);
                }
            }
        }
        return best;
    }

    private boolean districtVisible(World.District district, double margin) {
        if (pass == Pass.BILLBOARD) {
            // In 3D, judge by ground distance: a district's centre can be far off-screen while you stand in it.
            return view3d.nearGround(district.column, district.row, district.column + 1, district.row + 1)
                    && view3d.horizontallyVisible(district.column + 0.5, district.row + 0.5, Math.sqrt(0.5));
        }
        Point2D.Double corner = worldToScreen(district.column, district.row);
        double size = scale();
        return corner.x + size >= -margin && corner.y + size >= -margin
                && corner.x <= viewWidth() + margin && corner.y <= viewHeight() + margin;
    }

    private boolean onScreen(double x, double y, double margin) {
        return x >= -margin && y >= -margin && x <= viewWidth() + margin && y <= viewHeight() + margin;
    }

    private double baseScale() {
        return Math.max(1.0, Math.min((viewWidth() - 70.0) / 8.0, (viewHeight() - 70.0) / 8.0));
    }

    private double scale() { return baseScale() * zoom; }

    private Point2D.Double worldToScreen(double worldX, double worldY) {
        if (pass == Pass.BILLBOARD) {
            double[] p = view3d.project(worldX, worldY, view3d.surfaceHeight(worldX, worldY));
            return p == null ? new Point2D.Double(-1e9, -1e9) : new Point2D.Double(p[0], p[1]);
        }
        double scale = scale();
        return new Point2D.Double(viewWidth() / 2.0 + panX + (worldX - 4.0) * scale,
                viewHeight() / 2.0 + panY + (worldY - 4.0) * scale);
    }

    private Point2D.Double screenToWorld(double screenX, double screenY) {
        if (view3d.enabled && pass != Pass.TEXTURE) {
            Point2D.Double ground = view3d.pick(screenX, screenY);
            return ground != null ? ground : view3d.groundPoint(screenX, screenY);
        }
        double scale = scale();
        return new Point2D.Double(4.0 + (screenX - viewWidth() / 2.0 - panX) / scale,
                4.0 + (screenY - viewHeight() / 2.0 - panY) / scale);
    }

    /** Size of the surface being drawn: the panel, or the 3D view's map texture while it is rendered. */
    private int viewWidth() { return overrideWidth > 0 ? overrideWidth : getWidth(); }

    private int viewHeight() { return overrideHeight > 0 ? overrideHeight : getHeight(); }

    private static Color districtColor(int id) {
        float hue = (float) ((id * 0.61803398875) % 1.0);
        Color color = Color.getHSBColor(hue, 0.24f, 0.46f);
        return new Color(color.getRed(), color.getGreen(), color.getBlue());
    }

    private static void drawStar(Graphics2D g, double x, double y, double outer, double inner) {
        Path2D star = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2.0 + i * Math.PI / 5.0;
            double radius = (i & 1) == 0 ? outer : inner;
            double px = x + Math.cos(angle) * radius;
            double py = y + Math.sin(angle) * radius;
            if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
        }
        star.closePath();
        g.fill(star);
    }

    private static void setAlpha(Graphics2D g, float alpha) {
        Composite composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp(alpha, 0.0, 1.0));
        g.setComposite(composite);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
