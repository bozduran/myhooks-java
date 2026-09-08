package com.myhooks.textrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PeriodSpaceTest {

    @Test
    void addsSpaceAfterPeriodBeforeUppercase() {
        assertEquals("today. Swill", PeriodSpace.apply("today.Swill").text());
        assertEquals("Text Field. Today is something else",
                PeriodSpace.apply("Text Field.Today is something else").text());
    }

    @Test
    void leavesAlreadySpacedAndDecimalsUntouched() {
        assertEquals("else. This", PeriodSpace.apply("else. This").text());
        assertEquals("3.14", PeriodSpace.apply("3.14").text());
        assertEquals("something.", PeriodSpace.apply("something.").text());
    }

    @Test
    void reportsFindingOnlyWhenChanged() {
        TextResult changed = PeriodSpace.apply("a.B");
        assertTrue(changed.changed());
        assertTrue(changed.findings().get(0).startsWith("add space after '.'"));
        assertFalse(PeriodSpace.apply("a. B").changed());
    }
}
