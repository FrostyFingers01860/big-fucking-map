package districts.ui;

import districts.travel.TicketPlanner;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text field with a live suggestion list over every station name in the world. */
@SuppressWarnings("serial")
final class StationSearchField extends JTextField {
    private static final int MAX_SUGGESTIONS = 12;

    private final List<TicketPlanner.StationRef> stations;
    private final String[] lowerNames;
    private final DefaultListModel<TicketPlanner.StationRef> suggestions = new DefaultListModel<>();
    private final JList<TicketPlanner.StationRef> list = new JList<>(suggestions);
    private final JPopupMenu popup = new JPopupMenu();
    private final Runnable onChange;
    private TicketPlanner.StationRef selected;
    private boolean updating;

    StationSearchField(List<TicketPlanner.StationRef> stations, Runnable onChange) {
        this.stations = stations;
        this.onChange = onChange;
        lowerNames = new String[stations.size()];
        for (int index = 0; index < stations.size(); index++) {
            lowerNames[index] = stations.get(index).station().name.toLowerCase(Locale.ROOT);
        }

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> owner, Object value, int index,
                                                                    boolean isSelected, boolean hasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(owner, value, index, isSelected, hasFocus);
                TicketPlanner.StationRef ref = (TicketPlanner.StationRef) value;
                String kind = ref.station().capital ? "capital" : ref.station().kind.name().toLowerCase(Locale.ROOT);
                label.setText("<html><b>" + escape(ref.station().name) + "</b> <font color='#8a9aa8'>"
                        + kind + " · D" + ref.district().id + " · " + escape(ref.region().name) + "</font></html>");
                label.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
                return label;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index >= 0) choose(suggestions.get(index));
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(61, 72, 84)));
        popup.setBorder(BorderFactory.createEmptyBorder());
        popup.setFocusable(false);
        popup.add(scroll);

        getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { textEdited(); }
            @Override public void removeUpdate(DocumentEvent event) { textEdited(); }
            @Override public void changedUpdate(DocumentEvent event) { textEdited(); }
        });
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                if (!popup.isVisible()) return;
                int index = list.getSelectedIndex();
                switch (event.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> select(Math.min(suggestions.size() - 1, index + 1));
                    case KeyEvent.VK_UP -> select(Math.max(0, index - 1));
                    case KeyEvent.VK_ENTER -> {
                        if (index >= 0) choose(suggestions.get(index));
                    }
                    case KeyEvent.VK_ESCAPE -> popup.setVisible(false);
                    default -> { return; }
                }
                event.consume();
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { popup.setVisible(false); }
        });
    }

    TicketPlanner.StationRef selectedStation() { return selected; }

    void setStation(TicketPlanner.StationRef ref) {
        updating = true;
        setText(ref == null ? "" : ref.station().name);
        updating = false;
        selected = ref;
        popup.setVisible(false);
        onChange.run();
    }

    private void choose(TicketPlanner.StationRef ref) {
        setStation(ref);
        setCaretPosition(getText().length());
    }

    private void select(int index) {
        if (index < 0) return;
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    private void textEdited() {
        if (updating) return;
        selected = null;
        onChange.run();
        SwingUtilities.invokeLater(this::refreshSuggestions);
    }

    private void refreshSuggestions() {
        String query = getText().trim().toLowerCase(Locale.ROOT);
        suggestions.clear();
        if (query.isEmpty() || !isShowing()) {
            popup.setVisible(false);
            return;
        }
        List<TicketPlanner.StationRef> prefix = new ArrayList<>();
        List<TicketPlanner.StationRef> contains = new ArrayList<>();
        for (int index = 0; index < lowerNames.length && prefix.size() < MAX_SUGGESTIONS; index++) {
            if (lowerNames[index].equals(query)) prefix.add(0, stations.get(index));
            else if (lowerNames[index].startsWith(query)) prefix.add(stations.get(index));
            else if (contains.size() < MAX_SUGGESTIONS && lowerNames[index].contains(query)) {
                contains.add(stations.get(index));
            }
        }
        // Station names are globally unique, so an exact match is selected without needing the list.
        if (!prefix.isEmpty() && prefix.get(0).station().name.equalsIgnoreCase(getText().trim())) {
            selected = prefix.get(0);
            onChange.run();
        }
        for (TicketPlanner.StationRef ref : prefix) if (suggestions.size() < MAX_SUGGESTIONS) suggestions.addElement(ref);
        for (TicketPlanner.StationRef ref : contains) if (suggestions.size() < MAX_SUGGESTIONS) suggestions.addElement(ref);
        if (suggestions.isEmpty()) {
            popup.setVisible(false);
            return;
        }
        list.setVisibleRowCount(Math.min(suggestions.size(), 8));
        list.setSelectedIndex(0);
        popup.setPreferredSize(new Dimension(Math.max(getWidth(), 300),
                Math.min(suggestions.size(), 8) * 24 + 6));
        popup.setVisible(false);
        popup.pack();
        popup.show(this, 0, getHeight());
        requestFocusInWindow();
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
