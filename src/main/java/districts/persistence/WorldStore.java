package districts.persistence;

import districts.generator.WorldGenerator;
import districts.model.World;
import districts.terrain.TerrainMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;

public final class WorldStore {
    public static final String WORLD_FILE_NAME = "world.bin";

    private WorldStore() {}

    public record LoadResult(World world, boolean generated, Path path) {}

    public static LoadResult loadOrGenerate(Path dataDirectory, TerrainMap terrain,
                                            Function<TerrainMap, World> generator)
            throws IOException, ClassNotFoundException {
        Files.createDirectories(dataDirectory);
        Path path = dataDirectory.resolve(WORLD_FILE_NAME);
        if (Files.isRegularFile(path)) return new LoadResult(load(path, terrain), false, path);

        World world = generator.apply(terrain);
        save(path, world);
        return new LoadResult(world, true, path);
    }

    /** The terrain file that sits next to a world file, or null when there is none. */
    public static TerrainMap loadTerrainBeside(Path worldOrDataPath) throws IOException {
        Path directory = Files.isRegularFile(worldOrDataPath)
                ? worldOrDataPath.toAbsolutePath().getParent() : worldOrDataPath;
        Path terrainPath = directory.resolve(TerrainMap.FILE_NAME);
        return Files.isRegularFile(terrainPath) ? TerrainMap.load(terrainPath) : null;
    }

    public static World load(Path path) throws IOException, ClassNotFoundException {
        return load(path, loadTerrainBeside(path));
    }

    public static World load(Path path, TerrainMap terrain) throws IOException, ClassNotFoundException {
        World world;
        try (ObjectInputStream input = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            Object value = input.readObject();
            if (!(value instanceof World savedWorld)) throw new IOException("Saved file is not a Districts world.");
            world = savedWorld;
        }
        boolean changed = false;
        if (world.formatVersion >= 3 && world.formatVersion <= 5) {
            WorldGenerator.upgradeEfficientNetworks(world);
            changed = true;
        }
        if (world.formatVersion == 6) {
            if (terrain == null) {
                throw new IOException("This world must be re-laid out on the terrain, but "
                        + TerrainMap.FILE_NAME + " is missing next to " + path.toAbsolutePath()
                        + ". Run tools/extract-terrain-cells.py first.");
            }
            Path backup = path.resolveSibling("world-v6-before-terrain.bin");
            if (!Files.exists(backup)) Files.copy(path, backup);
            WorldGenerator.relayoutOnTerrain(world, terrain);
            changed = true;
        }
        if (world.formatVersion != World.FORMAT_VERSION) {
            throw new IOException("Unsupported world format " + world.formatVersion
                    + "; this build expects " + World.FORMAT_VERSION + ".");
        }
        if (changed) save(path, world);
        return world;
    }

    public static void save(Path path, World world) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (ObjectOutputStream output = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            output.writeObject(world);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
