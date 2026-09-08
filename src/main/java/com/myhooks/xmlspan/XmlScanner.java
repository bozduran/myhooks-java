package com.myhooks.xmlspan;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Builds a byte-offset {@link Node} tree from a JRXML document.
 *
 * <p>Structure (element names, nesting, attribute decoding, self-closing
 * detection) is driven by StAX, which also validates well-formedness. Exact
 * byte spans are computed with a quote-aware scan of the raw bytes, because
 * StAX's {@link javax.xml.stream.Location#getCharacterOffset()} is a character
 * offset (not a byte offset) and is unreliable across implementations.
 */
public final class XmlScanner {

    private XmlScanner() {
    }

    /** Scans {@code raw} and returns the document root {@link Node}. */
    public static Node scan(byte[] raw) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(raw));

        Node root = null;
        Deque<Node> stack = new ArrayDeque<>();
        int cursor = 0;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = reader.getLocalName();
                int startTag = findStartTag(raw, cursor, tag);
                if (startTag < 0) {
                    throw new XMLStreamException("could not locate <" + tag + ">");
                }
                StartTag st = scanStartTag(raw, startTag);
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
                if (!st.selfClosing) {
                    stack.push(node);
                }
                cursor = st.end;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String tag = reader.getLocalName();
                // A self-closing element emits START then END but is never pushed;
                // its END event therefore does not match the stack top.
                if (!stack.isEmpty() && stack.peek().tag().equals(tag)) {
                    int endTag = findEndTag(raw, cursor, tag);
                    if (endTag < 0) {
                        throw new XMLStreamException("could not locate </" + tag + ">");
                    }
                    int end = afterChar(raw, endTag, (byte) '>');
                    stack.pop().close(endTag, end);
                    cursor = end;
                }
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
    // Raw-byte scanning
    // ------------------------------------------------------------------

    private record StartTag(boolean selfClosing, int end, List<Attr> attrs) {
    }

    /** Locates the start tag {@code <tag ...} beginning at or after {@code from}. */
    private static int findStartTag(byte[] raw, int from, String tag) {
        String needle = "<" + tag;
        int i = from;
        while (i < raw.length) {
            int lt = indexOf(raw, (byte) '<', i);
            if (lt < 0) {
                return -1;
            }
            if (matchAt(raw, lt, "<!--")) {
                i = after(raw, "-->", lt + 4);
            } else if (matchAt(raw, lt, "<![CDATA[")) {
                i = after(raw, "]]>", lt + 9);
            } else if (matchAt(raw, lt, "<?")) {
                i = after(raw, "?>", lt + 2);
            } else if (matchAt(raw, lt, "</")) {
                i = afterChar(raw, lt + 2, (byte) '>');
            } else if (matchAt(raw, lt, needle) && nameBoundary(raw, lt + needle.length())) {
                return lt;
            } else {
                i = lt + 1;
            }
        }
        return -1;
    }

    /** Locates the end tag {@code </tag ...} beginning at or after {@code from}. */
    private static int findEndTag(byte[] raw, int from, String tag) {
        int i = from;
        while (i < raw.length) {
            int lt = indexOf(raw, (byte) '<', i);
            if (lt < 0) {
                return -1;
            }
            if (matchAt(raw, lt, "<!--")) {
                i = after(raw, "-->", lt + 4);
            } else if (matchAt(raw, lt, "<![CDATA[")) {
                i = after(raw, "]]>", lt + 9);
            } else if (matchAt(raw, lt, "<?")) {
                i = after(raw, "?>", lt + 2);
            } else if (matchAt(raw, lt, "</")) {
                int nameStart = lt + 2;
                int nameEnd = nameStart;
                while (nameEnd < raw.length && !isWhitespace(raw[nameEnd]) && raw[nameEnd] != '>') {
                    nameEnd++;
                }
                String name = new String(raw, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8);
                if (name.equals(tag)) {
                    return lt;
                }
                i = afterChar(raw, nameEnd, (byte) '>');
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
    private static StartTag scanStartTag(byte[] raw, int start) {
        List<Attr> attrs = new ArrayList<>();
        int i = start + 1;
        // Skip the tag name.
        while (i < raw.length && !isWhitespace(raw[i]) && raw[i] != '>' && raw[i] != '/') {
            i++;
        }
        while (i < raw.length) {
            while (i < raw.length && isWhitespace(raw[i])) {
                i++;
            }
            if (i >= raw.length) {
                break;
            }
            byte b = raw[i];
            if (b == '>') {
                return new StartTag(false, i + 1, attrs);
            }
            if (b == '/') {
                if (i + 1 < raw.length && raw[i + 1] == '>') {
                    return new StartTag(true, i + 2, attrs);
                }
                i++;
                continue;
            }
            // Attribute name.
            int nameStart = i;
            while (i < raw.length && !isWhitespace(raw[i]) && raw[i] != '=' && raw[i] != '>' && raw[i] != '/') {
                i++;
            }
            String name = new String(raw, nameStart, i - nameStart, StandardCharsets.UTF_8);
            while (i < raw.length && isWhitespace(raw[i])) {
                i++;
            }
            if (i >= raw.length || raw[i] != '=') {
                i++;
                continue;
            }
            i++;
            while (i < raw.length && isWhitespace(raw[i])) {
                i++;
            }
            if (i >= raw.length) {
                break;
            }
            byte quote = raw[i];
            if (quote != '"' && quote != '\'') {
                i++;
                continue;
            }
            int valueStart = i + 1;
            i++;
            while (i < raw.length && raw[i] != quote) {
                i++;
            }
            int valueEnd = i;
            if (i < raw.length) {
                i++;
            }
            attrs.add(new Attr(name, valueStart, valueEnd, (char) quote));
        }
        // Unreachable for well-formed input.
        return new StartTag(false, start + 1, attrs);
    }

    private static boolean nameBoundary(byte[] raw, int index) {
        return index >= raw.length
                || isWhitespace(raw[index])
                || raw[index] == '>'
                || raw[index] == '/';
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    private static boolean matchAt(byte[] raw, int index, String ascii) {
        if (index < 0 || index + ascii.length() > raw.length) {
            return false;
        }
        for (int k = 0; k < ascii.length(); k++) {
            if (raw[index + k] != (byte) ascii.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] raw, byte needle, int from) {
        for (int i = Math.max(from, 0); i < raw.length; i++) {
            if (raw[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] raw, String ascii, int from) {
        for (int i = Math.max(from, 0); i + ascii.length() <= raw.length; i++) {
            if (matchAt(raw, i, ascii)) {
                return i;
            }
        }
        return -1;
    }

    private static int after(byte[] raw, String ascii, int from) {
        int i = indexOf(raw, ascii, from);
        return i < 0 ? raw.length : i + ascii.length();
    }

    private static int afterChar(byte[] raw, int from, byte ch) {
        int i = indexOf(raw, ch, from);
        return i < 0 ? raw.length : i + 1;
    }
}
