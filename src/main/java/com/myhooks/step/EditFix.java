package com.myhooks.step;

import com.myhooks.diffui.DiffRenderer;
import com.myhooks.diffui.Output;
import com.myhooks.edit.Edit;
import com.myhooks.edit.EditSet;
import com.myhooks.io.Lines;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.Query;

/**
 * A {@link Fix} backed by a single byte-span {@link Edit}. The before/after
 * texts are captured at construction so the diff can be rendered without
 * re-reading the file.
 *
 * <p>{@code line} is the 1-based file line the diff starts on; when positive it
 * is shown as a gutter. A non-positive line (the convenience constructor)
 * renders without line numbers.
 *
 * <p>Prefer {@link #inElement} / {@link #inContext} over the raw constructors:
 * they widen the rendered diff to the change's whole enclosing element, so the
 * prompt shows the element's other lines as plain context and paints only the
 * line(s) the edit actually touches.
 */
public final class EditFix implements Fix {

    private static final String DIFF_INDENT = "          ";

    private final String description;
    private final String before;
    private final String after;
    private final Edit edit;
    private final boolean color;
    private final int line;

    public EditFix(String description, String before, String after, Edit edit, boolean color) {
        this(description, before, after, edit, color, 0);
    }

    public EditFix(String description, String before, String after, Edit edit, boolean color, int line) {
        this.description = description;
        this.before = before;
        this.after = after;
        this.edit = edit;
        this.color = color;
        this.line = line;
    }

    /**
     * A fix whose diff shows {@code node}'s whole enclosing unit as context,
     * with only the edit's line(s) painted. The unit is
     * {@link Query#enclosingUnit(Node)}; when the node is the document root the
     * whole document would be the unit, so only its start tag is shown (where a
     * root attribute such as {@code <jasperReport name="...">} lives).
     */
    public static EditFix inElement(String description, Edit edit, boolean color, String raw, Node node) {
        Node unit = Query.enclosingUnit(node);
        int start = unit.startTag();
        int end = unit.parent() == null ? unit.startTagEnd() : unit.end();
        return inContext(description, edit, color, raw, start, end);
    }

    /**
     * A fix whose diff shows {@code raw[contextStart, contextEnd)} as context.
     * The span is widened to contain the edit, so a removal that also takes the
     * element's line indentation and terminator still renders whole. Diffing the
     * block with and without the edit leaves the untouched lines uncolored and
     * paints exactly the changed line(s).
     */
    public static EditFix inContext(String description, Edit edit, boolean color, String raw,
            int contextStart, int contextEnd) {
        int start = Math.max(0, Math.min(Math.min(contextStart, contextEnd), edit.start()));
        int end = Math.min(raw.length(), Math.max(Math.max(contextStart, contextEnd), edit.end()));
        String before = raw.substring(start, end);
        String after = raw.substring(start, edit.start()) + edit.replacement() + raw.substring(edit.end(), end);
        return new EditFix(description, before, after, edit, color, Lines.lineOf(raw, start));
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public String diff() {
        return DiffRenderer.render(before, after, DIFF_INDENT, color, line, Output.width());
    }

    @Override
    public void apply(EditSet editSet) {
        editSet.add(edit);
    }
}
