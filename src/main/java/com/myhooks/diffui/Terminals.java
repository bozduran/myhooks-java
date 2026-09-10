package com.myhooks.diffui;

import java.io.File;
import java.util.Locale;

/**
 * Platform selection for the controlling terminal, plus the color gate.
 *
 * <p>POSIX uses {@code /dev/tty} and {@code stty}; Windows opens the
 * {@code CONIN$}/{@code CONOUT$} devices and drives them with
 * {@code SetConsoleMode}. This matters because git runs hooks with stdin bound
 * to {@code /dev/null} (POSIX) or {@code NUL} (Windows), so the user's
 * keystrokes never arrive on {@code System.in}.
 */
public final class Terminals {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Set once by {@link #enableAnsi()}; on Windows color needs VT processing. */
    private static volatile boolean ansiEnabled;

    private Terminals() {
    }

    /** Whether ANSI color should be emitted (a terminal must be present). */
    public static boolean available() {
        if (WINDOWS) {
            try {
                return ansiEnabled && WindowsTerminal.consoleAvailable();
            } catch (Throwable nativeFailure) {
                return false;
            }
        }
        if (System.console() != null) {
            return true;
        }
        // git hooks: stdin is /dev/null, but the controlling terminal exists.
        return new File("/dev/tty").exists();
    }

    /** Opens the controlling terminal in raw mode, or {@code null} if unavailable. */
    static Terminal openRaw() {
        if (!WINDOWS) {
            return PosixTerminal.openRaw();
        }
        try {
            return WindowsTerminal.openRaw();
        } catch (Throwable nativeFailure) {
            return null; // falls through to the line prompt / block
        }
    }

    /** Opens the controlling terminal in cooked (line) mode, or {@code null} if unavailable. */
    static Terminal openLine() {
        if (!WINDOWS) {
            return PosixTerminal.openLine();
        }
        try {
            return WindowsTerminal.openLine();
        } catch (Throwable nativeFailure) {
            return null;
        }
    }

    /**
     * Enables ANSI/VT processing on the Windows console so the pre-rendered diff
     * color codes render; a no-op on POSIX, where the terminal handles them.
     */
    public static void enableAnsi() {
        if (!WINDOWS) {
            return;
        }
        try {
            ansiEnabled = WindowsTerminal.enableAnsiOnStdout();
        } catch (Throwable ignored) {
            // best-effort; color is optional
        }
    }

    /** Whether this JVM is running on Windows. */
    static boolean isWindows() {
        return WINDOWS;
    }
}
