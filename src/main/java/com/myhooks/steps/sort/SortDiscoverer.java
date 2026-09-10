package com.myhooks.steps.sort;

import com.myhooks.edit.Edit;
import com.myhooks.step.Context;
import com.myhooks.step.Discoverer;
import com.myhooks.step.EditFix;
import com.myhooks.step.Fix;
import com.myhooks.step.Group;
import com.myhooks.xmlspan.Attr;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.Query;
import com.myhooks.xmlspan.XmlScanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import javax.xml.stream.XMLStreamException;

/**
 * The sort step's fix discovery: reorder each band/frame container's direct
 * {@code <element>} children by geometry (y then x, stable). Overlaps and
 * missing/non-numeric coordinates are surfaced as warnings, not silently
 * accepted.
 */
public final class SortDiscoverer implements Discoverer {

    record Child(String kind, int x, int y, int w, int h, int start, int end) {
    }

    record Container(String kind, int innerStart, int innerEnd, List<Child> children) {
    }

    record Overlap(Child a, Child b) {
    }

    record ParsedReport(List<Container> containers, List<String> warnings) {
    }

    @Override
    public List<Group> discover(Context context, Path path) throws Exception {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        ParsedReport report = parse(raw);

        List<Container> changed = report.containers().stream()
                .filter(SortDiscoverer::reorderChanged)
                .toList();
        if (changed.isEmpty()) {
            return List.of();
        }

        List<String> details = new ArrayList<>(report.warnings());
        for (Container container : changed) {
            details.add(orderSummary(container.children()));
            for (Overlap overlap : overlaps(container.children())) {
                details.add(String.format("warning: overlap %s(%d,%d) <-> %s(%d,%d)",
                        overlap.a().kind(), overlap.a().x(), overlap.a().y(),
                        overlap.b().kind(), overlap.b().x(), overlap.b().y()));
            }
        }

        String reordered = applyReorders(raw, report.containers());
        Fix fix = new EditFix(String.join("\n", details), raw, reordered,
                new Edit(0, raw.length(), reordered), context.color());
        return List.of(new Group("sort", List.of(fix)));
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    static ParsedReport parse(String raw) throws XMLStreamException {
        Node root = XmlScanner.scan(raw);
        List<Container> containers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        collectContainers(root, raw, containers, warnings);
        return new ParsedReport(containers, warnings);
    }

    private static void collectContainers(Node node, String raw, List<Container> containers, List<String> warnings) {
        if (isContainer(node)) {
            List<Child> children = new ArrayList<>();
            for (Node child : Query.directChildren(node, null)) {
                children.add(toChild(child, raw, warnings));
            }
            containers.add(new Container(node.kind(), node.startTagEnd(), node.endTag(), children));
        }
        for (Node child : node.children()) {
            collectContainers(child, raw, containers, warnings);
        }
    }

    private static boolean isContainer(Node node) {
        return node.tag().equals("band") || (node.tag().equals("element") && node.kind().equals("frame"));
    }

    private static Child toChild(Node element, String raw, List<String> warnings) {
        int x = coordinate(element, "x", raw, warnings);
        int y = coordinate(element, "y", raw, warnings);
        int w = coordinate(element, "width", raw, warnings);
        int h = coordinate(element, "height", raw, warnings);
        return new Child(element.kind(), x, y, w, h, element.startTag(), element.end());
    }

    private static int coordinate(Node element, String name, String raw, List<String> warnings) {
        OptionalInt value = parseInt(element, name, raw);
        if (value.isEmpty()) {
            warnings.add(String.format("warning: missing or non-numeric %s on %s (line %d)",
                    name, element.kind(), lineOf(raw, element.startTag())));
            return 0;
        }
        return value.getAsInt();
    }

    /** Parses an attribute as an int; empty for a missing or non-numeric value. */
    static OptionalInt parseInt(Node element, String name, String raw) {
        Optional<Attr> attr = Query.findAttr(element, name);
        if (attr.isEmpty()) {
            return OptionalInt.empty();
        }
        String value = raw.substring(attr.get().valueStart(), attr.get().valueEnd()).strip();
        try {
            return OptionalInt.of(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static int lineOf(String raw, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (raw.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ------------------------------------------------------------------
    // Sorting and reordering
    // ------------------------------------------------------------------

    static List<Child> sortedChildren(List<Child> children) {
        List<Child> sorted = new ArrayList<>(children);
        sorted.sort((a, b) -> {
            if (a.y() != b.y()) {
                return Integer.compare(a.y(), b.y());
            }
            return Integer.compare(a.x(), b.x());
        });
        return sorted;
    }

    static boolean reorderChanged(Container container) {
        if (container.children().size() < 2) {
            return false;
        }
        List<Child> sorted = sortedChildren(container.children());
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i) != container.children().get(i)) {
                return true;
            }
        }
        return false;
    }

    static List<Overlap> overlaps(List<Child> children) {
        List<Overlap> out = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            for (int j = i + 1; j < children.size(); j++) {
                if (rectsOverlap(children.get(i), children.get(j))) {
                    out.add(new Overlap(children.get(i), children.get(j)));
                }
            }
        }
        return out;
    }

    static boolean rectsOverlap(Child a, Child b) {
        return a.x() < b.x() + b.w() && b.x() < a.x() + a.w()
                && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
    }

    static String applyReorders(String raw, List<Container> containers) {
        List<Container> ordered = new ArrayList<>(containers);
        ordered.sort((a, b) -> Integer.compare(b.innerStart(), a.innerStart()));
        String result = raw;
        for (Container container : ordered) {
            result = reorderContainer(result, container);
        }
        return result;
    }

    private static String reorderContainer(String raw, Container container) {
        if (!reorderChanged(container)) {
            return raw;
        }
        List<Child> original = container.children();
        List<Child> sorted = sortedChildren(original);
        StringBuilder b = new StringBuilder(container.innerEnd() - container.innerStart());
        b.append(raw, container.innerStart(), original.get(0).start());
        for (int k = 0; k < sorted.size(); k++) {
            b.append(raw, sorted.get(k).start(), sorted.get(k).end());
            if (k == sorted.size() - 1) {
                b.append(raw, original.get(original.size() - 1).end(), container.innerEnd());
            } else {
                b.append(raw, original.get(k).end(), original.get(k + 1).start());
            }
        }
        return raw.substring(0, container.innerStart()) + b + raw.substring(container.innerEnd());
    }

    private static String orderSummary(List<Child> children) {
        String oldOrder = children.stream().map(SortDiscoverer::desc).collect(Collectors.joining(", "));
        String newOrder = sortedChildren(children).stream().map(SortDiscoverer::desc).collect(Collectors.joining(", "));
        return "reorder [" + oldOrder + "] -> [" + newOrder + "]";
    }

    private static String desc(Child child) {
        return child.kind() + "(" + child.x() + "," + child.y() + ")";
    }
}
