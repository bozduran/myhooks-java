package com.myhooks.step;

import java.util.List;

/**
 * A labelled group of {@link Fix}es. The "All" prompt choice is scoped to a
 * single group: choosing it applies the rest of that group's fixes.
 */
public final class Group {

    private final String label;
    private final List<Fix> fixes;

    public Group(String label, List<Fix> fixes) {
        this.label = label;
        this.fixes = List.copyOf(fixes);
    }

    public String label() {
        return label;
    }

    public List<Fix> fixes() {
        return fixes;
    }
}
