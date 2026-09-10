package com.myhooks.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shells out to {@code git} for the staged and tracked file lists, memoizing
 * each result so git runs once per list. Failures are surfaced as
 * {@link GitException} (never swallowed). There is deliberately no {@code git
 * add} helper: every applied change is left unstaged.
 */
public final class GitStaged {

    /** How long a single git invocation may run before it is killed. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    /** Upper bound on captured git output, so a runaway command cannot OOM the hook. */
    static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024 * 1024;

    /** A single git invocation: full command args → raw output. */
    @FunctionalInterface
    public interface Command {
        String run(List<String> args) throws IOException;
    }

    private final Command command;
    private List<String> staged;
    private List<String> tracked;
    private List<String> modified;

    /** Runs git in the current working directory. */
    public GitStaged() {
        this(new ProcessCommand(null));
    }

    /** Runs git in the given working directory (integration test seam). */
    public GitStaged(Path workingDirectory) {
        this(new ProcessCommand(workingDirectory));
    }

    /** Uses an injected command (unit test seam). */
    public GitStaged(Command command) {
        this.command = command;
    }

    /** Staged files from {@code git diff --cached --name-only --diff-filter=ACMR}. */
    public List<String> staged() {
        if (staged == null) {
            staged = splitLines(run("diff", "--cached", "--name-only", "--diff-filter=ACMR"));
        }
        return staged;
    }

    /** Tracked files from {@code git ls-files -z}. */
    public List<String> tracked() {
        if (tracked == null) {
            tracked = splitNul(run("ls-files", "-z"));
        }
        return tracked;
    }

    /** Files with unstaged changes from {@code git diff --name-only}. */
    public List<String> modified() {
        if (modified == null) {
            modified = splitLines(run("diff", "--name-only"));
        }
        return modified;
    }

    private String run(String... gitArgs) {
        List<String> full = new ArrayList<>();
        full.add("git");
        Collections.addAll(full, gitArgs);
        try {
            return command.run(full);
        } catch (IOException e) {
            throw new GitException("git " + String.join(" ", gitArgs) + " failed", e);
        }
    }

    private static List<String> splitLines(String out) {
        List<String> result = new ArrayList<>();
        for (String line : out.split("\n", -1)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> splitNul(String out) {
        List<String> result = new ArrayList<>();
        for (String path : out.split("\0", -1)) {
            if (!path.isEmpty()) {
                result.add(path);
            }
        }
        return Collections.unmodifiableList(result);
    }

    static final class ProcessCommand implements Command {
        private final Path directory; // null = current directory
        private final Duration timeout;
        private final int maxOutputBytes;

        ProcessCommand(Path directory) {
            this(directory, DEFAULT_TIMEOUT, DEFAULT_MAX_OUTPUT_BYTES);
        }

        /** Test seam: caller-chosen timeout and output cap. */
        ProcessCommand(Path directory, Duration timeout, int maxOutputBytes) {
            this.directory = directory;
            this.timeout = timeout;
            this.maxOutputBytes = maxOutputBytes;
        }

        @Override
        public String run(List<String> args) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(args);
            if (directory != null) {
                builder.directory(directory.toFile());
            }
            builder.redirectErrorStream(true);
            Process process = builder.start();

            AtomicBoolean timedOut = new AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                try {
                    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        timedOut.set(true);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "myhooks-git-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            try {
                String output;
                try {
                    output = readBounded(process.getInputStream(), maxOutputBytes);
                } catch (IOException capOrReadFailure) {
                    process.destroyForcibly();
                    throw capOrReadFailure;
                }
                int exit = process.waitFor();
                if (timedOut.get()) {
                    throw new IOException("git " + String.join(" ", args)
                            + " timed out after " + timeout.toSeconds() + "s");
                }
                if (exit != 0) {
                    throw new IOException("exited " + exit + ": " + output.strip());
                }
                return output;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                watchdog.interrupt();
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        /** Reads at most {@code maxBytes}; exceeding the cap is an error, not OOM. */
        private static String readBounded(InputStream in, int maxBytes) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (out.size() + read > maxBytes) {
                    throw new IOException("git output exceeded " + maxBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
