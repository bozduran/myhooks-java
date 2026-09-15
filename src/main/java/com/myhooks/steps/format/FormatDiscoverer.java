package com.myhooks.steps.format;

import com.myhooks.edit.Edit;
import com.myhooks.io.XmlSource;
import com.myhooks.jrutil.JrStringUtil;
import com.myhooks.step.Context;
import com.myhooks.step.Discoverer;
import com.myhooks.step.EditFix;
import com.myhooks.step.Fix;
import com.myhooks.step.Group;
import com.myhooks.textrules.JavaExpr;
import com.myhooks.xmlspan.Attr;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.Query;
import com.myhooks.xmlspan.XmlScanner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The format step's fix discovery: required element attributes, report-name
 * alignment, and Java-expression formatting. Text rules (period/newline) are
 * owned by textcheck, not here.
 *
 * <p>Each fix kind is its own {@link Group}, so the interactive review asks
 * about them separately: {@code positionType} and {@code textAdjust} are never
 * bundled into one question. {@code positionType="Float"} is required only on
 * {@code textField} and {@code subreport} elements, and
 * {@code textAdjust="StretchHeight"} only on {@code textField} elements.
 */
public final class FormatDiscoverer implements Discoverer {

    private static final String POSITION_TYPE = "positionType";
    private static final String TEXT_ADJUST = "textAdjust";
    private static final Set<String> POSITION_TYPE_KINDS = Set.of("textField", "subreport");
    private static final String CDATA_OPEN = "<![CDATA[";
    private static final String CDATA_CLOSE = "]]>";

    private static final Set<String> EXPRESSION_ELEMENTS = Set.of(
            "expression", "defaultValueExpression", "connectionExpression",
            "textFieldExpression", "textExpression", "patternExpression",
            "printWhenExpression", "initialValueExpression", "variableExpression",
            "groupExpression");

    @Override
    public List<Group> discover(Context context, Path path) throws Exception {
        String text = XmlSource.read(path).text();
        Node root = XmlScanner.scan(text);
        Buckets buckets = new Buckets();
        collect(root, text, baseName(path), context.color(), buckets);
        return buckets.groups();
    }

    private void collect(Node node, String raw, String expectedName, boolean color, Buckets buckets) {
        if (node.tag().equals("jasperReport")) {
            nameFix(node, raw, expectedName, color, buckets.names);
        } else if (node.tag().equals("element")) {
            elementFix(node, raw, color, buckets);
        } else if (EXPRESSION_ELEMENTS.contains(node.tag())) {
            expressionFix(node, raw, color, buckets.expressions);
        }
        for (Node child : node.children()) {
            collect(child, raw, expectedName, color, buckets);
        }
    }

    private void nameFix(Node jasperReport, String raw, String expectedName, boolean color, List<Fix> fixes) {
        String description = "set name=\"" + expectedName + "\"";
        Optional<Attr> name = Query.findAttr(jasperReport, "name");
        if (name.isPresent()) {
            Attr attr = name.get();
            String current = JrStringUtil.decode(raw.substring(attr.valueStart(), attr.valueEnd()));
            if (!current.equals(expectedName)) {
                fixes.add(EditFix.inElement(description,
                        new Edit(attr.valueStart(), attr.valueEnd(), JrStringUtil.encode(expectedName)),
                        color, raw, jasperReport));
            }
        } else {
            int pos = jasperReport.startTag() + 1 + jasperReport.tag().length();
            fixes.add(EditFix.inElement(description,
                    new Edit(pos, pos, " name=\"" + JrStringUtil.encode(expectedName) + "\""), color, raw, jasperReport));
        }
    }

    private void elementFix(Node element, String raw, boolean color, Buckets buckets) {
        String kind = element.kind();
        if (POSITION_TYPE_KINDS.contains(kind)) {
            positionTypeFix(element, raw, color, buckets.positionTypes);
        }
        if (kind.equals("textField")) {
            textAdjustFix(element, raw, color, buckets.textAdjusts);
        }
    }

