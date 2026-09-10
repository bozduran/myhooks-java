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

    /**
     * Decodes XML character/entity references: the five named entities produced
     * by {@link #encode} plus numeric references such as {@code &#95;} and
     * {@code &#x5F;}. Decoding is single-pass (so {@code &amp;lt;} becomes
     * {@code &lt;}, not {@code <}) and an unknown or malformed reference is left
     * verbatim instead of being partially consumed.
     */
    public static String decode(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int consumed = appendDecoded(text, i, out);
            if (consumed > 0) {
                i += consumed;
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** Decodes one reference at {@code index}; returns the chars consumed, or 0. */
    private static int appendDecoded(String text, int index, StringBuilder out) {
        if (text.charAt(index) != '&') {
            return 0;
        }
        int semi = text.indexOf(';', index + 1);
        // Named entities are short; a longer run is not a reference we understand.
        if (semi < 0 || semi - index > 12) {
            return 0;
        }
        String decoded = namedOrNumeric(text.substring(index + 1, semi));
        if (decoded == null) {
            return 0;
        }
        out.append(decoded);
        return semi - index + 1;
    }

    private static String namedOrNumeric(String name) {
        switch (name) {
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            default:
                break;
        }
        if (!name.startsWith("#")) {
            return null;
        }
        try {
            int code = (name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X'))
                    ? Integer.parseInt(name.substring(2), 16)
                    : Integer.parseInt(name.substring(1));
            return Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
