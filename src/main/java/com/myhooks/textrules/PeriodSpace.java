package com.myhooks.textrules;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds a space after a {@code .} that is directly followed by an uppercase
 * letter ({@code today.Swill} → {@code today. Swill}). Decimal points and
 * already-spaced sentences are left untouched.
 */
public final class PeriodSpace {

    private static final Pattern PATTERN = Pattern.compile("\\.([A-Z])");

    private PeriodSpace() {
    }

    public static TextResult apply(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int occurrences = 0;
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            occurrences++;
            out.append(text, last, matcher.start());
            out.append(". ").append(matcher.group(1));
            last = matcher.end();
        }
        if (occurrences == 0) {
            return TextResult.unchanged(text);
        }
        out.append(text.substring(last));
        return new TextResult(out.toString(), List.of(finding(occurrences)));
    }

    private static String finding(int occurrences) {
        return "add space after '.' (" + occurrencesText(occurrences) + ")";
    }

    private static String occurrencesText(int n) {
        return n == 1 ? "1 occurrence" : n + " occurrences";
    }
}
