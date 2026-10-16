package districts.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Allocates globally unique names while keeping directional counterparts together. */
final class NameAllocator {
    record GeneratedName(String value, Map<String, String> metadata) {}

    private static final String[] ADJECTIVES = {
            "Amber", "Ancient", "Ashen", "Azure", "Bright", "Bronze", "Cedar", "Clear",
            "Copper", "Crimson", "Crystal", "Deep", "Emerald", "Fair", "Frost", "Golden",
            "Grand", "Green", "Grey", "Hidden", "High", "Iron", "Ivory", "Jade", "Little",
            "Lone", "Long", "Lower", "Maple", "Misty", "Old", "Pale", "Pine", "Quiet",
            "Red", "Royal", "Ruby", "Silver", "Stone", "Sunny", "Swift", "Tall", "Upper",
            "White", "Wild", "Willow", "Windy", "Young"
    };
    private static final String[] DERIVED_SUFFIXES = {
            "ford", "ton", "stead", "wick", "haven", "dale", "field", "bridge",
            "bury", "port", "wood", "mere", "croft", "mont", "borough", "chester",
            "mouth", "view", "ridge", "brook", "glen", "moor", "crest", "well",
            "fall", "spring", "gate", "rock", "holm", "shaw", "marsh", "worth"
    };
    private static final String[] DESCRIPTOR_SUFFIXES = {
            "Heights", "Crossing", "Junction", "Harbor", "Ridge", "Falls", "Springs",
            "Grove", "Hollow", "Point", "Landing", "Meadows", "Terrace", "Reach",
            "Bluff", "Gardens", "Shores", "Plains", "Center", "Valley", "Bay", "Hill"
    };
    private static final String[] REMOVABLE_ENDINGS = {
            "borough", "chester", "bridge", "mouth", "spring", "heights", "ville",
            "field", "haven", "stead", "croft", "ridge", "wood", "land", "ford",
            "town", "bury", "wick", "dale", "port", "mere", "view", "brook", "glen",
            "moor", "crest", "well", "fall", "gate", "rock", "holm", "shaw", "marsh",
            "worth", "pine", "fair", "cove", "lake", "mont", "ton", "ham", "bay"
    };

    private final List<String> bases;
    private final Random random;
    private final Set<String> used = new HashSet<>();
    private int baseCursor;
    private int newCursor;
    private int cardinalCursor;
    private long adjectiveCursor;
    private long derivedCursor;
    private long descriptorCursor;
    private int lakeCount;
    private long pairSequence;

    NameAllocator(List<String> sourceNames, Random random) {
        this.random = random;
        Set<String> seen = new HashSet<>();
        this.bases = new ArrayList<>();
        for (String raw : sourceNames) {
            String clean = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
            if (!clean.isEmpty() && seen.add(clean.toLowerCase(Locale.ROOT))) bases.add(clean);
        }
        if (bases.isEmpty()) throw new IllegalArgumentException("The town-name source is empty.");
        Collections.shuffle(bases, random);
    }

    int sourceNameCount() { return bases.size(); }

