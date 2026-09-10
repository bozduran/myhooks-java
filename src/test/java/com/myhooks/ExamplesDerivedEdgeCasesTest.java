package com.myhooks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.edit.EditSet;
import com.myhooks.step.Context;
import com.myhooks.step.Discoverer;
import com.myhooks.step.Fix;
import com.myhooks.step.Group;
import com.myhooks.steps.clear.ClearDiscoverer;
import com.myhooks.steps.format.FormatDiscoverer;
import com.myhooks.steps.lint.LintStep;
import com.myhooks.steps.sort.SortDiscoverer;
import com.myhooks.steps.textcheck.TextcheckDiscoverer;
import com.myhooks.xmlspan.XmlScanner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end edge cases built from the {@code examples/} reports: each fixture
 * is a valid report deliberately broken in the ways the steps exist to fix
 * (double spaces, unused declarations, unsorted geometry, empty expressions),
 * mixed with the hazards fixed in the A/B series (non-ASCII text, entity names,
 * char literals, text blocks, comments between reordered elements).
 */
class ExamplesDerivedEdgeCasesTest {

    @TempDir
    Path dir;

    @Test
    void textcheckFixesEveryTextEdge() throws Exception {
        String raw = fixture("TextEdgeReport.jrxml");

        String out = apply(new TextcheckDiscoverer(), raw, "");

        // Rendered text: unrenderable apostrophe, double spaces and period-space.
        assertTrue(out.contains("Count : It's today. Swill"), out);
        assertTrue(out.contains("\"today. Swill here\""), out);
        assertTrue(out.contains("\"no space at dot. Dot x\""), out);
        // Non-text literal: period kept, double space collapsed, and the quote in
        // the char literal neither starts a string nor disturbs code spacing.
        assertTrue(out.contains("'\"'  +  \"keep dot.Dot x\""), out);
        // Text block is one literal, not a run of empty strings.
        assertTrue(out.contains("\"\"\"say \"hi\" twice\"\"\""), out);
        // A bare reference is untouched.
        assertTrue(out.contains("$P{ReportTitle}"), out);
        assertFalse(out.contains("today.Swill  here"), out);
        assertFalse(out.contains("\"\"\"say \"hi\"  twice\"\"\""), out);
    }

    @Test
    void clearRemovesUnusedDeclarationsButKeepsTheRest() throws Exception {
        String raw = fixture("ClearEdgeReport.jrxml");

        String out = apply(new ClearDiscoverer(), raw, "");

        assertFalse(out.contains("UnusedParam"), out);
        assertFalse(out.contains("UnusedField"), out);
        assertFalse(out.contains("UnusedVar"), out);
        // The used parameter and entity-named field survive, and the non-ASCII
        // comment before them is untouched.
        assertTrue(out.contains("name=\"ReportTitle\""), out);
        assertTrue(out.contains("café"), out);
        assertTrue(out.contains("name=\"Total&amp;Tax\""), out);
        assertTrue(out.contains("$F{Total&Tax}"), out);
        // The description's ampersand is encoded exactly once in the added jsonql
        // property.
        assertTrue(out.contains("value=\"a &amp; b\""), out);
        assertFalse(out.contains("&amp;amp;"), out);
    }

    @Test
    void sortOrdersElementsAndMovesTriviaWithThem() throws Exception {
        String raw = fixture("SortEdgeReport.jrxml");
        Path file = dir.resolve("SortEdgeReport.jrxml");
        Files.writeString(file, raw);

        String out = apply(new SortDiscoverer(), file, "");

        // Band order by y: image(10) < textField(40) < frame(60).
        int image = out.indexOf("dddddddd-0000-0000-0000-000000000003");
        int text = out.indexOf("dddddddd-0000-0000-0000-000000000002");
        int frame = out.indexOf("dddddddd-0000-0000-0000-000000000004");
        assertTrue(image >= 0 && image < text && text < frame, out);
        // The comment/property that preceded the image move with it, once.
        int comment = out.indexOf("<!-- belongs to the early image -->");
        int property = out.indexOf("com.example.belongs-to-a");
        assertTrue(comment > 0 && comment < image, out);
        assertTrue(property > comment && property < image, out);
        assertEquals(1, count(out, "belongs to the early image"), out);
        assertEquals(1, count(out, "belongs-to-a"), out);
        // Nested frame reordered too, and nothing glued onto one line.
        assertTrue(out.indexOf("dddddddd-0000-0000-0000-000000000006")
                < out.indexOf("dddddddd-0000-0000-0000-000000000005"), out);
        assertFalse(out.contains("/><"), out);
        // Applying again is stable.
        Path once = dir.resolve("SortEdgeReport.once.jrxml");
        Files.writeString(once, out);
        assertEquals(out, apply(new SortDiscoverer(), once, ""));
    }

    @Test
    void lintSurvivesSelfClosingAndEmptyExpressions() throws Exception {
        Path file = dir.resolve("LintEdgeReport.jrxml");
        Files.writeString(file, fixture("LintEdgeReport.jrxml"));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Context context = context(new PrintStream(buffer));

        // The empty <expression/> must not NPE used-name detection.
        assertDoesNotThrow(() -> new ClearDiscoverer().discover(context, file));

        int exit = new LintStep().run(context, List.of(file.toString()));

        assertEquals(0, exit, "lint must never block");
        String out = buffer.toString();
        assertEquals(1, count(out, "printWhenExpression is a constant 'true'"), out);
        assertFalse(out.contains("constant 'false'"), out);
    }

    @Test
    void combinedReportRunsEveryStepAndIsStable() throws Exception {
        Path file = dir.resolve("CombinedReport.jrxml");
        Files.writeString(file, fixture("CombinedReport.jrxml"));

        for (Discoverer step : List.of(new FormatDiscoverer(), new TextcheckDiscoverer(), new ClearDiscoverer())) {
            Files.writeString(file, apply(step, file, ""));
        }
        String out = Files.readString(file);

        assertTrue(out.contains("name=\"CombinedReport\""), out);
        assertFalse(out.contains("UnusedParam"), out);
        assertFalse(out.contains("UnusedField"), out);
        assertTrue(out.contains("and double spaces with It's"), out);
        assertTrue(out.contains("\"a b\""), out);
        assertTrue(out.contains("'\"'  +  \"keep dot. Dot x\""), out);
        assertTrue(out.contains("positionType=\"Float\""), out);
        assertTrue(out.contains("textAdjust=\"StretchHeight\""), out);
        assertDoesNotThrow(() -> XmlScanner.scan(out), out);

        // Every step is idempotent on its own output.
        assertEquals(out, apply(new FormatDiscoverer(), file, ""));
        assertEquals(out, apply(new TextcheckDiscoverer(), file, ""));
        assertEquals(out, apply(new ClearDiscoverer(), file, ""));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = ExamplesDerivedEdgeCasesTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String apply(Discoverer discoverer, String content, String freeform) throws Exception {
        Path file = dir.resolve("case.jrxml");
        Files.writeString(file, content);
        return apply(discoverer, file, freeform);
    }

    private String apply(Discoverer discoverer, Path file, String freeform) throws Exception {
        List<Group> groups = discoverer.discover(context(new PrintStream(new ByteArrayOutputStream())), file);
        EditSet edits = new EditSet();
        for (Group group : groups) {
            for (Fix fix : group.fixes()) {
                fix.apply(edits);
            }
        }
        return edits.apply(Files.readString(file));
    }

    private static Context context(PrintStream out) {
        return new Context(new FileDiscovery(), out, out, false, q -> Choice.NO, q -> "");
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
