package com.myhooks;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.step.Context;
import com.myhooks.step.Engine;
import com.myhooks.steps.clear.ClearDiscoverer;
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
 * The full read/apply/write path must preserve a JRXML's original encoding,
 * BOM and declaration instead of silently normalizing it to UTF-8.
 */
class XmlEncodingIntegrationTest {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String UNUSED_FIELD = "\t<field name=\"Unused\" class=\"java.lang.String\"/>\n";

    @TempDir
    Path dir;

    private static String fixture;

    @BeforeAll
    static void loadFixture() throws IOException {
        try (InputStream in = XmlEncodingIntegrationTest.class.getResourceAsStream("/fixtures/UnicodeReport.jrxml")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void latin1FileStaysLatin1AfterEdits() throws Exception {
        String declared = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n" + fixture;
        Path file = dir.resolve("latin1.jrxml");
        Files.write(file, declared.getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(1, runClear(file));

        byte[] out = Files.readAllBytes(file);
        String expected = declared.replace(UNUSED_FIELD, "");
        assertArrayEquals(expected.getBytes(StandardCharsets.ISO_8859_1), out);
        assertTrue(contains(out, new byte[] {(byte) 0xE9}), "é must stay a single Latin-1 byte");
        assertFalse(contains(out, new byte[] {(byte) 0xC3, (byte) 0xA9}), "file must not be re-encoded as UTF-8");
    }

    @Test
    void utf8BomIsPreservedAfterEdits() throws Exception {
        Path file = dir.resolve("bom.jrxml");
        Files.write(file, concat(UTF8_BOM, fixture.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, runClear(file));

        String expected = fixture.replace(UNUSED_FIELD, "");
        assertArrayEquals(concat(UTF8_BOM, expected.getBytes(StandardCharsets.UTF_8)),
                Files.readAllBytes(file));
    }

    @Test
    void malformedUtf8BlocksAndLeavesTheFileUntouched() throws Exception {
        byte[] raw = concat("<jasperReport name=\"x\">".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xC3}); // truncated UTF-8 sequence
        Path file = dir.resolve("bad.jrxml");
        Files.write(file, raw);

        assertEquals(1, runClear(file), "a file that cannot be decoded must block the commit");
        assertArrayEquals(raw, Files.readAllBytes(file));
    }

    private int runClear(Path file) {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        Context context = new Context(new FileDiscovery(), out, out, false, q -> Choice.YES, q -> "");
        return new Engine(new ClearDiscoverer(), context).run(List.of(file.toString()));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
