package districts.towns;

import districts.model.World;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Decides what kind of settlement layout each station gets from the Medieval Fantasy City Generator,
 * and with which seed, so the layouts can be pregenerated once and reproduced exactly.
 *
 * <ul>
 *   <li>Every town is a Large Town.</li>
 *   <li>Every capital is a Large City.</li>
 *   <li>Each city in District 1 has an 80% chance of being a Large City.</li>
 *   <li>All other cities are exactly 20% Large Cities and 80% Small Cities.</li>
 * </ul>
 * Sizes follow the generator's own buttons: Large Town 10–14, Small City 15–23, Large City 24–39 patches.
 * The plan is stored on each station as {@code townClass}, {@code townSize} and {@code townSeed}.
 */
public final class TownPlan {
    public enum TownClass {
        LARGE_TOWN("largeTown", "Large Town", 10, 15),
        SMALL_CITY("smallCity", "Small City", 15, 24),
        LARGE_CITY("largeCity", "Large City", 24, 40);

        public final String key;
        public final String label;
        final int minSize;
        final int maxSize;

        TownClass(String key, String label, int minSize, int maxSize) {
            this.key = key;
            this.label = label;
            this.minSize = minSize;
            this.maxSize = maxSize;
        }

        public static TownClass fromKey(String key) {
            for (TownClass value : values()) if (value.key.equals(key)) return value;
            return null;
        }
    }

    public static final double LARGE_CITY_SHARE = 0.20;
    public static final double DISTRICT_ONE_LARGE_CITY_CHANCE = 0.80;

    private TownPlan() {}

    /** Assigns a town class, size and seed to every station that lacks one. Returns true if anything changed. */
    public static boolean assign(World world) {
        boolean missing = false;
        for (World.District district : world.districts) {
            for (World.Region region : district.regions) {
                for (World.Station station : region.stations) {
                    if (!station.metadata.containsKey("townClass")) missing = true;
                }
            }
        }
        if (!missing) return false;

        Random random = new Random(world.seed ^ 0x70E5_C17E_5EEDL);
        List<World.District> districts = new ArrayList<>(world.districts);
        districts.sort(Comparator.comparingInt(district -> district.id));
        List<World.Station> ordinaryCities = new ArrayList<>();
        for (World.District district : districts) {
            List<World.Region> regions = new ArrayList<>(district.regions);
            regions.sort(Comparator.comparingInt(region -> region.id));
            for (World.Region region : regions) {
                List<World.Station> stations = new ArrayList<>(region.stations);
                stations.sort(Comparator.comparingInt(station -> station.id));
                for (World.Station station : stations) {
                    TownClass townClass;
                    if (station.kind == World.StationKind.TOWN) townClass = TownClass.LARGE_TOWN;
                    else if (station.capital) townClass = TownClass.LARGE_CITY;
                    else if (district.id == 1) {
                        townClass = random.nextDouble() < DISTRICT_ONE_LARGE_CITY_CHANCE
                                ? TownClass.LARGE_CITY : TownClass.SMALL_CITY;
                    } else {
                        ordinaryCities.add(station);
                        continue;
                    }
                    apply(station, townClass, random);
                }
            }
        }
        // An exact 20% of the remaining cities are large, chosen at random.
        Collections.shuffle(ordinaryCities, random);
        int large = (int) Math.round(ordinaryCities.size() * LARGE_CITY_SHARE);
        for (int index = 0; index < ordinaryCities.size(); index++) {
            apply(ordinaryCities.get(index), index < large ? TownClass.LARGE_CITY : TownClass.SMALL_CITY, random);
        }
        world.metadata.put("townPlan", "large-towns; capitals large; district-1 cities 80% large; other cities 20% large");
        return true;
    }

    private static void apply(World.Station station, TownClass townClass, Random random) {
        int size = townClass.minSize + random.nextInt(townClass.maxSize - townClass.minSize);
        int seed = 1 + random.nextInt(Integer.MAX_VALUE - 2);
        station.metadata.put("townClass", townClass.key);
        station.metadata.put("townSize", Integer.toString(size));
        station.metadata.put("townSeed", Integer.toString(seed));
    }

    public static TownClass classOf(World.Station station) {
        return TownClass.fromKey(station.metadata.get("townClass"));
    }

    /**
     * Writes one generation task per line: {@code districtId regionId stationId size seed classKey}.
     * tools/generate-station-towns.js reads this file.
     */
    public static int writeTasks(World world, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        int count = 0;
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (World.District district : world.districts) {
                for (World.Region region : district.regions) {
                    for (World.Station station : region.stations) {
                        out.write(district.id + " " + region.id + " " + station.id + " "
                                + station.metadata.get("townSize") + " " + station.metadata.get("townSeed") + " "
                                + station.metadata.get("townClass") + "\n");
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
