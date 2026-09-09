package com.myhooks.diffui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Access to the controlling terminal ({@code /dev/tty}) for interactive prompts.
 *
 * <p>git runs hooks with stdin bound to {@code /dev/null}, so {@code System.in}
 * cannot be used to ask the user anything — the user's keystrokes only ever
 * arrive on the controlling terminal. This class opens it directly.
 *
 * <p>For arrow-key prompts the terminal must be in raw mode (no canonical line
 * buffering, no echo). Raw mode is entered here with {@code stty} and restored
 * on {@link #close()}. Key reads are single-threaded and blocking; the short
 * escape-sequence timeout is implemented with {@code stty min 0 time 1} rather
 * than a background reader thread, so closing one {@code Tty} can never leave a
 * stale reader competing with the next open (which previously ate the first
 * character of the free-form jsonql answer).
 */
final class Tty implements AutoCloseable, KeySource {

    private final FileInputStream rawIn;      // raw-mode input, null when line mode
    private final BufferedReader lineReader;  // line-mode input, null when raw mode
    private final PrintStream out;
    private final String savedSettings;       // original stty settings, null when not raw

    private Tty(FileInputStream rawIn, BufferedReader lineReader,
            FileOutputStream output, String savedSettings) {
        this.rawIn = rawIn;
        this.lineReader = lineReader;
        this.out = new PrintStream(output, true, StandardCharsets.UTF_8);
        this.savedSettings = savedSettings;
    }

    /** Opens the controlling terminal in raw mode, or {@code null} if unavailable. */
    static Tty openRaw() {
        File tty = new File("/dev/tty");
        if (!tty.canRead() || !tty.canWrite()) {
            return null;
        }
        String saved = null;
        boolean rawSet = false;
        try {
            saved = stty("-g");
            stty("-icanon", "-echo", "min", "1", "time", "0");
            rawSet = true;
            return new Tty(new FileInputStream(tty), null, new FileOutputStream(tty), saved);
        } catch (Exception e) {
            if (rawSet) {
                try {
                    stty(saved);
                } catch (Exception ignored) {
                    // best-effort restore
                }
            }
            return null;
        }
    }

    /** Opens the controlling terminal in cooked (line) mode, or {@code null} if unavailable. */
    static Tty openLine() {
        File tty = new File("/dev/tty");
        if (!tty.canRead() || !tty.canWrite()) {
            return null;
        }
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(tty), StandardCharsets.UTF_8));
            return new Tty(null, reader, new FileOutputStream(tty), null);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Reads one raw byte, blocking until one is available. */
    @Override
    public int read() throws IOException {
        return rawIn.read();
    }

    /**
     * Reads one raw byte with a short timeout, returning -1 when no byte arrives
     * in time. A {@code stty min 0 time 1} poll makes the kernel deliver a 0-byte
     * read (which {@link FileInputStream#read()} reports as -1) after ~100ms when
     * no byte is pending, without involving a second thread.
     */
    @Override
    public int readTimed() throws IOException {
        stty("min", "0", "time", "1");
        try {
            return rawIn.read();
        } finally {
            try {
                stty("min", "1", "time", "0");
            } catch (IOException ignored) {
                // best-effort restore of blocking mode
            }
        }
    }

    /** Reads one logical key (arrow sequence or single char) in raw mode. */
    String readKey() {
        return Prompt.readKey(this);
    }

    /** Restores the terminal to the settings saved when raw mode was entered. */
    void cook() {
        if (savedSettings == null) {
            return;
        }
        try {
            stty(savedSettings);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Re-enters raw mode after a temporary {@link #cook()}. */
    void raw() {
        try {
            stty("-icanon", "-echo", "min", "1", "time", "0");
        } catch (IOException ignored) {
            // best-effort
        }
    }

    BufferedReader lineReader() {
        return lineReader;
    }

    PrintStream out() {
        return out;
    }

    @Override
    public void close() {
        if (savedSettings != null) {
            try {
                stty(savedSettings);
            } catch (Exception ignored) {
                // best-effort restore
            }
        }
        try {
            if (rawIn != null) {
                rawIn.close();
            } else if (lineReader != null) {
                lineReader.close();
            }
        } catch (IOException ignored) {
            // best-effort
        }
        out.close(); // closes the underlying /dev/tty output stream
    }

    /** Runs {@code stty <args>} against {@code /dev/tty}, returning its stdout (trimmed). */
    private static String stty(String... args) throws IOException {
        String[] command = new String[args.length + 1];
        command[0] = "stty";
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectInput(new File("/dev/tty"));
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for stty", e);
        }
        if (code != 0) {
            throw new IOException("stty " + String.join(" ", args) + " exited with " + code);
        }
        return stdout;
    }
}
