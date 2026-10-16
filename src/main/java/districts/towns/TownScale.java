package districts.towns;

/** One fixed scale for every station's town layout, so buildings are the same size everywhere. */
public final class TownScale {
    /** World units per generator unit: one generator unit is about 3.8 m (one district ≈ 1,538 km). */
    public static final double WORLD_PER_UNIT = 2.5e-6;
    /** Two stations must be at least this many times the sum of their layout radii apart. */
    public static final double SPACING_FACTOR = 1.15;

    private TownScale() {}

    /** The minimum allowed distance (world units) between two stations with these layout radii. */
    public static double minimumDistance(double radiusA, double radiusB) {
        return (radiusA + radiusB) * WORLD_PER_UNIT * SPACING_FACTOR;
    }
}
