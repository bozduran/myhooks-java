package com.myhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.edit.EditSet;
import com.myhooks.step.Context;
import com.myhooks.step.Discoverer;
import com.myhooks.step.Fix;
import com.myhooks.step.Group;
import com.myhooks.steps.clear.ClearDiscoverer;
import com.myhooks.steps.textcheck.TextcheckDiscoverer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the byte-vs-character offset defect: a multi-byte UTF-8
 * character before an edit must not shift the edit's target.
 *
 * <p>The fixture is derived from {@code examples/MarkupReport.jrxml} by adding
 * a non-ASCII comment, an unused field, and a double space in an expression.
 */
class UnicodeOffsetIntegrationTest {

    @TempDir
    Path dir;

    private static String report;

    @BeforeAll
    static void loadFixture() throws IOException {
        try (InputStream in = UnicodeOffsetIntegrationTest.class.getResourceAsStream("/fixtures/UnicodeReport.jrxml")) {
            report = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void fixtureActuallyContainsMultiByteText() {
        assertTrue(report.getBytes(StandardCharsets.UTF_8).length > report.length(),
                "fixture must contain multi-byte UTF-8 for these tests to be meaningful");
    }

    @Test
    void clearRemovesUnusedFieldAfterUnicodeWithoutCorruption() throws Exception {
        String out = applyAll(new ClearDiscoverer(), report, "");
        String expected = report.replace("\t<field name=\"Unused\" class=\"java.lang.String\"/>\n", "");
        assertEquals(expected, out);
        assertFalse(out.contains("name=\"Unused\""), out);
        assertTrue(out.contains("café"), out);
    }

    @Test
    void textcheckCollapsesDoubleSpaceAfterUnicodeWithoutCorruption() throws Exception {
        String out = applyAll(new TextcheckDiscoverer(), report, "");
        String expected = report.replace("\"double  space\"", "\"double space\"");
        assertEquals(expected, out);
        assertTrue(out.contains("café"), out);
    }

    private String applyAll(Discoverer discoverer, String content, String freeform) throws Exception {
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, content);
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        Context ctx = new Context(new FileDiscovery(), out, out, false, q -> Choice.NO, q -> freeform);
        List<Group> groups = discoverer.discover(ctx, file);
        EditSet edits = new EditSet();
        for (Group group : groups) {
            for (Fix fix : group.fixes()) {
                fix.apply(edits);
            }
        }
        return edits.apply(content);
    }
}
