package com.myhooks.xmlspan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One element in the character-offset XML index.
 *
 * <p>{@code tag} is the element's local name ({@code element}, {@code band},
 * {@code text}, ...). {@code kind} is the unified "what is this element" value:
 * for an {@code <element>} it is the value of the {@code kind} attribute
 * ({@code textField}, {@code staticText}, {@code frame}, ...); for any other
 * tag it falls back to the tag name.
 *
 * <p>All four offsets are absolute character offsets into the scanned text:
 * <ul>
 *   <li>{@code startTag} — the {@code <} of the start tag.</li>
 *   <li>{@code startTagEnd} — one past the {@code >} of the start tag.</li>
 *   <li>{@code endTag} — the {@code <} of the end tag (equals {@code startTag}
 *       for a self-closing element).</li>
 *   <li>{@code end} — one past the {@code >} of the end tag (equals
 *       {@code startTagEnd} for a self-closing element).</li>
 * </ul>
 */
public final class Node {

    private final String tag;
    private final String kind;
    private final List<Attr> attrs;
    private final int startTag;
    private final int startTagEnd;
    private final int depth;

    private Node parent;
    private final List<Node> children = new ArrayList<>();
    private int endTag;
    private int end;

    Node(String tag, String kind, List<Attr> attrs, int startTag, int startTagEnd, int depth) {
        this.tag = tag;
        this.kind = kind;
        this.attrs = Collections.unmodifiableList(new ArrayList<>(attrs));
        this.startTag = startTag;
        this.startTagEnd = startTagEnd;
        this.depth = depth;
        // Default to a self-closing element; close() overrides for paired tags.
        this.endTag = startTag;
        this.end = startTagEnd;
    }

    void setParent(Node parent) {
        this.parent = parent;
    }

    void addChild(Node child) {
        children.add(child);
        child.setParent(this);
    }

    void close(int endTag, int end) {
        this.endTag = endTag;
        this.end = end;
    }

    public String tag() {
        return tag;
    }

    public String kind() {
        return kind;
    }

    public List<Attr> attrs() {
        return attrs;
    }

    public Node parent() {
        return parent;
    }

    public List<Node> children() {
        return Collections.unmodifiableList(children);
    }

    public int startTag() {
        return startTag;
    }

    public int startTagEnd() {
        return startTagEnd;
    }

    public int endTag() {
        return endTag;
    }

    public int end() {
        return end;
    }

    public int depth() {
        return depth;
    }
}
