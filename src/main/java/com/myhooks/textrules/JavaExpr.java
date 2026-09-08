package com.myhooks.textrules;

import com.github.javaparser.JavaToken;
import com.github.javaparser.Position;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.myhooks.edit.Edit;
import com.myhooks.edit.EditSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Formats a Java expression's code spacing using the JavaParser AST, while
 * leaving string, char, and text-block literals (and JasperReports
 * {@code $F{}/$P{}/$V{}} references) byte-for-byte untouched.
 *
 * <p>The rules port the Go regex tokenizer:
 * <ul>
 *   <li>binary operators {@code == != < > >= <= && ||} → single surrounding space;</li>
 *   <li>ternary {@code ?} and {@code :} → single surrounding space;</li>
 *   <li>comma followed by a non-space → {@code ", "};</li>
 *   <li>an uppercase reference-type cast {@code (Type)x} → {@code (Type) x}.</li>
 * </ul>
 */
public final class JavaExpr {

    private static final String MASK_PREFIX = "__myhooks_";

    private static final Set<BinaryExpr.Operator> SPACED_BINARY = Set.of(
            BinaryExpr.Operator.EQUALS,
            BinaryExpr.Operator.NOT_EQUALS,
            BinaryExpr.Operator.LESS,
            BinaryExpr.Operator.GREATER,
            BinaryExpr.Operator.GREATER_EQUALS,
            BinaryExpr.Operator.LESS_EQUALS,
            BinaryExpr.Operator.AND,
            BinaryExpr.Operator.OR);

    private JavaExpr() {
    }

    public static String format(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        Mask mask = mask(code);
        Expression expression;
        try {
            expression = StaticJavaParser.parseExpression(mask.text());
        } catch (RuntimeException parseFailure) {
            return code; // not valid Java even after masking — leave untouched
        }

        int[] lineStarts = lineStarts(mask.text());
        List<Edit> edits = new ArrayList<>();
        collectEdits(expression, mask.text(), lineStarts, edits);
        if (edits.isEmpty()) {
            return code;
        }
        String formatted = applyEdits(mask.text(), edits);
        return unmask(formatted, mask);
    }

    // ------------------------------------------------------------------
    // AST walking
    // ------------------------------------------------------------------

    private static void collectEdits(Node node, String masked, int[] lineStarts, List<Edit> edits) {
        node.walk(Node.class, n -> {
            if (n instanceof BinaryExpr binary && SPACED_BINARY.contains(binary.getOperator())) {
                int leftEnd = endExclusive(lineStarts, binary.getLeft().getRange().orElseThrow().end);
                int rightBegin = begin(lineStarts, binary.getRight().getRange().orElseThrow().begin);
                edits.add(new Edit(leftEnd, rightBegin, " " + binary.getOperator().asString() + " "));
            } else if (n instanceof ConditionalExpr conditional) {
                int conditionEnd = endExclusive(lineStarts, conditional.getCondition().getRange().orElseThrow().end);
                int thenBegin = begin(lineStarts, conditional.getThenExpr().getRange().orElseThrow().begin);
                edits.add(new Edit(conditionEnd, thenBegin, " ? "));

                int thenEnd = endExclusive(lineStarts, conditional.getThenExpr().getRange().orElseThrow().end);
                int elseBegin = begin(lineStarts, conditional.getElseExpr().getRange().orElseThrow().begin);
                edits.add(new Edit(thenEnd, elseBegin, " : "));
            } else if (n instanceof CastExpr cast) {
                addCastSpace(cast, masked, lineStarts, edits);
            }
        });

        // Comma rule (global, like the Go tokenizer): ",x" -> ", x".
        for (JavaToken token : node.getTokenRange().orElseThrow()) {
            if (",".equals(token.getText())) {
                int comma = begin(lineStarts, token.getRange().orElseThrow().begin);
                if (comma + 1 < masked.length() && !Character.isWhitespace(masked.charAt(comma + 1))) {
                    edits.add(new Edit(comma + 1, comma + 1, " "));
                }
            }
        }
    }

    private static void addCastSpace(CastExpr cast, String masked, int[] lineStarts, List<Edit> edits) {
        String typeText = cast.getType().asString();
        if (!typeText.matches("[A-Z][A-Za-z0-9_$.]*")) {
            return; // primitives and generics are left alone, matching the Go rule
        }
        int afterType = endExclusive(lineStarts, cast.getType().getRange().orElseThrow().end);
        if (afterType >= masked.length() || masked.charAt(afterType) != ')') {
            return;
        }
        int afterParen = afterType + 1;
        if (afterParen < masked.length()
                && !Character.isWhitespace(masked.charAt(afterParen))
                && masked.charAt(afterParen) != ')') {
            edits.add(new Edit(afterParen, afterParen, " "));
        }
    }

    // ------------------------------------------------------------------
    // Offsets
    // ------------------------------------------------------------------

    private static int[] lineStarts(String s) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = starts.get(i);
        }
        return out;
    }

    private static int begin(int[] lineStarts, Position position) {
        return lineStarts[position.line - 1] + (position.column - 1);
    }

    private static int endExclusive(int[] lineStarts, Position position) {
        // JavaParser positions are 1-based and the range end column is inclusive.
        return lineStarts[position.line - 1] + position.column;
    }

    // ------------------------------------------------------------------
    // Masking of literals and JasperReports references
    // ------------------------------------------------------------------

    private record Mask(String text, List<String> replacements) {
    }

    private static Mask mask(String code) {
        StringBuilder out = new StringBuilder(code.length());
        List<String> replacements = new ArrayList<>();
        int i = 0;
        int n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            int end;
            if (i + 2 < n && code.startsWith("\"\"\"", i)) {
                end = scanTextBlock(code, i + 3);
            } else if (c == '"') {
                end = scanQuoted(code, i, '"');
            } else if (c == '\'') {
                end = scanQuoted(code, i, '\'');
            } else if (c == '$' && i + 1 < n && isJrReferenceStart(code, i)) {
                end = scanJrReference(code, i);
            } else {
                out.append(c);
                i++;
                continue;
            }
            replacements.add(code.substring(i, end));
            out.append(MASK_PREFIX).append(replacements.size() - 1).append("__");
            i = end;
        }
        return new Mask(out.toString(), replacements);
    }

    private static boolean isJrReferenceStart(String s, int dollar) {
        char after = s.charAt(dollar + 1);
        return Character.isLetter(after);
    }

    private static int scanQuoted(String s, int start, char quote) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return s.length();
    }

    private static int scanTextBlock(String s, int start) {
        int i = start;
        while (i + 2 < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (s.startsWith("\"\"\"", i)) {
                return i + 3;
            }
            i++;
        }
        return s.length();
    }

    private static int scanJrReference(String s, int start) {
        int i = start + 1; // skip '$'
        if (i < s.length() && Character.isLetter(s.charAt(i))) {
            i++;
        }
        if (i < s.length() && s.charAt(i) == '!') {
            i++; // e.g. $P!{...}
        }
        if (i < s.length() && s.charAt(i) == '{') {
            int close = s.indexOf('}', i + 1);
            return close < 0 ? s.length() : close + 1;
        }
        return start + 1;
    }

    private static String unmask(String text, Mask mask) {
        String result = text;
        for (int k = mask.replacements().size() - 1; k >= 0; k--) {
            result = result.replace(MASK_PREFIX + k + "__", mask.replacements().get(k));
        }
        return result;
    }

    private static String applyEdits(String text, List<Edit> edits) {
        EditSet set = new EditSet();
        edits.forEach(set::add);
        return set.apply(text);
    }
}
