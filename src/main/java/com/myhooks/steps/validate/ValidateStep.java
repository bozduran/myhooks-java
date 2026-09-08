package com.myhooks.steps.validate;

import com.myhooks.git.GitException;
import com.myhooks.jrutil.JrCompiler;
import com.myhooks.jrutil.JrSchema;
import com.myhooks.step.Context;
import com.myhooks.step.Step;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.sf.jasperreports.engine.design.JasperDesign;

/**
 * The validation gate that runs after the mutating steps: XSD validation first
 * (fast), then a {@code JasperCompileManager} compile gate (slow) on modified
 * files only. A failure warns and stops the commit. {@code check} is
 * report-only and never stops.
 */
public final class ValidateStep implements Step {

    @Override
    public String name() {
        return "validate";
    }

    @Override
    public String usage() {
        return "myhooks validate [file.jrxml ...]";
    }

    @Override
    public int run(Context context, List<String> args) {
        return validate(context, args, false);
    }

    @Override
    public int check(Context context, List<String> args) {
        return validate(context, args, true);
    }

    private int validate(Context context, List<String> args, boolean checkOnly) {
        List<String> files;
        try {
            files = context.discovery().files(args);
        } catch (GitException e) {
            context.err().println("myhooks: " + e.getMessage());
            return 1;
        }
        if (files.isEmpty()) {
            context.out().println("no .jrxml files to check.");
            return 0;
        }

        // Compile only files explicitly named, or (when discovered from git)
        // those with unstaged changes left by the mutating steps.
        Set<String> toCompile = (args != null && !args.isEmpty())
                ? new HashSet<>(files)
                : new HashSet<>(context.discovery().modified());
        if (args == null || args.isEmpty()) {
            toCompile.retainAll(files);
        }

        boolean failed = false;
        for (String file : files) {
            Path path = Path.of(file);
            try {
                JasperDesign design = JrSchema.validate(Files.readAllBytes(path));
                if (toCompile.contains(file)) {
                    JrCompiler.compile(design);
                }
                context.out().println("  [ok] " + file);
            } catch (Exception e) {
                context.err().println("  [invalid] " + file + ": " + e.getMessage());
                failed = true;
            }
        }
        return (failed && !checkOnly) ? 1 : 0;
    }
}
