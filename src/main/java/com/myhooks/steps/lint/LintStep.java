package com.myhooks.steps.lint;

import com.myhooks.git.GitException;
import com.myhooks.step.Context;
import com.myhooks.step.Step;
import com.myhooks.steps.lint.rules.ConstantPrintWhen;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.XmlScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        return lint(context, args);
    }

    @Override
    public int check(Context context, List<String> args) {
        return lint(context, args);
    }

    private int lint(Context context, List<String> args) {
        List<String> files;
        try {
            files = context.discovery().files(args);
        } catch (GitException e) {
            files = List.of();
        }
        if (files.isEmpty()) {
            context.out().println("no .jrxml files to lint.");
            return 0;
        }
        for (String file : files) {
            lintFile(context, file);
        }
        return 0;
    }

    private void lintFile(Context context, String file) {
        String raw;
        try {
            raw = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            context.err().println("myhooks: " + file + ": " + e.getMessage());
            return;
        }
        Node root;
        try {
            root = XmlScanner.scan(raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            context.err().println("myhooks: " + file + ": " + e.getMessage());
            return;
        }
        List<Warning> warnings = new ArrayList<>();
        for (Rule rule : RULES) {
            warnings.addAll(rule.check(root, raw));
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
