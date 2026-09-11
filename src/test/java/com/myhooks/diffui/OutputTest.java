package com.myhooks.diffui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OutputTest {

    @Test
    void clampsToTheReadableRange() {
        assertEquals(Output.MIN_WIDTH, Output.clamp(10));
        assertEquals(Output.MAX_WIDTH, Output.clamp(500));
        assertEquals(60, Output.clamp(60));
    }

    @Test
    void parsesColumnsAndFallsBackForJunk() {
        assertEquals(72, Output.parse("72", Output.DEFAULT_WIDTH));
        assertEquals(72, Output.parse(" 72 ", Output.DEFAULT_WIDTH));
        assertEquals(Output.DEFAULT_WIDTH, Output.parse(null, Output.DEFAULT_WIDTH));
        assertEquals(Output.DEFAULT_WIDTH, Output.parse("wide", Output.DEFAULT_WIDTH));
    }
}
