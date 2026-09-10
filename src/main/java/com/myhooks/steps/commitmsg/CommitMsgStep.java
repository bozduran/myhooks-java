package com.myhooks.steps.commitmsg;

import com.myhooks.diffui.Choice;
import com.myhooks.diffui.NoTerminalException;
import com.myhooks.step.Context;
import com.myhooks.step.Step;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.languagetool.JLanguageTool;
import org.languagetool.Languages;
import org.languagetool.UserConfig;
import org.languagetool.rules.RuleMatch;

/**
 * The commit-message step (non-file). Validates the Conventional Commit subject
 * (a "semantic" check that blocks the commit) and reports spelling and grammar
 * issues found by LanguageTool (a real Java spell + grammar checker). Spelling
 * and grammar issues never block the commit: the author may correct them or
 * skip and proceed with the message as-is.
 *
 * <p>Implements {@link Step} directly rather than {@code FileStep}.
 */
public final class CommitMsgStep implements Step {

    private static final List<String> ALLOWED_TYPES = List.of(
            "feat", "fix", "docs", "style", "refactor",
            "perf", "test", "build", "ci", "chore", "revert");

    private static final Pattern SEMANTIC = Pattern.compile(
            "^(" + String.join("|", ALLOWED_TYPES) + ")(\\([^)]+\\))?!?: .+");

    // Technical terms and the Conventional Commit type names are never reported
    // as spelling mistakes (they are not English words but are valid here).
    private static final Set<String> IGNORE_WORDS = Set.of(
            "jsonql", "jrxml", "jasperreports", "subreport", "readme");

    /** One LanguageTool match, classified as spelling or grammar. */
    public record Issue(int from, int to, String message, List<String> suggestions, boolean spelling) {
    }

    /** Lazily initialized, shared across the single-threaded hook invocation. */
    private static final class Engine {
        private static final JLanguageTool TOOL = create();

        private static JLanguageTool create() {
            List<String> ignores = new ArrayList<>(IGNORE_WORDS);
            ignores.addAll(ALLOWED_TYPES);
            JLanguageTool tool = new JLanguageTool(
                    Languages.getLanguageForShortCode("en-US"), null, new UserConfig(ignores));
            // Conventional Commit subjects are lowercase by design, so the
            // sentence-initial capitalization rule is pure noise here.
            tool.disableRule("UPPERCASE_SENTENCE_START");
            return tool;
        }
    }

    @Override
    public String name() {
        return "commitmsg";
    }

    @Override
    public String usage() {
        return "myhooks commitmsg <message-file>";
    }

    @Override
    public int run(Context context, List<String> args) {
        return run(context, args, false);
    }

    @Override
    public int check(Context context, List<String> args) {
        return run(context, args, true);
    }

