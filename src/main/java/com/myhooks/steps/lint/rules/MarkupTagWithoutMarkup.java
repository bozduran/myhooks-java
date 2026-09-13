package com.myhooks.steps.lint.rules;

import com.myhooks.steps.lint.Rule;
import com.myhooks.steps.lint.Warning;
import com.myhooks.textrules.JavaExpr;
import com.myhooks.xmlspan.Attr;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Warns when a {@code textField} expression contains JasperReports markup tags
 * but the element does not declare a markup language that makes the engine
 * interpret them.
 *
 * <p>The engine reads the {@code markup} attribute on the {@code textField}
 * element: {@code styled}, {@code html} and {@code rtf} all process embedded
 * tags, while the default (no attribute) and {@code none} render them as
 * literal text. A tag such as {@code <b>} is therefore shown verbatim in the
 * report unless one of the three processing values is set.
 *
 * <p>Only {@code textField} elements are inspected; the expression content is
 * scanned inside its string literals so Java generics or comparisons cannot be
 * mistaken for markup. Tags are limited to the JasperReports styled-text and
 * HTML tag set, matched case-insensitively.
 */
public final class MarkupTagWithoutMarkup implements Rule {

    private static final String CDATA_OPEN = "<![CDATA[";
    private static final String CDATA_CLOSE = "]]>";

    /** Markup values whose processors interpret embedded tags. */
    private static final Set<String> ACCEPTED_MARKUP = Set.of("styled", "html", "rtf");

    /** Tags recognised by the JasperReports styled-text / HTML markup processors. */
    private static final Set<String> TAGS = Set.of(
            "a", "b", "blockquote", "body", "br", "center", "code", "div", "em", "font",
            "h1", "h2", "h3", "h4", "h5", "h6", "hr", "html", "i", "img", "li", "ol",
            "p", "pre", "s", "span", "strike", "strong", "style", "sub", "sup", "table",
            "td", "th", "tr", "u", "ul");

    /** An opening, closing or self-closing tag with optional {@code name="value"} attributes. */
    private static final Pattern TAG = Pattern.compile(
            "</?([a-zA-Z][a-zA-Z0-9]*)"
                    + "(?:\\s+[a-zA-Z_:][-a-zA-Z0-9_:.]*"
                    + "\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+))*"
                    + "\\s*/?>");

    @Override
    public List<Warning> check(Node root, String raw) {
        List<Warning> warnings = new ArrayList<>();
        for (Node element : Query.descendants(root, "element")) {
            if (!element.kind().equals("textField") || hasMarkup(element, raw)) {
                continue;
            }
            String tag = firstTag(expressionContent(element, raw));
            if (tag != null) {
                warnings.add(new Warning(element.startTag(),
                        "text contains markup tag " + tag
                                + " but markup is not \"styled\", \"html\" or \"rtf\""));
            }
        }
        return warnings;
    }

    /** True when the element sets an accepted {@code markup} value. */
    private static boolean hasMarkup(Node element, String raw) {
        Optional<Attr> markup = Query.findAttr(element, "markup");
        if (markup.isEmpty()) {
            return false;
        }
        String value = raw.substring(markup.get().valueStart(), markup.get().valueEnd());
        return ACCEPTED_MARKUP.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * The text of the element's {@code <expression>} / {@code <textFieldExpression>}
     * child with any CDATA wrapper removed, or an empty string when there is none.
     */
    private static String expressionContent(Node element, String raw) {
        for (Node child : element.children()) {
            if (!child.tag().equals("expression") && !child.tag().equals("textFieldExpression")) {
                continue;
            }
            if (child.endTag() <= child.startTagEnd()) {
                return ""; // self-closing or empty element
            }
            String text = raw.substring(child.startTagEnd(), child.endTag());
            int open = text.indexOf(CDATA_OPEN);
            if (open >= 0) {
                int close = text.indexOf(CDATA_CLOSE, open + CDATA_OPEN.length());
                if (close >= 0) {
                    return text.substring(open + CDATA_OPEN.length(), close);
                }
            }
            return text;
        }
        return "";
    }

    /** The first markup tag found inside the expression's string literals, or null. */
    private static String firstTag(String expression) {
        int i = 0;
        int n = expression.length();
        while (i < n) {
            int end = JavaExpr.literalEnd(expression, i);
            if (end > i && expression.charAt(i) == '"') {
                String literal = literalContent(expression, i, end);
                if (literal != null) {
                    String tag = tagIn(literal);
                    if (tag != null) {
                        return tag;
                    }
                }
                i = end;
                continue;
            }
            i++;
        }
        return null;
    }

    /** The inside of a terminated string or text-block literal, or null. */
    private static String literalContent(String text, int start, int end) {
        if (text.startsWith("\"\"\"", start)) {
            if (end >= start + 6 && text.startsWith("\"\"\"", end - 3)) {
                return text.substring(start + 3, end - 3);
            }
            return null;
        }
        if (end > start + 1 && text.charAt(end - 1) == '"') {
            return text.substring(start + 1, end - 1);
        }
        return null;
    }

    /** The first known markup tag in {@code text}, or null. */
    private static String tagIn(String text) {
        Matcher matcher = TAG.matcher(text);
        while (matcher.find()) {
            if (TAGS.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                return matcher.group();
            }
        }
        return null;
    }
}
