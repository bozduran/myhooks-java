package com.myhooks.step;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import java.io.PrintStream;
import java.util.function.Function;

/**
 * Shared state handed to every step and discoverer: file discovery, output
 * streams, color gating, and the interactive prompt.
 */
public final class Context {

    private final FileDiscovery discovery;
    private final PrintStream out;
    private final PrintStream err;
    private final boolean color;
    private final boolean tui;
    private final Function<String, Choice> prompt;
    private final Function<String, String> freeform;
    private final Tally tally = new Tally();

    public Context(FileDiscovery discovery, PrintStream out, PrintStream err,
            boolean color, Function<String, Choice> prompt) {
        this(discovery, out, err, color, false, prompt, question -> "");
    }

    public Context(FileDiscovery discovery, PrintStream out, PrintStream err,
            boolean color, Function<String, Choice> prompt, Function<String, String> freeform) {
        this(discovery, out, err, color, false, prompt, freeform);
    }

    public Context(FileDiscovery discovery, PrintStream out, PrintStream err,
            boolean color, boolean tui, Function<String, Choice> prompt, Function<String, String> freeform) {
        this.discovery = discovery;
        this.out = out;
        this.err = err;
        this.color = color;
        this.tui = tui;
        this.prompt = prompt;
        this.freeform = freeform;
    }

    public FileDiscovery discovery() {
        return discovery;
    }

    public PrintStream out() {
        return out;
    }

    public PrintStream err() {
        return err;
    }

    public boolean color() {
        return color;
    }

    /** Whether the inline review TUI may be used (production) instead of line prompts (tests). */
    public boolean tui() {
        return tui;
    }

    /** Run-wide counts, accumulated by each step and reported by the CLI. */
    public Tally tally() {
        return tally;
    }

    public Choice prompt(String question) {
        return prompt.apply(question);
    }

    public String freeform(String question) {
        return freeform.apply(question);
    }
}
