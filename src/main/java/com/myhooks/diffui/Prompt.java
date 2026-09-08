package com.myhooks.diffui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import org.jline.utils.NonBlockingReader;

/**
 * Interactive yes/no/all/skip prompt. On a real terminal it renders
 * arrow-key-selectable options via the controlling terminal in raw mode
 * (default answer No); on a non-terminal it falls back to line-based
 * {@code y/n/a/s} input. The controlling terminal ({@code /dev/tty}) is used
 * rather than stdin because git runs hooks with stdin bound to {@code /dev/null}.
 */
public final class Prompt {

    /** How long to wait for the rest of an escape sequence. */
    private static final long ESC_TIMEOUT_MS = 40L;
    /** How long to wait for typeahead when flushing after an answer. */
    private static final long FLUSH_TIMEOUT_MS = 25L;

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
        NonBlockingReader reader = tty.rawReader();
        PrintStream out = tty.out();
        int selected = 1; // default answer is No
        while (true) {
            out.print(renderPrompt(question, selected));
            out.flush();
            String key = readKey(reader);
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
                    drain(reader);
                    return Choice.values()[selected];
                case "y":
                case "Y":
                    newline(out);
                    drain(reader);
                    return Choice.YES;
                case "n":
                case "N":
                    newline(out);
                    drain(reader);
                    return Choice.NO;
                case "a":
                case "A":
                    newline(out);
                    drain(reader);
                    return Choice.ALL;
                case "s":
                case "S":
                    newline(out);
                    drain(reader);
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
    private static void drain(NonBlockingReader in) {
        try {
            while (in.read(FLUSH_TIMEOUT_MS) >= 0) {
                // discard
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Reads one logical key. Returns "" on EOF or read error. A JLine
     * {@link NonBlockingReader} (real terminal) reads escape sequences with a
     * timeout so a lone ESC is not confused with an arrow key; a plain
     * {@link Reader} (tests) reads bytes directly.
     */
    static String readKey(Reader in) {
        if (in instanceof NonBlockingReader nonBlocking) {
            return readKeyNonBlocking(nonBlocking);
        }
        return readKeyBlocking(in);
    }

    private static String readKeyBlocking(Reader in) {
        try {
            int c = in.read();
            if (c < 0) {
                return "";
            }
            if (c == 0x1b) {
                int c1 = in.read();
                int c2 = in.read();
                if (c1 != '[') {
                    return "esc";
                }
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

    private static String readKeyNonBlocking(NonBlockingReader in) {
        try {
            int c = in.read();
            if (c < 0) {
                return "";
            }
            if (c == 0x1b) {
                int c1 = in.peek(ESC_TIMEOUT_MS);
                if (c1 != '[') {
                    return "esc";
                }
                in.read(); // consume '['
                int c2 = in.read(ESC_TIMEOUT_MS);
                if (c2 < 0) {
                    return "esc";
                }
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
