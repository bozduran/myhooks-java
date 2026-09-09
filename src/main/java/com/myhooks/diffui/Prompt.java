package com.myhooks.diffui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;

/**
 * Interactive yes/no/all/skip prompt. On a real terminal it renders
 * arrow-key-selectable options via the controlling terminal in raw mode
 * (default answer No); on a non-terminal it falls back to line-based
 * {@code y/n/a/s} input. The controlling terminal ({@code /dev/tty}) is used
 * rather than stdin because git runs hooks with stdin bound to {@code /dev/null}.
 */
public final class Prompt {

    private Prompt() {
    }

    public static Choice ask(String question) {
        Tty tty = Tty.openRaw();
        if (tty != null) {
            try {
                return askRaw(question, tty);
            } catch (Exception ignored) {
                // fall through to line-based input
            } finally {
                tty.close();
            }
        }
        return askLine(question, new BufferedReader(new InputStreamReader(System.in)), System.out);
    }

    /** Line-based prompt over an injected reader (used by tests). */
    public static Choice ask(String question, Reader in) {
        return askLine(question, toBufferedReader(in), System.out);
    }

    // ------------------------------------------------------------------
    // Line-based fallback
    // ------------------------------------------------------------------

    static Choice askLine(String question, BufferedReader reader, PrintStream out) {
        out.print("  " + question + " [y]es/[n]o/[a]ll/[s]kip (default no): ");
        out.flush();
        String line = readLine(reader);
        if (line == null) {
            out.println("no");
            return Choice.NO;
        }
        return parseLineAnswer(line);
    }

    static Choice parseLineAnswer(String line) {
        switch (line.trim().toLowerCase()) {
            case "y":
            case "yes":
                return Choice.YES;
            case "a":
            case "all":
                return Choice.ALL;
            case "s":
            case "skip":
                return Choice.SKIP;
            default:
                return Choice.NO;
        }
    }

    // ------------------------------------------------------------------
    // Raw terminal mode
    // ------------------------------------------------------------------

    static Choice askRaw(String question, Tty tty) {
        PrintStream out = tty.out();
        int selected = 1; // default answer is No
        while (true) {
            out.print(renderPrompt(question, selected));
            out.flush();
            String key = readKey(tty);
            switch (key) {
                case "left":
                case "up":
                    selected = selected - 1 < 0 ? Choice.values().length - 1 : selected - 1;
                    break;
                case "right":
                case "down":
                    selected = (selected + 1) % Choice.values().length;
                    break;
                case "enter":
                    newline(out);
                    drain(tty);
                    return Choice.values()[selected];
                case "y":
                case "Y":
                    newline(out);
                    drain(tty);
                    return Choice.YES;
                case "n":
                case "N":
                    newline(out);
                    drain(tty);
                    return Choice.NO;
                case "a":
                case "A":
                    newline(out);
                    drain(tty);
                    return Choice.ALL;
                case "s":
                case "S":
                    newline(out);
                    drain(tty);
                    return Choice.SKIP;
                case "":
                    return Choice.NO; // EOF (terminal gone)
                default:
                    break; // ignore ESC / unknown keys
            }
        }
    }

    private static void newline(PrintStream out) {
        out.println();
        out.flush();
    }

    /** Discards typeahead (the rest of "yes"/"no"/"all"/"skip" and the newline). */
    private static void drain(KeySource in) {
        try {
            while (in.readTimed() >= 0) {
                // discard
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Reads one logical key. Returns "" on EOF or read error. A real terminal
     * distinguishes a lone ESC from an arrow key with a short timed read; a
     * plain {@link Reader} (tests) reads the escape sequence directly.
     */
    static String readKey(KeySource in) {
        try {
            int c = in.read();
            if (c < 0) {
                return "";
            }
            if (c == 0x1b) {
                int c1 = in.readTimed();
                if (c1 != '[') {
                    return "esc";
                }
                int c2 = in.readTimed();
                return switch (c2) {
                    case 'C' -> "right";
                    case 'D' -> "left";
                    case 'A' -> "up";
                    case 'B' -> "down";
                    default -> "esc";
                };
            }
            if (c == '\r' || c == '\n') {
                return "enter";
            }
            return String.valueOf((char) c);
        } catch (IOException e) {
            return "";
        }
    }

    /** Renders the option row, inverting the selected option. */
    static String renderPrompt(String question, int selected) {
        StringBuilder sb = new StringBuilder();
        sb.append("\r\u001b[2K").append(question).append("  ");
        Choice[] options = Choice.values();
        for (int i = 0; i < options.length; i++) {
            if (i == selected) {
                sb.append("\u001b[7m").append(options[i].label()).append("\u001b[0m  ");
            } else {
                sb.append(options[i].label()).append("  ");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static BufferedReader toBufferedReader(Reader in) {
        return in instanceof BufferedReader buffered ? buffered : new BufferedReader(in);
    }

    private static String readLine(BufferedReader reader) {
        try {
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
