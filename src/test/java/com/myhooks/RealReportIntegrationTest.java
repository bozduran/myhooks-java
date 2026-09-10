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
import com.myhooks.steps.format.FormatDiscoverer;
import com.myhooks.steps.sort.SortDiscoverer;
import com.myhooks.steps.textcheck.TextcheckDiscoverer;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.XmlScanner;
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
 * Exercises the full pipeline against a real Jaspersoft Studio report, covering
 * nested/self-closing elements, expression formatting, text rules, geometry
 * sort, and unused-declaration detection.
 */
class RealReportIntegrationTest {

    @TempDir
    Path dir;

    private static String report;

    @BeforeAll
    static void loadFixture() throws IOException {
        try (InputStream in = RealReportIntegrationTest.class.getResourceAsStream("/fixtures/Blank_A4_1.jrxml")) {
            report = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void scansRealReportWithoutError() throws Exception {
        Node root = XmlScanner.scan(report);
        assertEquals("jasperReport", root.tag());
        assertEquals("Blank_A4_1", attr(root, "name"));
    }

    @Test
    void formatFormatsExpressionOperators() throws Exception {
        String out = applyAll(new FormatDiscoverer(), report, "");
        assertTrue(out.contains("(32 <= 42)"), out);
        assertTrue(out.contains("? \"no space at dot.Dot\""), out);
        // format leaves string-literal content untouched (that is textcheck's job)
        assertTrue(out.contains("\"test   space in var\""), out);
    }

    @Test
    void textcheckFixesRenderedText() throws Exception {
        String out = applyAll(new TextcheckDiscoverer(), report, "");
        // double space in a variable expression literal (non-text) still collapses
        assertTrue(out.contains("\"test space in var\""), out);
        // double space in a textField literal collapses
        assertTrue(out.contains("\"double space\""), out);
        // ".dasasd" is lowercase, so the period-space rule must NOT fire
        assertTrue(out.contains("\"no space dot textfield.dasasd\""), out);
        // non-text literal keeps its period (JSON-path / code safety)
        assertTrue(out.contains("\"no space at dot.Dot\""), out);
    }

    @Test
    void sortReordersFrameChildren() throws Exception {
        String out = applyAll(new SortDiscoverer(), report, "");
        // frame children sorted by y: subreport(24) < textField(50) < textField(101)
        assertTrue(index(out, "9718dd2d-") < index(out, "1a90522d-"), out);
        assertTrue(index(out, "1a90522d-") < index(out, "bc85dd3b-"), out);
    }

    @Test
    void clearDeletesUnusedFieldAndMigratesQuery() throws Exception {
        String out = applyAll(new ClearDiscoverer(), report, "document.iddata");
        // unused Field_1 deleted; used Field_4 kept
        assertFalse(out.contains("name=\"Field_1\""), out);
        assertTrue(out.contains("name=\"Field_4\""), out);
        // SQL query migrated to jsonql
        assertTrue(out.contains("language=\"jsonql\""), out);
        assertTrue(out.contains("<![CDATA[document.iddata]]>"), out);
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

    private static int index(String text, String needle) {
        return text.indexOf(needle);
    }

    private static String attr(Node node, String name) {
        return com.myhooks.xmlspan.Query.findAttr(node, name)
                .map(a -> report.substring(a.valueStart(), a.valueEnd()))
                .orElse("");
    }
}
