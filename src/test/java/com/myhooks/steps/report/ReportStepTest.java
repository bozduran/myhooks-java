package com.myhooks.steps.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.step.Context;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportStepTest {

    @TempDir
    Path dir;

    @Test
    void reportReturnsZeroAndShowsRoot() throws Exception {
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, "");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Context ctx = new Context(new FileDiscovery(),
                new PrintStream(out), new PrintStream(out), false, q -> Choice.NO);

        assertEquals(0, new ReportStep().run(ctx, List.of(file.toString())));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("t.jrxml"), out.toString());
    }
}
