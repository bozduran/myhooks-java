package com.myhooks.xmlspan;

/**
 * A single XML attribute: its name, the byte span of its value (inside the
 * quotes, so entity text such as {@code &amp;} is included verbatim), and the
 * quote character ({@code "} or {@code '}) that wraps the value.
 *
 * <p>Spans are absolute character offsets into the same text that
 * {@link XmlScanner#scan(String)} was given.
 */
public record Attr(String name, int valueStart, int valueEnd, char quote) {
}
