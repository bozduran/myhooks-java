package com.myhooks.steps.format;

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

class FormatDiscovererTest {

    @TempDir
    Path dir;

    @Test
    void addsPositionTypeToTextFieldAndSubreportOnly() throws Exception {
        String in = """
                <jasperReport name="sample" language="java">
                  <detail>
                    <band height="100">
                      <element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
                        <expression><![CDATA["clean"]]></expression>
                      </element>
                      <element kind="subreport" uuid="r1" x="0" y="0" width="100" height="20"/>
                      <element kind="staticText" uuid="s1" x="0" y="0" width="100" height="20">
                        <text><![CDATA[clean]]></text>
                      </element>
                      <element kind="break" uuid="b1" x="0" y="0" width="100" height="1"/>
                    </band>
                  </detail>
                </jasperReport>
                """;
        String out = apply("sample.jrxml", in);

        assertTrue(out.contains("textField\" uuid=\"u1\" positionType=\"Float\""), out);
        assertTrue(out.contains("subreport\" uuid=\"r1\" positionType=\"Float\""), out);
        assertFalse(out.contains("uuid=\"s1\" positionType"), out);
        assertFalse(out.contains("uuid=\"b1\" positionType"), out);
    }

    @Test
    void addsTextAdjustToTextFieldOnly() throws Exception {
        String in = """
                <jasperReport name="sample" language="java">
                  <detail>
                    <band height="100">
                      <element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
                        <expression><![CDATA["clean"]]></expression>
                      </element>
                      <element kind="staticText" uuid="s1" x="0" y="0" width="100" height="20">
                        <text><![CDATA[clean]]></text>
                      </element>
                      <element kind="subreport" uuid="r1" x="0" y="0" width="100" height="20"/>
                    </band>
                  </detail>
                </jasperReport>
                """;
        String out = apply("sample.jrxml", in);

        assertTrue(out.contains("height=\"20\" textAdjust=\"StretchHeight\">"), out);
        assertFalse(out.contains("uuid=\"s1\" textAdjust"), out);
        assertFalse(out.contains("uuid=\"r1\" textAdjust"), out);
    }

    @Test
    void reportsPositionTypeAndTextAdjustAsSeparateGroups() throws Exception {
        String in = """
                <jasperReport name="sample" language="java">
                  <element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
                    <expression><![CDATA["clean"]]></expression>
                  </element>
                </jasperReport>
                """;
        Path file = dir.resolve("sample.jrxml");
        Files.writeString(file, in);
        List<Group> groups = new FormatDiscoverer().discover(context(), file);

        List<String> labels = groups.stream().map(Group::label).toList();
        assertEquals(List.of("positionType", "textAdjust"), labels);
    }

    @Test
    void selfClosingTextFieldGetsBothAttributesWithoutConflictingEdits() throws Exception {
        String in = """
                <jasperReport name="sample" language="java">
                  <element kind="textField" uuid="u1"/>
                </jasperReport>
                """;
        String out = apply("sample.jrxml", in);

        assertTrue(out.contains("textField\" positionType=\"Float\" uuid=\"u1\""
                + " textAdjust=\"StretchHeight\"/>"), out);
    }

    @Test
    void alignsReportName() throws Exception {
        String mismatch = apply("sample.jrxml", "<jasperReport name=\"wrong\" language=\"java\"></jasperReport>");
        assertTrue(mismatch.contains("name=\"sample\""), mismatch);
        assertTrue(!mismatch.contains("name=\"wrong\""), mismatch);

        String match = apply("sample.jrxml", "<jasperReport name=\"sample\" language=\"java\"></jasperReport>");
        assertEquals("<jasperReport name=\"sample\" language=\"java\"></jasperReport>", match);
    }

    @Test
    void addsMissingReportName() throws Exception {
        String out = apply("sample.jrxml", "<jasperReport language=\"java\"></jasperReport>");
        assertTrue(out.contains("<jasperReport name=\"sample\" language=\"java\">"), out);
    }

    @Test
    void formatsExpressionCode() throws Exception {
        String in = """
                <jasperReport name="sample" language="java">
                  <parameter name="p" class="java.lang.String">
                    <defaultValueExpression><![CDATA[(String)IF(a==1?b!=2:c)]]></defaultValueExpression>
                  </parameter>
                </jasperReport>
                """;
        String out = apply("sample.jrxml", in);
        assertTrue(out.contains("<![CDATA[(String) IF(a == 1 ? b != 2 : c)]]>"), out);
    }

    @Test
    void handlesSingleQuotedAttributes() throws Exception {
        String in = """
                <jasperReport name='sample' language='java'>
                  <element kind='textField' uuid='u1' x='0' y='0' width='100' height='20'>
                    <expression><![CDATA["x"]]></expression>
                  </element>
                </jasperReport>
                """;
        String out = apply("sample.jrxml", in);
        assertTrue(out.contains("uuid='u1' positionType=\"Float\""), out);
        assertTrue(out.contains("height='20' textAdjust=\"StretchHeight\">"), out);
    }

    private String apply(String fileName, String content) throws Exception {
        Path file = dir.resolve(fileName);
        Files.writeString(file, content);
        List<Group> groups = new FormatDiscoverer().discover(context(), file);
        EditSet edits = new EditSet();
        for (Group group : groups) {
            for (Fix fix : group.fixes()) {
                fix.apply(edits);
            }
        }
        return edits.apply(content);
    }

    private static Context context() {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        return new Context(new FileDiscovery(), out, out, false, q -> Choice.NO);
    }
}
