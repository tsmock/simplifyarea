// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.simplifyarea;

import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.lang.Math.toRadians;
import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.HelpAwareOptionPane;
import org.openstreetmap.josm.gui.HelpAwareOptionPane.ButtonSpec;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

public final class SimplifyAreaAction extends JosmAction {

    public SimplifyAreaAction() {
        super(tr("Simplify Area"), "simplify", tr("Delete unnecessary nodes from an area."),
                Shortcut.registerShortcut("tools:simplifyArea", tr("More tools: {0}", tr("Simplify Area")), KeyEvent.VK_Y, Shortcut.CTRL_SHIFT),
                true, "simplifyarea", true);
    }

    private static boolean confirmWayWithNodesOutsideBoundingBox() {
        final ButtonSpec[] options = new ButtonSpec[] { new ButtonSpec(tr("Yes, delete nodes"), ImageProvider.get("ok"), tr("Delete nodes outside of downloaded data regions"), null),
                new ButtonSpec(tr("No, abort"), ImageProvider.get("cancel"), tr("Cancel operation"), null) };
        return 0 == HelpAwareOptionPane.showOptionDialog(
                MainApplication.getMainFrame(),
                "<html>" + trn("The selected way has nodes outside of the downloaded data region.", "The selected ways have nodes outside of the downloaded data region.", 
                        MainApplication.getLayerManager().getEditDataSet().getSelectedWays().size())
                + "<br>" + tr("This can lead to nodes being deleted accidentally.") + "<br>" + tr("Do you want to delete them anyway?") + "</html>",
                tr("Delete nodes outside of data regions?"), JOptionPane.WARNING_MESSAGE, null, // no special icon
                options, options[0], null);
    }

    private void alertSelectAtLeastOneWay() {
        HelpAwareOptionPane.showOptionDialog(MainApplication.getMainFrame(), tr("Please select at least one way to simplify."), tr("Warning"), JOptionPane.WARNING_MESSAGE, null);
    }

    private boolean confirmSimplifyManyWays(final int numWays) {
        final ButtonSpec[] options = new ButtonSpec[] { new ButtonSpec(tr("Yes"), ImageProvider.get("ok"), tr("Simplify all selected ways"), null),
                new ButtonSpec(tr("Cancel"), ImageProvider.get("cancel"), tr("Cancel operation"), null) };
        return 0 == HelpAwareOptionPane.showOptionDialog(MainApplication.getMainFrame(), tr("The selection contains {0} ways. Are you sure you want to simplify them all?", numWays), 
                tr("Simplify ways?"),
                JOptionPane.WARNING_MESSAGE, null, // no special icon
                options, options[0], null);
    }

