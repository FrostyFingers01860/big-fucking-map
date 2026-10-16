package districts.ui;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Walking around the 3D map in first person: WASD (or the arrow keys) to move, Shift to run, Space to
 * jump, the mouse to look, Esc to leave. The walker has gravity, stands on the ground (or the water
 * surface), and collides with houses, town walls, towers and gatehouses — it can walk through gate
 * passages, and jump onto low roofs. Distances are real metres; the ground keeps the 3D view's relief
 * exaggeration.
 */
final class FirstPerson {
    /** Walker settings, saved beside the world. */
    static final class Settings {
        double height = 1.75;        // metres (eye level is a little lower); also sets the walker's width
        double speed = 4.5;          // metres per second
        double jump = 1.0;           // metres
        double sensitivity = 0.12;   // degrees of turn per pixel of mouse movement
        double fieldOfView = 40.0;   // vertical degrees
        boolean flyMode;
        double flySpeed = 250.0;     // metres per second; the slider reaches cross-map speeds

        double radius() { return Math.max(0.12, height * 0.17); }
    }

    private static final double GRAVITY = 9.81;          // m/s²
    private static final double STEP = 0.45;             // metres the walker steps up without jumping
    private static final double RUN = 2.5;
    private static final double MIN_FLY_SPEED = 5.0;
    private static final double MAX_FLY_SPEED = 500_000.0;

    private final MapPanel map;
    private final View3D view;
    private final Path settingsFile;
    final Settings settings = new Settings();
    private Runnable changed = () -> {};

    private boolean active;
    private double x;
    private double y;
    private double feet;          // world units
    private double verticalSpeed; // world units per second
    private double yaw;
    private double pitch;         // radians; 0 is level
    private boolean onGround;
    private boolean forward, back, left, right, jump, descend, run;
    private Timer timer;
    private long lastTick;
    private Robot robot;
    private KeyEventDispatcher keys;
    private WindowAdapter windowWatcher;
    private Window window;
    private double savedTilt;

    FirstPerson(MapPanel map, View3D view, Path settingsFile) {
        this.map = map;
        this.view = view;
        this.settingsFile = settingsFile;
        loadSettings();
        view.setFirstPersonFieldOfView(settings.fieldOfView);
    }

    void onChange(Runnable listener) { this.changed = listener; }

    boolean active() { return active; }

    // ------------------------------------------------------------------ entering and leaving

    /** Starts walking at a world point, facing the given heading. */
    void enter(double startX, double startY, double heading) {
        if (active) return;
        active = true;
        x = startX;
        y = startY;
        yaw = heading;
        pitch = 0;
        // Land on whatever is here: the ground, or the roof of a house if starting above one.
        feet = support(map.townMeshesInView(), Double.POSITIVE_INFINITY);
        verticalSpeed = 0;
        onGround = true;
        forward = back = left = right = jump = descend = run = false;
        savedTilt = view.tilt;
        view.firstPerson = true;
        view.resetFirstPersonPerformance();
        applyCamera();

        try {
            robot = new Robot();
        } catch (AWTException | SecurityException unavailable) {
            robot = null;     // looking around then needs a drag
        }
        map.setCursor(Toolkit.getDefaultToolkit().createCustomCursor(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "none"));
        centreMouse();
        keys = this::key;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
        window = SwingUtilities.getWindowAncestor(map);
        if (window != null) {
            // Leaving the window (Alt+Tab, another window) lets go of the mouse.
            windowWatcher = new WindowAdapter() {
                @Override public void windowDeactivated(WindowEvent event) { exit(); }
            };
            window.addWindowListener(windowWatcher);
        }
        map.requestFocusInWindow();
        lastTick = System.nanoTime();
        timer = new Timer(10, event -> tick());
        timer.setInitialDelay(0);
        timer.setCoalesce(true);
        timer.start();
        changed.run();
        map.repaint();
    }

    void exit() {
        if (!active) return;
        active = false;
        timer.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
        if (window != null) window.removeWindowListener(windowWatcher);
        view.firstPerson = false;
        view.tilt = savedTilt;
        view.heading = yaw;
        map.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        changed.run();
        map.repaint();
    }

    // ------------------------------------------------------------------ input

