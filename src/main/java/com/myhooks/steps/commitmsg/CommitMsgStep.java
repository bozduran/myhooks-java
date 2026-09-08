package com.myhooks.steps.commitmsg;

import com.myhooks.diffui.Choice;
import com.myhooks.step.Context;
import com.myhooks.step.Step;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The commit-message step (non-file). Validates the Conventional Commit subject
 * and flags common misspellings using the bundled misspell dictionary. It
 * implements {@link Step} directly rather than {@code FileStep}.
 */
public final class CommitMsgStep implements Step {

    private static final List<String> ALLOWED_TYPES = List.of(
            "feat", "fix", "docs", "style", "refactor",
            "perf", "test", "build", "ci", "chore", "revert");

    private static final Pattern SEMANTIC = Pattern.compile(
            "^(" + String.join("|", ALLOWED_TYPES) + ")(\\([^)]+\\))?!?: .+");

    private static final Pattern WORD = Pattern.compile("[A-Za-z]+");

    // Technical terms to never flag as typos (mirrors the Go ignoreRules).
    private static final Set<String> IGNORE_RULES = Set.of(
            "jsonql", "jrxml", "jasperreports", "subreport");

    private static final Map<String, String> DICTIONARY = loadDictionary();

    public record Typo(String original, String corrected, int line) {
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
            context.out().println("myhooks commitmsg: validate the commit message (Conventional Commits + typo check).");
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

        List<Typo> typos = findTypos(message);
        if (typos.isEmpty()) {
            if (semanticError == null) {
                context.out().println("  [ok] commit message is valid");
            }
        } else {
            for (Typo typo : typos) {
                context.out().printf("  [typo] %s -> %s (line %d)%n",
                        quote(typo.original()), quote(typo.corrected()), typo.line());
            }
        }

        if (checkOnly) {
            return 0;
        }
        if (semanticError != null) {
            return 1;
        }
        if (typos.isEmpty()) {
            return 0;
        }

        context.out().println("  Correct the typo(s)? yes = correct, no = stop commit, skip = accept as-is.");
        Choice choice = context.prompt("Correct the typo(s) above?");
        switch (choice) {
            case YES:
            case ALL:
                String corrected = correctMessage(message);
                if (!corrected.equals(message)) {
                    try {
                        Files.writeString(Path.of(path), corrected, StandardCharsets.UTF_8);
                        context.out().println("  [fix] typos corrected in commit message");
                    } catch (IOException e) {
                        context.err().println("myhooks commitmsg: " + e.getMessage());
                        return 1;
                    }
                }
                return 0;
            case SKIP:
                context.out().println("  [skip] commit message left as-is");
                return 0;
            case NO:
            default:
                context.err().println("\nmyhooks commitmsg: commit stopped — fix the typos, or re-run and choose skip.");
                return 1;
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

    static List<Typo> findTypos(String message) {
        List<Typo> typos = new ArrayList<>();
        String[] lines = message.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = WORD.matcher(lines[i]);
            while (matcher.find()) {
                String word = matcher.group();
                String lower = word.toLowerCase();
                String correction = IGNORE_RULES.contains(lower) ? null : DICTIONARY.get(lower);
                if (correction != null) {
                    typos.add(new Typo(word, applyCase(word, correction), i + 1));
                }
            }
        }
        return typos;
    }

    static String correctMessage(String message) {
        String[] lines = message.split("\n", -1);
        StringBuilder out = new StringBuilder(message.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(correctLine(lines[i]));
        }
        return out.toString();
    }

    private static String correctLine(String line) {
        Matcher matcher = WORD.matcher(line);
        StringBuilder out = new StringBuilder(line.length());
        while (matcher.find()) {
            String word = matcher.group();
            String replacement = word;
            String lower = word.toLowerCase();
            if (!IGNORE_RULES.contains(lower)) {
                String correction = DICTIONARY.get(lower);
                if (correction != null) {
                    replacement = applyCase(word, correction);
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String firstLine(String message) {
        String trimmed = message.strip();
        int newline = trimmed.indexOf('\n');
        return (newline >= 0 ? trimmed.substring(0, newline) : trimmed).strip();
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

    private static Map<String, String> loadDictionary() {
        Map<String, String> dictionary = new HashMap<>();
        try (InputStream in = CommitMsgStep.class.getResourceAsStream("misspell.dict")) {
            if (in == null) {
                throw new IllegalStateException("misspell.dict not found on classpath");
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    dictionary.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to load misspell.dict", e);
        }
        dictionary.entrySet().removeIf(entry ->
                IGNORE_RULES.contains(entry.getKey()) || IGNORE_RULES.contains(entry.getValue()));
        return Map.copyOf(dictionary);
    }
}
