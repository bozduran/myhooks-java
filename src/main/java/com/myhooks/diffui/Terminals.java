package com.myhooks.diffui;

import java.io.File;

/**
 * Terminal availability checks. Used for color gating: ANSI color should be
 * emitted when the output is a terminal, which is the case both when run
 * directly and under {@code git commit} (stdin is {@code /dev/null} there, but
 * stdout is still the terminal).
 */
public final class Terminals {

    private Terminals() {
    }

    public static boolean available() {
        if (System.console() != null) {
            return true;
        }
        // git hooks: stdin is /dev/null, but the controlling terminal exists.
        File tty = new File("/dev/tty");
        return tty.exists();
    }
}
