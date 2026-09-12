package com.myhooks.steps.lint.rules;

import com.myhooks.steps.lint.Rule;
import com.myhooks.steps.lint.Warning;
import com.myhooks.xmlspan.Attr;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Warns when a {@code textField} or {@code subreport} element does not set
 * {@code removeLineWhenBlank="true"}. A blank text field or subreport still
 * occupies its band row, so unless the line is removed the report shows a gap;
 * the attribute is therefore required on those two kinds.
 *
 * <p>An explicit {@code removeLineWhenBlank="false"} is also flagged: the
 * attribute is present but the element will still leave its row behind.
 */
public final class RemoveLineWhenBlank implements Rule {

    private static final String ATTRIBUTE = "removeLineWhenBlank";
    private static final Set<String> KINDS = Set.of("textField", "subreport");

    @Override
    public List<Warning> check(Node root, String raw) {
        List<Warning> warnings = new ArrayList<>();
        collect(root, raw, warnings);
        return warnings;
    }

    private static void collect(Node node, String raw, List<Warning> warnings) {
        if (node.tag().equals("element") && KINDS.contains(node.kind())) {
            Optional<Attr> attr = Query.findAttr(node, ATTRIBUTE);
            if (attr.isEmpty()) {
                warnings.add(new Warning(node.startTag(),
                        node.kind() + " is missing " + ATTRIBUTE + "=\"true\""));
            } else {
                String value = raw.substring(attr.get().valueStart(), attr.get().valueEnd()).strip();
                if (!value.equals("true")) {
                    warnings.add(new Warning(node.startTag(), ATTRIBUTE + " is \"" + value + "\" on "
                            + node.kind() + "; expected \"true\""));
                }
            }
        }
        for (Node child : node.children()) {
            collect(child, raw, warnings);
        }
    }
}