    private boolean key(KeyEvent event) {
        if (!active) return false;
        boolean down = event.getID() == KeyEvent.KEY_PRESSED;
        if (event.getID() != KeyEvent.KEY_PRESSED && event.getID() != KeyEvent.KEY_RELEASED) {
            return event.getID() == KeyEvent.KEY_TYPED;   // swallow typing while walking
        }
        switch (event.getKeyCode()) {
            case KeyEvent.VK_W, KeyEvent.VK_UP -> forward = down;
            case KeyEvent.VK_S, KeyEvent.VK_DOWN -> back = down;
            case KeyEvent.VK_A, KeyEvent.VK_LEFT -> left = down;
            case KeyEvent.VK_D, KeyEvent.VK_RIGHT -> right = down;
            case KeyEvent.VK_SPACE -> jump = down;
            case KeyEvent.VK_CONTROL -> descend = down;
            case KeyEvent.VK_SHIFT -> run = down;
            case KeyEvent.VK_F -> {
                // Toggle on release so key-repeat cannot switch flight on and off rapidly.
                if (!down) {
                    settings.flyMode = !settings.flyMode;
                    verticalSpeed = 0;
                    onGround = false;
                    saveSettings();
                    map.repaint();
                }
            }
            case KeyEvent.VK_ESCAPE -> {
                if (down) SwingUtilities.invokeLater(this::exit);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Mouse look: every movement turns the view, then the pointer is put back in the middle. */
    void mouseMoved(MouseEvent event) {
        if (!active) return;
        int cx = map.getWidth() / 2;
        int cy = map.getHeight() / 2;
        int dx = event.getX() - cx;
        int dy = event.getY() - cy;
        if (dx == 0 && dy == 0) return;
        double turn = Math.toRadians(settings.sensitivity);
        yaw += dx * turn;
        pitch = Math.max(Math.toRadians(-89), Math.min(Math.toRadians(89), pitch - dy * turn));
        centreMouse();
        applyCamera();
        map.repaint();
    }

    private void centreMouse() {
        if (robot == null || !map.isShowing()) return;
        Point origin = map.getLocationOnScreen();
        robot.mouseMove(origin.x + map.getWidth() / 2, origin.y + map.getHeight() / 2);
    }

    // ------------------------------------------------------------------ simulation

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastTick) / 1e9);
        lastTick = now;
        if (dt <= 0) return;
        double metre = TownMeshes.WORLD_PER_METRE;
        double radius = settings.radius() * metre;

        if (settings.flyMode) {
            fly(dt, metre);
            applyCamera();
            map.repaint();
            return;
        }

        // Walking: split into small steps so a fast walker cannot pass through a thin wall.
        double moveForward = (forward ? 1 : 0) - (back ? 1 : 0);
        double moveRight = (right ? 1 : 0) - (left ? 1 : 0);
        double fx = Math.sin(yaw), fy = -Math.cos(yaw), rx = Math.cos(yaw), ry = Math.sin(yaw);
        double mx = moveForward * fx + moveRight * rx;
        double my = moveForward * fy + moveRight * ry;
        double length = Math.hypot(mx, my);
        List<TownMeshes.Mesh> towns = map.townMeshesInView();
        if (length > 0) {
            double distance = settings.speed * (run ? RUN : 1) * metre * dt;
            int steps = Math.max(1, (int) Math.ceil(distance / (radius * 0.5)));
            for (int step = 0; step < steps; step++) {
                x += mx / length * distance / steps;
                y += my / length * distance / steps;
                pushOut(towns, radius);
            }
        } else {
            pushOut(towns, radius);
        }

        // Falling, jumping and landing.
        double ground = support(towns, feet + STEP * metre);
        if (onGround && jump) {
            verticalSpeed = Math.sqrt(2 * GRAVITY * settings.jump) * metre;
            onGround = false;
        }
        verticalSpeed -= GRAVITY * metre * dt;
        feet += verticalSpeed * dt;
        double ceiling = ceiling(towns, feet + STEP * metre);
        double tall = settings.height * metre;
        if (feet + tall > ceiling && verticalSpeed > 0) {
            feet = ceiling - tall;
            verticalSpeed = 0;
        }
        if (feet <= ground) {
            feet = ground;
            verticalSpeed = 0;
            onGround = true;
        } else if (onGround && verticalSpeed <= 0 && feet - ground < STEP * metre) {
            feet = ground;    // follow the ground down slopes and steps instead of hopping
            verticalSpeed = 0;
        } else {
            onGround = false;
        }
        applyCamera();
        map.repaint();
    }

