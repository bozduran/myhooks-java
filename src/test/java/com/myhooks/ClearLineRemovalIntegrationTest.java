package com.myhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.edit.EditSet;
import com.myhooks.step.Context;
import com.myhooks.step.Fix;
import com.myhooks.step.Group;
import com.myhooks.steps.clear.ClearDiscoverer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deleting an unused declaration must remove its line only when the
 * declaration is alone on it; neighbours on the same line, trailing comments
 * and CRLF terminators must survive intact.
 */
class ClearLineRemovalIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void twoUnusedDeclarationsOnOneLineBothRemoved() throws Exception {
        String decls = "\t<field name=\"A\" class=\"java.lang.String\"/>"
                + "<field name=\"B\" class=\"java.lang.String\"/>\n";
        String content = report(decls, "\"x\"", "\n");

        String out = runClear(content);

        assertEquals(content.replace(decls, "\n"), out);
        assertFalse(out.contains("name=\"A\""), out);
        assertFalse(out.contains("name=\"B\""), out);
    }

    @Test
    void usedAndUnusedOnOneLineKeepsTheUsedDeclarationAndTheNewline() throws Exception {
        String used = "\t<field name=\"Used\" class=\"java.lang.String\"/>";
        String unused = "<field name=\"Unused\" class=\"java.lang.String\"/>";
        String content = report(used + unused + "\n", "$F{Used}", "\n");

        String out = runClear(content);

        assertEquals(content.replace(used + unused, used), out);
        assertTrue(out.contains("name=\"Used\""), out);
        assertFalse(out.contains("name=\"Unused\""), out);
        assertTrue(out.contains(used + "\n"), out);
    }

    @Test
    void unusedAloneOnItsLineRemovesTheWholeLine() throws Exception {
        String decls = "\t<field name=\"Unused\" class=\"java.lang.String\"/>\n";
        String content = report(decls, "\"x\"", "\n");

        String out = runClear(content);

        assertEquals(content.replace(decls, ""), out);
    }

    @Test
    void unusedWithTrailingCommentKeepsTheComment() throws Exception {
        String decls = "\t<field name=\"Unused\" class=\"java.lang.String\"/><!-- keep -->\n";
        String content = report(decls, "\"x\"", "\n");

        String out = runClear(content);

        assertEquals(content.replace(decls, "<!-- keep -->\n"), out);
        assertTrue(out.contains("<!-- keep -->"), out);
    }

    @Test
    void crlfUnusedLineIsRemovedWithoutStrayCarriageReturn() throws Exception {
        String decls = "\t<field name=\"Unused\" class=\"java.lang.String\"/>\n";
        String content = report(decls, "\"x\"", "\r\n");

        String out = runClear(content);

        assertEquals(content.replace(decls.replace("\n", "\r\n"), ""), out);
        assertFalse(out.replace("\r\n", "").contains("\r"), "no lone carriage return left behind");
    }

    private String runClear(String content) throws Exception {
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, content);
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        Context context = new Context(new FileDiscovery(), out, out, false, q -> Choice.YES, q -> "");
        List<Group> groups = new ClearDiscoverer().discover(context, file);
        EditSet edits = new EditSet();
        for (Group group : groups) {
            for (Fix fix : group.fixes()) {
                fix.apply(edits);
            }
        }
        return edits.apply(content);
    }

    private static String report(String declarations, String expression, String eol) {
        String body = "<jasperReport name=\"r\" language=\"java\" pageWidth=\"595\" pageHeight=\"842\""
                + " columnWidth=\"555\" leftMargin=\"20\" rightMargin=\"20\" topMargin=\"20\""
                + " bottomMargin=\"20\" uuid=\"aaaaaaaa-0000-0000-0000-000000000001\">\n"
                + declarations
                + "\t<title height=\"20\" splitType=\"Stretch\">\n"
                + "\t\t<element kind=\"textField\" uuid=\"aaaaaaaa-0000-0000-0000-000000000002\""
                + " x=\"0\" y=\"0\" width=\"100\" height=\"20\">\n"
                + "\t\t\t<expression><![CDATA[" + expression + "]]></expression>\n"
                + "\t\t</element>\n"
                + "\t</title>\n"
                + "\t<detail>\n"
                + "\t\t<band height=\"20\" splitType=\"Stretch\"/>\n"
                + "\t</detail>\n"
                + "</jasperReport>\n";
        return "\n".equals(eol) ? body : body.replace("\n", eol);
    }
}
