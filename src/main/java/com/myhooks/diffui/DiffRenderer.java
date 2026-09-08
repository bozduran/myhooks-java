package com.myhooks.diffui;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a before/after change in git-diff style: removed lines are prefixed
 * {@code -} on a red background and added lines {@code +} on a green
 * background, both with black text. Unchanged context lines are emitted with a
 * single space prefix. Multi-line diffs are computed with java-diff-utils.
 *
 * <p>A single trailing newline on either side is dropped so an edit ending in
 * {@code \n} does not produce a spurious empty line. Color is emitted only when
 * {@code color} is true.
 */
public final class DiffRenderer {

    private static final String RED = "\u001b[41m";
    private static final String GREEN = "\u001b[42m";
    private static final String BLACK = "\u001b[30m";
    private static final String RESET = "\u001b[0m";

    private DiffRenderer() {
    }

    public static String render(String before, String after, String indent, boolean color) {
        List<String> beforeLines = splitLines(before);
        List<String> afterLines = splitLines(after);
        if (beforeLines.isEmpty() && afterLines.isEmpty()) {
            return "";
        }

        Patch<String> patch = DiffUtils.diff(beforeLines, afterLines);
        String red = color ? RED : "";
        String green = color ? GREEN : "";
        String black = color ? BLACK : "";
        String reset = color ? RESET : "";

        StringBuilder out = new StringBuilder();
        int originalPosition = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int originalStart = delta.getSource().getPosition();
            while (originalPosition < originalStart) {
                appendContext(out, indent, beforeLines.get(originalPosition));
                originalPosition++;
            }
            for (String line : delta.getSource().getLines()) {
                appendChanged(out, indent, "-", line, red, black, reset);
                originalPosition++;
            }
            for (String line : delta.getTarget().getLines()) {
                appendChanged(out, indent, "+", line, green, black, reset);
            }
        }
        while (originalPosition < beforeLines.size()) {
            appendContext(out, indent, beforeLines.get(originalPosition));
            originalPosition++;
        }
        return out.toString();
    }

    /**
     * Reports whether ANSI color should be emitted: a terminal must be present
     * and {@code NO_COLOR} must be unset.
     */
    public static boolean colorEnabled(boolean tty) {
        return colorEnabled(tty, System.getenv("NO_COLOR"));
    }

    public static boolean colorEnabled(boolean tty, String noColorValue) {
        return tty && noColorValue == null;
    }

    private static void appendContext(StringBuilder out, String indent, String line) {
        out.append(indent).append(' ').append(line).append('\n');
    }

    private static void appendChanged(StringBuilder out, String indent, String prefix, String line,
            String background, String foreground, String reset) {
        out.append(indent).append(background).append(foreground)
                .append(prefix).append(' ').append(line).append(reset).append('\n');
    }

    private static List<String> splitLines(String s) {
        if (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> lines = new ArrayList<>();
        if (s.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                lines.add(s.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(s.substring(start));
        return lines;
    }
}
