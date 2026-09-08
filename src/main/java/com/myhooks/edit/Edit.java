package com.myhooks.edit;

import java.util.Objects;

/**
 * A surgical byte-span replacement.
 *
 * <p>{@code [start, end)} is the replaced range; {@code replacement} is the new
 * text. A pure insertion is {@code end == start}; a pure deletion is an empty
 * {@code replacement}.
 */
public record Edit(int start, int end, String replacement) {

    public Edit {
        Objects.requireNonNull(replacement, "replacement");
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("offsets must be non-negative: [" + start + ", " + end + ")");
        }
        if (end < start) {
            throw new IllegalArgumentException("end must not precede start: " + end + " < " + start);
        }
    }

    /** True when this edit is a pure insertion ({@code end == start}). */
    public boolean isInsert() {
        return end == start;
    }

    /** True when this edit is a pure deletion (empty replacement). */
    public boolean isDelete() {
        return replacement.isEmpty();
    }
}
