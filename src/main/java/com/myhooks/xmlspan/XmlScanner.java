package com.myhooks.xmlspan;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Builds a character-offset {@link Node} tree from a JRXML document.
 *
 * <p>Every offset is a <em>character</em> offset into the same {@link String}
 * that is passed to {@link #scan(String)}. That is the coordinate system the
 * editing path uses ({@link String#substring}, {@code StringBuilder.replace}),
 * so a document containing multi-byte UTF-8 characters (where a byte offset and
 * a character offset differ) is no longer mis-sliced.
 *
 * <p>Structure (element names, nesting, attribute decoding, self-closing
 * detection) is driven by StAX, which also validates well-formedness. Exact
 * spans are computed with a quote-aware scan of the decoded text because
 * StAX's {@link javax.xml.stream.Location#getCharacterOffset()} is unreliable
 * across implementations.
 */
public final class XmlScanner {

    private XmlScanner() {
    }

    /** Scans {@code xml} and returns the document root {@link Node}. */
    public static Node scan(String xml) throws XMLStreamException {
        Objects.requireNonNull(xml, "xml");
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));

        Node root = null;
        Deque<Node> stack = new ArrayDeque<>();
        int cursor = 0;
        boolean skipNextEnd = false;

        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String tag = reader.getLocalName();
                    int startTag = findStartTag(xml, cursor, tag);
                    if (startTag < 0) {
                        throw new XMLStreamException("could not locate <" + tag + ">");
                    }
                    StartTag st = scanStartTag(xml, startTag);
                    String kind = attributeValue(reader, "kind");
                    if (kind == null) {
                        kind = tag;
                    }
                    Node node = new Node(tag, kind, st.attrs, startTag, st.end, stack.size());
                    if (root == null) {
                        root = node;
                    } else {
                        stack.peek().addChild(node);
                    }
                    if (st.selfClosing) {
                        // StAX emits START then END for a self-closing element; its
                        // END event must not be attributed to the enclosing element
                        // (which may share the same tag name, e.g. <element>).
                        skipNextEnd = true;
                    } else {
                        stack.push(node);
                    }
                    cursor = st.end;
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (skipNextEnd) {
                        skipNextEnd = false;
                        continue;
                    }
                    String tag = reader.getLocalName();
                    if (!stack.isEmpty() && stack.peek().tag().equals(tag)) {
                        int endTag = findEndTag(xml, cursor, tag);
                        if (endTag < 0) {
                            throw new XMLStreamException("could not locate </" + tag + ">");
                        }
                        int end = afterChar(xml, endTag, '>');
                        stack.pop().close(endTag, end);
                        cursor = end;
                    }
                }
            }
        } finally {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {
                // best-effort close
            }
        }

        if (root == null) {
            throw new XMLStreamException("no root element");
        }
        return root;
    }

    private static String attributeValue(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Raw-text scanning
    // ------------------------------------------------------------------

    private record StartTag(boolean selfClosing, int end, List<Attr> attrs) {
    }

    /** Locates the start tag {@code <tag ...} beginning at or after {@code from}. */
    private static int findStartTag(String xml, int from, String tag) {
        String needle = "<" + tag;
        int i = from;
        while (i < xml.length()) {
            int lt = indexOf(xml, '<', i);
            if (lt < 0) {
                return -1;
            }
            if (matchAt(xml, lt, "<!--")) {
                i = after(xml, "-->", lt + 4);
            } else if (matchAt(xml, lt, "<![CDATA[")) {
                i = after(xml, "]]>", lt + 9);
            } else if (matchAt(xml, lt, "<?")) {
                i = after(xml, "?>", lt + 2);
            } else if (matchAt(xml, lt, "</")) {
                i = afterChar(xml, lt + 2, '>');
            } else if (matchAt(xml, lt, needle) && nameBoundary(xml, lt + needle.length())) {
                return lt;
            } else {
                i = lt + 1;
            }
        }
        return -1;
    }

    /** Locates the end tag {@code </tag ...} beginning at or after {@code from}. */
    private static int findEndTag(String xml, int from, String tag) {
        int i = from;
        while (i < xml.length()) {
            int lt = indexOf(xml, '<', i);
            if (lt < 0) {
                return -1;
            }
            if (matchAt(xml, lt, "<!--")) {
                i = after(xml, "-->", lt + 4);
            } else if (matchAt(xml, lt, "<![CDATA[")) {
                i = after(xml, "]]>", lt + 9);
            } else if (matchAt(xml, lt, "<?")) {
                i = after(xml, "?>", lt + 2);
            } else if (matchAt(xml, lt, "</")) {
                int nameStart = lt + 2;
                int nameEnd = nameStart;
                while (nameEnd < xml.length() && !isWhitespace(xml.charAt(nameEnd)) && xml.charAt(nameEnd) != '>') {
                    nameEnd++;
                }
                String name = xml.substring(nameStart, nameEnd);
                if (name.equals(tag)) {
                    return lt;
                }
                i = afterChar(xml, nameEnd, '>');
            } else {
                i = lt + 1;
            }
        }
        return -1;
    }

    /**
     * Scans a start tag starting at {@code start} (which points at {@code <}),
     * returning whether it is self-closing, the offset one past its {@code >},
     * and its attributes with value spans and quote characters.
     */
    private static StartTag scanStartTag(String xml, int start) {
        List<Attr> attrs = new ArrayList<>();
        int i = start + 1;
        // Skip the tag name.
        while (i < xml.length() && !isWhitespace(xml.charAt(i)) && xml.charAt(i) != '>' && xml.charAt(i) != '/') {
            i++;
        }
        while (i < xml.length()) {
            while (i < xml.length() && isWhitespace(xml.charAt(i))) {
                i++;
            }
            if (i >= xml.length()) {
                break;
            }
            char b = xml.charAt(i);
            if (b == '>') {
                return new StartTag(false, i + 1, attrs);
            }
            if (b == '/') {
                if (i + 1 < xml.length() && xml.charAt(i + 1) == '>') {
                    return new StartTag(true, i + 2, attrs);
                }
                i++;
                continue;
            }
            // Attribute name.
            int nameStart = i;
            while (i < xml.length() && !isWhitespace(xml.charAt(i)) && xml.charAt(i) != '='
                    && xml.charAt(i) != '>' && xml.charAt(i) != '/') {
                i++;
            }
            String name = xml.substring(nameStart, i);
            while (i < xml.length() && isWhitespace(xml.charAt(i))) {
                i++;
            }
            if (i >= xml.length() || xml.charAt(i) != '=') {
                i++;
                continue;
            }
            i++;
            while (i < xml.length() && isWhitespace(xml.charAt(i))) {
                i++;
            }
            if (i >= xml.length()) {
                break;
            }
            char quote = xml.charAt(i);
            if (quote != '"' && quote != '\'') {
                i++;
                continue;
            }
            int valueStart = i + 1;
            i++;
            while (i < xml.length() && xml.charAt(i) != quote) {
                i++;
            }
            int valueEnd = i;
            if (i < xml.length()) {
                i++;
            }
            attrs.add(new Attr(name, valueStart, valueEnd, quote));
        }
        // Unreachable for well-formed input.
        return new StartTag(false, start + 1, attrs);
    }

    private static boolean nameBoundary(String xml, int index) {
        return index >= xml.length()
                || isWhitespace(xml.charAt(index))
                || xml.charAt(index) == '>'
                || xml.charAt(index) == '/';
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean matchAt(String xml, int index, String ascii) {
        return index >= 0 && xml.startsWith(ascii, index);
    }

    private static int indexOf(String xml, char needle, int from) {
        return xml.indexOf(needle, Math.max(from, 0));
    }

    private static int indexOf(String xml, String ascii, int from) {
        return xml.indexOf(ascii, Math.max(from, 0));
    }

    private static int after(String xml, String ascii, int from) {
        int i = indexOf(xml, ascii, from);
        return i < 0 ? xml.length() : i + ascii.length();
    }

    private static int afterChar(String xml, int from, char ch) {
        int i = indexOf(xml, ch, from);
        return i < 0 ? xml.length() : i + 1;
    }
}
