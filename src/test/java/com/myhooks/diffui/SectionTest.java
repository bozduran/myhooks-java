package com.myhooks.diffui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SectionTest {

    @Test
    void bannerPutsTheNameBetweenTwoDashedRules() {
        String banner = Section.banner("positionType (3)", 52);

        String[] lines = banner.split("\n", -1);
        assertEquals(4, lines.length, banner);
        assertEquals(lines[0], lines[2], banner);
        assertEquals(52, lines[0].length(), banner);
        assertTrue(lines[0].matches("-+"), banner);
        assertEquals("positionType (3)", lines[1], banner);
        assertEquals("", lines[3], banner);
    }

    @Test
    void headerPutsTheTitleBetweenTwoEqualsRules() {
        String header = Section.header("format · path/Foo.jrxml", 40);

        String[] lines = header.split("\n", -1);
        assertEquals(4, lines.length, header);
        assertEquals(lines[0], lines[2], header);
        assertEquals(40, lines[0].length(), header);
        assertTrue(lines[0].matches("=+"), header);
        assertEquals("format · path/Foo.jrxml", lines[1], header);
        assertEquals("", lines[3], header);
    }
}
