package com.myhooks.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitStagedTest {

    @Test
    void stagedSplitsNewlinesAndTrimsBlanks() {
        GitStaged git = new GitStaged(args -> "a.jrxml\n\n  b.jrxml  \n");
        assertEquals(List.of("a.jrxml", "b.jrxml"), git.staged());
    }

    @Test
    void trackedSplitsNul() {
        GitStaged git = new GitStaged(args -> "a.jrxml\0b.xml\0c.JRXML\0");
        assertEquals(List.of("a.jrxml", "b.xml", "c.JRXML"), git.tracked());
    }

    @Test
    void memoizesEachGitRun() {
        AtomicInteger calls = new AtomicInteger();
        GitStaged git = new GitStaged(args -> {
            calls.incrementAndGet();
            return "a.jrxml\n";
        });
        git.staged();
        git.staged();
        assertEquals(1, calls.get());
    }

    @Test
    void surfacesGitErrors() {
        GitStaged git = new GitStaged(args -> {
            throw new IOException("boom");
        });
        assertThrows(GitException.class, git::staged);
    }

    @Test
    void readsStagedAndTrackedFromRealRepo(@TempDir Path dir) throws Exception {
        run(dir, "git", "init", "-q");
        run(dir, "git", "config", "user.email", "t@example.com");
        run(dir, "git", "config", "user.name", "T");
        Files.writeString(dir.resolve("a.jrxml"), "x");
        Files.writeString(dir.resolve("b.txt"), "y");
        run(dir, "git", "add", "a.jrxml", "b.txt");

        GitStaged git = new GitStaged(dir);
        assertEquals(List.of("a.jrxml", "b.txt"), git.staged());
        assertEquals(List.of("a.jrxml", "b.txt"), git.tracked());
    }

    private static void run(Path dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out);
        }
    }
}
