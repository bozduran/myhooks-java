package com.myhooks.diffui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiffRendererTest {

    private static final String RED = "\u001b[41m";
    private static final String GREEN = "\u001b[42m";
    private static final String BLACK = "\u001b[30m";
    private static final String RESET = "\u001b[0m";

    @Test
    void rendersReplacement() {
        String want = RED + BLACK + "  - old" + RESET + "\n"
                + GREEN + BLACK + "  + new" + RESET + "\n";
        assertEquals(want, DiffRenderer.render("old", "new", "  ", true));
    }

    @Test
    void rendersPureDeletionAndInsertion() {
        assertEquals(RED + BLACK + "- gone" + RESET + "\n",
                DiffRenderer.render("gone", "", "", true));
        assertEquals(GREEN + BLACK + "+ added" + RESET + "\n",
                DiffRenderer.render("", "added", "", true));
    }

    @Test
    void rendersMultiLineHunksWithContext() {
        String want = " a\n"
                + RED + BLACK + "- b" + RESET + "\n"
                + GREEN + BLACK + "+ c" + RESET + "\n";
        assertEquals(want, DiffRenderer.render("a\nb", "a\nc", "", true));
    }

    @Test
    void colorsTheWholeLineIncludingIndentation() {
        // The indentation is part of the bar, not left outside it.
        String want = RED + BLACK + "    - old" + RESET + "\n"
                + GREEN + BLACK + "    + new" + RESET + "\n";
        assertEquals(want, DiffRenderer.render("old", "new", "    ", true));
    }

    @Test
    void rendersAbsoluteLineNumbersForBothSides() {
        String want = "1  a\n"
                + RED + BLACK + "2 - b" + " ".repeat(15) + RESET + "\n"
                + GREEN + BLACK + "2 + c" + " ".repeat(15) + RESET + "\n";
        assertEquals(want, DiffRenderer.render("a\nb", "a\nc", "", true, 1, 20));
    }

    @Test
    void lineNumbersAdvanceOnAnInsertion() {
        String want = GREEN + BLACK + "5 + x" + RESET + "\n"
                + GREEN + BLACK + "6 + y" + RESET + "\n";
        assertEquals(want, DiffRenderer.render("", "x\ny", "", true, 5, 0));
    }

    @Test
    void noPaddingWhenWidthIsNotPositive() {
        String want = RED + BLACK + "2 - old" + RESET + "\n"
                + GREEN + BLACK + "2 + new" + RESET + "\n";
        assertEquals(want, DiffRenderer.render("old", "new", "", true, 2, 0));
    }

    @Test
    void emptyBothSidesRendersNothing() {
        assertEquals("", DiffRenderer.render("", "", "", true));
    }

    @Test
    void dropsSingleTrailingNewline() {
        String want = RED + BLACK + "- old" + RESET + "\n"
                + GREEN + BLACK + "+ new" + RESET + "\n";
        assertEquals(want, DiffRenderer.render("old", "new\n", "", true));
    }

    @Test
    void disablesColor() {
        assertEquals("  - old\n  + new\n", DiffRenderer.render("old", "new", "  ", false));
    }

    @Test
    void colorEnabledRequiresTtyAndNoColorUnset() {
        assertFalse(DiffRenderer.colorEnabled(false, null));
        assertFalse(DiffRenderer.colorEnabled(true, "1"));
        assertFalse(DiffRenderer.colorEnabled(true, ""));
        assertTrue(DiffRenderer.colorEnabled(true, null));
    }
}