    private int run(Context context, List<String> args, boolean checkOnly) {
        if (!args.isEmpty() && ("-h".equals(args.get(0)) || "--help".equals(args.get(0)))) {
            context.out().println("myhooks commitmsg: validate the commit message (Conventional Commits + spell/grammar check).");
            context.out().println("Usage: " + usage());
            return 0;
        }
        if (args.isEmpty()) {
            context.err().println("myhooks commitmsg: missing commit message file argument.");
            return 1;
        }

        String path = args.get(0);
        String message;
        try {
            message = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            context.err().println("myhooks commitmsg: " + e.getMessage());
            return 1;
        }

        String semanticError = checkSemantic(message);
        if (semanticError != null) {
            context.out().println("  [semantic] " + semanticError);
            context.out().println("  [semantic] expected: <type>(<scope>)!: <subject>");
            context.out().println("  [semantic] allowed types: " + String.join(", ", ALLOWED_TYPES));
        }

        List<Issue> issues = findIssues(message);
        if (issues.isEmpty()) {
            if (semanticError == null) {
                context.out().println("  [ok] commit message is valid");
            }
        } else {
            for (Issue issue : issues) {
                String original = message.substring(issue.from(), issue.to());
                int line = lineOf(message, issue.from());
                if (issue.spelling()) {
                    String suggestion = issue.suggestions().isEmpty() ? "?" : issue.suggestions().get(0);
                    context.out().printf("  [spell] %s -> %s (line %d)%n",
                            quote(original), quote(suggestion), line);
                } else {
                    String suggestion = issue.suggestions().isEmpty()
                            ? "" : " -> " + quote(issue.suggestions().get(0));
                    context.out().printf("  [grammar] %s%s (line %d)%n", issue.message(), suggestion, line);
                }
            }
        }

        if (checkOnly) {
            return 0;
        }
        // Only the semantic check blocks the commit.
        if (semanticError != null) {
            return 1;
        }
        if (issues.isEmpty()) {
            return 0;
        }

        context.out().println("  Spelling/grammar issues never block the commit.");
        Choice choice;
        try {
            choice = context.prompt("Correct the spelling errors above?");
        } catch (NoTerminalException e) {
            // Spelling must never block, but a missing terminal must be reported
            // loudly rather than looking like a deliberate "No".
            context.err().println("myhooks commitmsg: " + e.getMessage());
            context.err().println("myhooks commitmsg: spelling/grammar issues left as-is (no interactive terminal).");
            return 0;
        }
        switch (choice) {
            case YES:
            case ALL:
                String corrected = correctMessage(message, issues);
                if (!corrected.equals(message)) {
                    try {
                        Files.writeString(Path.of(path), corrected, StandardCharsets.UTF_8);
                        context.out().println("  [fix] spelling corrected in commit message");
                    } catch (IOException e) {
                        context.err().println("myhooks commitmsg: " + e.getMessage());
                        return 1;
                    }
                } else {
                    context.out().println("  [skip] no spelling to auto-correct (grammar issues left for manual review)");
                }
                return 0;
            case SKIP:
            case NO:
            default:
                context.out().println("  [skip] commit message left as-is");
                return 0;
        }
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    // ------------------------------------------------------------------
    // Package-private helpers (unit-tested directly)
    // ------------------------------------------------------------------

    /** Returns the error message when the subject is not a Conventional Commit, else null. */
    static String checkSemantic(String message) {
        String subject = firstLine(message);
        if (subject.isEmpty()) {
            return "commit message is empty";
        }
        if (!SEMANTIC.matcher(subject).matches()) {
            return "subject \"" + subject + "\" is not a Conventional Commit";
        }
        return null;
    }

    /** Runs LanguageTool over the message and returns spelling and grammar issues. */
    static List<Issue> findIssues(String message) {
        List<RuleMatch> matches;
        try {
            matches = Engine.TOOL.check(message);
        } catch (IOException e) {
            throw new IllegalStateException("language check failed", e);
        }
        List<Issue> issues = new ArrayList<>(matches.size());
        for (RuleMatch match : matches) {
            issues.add(new Issue(
                    match.getFromPos(),
                    match.getToPos(),
                    match.getMessage(),
                    List.copyOf(match.getSuggestedReplacements()),
                    match.getRule().isDictionaryBasedSpellingRule()));
        }
        return List.copyOf(issues);
    }

    /** Applies the first suggestion of each spelling issue; grammar issues are left untouched. */
    static String correctMessage(String message, List<Issue> issues) {
        List<Issue> spelling = issues.stream()
                .filter(Issue::spelling)
                .filter(issue -> !issue.suggestions().isEmpty())
                .sorted(Comparator.comparingInt(Issue::from).reversed())
                .toList();
        StringBuilder out = new StringBuilder(message);
        for (Issue issue : spelling) {
            String original = message.substring(issue.from(), issue.to());
            out.replace(issue.from(), issue.to(), applyCase(original, issue.suggestions().get(0)));
        }
        return out.toString();
    }

    private static String firstLine(String message) {
        String trimmed = message.strip();
        int newline = trimmed.indexOf('\n');
        return (newline >= 0 ? trimmed.substring(0, newline) : trimmed).strip();
    }

    private static int lineOf(String text, int pos) {
        int line = 1;
        for (int i = 0; i < pos && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String applyCase(String original, String correction) {
        if (!original.isEmpty() && original.equals(original.toUpperCase())) {
            return correction.toUpperCase();
        }
        if (!original.isEmpty() && Character.isUpperCase(original.charAt(0))) {
            return correction.substring(0, 1).toUpperCase() + correction.substring(1);
        }
        return correction.toLowerCase();
    }
}
