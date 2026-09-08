package com.myhooks.textrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DoubleSpaceTest {

    @Test
    void collapsesRunsOfSpacesToSingleSpace() {
        assertEquals("a b", DoubleSpace.apply("a  b").text());
        assertEquals("a b", DoubleSpace.apply("a   b").text());
        assertEquals("a b c", DoubleSpace.apply("a  b  c").text());
    }

    @Test
    void collapsesLeadingAndTrailingDoubleSpaces() {
        assertEquals(" leading", DoubleSpace.apply("  leading").text());
        assertEquals("trailing ", DoubleSpace.apply("trailing  ").text());
    }

    @Test
    void leavesSingleSpacesUntouched() {
        TextResult result = DoubleSpace.apply("a b");
        assertEquals("a b", result.text());
        assertFalse(result.changed());
    }

    @Test
    void reportsFindingWhenChanged() {
        assertTrue(DoubleSpace.apply("a  b").findings().get(0).startsWith("remove double space"));
    }
}