    private void positionTypeFix(Node element, String raw, boolean color, List<Fix> fixes) {
        Optional<Attr> positionType = Query.findAttr(element, POSITION_TYPE);
        if (positionType.isEmpty()) {
            int pos = afterAttr(element, raw, "uuid", "kind");
            fixes.add(EditFix.inElement("add positionType=\"Float\"",
                    new Edit(pos, pos, " positionType=\"Float\""), color, raw, element));
        } else if (positionType.get().valueStart() == positionType.get().valueEnd()) {
            Attr attr = positionType.get();
            fixes.add(EditFix.inElement("set positionType=\"Float\"",
                    new Edit(attr.valueStart(), attr.valueEnd(), "Float"), color, raw, element));
        }
    }

    private void textAdjustFix(Node element, String raw, boolean color, List<Fix> fixes) {
        Optional<Attr> textAdjust = Query.findAttr(element, TEXT_ADJUST);
        if (textAdjust.isEmpty()) {
            int pos = beforeClose(element, raw);
            fixes.add(EditFix.inElement("add textAdjust=\"StretchHeight\"",
                    new Edit(pos, pos, " textAdjust=\"StretchHeight\""), color, raw, element));
        } else if (textAdjust.get().valueStart() == textAdjust.get().valueEnd()) {
            Attr attr = textAdjust.get();
            fixes.add(EditFix.inElement("set textAdjust=\"StretchHeight\"",
                    new Edit(attr.valueStart(), attr.valueEnd(), "StretchHeight"), color, raw, element));
        }
    }

    private void expressionFix(Node expression, String raw, boolean color, List<Fix> fixes) {
        int from = expression.startTagEnd();
        int to = expression.endTag();
        int open = raw.indexOf(CDATA_OPEN, from);
        if (open < 0 || open >= to) {
            return;
        }
        int contentStart = open + CDATA_OPEN.length();
        int close = raw.indexOf(CDATA_CLOSE, contentStart);
        if (close < 0 || close >= to) {
            return;
        }
        String body = raw.substring(contentStart, close);
        String formatted = JavaExpr.format(body);
        if (!formatted.equals(body)) {
            fixes.add(EditFix.inElement("format expression",
                    new Edit(contentStart, close, formatted), color, raw, expression));
        }
    }

    /**
     * The insertion point just after the first present anchor attribute, or just
     * after the tag name when none can be used. When an anchor is the element's
     * last attribute its successor is the tag close, which the textAdjust
     * insertion also targets; the tag-name position is chosen instead so the two
     * fixes never add edits on the same span (which {@code EditSet} rejects).
     */
    private int afterAttr(Node element, String raw, String... names) {
        for (String name : names) {
            Optional<Attr> attr = Query.findAttr(element, name);
            if (attr.isPresent()) {
                int pos = attr.get().valueEnd() + 1;
                if (pos < element.startTagEnd() && raw.charAt(pos) != '>' && raw.charAt(pos) != '/') {
                    return pos;
                }
            }
        }
        return element.startTag() + 1 + element.tag().length();
    }

    private int beforeClose(Node element, String raw) {
        int pos = element.startTagEnd() - 1; // before '>'
        if (pos > element.startTag() && raw.charAt(pos - 1) == '/') {
            pos--; // self-closing: before '/'
        }
        return pos;
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Collects the discovered fixes per kind so that each non-empty kind becomes
     * its own group (and therefore its own review question).
     */
    private static final class Buckets {

        private final List<Fix> names = new ArrayList<>();
        private final List<Fix> positionTypes = new ArrayList<>();
        private final List<Fix> textAdjusts = new ArrayList<>();
        private final List<Fix> expressions = new ArrayList<>();

        private List<Group> groups() {
            List<Group> groups = new ArrayList<>();
            add(groups, "report name", names);
            add(groups, "positionType", positionTypes);
            add(groups, "textAdjust", textAdjusts);
            add(groups, "expression formatting", expressions);
            return groups;
        }

        private static void add(List<Group> groups, String label, List<Fix> fixes) {
            if (!fixes.isEmpty()) {
                groups.add(new Group(label, fixes));
            }
        }
    }
}
