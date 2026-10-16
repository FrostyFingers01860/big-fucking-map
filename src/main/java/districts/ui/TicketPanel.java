package districts.ui;

import districts.travel.TicketPlanner;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Sidebar tab: choose two stations and print a ticket for the fastest bus / subway / train journey. */
@SuppressWarnings("serial")
final class TicketPanel extends JPanel {
    private static final Color BACKGROUND = new Color(22, 28, 37);
    private static final Color MUTED = new Color(147, 166, 181);

    private final TicketPlanner planner;
    private final Consumer<TicketPlanner.Ticket> ticketListener;
    private final Runnable showOnMap;
    private final StationSearchField fromField;
    private final StationSearchField toField;
    private final JButton issue = new JButton("Get ticket");
    private final JButton showRoute = new JButton("Show route on map");
    private final JButton clear = new JButton("Clear");
    private final JButton expand = new JButton("⤢  Expand ticket");
    private final JEditorPane ticketView = new JEditorPane("text/html", "");
    private TicketPlanner.Ticket ticket;

    TicketPanel(TicketPlanner planner, Consumer<TicketPlanner.Ticket> ticketListener, Runnable showOnMap) {
        super(new BorderLayout(0, 10));
        this.planner = planner;
        this.ticketListener = ticketListener;
        this.showOnMap = showOnMap;
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        fromField = new StationSearchField(planner.stations(), this::updateButtons);
        toField = new StationSearchField(planner.stations(), this::updateButtons);
        fromField.addActionListener(event -> issueIfReady());
        toField.addActionListener(event -> issueIfReady());

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(0, 0, 3, 0);
        JLabel title = new JLabel("Travel ticket");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        title.setForeground(new Color(239, 244, 247));
        form.add(title, c);
        c.insets = new Insets(0, 0, 10, 0);
        form.add(muted("Fastest journey by bus, subway and train"), c);
        c.insets = new Insets(0, 0, 3, 0);
        form.add(muted("From station"), c);
        c.insets = new Insets(0, 0, 8, 0);
        form.add(fromField, c);
        c.insets = new Insets(0, 0, 3, 0);
        form.add(muted("To station"), c);
        c.insets = new Insets(0, 0, 8, 0);
        form.add(toField, c);

        JButton swap = new JButton("⇅ Swap");
        swap.addActionListener(event -> {
            TicketPlanner.StationRef from = fromField.selectedStation();
            fromField.setStation(toField.selectedStation());
            toField.setStation(from);
        });
        issue.addActionListener(event -> issueIfReady());
        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 0));
        actions.setOpaque(false);
        actions.add(swap);
        actions.add(issue);
        c.insets = new Insets(0, 0, 0, 0);
        form.add(actions, c);

        ticketView.setEditable(false);
        ticketView.setBackground(BACKGROUND);
        ticketView.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        ticketView.setText(placeholder());
        JScrollPane scroll = new JScrollPane(ticketView);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        showRoute.addActionListener(event -> showOnMap.run());
        clear.addActionListener(event -> setTicket(null));
        expand.setToolTipText("Open the whole ticket full screen (Esc closes it)");
        expand.addActionListener(event -> showFullScreen());
        JPanel routeActions = new JPanel(new GridLayout(1, 2, 8, 0));
        routeActions.setOpaque(false);
        routeActions.add(showRoute);
        routeActions.add(clear);
        JPanel footer = new JPanel(new GridLayout(2, 1, 0, 6));
        footer.setOpaque(false);
        footer.add(expand);
        footer.add(routeActions);

        add(form, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        updateButtons();
    }

    void setFrom(TicketPlanner.StationRef ref) {
        fromField.setStation(ref);
        issueIfReady();
    }

    void setTo(TicketPlanner.StationRef ref) {
        toField.setStation(ref);
        issueIfReady();
    }

    private void issueIfReady() {
        TicketPlanner.StationRef from = fromField.selectedStation();
        TicketPlanner.StationRef to = toField.selectedStation();
        if (from == null || to == null) return;
        TicketPlanner.Ticket planned = planner.plan(from, to);
        if (planned == null) {
            setTicket(null);
            ticketView.setText(message("No connection", "These two stations are not linked by any bus, subway or rail."));
            return;
        }
        setTicket(planned);
        if (!planned.legs().isEmpty()) showOnMap.run();
    }

    private void setTicket(TicketPlanner.Ticket value) {
        ticket = value;
        ticketView.setText(value == null ? placeholder() : TicketHtml.render(value));
        ticketView.setCaretPosition(0);
        ticketListener.accept(value);
        updateButtons();
    }

    private void updateButtons() {
        issue.setEnabled(fromField.selectedStation() != null && toField.selectedStation() != null);
        boolean hasRoute = ticket != null && !ticket.legs().isEmpty();
        showRoute.setEnabled(hasRoute);
        clear.setEnabled(ticket != null);
        expand.setEnabled(ticket != null);
    }

    /** Shows the complete ticket, enlarged, in a window covering the whole screen. */
    private void showFullScreen() {
        if (ticket == null) return;
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "Ticket — " + ticket.from().station().name + " to "
                + ticket.to().station().name, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        Rectangle screen = owner != null ? owner.getGraphicsConfiguration().getBounds()
                : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        dialog.setBounds(screen);

        JEditorPane view = htmlView();
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        // Once the screen area is known, pick the largest text size and column count that shows every step.
        TicketPlanner.Ticket shown = ticket;
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            private java.awt.Dimension fittedFor;

            @Override public void componentResized(ComponentEvent event) {
                java.awt.Dimension size = scroll.getViewport().getSize();
                if (size.width <= 0 || size.height <= 0 || size.equals(fittedFor)) return;
                fittedFor = size;
                FullScreenLayout layout = fitFullScreen(shown, size.width, size.height);
                int side = Math.max(FULL_SCREEN_MARGIN, (size.width - layout.width()) / 2);
                int top = Math.max(FULL_SCREEN_MARGIN, (size.height - layout.height()) / 2);
                view.setBorder(BorderFactory.createEmptyBorder(top, side, FULL_SCREEN_MARGIN, side));
                view.setText(layout.html());
                view.setCaretPosition(0);
            }
        });

        JLabel heading = new JLabel(ticket.from().station().name + "  →  " + ticket.to().station().name
                + "   ·   " + TicketPlanner.formatMinutes(ticket.totalMinutes()));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        heading.setForeground(new Color(239, 244, 247));
        JLabel hint = muted("Esc to close");
        hint.setFont(hint.getFont().deriveFont(14f));
        JButton close = new JButton("Close");
        close.setFont(close.getFont().deriveFont(Font.BOLD, 14f));
        close.addActionListener(event -> dialog.dispose());
        JPanel right = new JPanel(new BorderLayout(14, 0));
        right.setOpaque(false);
        right.add(hint, BorderLayout.CENTER);
        right.add(close, BorderLayout.EAST);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(14, 19, 26));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(61, 72, 84)),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)));
        bar.add(heading, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(BACKGROUND);
        content.add(bar, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().setDefaultButton(close);
        dialog.setVisible(true);
    }

    private static final int FULL_SCREEN_MARGIN = 20;
    private static final double[] FULL_SCREEN_SCALES = {1.6, 1.45, 1.3, 1.18, 1.06, 0.96, 0.88, 0.8, 0.72, 0.66};

    record FullScreenLayout(String html, int width, int height) {}

    /**
     * Chooses the biggest text scale at which the whole ticket fits in the given area, using the fewest
     * step columns that achieve it. Falls back to the smallest scale (scrollable) on very long journeys.
     */
    static FullScreenLayout fitFullScreen(TicketPlanner.Ticket ticket, int areaWidth, int areaHeight) {
        int available = Math.max(400, areaWidth - FULL_SCREEN_MARGIN * 2);
        int availableHeight = Math.max(300, areaHeight - FULL_SCREEN_MARGIN * 2);
        int legs = Math.max(1, ticket.legs().size());
        FullScreenLayout fallback = null;
        for (double scale : FULL_SCREEN_SCALES) {
            int maxColumns = 1;
            while (maxColumns < Math.min(legs, 6) && available / (maxColumns + 1) >= 270 * scale) maxColumns++;
            // More columns are always shorter; if even the widest grid is too tall, try a smaller scale.
            FullScreenLayout widest = layoutAt(ticket, maxColumns, scale, available);
            fallback = widest;
            if (widest.height() > availableHeight) continue;
            for (int columns = 1; columns < maxColumns; columns++) {
                FullScreenLayout candidate = layoutAt(ticket, columns, scale, available);
                if (candidate.height() <= availableHeight) return candidate;
            }
            return widest;
        }
        return fallback;
    }

    private static FullScreenLayout layoutAt(TicketPlanner.Ticket ticket, int columns, double scale, int available) {
        int width = (int) Math.min(available, Math.max(900 * scale, columns * 520 * scale));
        String html = TicketHtml.scaled(TicketHtml.renderWide(ticket, columns), scale);
        JEditorPane probe = htmlView();
        probe.setText(html);
        probe.setSize(width, Short.MAX_VALUE);
        return new FullScreenLayout(html, width, probe.getPreferredSize().height);
    }

    private static JEditorPane htmlView() {
        JEditorPane view = new JEditorPane("text/html", "");
        view.setEditable(false);
        view.setBackground(BACKGROUND);
        view.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        return view;
    }

    private static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        return label;
    }

    private static String placeholder() {
        return message("No ticket yet",
                "Type a station name in both fields, or double-click a station on the map and use "
                        + "<b>Set as start</b> / <b>Set as destination</b> in the Station tab.");
    }

    private static String message(String heading, String body) {
        return "<html><body style='font-family:sans-serif;color:#93a6b5;margin:6px'>"
                + "<p style='font-size:13px;color:#dfe7ec'><b>" + heading + "</b></p>"
                + "<p style='font-size:11px'>" + body + "</p></body></html>";
    }

    /** Renders a ticket as a printed paper stub. Swing's HTML engine supports only simple tables and CSS. */
    private static final class TicketHtml {
        private static final String PAPER = "#f4ecd8";
        private static final String INK = "#2b2a26";
        private static final String FADED = "#6b6455";

        static String render(TicketPlanner.Ticket ticket) {
            StringBuilder html = new StringBuilder();
            html.append("<html><body style='font-family:sans-serif;font-size:13px;margin:2px;color:").append(INK).append("'>");
            html.append("<table width='100%' cellspacing='0' cellpadding='0' bgcolor='").append(PAPER).append("'>");

            html.append("<tr><td bgcolor='#24313d' style='padding:7px 10px'>")
                    .append("<font color='#f4ecd8' style='font-size:11px'><b>DISTRICTS TRANSIT</b> &nbsp;·&nbsp; ADMIT ONE</font>")
                    .append("<br><font color='#93a6b5' style='font-size:11px'>Ticket No. ").append(ticketNumber(ticket))
                    .append("</font></td></tr>");

            html.append("<tr><td style='padding:9px 10px 2px 10px'>");
            endpoint(html, "FROM", ticket.from());
            html.append("</td></tr><tr><td style='padding:6px 10px 6px 10px'>");
            endpoint(html, "TO", ticket.to());
            html.append("</td></tr>");

            html.append("<tr><td style='padding:4px 10px 8px 10px'><table cellspacing='0' cellpadding='0' width='100%'><tr>");
            summaryCell(html, "FASTEST TIME", ticket.legs().isEmpty() ? "0 min" : TicketPlanner.formatMinutes(ticket.totalMinutes()), "62%");
            summaryCell(html, "TRANSFERS", Integer.toString(ticket.transfers()), "38%");
            html.append("</tr><tr><td colspan='2' style='padding-top:6px'><table cellspacing='0' cellpadding='0'><tr>");
            distanceCell(html, ticket);
            html.append("</tr></table></td>");
            html.append("</tr></table></td></tr>");
            perforation(html);

            if (ticket.legs().isEmpty()) {
                html.append("<tr><td style='padding:10px'>You are already at this station.</td></tr>");
            }
            double clock = 0.0;
            for (int index = 0; index < ticket.legs().size(); index++) {
                TicketPlanner.Leg leg = ticket.legs().get(index);
                leg(html, index + 1, leg, clock);
                clock += leg.waitMinutes() + leg.rideMinutes();
                if (index < ticket.legs().size() - 1) perforation(html);
            }
            perforation(html);
            footnote(html);
            html.append("</table></body></html>");
            return html.toString();
        }

        /** Landscape ticket for the full-screen view: one summary row, then the steps as cards in columns. */
        static String renderWide(TicketPlanner.Ticket ticket, int columns) {
            StringBuilder html = new StringBuilder();
            html.append("<html><body style='font-family:sans-serif;font-size:13px;margin:0;color:").append(INK).append("'>");
            html.append("<table width='100%' cellspacing='0' cellpadding='0' bgcolor='").append(PAPER).append("'>");
            html.append("<tr><td bgcolor='#24313d' style='padding:7px 12px'>")
                    .append("<font color='#f4ecd8' style='font-size:12px'><b>DISTRICTS TRANSIT</b> &nbsp;·&nbsp; ADMIT ONE")
                    .append("</font><font color='#93a6b5' style='font-size:12px'> &nbsp;&nbsp;&nbsp; Ticket No. ")
                    .append(ticketNumber(ticket)).append("</font></td></tr>");

            html.append("<tr><td style='padding:8px 12px 4px 12px'><table cellspacing='0' cellpadding='0' width='100%'><tr>");
            html.append("<td valign='top'>");
            endpoint(html, "FROM", ticket.from());
            html.append("</td><td valign='middle' align='center'><font style='font-size:20px' color='")
                    .append(FADED).append("'>&nbsp;→&nbsp;</font></td><td valign='top'>");
            endpoint(html, "TO", ticket.to());
            html.append("</td>");
            summaryCell(html, "FASTEST TIME", ticket.legs().isEmpty() ? "0 min" : TicketPlanner.formatMinutes(ticket.totalMinutes()));
            summaryCell(html, "TRANSFERS", Integer.toString(ticket.transfers()));
            distanceCell(html, ticket);
            html.append("</tr></table></td></tr>");
            perforation(html);

            html.append("<tr><td style='padding:0 4px'><table width='100%' cellspacing='8' cellpadding='0'>");
            double clock = 0.0;
            List<TicketPlanner.Leg> legs = ticket.legs();
            int rows = (legs.size() + columns - 1) / columns;
            for (int row = 0; row < rows; row++) {
                html.append("<tr>");
                for (int column = 0; column < columns; column++) {
                    int index = row * columns + column;
                    html.append("<td valign='top' width='").append(100 / columns).append("%'");
                    if (index < legs.size()) {
                        html.append(" bgcolor='#ebe0c4' style='padding:8px'>");
                        legBody(html, index + 1, legs.get(index), clock);
                        clock += legs.get(index).waitMinutes() + legs.get(index).rideMinutes();
                    } else {
                        html.append(">");
                    }
                    html.append("</td>");
                }
                html.append("</tr>");
            }
            if (legs.isEmpty()) html.append("<tr><td>You are already at this station.</td></tr>");
            html.append("</table></td></tr>");
            footnote(html);
            html.append("</table></body></html>");
            return html.toString();
        }

        private static void footnote(StringBuilder html) {
            html.append("<tr><td style='padding:4px 12px 9px 12px'><font style='font-size:11px' color='").append(FADED).append("'>")
                    .append("Times include average waits (bus ")
                    .append(minutes(TicketPlanner.Mode.BUS.averageWaitMinutes)).append(", subway ")
                    .append(minutes(TicketPlanner.Mode.SUBWAY.averageWaitMinutes)).append(", train ")
                    .append(minutes(TicketPlanner.Mode.TRAIN.averageWaitMinutes)).append(", ferry ")
                    .append(minutes(TicketPlanner.Mode.FERRY.averageWaitMinutes))
                    .append(" min) and stops along the way. 1 district = ")
                    .append(String.format(Locale.ROOT, "%,.0f", TicketPlanner.KM_PER_MAP_UNIT))
                    .append(" km. Bullet train ").append(minutes(TicketPlanner.TRAIN_SPEED_KMH))
                    .append(" km/h.</font></td></tr>");
        }

        private static void endpoint(StringBuilder html, String label, TicketPlanner.StationRef ref) {
            html.append("<font style='font-size:11px' color='").append(FADED).append("'><b>").append(label).append("</b></font><br>")
                    .append("<font style='font-size:18px'><b>").append(escape(ref.station().name)).append("</b></font><br>")
                    .append("<font style='font-size:11px' color='").append(FADED).append("'>")
                    .append(ref.station().capital ? "Capital city" : ref.station().kind == districts.model.World.StationKind.CITY ? "City" : "Town")
                    .append(" · District ").append(ref.district().id).append(" · ").append(escape(ref.region().name))
                    .append("</font>");
        }

        private static void summaryCell(StringBuilder html, String label, String value) {
            summaryCell(html, label, value, null);
        }

        /** With a width, labels and values never wrap (used in the narrow sidebar ticket). */
        private static void summaryCell(StringBuilder html, String label, String value, String width) {
            if (width != null) {
                html.append("<td valign='top' width='").append(width).append("'><font style='font-size:11px' color='")
                        .append(FADED).append("'><b>").append(nowrap(label)).append("</b></font><br><font style='font-size:15px'><b>")
                        .append(nowrap(value)).append("</b></font></td>");
                return;
            }
            html.append("<td valign='top'><font style='font-size:11px' color='").append(FADED).append("'><b>").append(label)
                    .append("</b></font><br><font style='font-size:15px'><b>").append(value).append("</b></font></td>");
        }

        /** Travelled distance plus the straight-line distance between the two stations, for comparison. */
        private static void distanceCell(StringBuilder html, TicketPlanner.Ticket ticket) {
            distanceCell(html, ticket, null);
        }

        private static void distanceCell(StringBuilder html, TicketPlanner.Ticket ticket, String width) {
            double straight = Math.hypot(ticket.from().station().x - ticket.to().station().x,
                    ticket.from().station().y - ticket.to().station().y) * TicketPlanner.KM_PER_MAP_UNIT;
            html.append("<td valign='top'").append(width == null ? "" : " width='" + width + "'")
                    .append("><font style='font-size:11px' color='").append(FADED).append("'><b>DISTANCE</b></font><br>")
                    .append("<font style='font-size:15px'><b>").append(nowrap(String.format(Locale.ROOT, "%,.0f km", ticket.distanceKm())))
                    .append("</b></font><br><font style='font-size:11px' color='").append(FADED).append("'>straight ")
                    .append(nowrap(String.format(Locale.ROOT, "%,.0f km", straight)));
            if (straight > 0.5) html.append(String.format(Locale.ROOT, " (×%.2f)", ticket.distanceKm() / straight));
            html.append("</font></td>");
        }

        private static void perforation(StringBuilder html) {
            html.append("<tr><td align='center' style='padding:0 6px'><font color='#c9bd9c' style='font-size:10px'>")
                    .append("- - - - - - - - - - - - - - - - - - - - - -</font></td></tr>");
        }

        private static void leg(StringBuilder html, int number, TicketPlanner.Leg leg, double clock) {
            html.append("<tr><td style='padding:8px 10px'>");
            legBody(html, number, leg, clock);
            html.append("</td></tr>");
        }

        private static void legBody(StringBuilder html, int number, TicketPlanner.Leg leg, double clock) {
            String color = switch (leg.mode()) {
                case BUS -> "#41c46f";
                case SUBWAY -> "#d8bb81";
                case TRAIN -> "#5dcdeb";
                case FERRY -> "#b09cff";
            };
            String verb = switch (leg.mode()) {
                case BUS -> "Take the bus";
                case SUBWAY -> "Take the subway";
                case TRAIN -> "Take the train";
                case FERRY -> "Take the ferry";
            };
            int stops = leg.stopsToRide();
            html.append("<table cellspacing='0' cellpadding='0' width='100%'><tr>")
                    .append("<td width='78' valign='top'><table cellspacing='0' cellpadding='3'><tr><td bgcolor='")
                    .append(color).append("' align='center'><font style='font-size:11px' color='#10161d'><b>")
                    .append(leg.mode().label.toUpperCase(Locale.ROOT)).append("</b></font></td></tr></table>")
                    .append("<font style='font-size:11px' color='").append(FADED).append("'>Step ").append(number)
                    .append("<br>").append(elapsed(clock))
                    .append("</font></td>")
                    .append("<td valign='top'>");
            html.append("<b>").append(escape(leg.line())).append("</b><br>")
                    .append("<font style='font-size:11px' color='").append(FADED).append("'>").append(verb).append(" toward ")
                    .append(escape(leg.toward())).append("</font><br>");
            html.append("Board at <b>").append(escape(leg.boardAt().station().name)).append("</b>")
                    .append("<br><font style='font-size:11px' color='").append(FADED).append("'>wait about ")
                    .append(TicketPlanner.formatMinutes(leg.waitMinutes())).append("</font><br>");
            html.append("Ride <b>").append(stops).append(stops == 1 ? " stop" : " stops").append("</b>")
                    .append(" · ").append(nowrap(TicketPlanner.formatMinutes(leg.rideMinutes())))
                    .append(" · ").append(nowrap(String.format(Locale.ROOT, leg.distanceKm() < 100 ? "%.1f km" : "%,.0f km", leg.distanceKm())))
                    .append("<br>");
            html.append("Get off at <b>").append(escape(leg.alightAt().station().name)).append("</b>");
            if (stops > 1) {
                html.append("<br><font style='font-size:11px' color='").append(FADED).append("'>Stops: ")
                        .append(stopList(leg.stops())).append("</font>");
            }
            html.append("</td></tr></table>");
        }

        /** Lists the stops after boarding; long bus rides are shortened in the middle. */
        private static String stopList(List<TicketPlanner.StationRef> stops) {
            List<TicketPlanner.StationRef> ridden = stops.subList(1, stops.size());
            StringBuilder text = new StringBuilder();
            int limit = 12;
            for (int index = 0; index < ridden.size(); index++) {
                if (ridden.size() > limit && index == 5) {
                    text.append("… ").append(ridden.size() - 10).append(" more … · ");
                    index = ridden.size() - 5;
                }
                text.append(index + 1 == ridden.size() ? "<b>" : "")
                        .append(escape(ridden.get(index).station().name))
                        .append(index + 1 == ridden.size() ? "</b>" : " · ");
            }
            return text.toString();
        }

        private static String ticketNumber(TicketPlanner.Ticket ticket) {
            int hash = (ticket.from().station().name + "→" + ticket.to().station().name).hashCode();
            String digits = String.format(Locale.ROOT, "%08d", Math.floorMod(hash, 100_000_000));
            return digits.substring(0, 4) + "-" + digits.substring(4);
        }

        private static String minutes(double value) {
            return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
        }

        private static String escape(String text) { return StationSearchField.escape(text); }

        /** Enlarges every pixel size in a rendered ticket, for the full-screen view. */
        static String scaled(String html, double factor) {
            Matcher matcher = Pattern.compile("(font-size:|width=')(\\d+)(px|')").matcher(html);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                long size = Math.round(Integer.parseInt(matcher.group(2)) * factor);
                matcher.appendReplacement(result, matcher.group(1) + size + matcher.group(3));
            }
            matcher.appendTail(result);
            return result.toString();
        }

        /** Compact time since departure for the narrow step column, e.g. +45m or +21h 23m. */
        private static String elapsed(double minutes) {
            long rounded = Math.round(minutes);
            return rounded < 60 ? "+" + rounded + "m" : String.format(Locale.ROOT, "+%dh&nbsp;%02dm", rounded / 60, rounded % 60);
        }

        private static String nowrap(String text) { return text.replace(" ", "&nbsp;"); }
    }
}
