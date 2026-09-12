package com.myhooks.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LinesTest {

    @Test
    void reportsTheOneBasedLineContainingAnOffset() {
        String text = "one\ntwo\nthree";
        assertEquals(1, Lines.lineOf(text, 0));
        assertEquals(1, Lines.lineOf(text, 3));
        assertEquals(2, Lines.lineOf(text, 4));
        assertEquals(3, Lines.lineOf(text, 8));
    }

    @Test
    void clampsOffsetsToTheDocument() {
        String text = "one\ntwo";
        assertEquals(1, Lines.lineOf(text, -5));
        assertEquals(2, Lines.lineOf(text, 999));
    }
}
