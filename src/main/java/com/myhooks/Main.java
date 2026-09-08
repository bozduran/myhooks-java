package com.myhooks;

import com.myhooks.diffui.DiffRenderer;
import com.myhooks.diffui.Freeform;
import com.myhooks.diffui.Prompt;
import com.myhooks.diffui.Terminals;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.step.Context;
import com.myhooks.step.FileStep;
import com.myhooks.step.Step;
import com.myhooks.steps.clear.ClearDiscoverer;
import com.myhooks.steps.commitmsg.CommitMsgStep;
import com.myhooks.steps.format.FormatDiscoverer;
import com.myhooks.steps.report.ReportStep;
import com.myhooks.steps.sort.SortDiscoverer;
import com.myhooks.steps.textcheck.TextcheckDiscoverer;
import com.myhooks.steps.validate.ValidateStep;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * picocli CLI entry point. Dispatches over a {@code List<Step>} registry in a
 * data-driven order, honoring the {@code commit-msg} basename, per-step help,
 * unknown-argument validation, and {@code MYHOOKS_DISABLE} toggles.
 */
@Command(name = "myhooks",
        description = "JasperReports pre-commit / commit-msg hook (commitmsg + clear + format + sort + textcheck + validate + report).")
public final class Main {

    private static final List<String> PRE_COMMIT_ORDER = List.of(
            "clear", "format", "sort", "textcheck", "validate", "report");

    private final Map<String, Step> steps;
    private final Context context;
    private final Set<String> disabled;

    public Main(Map<String, Step> steps, Context context, Set<String> disabled) {
        this.steps = steps;
        this.context = context;
        this.disabled = disabled;
    }

    public static void main(String[] args) {
        System.exit(production().execute(args));
    }

    /** Builds the production registry and context. */
    public static Main production() {
        FileDiscovery discovery = new FileDiscovery();
        boolean color = DiffRenderer.colorEnabled(Terminals.available());
        Context context = new Context(discovery, System.out, System.err, color,
                Prompt::ask, Freeform::ask);

        Map<String, Step> steps = new LinkedHashMap<>();
        steps.put("commitmsg", new CommitMsgStep());
        steps.put("clear", new FileStep("clear", "myhooks clear [file.jrxml ...]", new ClearDiscoverer(), context));
        steps.put("format", new FileStep("format", "myhooks format [file.jrxml ...]", new FormatDiscoverer(), context));
        steps.put("sort", new FileStep("sort", "myhooks sort [file.jrxml ...]", new SortDiscoverer(), context));
        steps.put("textcheck", new FileStep("textcheck", "myhooks textcheck [file.jrxml ...]", new TextcheckDiscoverer(), context));
        steps.put("validate", new ValidateStep());
        steps.put("report", new ReportStep());

        return new Main(steps, context, parseDisabled(System.getenv("MYHOOKS_DISABLE")));
    }

    public int execute(String[] args) {
        List<String> list = List.of(args);

        if (invokedAsCommitMsg()) {
            return runStep("commitmsg", list);
        }
        if (list.isEmpty()) {
            return runAll(List.of());
        }

        String first = list.get(0);
        if (first.equals("-h") || first.equals("--help")) {
            printUsage();
            return 0;
        }
        if (steps.containsKey(first)) {
            List<String> rest = list.subList(1, list.size());
            if (!rest.isEmpty() && (rest.get(0).equals("-h") || rest.get(0).equals("--help"))) {
                printStepUsage(first);
                return 0;
            }
            return runStep(first, rest);
        }
        if (isJrxml(first)) {
            return runAll(list);
        }

        context.err().println("myhooks: unknown argument: " + first);
        printUsage();
        return 2;
    }

    private int runStep(String name, List<String> args) {
        Step step = steps.get(name);
        return disabled.contains(name) ? step.check(context, args) : step.run(context, args);
    }

    private int runAll(List<String> args) {
        boolean stop = false;
        for (String name : PRE_COMMIT_ORDER) {
            if (runStep(name, args) != 0) {
                stop = true;
            }
        }
        return stop ? 1 : 0;
    }

    private void printUsage() {
        new CommandLine(this).usage(context.out());
        context.out().println("Usage:");
        context.out().println("  myhooks [file.jrxml ...]          run all enabled steps on the staged files");
        context.out().println("  myhooks <step> [file.jrxml ...]   run one step (commitmsg|clear|format|sort|textcheck|validate|report)");
        context.out().println("  myhooks commitmsg <message-file>  validate the commit message (commit-msg hook)");
    }

    private void printStepUsage(String name) {
        context.out().println(steps.get(name).usage());
    }

    private static boolean isJrxml(String arg) {
        return arg.toLowerCase(Locale.ROOT).endsWith(".jrxml");
    }

    static Set<String> parseDisabled(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    static boolean invokedAsCommitMsg() {
        String command = System.getProperty("sun.java.command", "");
        if (command.isEmpty()) {
            return false;
        }
        String base = command.split("\\s+", 2)[0];
        String name = base.substring(base.lastIndexOf('/') + 1);
        return name.equals("commit-msg") || name.equals("commit-msg.jar");
    }
}
