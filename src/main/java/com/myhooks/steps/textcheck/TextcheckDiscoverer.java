package com.myhooks.steps.textcheck;

import com.myhooks.edit.Edit;
import com.myhooks.io.XmlSource;
import com.myhooks.step.Context;
import com.myhooks.step.Discoverer;
import com.myhooks.step.EditFix;
import com.myhooks.step.Fix;
import com.myhooks.step.Group;
import com.myhooks.textrules.DoubleSpace;
import com.myhooks.textrules.JavaExpr;
import com.myhooks.textrules.Newline;
import com.myhooks.textrules.PeriodSpace;
import com.myhooks.textrules.TextResult;
import com.myhooks.textrules.Unrenderable;
import com.myhooks.xmlspan.Attr;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.Query;
import com.myhooks.xmlspan.XmlScanner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The textcheck step's fix discovery: every rendered-text rule (period space,
 * double-space, unrenderable characters, and newline normalization) applied via
 * {@code textrules}. String literals inside expressions are transformed without
 * touching the surrounding code.
 */
public final class TextcheckDiscoverer implements Discoverer {

    private static final String CDATA_OPEN = "<![CDATA[";
    private static final String CDATA_CLOSE = "]]>";

    private static final Set<String> EXPRESSION_ELEMENTS = Set.of(
            "expression", "defaultValueExpression", "connectionExpression",
            "textFieldExpression", "textExpression", "patternExpression",
            "printWhenExpression", "initialValueExpression", "variableExpression",
            "groupExpression");

    @Override
    public List<Group> discover(Context context, Path path) throws Exception {
        String raw = XmlSource.read(path).text();
        Node root = XmlScanner.scan(raw);
        List<Fix> fixes = new ArrayList<>();
        walk(root, raw, context.color(), fixes);
        return fixes.isEmpty() ? List.of() : List.of(new Group("textcheck", fixes));
    }

    private void walk(Node node, String raw, boolean color, List<Fix> fixes) {
        if (node.tag().equals("text")) {
            textFix(node, raw, markupOf(node, raw), color, fixes);
        } else if (EXPRESSION_ELEMENTS.contains(node.tag())) {
            expressionFix(node, raw, isTextContext(node), markupOf(node, raw), color, fixes);
        }
        for (Node child : node.children()) {
            walk(child, raw, color, fixes);
        }
    }

    private void textFix(Node textNode, String raw, String markup, boolean color, List<Fix> fixes) {
        Cdata cdata = cdataOf(textNode, raw);
        if (cdata == null) {
            return;
        }
        List<String> findings = new ArrayList<>();
        String transformed = transformText(cdata.content(), markup, findings);
        addFixIfChanged(cdata, transformed, findings, color, fixes);
    }

    private void expressionFix(Node expression, String raw, boolean isText, String markup,
            boolean color, List<Fix> fixes) {
        Cdata cdata = cdataOf(expression, raw);
        if (cdata == null) {
            return;
        }
        List<String> findings = new ArrayList<>();
        String transformed = transformExpression(cdata.content(), isText, markup, findings);
        addFixIfChanged(cdata, transformed, findings, color, fixes);
    }

    private void addFixIfChanged(Cdata cdata, String transformed, List<String> findings,
            boolean color, List<Fix> fixes) {
        if (!transformed.equals(cdata.content())) {
            String description = findings.isEmpty() ? "fix text" : String.join(", ", findings);
            fixes.add(new EditFix(description, cdata.content(), transformed,
                    new Edit(cdata.start(), cdata.end(), transformed), color));
        }
    }

    // ------------------------------------------------------------------
    // Transformations
    // ------------------------------------------------------------------

    /** Applies all rendered-text rules to a block of text. */
    static String transformText(String content, String markup, List<String> findings) {
        content = apply(content, Unrenderable::apply, findings);
        content = apply(content, PeriodSpace::apply, findings);
        content = apply(content, DoubleSpace::apply, findings);
        String normalized = Newline.apply(content, markup);
        if (!normalized.equals(content)) {
            findings.add("normalize newline");
            content = normalized;
        }
        return content;
    }

    /** Applies the literal-safe rules (no period, no newline) to a string literal. */
    static String transformLiteral(String content, List<String> findings) {
        content = apply(content, Unrenderable::apply, findings);
        content = apply(content, DoubleSpace::apply, findings);
        return content;
    }

    /**
     * Transforms the string literals inside an expression, leaving code and char
     * literals untouched. Literals are located with the same escape-aware
     * scanner {@link JavaExpr} uses to format expressions, so a quote inside a
     * char literal cannot start a string and a text block is treated as one
     * literal instead of a run of empty strings.
     */
    static String transformExpression(String content, boolean isText, String markup, List<String> findings) {
        StringBuilder out = new StringBuilder(content.length());
        int i = 0;
        int n = content.length();
        while (i < n) {
            int end = JavaExpr.literalEnd(content, i);
            if (end < 0) {
                out.append(content.charAt(i));
                i++;
                continue;
            }
            char quote = content.charAt(i);
            boolean textBlock = quote == '"' && content.startsWith("\"\"\"", i);
            boolean terminated = textBlock
                    ? end >= i + 6 && content.startsWith("\"\"\"", end - 3)
                    : end > i + 1 && content.charAt(end - 1) == quote;
            if (!terminated) {
                out.append(content, i, n);
                break;
            }
            if (quote == '\'') {
                out.append(content, i, end); // char literals are never rewritten
                i = end;
                continue;
            }
            int innerStart = textBlock ? i + 3 : i + 1;
            int innerEnd = textBlock ? end - 3 : end - 1;
            String literal = content.substring(innerStart, innerEnd);
            out.append(content, i, innerStart);
            out.append(isText ? transformText(literal, markup, findings) : transformLiteral(literal, findings));
            out.append(content, innerEnd, end);
            i = end;
        }
        return out.toString();
    }

    private static String apply(String content, Function<String, TextResult> rule, List<String> findings) {
        TextResult result = rule.apply(content);
        if (result.changed()) {
            findings.addAll(result.findings());
            return result.text();
        }
        return content;
    }

    // ------------------------------------------------------------------
    // Classification and helpers
    // ------------------------------------------------------------------

    static boolean isTextContext(Node node) {
        return Query.isRenderedTextContext(node)
                || node.tag().equals("defaultValueExpression")
                || node.tag().equals("initialValueExpression");
    }

    private String markupOf(Node node, String raw) {
        for (Node n = node.parent(); n != null; n = n.parent()) {
            if (n.tag().equals("element")) {
                Optional<Attr> markup = Query.findAttr(n, "markup");
                return markup.map(attr -> raw.substring(attr.valueStart(), attr.valueEnd())).orElse("");
            }
        }
        return "";
    }

    private record Cdata(int start, int end, String content) {
    }

    private static Cdata cdataOf(Node node, String raw) {
        int from = node.startTagEnd();
        int to = node.endTag();
        int open = raw.indexOf(CDATA_OPEN, from);
        if (open < 0 || open >= to) {
            return null;
        }
        int start = open + CDATA_OPEN.length();
        int close = raw.indexOf(CDATA_CLOSE, start);
        if (close < 0 || close >= to) {
            return null;
        }
        return new Cdata(start, close, raw.substring(start, close));
    }
}
