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
}