    @Override
    public void actionPerformed(final ActionEvent e) {
        final Collection<Way> ways = new LinkedHashSet<>(getLayerManager().getEditDataSet().getSelectedWays());
        ways.removeIf(w -> !w.isUsable());
        int checkRes = Command.checkOutlyingOrIncompleteOperation(ways, null);
        if ((checkRes & Command.IS_OUTSIDE) != 0) {
            if (!confirmWayWithNodesOutsideBoundingBox())
                return;
        }
        if (ways.isEmpty()) {
            alertSelectAtLeastOneWay();
            return;
        } else if (ways.size() > 10) {
            if (!confirmSimplifyManyWays(ways.size())) {
                return;
            }
        }

        final List<Node> nodesToDelete = new ArrayList<>(); // can contain duplicate instances

        for (final Way way : ways) {
            addNodesToDelete(nodesToDelete, way);
        }

        final Map<Node, Integer> nodeCountMap = new HashMap<>();
        for (final Node node : nodesToDelete) {
            Integer count = nodeCountMap.get(node);
            if (count == null) {
                count = 0;
            }
            nodeCountMap.put(node, ++count);
        }

        final Collection<Node> nodesReallyToRemove = new ArrayList<>();

        for (final Entry<Node, Integer> entry : nodeCountMap.entrySet()) {
            final Node node = entry.getKey();
            final Integer count = entry.getValue();

            if (!node.isTagged() && node.getReferrers().size() == count) {
                nodesReallyToRemove.add(node);
            }
        }

        final Collection<Command> allCommands = new ArrayList<>();
        Map<Way, List<Node>> modifiedWays = new HashMap<>();

        if (!nodesReallyToRemove.isEmpty()) {
            for (final Way way : ways) {
                final List<Node> nodes = way.getNodes();
                final boolean closed = nodes.get(0).equals(nodes.get(nodes.size() - 1));
                if (closed) {
                    nodes.remove(nodes.size() - 1);
                }

                if (nodes.removeAll(nodesReallyToRemove)) {
                    if (closed) {
                        nodes.add(nodes.get(0));
                    }
                    allCommands.add(new ChangeNodesCommand(way, nodes));
                    modifiedWays.put(way, nodes);
                }
            }

            allCommands.add(new DeleteCommand(nodesReallyToRemove));
        }

        final Collection<Command> avgCommands = averageNearbyNodes(ways, nodesReallyToRemove, modifiedWays);
        if (avgCommands != null && !avgCommands.isEmpty()) {
            allCommands.add(new SequenceCommand(tr("average nearby nodes"), avgCommands));
        }

        if (!allCommands.isEmpty()) {
            final SequenceCommand rootCommand = new SequenceCommand(trn("Simplify {0} way", "Simplify {0} ways", modifiedWays.size(), modifiedWays.size()), allCommands);
            UndoRedoHandler.getInstance().add(rootCommand);
            MainApplication.getMap().repaint();
        }
    }

