package com.myhooks.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The git process runner must not hang the hook or buffer unbounded output.
 */
class ProcessCommandTest {

    @Test
    void killsACommandThatExceedsTheTimeout() {
        assumeTrue(shellAvailable(), "POSIX shell required");
        GitStaged.ProcessCommand command =
                new GitStaged.ProcessCommand(null, Duration.ofMillis(300), 1 << 20);

        IOException error = assertThrows(IOException.class,
                () -> command.run(List.of("/bin/sh", "-c", "sleep 5")));

        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
    }

    @Test
    void rejectsOutputLargerThanTheCap() {
        assumeTrue(shellAvailable(), "POSIX shell required");
        GitStaged.ProcessCommand command =
                new GitStaged.ProcessCommand(null, Duration.ofSeconds(10), 4096);

        IOException error = assertThrows(IOException.class,
                () -> command.run(List.of("/bin/sh", "-c", "yes 0123456789")));

        assertTrue(error.getMessage().contains("exceeded"), error.getMessage());
    }

    @Test
    void returnsOutputForANormalCommand() throws Exception {
        assumeTrue(shellAvailable(), "POSIX shell required");
        GitStaged.ProcessCommand command =
                new GitStaged.ProcessCommand(null, Duration.ofSeconds(10), 1 << 20);

        assertEquals("hi\n", command.run(List.of("/bin/sh", "-c", "echo hi")));
    }

    private static boolean shellAvailable() {
        return new File("/bin/sh").canExecute();
    }
}
