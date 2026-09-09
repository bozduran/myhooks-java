package com.myhooks.xmlspan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Static helpers over a scanned {@link Node} tree.
 */
public final class Query {

    private Query() {
    }

    /**
     * Returns the immediate {@code <element>} children of {@code node}. When
     * {@code kind} is non-null, only children whose {@link Node#kind()} equals
     * it are returned. Non-{@code element} children (e.g. {@code <property>},
     * {@code <component>}) are always excluded, and nested elements deeper than
     * the direct child level are not considered.
     */
    public static List<Node> directChildren(Node node, String kind) {
        List<Node> out = new ArrayList<>();
        for (Node child : node.children()) {
            if (!child.tag().equals("element")) {
                continue;
            }
            if (kind != null && !kind.equals(child.kind())) {
                continue;
            }
            out.add(child);
        }
        return out;
    }

    /**
     * Returns the descendants of {@code node} whose {@link Node#tag()} equals
     * {@code tag}, in document order.
     */
    public static List<Node> descendants(Node node, String tag) {
        List<Node> out = new ArrayList<>();
        collectDescendants(node, tag, out);
        return out;
    }

    private static void collectDescendants(Node node, String tag, List<Node> out) {
        for (Node child : node.children()) {
            if (child.tag().equals(tag)) {
                out.add(child);
            }
            collectDescendants(child, tag, out);
        }
    }

    /**
     * Finds the named attribute regardless of whether its value is single- or
     * double-quoted.
     */
    public static Optional<Attr> findAttr(Node node, String name) {
        for (Attr attr : node.attrs()) {
            if (attr.name().equals(name)) {
                return Optional.of(attr);
            }
        }
        return Optional.empty();
    }

    /**
     * Reports whether {@code node} sits inside a rendered-text context: any
     * ancestor whose {@link Node#kind()} is {@code textField} or
     * {@code staticText}. Steps additionally treat
     * {@code defaultValueExpression} / {@code initialValueExpression} as text
     * contexts themselves (see US-09/US-11).
     */
    public static boolean isRenderedTextContext(Node node) {
        for (Node n = node.parent(); n != null; n = n.parent()) {
            if (n.kind().equals("textField") || n.kind().equals("staticText")) {
                return true;
            }
        }
        return false;
    }
}
