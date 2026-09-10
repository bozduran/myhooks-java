package com.myhooks.steps.lint.rules;

import com.github.javaparser.Position;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.myhooks.steps.lint.Rule;
import com.myhooks.steps.lint.Warning;
import com.myhooks.textrules.JavaExpr;
import com.myhooks.xmlspan.Node;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Warns when a {@code $F{}}/{@code $P{}}/{@code $V{}} reference is the receiver
 * of a method call without a null check that dominates the call, because that
 * call throws {@link NullPointerException} when the reference is null.
 *
 * <p>The analysis is short-circuit aware: {@code $V{v} != null && $V{v}.foo()}
 * is safe, as is {@code $V{v} == null || $V{v}.foo()} and
 * {@code $V{v} != null ? $V{v}.foo() : ""}, while the null branch itself is
 * still flagged. Recognised checks are {@code != null} / {@code == null} in
 * either operand order, their negations ({@code !($V{v} == null)}),
 * {@code EQUALS($V{v}, null)} (case-insensitive), and
 * {@code Objects.equals($V{v}, null)} / {@code Objects.nonNull($V{v})} /
 * {@code Objects.isNull($V{v})}.
 *
 * <p>References are masked to same-length Java identifiers before parsing, so
 * AST offsets map back to the raw expression; anything that is not valid Java
 * (or a reference name that is not an identifier) is skipped rather than
 * guessed at.
 */
public final class NullDereference implements Rule {

    private static final Set<String> EXPRESSION_ELEMENTS = Set.of(
            "expression", "defaultValueExpression", "connectionExpression",
            "textFieldExpression", "textExpression", "patternExpression",
            "printWhenExpression", "initialValueExpression", "variableExpression",
            "groupExpression");

    private static final String CDATA_OPEN = "<![CDATA[";
    private static final String CDATA_CLOSE = "]]>";

    /** A JasperReports reference: kind is F, P or V. */
    private record Ref(char kind, String name) {
        String key() {
            return kind + ":" + name;
        }

        String display() {
            return "$" + kind + "{" + name + "}";
        }
    }

    @Override
    public List<Warning> check(Node root, String raw) {
        List<Warning> warnings = new ArrayList<>();
        for (Node element : expressionElements(root)) {
            Content content = contentOf(element, raw);
            if (content == null || content.text().isBlank()) {
                continue;
            }
            String masked = maskReferences(content.text());
            Expression parsed;
            try {
                parsed = StaticJavaParser.parseExpression(masked);
            } catch (RuntimeException notJava) {
                continue; // not a parseable Java expression: leave it alone
            }
            int[] lineStarts = lineStarts(masked);
            walk(parsed, Set.of(), lineStarts, content.start(), warnings);
        }
        return warnings;
    }

    // ------------------------------------------------------------------
    // Flow walk
    // ------------------------------------------------------------------

    private static void walk(Expression expression, Set<String> nonNull,
            int[] lineStarts, int base, List<Warning> warnings) {
        Expression e = unwrap(expression);

        if (e instanceof ConditionalExpr conditional) {
            walk(conditional.getCondition(), nonNull, lineStarts, base, warnings);
            walk(conditional.getThenExpr(), union(nonNull, whenTrue(conditional.getCondition())),
                    lineStarts, base, warnings);
            walk(conditional.getElseExpr(), union(nonNull, whenFalse(conditional.getCondition())),
                    lineStarts, base, warnings);
            return;
        }

        if (e instanceof BinaryExpr binary) {
            if (binary.getOperator() == BinaryExpr.Operator.AND) {
                walk(binary.getLeft(), nonNull, lineStarts, base, warnings);
                walk(binary.getRight(), union(nonNull, whenTrue(binary.getLeft())), lineStarts, base, warnings);
                return;
            }
            if (binary.getOperator() == BinaryExpr.Operator.OR) {
                walk(binary.getLeft(), nonNull, lineStarts, base, warnings);
                walk(binary.getRight(), union(nonNull, whenFalse(binary.getLeft())), lineStarts, base, warnings);
                return;
            }
            walk(binary.getLeft(), nonNull, lineStarts, base, warnings);
            walk(binary.getRight(), nonNull, lineStarts, base, warnings);
            return;
        }

        if (e instanceof MethodCallExpr call) {
            Expression scope = call.getScope().orElse(null);
            if (scope != null) {
                Ref receiver = refName(scope);
                if (receiver != null) {
                    if (!nonNull.contains(receiver.key())) {
                        warnings.add(new Warning(base + offset(lineStarts, call),
                                receiver.display() + " may be null when ." + call.getNameAsString()
                                        + "(...) is called; check it for null first"));
                    }
                } else {
                    walk(scope, nonNull, lineStarts, base, warnings);
                }
            }
            for (Expression argument : call.getArguments()) {
                walk(argument, nonNull, lineStarts, base, warnings);
            }
            return;
        }

        if (e instanceof CastExpr cast) {
            walk(cast.getExpression(), nonNull, lineStarts, base, warnings);
            return;
        }

        // Everything else (unary, array access, object creation, ...): recurse.
        for (var child : e.getChildNodes()) {
            if (child instanceof Expression childExpression) {
                walk(childExpression, nonNull, lineStarts, base, warnings);
            }
        }
    }

