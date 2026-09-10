package com.myhooks.edit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An ordered collection of non-overlapping {@link Edit}s that can be applied to
 * a document in one pass.
 */
public final class EditSet {

    private final List<Edit> edits = new ArrayList<>();

    /** Adds an edit. Offsets must be non-negative. */
    public void add(Edit edit) {
        Objects.requireNonNull(edit, "edit");
        if (edit.start() < 0 || edit.end() < 0) {
            throw new IllegalArgumentException("offsets must be non-negative");
        }
        edits.add(edit);
    }

    /**
     * Applies all edits to {@code raw} and returns the result. Edits are copied,
     * sorted by descending start offset, applied in that order so earlier
     * offsets stay valid, and rejected when two of them overlap or share the
     * exact same span (which would make their order ambiguous). Neither
     * {@code raw} nor the stored edits are mutated.
     *
     * @throws EditException when an edit lies outside {@code raw} or conflicts
     *         with another edit
     */
    public String apply(String raw) {
        Objects.requireNonNull(raw, "raw");
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(Edit::start).reversed());

        for (Edit edit : ordered) {
            if (edit.start() > raw.length() || edit.end() > raw.length()) {
                throw new EditException("edit " + edit + " is outside the document (length " + raw.length() + ")");
            }
        }

        for (int i = 0; i + 1 < ordered.size(); i++) {
            Edit later = ordered.get(i);
            Edit earlier = ordered.get(i + 1);
            boolean overlap = earlier.end() > later.start();
            boolean sameSpan = earlier.start() == later.start() && earlier.end() == later.end();
            if (overlap || sameSpan) {
                throw new EditException("conflicting edits: " + earlier + " and " + later);
            }
        }

        StringBuilder out = new StringBuilder(raw);
        for (Edit edit : ordered) {
            out.replace(edit.start(), edit.end(), edit.replacement());
        }
        return out.toString();
    }
}
