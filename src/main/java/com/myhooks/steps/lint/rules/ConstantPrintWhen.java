package com.myhooks.steps.lint.rules;

import com.myhooks.steps.lint.Rule;
import com.myhooks.steps.lint.Warning;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.Query;
import java.util.ArrayList;
import java.util.List;

/**
 * Warns when a {@code <printWhenExpression>} holds a constant boolean literal
 * ({@code true} or {@code false}) rather than an expression, which makes the
 * element's visibility condition redundant.
 */
public final class ConstantPrintWhen implements Rule {

    @Override
    public List<Warning> check(Node root, String raw) {
        List<Warning> warnings = new ArrayList<>();
        for (Node node : Query.descendants(root, "printWhenExpression")) {
            String value = content(node, raw);
            if (value.equals("true") || value.equals("false")) {
                warnings.add(new Warning(node.startTag(),
                        "printWhenExpression is a constant '" + value + "'"));
            }
        }
        return warnings;
    }

    /** The element's text content with any CDATA wrapper stripped, then trimmed. */
    private static String content(Node node, String raw) {
        String text = raw.substring(node.startTagEnd(), node.endTag());
        if (text.startsWith("<![CDATA[") && text.endsWith("]]>")) {
            text = text.substring("<![CDATA[".length(), text.length() - "]]>".length());
        }
        return text.strip();
    }
}
