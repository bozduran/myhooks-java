package com.myhooks.diffui;

/**
 * Terminal layout facts: the width used for the section rules and for the
 * full-width background on changed diff lines. The width comes from
 * {@code COLUMNS} when it is a sane value, and is clamped so output stays
 * readable on both narrow and very wide terminals.
 *
 * <p>Tests can pin the width with the {@code myhooks.width} system property.
 */
public final class Output {

    static final int DEFAULT_WIDTH = 52;
    static final int MIN_WIDTH = 40;
    static final int MAX_WIDTH = 100;

    private Output() {
    }

    /** The clamped output width in columns. */
    public static int width() {
        String override = System.getProperty("myhooks.width");
        if (override != null) {
            return clamp(parse(override, DEFAULT_WIDTH));
        }
        return clamp(parse(System.getenv("COLUMNS"), DEFAULT_WIDTH));
    }

    static int clamp(int width) {
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
    }

    static int parse(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
