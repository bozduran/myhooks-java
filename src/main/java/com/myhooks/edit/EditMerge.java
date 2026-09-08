package com.myhooks.edit;

import java.util.List;
import java.util.Objects;

/**
 * Combines edits that target the same byte span.
 */
public final class EditMerge {

    private EditMerge() {
    }

    /**
     * Merges edits that all share the same {@code [start, end)} span into a
     * single edit whose replacement is the concatenation of the replacements in
     * list order. Throws {@link IllegalArgumentException} when the list is empty
     * or the spans differ.
     */
    public static Edit mergeSameSpan(List<Edit> edits) {
        Objects.requireNonNull(edits, "edits");
        if (edits.isEmpty()) {
            throw new IllegalArgumentException("no edits to merge");
        }
        int start = edits.get(0).start();
        int end = edits.get(0).end();
        StringBuilder replacement = new StringBuilder();
        for (Edit edit : edits) {
            if (edit.start() != start || edit.end() != end) {
                throw new IllegalArgumentException(
                        "edits must share the same span: expected [" + start + ", " + end + ") but got " + edit);
            }
            replacement.append(edit.replacement());
        }
        return new Edit(start, end, replacement.toString());
    }
}