    List<GeneratedName> allocateBatch(int count, int districtId, int regionId) {
        List<GeneratedName> result = new ArrayList<>(count);
        int newNames = 0;
        int cardinalPairs = 0;

        // Spread the scarce untouched source names across regions instead of exhausting them
        // in the first few districts.
        GeneratedName base = nextBaseName();
        if (base != null) result.add(base);

        if (result.size() < count && random.nextDouble() < 0.45) {
            GeneratedName newName = nextNewName();
            if (newName != null) {
                result.add(newName);
                newNames++;
            }
        }
        if (count - result.size() >= 2 && random.nextDouble() < 0.28) {
            List<GeneratedName> pair = nextCardinalPair(districtId, regionId);
            if (pair != null) {
                result.addAll(pair);
                cardinalPairs++;
            }
        }

        // Every region receives multiple naming styles before the random mix fills it out.
        int derivedNames = 0;
        int adjectiveNames = 0;
        int descriptorNames = 0;
        if (result.size() < count) {
            result.add(nextDerivedName());
            derivedNames++;
        }
        if (result.size() < count) {
            result.add(nextAdjectiveName());
            adjectiveNames++;
        }
        if (result.size() < count) {
            result.add(nextDescriptorName());
            descriptorNames++;
        }

        int maximumCoreStyle = (int) Math.ceil(count * 0.48);

        while (result.size() < count) {
            int remaining = count - result.size();
            double roll = random.nextDouble();
            if (roll >= 0.90 && roll < 0.95 && newNames < 2) {
                GeneratedName newName = nextNewName();
                if (newName != null) {
                    result.add(newName);
                    newNames++;
                    continue;
                }
            }
            if (roll >= 0.95 && remaining >= 2 && cardinalPairs < 2) {
                List<GeneratedName> pair = nextCardinalPair(districtId, regionId);
                if (pair != null) {
                    result.addAll(pair);
                    cardinalPairs++;
                    continue;
                }
            }

            int style = roll < 0.34 ? 0 : roll < 0.64 ? 1 : roll < 0.90 ? 2 : random.nextInt(3);
            int selectedCount = style == 0 ? derivedNames : style == 1 ? adjectiveNames : descriptorNames;
            if (selectedCount >= maximumCoreStyle) {
                if (derivedNames <= adjectiveNames && derivedNames <= descriptorNames) style = 0;
                else if (adjectiveNames <= descriptorNames) style = 1;
                else style = 2;
            }
            if (style == 0) {
                result.add(nextDerivedName());
                derivedNames++;
            } else if (style == 1) {
                result.add(nextAdjectiveName());
                adjectiveNames++;
            } else {
                result.add(nextDescriptorName());
                descriptorNames++;
            }
        }
        Collections.shuffle(result, random);
        return result;
    }

    private GeneratedName nextBaseName() {
        while (baseCursor < bases.size()) {
            String candidate = bases.get(baseCursor++);
            GeneratedName name = acceptSingleton(candidate, "base", false);
            if (name != null) return name;
        }
        return null;
    }

    private GeneratedName nextNewName() {
        while (newCursor < bases.size()) {
            String candidate = "New " + bases.get(newCursor++);
            GeneratedName name = acceptSingleton(candidate, "new", false);
            if (name != null) {
                name.metadata().put("literalPrefix", "New");
                return name;
            }
        }
        return null;
    }

    private List<GeneratedName> nextCardinalPair(int districtId, int regionId) {
        while (cardinalCursor < bases.size() * 2) {
            int cursor = cardinalCursor++;
            String base = bases.get(cursor % bases.size());
            boolean northSouth = cursor < bases.size();
            String first = northSouth ? "North" : "East";
            String second = northSouth ? "South" : "West";
            boolean prefix = random.nextBoolean();
            String a = prefix ? first + " " + base : base + " " + first;
            String b = prefix ? second + " " + base : base + " " + second;
            if (isUsed(a) || isUsed(b)) continue;

            String pairId = "D" + districtId + "-R" + regionId + "-P" + (++pairSequence);
            markUsed(a);
            markUsed(b);
            Map<String, String> metaA = metadata("cardinal", false);
            Map<String, String> metaB = metadata("cardinal", false);
            metaA.put("cardinalPairId", pairId);
            metaB.put("cardinalPairId", pairId);
            metaA.put("counterpart", b);
            metaB.put("counterpart", a);
            return List.of(new GeneratedName(a, metaA), new GeneratedName(b, metaB));
        }
        return null;
    }

