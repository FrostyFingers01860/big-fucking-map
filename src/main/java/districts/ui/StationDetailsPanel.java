package districts.ui;

import districts.model.World;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@SuppressWarnings("serial")
final class StationDetailsPanel extends JPanel {
    private static final Color BACKGROUND = new Color(22, 28, 37);
    private final World world;
    private final JLabel title = new JLabel("Station details");
    private final JLabel subtitle = new JLabel("Double-click a city or town");
    private final JTextArea details = new JTextArea();
    private final JButton setStart = new JButton("Set as start");
    private final JButton setDestination = new JButton("Set as destination");
    private MapPanel.StationSelection current;

    StationDetailsPanel(World world, Consumer<MapPanel.StationSelection> onStart,
                        Consumer<MapPanel.StationSelection> onDestination) {
        super(new BorderLayout(0, 10));
        this.world = world;
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel heading = new JPanel(new BorderLayout(0, 4));
        heading.setOpaque(false);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        title.setForeground(new Color(239, 244, 247));
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
        subtitle.setForeground(new Color(147, 166, 181));
        heading.add(title, BorderLayout.NORTH);
        heading.add(subtitle, BorderLayout.SOUTH);

        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        details.setForeground(new Color(210, 222, 229));
        details.setBackground(BACKGROUND);
        details.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 4));
        details.setText("Zoom into a region, then double-click a station to inspect it.");
        JScrollPane scroll = new JScrollPane(details);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BACKGROUND);

        setStart.setEnabled(false);
        setDestination.setEnabled(false);
        setStart.addActionListener(event -> onStart.accept(current));
        setDestination.addActionListener(event -> onDestination.accept(current));
        JPanel ticketActions = new JPanel(new GridLayout(1, 2, 8, 0));
        ticketActions.setOpaque(false);
        ticketActions.add(setStart);
        ticketActions.add(setDestination);

        add(heading, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(ticketActions, BorderLayout.SOUTH);
    }

    void showStation(MapPanel.StationSelection selection) {
        World.District district = selection.district();
        World.Region region = selection.region();
        World.Station station = selection.station();
        current = selection;
        setStart.setEnabled(true);
        setDestination.setEnabled(true);
        title.setText(station.name);
        subtitle.setText(station.capital ? "Capital city" : station.kind == World.StationKind.CITY ? "City" : "Town");

        String stationKey = "station:" + station.id;
        int roadConnections = 0;
        int stressConnections = 0;
        for (World.Road road : region.roads) {
            if (!road.fromNode.equals(stationKey) && !road.toNode.equals(stationKey)) continue;
            roadConnections++;
            if ("location-stress-relief".equals(road.metadata.get("kind"))) stressConnections++;
        }
        Set<String> railServices = new HashSet<>();
        if (station.capital) {
            for (World.Rail rail : world.rails) {
                if (rail.fromDistrictId == district.id || rail.toDistrictId == district.id) {
                    railServices.add(rail.metadata.get("serviceId"));
                }
            }
        }

        StringBuilder text = new StringBuilder();
        text.append("District       ").append(district.id).append('\n');
        text.append("Region         ").append(region.name);
        if (region.capital) text.append(" (capital region)");
        text.append('\n');
        text.append("Station ID     ").append(station.id).append('\n');
        text.append("Coordinates    ").append(String.format(Locale.ROOT, "%.5f, %.5f", station.x, station.y)).append('\n');
        text.append("Road links     ").append(roadConnections).append('\n');
        text.append("Stress links   ").append(stressConnections).append('\n');
        if (station.capital) text.append("Rail services  ").append(railServices.size()).append('\n');

        text.append("\nTRANSPORT\n");
        appendValue(text, "Road priority", station.metadata.get("roadRoutePriority"));
        appendValue(text, "Priority role", station.metadata.get("roadPriorityRole"));
        appendValue(text, "Max road stress", station.metadata.get("maximumRoadLocationStress"));
        appendValue(text, "Stress relief links", station.metadata.get("roadStressReliefConnections"));
        if (station.capital) {
            appendValue(text, "District rail value", district.metadata.get("railImportance"));
            appendValue(text, "Direct rail services", district.metadata.get("railServiceEndpointDegree"));
            appendValue(text, "All stopping services", district.metadata.get("railStoppingServiceCount"));
            appendValue(text, "Intermediate stops", district.metadata.get("railIntermediateStopCount"));
            appendValue(text, "Mean rail distance", district.metadata.get("railAverageTravelDistance"));
        }

        if (station.metadata.containsKey("townClass")) {
            districts.towns.TownPlan.TownClass townClass = districts.towns.TownPlan.classOf(station);
            text.append("\nTOWN LAYOUT\n");
            appendValue(text, "Type", townClass == null ? station.metadata.get("townClass") : townClass.label);
            appendValue(text, "Generator size", station.metadata.get("townSize"));
            appendValue(text, "Generator seed", station.metadata.get("townSeed"));
        }
        if (station.metadata.containsKey("terrainBiome")) {
            text.append("\nTERRAIN\n");
            appendValue(text, "Biome", station.metadata.get("terrainBiome"));
            appendValue(text, "Height", station.metadata.get("terrainHeight"));
            appendValue(text, "River cell", station.metadata.get("terrainRiver"));
            appendValue(text, "Water distance", station.metadata.get("terrainDistanceToWater"));
            text.append("Shore side = ").append("true".equals(station.metadata.get("shoreSide"))
                    ? "yes (moved off the water)" : "no").append('\n');
        }

        text.append("\nMETADATA\n");
        station.metadata.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> text.append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n'));
        details.setText(text.toString());
        details.setCaretPosition(0);
    }

    private static void appendValue(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) text.append(label).append(" = ").append(value).append('\n');
    }
}