    /** Free flight: WASD follows the look direction, Space rises, Ctrl descends, and collisions are off. */
    private void fly(double dt, double metre) {
        double moveForward = (forward ? 1 : 0) - (back ? 1 : 0);
        double moveRight = (right ? 1 : 0) - (left ? 1 : 0);
        double moveVertical = (jump ? 1 : 0) - (descend ? 1 : 0);
        double cosPitch = Math.cos(pitch);
        double fx = Math.sin(yaw), fy = -Math.cos(yaw), rx = Math.cos(yaw), ry = Math.sin(yaw);
        double mx = moveForward * fx * cosPitch + moveRight * rx;
        double my = moveForward * fy * cosPitch + moveRight * ry;
        double mz = moveForward * Math.sin(pitch) + moveVertical;
        double length = Math.sqrt(mx * mx + my * my + mz * mz);
        if (length > 0) {
            double distance = settings.flySpeed * (run ? RUN : 1) * metre * dt / length;
            x = Math.max(0, Math.min(districts.model.World.GRID_SIZE, x + mx * distance));
            y = Math.max(0, Math.min(districts.model.World.GRID_SIZE, y + my * distance));
            feet += mz * distance;
        }
        // Flight can skim the terrain but never passes below it.
        double floor = view.surfaceHeight(x, y);
        if (feet < floor) feet = floor;
        verticalSpeed = 0;
        onGround = feet <= floor;
    }

    /** Puts the walker's eye into the view, and the map's target under it. */
    private void applyCamera() {
        view.heading = yaw;
        view.tilt = Math.PI / 2 + pitch;
        view.eyeZ = feet + settings.height * 0.93 * TownMeshes.WORLD_PER_METRE;
        map.moveTarget(x, y);
    }

    /** The highest thing to stand on under the walker's centre: ground, water, or a roof or wall top. */
    private double support(List<TownMeshes.Mesh> towns, double reach) {
        double best = view.surfaceHeight(x, y);
        for (TownMeshes.Mesh mesh : towns) {
            double px = x - mesh.centerX, py = y - mesh.centerY;
            if (Math.hypot(px, py) > mesh.radius) continue;
            for (int s = 0; s < mesh.solids.length; s++) {
                if (!inBox(mesh, s, px, py, 0)) continue;
                double top = top(mesh, s);
                if (top <= reach && top > best && inside(mesh.solids[s], px, py)) best = top;
            }
        }
        return best;
    }

    /** The lowest overhead stone (an arch, say) above the walker. */
    private double ceiling(List<TownMeshes.Mesh> towns, double above) {
        double best = Double.POSITIVE_INFINITY;
        for (TownMeshes.Mesh mesh : towns) {
            double px = x - mesh.centerX, py = y - mesh.centerY;
            if (Math.hypot(px, py) > mesh.radius) continue;
            for (int s = 0; s < mesh.solids.length; s++) {
                if (!inBox(mesh, s, px, py, 0)) continue;
                double bottom = bottom(mesh, s);
                if (bottom >= above && bottom < best && inside(mesh.solids[s], px, py)) best = bottom;
            }
        }
        return best;
    }

    /** Moves the walker out of any building, wall or tower it overlaps at body height. */
    private void pushOut(List<TownMeshes.Mesh> towns, double radius) {
        double metre = TownMeshes.WORLD_PER_METRE;
        double low = feet + STEP * metre;
        double high = feet + settings.height * metre;
        for (int pass = 0; pass < 3; pass++) {
            boolean moved = false;
            for (TownMeshes.Mesh mesh : towns) {
                double px = x - mesh.centerX, py = y - mesh.centerY;
                if (Math.hypot(px, py) > mesh.radius + radius) continue;
                for (int s = 0; s < mesh.solids.length; s++) {
                    if (!inBox(mesh, s, px, py, radius)) continue;
                    if (top(mesh, s) <= low || bottom(mesh, s) >= high) continue;
                    double[] out = pushOutOf(mesh.solids[s], px, py, radius);
                    if (out != null) {
                        px = out[0];
                        py = out[1];
                        x = mesh.centerX + px;
                        y = mesh.centerY + py;
                        moved = true;
                    }
                }
            }
            if (!moved) break;
        }
    }

    private double top(TownMeshes.Mesh mesh, int s) {
        return view.reliefToWorld(mesh.solidHeights[s * 4 + 2]) + mesh.solidHeights[s * 4 + 3] * TownMeshes.WORLD_PER_METRE;
    }

    private double bottom(TownMeshes.Mesh mesh, int s) {
        return view.reliefToWorld(mesh.solidHeights[s * 4]) + mesh.solidHeights[s * 4 + 1] * TownMeshes.WORLD_PER_METRE;
    }

    private static boolean inBox(TownMeshes.Mesh mesh, int s, double px, double py, double margin) {
        float[] box = mesh.solidBoxes;
        return px >= box[s * 4] - margin && py >= box[s * 4 + 1] - margin
                && px <= box[s * 4 + 2] + margin && py <= box[s * 4 + 3] + margin;
    }

