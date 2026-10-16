package districts.towns;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads the pregenerated Medieval Fantasy City Generator layouts written by
 * tools/generate-station-towns.js (one file per district, see that script for the format).
 * Each district's index is read once; layouts are read and decoded on demand.
 * Coordinates are in the generator's units, centred on the station.
 */
public final class StationTowns {
    public static final String[] WARD_TYPES = {"Empty", "Ward", "CraftsmenWard", "MerchantWard", "Cathedral",
            "AdministrationWard", "Slum", "PatriciateWard", "Market", "MilitaryWard", "Park", "GateWard", "Farm",
            "Castle"};
    public static final int PARK = 10;

    public record Ward(int type, boolean walled, float[] shape, float[][] buildings) {}

    /** All polygons and point lists are flat [x0, y0, x1, y1, …] arrays. */
    public record Town(TownPlan.TownClass townClass, int size, float radius, Ward[] wards, float[] wall,
                       float[] towers, float[] gates, float[][] streets, float[][] roads) {}

    private record Index(float quantum, Map<Integer, long[]> records) {}

    private final Path directory;
    private final Map<Integer, Index> indexes = new HashMap<>();

    public StationTowns(Path directory) { this.directory = directory; }

    public boolean available() {
        return Files.isRegularFile(districtFile(1));
    }

    private Path districtFile(int district) {
        return directory.resolve(String.format("district-%02d.bin", district));
    }

    /** The decoded layout for a station, or null if it was never generated. */
    public Town load(int district, int region, int station) throws IOException {
        Index index = index(district);
        if (index == null) return null;
        long[] record = index.records.get(region << 16 | station);
        if (record == null) return null;
        byte[] compressed = new byte[(int) record[1]];
        try (RandomAccessFile file = new RandomAccessFile(districtFile(district).toFile(), "r")) {
            file.seek(record[0]);
            file.readFully(compressed);
        }
        return decode(inflate(compressed), index.quantum);
    }

    private synchronized Index index(int district) throws IOException {
        if (indexes.containsKey(district)) return indexes.get(district);
        Path file = districtFile(district);
        Index index = null;
        if (Files.isRegularFile(file)) {
            try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
                byte[] head = new byte[16];
                in.readFully(head);
                ByteBuffer header = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
                if (header.get(0) != 'D' || header.get(1) != 'T' || header.get(2) != 'W' || header.get(3) != 'N') {
                    throw new IOException(file + " is not a station town file");
                }
                int count = header.getInt(8);
                float quantum = header.getInt(12);
                byte[] table = new byte[count * 12];
                in.readFully(table);
                ByteBuffer entries = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
                Map<Integer, long[]> records = new HashMap<>(count * 2);
                for (int i = 0; i < count; i++) {
                    int region = entries.getShort() & 0xffff;
                    int station = entries.getShort() & 0xffff;
                    long offset = entries.getInt() & 0xffffffffL;
                    long length = entries.getInt() & 0xffffffffL;
                    records.put(region << 16 | station, new long[]{offset, length});
                }
                index = new Index(quantum, records);
            }
        }
        indexes.put(district, index);
        return index;
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] buffer = new byte[Math.max(4096, compressed.length * 3)];
            int length = 0;
            while (!inflater.finished()) {
                if (length == buffer.length) buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2);
                int read = inflater.inflate(buffer, length, buffer.length - length);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                length += read;
            }
            return java.util.Arrays.copyOf(buffer, length);
        } catch (DataFormatException exception) {
            throw new IOException("Corrupt station town record", exception);
        } finally {
            inflater.end();
        }
    }

    private static Town decode(byte[] bytes, float quantum) {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int classIndex = in.get() & 0xff;
        int size = in.get() & 0xff;
        in.get(); // seed fallback attempt, informational
        float radius = in.getFloat();
        Ward[] wards = new Ward[in.getShort() & 0xffff];
        for (int w = 0; w < wards.length; w++) {
            int type = in.get() & 0xff;
            boolean walled = in.get() != 0;
            float[] shape = poly(in, quantum);
            float[][] buildings = new float[in.getShort() & 0xffff][];
            for (int b = 0; b < buildings.length; b++) buildings[b] = poly(in, quantum);
            wards[w] = new Ward(type, walled, shape, buildings);
        }
        float[] wall = poly(in, quantum);
        float[] towers = poly(in, quantum);
        float[] gates = poly(in, quantum);
        float[][] streets = new float[in.getShort() & 0xffff][];
        for (int s = 0; s < streets.length; s++) streets[s] = poly(in, quantum);
        float[][] roads = new float[in.getShort() & 0xffff][];
        for (int r = 0; r < roads.length; r++) roads[r] = poly(in, quantum);
        TownPlan.TownClass[] classes = TownPlan.TownClass.values();
        return new Town(classes[Math.min(classIndex, classes.length - 1)], size, radius, wards, wall, towers, gates,
                streets, roads);
    }

    private static float[] poly(ByteBuffer in, float quantum) {
        int count = in.getShort() & 0xffff;
        float[] flat = new float[count * 2];
        int x = 0;
        int y = 0;
        for (int i = 0; i < count; i++) {
            x += in.getShort();
            y += in.getShort();
            flat[2 * i] = x / quantum;
            flat[2 * i + 1] = y / quantum;
        }
        return flat;
    }
}
