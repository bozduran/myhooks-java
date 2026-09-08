package com.myhooks.textrules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces characters that commonly fail to render in JasperReports' default
 * fonts with safe ASCII equivalents (or removes them). The replacement table is
 * fixed and deterministic; each distinct replaced character yields one finding.
 */
public final class Unrenderable {

    private record Replacement(int codePoint, String replacement, String name) {
    }

    private static final Map<Integer, Replacement> TABLE = new LinkedHashMap<>();

    static {
        // invisible spacing / control characters
        put(0x00A0, " ", "non-breaking space");
        put(0x2007, " ", "figure space");
        put(0x2009, " ", "thin space");
        put(0x200A, " ", "hair space");
        put(0x202F, " ", "narrow no-break space");
        put(0x200B, "", "zero-width space");
        put(0x00AD, "", "soft hyphen");

        // dashes and hyphens
        put(0x2010, "-", "hyphen");
        put(0x2011, "-", "non-breaking hyphen");
        put(0x2012, "-", "figure dash");
        put(0x2013, "-", "en dash");
        put(0x2014, "-", "em dash");
        put(0x2015, "-", "horizontal bar");

        // quotes
        put(0x2018, "'", "left single quote");
        put(0x2019, "'", "right single quote");
        put(0x201A, "'", "single low-9 quote");
        put(0x201B, "'", "reversed-9 single quote");
        put(0x201C, "\"", "left double quote");
        put(0x201D, "\"", "right double quote");
        put(0x201E, "\"", "double low-9 quote");
        put(0x201F, "\"", "reversed-9 double quote");

        // misc typography
        put(0x2022, "-", "bullet");
        put(0x2023, "-", "triangular bullet");
        put(0x2026, "...", "ellipsis");
    }

    private Unrenderable() {
    }

    private static void put(int codePoint, String replacement, String name) {
        TABLE.put(codePoint, new Replacement(codePoint, replacement, name));
    }

    public static TextResult apply(String text) {
        StringBuilder out = new StringBuilder(text.length());
        List<String> findings = new ArrayList<>();
        Map<Integer, Boolean> seen = new LinkedHashMap<>();

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            Replacement r = TABLE.get(cp);
            if (r != null) {
                if (seen.put(cp, Boolean.TRUE) == null) {
                    findings.add("replace " + r.name() + " (U+" + String.format("%04X", cp)
                            + ") with " + goQuote(r.replacement()));
                }
                out.append(r.replacement());
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }

        return new TextResult(out.toString(), findings);
    }

    private static String goQuote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
