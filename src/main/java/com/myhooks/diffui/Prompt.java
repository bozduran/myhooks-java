package com.myhooks.diffui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Interactive yes/no/all/skip prompt. On a real terminal it renders
 * arrow-key-selectable options via JLine raw mode (default answer No); on a
 * non-terminal it falls back to line-based {@code y/n/a/s} input. The
 * controlling terminal ({@code /dev/tty}) is used as a fallback when stdin is a
 * non-TTY, so prompts still work under {@code git commit}.
 */
public final class Prompt {

    private Prompt() {
    }

    public static Choice ask(String question) {
        Terminal terminal = buildTerminal();
        if (terminal != null) {
            try {
                return askRaw(question, terminal);
            } catch (Exception ignored) {
                // fall through to line-based input
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

    static Choice askRaw(String question, Terminal terminal) {
        Attributes previous = terminal.enterRawMode();
        try {
            int selected = 1; // default answer is No
            while (true) {
                terminal.writer().print(renderPrompt(question, selected));
                terminal.flush();
                String key = readKey(terminal.input());
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
                        newline(terminal);
                        return Choice.values()[selected];
                    case "y":
                    case "Y":
                        newline(terminal);
                        return Choice.YES;
                    case "n":
                    case "N":
                        newline(terminal);
                        return Choice.NO;
                    case "a":
                    case "A":
                        newline(terminal);
                        return Choice.ALL;
                    case "s":
                    case "S":
                        newline(terminal);
                        return Choice.SKIP;
                    case "":
                        return Choice.NO; // EOF (terminal gone)
                    default:
                        break; // ignore ESC / unknown keys
                }
            }
        } finally {
            try {
                terminal.setAttributes(previous);
            } catch (Exception ignored) {
                // best-effort restore
            }
        }
    }

    private static void newline(Terminal terminal) {
        terminal.writer().println();
        terminal.flush();
    }

    /** Reads one logical key; returns "" on EOF or read error. */
    static String readKey(InputStream in) {
        try {
            int b = in.read();
            if (b < 0) {
                return "";
            }
            if (b == 0x1b) {
                int b1 = in.read();
                int b2 = in.read();
                if (b1 != '[') {
                    return "esc";
                }
                switch (b2) {
                    case 'C':
                        return "right";
                    case 'D':
                        return "left";
                    case 'A':
                        return "up";
                    case 'B':
                        return "down";
                    default:
                        return "esc";
                }
            }
            if (b == '\r' || b == '\n') {
                return "enter";
            }
            return String.valueOf((char) b);
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
    // Terminal selection
    // ------------------------------------------------------------------

    private static Terminal buildTerminal() {
        Terminal terminal = trySystemTerminal();
        if (terminal != null) {
            return terminal;
        }
        // git runs hooks with /dev/null as stdin: fall back to the controlling
        // terminal when available (opened for both input and output so JLine can
        // detect it via its file descriptors).
        File tty = new File("/dev/tty");
        if (tty.canRead() && tty.canWrite()) {
            try {
                Terminal t = TerminalBuilder.builder()
                        .streams(new FileInputStream(tty), new FileOutputStream(tty))
                        .dumb(true)
                        .build();
                return Terminal.TYPE_DUMB.equals(t.getType()) ? null : t;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static Terminal trySystemTerminal() {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .dumb(true)
                    .build();
            return Terminal.TYPE_DUMB.equals(terminal.getType()) ? null : terminal;
        } catch (Exception ignored) {
            return null;
        }
    }

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
