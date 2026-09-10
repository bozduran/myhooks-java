package com.myhooks.steps.lint;

import com.myhooks.git.GitException;
import com.myhooks.io.XmlSource;
import com.myhooks.step.Context;
import com.myhooks.step.Step;
import com.myhooks.steps.lint.rules.ConstantPrintWhen;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.XmlScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The lint step: runs a set of static-analysis {@link Rule}s over each staged
 * (or explicitly named) {@code .jrxml} file and prints any warnings as
 * {@code path:line: message}. It never modifies files and always returns 0, so
 * warnings are informational and do not block the commit.
 */
public final class LintStep implements Step {

    /** The registered rules, in run order. Add a new rule here to enable it. */
    private static final List<Rule> RULES = List.of(new ConstantPrintWhen());

    private final List<Rule> rules;

    public LintStep() {
        this(RULES);
    }

    /** Test seam: run a specific set of rules. */
    LintStep(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public String name() {
        return "lint";
    }

    @Override
    public String usage() {
        return "myhooks lint [file.jrxml ...]";
    }

    @Override
    public int run(Context context, List<String> args) {
        lint(context, args);
        return 0;
    }

    @Override
    public int check(Context context, List<String> args) {
        lint(context, args);
        return 0;
    }

    private void lint(Context context, List<String> args) {
        List<String> files;
        try {
            files = context.discovery().files(args);
        } catch (GitException e) {
            files = List.of();
        }
        if (files.isEmpty()) {
            context.out().println("no .jrxml files to lint.");
            return;
        }
        for (String file : files) {
            lintFile(context, file);
        }
    }

    private void lintFile(Context context, String file) {
        String raw;
        try {
            raw = XmlSource.read(Path.of(file)).text();
        } catch (IOException e) {
            context.err().println("myhooks: " + file + ": " + e.getMessage());
            return;
        }
        Node root;
        try {
            root = XmlScanner.scan(raw);
        } catch (Exception e) {
            context.err().println("myhooks: " + file + ": " + e.getMessage());
            return;
        }
        List<Warning> warnings = new ArrayList<>();
        for (Rule rule : rules) {
            try {
                warnings.addAll(rule.check(root, raw));
            } catch (RuntimeException e) {
                // A broken rule must never take the hook down: lint is
                // informational and always exits 0.
                context.err().println("myhooks: " + file + ": lint rule failed: " + e);
            }
        }
        warnings.sort(Comparator.comparingInt(Warning::offset));
        for (Warning warning : warnings) {
            context.out().println(file + ":" + lineOf(raw, warning.offset()) + ": " + warning.message());
        }
    }

    /** 1-based line number of a byte offset in {@code raw}. */
    private static int lineOf(String raw, int offset) {
        int line = 1;
        int end = Math.min(offset, raw.length());
        for (int i = 0; i < end; i++) {
            if (raw.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
