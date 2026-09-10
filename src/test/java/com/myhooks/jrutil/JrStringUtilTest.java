package com.myhooks.jrutil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JrStringUtilTest {

    @Test
    void encodesTheFiveXmlEntities() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", JrStringUtil.encode("a&b<c>d\"e'f"));
    }

    @Test
    void decodesBackToTheOriginal() {
        String original = "a&b<c>d\"e'f";
        assertEquals(original, JrStringUtil.decode(JrStringUtil.encode(original)));
    }

    @Test
    void encodesAttributes() {
        assertEquals("a&amp;b&quot;c", JrStringUtil.encodeAttribute("a&b\"c"));
    }

    @Test
    void decodesNumericCharacterReferences() {
        assertEquals("Field_1", JrStringUtil.decode("Field&#95;1"));
        assertEquals("A&B", JrStringUtil.decode("A&#x26;B"));
        assertEquals("A&B", JrStringUtil.decode("A&#X26;B"));
    }

    @Test
    void decodeIsSinglePassAndLeavesUnknownReferencesAlone() {
        assertEquals("&lt;", JrStringUtil.decode("&amp;lt;"));
        assertEquals("&unknown;", JrStringUtil.decode("&unknown;"));
        assertEquals("&#xZZ;", JrStringUtil.decode("&#xZZ;"));
        assertEquals("cost & 5", JrStringUtil.decode("cost & 5"));
        assertEquals("&", JrStringUtil.decode("&"));
        assertEquals("", JrStringUtil.decode(""));
    }
}
