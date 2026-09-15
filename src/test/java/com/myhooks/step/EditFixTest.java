package com.myhooks.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.edit.Edit;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.XmlScanner;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The prompt preview must show a change's whole enclosing element, with only
 * the line(s) the edit touches painted as a diff.
 */
class EditFixTest {

    /** {@link com.myhooks.diffui.DiffRenderer}'s diff indent. */
    private static final String INDENT = "          ";

    private static final String FIELD_REPORT = """
            <jasperReport name="t">
              <field name="a" class="java.lang.String">
                <property name="net.sf.jasperreports.json.field.expression" value="a"/>
              </field>
            </jasperReport>
            """;

    @Test
    void wholeElementIsContextAndOnlyTheChangedLineIsMarked() throws Exception {
        Node root = XmlScanner.scan(FIELD_REPORT);
        Node field = root.children().get(0);
        int from = FIELD_REPORT.indexOf("json.field");
        EditFix fix = EditFix.inElement("rename json property",
                new Edit(from, from + "json.field".length(), "jsonql.field"), false, FIELD_REPORT, field);

        assertEquals(INDENT + "2  <field name=\"a\" class=\"java.lang.String\">\n"
                + INDENT + "3 -     <property name=\"net.sf.jasperreports.json.field.expression\" value=\"a\"/>\n"
                + INDENT + "3 +     <property name=\"net.sf.jasperreports.jsonql.field.expression\" value=\"a\"/>\n"
                + INDENT + "4    </field>\n",
                fix.diff());
    }

    @Test
    void wholeElementRemovalIsMarkedAndNeighboursAreNot() throws Exception {
        String raw = "<jasperReport name=\"t\">\n"
                + "  <field name=\"dead\" class=\"java.lang.String\"/>\n"
                + "  <field name=\"live\" class=\"java.lang.String\"/>\n"
                + "</jasperReport>\n";
        Node root = XmlScanner.scan(raw);
        Node dead = root.children().get(0);
        int lineStart = raw.lastIndexOf('\n', dead.startTag()) + 1;
        EditFix fix = EditFix.inContext("delete unused field", new Edit(lineStart, dead.end() + 1, ""),
                false, raw, dead.startTag(), dead.end());

        String diff = fix.diff();
        assertEquals(List.of("-   <field name=\"dead\" class=\"java.lang.String\"/>"), marked(diff, '-'));
        assertTrue(marked(diff, '+').isEmpty(), diff);
        assertFalse(diff.contains("live"), diff);
    }

    @Test
    void colorPaintsOnlyTheChangedLines() throws Exception {
        Node root = XmlScanner.scan(FIELD_REPORT);
        Node field = root.children().get(0);
        int from = FIELD_REPORT.indexOf("json.field");
        String diff = EditFix.inElement("rename", new Edit(from, from + "json.field".length(), "jsonql.field"),
                true, FIELD_REPORT, field).diff();

        long painted = diff.lines().filter(line -> line.contains("\u001b[41m") || line.contains("\u001b[42m")).count();
        assertEquals(2, painted, diff);
        assertTrue(diff.contains("\u001b[41m"), "removed line is red");
        assertTrue(diff.contains("\u001b[42m"), "added line is green");
        // The unchanged <field> and </field> lines carry no color.
        assertTrue(diff.lines().anyMatch(line -> line.contains("<field") && !line.contains("\u001b")), diff);
        assertTrue(diff.lines().anyMatch(line -> line.contains("</field>") && !line.contains("\u001b")), diff);
    }

    @Test
    void rootUnitUsesTheStartTagSoTheWholeDocumentIsNotDumped() throws Exception {
        String raw = "<jasperReport name=\"wrong\" language=\"java\"></jasperReport>";
        Node root = XmlScanner.scan(raw);
        int from = raw.indexOf("wrong");
        String diff = EditFix.inElement("set name", new Edit(from, from + "wrong".length(), "sample"),
                false, raw, root).diff();

        assertTrue(diff.contains("- <jasperReport name=\"wrong\" language=\"java\">"), diff);
        assertTrue(diff.contains("+ <jasperReport name=\"sample\" language=\"java\">"), diff);
        assertFalse(diff.contains("</jasperReport>"), "the end tag must not be part of the preview: " + diff);
    }

    @Test
    void inContextWidensToContainAnEditThatReachesPastTheContext() {
        String raw = "  <field name=\"f\"/>\n";
        // The edit removes the whole line, so the context is widened from the
        // element span out to the indentation and the line terminator.
        EditFix fix = EditFix.inContext("delete", new Edit(0, raw.length(), ""), false,
                raw, raw.indexOf("<field"), raw.indexOf("/>") + 2);

        assertEquals(INDENT + "1 -   <field name=\"f\"/>\n", fix.diff());
    }

    /** The text after the {@code -}/{@code +} marker on each marked line, in order. */
    private static List<String> marked(String diff, char marker) {
        String needle = Character.toString(marker) + " ";
        return diff.lines()
                .filter(line -> line.contains(needle))
                .map(line -> {
                    int at = line.indexOf(needle);
                    return line.substring(at).stripTrailing();
                })
                .toList();
    }
}
