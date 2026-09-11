package com.myhooks.diffui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SectionTest {

    @Test
    void bannerPutsTheNameBetweenTwoRules() {
        String banner = Section.banner("positionType");

        String[] lines = banner.split("\n", -1);
        assertEquals(4, lines.length, banner);
        assertEquals(lines[0], lines[2], banner);
        assertEquals(52, lines[0].length(), banner);
        assertTrue(lines[0].matches("-+"), banner);
        assertEquals("positionType", lines[1], banner);
        assertEquals("", lines[3], banner);
    }
}
