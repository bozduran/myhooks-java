package com.myhooks.xmlspan;

/**
 * A single XML attribute: its name, the byte span of its value (inside the
 * quotes, so entity text such as {@code &amp;} is included verbatim), and the
 * quote character ({@code "} or {@code '}) that wraps the value.
 *
 * <p>Spans are absolute offsets into the same byte array that
 * {@link XmlScanner#scan(byte[])} was given.
 */
public record Attr(String name, int valueStart, int valueEnd, char quote) {
}
