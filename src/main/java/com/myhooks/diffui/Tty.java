package com.myhooks.diffui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.jline.utils.NonBlocking;
import org.jline.utils.NonBlockingReader;

/**
 * Access to the controlling terminal ({@code /dev/tty}) for interactive prompts.
 *
 * <p>git runs hooks with stdin bound to {@code /dev/null}, so {@code System.in}
 * cannot be used to ask the user anything — the user's keystrokes only ever
 * arrive on the controlling terminal. This class opens it directly.
 *
 * <p>For arrow-key prompts the terminal must be in raw mode (no canonical line
 * buffering, no echo). JLine's {@code ExternalTerminal}, which is what
 * {@code TerminalBuilder} produces when handed explicit streams, only emulates
 * line discipline in software and never touches the real tty, so raw mode is
 * entered here with {@code stty} and restored on {@link #close()}.
 */
final class Tty implements AutoCloseable {

    private final PrintStream out;
    private final NonBlockingReader rawReader;
    private final BufferedReader lineReader;
    private final String savedSettings; // original stty settings, null when not raw

    private Tty(FileOutputStream output, String savedSettings,
            NonBlockingReader rawReader, BufferedReader lineReader) {
        this.out = new PrintStream(output, true, StandardCharsets.UTF_8);
        this.rawReader = rawReader;
        this.lineReader = lineReader;
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
            NonBlockingReader reader = NonBlocking.nonBlocking(
                    "myhooks-tty", new FileInputStream(tty), StandardCharsets.UTF_8);
            return new Tty(new FileOutputStream(tty), saved, reader, null);
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
            return new Tty(new FileOutputStream(tty), null, null, reader);
        } catch (Exception ignored) {
            return null;
        }
    }

    NonBlockingReader rawReader() {
        return rawReader;
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
        // Closing the reader also closes the underlying /dev/tty input stream,
        // which unblocks the pump thread JLine uses for non-blocking reads.
        try {
            if (rawReader != null) {
                rawReader.close();
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
