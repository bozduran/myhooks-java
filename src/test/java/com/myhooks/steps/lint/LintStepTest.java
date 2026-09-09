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
