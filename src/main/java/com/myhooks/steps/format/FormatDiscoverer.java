package com.myhooks.steps.format;

import com.myhooks.edit.Edit;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The format step's fix discovery: required element attributes, report-name
 * alignment, and Java-expression formatting. Text rules (period/newline) are
 * owned by textcheck, not here.
 */
public final class FormatDiscoverer implements Discoverer {

    private static final String POSITION_TYPE = "positionType";
    private static final String TEXT_ADJUST = "textAdjust";
    private static final String CDATA_OPEN = "<![CDATA[";
    private static final String CDATA_CLOSE = "]]>";

    private static final Set<String> EXPRESSION_ELEMENTS = Set.of(
            "expression", "defaultValueExpression", "connectionExpression",
            "textFieldExpression", "textExpression", "patternExpression",
            "printWhenExpression", "initialValueExpression", "variableExpression",
            "groupExpression");

    @Override
    public List<Group> discover(Context context, Path path) throws Exception {
        byte[] raw = Files.readAllBytes(path);
        String text = new String(raw, StandardCharsets.UTF_8);
        Node root = XmlScanner.scan(raw);
        List<Fix> fixes = new ArrayList<>();
        collect(root, text, baseName(path), context.color(), fixes);
        return fixes.isEmpty() ? List.of() : List.of(new Group("format", fixes));
    }

    private void collect(Node node, String raw, String expectedName, boolean color, List<Fix> fixes) {
        if (node.tag().equals("jasperReport")) {
            nameFix(node, raw, expectedName, color, fixes);
        } else if (node.tag().equals("element")) {
            elementFix(node, raw, color, fixes);
        } else if (EXPRESSION_ELEMENTS.contains(node.tag())) {
            expressionFix(node, raw, color, fixes);
        }
        for (Node child : node.children()) {
            collect(child, raw, expectedName, color, fixes);
        }
    }

    private void nameFix(Node jasperReport, String raw, String expectedName, boolean color, List<Fix> fixes) {
        String description = "set name=\"" + expectedName + "\"";
        Optional<Attr> name = Query.findAttr(jasperReport, "name");
        if (name.isPresent()) {
            Attr attr = name.get();
            String current = raw.substring(attr.valueStart(), attr.valueEnd());
            if (!current.equals(expectedName)) {
                fixes.add(new EditFix(description, current, expectedName,
                        new Edit(attr.valueStart(), attr.valueEnd(), JrStringUtil.encode(expectedName)), color));
            }
        } else {
            int pos = jasperReport.startTag() + 1 + jasperReport.tag().length();
            fixes.add(new EditFix(description, "", expectedName,
                    new Edit(pos, pos, " name=\"" + JrStringUtil.encode(expectedName) + "\""), color));
        }
    }

    private void elementFix(Node element, String raw, boolean color, List<Fix> fixes) {
        Optional<Attr> positionType = Query.findAttr(element, POSITION_TYPE);
        if (positionType.isEmpty()) {
            int pos = afterAttr(element, "uuid", "kind");
            fixes.add(new EditFix("add positionType=\"Float\"", "", "positionType=\"Float\"",
                    new Edit(pos, pos, " positionType=\"Float\""), color));
        } else if (positionType.get().valueStart() == positionType.get().valueEnd()) {
            Attr attr = positionType.get();
            fixes.add(new EditFix("set positionType=\"Float\"", "", "Float",
                    new Edit(attr.valueStart(), attr.valueEnd(), "Float"), color));
        }

        if (element.kind().equals("textField")) {
            Optional<Attr> textAdjust = Query.findAttr(element, TEXT_ADJUST);
            if (textAdjust.isEmpty()) {
                int pos = beforeClose(element, raw);
                fixes.add(new EditFix("add textAdjust=\"StretchHeight\"", "", "textAdjust=\"StretchHeight\"",
                        new Edit(pos, pos, " textAdjust=\"StretchHeight\""), color));
            } else if (textAdjust.get().valueStart() == textAdjust.get().valueEnd()) {
                Attr attr = textAdjust.get();
                fixes.add(new EditFix("set textAdjust=\"StretchHeight\"", "", "StretchHeight",
                        new Edit(attr.valueStart(), attr.valueEnd(), "StretchHeight"), color));
            }
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
            fixes.add(new EditFix("format expression", body, formatted,
                    new Edit(contentStart, close, formatted), color));
        }
    }

    private int afterAttr(Node element, String... names) {
        for (String name : names) {
            Optional<Attr> attr = Query.findAttr(element, name);
            if (attr.isPresent()) {
                return attr.get().valueEnd() + 1;
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
}
