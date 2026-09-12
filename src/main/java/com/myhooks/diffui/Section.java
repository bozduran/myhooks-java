package com.myhooks.diffui;

/**
 * Renders the dashed banners that delimit output sections.
 *
 * <p>A file header names the step and the file:
 *
 * <pre>
 * ====================================================
 * format · path/to/Foo.jrxml
 * ====================================================
 * </pre>
 *
 * <p>A group banner names one fix-group ("step") and how many fixes it holds:
 *
 * <pre>
 * ----------------------------------------------------
 * positionType (3)
 * ----------------------------------------------------
 * </pre>
 *
 * <p>Both rules are clamped to the output width (see {@link Output}).
 */
public final class Section {

    private static final char GROUP_RULE = '-';
    private static final char FILE_RULE = '=';

    private Section() {
    }

    /** Returns the group banner for {@code label}, terminated by a newline. */
    public static String banner(String label, int width) {
        return rule(GROUP_RULE, width) + "\n" + label + "\n" + rule(GROUP_RULE, width) + "\n";
    }

    /** Returns the file header for {@code title}, terminated by a newline. */
    public static String header(String title, int width) {
        return rule(FILE_RULE, width) + "\n" + title + "\n" + rule(FILE_RULE, width) + "\n";
    }

    private static String rule(char c, int width) {
        return String.valueOf(c).repeat(Math.max(1, width));
    }
}
