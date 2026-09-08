package com.myhooks.textrules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NewlineTest {

    @Test
    void normalizesToMarkupToken() {
        assertEquals("a\\nb", Newline.apply("a\\nb", ""));
        assertEquals("a\\nb", Newline.apply("a\\nb", "none"));
        assertEquals("a<br/>b", Newline.apply("a\\nb", "styled"));
        assertEquals("a<br/>b", Newline.apply("a<br>b", "styled"));
        assertEquals("a<br>b", Newline.apply("a\\nb", "html"));
        assertEquals("a<br>b", Newline.apply("a<br/>b", "html"));
        assertEquals("a\\nb", Newline.apply("a<br>b", "none"));
    }

    @Test
    void unknownMarkupIsLeftUnchanged() {
        assertEquals("a\\nb", Newline.apply("a\\nb", "rtf"));
    }
}
