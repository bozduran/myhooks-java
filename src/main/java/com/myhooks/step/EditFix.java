package com.myhooks.step;

import com.myhooks.diffui.DiffRenderer;
import com.myhooks.edit.Edit;
import com.myhooks.edit.EditSet;

/**
 * A {@link Fix} backed by a single byte-span {@link Edit}. The before/after
 * texts are captured at construction so the diff can be rendered without
 * re-reading the file.
 */
public final class EditFix implements Fix {

    private static final String DIFF_INDENT = "          ";

    private final String description;
    private final String before;
    private final String after;
    private final Edit edit;
    private final boolean color;

    public EditFix(String description, String before, String after, Edit edit, boolean color) {
        this.description = description;
        this.before = before;
        this.after = after;
        this.edit = edit;
        this.color = color;
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public String diff() {
        return DiffRenderer.render(before, after, DIFF_INDENT, color);
    }

    @Override
    public void apply(EditSet editSet) {
        editSet.add(edit);
    }
}
