package com.myhooks.diffui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class FreeformTest {

    private static BufferedReader reader(String s) {
        return new BufferedReader(new StringReader(s));
    }

    @Test
    void returnsTrimmedLine() {
        assertEquals("document.iddata", Freeform.ask("jsonql expression:", reader("document.iddata\n")));
    }

    @Test
    void blankLineReturnsEmpty() {
        assertEquals("", Freeform.ask("again:", reader("   \n")));
    }

    @Test
    void exhaustedInputReturnsEmpty() {
        assertEquals("", Freeform.ask("once more:", reader("")));
    }
}
