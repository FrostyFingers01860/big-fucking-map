package districts;

import districts.generator.WorldGenerator;
import districts.generator.WorldValidator;
import districts.model.World;
import districts.persistence.WorldStore;
import districts.terrain.TerrainMap;
import districts.towns.StationTowns;
import districts.towns.TownPlan;
import districts.generator.TownSpacing;
import districts.ui.MainFrame;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

public final class DistrictsApp {
    private DistrictsApp() {}

    public static void main(String[] args) {
        try {
            Arguments options = Arguments.parse(args);
            List<String> sourceNames = WorldGenerator.loadBundledTownNames();
            long seed = options.seed != null ? options.seed : new SecureRandom().nextLong();
            TerrainMap terrain = WorldStore.loadTerrainBeside(options.dataDirectory);
            WorldStore.LoadResult result = WorldStore.loadOrGenerate(options.dataDirectory, terrain,
                    loadedTerrain -> WorldGenerator.generate(sourceNames, seed, loadedTerrain));
            if (TownPlan.assign(result.world())) WorldStore.save(result.path(), result.world());
            applyTownSpacing(result, terrain, options.dataDirectory);
            if (terrain != null && !result.world().metadata.containsKey("waterRule")) {
                TownSpacing.Repair repair = TownSpacing.repairWater(result.world(), terrain);
                WorldStore.save(result.path(), result.world());
                System.out.println("Water rule updated: moved " + repair.stationsMoved() + " stations off water, rebuilt roads in "
                        + repair.regionsRebuilt() + " regions");
            }
            if (terrain != null && !TownSpacing.SITE_RULE.equals(result.world().metadata.get("siteRule"))) {
                TownSpacing.Repair repair = TownSpacing.repairSites(result.world(), terrain,
                        districts.terrain.HeightField.build(terrain));
                WorldStore.save(result.path(), result.world());
                System.out.println("Site rule applied: moved " + repair.stationsMoved()
                        + " stations off river banks and steep ground, rebuilt roads in " + repair.regionsRebuilt() + " regions");
            }
            WorldValidator.Report report = WorldValidator.validate(result.world(), terrain);
            if (options.planTowns) {
                Path tasks = options.dataDirectory.resolve("towns").resolve("tasks.txt");
                int count = TownPlan.writeTasks(result.world(), tasks);
                System.out.println("Wrote " + count + " town generation tasks to " + tasks.toAbsolutePath());
                return;
            }

            System.out.println((result.generated() ? "Generated and saved " : "Loaded ") + result.path().toAbsolutePath());
            System.out.println("Seed: " + result.world().seed);
            System.out.println(report);
            if (options.generateOnly || options.verifyOnly) return;

            setNativeLookAndFeel();
            SwingUtilities.invokeLater(() -> new MainFrame(result.world(), report, result.path(), terrain).setVisible(true));
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            if (!Arrays.asList(args).contains("--generate-only") && !Arrays.asList(args).contains("--verify")) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                        exception.getMessage(), "Districts could not start", JOptionPane.ERROR_MESSAGE));
            }
            System.exit(1);
        }
    }

    /**
     * Once the station town layouts exist, moves stations that are too close for their layouts
     * (a one-time step; the previous world is kept as a backup).
     */
    private static void applyTownSpacing(WorldStore.LoadResult result, TerrainMap terrain, Path dataDirectory)
            throws java.io.IOException {
        World world = result.world();
        StationTowns towns = new StationTowns(dataDirectory.resolve("towns"));
        if (world.metadata.containsKey("townSpacing")) {
            if (TownSpacing.rescale(world)) {
                WorldStore.save(result.path(), world);
                System.out.println("Town layouts rescaled to " + world.metadata.get("townScale") + " world units per generator unit");
            }
            return;
        }
        if (terrain == null || !towns.available()) return;
        Path backup = result.path().resolveSibling("world-v7-before-town-spacing.bin");
        if (!java.nio.file.Files.exists(backup)) java.nio.file.Files.copy(result.path(), backup);
        TownSpacing.Result spacing = TownSpacing.enforce(world, terrain, towns);
        WorldStore.save(result.path(), world);
        System.out.printf("Town spacing: moved %d stations, rebuilt roads in %d regions (largest move %.4f world units)%n",
                spacing.moved(), spacing.regionsRebuilt(), spacing.largestMove());
    }

    private static void setNativeLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's cross-platform appearance remains available.
        }
    }

    private static final class Arguments {
        private Path dataDirectory = Path.of("data");
        private Long seed;
        private boolean generateOnly;
        private boolean verifyOnly;
        private boolean planTowns;

        private static Arguments parse(String[] args) throws IOException {
            Arguments result = new Arguments();
            for (String arg : args) {
                if (arg.equals("--generate-only")) result.generateOnly = true;
                else if (arg.equals("--verify")) result.verifyOnly = true;
                else if (arg.equals("--plan-towns")) result.planTowns = true;
                else if (arg.startsWith("--seed=")) result.seed = Long.parseLong(arg.substring("--seed=".length()));
                else if (arg.startsWith("--data-dir=")) result.dataDirectory = Path.of(arg.substring("--data-dir=".length()));
                else if (arg.equals("--help")) {
                    System.out.println("Districts options: --generate-only --verify --plan-towns --seed=<long> --data-dir=<path>");
                    System.exit(0);
                } else throw new IOException("Unknown option: " + arg);
            }
            return result;
        }
    }
}
