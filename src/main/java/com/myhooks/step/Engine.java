package com.myhooks.step;

import com.myhooks.diffui.Choice;
import com.myhooks.diffui.NoTerminalException;
import com.myhooks.diffui.Output;
import com.myhooks.diffui.Review;
import com.myhooks.diffui.Section;
import com.myhooks.edit.EditException;
import com.myhooks.edit.EditSet;
import com.myhooks.git.GitException;
import com.myhooks.io.XmlSource;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared scaffolding for the file-oriented steps: discover files, list each
 * fix-group, prompt per fix (All = rest of the current group, Skip = whole
 * file), accumulate approved edits, apply them once, and atomic-write the
 * result. On a real terminal the fixes are reviewed through the {@link Review}
 * inline TUI; otherwise a line-based per-fix prompt is used. {@link #check} is
 * report-only and never writes.
 *
 * <p>Every file is announced with a header naming the step and the file, so a
 * run over several steps and files stays readable, and each step prints its
 * decided/applied/stopped counts at the end.
 */
public final class Engine {

    private final String stepName;
    private final Discoverer discoverer;
    private final Context context;

    public Engine(Discoverer discoverer, Context context) {
        this(null, discoverer, context);
    }

    public Engine(String stepName, Discoverer discoverer, Context context) {
        this.stepName = stepName;
        this.discoverer = discoverer;
        this.context = context;
    }

    public int run(List<String> args) {
        return run(args, false);
    }

    public int check(List<String> args) {
        return run(args, true);
    }

    private enum Outcome {
        UNCHANGED, MODIFIED, FAILED, QUIT
    }

    private int run(List<String> args, boolean checkOnly) {
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

        Tally step = new Tally();
        boolean stopCommit = false;
        for (String file : files) {
            Path path = Path.of(file);
            context.out().print(Section.header(title(path), Output.width()));

            // Snapshot before discovery: the edits are computed from this state,
            // and the interactive review can pause for a long time.
            byte[] snapshot = null;
            if (!checkOnly) {
                try {
                    snapshot = Files.readAllBytes(path);
                } catch (IOException e) {
                    context.err().println("myhooks: " + path + ": " + e.getMessage());
                    stopCommit = true;
                    step.stopped(1);
                    continue;
                }
            }
            List<Group> groups;
            try {
                groups = discoverer.discover(context, path);
            } catch (Exception e) {
                context.err().println("myhooks: " + path + ": " + e.getMessage());
                if (!checkOnly) {
                    stopCommit = true;
                    step.stopped(1);
                }
                continue;
            }

            if (groups.isEmpty()) {
                context.out().println("  [ok] " + path);
                continue;
            }

            if (checkOnly) {
                list(groups);
                context.out().println("  [report] " + path + ": fixes found (not applied)");
                continue;
            }

            Outcome outcome;
            try {
                outcome = applyInteractively(path, groups, snapshot, step);
            } catch (NoTerminalException e) {
                context.err().println("myhooks: " + e.getMessage());
                context.err().println("myhooks: no interactive terminal; commit blocked so fixes are not silently skipped.");
                context.err().println("myhooks: run from a terminal, or set MYHOOKS_DISABLE=clear,format,sort,textcheck to skip these steps.");
                return 1;
            }
            if (outcome == Outcome.MODIFIED || outcome == Outcome.FAILED) {
                stopCommit = true;
                step.stopped(1);
            }
            if (outcome == Outcome.QUIT) {
                break;
            }
        }

        context.tally().add(step);
        if (!checkOnly && !step.isEmpty()) {
            context.out().println("  totals: " + step.summary());
        }
        return stopCommit ? 1 : 0;
    }

    private void list(List<Group> groups) {
        int width = Output.width();
        for (Group group : groups) {
            context.out().print(Section.banner(label(group), width));
            for (Fix fix : group.fixes()) {
                context.out().println("    - " + fix.describe());
                context.out().print(fix.diff());
            }
        }
    }

    /** The step/file title used for a file's header banner. */
    private String title(Path path) {
        return stepName == null || stepName.isBlank() ? path.toString() : stepName + " · " + path;
    }

    private static String label(Group group) {
        return group.label() + " (" + group.fixes().size() + ")";
    }

    private Outcome applyInteractively(Path path, List<Group> groups, byte[] snapshot, Tally tally) {
        if (context.tui()) {
            return applyViaReview(path, groups, snapshot, tally);
        }
        return applySequentially(path, groups, snapshot, tally);
    }

    private Outcome applyViaReview(Path path, List<Group> groups, byte[] snapshot, Tally tally) {
        EditSet edits = new EditSet();
        List<Review.Item> items = new ArrayList<>();
        int[] applied = {0};
        for (Group group : groups) {
            for (Fix fix : group.fixes()) {
                items.add(new Review.Item(group.label(), fix.describe(), fix.diff(), () -> {
                    fix.apply(edits);
                    applied[0]++;
                }));
            }
        }

        Review.Outcome outcome = Review.run(items, context.color());
        if (outcome == Review.Outcome.UNAVAILABLE) {
            return applySequentially(path, groups, snapshot, tally);
        }
        if (outcome == Review.Outcome.SKIPPED) {
            context.out().println("  [skip] " + path + " left unchanged");
            tally.skipped(1);
            return Outcome.UNCHANGED;
        }
        if (outcome == Review.Outcome.QUIT) {
            tally.skipped(1);
            return Outcome.QUIT;
        }
        // Whatever the reviewer did not apply was declined; applied fixes only
        // count once the file is actually written.
        tally.skipped(items.size() - applied[0]);
        Outcome written = writeIfChanged(path, edits, snapshot);
        if (written == Outcome.MODIFIED) {
            tally.applied(applied[0]);
        }
        return written;
    }

    /** Line-based per-fix prompting, used when no controlling terminal is available. */
    private Outcome applySequentially(Path path, List<Group> groups, byte[] snapshot, Tally tally) {
        list(groups);

        EditSet edits = new EditSet();
        int approved = 0;
        boolean anyApplied = false;
        boolean skipFile = false;

        outer:
        for (Group group : groups) {
            boolean restOfGroup = false;
            for (Fix fix : group.fixes()) {
                if (skipFile) {
                    break outer;
                }
                if (restOfGroup) {
                    fix.apply(edits);
                    approved++;
                    anyApplied = true;
                    continue;
                }
                Choice choice = context.prompt("Apply " + group.label() + " — " + fix.describe() + "?");
                switch (choice) {
                    case YES:
                        fix.apply(edits);
                        approved++;
                        anyApplied = true;
                        break;
                    case ALL:
                        fix.apply(edits);
                        approved++;
                        anyApplied = true;
                        restOfGroup = true;
                        break;
                    case SKIP:
                        skipFile = true;
                        break;
                    case NO:
                    default:
                        tally.skipped(1);
                        break;
                }
            }
        }

        if (skipFile) {
            context.out().println("  [skip] " + path + " left unchanged");
            tally.skipped(1);
            return Outcome.UNCHANGED;
        }
        if (!anyApplied) {
            context.out().println("  [ok] " + path);
            return Outcome.UNCHANGED;
        }
        // Fixes count as applied only once the file is actually written.
        Outcome written = writeIfChanged(path, edits, snapshot);
        if (written == Outcome.MODIFIED) {
            tally.applied(approved);
        }
        return written;
    }

    private Outcome writeIfChanged(Path path, EditSet edits, byte[] snapshot) {
        try {
            byte[] current = Files.readAllBytes(path);
            if (snapshot != null && !Arrays.equals(snapshot, current)) {
                context.err().println("myhooks: " + path
                        + ": file changed while it was being reviewed; nothing written, re-run the hook.");
                return Outcome.FAILED;
            }
            XmlSource source = XmlSource.of(current);
            String updated = edits.apply(source.text());
            if (updated.equals(source.text())) {
                context.out().println("  [ok] " + path);
                return Outcome.UNCHANGED;
            }
            atomicWrite(path, source.encode(updated));
            context.out().println("  [stop] " + path + ": fixes applied and left UNSTAGED for review.");
            return Outcome.MODIFIED;
        } catch (EditException e) {
            context.err().println("myhooks: " + path + ": " + e.getMessage());
            return Outcome.FAILED;
        } catch (IOException e) {
            // A read/encode/write failure means the promised fix was not applied;
            // block rather than let the commit proceed silently un-fixed.
            context.err().println("myhooks: " + path + ": " + e.getMessage());
            return Outcome.FAILED;
        }
    }

    private static void atomicWrite(Path path, byte[] content) throws IOException {
        // Write through a symlink to its target so the link itself survives;
        // moving the temp file over the link would replace it with a regular file.
        Path target = Files.isSymbolicLink(path) ? path.toRealPath() : path;
        Path absolute = target.toAbsolutePath();
        Path dir = absolute.getParent();
        Path tmp = Files.createTempFile(dir, absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, content);
            try {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(absolute));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem
            }
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
