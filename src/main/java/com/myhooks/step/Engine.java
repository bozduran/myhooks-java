package com.myhooks.step;

import com.myhooks.diffui.Choice;
import com.myhooks.diffui.NoTerminalException;
import com.myhooks.diffui.Review;
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
 */
public final class Engine {

    private final Discoverer discoverer;
    private final Context context;

    public Engine(Discoverer discoverer, Context context) {
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

        boolean stopCommit = false;
        for (String file : files) {
            Path path = Path.of(file);
            // Snapshot before discovery: the edits are computed from this state,
            // and the interactive review can pause for a long time.
            byte[] snapshot = null;
            if (!checkOnly) {
                try {
                    snapshot = Files.readAllBytes(path);
                } catch (IOException e) {
                    context.err().println("myhooks: " + path + ": " + e.getMessage());
                    stopCommit = true;
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
                }
                continue;
            }

            if (groups.isEmpty()) {
                context.out().println("  [ok] " + path);
                continue;
            }

            context.out().println("checking " + path);

            if (checkOnly) {
                list(groups);
                context.out().println("  [report] " + path + ": fixes found (not applied)");
                continue;
            }

            Outcome outcome;
            try {
                outcome = applyInteractively(path, groups, snapshot);
            } catch (NoTerminalException e) {
                context.err().println("myhooks: " + e.getMessage());
                context.err().println("myhooks: no interactive terminal; commit blocked so fixes are not silently skipped.");
                context.err().println("myhooks: run from a terminal, or set MYHOOKS_DISABLE=clear,format,sort,textcheck to skip these steps.");
                return 1;
            }
            if (outcome == Outcome.MODIFIED || outcome == Outcome.FAILED) {
                stopCommit = true;
            }
            if (outcome == Outcome.QUIT) {
                break;
            }
        }
        return stopCommit ? 1 : 0;
    }

    private void list(List<Group> groups) {
        for (Group group : groups) {
            context.out().println("  " + group.label());
            for (Fix fix : group.fixes()) {
                context.out().println("    - " + fix.describe());
                context.out().print(fix.diff());
            }
        }
    }

    private Outcome applyInteractively(Path path, List<Group> groups, byte[] snapshot) {
        if (context.tui()) {
            return applyViaReview(path, groups, snapshot);
        }
        return applySequentially(path, groups, snapshot);
    }

    private Outcome applyViaReview(Path path, List<Group> groups, byte[] snapshot) {
        EditSet edits = new EditSet();
        List<Review.Item> items = new ArrayList<>();
        for (Group group : groups) {
            for (Fix fix : group.fixes()) {
                items.add(new Review.Item(group.label(), fix.describe(), fix.diff(), () -> fix.apply(edits)));
            }
        }

        Review.Outcome outcome = Review.run(items, context.color());
        if (outcome == Review.Outcome.UNAVAILABLE) {
            return applySequentially(path, groups, snapshot);
        }
        if (outcome == Review.Outcome.SKIPPED) {
            context.out().println("  [skip] " + path + " left unchanged");
            return Outcome.UNCHANGED;
        }
        if (outcome == Review.Outcome.QUIT) {
            return Outcome.QUIT;
        }
        return writeIfChanged(path, edits, snapshot);
    }

    /** Line-based per-fix prompting, used when no controlling terminal is available. */
    private Outcome applySequentially(Path path, List<Group> groups, byte[] snapshot) {
        list(groups);

        EditSet edits = new EditSet();
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
                    anyApplied = true;
                    continue;
                }
                Choice choice = context.prompt("Apply " + group.label() + " — " + fix.describe() + "?");
                switch (choice) {
                    case YES:
                        fix.apply(edits);
                        anyApplied = true;
                        break;
                    case ALL:
                        fix.apply(edits);
                        anyApplied = true;
                        restOfGroup = true;
                        break;
                    case SKIP:
                        skipFile = true;
                        break;
                    case NO:
                    default:
                        break;
                }
            }
        }

        if (skipFile) {
            context.out().println("  [skip] " + path + " left unchanged");
            return Outcome.UNCHANGED;
        }
        if (!anyApplied) {
            context.out().println("  [ok] " + path);
            return Outcome.UNCHANGED;
        }
        return writeIfChanged(path, edits, snapshot);
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
