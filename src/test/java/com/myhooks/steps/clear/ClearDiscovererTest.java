package com.myhooks.steps.clear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.edit.EditSet;
import com.myhooks.step.Context;
import com.myhooks.step.Fix;
import com.myhooks.step.Group;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClearDiscovererTest {

    @TempDir
    Path dir;

    private static final String HEADER = """
            <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
            """;

    @Test
    void detectsUnusedDeclarations() throws Exception {
        String report = HEADER + """
                  <field name="f" class="java.lang.String"/>
                  <field name="unused" class="java.lang.String"/>
                  <detail>
                    <band height="20">
                      <element kind="textField" uuid="00000000-0000-0000-0000-000000000001" x="0" y="0" width="100" height="20">
                        <expression><![CDATA[$F{f}]]></expression>
                      </element>
                    </band>
                  </detail>
                </jasperReport>
                """;
        Group unused = group(discover(report, ""), "unused declarations");
        List<String> descriptions = unused.fixes().stream().map(Fix::describe).toList();
        assertTrue(descriptions.stream().anyMatch(d -> d.contains("'unused'")), descriptions.toString());
        assertTrue(descriptions.stream().noneMatch(d -> d.contains("'f'")), descriptions.toString());
    }

    @Test
    void migratesSqlQueryWithSingleQuotedLanguage() throws Exception {
        String report = HEADER + """
                  <query language='sql'><![CDATA[SELECT * FROM t]]></query>
                </jasperReport>
                """;
        Group migration = group(discover(report, "document.iddata"), "query migration");
        EditSet edits = new EditSet();
        for (Fix fix : migration.fixes()) {
            fix.apply(edits);
        }
        String out = edits.apply(report);
        assertTrue(out.contains("language='jsonql'"), out);
        assertTrue(out.contains("<![CDATA[document.iddata]]>"), out);
    }

    @Test
    void renamesLegacyJsonProperty() throws Exception {
        String report = HEADER + """
                  <field name="a" class="java.lang.String">
                    <property name="net.sf.jasperreports.json.field.expression" value="a"/>
                  </field>
                """ + reference("a") + "</jasperReport>\n";
        Group jsonql = group(discover(report, ""), "jsonql fixes");
        assertEquals(1, jsonql.fixes().size());
        String out = apply(jsonql.fixes(), report);
        assertTrue(out.contains("net.sf.jasperreports.jsonql.field.expression"), out);
        assertTrue(!out.contains("net.sf.jasperreports.json.field.expression"), out);
    }

    @Test
    void jsonqlRenamePreviewShowsTheWholeField() throws Exception {
        String report = HEADER + """
                  <field name="a" class="java.lang.String">
                    <property name="net.sf.jasperreports.json.field.expression" value="a"/>
                  </field>
                """ + reference("a") + "</jasperReport>\n";
        String diff = group(discover(report, ""), "jsonql fixes").fixes().get(0).diff();

        assertTrue(diff.contains("<field name=\"a\" class=\"java.lang.String\">"), diff);
        assertTrue(diff.contains("</field>"), diff);
        List<String> removed = marked(diff, '-');
        List<String> added = marked(diff, '+');
        assertEquals(1, removed.size(), diff);
        assertEquals(1, added.size(), diff);
        assertTrue(removed.get(0).contains("json.field.expression"), diff);
        assertTrue(added.get(0).contains("jsonql.field.expression"), diff);
    }

    @Test
    void removesLegacyWhenJsonqlAlreadyExists() throws Exception {
        String report = HEADER + """
                  <field name="a" class="java.lang.String">
                    <property name="net.sf.jasperreports.json.field.expression" value="a"/>
                    <property name="net.sf.jasperreports.jsonql.field.expression" value="a"/>
                  </field>
                """ + reference("a") + "</jasperReport>\n";
        Group jsonql = group(discover(report, ""), "jsonql fixes");
        String out = apply(jsonql.fixes(), report);
        assertTrue(!out.contains("net.sf.jasperreports.json.field.expression"), out);
        assertTrue(out.contains("net.sf.jasperreports.jsonql.field.expression"), out);
    }

    @Test
    void addsJsonqlPropertyFromDescription() throws Exception {
        String report = HEADER + """
                  <field name="a" class="java.lang.String">
                    <description><![CDATA[a.b.c]]></description>
                  </field>
                """ + reference("a") + "</jasperReport>\n";
        Group jsonql = group(discover(report, ""), "jsonql fixes");
        String out = apply(jsonql.fixes(), report);
        assertTrue(out.contains("net.sf.jasperreports.jsonql.field.expression\" value=\"a.b.c\""), out);
    }

    @Test
    void syncsDescriptionToJsonql() throws Exception {
        String report = HEADER + """
                  <field name="a" class="java.lang.String">
                    <description><![CDATA[a.b.c]]></description>
                    <property name="net.sf.jasperreports.jsonql.field.expression" value="a.b"/>
                  </field>
                """ + reference("a") + "</jasperReport>\n";
        Group sync = group(discover(report, ""), "description sync");
        String out = apply(sync.fixes(), report);
        assertTrue(out.contains("<![CDATA[a.b]]>"), out);
    }

    @Test
    void discoversFourGroupsForACombinedReport() throws Exception {
        String report = HEADER + """
                  <query language="sql"><![CDATA[SELECT * FROM t]]></query>
                  <field name="dead" class="java.lang.String"/>
                  <field name="mismatch" class="java.lang.String">
                    <description><![CDATA[a.b.c]]></description>
                    <property name="net.sf.jasperreports.jsonql.field.expression" value="a.b"/>
                  </field>
                  <field name="legacy" class="java.lang.String">
                    <property name="net.sf.jasperreports.json.field.expression" value="legacy"/>
                  </field>
                """ + reference("mismatch") + reference("legacy") + "</jasperReport>\n";
        List<Group> groups = discover(report, "document.iddata");
        List<String> labels = groups.stream().map(Group::label).toList();
        assertTrue(labels.contains("query migration"), labels.toString());
        assertTrue(labels.contains("unused declarations"), labels.toString());
        assertTrue(labels.contains("description sync"), labels.toString());
        assertTrue(labels.contains("jsonql fixes"), labels.toString());
    }

    @Test
    void emptyExpressionDoesNotBlockUsedNameDetection() throws Exception {
        String report = HEADER + """
                  <variable name="v" class="java.lang.Integer">
                    <expression/>
                  </variable>
                  <field name="dead" class="java.lang.String"/>
                """ + "</jasperReport>\n";

        Group unused = group(discover(report, ""), "unused declarations");
        String out = apply(unused.fixes(), report);

        assertFalse(out.contains("name=\"dead\""), out);
    }

    private List<Group> discover(String report, String freeformAnswer) throws Exception {
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, report);
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        Context ctx = new Context(new FileDiscovery(), out, out, false,
                q -> Choice.NO, q -> freeformAnswer);
        return new ClearDiscoverer().discover(ctx, file);
    }

    private static Group group(List<Group> groups, String label) {
        return groups.stream().filter(g -> g.label().equals(label)).findFirst().orElseThrow();
    }

    /** The marked lines' text starting at the {@code -}/{@code +} marker. */
    private static List<String> marked(String diff, char marker) {
        String needle = marker + " ";
        return diff.lines()
                .filter(line -> line.contains(needle))
                .map(line -> line.substring(line.indexOf(needle)))
                .toList();
    }

    private static String apply(List<Fix> fixes, String report) {
        EditSet edits = new EditSet();
        for (Fix fix : fixes) {
            fix.apply(edits);
        }
        return edits.apply(report);
    }

    /** A textField that references {@code name}, so the field counts as used. */
    private static String reference(String name) {
        return "    <detail><band height=\"20\">\n"
                + "      <element kind=\"textField\" uuid=\"00000000-0000-0000-0000-000000000001\" x=\"0\" y=\"0\" width=\"100\" height=\"20\">\n"
                + "        <expression><![CDATA[$F{" + name + "}]]></expression>\n"
                + "      </element>\n"
                + "    </band></detail>\n";
    }
}
