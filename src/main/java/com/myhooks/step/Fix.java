package com.myhooks.step;

import com.myhooks.edit.EditSet;

/**
 * A single proposed fix: a description, a git-diff-style rendering of the
 * before/after, and the ability to add its edit(s) to an {@link EditSet}.
 */
public interface Fix {

    String describe();

    String diff();

    void apply(EditSet editSet);
}
