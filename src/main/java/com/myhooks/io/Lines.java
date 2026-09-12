package com.myhooks.io;

/**
 * Line-number helpers over a raw document. Offsets are character offsets in the
 * same text the {@code xmlspan} scanner was given, so a reported line number is
 * the real file line an edit touches.
 */
public final class Lines {

    private Lines() {
    }

    /** The 1-based line number containing {@code offset}. */
    public static int lineOf(String text, int offset) {
        int line = 1;
        int limit = Math.min(Math.max(offset, 0), text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