    private GeneratedName nextAdjectiveName() {
        for (;;) {
            long index = adjectiveCursor++;
            int baseIndex = (int) (index % bases.size());
            long adjectiveRound = index / bases.size();
            String prefix;
            if (adjectiveRound < ADJECTIVES.length) {
                // Rotate rapidly through adjectives so one region never becomes a block of
                // "Silver ...", then a block of "Stone ..." names.
                int adjectiveIndex = Math.floorMod(baseIndex * 17 + (int) adjectiveRound * 11,
                        ADJECTIVES.length);
                prefix = ADJECTIVES[adjectiveIndex];
            } else {
                long compound = adjectiveRound - ADJECTIVES.length;
                int firstIndex = Math.floorMod(baseIndex * 17 + (int) compound * 11, ADJECTIVES.length);
                int secondIndex = Math.floorMod(baseIndex * 29 + (int) compound * 7 + 1, ADJECTIVES.length);
                String first = ADJECTIVES[firstIndex];
                String second = ADJECTIVES[secondIndex];
                prefix = first.equals(second) ? first + " Far" : first + " " + second;
            }
            GeneratedName accepted = acceptSingleton(prefix + " " + bases.get(baseIndex), "adjective", true);
            if (accepted != null) {
                accepted.metadata().put("adjectivePrefix", prefix);
                return accepted;
            }
        }
    }

    private GeneratedName nextDerivedName() {
        for (;;) {
            long index = derivedCursor++;
            int baseIndex = (int) (index % bases.size());
            long round = index / bases.size();
            int suffixIndex = Math.floorMod(baseIndex * 13 + (int) round * 7,
                    DERIVED_SUFFIXES.length);
            String suffix = DERIVED_SUFFIXES[suffixIndex];
            String candidate = stemFor(bases.get(baseIndex)) + suffix;
            GeneratedName accepted = acceptSingleton(candidate, "derived", true);
            if (accepted != null) {
                accepted.metadata().put("derivedSuffix", suffix);
                return accepted;
            }
        }
    }

    private GeneratedName nextDescriptorName() {
        for (;;) {
            long index = descriptorCursor++;
            int baseIndex = (int) (index % bases.size());
            long round = index / bases.size();
            int suffixIndex = Math.floorMod(baseIndex * 5 + (int) round * 9,
                    DESCRIPTOR_SUFFIXES.length);
            String suffix = DESCRIPTOR_SUFFIXES[suffixIndex];
            GeneratedName accepted = acceptSingleton(bases.get(baseIndex) + " " + suffix,
                    "descriptor-suffix", false);
            if (accepted != null) {
                accepted.metadata().put("descriptorSuffix", suffix);
                return accepted;
            }
        }
    }

    private String stemFor(String base) {
        String compact = base.replaceAll("[^\\p{L}]", "");
        if (compact.isEmpty()) compact = "Town";
        String lower = compact.toLowerCase(Locale.ROOT);
        for (String ending : REMOVABLE_ENDINGS) {
            if (lower.endsWith(ending) && compact.length() - ending.length() >= 3) {
                compact = compact.substring(0, compact.length() - ending.length());
                break;
            }
        }
        return Character.toUpperCase(compact.charAt(0)) + compact.substring(1);
    }

    private GeneratedName acceptSingleton(String original, String strategy, boolean allowLake) {
        String candidate = original;
        boolean lake = false;
        int eventualCount = used.size() + 1;
        if (allowLake && random.nextDouble() < 0.035
                && lakeCount + 1 <= Math.floor(eventualCount * 0.05)) {
            String withLake = original + " Lake";
            if (!isUsed(withLake)) {
                candidate = withLake;
                lake = true;
            }
        }
        if (isUsed(candidate)) return null;
        markUsed(candidate);
        if (lake) lakeCount++;
        return new GeneratedName(candidate, metadata(strategy, lake));
    }

    private Map<String, String> metadata(String strategy, boolean lake) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("nameStrategy", strategy);
        metadata.put("lakeSuffix", Boolean.toString(lake));
        return metadata;
    }

    private boolean isUsed(String value) { return used.contains(value.toLowerCase(Locale.ROOT)); }
    private void markUsed(String value) { used.add(value.toLowerCase(Locale.ROOT)); }
}
