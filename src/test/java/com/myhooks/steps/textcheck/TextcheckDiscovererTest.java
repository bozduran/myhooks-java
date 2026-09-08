package com.myhooks.steps.textcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextcheckDiscovererTest {

    @TempDir
    Path dir;

    @Test
    void transformTextAppliesPeriodDoubleSpaceAndUnrenderable() {
        List<String> findings = new ArrayList<>();
        assertEquals("today. Swill", transformText("today.Swill", "", findings));
        assertEquals("a b", transformText("a  b", "", findings));
        assertEquals("It's", transformText("It\u2019s", "", findings));
    }

    @Test
    void transformTextAppliesUnrenderableBeforeDoubleSpace() {
        List<String> findings = new ArrayList<>();
        assertEquals("a b", transformText("a \u00A0b", "", findings));
        assertTrue(findings.stream().anyMatch(f -> f.startsWith("replace non-breaking space")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.startsWith("remove double space")), findings.toString());
    }

    @Test
    void transformTextNormalizesNewlineToMarkup() {
        assertEquals("a<br/>b", transformText("a\\nb", "styled", new ArrayList<>()));
        assertEquals("a\\nb", transformText("a<br>b", "none", new ArrayList<>()));
        assertEquals("a\\nb", transformText("a\\nb", "rtf", new ArrayList<>()));
    }

    @Test
    void transformExpressionOnlyTouchesStringLiterals() {
        List<String> findings = new ArrayList<>();
        assertEquals("\"today. Swill\"", transformExpr("\"today.Swill\"", true, "", findings));
        assertEquals("\"a b\" + $F{x}", transformExpr("\"a  b\" + $F{x}", true, "", findings));
        assertEquals("$F{x} == 1 && $F{y} != 2", transformExpr("$F{x} == 1 && $F{y} != 2", false, "", findings));
    }

    @Test
    void transformExpressionNonTextKeepsPeriod() {
        List<String> findings = new ArrayList<>();
        assertEquals("\"foo.Bar baz\"", transformExpr("\"foo.Bar  baz\"", false, "", findings));
        assertEquals("\"asd \"+$V{Variable_2}+\"s ds ds\"",
                transformExpr("\"asd  \"+$V{Variable_2}+\"s  ds  ds\"", false, "", findings));
    }

    @Test
    void discoverFindsTextChanges() throws Exception {
        String report = """
                <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                    leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                  <detail>
                    <band height="40">
                      <element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
                        <expression><![CDATA["today.Swill  here"]]></expression>
                      </element>
                      <element kind="staticText" uuid="u2" x="0" y="20" width="100" height="20">
                        <text><![CDATA[It\u2019s a test\u2014okay. Done]]></text>
                      </element>
                    </band>
                  </detail>
                </jasperReport>
                """;
        List<Group> groups = discover(report);
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).fixes().size());

        String out = apply(groups, report);
        assertTrue(out.contains("\"today. Swill here\""), out);
        assertTrue(out.contains("It's a test-okay. Done"), out);
    }

    @Test
    void discoverNonTextKeepsPeriod() throws Exception {
        String report = """
                <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                    leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                  <detail>
                    <band height="20">
                      <element kind="image" uuid="u1" x="0" y="0" width="100" height="20">
                        <expression><![CDATA["foo.Bar"]]></expression>
                      </element>
                    </band>
                  </detail>
                </jasperReport>
                """;
        assertTrue(discover(report).isEmpty(), "non-text expression with no double space must produce no fixes");
    }

    @Test
    void discoverNonTextStillCollapsesDoubleSpace() throws Exception {
        String report = """
                <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                    leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                  <detail>
                    <band height="20">
                      <element kind="subreport" uuid="u1" x="0" y="0" width="100" height="20">
                        <expression><![CDATA["asd  "+$V{Variable_2}+"s  ds  ds"]]></expression>
                      </element>
                    </band>
                  </detail>
                </jasperReport>
                """;
        List<Group> groups = discover(report);
        assertEquals(1, groups.get(0).fixes().size());
        assertTrue(apply(groups, report).contains("\"asd \"+$V{Variable_2}+\"s ds ds\""));
    }

    @Test
    void discoverAppliesPeriodInParameterDefaultAndVariableInitial() throws Exception {
        String report = """
                <jasperReport name="t">
                  <parameter name="p" class="java.lang.String">
                    <defaultValueExpression><![CDATA["param.World"]]></defaultValueExpression>
                  </parameter>
                  <variable name="v" class="java.lang.String">
                    <initialValueExpression><![CDATA["var.World"]]></initialValueExpression>
                  </variable>
                </jasperReport>
                """;
        List<Group> groups = discover(report);
        assertEquals(2, groups.get(0).fixes().size());
        String out = apply(groups, report);
        assertTrue(out.contains("\"param. World\""), out);
        assertTrue(out.contains("\"var. World\""), out);
    }

    private static String transformText(String content, String markup, List<String> findings) {
        return TextcheckDiscoverer.transformText(content, markup, findings);
    }

    private static String transformExpr(String content, boolean isText, String markup, List<String> findings) {
        return TextcheckDiscoverer.transformExpression(content, isText, markup, findings);
    }

    private List<Group> discover(String report) throws Exception {
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, report);
        return new TextcheckDiscoverer().discover(context(), file);
    }

    private static String apply(List<Group> groups, String report) {
        EditSet edits = new EditSet();
        for (Group group : groups) {
            for (Fix fix : group.fixes()) {
                fix.apply(edits);
            }
        }
        return edits.apply(report);
    }

    private static Context context() {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        return new Context(new FileDiscovery(), out, out, false, q -> Choice.NO);
    }
}