    private static boolean nodeGluesWays(final Node node) {
        Set<Node> referenceNeighbours = null;
        for (final OsmPrimitive ref : node.getReferrers()) {
            if (ref.getType() == OsmPrimitiveType.WAY) {
                final Way way = ((Way) ref);
                final Set<Node> neighbours = way.getNeighbours(node);
                if (referenceNeighbours == null) {
                    referenceNeighbours = neighbours;
                } else if (!referenceNeighbours.containsAll(neighbours)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    // average nearby nodes
    private static Collection<Command> averageNearbyNodes(final Collection<Way> ways, final Collection<Node> nodesAlreadyDeleted, Map<Way, List<Node>> modifiedWays) {
        final double mergeThreshold = Config.getPref().getDouble(SimplifyAreaPreferenceSetting.MERGE_THRESHOLD, 0.2);

        final Map<Node, LatLon> coordMap = new HashMap<>();
        for (final Way way : ways) {
            for (final Node n : way.getNodes()) {
                coordMap.put(n, n.getCoor());
            }
        }

        coordMap.keySet().removeAll(nodesAlreadyDeleted);

        for (final Way w : ways) {
            final List<Node> nodes = w.getNodes();

            final Node lastNode = nodes.get(nodes.size() - 1);
            final boolean closed = nodes.get(0).equals(lastNode);
            if (closed) {
                nodes.remove(lastNode);
            }

            nodes.retainAll(coordMap.keySet()); // removes already deleted nodes

            while (true) {
                double minDist = Double.POSITIVE_INFINITY;
                Node node1 = null;
                Node node2 = null;

                final int len = nodes.size();
                if (len == 0) {
                    break;
                }

                // find smallest distance
                for (int i = 0; i <= len; i++) {
                    final Node n1 = nodes.get(i % len);
                    final Node n2 = nodes.get((i + 1) % len);

                    if (n1.isTagged() || n2.isTagged()) {
                        continue;
                    }

                    // test if both nodes are on the same ways
                    final List<OsmPrimitive> referrers = n1.getReferrers();
                    if (!ways.containsAll(referrers)) {
                        continue;
                    }

                    final List<OsmPrimitive> referrers2 = n2.getReferrers();
                    if (!ways.containsAll(referrers2)) {
                        continue;
                    }

                    // test if both nodes have same parents
                    if (referrers.size() != referrers2.size() || !referrers2.containsAll(referrers)) {
                        continue;
                    }

                    final ILatLon a = coordMap.get(n1);
                    final ILatLon b = coordMap.get(n2);
                    
                    if (a != null && b != null) {
                        final double dist = a.greatCircleDistance(b);
                        if (dist < minDist && dist < mergeThreshold) {
                            minDist = dist;
                            node1 = n1;
                            node2 = n2;
                        }
                    }
                }

                if (node1 == null || node2 == null) {
                    break;
                }

                final LatLon coord = coordMap.get(node1).getCenter(coordMap.get(node2));
                coordMap.put(node1, coord);

                nodes.remove(node2);
                coordMap.remove(node2);
            }
        }

        final Collection<Command> commands = new ArrayList<>();
        final Set<Node> nodesToDelete2 = new HashSet<>();
        for (final Way way : ways) {
            final List<Node> nodesToDelete = way.getNodes();
            nodesToDelete.removeAll(nodesAlreadyDeleted);
            if (nodesToDelete.removeAll(coordMap.keySet())) {
                nodesToDelete2.addAll(nodesToDelete);
                final List<Node> nodes = way.getNodes();
                final boolean closed = nodes.get(0).equals(nodes.get(nodes.size() - 1));
                if (closed) {
                    nodes.remove(nodes.size() - 1);
                }
                nodes.retainAll(coordMap.keySet());
                if (closed) {
                    nodes.add(nodes.get(0));
                }

                if (!way.getNodes().equals(nodes)) {
                    List<Node> nodes1 = modifiedWays.get(way);
                    if (nodes1 == null || !nodes.equals(nodes1)) {
                        commands.add(new ChangeNodesCommand(way, nodes));
                        modifiedWays.put(way, nodes);
                    }
                }
            }
        }

        if (!nodesToDelete2.isEmpty()) {
            commands.add(new DeleteCommand(nodesToDelete2));
        }

        for (final Entry<Node, LatLon> entry : coordMap.entrySet()) {
            final Node node = entry.getKey();
            final LatLon coord = entry.getValue();
            if (!node.getCoor().equals(coord)) {
                commands.add(new MoveCommand(node, coord));
            }
        }

        return commands;
    }

    private static void addNodesToDelete(final Collection<Node> nodesToDelete, final Way w) {
        final double angleThreshold = Config.getPref().getDouble(SimplifyAreaPreferenceSetting.ANGLE_THRESHOLD, 10);
        final double angleFactor = Config.getPref().getDouble(SimplifyAreaPreferenceSetting.ANGLE_FACTOR, 1.0);
        final double areaThreshold = Config.getPref().getDouble(SimplifyAreaPreferenceSetting.AREA_THRESHOLD, 5.0);
        final double areaFactor = Config.getPref().getDouble(SimplifyAreaPreferenceSetting.AREA_FACTOR, 1.0);
        final double distanceThreshold = Config.getPref().getDouble(SimplifyAreaPreferenceSetting.DIST_THRESHOLD, 3);
        final double distanceFactor = Config.getPref().getDouble(SimplifyAreaPreferenceSetting.DIST_FACTOR, 3);

        final List<Node> nodes = w.getNodes();
        final int size = nodes.size();

        if (size == 0) {
            return;
        }

        final boolean closed = nodes.get(0).equals(nodes.get(size - 1));

        if (closed) {
            nodes.remove(size - 1); // remove end node ( = start node)
        }

        // remove nodes within threshold

        final List<Double> weightList = new ArrayList<>(nodes.size()); // weight cache
        for (int i = 0; i < nodes.size(); i++) {
            weightList.add(null);
        }

        while (true) {
            Node prevNode = null;
            LatLon coord1 = null;
            LatLon coord2 = null;
            int prevIndex = -1;

            double minWeight = Double.POSITIVE_INFINITY;
            Node bestMatch = null;

            final int size2 = nodes.size();

            if (size2 == 0) {
                break;
            }

            for (int i = 0, len = size2 + (closed ? 2 : 1); i < len; i++) {
                final int index = i % size2;

                final Node n = nodes.get(index);
                final LatLon coord3 = n.getCoor();

                if (coord1 != null) {
                    final double weight;

                    if (weightList.get(prevIndex) == null) {
                        final double angleWeight = computeConvectAngle(coord1, coord2, coord3) / angleThreshold;
                        final double areaWeight = computeArea(coord1, coord2, coord3) / areaThreshold;
                        final double distanceWeight = Math.abs(crossTrackError(coord1, coord2, coord3)) / distanceThreshold;

                        weight = (!closed && i == len - 1) || // don't remove last node of the not closed way
                                nodeGluesWays(prevNode) ||
                                angleWeight > 1.0 || areaWeight > 1.0 || distanceWeight > 1.0 ? Double.POSITIVE_INFINITY :
                                angleWeight * angleFactor + areaWeight * areaFactor + distanceWeight * distanceFactor;

                        weightList.set(prevIndex, weight);
                    } else {
                        weight = weightList.get(prevIndex);
                    }

                    if (weight < minWeight) {
                        minWeight = weight;
                        bestMatch = prevNode;
                    }
                }

                coord1 = coord2;
                coord2 = coord3;
                prevNode = n;
                prevIndex = index;
            }

            if (bestMatch == null) {
                break;
            }

            final int index = nodes.indexOf(bestMatch);

            weightList.set((index - 1 + size2) % size2, null);
            weightList.set((index + 1 + size2) % size2, null);
            weightList.remove(index);
            nodes.remove(index);
        }

        final HashSet<Node> delNodes = new HashSet<>(w.getNodes());
        delNodes.removeAll(nodes);

        nodesToDelete.addAll(delNodes);
    }

    public static double computeConvectAngle(final ILatLon coord1, final ILatLon coord2, final ILatLon coord3) {
        final double angle = Math.abs(heading(coord2, coord3) - heading(coord1, coord2));
        return Math.toDegrees(angle < Math.PI ? angle : 2 * Math.PI - angle);
    }

    public static double computeArea(final ILatLon coord1, final ILatLon coord2, final ILatLon coord3) {
        final double a = coord1.greatCircleDistance(coord2);
        final double b = coord2.greatCircleDistance(coord3);
        final double c = coord3.greatCircleDistance(coord1);

        final double p = (a + b + c) / 2.0;

        final double q = p * (p - a) * (p - b) * (p - c); // I found this negative in one case (:-o) when nodes were in line on a small area
        return q < 0.0 ? 0.0 : Math.sqrt(q);
    }

    public static final double R = 6378135;

    public static double crossTrackError(final ILatLon l1, final ILatLon l2, final ILatLon l3) {
        return R * Math.asin(sin(l1.greatCircleDistance(l2) / R) * sin(heading(l1, l2) - heading(l1, l3)));
    }

    public static double heading(final ILatLon a, final ILatLon b) {
        double hd = Math.atan2(sin(toRadians(a.lon() - b.lon())) * cos(toRadians(b.lat())),
                cos(toRadians(a.lat())) * sin(toRadians(b.lat())) -
                sin(toRadians(a.lat())) * cos(toRadians(b.lat())) * cos(toRadians(a.lon() - b.lon())));
        hd %= 2 * Math.PI;
        if (hd < 0) {
            hd += 2 * Math.PI;
        }
        return hd;
    }

    @Override
    protected void updateEnabledState() {
        if (getLayerManager().getEditDataSet() == null) {
            setEnabled(false);
        } else {
            updateEnabledState(getLayerManager().getEditDataSet().getSelected());
        }
    }

    @Override
    protected void updateEnabledState(final Collection<? extends OsmPrimitive> selection) {
        setEnabled(selection != null && !selection.isEmpty());
    }
}
