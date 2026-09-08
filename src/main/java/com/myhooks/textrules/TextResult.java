package com.myhooks.textrules;

import java.util.List;

/**
 * The outcome of a text transform: the transformed text plus human-readable
 * findings describing each applied change (empty when nothing changed).
 */
public record TextResult(String text, List<String> findings) {

    public TextResult {
        findings = List.copyOf(findings);
    }

    /** Convenience constructor for an unchanged result with no findings. */
    public static TextResult unchanged(String text) {
        return new TextResult(text, List.of());
    }

    /** Reports whether the transform actually changed the text. */
    public boolean changed() {
        return !findings.isEmpty();
    }
}