    /** References guaranteed non-null when {@code expression} evaluates to true. */
    private static Set<String> whenTrue(Expression expression) {
        Expression e = unwrap(expression);
        if (e instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return whenFalse(unary.getExpression());
        }
        if (e instanceof BinaryExpr binary) {
            if (binary.getOperator() == BinaryExpr.Operator.AND) {
                return union(whenTrue(binary.getLeft()), whenTrue(binary.getRight()));
            }
            if (binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS) {
                Ref ref = refComparedToNull(binary);
                if (ref != null) {
                    return Set.of(ref.key());
                }
            }
            return Set.of();
        }
        if (e instanceof MethodCallExpr call) {
            Ref ref = oneArgumentCall(call, "nonNull", false);
            if (ref != null) {
                return Set.of(ref.key());
            }
        }
        return Set.of();
    }

    /** References guaranteed non-null when {@code expression} evaluates to false. */
    private static Set<String> whenFalse(Expression expression) {
        Expression e = unwrap(expression);
        if (e instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return whenTrue(unary.getExpression());
        }
        if (e instanceof BinaryExpr binary) {
            if (binary.getOperator() == BinaryExpr.Operator.OR) {
                return union(whenFalse(binary.getLeft()), whenFalse(binary.getRight()));
            }
            if (binary.getOperator() == BinaryExpr.Operator.EQUALS) {
                Ref ref = refComparedToNull(binary);
                if (ref != null) {
                    return Set.of(ref.key());
                }
            }
            return Set.of();
        }
        if (e instanceof MethodCallExpr call) {
            Ref ref = nullCheckCall(call);
            if (ref != null) {
                return Set.of(ref.key());
            }
        }
        return Set.of();
    }

    // ------------------------------------------------------------------
    // Null-check recognition
    // ------------------------------------------------------------------

    /** {@code ref == null} / {@code ref != null} in either operand order. */
    private static Ref refComparedToNull(BinaryExpr binary) {
        Ref left = refName(binary.getLeft());
        if (left != null && unwrap(binary.getRight()) instanceof NullLiteralExpr) {
            return left;
        }
        Ref right = refName(binary.getRight());
        if (right != null && unwrap(binary.getLeft()) instanceof NullLiteralExpr) {
            return right;
        }
        return null;
    }

