package com.myhooks.step;

import com.myhooks.diffui.Choice;
import com.myhooks.edit.EditSet;
import com.myhooks.git.GitException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Shared scaffolding for the file-oriented steps: discover files, list each
 * fix-group, prompt per fix (All = rest of the current group, Skip = whole
 * file), accumulate approved edits, apply them once, and atomic-write the
 * result. {@link #check} is report-only and never writes.
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
            list(groups);

            if (checkOnly) {
                context.out().println("  [report] " + path + ": fixes found (not applied)");
                continue;
            }

            if (applyInteractively(path, groups)) {
                stopCommit = true;
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

    /** Returns true when the file was modified. */
    private boolean applyInteractively(Path path, List<Group> groups) {
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
            return false;
        }
        if (!anyApplied) {
            context.out().println("  [ok] " + path);
            return false;
        }

        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            String updated = edits.apply(raw);
            if (updated.equals(raw)) {
                context.out().println("  [ok] " + path);
                return false;
            }
            atomicWrite(path, updated);
            context.out().println("  [stop] " + path + ": fixes applied and left UNSTAGED for review.");
            return true;
        } catch (IOException e) {
            context.err().println("myhooks: " + path + ": " + e.getMessage());
            return false;
        }
    }

    private static void atomicWrite(Path path, String content) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path dir = absolute.getParent();
        Path tmp = Files.createTempFile(dir, absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(path));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
