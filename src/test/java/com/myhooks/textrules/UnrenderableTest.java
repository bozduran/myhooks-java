package com.myhooks.textrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UnrenderableTest {

    @Test
    void replacesCommonUnrenderableCharacters() {
        assertEquals("It's", Unrenderable.apply("It\u2019s").text());
        assertEquals("\"quoted\"", Unrenderable.apply("\u201Cquoted\u201D").text());
        assertEquals("a-b-c", Unrenderable.apply("a\u2013b\u2014c").text());
        assertEquals("a b", Unrenderable.apply("a\u00A0b").text());
        assertEquals("ab", Unrenderable.apply("a\u200Bb").text());
        assertEquals("three...dots", Unrenderable.apply("three\u2026dots").text());
        assertEquals("- item", Unrenderable.apply("\u2022 item").text());
    }

    @Test
    void reportsOneFindingPerDistinctCharacter() {
        TextResult result = Unrenderable.apply("a\u2019\u2019b");
        assertEquals("a''b", result.text());
        assertEquals(1, result.findings().size());
        assertTrue(result.findings().get(0).startsWith("replace right single quote (U+2019)"));
    }

    @Test
    void nonBreakingSpaceThenDoubleSpaceOrder() {
        // A space directly before a non-breaking space collapses to one space
        // only after unrenderable replacement runs first.
        TextResult unrenderable = Unrenderable.apply("a \u00A0b");
        assertTrue(unrenderable.findings().get(0).startsWith("replace non-breaking space"));
        TextResult doubleSpace = DoubleSpace.apply(unrenderable.text());
        assertEquals("a b", doubleSpace.text());
        assertTrue(doubleSpace.findings().get(0).startsWith("remove double space"));
    }
}
