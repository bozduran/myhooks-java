package com.myhooks.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class XmlSourceTest {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};

    @Test
    void utf8WithoutBomRoundTrips() throws Exception {
        String xml = "<r>café</r>";
        byte[] raw = xml.getBytes(StandardCharsets.UTF_8);

        XmlSource source = XmlSource.of(raw);

        assertEquals(StandardCharsets.UTF_8, source.charset());
        assertFalse(source.hasBom());
        assertEquals(xml, source.text());
        assertArrayEquals(raw, source.encode(xml));
    }

    @Test
    void utf8BomIsStrippedFromTextAndRestoredOnEncode() throws Exception {
        byte[] raw = concat(UTF8_BOM, "<r>hi</r>".getBytes(StandardCharsets.UTF_8));

        XmlSource source = XmlSource.of(raw);

        assertTrue(source.hasBom());
        assertEquals("<r>hi</r>", source.text());
        assertArrayEquals(raw, source.encode(source.text()));
    }

    @Test
    void declaredLatin1IsHonored() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><r>café</r>";
        byte[] raw = xml.getBytes(StandardCharsets.ISO_8859_1);

        XmlSource source = XmlSource.of(raw);

        assertEquals(StandardCharsets.ISO_8859_1, source.charset());
        assertFalse(source.hasBom());
        assertEquals(xml, source.text());
        assertArrayEquals(raw, source.encode(xml));
        // "é" must stay the single Latin-1 byte 0xE9, not UTF-8's 0xC3 0xA9.
        assertTrue(contains(raw, new byte[] {(byte) 0xE9}));
        assertFalse(contains(raw, new byte[] {(byte) 0xC3, (byte) 0xA9}));
    }

    @Test
    void utf16BomIsDetectedAndRoundTrips() throws Exception {
        String xml = "<r>café</r>";
        byte[] raw = concat(UTF16LE_BOM, xml.getBytes(StandardCharsets.UTF_16LE));

        XmlSource source = XmlSource.of(raw);

        assertEquals(StandardCharsets.UTF_16LE, source.charset());
        assertTrue(source.hasBom());
        assertEquals(xml, source.text());
        assertArrayEquals(raw, source.encode(xml));
    }

    @Test
    void malformedInputIsRejectedNotReplaced() {
        // A dangling UTF-8 lead byte: strict decoding must fail, not emit U+FFFD.
        byte[] raw = {'<', 'r', '>', (byte) 0xC3, '<', '/', 'r', '>'};

        IOException error = assertThrows(IOException.class, () -> XmlSource.of(raw));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void unsupportedDeclaredEncodingIsRejected() {
        byte[] raw = "<?xml version=\"1.0\" encoding=\"NO-SUCH-CHARSET\"?><r/>"
                .getBytes(StandardCharsets.US_ASCII);

        IOException error = assertThrows(IOException.class, () -> XmlSource.of(raw));

        assertTrue(error.getMessage().contains("NO-SUCH-CHARSET"), error.getMessage());
    }

    @Test
    void encodeFailsWhenCharsetCannotRepresentTheEdit() throws Exception {
        XmlSource source = XmlSource.of(
                "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><r>café</r>"
                        .getBytes(StandardCharsets.ISO_8859_1));

        IOException error = assertThrows(IOException.class, () -> source.encode("<r>€</r>"));

        assertTrue(error.getMessage().contains("ISO-8859-1"), error.getMessage());
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
