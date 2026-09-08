package com.myhooks.diffui;

/**
 * The answer to an interactive yes/no/all/skip prompt.
 */
public enum Choice {
    YES("Yes"),
    NO("No"),
    ALL("All"),
    SKIP("Skip file");

    private final String label;

    Choice(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
