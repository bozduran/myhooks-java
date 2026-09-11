package com.myhooks.diffui;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Inline terminal UI for reviewing a list of proposed fixes.
 *
 * <p>Renders a compact navigable list once, then an in-place preview block
 * (the selected fix's diff) driven by one-key decisions. The terminal stays in
 * raw mode for key reading; the free-form (jsonql) prompt is handled by briefly
 * returning to cooked mode around each apply, then re-entering raw mode.
 *
 * <p>This is deliberately single-pass over an append-only edit set: applying a
 * fix is irreversible, so decided items are removed from the pending list rather
 * than revisited. Navigation ({@code up}/{@code down}) moves the cursor over the
 * remaining (undecided) fixes.
 */
public final class Review {

    /** One selectable fix: its group, description, pre-rendered diff, and apply action. */
    public record Item(String group, String describe, String diff, Runnable apply) {
    }

    public enum Outcome {
        /** Every fix was decided; the caller writes whatever was applied. */
        COMPLETED,
        /** Skip file: discard pending edits and leave the file unchanged. */
        SKIPPED,
        /** Quit: stop reviewing entirely (and any remaining files). */
        QUIT,
        /** No controlling terminal; the caller should fall back to line prompts. */
        UNAVAILABLE
    }

    private Review() {
    }

    public static Outcome run(List<Item> items, boolean color) {
        Terminal tty = Terminals.openRaw();
        if (tty == null) {
            return Outcome.UNAVAILABLE;
        }
        try {
            // Render to the controlling terminal rather than stdout so the ANSI
            // cursor control is never written into a redirected/piped stream.
            return new Session(items, tty.out(), color, tty).run();
        } finally {
            tty.close();
        }
    }

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    private static final class Session {

        private static final String HELP =
                "  Up/Down move   y/Enter apply   n skip   a rest-of-group   s skip-file   q quit";

        private final List<Item> items;
        private final PrintStream out;
        private final boolean color;
        private final Terminal tty;

        /** Indices into {@link #items} still undecided, in order. */
        private final List<Integer> pending = new ArrayList<>();
        private int cursor;   // index into pending
        private int prevLines; // lines printed by the preview block

        Session(List<Item> items, PrintStream out, boolean color, Terminal tty) {
            this.items = items;
            this.out = out;
            this.color = color;
            this.tty = tty;
            for (int i = 0; i < items.size(); i++) {
                pending.add(i);
            }
        }

        Outcome run() {
            printList();
            while (true) {
                if (pending.isEmpty()) {
                    return Outcome.COMPLETED;
                }
                render();
                switch (tty.readKey()) {
                    case "up":
                        move(-1);
                        break;
                    case "down":
                        move(+1);
                        break;
                    case "y":
                    case "Y":
                    case "enter":
                        decide(true);
                        break;
                    case "n":
                    case "N":
                        decide(false);
                        break;
                    case "a":
                    case "A":
                        applyRestOfGroup();
                        break;
                    case "s":
                    case "S":
                        drain();
                        return Outcome.SKIPPED;
                    case "q":
                    case "Q":
                    case "esc":
                    case "\u0003": // Ctrl-C
                        drain();
                        return Outcome.QUIT;
                    case "":
                        return Outcome.QUIT; // EOF (terminal gone)
                    default:
                        break; // ignore unknown keys
                }
            }
        }

        private Item current() {
            return items.get(pending.get(cursor));
        }

        private void move(int delta) {
            cursor = clamp(cursor + delta);
        }

        private void decide(boolean apply) {
            clearBlock();
            drain();
            if (apply) {
                cookAndRun(current());
            }
            pending.remove(cursor);
            cursor = clamp(cursor);
        }

        private void applyRestOfGroup() {
            clearBlock();
            drain();
            tty.cook();
            try {
                String group = current().group();
                while (!pending.isEmpty() && current().group().equals(group)) {
                    current().apply().run();
                    pending.remove(cursor);
                }
            } finally {
                tty.raw();
            }
            cursor = clamp(cursor);
        }

        /** Cooks the terminal, runs one fix (which may prompt free-form), then re-enters raw mode. */
        private void cookAndRun(Item item) {
            tty.cook();
            try {
                item.apply().run();
            } finally {
                tty.raw();
            }
        }

        private void drain() {
            try {
                while (tty.readTimed() >= 0) {
                    // discard typeahead (rest of "yes"/"no" and any newline)
                }
            } catch (IOException ignored) {
                // best-effort
            }
        }

        private int clamp(int value) {
            if (pending.isEmpty()) {
                return 0;
            }
            return Math.max(0, Math.min(value, pending.size() - 1));
        }

        // ------------------------------------------------------------------
        // Rendering
        // ------------------------------------------------------------------

        /** Prints the compact, non-interactive list of all fixes (no diffs). */
        private void printList() {
            String lastGroup = null;
            for (Item item : items) {
                if (!item.group().equals(lastGroup)) {
                    out.print(Section.banner(item.group()));
                    lastGroup = item.group();
                }
                out.println("    - " + item.describe());
            }
            out.println();
        }

        /** Re-renders the preview block in place (or prints it fresh on first call). */
        private void render() {
            if (prevLines > 0) {
                out.print("\u001b[" + prevLines + "A"); // up to block start
                out.print("\u001b[J");                  // erase to end of screen
            }
            prevLines = printBlock();
            out.flush();
        }

        /** Erases the preview block so a follow-up action can print cleanly. */
        private void clearBlock() {
            if (prevLines > 0) {
                out.print("\u001b[" + prevLines + "A");
                out.print("\u001b[J");
                out.flush();
            }
            prevLines = 0;
        }

        /** Prints the preview block and returns how many lines it occupied. */
        private int printBlock() {
            Item item = current();
            StringBuilder selection = new StringBuilder("  > [")
                    .append(cursor + 1).append('/').append(pending.size())
                    .append("] ").append(item.group()).append(" - ").append(item.describe());
            if (color) {
                out.print("\u001b[1m" + selection + "\u001b[0m");
            } else {
                out.print(selection);
            }
            out.println();
            int lines = 1;

            String diff = item.diff();
            if (diff != null && !diff.isEmpty()) {
                if (!diff.endsWith("\n")) {
                    diff = diff + "\n";
                }
                out.print(diff);
                lines += newlineCount(diff);
            }

            out.println(HELP);
            return lines + 1;
        }

        private static int newlineCount(String s) {
            int count = 0;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '\n') {
                    count++;
                }
            }
            return count;
        }
    }
}