    private static boolean inside(float[] poly, double px, double py) {
        boolean inside = false;
        int n = poly.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = poly[2 * i], yi = poly[2 * i + 1], xj = poly[2 * j], yj = poly[2 * j + 1];
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }

    /** The point moved to just outside the polygon by the radius, or null if it is already clear. */
    private static double[] pushOutOf(float[] poly, double px, double py, double radius) {
        int n = poly.length / 2;
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestX = 0, bestY = 0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double ax = poly[2 * j], ay = poly[2 * j + 1], bx = poly[2 * i], by = poly[2 * i + 1];
            double dx = bx - ax, dy = by - ay;
            double lengthSquared = dx * dx + dy * dy;
            double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
            double cx = ax + t * dx, cy = ay + t * dy;
            double distance = Math.hypot(px - cx, py - cy);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestX = cx;
                bestY = cy;
            }
        }
        boolean inside = inside(poly, px, py);
        if (!inside && bestDistance >= radius) return null;
        double nx, ny;
        if (bestDistance < 1e-12) {
            // Exactly on the edge: push away from the polygon's middle.
            double mx = 0, my = 0;
            for (int i = 0; i < n; i++) {
                mx += poly[2 * i] / n;
                my += poly[2 * i + 1] / n;
            }
            nx = px - mx;
            ny = py - my;
        } else if (inside) {
            nx = bestX - px;
            ny = bestY - py;
        } else {
            nx = px - bestX;
            ny = py - bestY;
        }
        double length = Math.hypot(nx, ny);
        if (length < 1e-15) return null;
        return new double[]{bestX + nx / length * radius * 1.001, bestY + ny / length * radius * 1.001};
    }

    // ------------------------------------------------------------------ heads-up display

    void drawHud(Graphics2D g, int width, int height) {
        if (!active) return;
        int cx = width / 2;
        int cy = height / 2;
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0, 0, 0, 120));
        g.drawLine(cx - 8, cy + 1, cx + 8, cy + 1);
        g.drawLine(cx + 1, cy - 8, cx + 1, cy + 8);
        g.setColor(new Color(255, 255, 255, 220));
        g.drawLine(cx - 8, cy, cx + 8, cy);
        g.drawLine(cx, cy - 8, cx, cy + 8);
        String help = settings.flyMode
                ? "FLY  •  WASD move  •  Space rise  •  Ctrl descend  •  Shift boost  •  F walk  •  Esc exit"
                : "First person  •  WASD move  •  Shift run  •  Space jump  •  F fly  •  Esc exit";
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int textWidth = g.getFontMetrics().stringWidth(help);
        g.setColor(new Color(13, 17, 23, 190));
        g.fillRoundRect(cx - textWidth / 2 - 10, height - 38, textWidth + 20, 24, 8, 8);
        g.setColor(new Color(230, 237, 243));
        g.drawString(help, cx - textWidth / 2, height - 21);
    }

    // ------------------------------------------------------------------ settings

    void showSettings(Component parent) {
        JSpinner height = spinner(settings.height, 0.5, 5.0, 0.05);
        JSpinner speed = spinner(settings.speed, 0.5, 60.0, 0.5);
        JSpinner jumpHeight = spinner(settings.jump, 0.1, 10.0, 0.1);
        JSpinner sensitivity = spinner(settings.sensitivity, 0.01, 1.0, 0.01);
        JSlider fieldOfView = new JSlider(25, 110, (int) Math.round(settings.fieldOfView));
        fieldOfView.setPaintTicks(true);
        fieldOfView.setMajorTickSpacing(10);
        JLabel fieldOfViewLabel = new JLabel(fieldOfViewText(settings.fieldOfView));
        JCheckBox flyMode = new JCheckBox("Enabled", settings.flyMode);
        JSlider flySpeed = new JSlider(0, 1000, flySpeedToSlider(settings.flySpeed));
        flySpeed.setPaintTicks(true);
        flySpeed.setMajorTickSpacing(200);
        JLabel flySpeedLabel = new JLabel(flySpeedText(settings.flySpeed));
        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        panel.add(new JLabel("Height (m, also sets size)"));
        panel.add(height);
        panel.add(new JLabel("Walk speed (m/s, Shift runs ×2.5)"));
        panel.add(speed);
        panel.add(new JLabel("Jump height (m)"));
        panel.add(jumpHeight);
        panel.add(new JLabel("Aim sensitivity (° per pixel)"));
        panel.add(sensitivity);
        panel.add(fieldOfViewLabel);
        panel.add(fieldOfView);
        panel.add(new JLabel("Fly mode (F toggles)"));
        panel.add(flyMode);
        panel.add(flySpeedLabel);
        panel.add(flySpeed);
        Window owner = SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "First person settings", java.awt.Dialog.ModalityType.MODELESS);
        Runnable apply = () -> {
            settings.height = ((Number) height.getValue()).doubleValue();
            settings.speed = ((Number) speed.getValue()).doubleValue();
            settings.jump = ((Number) jumpHeight.getValue()).doubleValue();
            settings.sensitivity = ((Number) sensitivity.getValue()).doubleValue();
            settings.fieldOfView = fieldOfView.getValue();
            view.setFirstPersonFieldOfView(settings.fieldOfView);
            fieldOfViewLabel.setText(fieldOfViewText(settings.fieldOfView));
            settings.flyMode = flyMode.isSelected();
            settings.flySpeed = sliderToFlySpeed(flySpeed.getValue());
            flySpeedLabel.setText(flySpeedText(settings.flySpeed));
            saveSettings();
            map.repaint();
        };
        for (JSpinner spinner : new JSpinner[]{height, speed, jumpHeight, sensitivity}) {
            spinner.addChangeListener(event -> apply.run());
        }
        flyMode.addActionListener(event -> apply.run());
        fieldOfView.addChangeListener(event -> apply.run());
        flySpeed.addChangeListener(event -> apply.run());
        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static JSpinner spinner(double value, double min, double max, double step) {
        return new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, value)), min, max, step));
    }

    /** Logarithmic slider: precise at ordinary speeds while still reaching 500 km/s. */
    private static int flySpeedToSlider(double speed) {
        double value = Math.log(Math.max(MIN_FLY_SPEED, Math.min(MAX_FLY_SPEED, speed)) / MIN_FLY_SPEED)
                / Math.log(MAX_FLY_SPEED / MIN_FLY_SPEED);
        return (int) Math.round(value * 1000);
    }

    private static double sliderToFlySpeed(int slider) {
        return MIN_FLY_SPEED * Math.pow(MAX_FLY_SPEED / MIN_FLY_SPEED, slider / 1000.0);
    }

    private static String flySpeedText(double metresPerSecond) {
        if (metresPerSecond >= 1000) return String.format(Locale.ROOT, "Fly speed: %,.1f km/s", metresPerSecond / 1000);
        return String.format(Locale.ROOT, "Fly speed: %,.0f m/s", metresPerSecond);
    }

    private static String fieldOfViewText(double degrees) {
        return String.format(Locale.ROOT, "Field of view (vertical): %.0f°", degrees);
    }

    private void loadSettings() {
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) return;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(settingsFile)) {
            properties.load(in);
            settings.height = number(properties, "height", settings.height);
            settings.speed = number(properties, "speed", settings.speed);
            settings.jump = number(properties, "jump", settings.jump);
            settings.sensitivity = number(properties, "sensitivity", settings.sensitivity);
            settings.fieldOfView = Math.max(25.0, Math.min(110.0,
                    number(properties, "fieldOfView", settings.fieldOfView)));
            settings.flyMode = Boolean.parseBoolean(properties.getProperty("flyMode", "false"));
            settings.flySpeed = number(properties, "flySpeed", settings.flySpeed);
        } catch (IOException | RuntimeException ignored) {
            // Defaults stay.
        }
    }

    private void saveSettings() {
        if (settingsFile == null) return;
        Properties properties = new Properties();
        properties.setProperty("height", String.format(Locale.ROOT, "%.3f", settings.height));
        properties.setProperty("speed", String.format(Locale.ROOT, "%.3f", settings.speed));
        properties.setProperty("jump", String.format(Locale.ROOT, "%.3f", settings.jump));
        properties.setProperty("sensitivity", String.format(Locale.ROOT, "%.4f", settings.sensitivity));
        properties.setProperty("fieldOfView", String.format(Locale.ROOT, "%.1f", settings.fieldOfView));
        properties.setProperty("flyMode", Boolean.toString(settings.flyMode));
        properties.setProperty("flySpeed", String.format(Locale.ROOT, "%.3f", settings.flySpeed));
        try (OutputStream out = Files.newOutputStream(settingsFile)) {
            properties.store(out, "Districts first-person settings");
        } catch (IOException exception) {
            System.err.println("Could not save first-person settings: " + exception.getMessage());
        }
    }

    private static double number(Properties properties, String key, double fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Double.parseDouble(value);
    }
}
