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
 * Attribute values are XML-decoded, so a declaration whose name uses a
 * character reference is not mistaken for a different, unused declaration.
 *
 * <p>The fixture is derived from {@code examples/MarkupReport.jrxml}'s shape: a
 * used field named {@code Total&Tax} (written {@code Total&amp;Tax}), an unused
 * {@code Unused&Field}, and a plain-text description containing an ampersand.
 */
class EntityNameIntegrationTest {

    private static final String UNUSED_FIELD =
            "\t<field name=\"Unused&amp;Field\" class=\"java.lang.String\"/>\n";

    @TempDir
    Path dir;

    private static String fixture;

    @BeforeAll
    static void loadFixture() throws IOException {
        try (InputStream in = EntityNameIntegrationTest.class.getResourceAsStream("/fixtures/EntityNameReport.jrxml")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void entityEncodedUsedFieldIsKeptAndUnusedFieldIsDeleted() throws Exception {
        String out = applyAll(new ClearDiscoverer(), fixture, "");

        String expected = fixture
                .replace(UNUSED_FIELD, "")
                .replace("\t</field>",
                        "\t\t<property name=\"net.sf.jasperreports.jsonql.field.expression\""
                                + " value=\"a &amp; b\"/>\n\t</field>");
        assertEquals(expected, out);

        assertTrue(out.contains("name=\"Total&amp;Tax\""), out);
        assertTrue(out.contains("$F{Total&Tax}"), out);
        assertFalse(out.contains("Unused"), out);
        // The jsonql property value is encoded exactly once.
        assertTrue(out.contains("value=\"a &amp; b\""), out);
        assertFalse(out.contains("&amp;amp;"), out);
    }

    @Test
    void entityEncodedReportNameMatchingDecodedBaseNameIsNotRewritten() throws Exception {
        Path file = dir.resolve("Total&Tax.jrxml");
        Files.writeString(file, "<jasperReport name=\"Total&amp;Tax\" language=\"java\"></jasperReport>");

        List<Group> groups = new FormatDiscoverer().discover(context(), file);

        assertTrue(groups.isEmpty(), "decoded name equals the file's base name; no fix expected");
    }

    private String applyAll(Discoverer discoverer, String content, String freeform) throws Exception {
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, content);
        List<Group> groups = discoverer.discover(context(freeform), file);
        EditSet edits = new EditSet();
        for (Group group : groups) {
            for (Fix fix : group.fixes()) {
                fix.apply(edits);
            }
        }
        return edits.apply(content);
    }

    private static Context context() {
        return context("");
    }

    private static Context context(String freeform) {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        return new Context(new FileDiscovery(), out, out, false, q -> Choice.YES, q -> freeform);
    }
}
