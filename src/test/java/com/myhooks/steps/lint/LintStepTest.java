package com.myhooks.steps.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.step.Context;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LintStepTest {

    @TempDir
    Path dir;

    @Test
    void warnsOnConstantBooleanPrintWhen() throws Exception {
        String body = """
                	<detail><band height="30">
                		<element kind="staticText" x="0" y="0" width="100" height="20">
                			<text><![CDATA[x]]></text>
                			<printWhenExpression><![CDATA[true]]></printWhenExpression>
                			<printWhenExpression><![CDATA[false]]></printWhenExpression>
                			<printWhenExpression><![CDATA[$F{visible}]]></printWhenExpression>
                			<printWhenExpression><![CDATA[true && $P{x}]]></printWhenExpression>
                		</element>
                	</band></detail>
                """;
        String raw = report(body);
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, raw);

        String out = run(file);

        int trueLine = lineOf(raw, "<printWhenExpression><![CDATA[true]]>");
        int falseLine = lineOf(raw, "<printWhenExpression><![CDATA[false]]>");
        assertTrue(out.contains(file + ":" + trueLine + ": printWhenExpression is a constant 'true'"), out);
        assertTrue(out.contains(file + ":" + falseLine + ": printWhenExpression is a constant 'false'"), out);

        // Expression and composite-expression bodies are not flagged.
        assertEquals(2, occurrences(out, "printWhenExpression is a constant"), out);
    }

    @Test
    void warnsOnPlainTextBooleanWithoutCdata() throws Exception {
        String body = """
                	<detail><band height="30">
                		<element kind="staticText" x="0" y="0" width="100" height="20">
                			<printWhenExpression>true</printWhenExpression>
                		</element>
                	</band></detail>
                """;
        String raw = report(body);
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, raw);

        String out = run(file);

        assertTrue(out.contains("printWhenExpression is a constant 'true'"), out);
    }

    @Test
    void warnsWhenRemoveLineWhenBlankIsMissing() throws Exception {
        String body = """
                	<detail><band height="30">
                		<element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
                			<expression><![CDATA["x"]]></expression>
                		</element>
                		<element kind="subreport" uuid="s1" x="0" y="0" width="100" height="20"/>
                	</band></detail>
                """;
        String raw = report(body);
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, raw);

        String out = run(file);

        int textLine = lineOf(raw, "<element kind=\"textField\"");
        int subLine = lineOf(raw, "<element kind=\"subreport\"");
        assertTrue(out.contains(file + ":" + textLine + ": textField is missing removeLineWhenBlank=\"true\""), out);
        assertTrue(out.contains(file + ":" + subLine + ": subreport is missing removeLineWhenBlank=\"true\""), out);
    }

    @Test
    void warnsOnMarkupTagsInTextFieldWithoutMarkup() throws Exception {
        String body = """
                	<detail><band height="30">
                		<element kind="textField" uuid="u1" x="0" y="0" width="100" height="20" removeLineWhenBlank="true">
                			<expression><![CDATA["Le <b>boulanger</b> est sympa."]]></expression>
                		</element>
                	</band></detail>
                """;
        String raw = report(body);
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, raw);

        String out = run(file);

        int line = lineOf(raw, "<element kind=\"textField\"");
        assertTrue(out.contains(file + ":" + line + ": text contains markup tag <b> but markup is not "
                + "\"styled\", \"html\" or \"rtf\""), out);
    }

    @Test
    void printsNothingWhenClean() throws Exception {
        String body = """
                	<detail><band height="30">
                		<element kind="staticText" x="0" y="0" width="100" height="20">
                			<text><![CDATA[x]]></text>
                		</element>
                	</band></detail>
                """;
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, report(body));

        String out = run(file);

        assertEquals("", out);
    }

    @Test
    void selfClosingPrintWhenExpressionDoesNotCrash() throws Exception {
        String body = """
                	<detail><band height="30">
                		<element kind="staticText" x="0" y="0" width="100" height="20">
                			<printWhenExpression/>
                		</element>
                	</band></detail>
                """;
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, report(body));

        assertEquals("", run(file));
    }

    @Test
    void aFailingRuleIsReportedAndDoesNotCrashTheStep() throws Exception {
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, report(""));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        Context context = new Context(new FileDiscovery(), out, new PrintStream(err), false, q -> Choice.NO);
        Rule boom = (root, raw) -> {
            throw new IllegalStateException("boom");
        };

        int exit = new LintStep(List.of(boom)).run(context, List.of(file.toString()));

        assertEquals(0, exit, "lint must never block the commit");
        assertTrue(err.toString().contains("boom"), err.toString());
    }

    private String run(Path file) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer);
        Context context = new Context(new FileDiscovery(), out, out, false, q -> Choice.NO);
        new LintStep().run(context, List.of(file.toString()));
        return buffer.toString();
    }

    private static String report(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jasperReport name=\"t\" language=\"java\" pageWidth=\"595\" pageHeight=\"842\""
                + " columnWidth=\"555\" leftMargin=\"20\" rightMargin=\"20\" topMargin=\"20\" bottomMargin=\"20\">\n"
                + "\t<title height=\"30\"/>\n"
                + body
                + "</jasperReport>\n";
    }

    private static int lineOf(String raw, String needle) {
        int offset = raw.indexOf(needle);
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (raw.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
