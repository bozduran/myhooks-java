package com.myhooks.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class EditMergeTest {

    @Test
    void mergesSameSpanInsertionsByConcatenatingReplacements() {
        Edit merged = EditMerge.mergeSameSpan(List.of(
                new Edit(2, 2, "a"),
                new Edit(2, 2, "b")));
        assertEquals(new Edit(2, 2, "ab"), merged);
    }

    @Test
    void mergesSameSpanReplacementsByConcatenatingReplacements() {
        Edit merged = EditMerge.mergeSameSpan(List.of(
                new Edit(1, 3, "X"),
                new Edit(1, 3, "Y")));
        assertEquals(new Edit(1, 3, "XY"), merged);
    }

    @Test
    void rejectsMismatchedSpans() {
        assertThrows(IllegalArgumentException.class, () -> EditMerge.mergeSameSpan(List.of(
                new Edit(1, 3, "X"),
                new Edit(1, 4, "Y"))));
    }

    @Test
    void rejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> EditMerge.mergeSameSpan(List.of()));
    }

    @Test
    void singleEditIsReturnedUnchanged() {
        Edit edit = new Edit(1, 2, "X");
        assertEquals(edit, EditMerge.mergeSameSpan(List.of(edit)));
    }
}
