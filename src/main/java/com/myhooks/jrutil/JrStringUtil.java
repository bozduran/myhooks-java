package com.myhooks.jrutil;

import net.sf.jasperreports.engine.util.JRStringUtil;

/**
 * Thin wrapper over JasperReports' {@link JRStringUtil} so edits stay
 * consistent with the engine's entity handling. {@code JRStringUtil} exposes
 * only encoding; {@link #decode} is the reverse of its five XML entities.
 */
public final class JrStringUtil {

    private JrStringUtil() {
    }

    /** Encodes XML text: {@code & < > " '} become their named entities. */
    public static String encode(String text) {
        return JRStringUtil.xmlEncode(text);
    }

    /** Encodes a value for a double-quoted XML attribute. */
    public static String encodeAttribute(String text) {
        return JRStringUtil.encodeXmlAttribute(text);
    }

    /** Decodes the five named entities produced by {@link #encode}. */
    public static String decode(String text) {
        return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
