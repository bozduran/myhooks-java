package com.myhooks.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EditSetTest {

    @Test
    void replace() {
        EditSet set = new EditSet();
        set.add(new Edit(1, 3, "XX"));
        assertEquals("aXXd", set.apply("abcd"));
    }

    @Test
    void insert() {
        Edit ins = new Edit(2, 2, "-");
        assertTrue(ins.isInsert());
        EditSet set = new EditSet();
        set.add(ins);
        assertEquals("ab-cd", set.apply("abcd"));
    }

    @Test
    void delete() {
        Edit del = new Edit(1, 3, "");
        assertTrue(del.isDelete());
        EditSet set = new EditSet();
        set.add(del);
        assertEquals("ad", set.apply("abcd"));
    }

    @Test
    void multipleNonOverlappingEditsApplyRegardlessOfAddOrder() {
        EditSet set = new EditSet();
        set.add(new Edit(0, 1, "A"));
        set.add(new Edit(3, 4, "D"));
        assertEquals("AbcD", set.apply("abcd"));

        EditSet reversed = new EditSet();
        reversed.add(new Edit(3, 4, "D"));
        reversed.add(new Edit(0, 1, "A"));
        assertEquals("AbcD", reversed.apply("abcd"));
    }

    @Test
    void overlapIsRejected() {
        EditSet set = new EditSet();
        set.add(new Edit(0, 3, "X"));
        set.add(new Edit(2, 4, "Y"));
        assertThrows(IllegalStateException.class, () -> set.apply("abcd"));
    }

    @Test
    void applyNeverMutatesCallerData() {
        EditSet set = new EditSet();
        set.add(new Edit(1, 2, "X"));
        String raw = "abc";
        assertEquals("aXc", set.apply(raw));
        assertEquals("abc", raw, "the input string must not be modified");
        // The set is not consumed: applying again gives the same result.
        assertEquals("aXc", set.apply(raw));
    }

    @Test
    void rejectsNegativeOrInvertedOffsets() {
        EditSet set = new EditSet();
        assertThrows(IllegalArgumentException.class, () -> set.add(new Edit(-1, 0, "x")));
        assertThrows(IllegalArgumentException.class, () -> new Edit(0, -1, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Edit(3, 1, "x"));
    }

    @Test
    void editOutsideTheDocumentIsRejected() {
        EditSet endPastEof = new EditSet();
        endPastEof.add(new Edit(2, 99, "X"));
        assertThrows(EditException.class, () -> endPastEof.apply("abcd"));

        EditSet startPastEof = new EditSet();
        startPastEof.add(new Edit(10, 11, "X"));
        assertThrows(EditException.class, () -> startPastEof.apply("abcd"));
    }

    @Test
    void identicalSpansAreRejected() {
        EditSet set = new EditSet();
        set.add(new Edit(2, 2, "A"));
        set.add(new Edit(2, 2, "B"));
        assertThrows(EditException.class, () -> set.apply("abcd"));
    }

    @Test
    void boundaryPositionsAreAllowed() {
        EditSet insertAtEnd = new EditSet();
        insertAtEnd.add(new Edit(4, 4, "!"));
        assertEquals("abcd!", insertAtEnd.apply("abcd"));

        EditSet replaceThroughEnd = new EditSet();
        replaceThroughEnd.add(new Edit(2, 4, "XY"));
        assertEquals("abXY", replaceThroughEnd.apply("abcd"));
    }
}
