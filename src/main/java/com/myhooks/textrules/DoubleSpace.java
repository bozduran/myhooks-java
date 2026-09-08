package com.myhooks.textrules;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collapses runs of two or more spaces to a single space.
 */
public final class DoubleSpace {

    private static final Pattern PATTERN = Pattern.compile(" {2,}");

    private DoubleSpace() {
    }

    public static TextResult apply(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int occurrences = 0;
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            occurrences++;
            out.append(text, last, matcher.start());
            out.append(' ');
            last = matcher.end();
        }
        if (occurrences == 0) {
            return TextResult.unchanged(text);
        }
        out.append(text.substring(last));
        return new TextResult(out.toString(), List.of("remove double space (" + occurrencesText(occurrences) + ")"));
    }

    private static String occurrencesText(int n) {
        return n == 1 ? "1 occurrence" : n + " occurrences";
    }
}
