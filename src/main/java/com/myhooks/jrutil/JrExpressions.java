package com.myhooks.jrutil;

import java.util.ArrayList;
import java.util.List;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRExpression;
import net.sf.jasperreports.engine.JRExpressionChunk;
import net.sf.jasperreports.engine.JRExpressionCollector;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.util.JRExpressionUtil;

/**
 * Extracts the ground-truth {@code $F{}} / {@code $P{}} / {@code $V{}}
 * references from a compiled report design, using the engine's own expression
 * chunk decomposition (rather than regex).
 */
public final class JrExpressions {

    public enum Kind {
        FIELD, PARAMETER, VARIABLE
    }

    /** A single reference: its kind, the referenced name, and the expression text it occurs in. */
    public record Reference(Kind kind, String name, String expressionText) {
    }

    private JrExpressions() {
    }

    public static List<Reference> references(JasperDesign design) {
        List<JRExpression> expressions = JRExpressionCollector.collectExpressions(
                DefaultJasperReportsContext.getInstance(), design);
        List<Reference> references = new ArrayList<>();
        for (JRExpression expression : expressions) {
            String text = JRExpressionUtil.getExpressionText(expression);
            JRExpressionChunk[] chunks = expression.getChunks();
            if (chunks == null) {
                // An empty <expression/> has no chunks; the engine returns null.
                continue;
            }
            for (JRExpressionChunk chunk : chunks) {
                Kind kind = kindOf(chunk.getType());
                if (kind != null && chunk.getText() != null) {
                    references.add(new Reference(kind, chunk.getText(), text));
                }
            }
        }
        return references;
    }

    private static Kind kindOf(byte type) {
        if (type == JRExpressionChunk.TYPE_FIELD) {
            return Kind.FIELD;
        }
        if (type == JRExpressionChunk.TYPE_PARAMETER) {
            return Kind.PARAMETER;
        }
        if (type == JRExpressionChunk.TYPE_VARIABLE) {
            return Kind.VARIABLE;
        }
        return null;
    }
}