    /** True when the call asserts the reference is null: EQUALS(ref,null), Objects.equals/isNull. */
    private static Ref nullCheckCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        List<Expression> args = call.getArguments();
        Expression scope = call.getScope().orElse(null);
        if (scope == null && name.equalsIgnoreCase("equals")) {
            return refWithNullArgument(args);
        }
        if (isObjects(scope)) {
            if (name.equals("equals")) {
                return refWithNullArgument(args);
            }
            if (name.equals("isNull")) {
                return oneRefArgument(args);
            }
        }
        return null;
    }

    /** True when the call asserts the reference is non-null: Objects.nonNull. */
    private static Ref oneArgumentCall(MethodCallExpr call, String expected, boolean caseInsensitive) {
        String name = call.getNameAsString();
        boolean matches = caseInsensitive ? name.equalsIgnoreCase(expected) : name.equals(expected);
        if (!matches || !isObjects(call.getScope().orElse(null))) {
            return null;
        }
        return oneRefArgument(call.getArguments());
    }

    private static boolean isObjects(Expression scope) {
        if (scope instanceof NameExpr name) {
            return name.getNameAsString().equals("Objects");
        }
        if (scope instanceof FieldAccessExpr field) {
            return field.getNameAsString().equals("Objects");
        }
        return false;
    }

    /** The reference in {@code EQUALS(ref, null)} / {@code EQUALS(null, ref)}. */
    private static Ref refWithNullArgument(List<Expression> args) {
        if (args.size() != 2) {
            return null;
        }
        Ref first = refName(args.get(0));
        if (first != null && unwrap(args.get(1)) instanceof NullLiteralExpr) {
            return first;
        }
        Ref second = refName(args.get(1));
        if (second != null && unwrap(args.get(0)) instanceof NullLiteralExpr) {
            return second;
        }
        return null;
    }

    private static Ref oneRefArgument(List<Expression> args) {
        return args.size() == 1 ? refName(args.get(0)) : null;
    }

    // ------------------------------------------------------------------
    // References, masking and offsets
    // ------------------------------------------------------------------

    /**
     * Replaces {@code $F{name}} / {@code $P{name}} / {@code $V{name}} with a
     * same-length identifier so JavaParser can parse the expression and AST
     * offsets still map to the raw text. Literals are copied unchanged, so a
     * reference inside a string or char literal is never masked.
     */
    static String maskReferences(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int n = code.length();
        while (i < n) {
            int literalEnd = JavaExpr.literalEnd(code, i);
            if (literalEnd > i) {
                out.append(code, i, literalEnd);
                i = literalEnd;
                continue;
            }
            char c = code.charAt(i);
            if (c == '$' && i + 2 < n) {
                char kind = code.charAt(i + 1);
                if ((kind == 'F' || kind == 'P' || kind == 'V') && code.charAt(i + 2) == '{') {
                    int close = code.indexOf('}', i + 3);
                    if (close > i + 3) {
                        String name = code.substring(i + 3, close);
                        String masked = "$" + kind + "_" + name + "_";
                        if (masked.length() == close + 1 - i && isIdentifier(name)) {
                            out.append(masked);
                            i = close + 1;
                            continue;
                        }
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isIdentifier(String name) {
        if (name.isEmpty() || !(Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** The reference behind a masked {@code $K_name_} identifier, or null. */
    private static Ref refName(Expression expression) {
        Expression e = unwrap(expression);
        if (!(e instanceof NameExpr nameExpr)) {
            return null;
        }
        String id = nameExpr.getNameAsString();
        if (id.length() < 5 || id.charAt(0) != '$' || id.charAt(2) != '_' || id.charAt(id.length() - 1) != '_') {
            return null;
        }
        char kind = id.charAt(1);
        if (kind != 'F' && kind != 'P' && kind != 'V') {
            return null;
        }
        String name = id.substring(3, id.length() - 1);
        return name.isEmpty() ? null : new Ref(kind, name);
    }

    private static Expression unwrap(Expression expression) {
        Expression e = expression;
        while (e instanceof EnclosedExpr enclosed) {
            e = enclosed.getInner();
        }
        return e;
    }

    private static Set<String> union(Set<String> base, Set<String> extra) {
        if (extra.isEmpty()) {
            return base;
        }
        Set<String> out = new HashSet<>(base);
        out.addAll(extra);
        return out;
    }

    private static int offset(int[] lineStarts, com.github.javaparser.ast.Node node) {
        Position begin = node.getRange().orElseThrow().begin;
        return lineStarts[begin.line - 1] + (begin.column - 1);
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = starts.get(i);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Expression extraction
    // ------------------------------------------------------------------

    private record Content(int start, String text) {
    }

    private static List<Node> expressionElements(Node root) {
        List<Node> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node node, List<Node> out) {
        for (Node child : node.children()) {
            if (EXPRESSION_ELEMENTS.contains(child.tag())) {
                out.add(child);
            }
            collect(child, out);
        }
    }

    private static Content contentOf(Node node, String raw) {
        int from = node.startTagEnd();
        int to = node.endTag();
        int open = raw.indexOf(CDATA_OPEN, from);
        if (open >= 0 && open < to) {
            int start = open + CDATA_OPEN.length();
            int close = raw.indexOf(CDATA_CLOSE, start);
            if (close >= 0 && close <= to) {
                return new Content(start, raw.substring(start, close));
            }
        }
        return to > from ? new Content(from, raw.substring(from, to)) : null;
    }
}
