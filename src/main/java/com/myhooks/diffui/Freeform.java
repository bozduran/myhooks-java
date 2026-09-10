package com.myhooks.diffui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;

/**
 * Line-based free-form text prompt. Always reads a single trimmed line; blank
 * or exhausted input returns the empty string. When no controlling terminal is
 * available it throws {@link NoTerminalException} rather than reading the
 * hook's {@code NUL} stdin (which would silently yield an empty answer).
 */
public final class Freeform {

    private Freeform() {
    }

    public static String ask(String question) {
        // git runs hooks with stdin bound to /dev/null (POSIX) or NUL (Windows),
        // so read the controlling terminal when available.
        Terminal tty = Terminals.openLine();
        if (tty != null) {
            try {
                return ask(question, tty.lineReader(), tty.out());
            } finally {
                tty.close();
            }
        }
        if (System.console() != null) {
            return ask(question, new BufferedReader(new InputStreamReader(System.in)), System.out);
        }
        throw new NoTerminalException(question);
    }

    public static String ask(String question, Reader in) {
        return ask(question, in instanceof BufferedReader buffered ? buffered : new BufferedReader(in), System.out);
    }

    static String ask(String question, BufferedReader reader, PrintStream out) {
        out.print("  " + question + " ");
        out.flush();
        String line;
        try {
            line = reader.readLine();
        } catch (IOException e) {
            line = null;
        }
        if (line == null) {
            out.println();
            return "";
        }
        return line.trim();
    }
}
