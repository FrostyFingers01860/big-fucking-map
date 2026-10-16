package districts.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializable world model. Every visible object owns an extensible metadata map. */
@SuppressWarnings("serial")
public final class World implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public static final int FORMAT_VERSION = 7;
    public static final int GRID_SIZE = 8;

    public int formatVersion;
    public final long seed;
    public final long generatedAtEpochMillis;
    public final int sourceNameCount;
    public final List<District> districts = new ArrayList<>();
    public List<Rail> rails = new ArrayList<>();
    public final Map<String, String> metadata = new LinkedHashMap<>();

    public World(long seed, int sourceNameCount) {
        this.formatVersion = FORMAT_VERSION;
        this.seed = seed;
        this.sourceNameCount = sourceNameCount;
        this.generatedAtEpochMillis = System.currentTimeMillis();
    }

    public District districtAt(int column, int row) {
        for (District district : districts) {
            if (district.column == column && district.row == row) return district;
        }
        return null;
    }

    public District districtById(int id) {
        for (District district : districts) {
            if (district.id == id) return district;
        }
        return null;
    }

    public Station capitalStation(int districtId) {
        District district = districtById(districtId);
        return district == null ? null : district.capitalStation();
    }

    public interface HasMetadata extends Serializable {
        Map<String, String> metadata();
    }

    public static final class District implements HasMetadata {
        @Serial private static final long serialVersionUID = 1L;

        public final int id;
        public final int column;
        public final int row;
        public final List<Region> regions = new ArrayList<>();
        public List<Junction> highwayJunctions = new ArrayList<>();
        public final List<Highway> highways = new ArrayList<>();
        public final Map<String, String> metadata = new LinkedHashMap<>();

        public District(int id, int column, int row) {
            this.id = id;
            this.column = column;
            this.row = row;
        }

        @Override public Map<String, String> metadata() { return metadata; }

        public Region regionById(int id) {
            for (Region region : regions) if (region.id == id) return region;
            return null;
        }

        public Junction highwayJunctionById(int id) {
            for (Junction junction : highwayJunctions) if (junction.id == id) return junction;
            return null;
        }

        public Region capitalRegion() {
            for (Region region : regions) if (region.capital) return region;
            return null;
        }

        public Station capitalStation() {
            Region capitalRegion = capitalRegion();
            if (capitalRegion == null) return null;
            for (Station station : capitalRegion.stations) if (station.capital) return station;
            return null;
        }
    }

    public static final class Region implements HasMetadata {
        @Serial private static final long serialVersionUID = 1L;

        public final int id;
        public final String name;
        public final boolean capital;
        public final double x;
        public final double y;
        public final double stationRadius;
        public final List<Station> stations = new ArrayList<>();
        public List<Junction> roadJunctions = new ArrayList<>();
        public final List<Road> roads = new ArrayList<>();
        /** Ferry ports ("port:N" route nodes) sit on the shore where a road meets water. */
        public List<Junction> ferryPorts = new ArrayList<>();
        /** Ferry crossings join two ports (or a shore-side station acting as its own port). */
        public List<Road> ferries = new ArrayList<>();
        public final Map<String, String> metadata = new LinkedHashMap<>();

        public Region(int id, String name, boolean capital, double x, double y, double stationRadius) {
            this.id = id;
            this.name = name;
            this.capital = capital;
            this.x = x;
            this.y = y;
            this.stationRadius = stationRadius;
        }

        @Override public Map<String, String> metadata() { return metadata; }

        public Station stationById(int id) {
            for (Station station : stations) if (station.id == id) return station;
            return null;
        }

        public Junction roadJunctionById(int id) {
            for (Junction junction : roadJunctions) if (junction.id == id) return junction;
            return null;
        }

        public Junction ferryPortById(int id) {
            if (ferryPorts == null) return null;
            for (Junction port : ferryPorts) if (port.id == id) return port;
            return null;
        }
    }

    /** Every station is classified as either a town or a city. */
    public enum StationKind { TOWN, CITY }

    public static final class Station implements HasMetadata {
        @Serial private static final long serialVersionUID = 1L;

        public final int id;
        public final String name;
        public final StationKind kind;
        public final boolean capital;
        public final double x;
        public final double y;
        public final Map<String, String> metadata = new LinkedHashMap<>();

        public Station(int id, String name, StationKind kind, boolean capital, double x, double y) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.capital = capital;
            this.x = x;
            this.y = y;
        }

        @Override public Map<String, String> metadata() { return metadata; }
    }

    public static final class Highway implements HasMetadata {
        @Serial private static final long serialVersionUID = 1L;

        public final int id;
        public final String fromNode;
        public final String toNode;
        public final Map<String, String> metadata = new LinkedHashMap<>();

        public Highway(int id, String fromNode, String toNode) {
            this.id = id;
            this.fromNode = fromNode;
            this.toNode = toNode;
        }

        @Override public Map<String, String> metadata() { return metadata; }
    }

    public static final class Road implements HasMetadata {
        @Serial private static final long serialVersionUID = 1L;

        public final int id;
        public final String fromNode;
        public final String toNode;
        public final Map<String, String> metadata = new LinkedHashMap<>();

        public Road(int id, String fromNode, String toNode) {
            this.id = id;
            this.fromNode = fromNode;
            this.toNode = toNode;
        }

        @Override public Map<String, String> metadata() { return metadata; }
    }

    /** A national rail segment directly joins the capital cities of two districts. */
    public static final class Rail implements HasMetadata {
        @Serial private static final long serialVersionUID = 1L;

        public final int id;
        public final int fromDistrictId;
        public final int toDistrictId;
        public final Map<String, String> metadata = new LinkedHashMap<>();

        public Rail(int id, int fromDistrictId, int toDistrictId) {
            this.id = id;
            this.fromDistrictId = fromDistrictId;
            this.toDistrictId = toDistrictId;
        }

        @Override public Map<String, String> metadata() { return metadata; }
    }

    public static final class Junction implements HasMetadata {
        @Serial private static final long serialVersionUID = 1L;

        public final int id;
        public final double x;
        public final double y;
        public final Map<String, String> metadata = new LinkedHashMap<>();

        public Junction(int id, double x, double y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        @Override public Map<String, String> metadata() { return metadata; }
    }
}
