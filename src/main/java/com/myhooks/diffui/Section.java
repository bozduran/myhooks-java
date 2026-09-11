package com.myhooks.diffui;

/**
 * Renders the dashed banner that separates one fix-group ("step") from the
 * next in the listing and the review TUI.
 *
 * <p>The shape is a rule, the group's name on its own line, and a second rule:
 *
 * <pre>
 * ----------------------------------------------------
 * positionType
 * ----------------------------------------------------
 * </pre>
 */
public final class Section {

    private static final String RULE = "-".repeat(52);

    private Section() {
    }

    /** Returns the banner for {@code label}, terminated by a newline. */
    public static String banner(String label) {
        return RULE + "\n" + label + "\n" + RULE + "\n";
    }
}
