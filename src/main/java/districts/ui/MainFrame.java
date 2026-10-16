package districts.ui;

import districts.generator.WorldValidator;
import districts.model.World;
import districts.terrain.TerrainMap;
import districts.travel.TicketPlanner;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

@SuppressWarnings("serial")
public final class MainFrame extends JFrame {
    public MainFrame(World world, WorldValidator.Report report, Path savePath) {
        this(world, report, savePath, null);
    }

    public MainFrame(World world, WorldValidator.Report report, Path savePath, TerrainMap terrain) {
        super("Districts — Giant Settlement Atlas");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 650));
        setSize(1280, 820);
        setLocationByPlatform(true);

        JLabel status = new JLabel(" ");
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        status.setOpaque(true);
        status.setBackground(new Color(27, 31, 38));
        status.setForeground(new Color(218, 224, 230));

        TicketPlanner planner = new TicketPlanner(world);
        JTabbedPane sidebar = new JTabbedPane();
        MapPanel[] mapHolder = new MapPanel[1];
        Map<MapPanel.Layer, JCheckBoxMenuItem> filterItems = new EnumMap<>(MapPanel.Layer.class);
        TicketPanel ticketPanel = new TicketPanel(planner, ticket -> mapHolder[0].setTicket(ticket), () -> {
            mapHolder[0].setLayerVisible(MapPanel.Layer.TICKET_ROUTE, true);
            filterItems.get(MapPanel.Layer.TICKET_ROUTE).setSelected(true);
            mapHolder[0].fitTicketRoute();
        });
        StationDetailsPanel stationDetails = new StationDetailsPanel(world,
                selection -> {
                    ticketPanel.setFrom(planner.find(selection.station()));
                    sidebar.setSelectedComponent(ticketPanel);
                },
                selection -> {
                    ticketPanel.setTo(planner.find(selection.station()));
                    sidebar.setSelectedComponent(ticketPanel);
                });
        MapPanel map = new MapPanel(world, status::setText, selection -> {
            stationDetails.showStation(selection);
            sidebar.setSelectedComponent(stationDetails);
        }, savePath.resolveSibling("terrain-tiles"), terrain);
        mapHolder[0] = map;
        map.setTownDirectory(savePath.resolveSibling("towns"));
        map.setFirstPersonSettingsFile(savePath.resolveSibling("first-person.properties"));
        sidebar.addTab("Station", stationDetails);
        sidebar.addTab("Ticket", ticketPanel);
        sidebar.setPreferredSize(new Dimension(360, 100));
        sidebar.setMinimumSize(new Dimension(300, 100));
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(61, 72, 84)));

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JButton reset = new JButton("Reset view");
        reset.addActionListener(event -> map.resetView());
        JButton zoomIn = new JButton("+");
        zoomIn.setToolTipText("Zoom in");
        zoomIn.addActionListener(event -> map.zoomAtCenter(1.35));
        JButton zoomOut = new JButton("−");
        zoomOut.setToolTipText("Zoom out");
        zoomOut.addActionListener(event -> map.zoomAtCenter(1.0 / 1.35));
        JButton filters = new JButton("Filters ▾");
        JButton ticketButton = new JButton("Ticket");
        ticketButton.setToolTipText("Plan the fastest journey between two stations");
        ticketButton.addActionListener(event -> sidebar.setSelectedComponent(ticketPanel));
        JPopupMenu filterMenu = new JPopupMenu();
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.TERRAIN, "Terrain map");
        filterMenu.addSeparator();
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.DISTRICT_LABELS, "District labels");
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.RAILS, "Rails");
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.REGIONS, "Regions");
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.HIGHWAYS, "Highways");
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.CITY_ROADS, "City roads");
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.TOWN_ROADS, "Town roads");
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.FERRIES, "Ferries");
        filterMenu.addSeparator();
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.CITIES, "Cities");
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.TOWNS, "Towns");
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.PLACE_NAMES, "Place names");
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.STATION_TOWNS, "Town layouts");
        filterMenu.addSeparator();
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.TICKET_ROUTE, "Ticket route");
        addFilter(filterMenu, filterItems, map, MapPanel.Layer.ROUTE_FOCUS, "Focus on ticket route");
        filterItems.get(MapPanel.Layer.ROUTE_FOCUS).setSelected(false);
        map.setLayerVisible(MapPanel.Layer.ROUTE_FOCUS, false);
        filterMenu.addSeparator();
        JMenuItem showAll = new JMenuItem("Show all");
        showAll.addActionListener(event -> {
            map.showAllLayers();
            filterItems.forEach((layer, item) -> item.setSelected(layer != MapPanel.Layer.ROUTE_FOCUS));
        });
        filterMenu.add(showAll);
        filters.addActionListener(event -> filterMenu.show(filters, 0, filters.getHeight()));
        toolbar.add(reset);
        toolbar.addSeparator();
        toolbar.add(zoomOut);
        toolbar.add(zoomIn);
        toolbar.addSeparator();
        toolbar.add(filters);
        toolbar.add(ticketButton);
        toolbar.addSeparator();
        javax.swing.JToggleButton threeD = new javax.swing.JToggleButton("3D");
        threeD.setToolTipText("Show the terrain in 3D from the heightmap (right-drag to tilt and turn)");
        javax.swing.JSlider relief = new javax.swing.JSlider(1, 100, 25);
        relief.setToolTipText("Height exaggeration in 3D (×1 is true scale)");
        relief.setPreferredSize(new Dimension(110, relief.getPreferredSize().height));
        relief.setMaximumSize(relief.getPreferredSize());
        relief.setEnabled(false);
        JLabel reliefLabel = new JLabel(" Relief ×25 ");
        reliefLabel.setEnabled(false);
        relief.addChangeListener(event -> {
            reliefLabel.setText(" Relief ×" + relief.getValue() + " ");
            map.set3DExaggeration(relief.getValue());
        });
        threeD.addActionListener(event -> {
            map.set3D(threeD.isSelected());
            relief.setEnabled(threeD.isSelected());
            reliefLabel.setEnabled(threeD.isSelected());
        });
        toolbar.add(threeD);
        toolbar.add(reliefLabel);
        toolbar.add(relief);
        JButton walk = new JButton("First person");
        walk.setToolTipText("Walk the 3D map: WASD move, Shift run, Space jump, mouse look, Esc exit");
        walk.addActionListener(event -> {
            if (!threeD.isSelected()) threeD.doClick();
            map.enterFirstPerson();
        });
        JButton walkSettings = new JButton("⚙");
        walkSettings.setToolTipText("First person settings: height, walk speed, jump height, aim sensitivity");
        walkSettings.addActionListener(event -> map.showFirstPersonSettings());
        toolbar.add(walk);
        toolbar.add(walkSettings);
        toolbar.addSeparator();
        toolbar.add(new JLabel(" Drag to pan  •  Ctrl + wheel zooms  •  Double-click a region or station"));

        JPanel footer = new JPanel(new BorderLayout());
        footer.add(status, BorderLayout.CENTER);
        String ferries = world.metadata.getOrDefault("ferryCount", "0");
        JLabel totals = new JLabel("  " + report.regions() + " regions  •  " + report.stations()
                + " stations  •  " + report.railServices() + " rail services  •  "
                + report.highways() + " highways  •  " + report.roads() + " roads  •  "
                + ferries + " ferries  •  saved: " + savePath.toAbsolutePath() + "  ");
        totals.setOpaque(true);
        totals.setBackground(new Color(27, 31, 38));
        totals.setForeground(new Color(150, 163, 176));
        footer.add(totals, BorderLayout.EAST);

        add(toolbar, BorderLayout.NORTH);
        add(map, BorderLayout.CENTER);
        add(sidebar, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    private static void addFilter(JPopupMenu menu, Map<MapPanel.Layer, JCheckBoxMenuItem> items,
                                  MapPanel map, MapPanel.Layer layer, String label) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(label, true);
        item.addActionListener(event -> map.setLayerVisible(layer, item.isSelected()));
        items.put(layer, item);
        menu.add(item);
    }
}
