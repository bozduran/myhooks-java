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
 * <p>The background covers the <em>whole</em> line — leading indentation, the
 * marker and its separating space, the code, and trailing padding to the output
 * width — so a change reads as a solid bar rather than a colored fragment.
 *
 * <p>When a starting line is supplied, each line carries its absolute file line
 * number in a gutter; removed lines show the original line number and added
 * lines the new one. A single trailing newline on either side is dropped so an
 * edit ending in {@code \n} does not produce a spurious empty line. Color is
 * emitted only when {@code color} is true.
 */
public final class DiffRenderer {

    private static final String RED = "\u001b[41m";
    private static final String GREEN = "\u001b[42m";
    private static final String BLACK = "\u001b[30m";
    private static final String RESET = "\u001b[0m";

    private DiffRenderer() {
    }

    /** Renders without line numbers or width padding. */
    public static String render(String before, String after, String indent, boolean color) {
        return render(before, after, indent, color, 0, 0);
    }

    /**
     * Renders a before/after change.
     *
     * @param line  the 1-based file line of the first before line; {@code <= 0}
     *              omits the line-number gutter
     * @param width the output width to pad changed lines to; {@code <= 0} leaves
     *              the bar at the length of its content
     */
    public static String render(String before, String after, String indent, boolean color, int line, int width) {
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
        int gutter = line > 0 ? gutterWidth(line, beforeLines.size(), afterLines.size()) : 0;

        StringBuilder out = new StringBuilder();
        int index = 0;
        int oldLine = line;
        int newLine = line;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int originalStart = delta.getSource().getPosition();
            while (index < originalStart) {
                appendContext(out, indent, beforeLines.get(index), oldLine, gutter);
                index++;
                oldLine++;
                newLine++;
            }
            for (String text : delta.getSource().getLines()) {
                appendChanged(out, indent, '-', text, oldLine, gutter, red, black, reset, width);
                oldLine++;
                index++;
            }
            for (String text : delta.getTarget().getLines()) {
                appendChanged(out, indent, '+', text, newLine, gutter, green, black, reset, width);
                newLine++;
            }
        }
        while (index < beforeLines.size()) {
            appendContext(out, indent, beforeLines.get(index), oldLine, gutter);
            index++;
            oldLine++;
            newLine++;
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

    private static void appendContext(StringBuilder out, String indent, String text, int line, int gutter) {
        out.append(indent).append(body(line, gutter, ' ', text)).append('\n');
    }

    private static void appendChanged(StringBuilder out, String indent, char marker, String text, int line,
            int gutter, String background, String foreground, String reset, int width) {
        out.append(paint(indent + body(line, gutter, marker, text), background, foreground, reset, width))
                .append('\n');
    }

    /**
     * Wraps {@code visible} in the change colors, padding it to {@code width}
     * first. With no color the text is returned untouched (no trailing spaces).
     */
    private static String paint(String visible, String background, String foreground, String reset, int width) {
        if (background.isEmpty()) {
            return visible;
        }
        String padded = width > visible.length() ? visible + " ".repeat(width - visible.length()) : visible;
        return background + foreground + padded + reset;
    }

    /**
     * The line's visible text: an optional right-aligned line number, the
     * {@code -}/{@code +}/space marker, a space after a change marker, then the
     * code. Context lines put the code right after the space marker (that is
     * their separator), matching unified-diff convention.
     */
    private static String body(int line, int gutter, char marker, String text) {
        StringBuilder b = new StringBuilder();
        if (gutter > 0) {
            String number = Integer.toString(Math.max(line, 1));
            for (int i = number.length(); i < gutter; i++) {
                b.append(' ');
            }
            b.append(number).append(' ');
        }
        b.append(marker);
        if (marker != ' ') {
            b.append(' ');
        }
        b.append(text);
        return b.toString();
    }

    private static int gutterWidth(int line, int beforeCount, int afterCount) {
        int highest = line + Math.max(beforeCount, afterCount) - 1;
        return Math.max(1, Integer.toString(Math.max(highest, line)).length());
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
