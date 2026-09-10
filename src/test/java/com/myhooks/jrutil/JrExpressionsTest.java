package com.myhooks.jrutil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.sf.jasperreports.engine.design.JasperDesign;
import org.junit.jupiter.api.Test;

class JrExpressionsTest {

    private static final String REPORT = """
            <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
              <field name="myField" class="java.lang.String"/>
              <parameter name="myParam" class="java.lang.String"/>
              <variable name="otherVar" class="java.lang.Integer"/>
              <variable name="myVar" class="java.lang.Integer">
                <expression><![CDATA[$F{myField}.length() + $P{myParam}.length() + $V{otherVar}.intValue()]]></expression>
              </variable>
            </jasperReport>
            """;

    @Test
    void listsFieldParameterAndVariableReferences() throws Exception {
        JasperDesign design = JrSchema.validate(REPORT.getBytes(StandardCharsets.UTF_8));
        List<JrExpressions.Reference> refs = JrExpressions.references(design);

        Set<String> kindAndName = refs.stream()
                .map(r -> r.kind() + ":" + r.name())
                .collect(Collectors.toSet());

        assertTrue(kindAndName.contains("FIELD:myField"));
        assertTrue(kindAndName.contains("PARAMETER:myParam"));
        assertTrue(kindAndName.contains("VARIABLE:otherVar"));
        assertFalse(kindAndName.contains("VARIABLE:myVar"), "the variable itself must not be reported as referenced");
    }

    @Test
    void emptyExpressionIsIgnoredInsteadOfFailing() throws Exception {
        String report = """
                <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                    leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                  <variable name="v" class="java.lang.Integer">
                    <expression/>
                  </variable>
                </jasperReport>
                """;
        JasperDesign design = JrSchema.validate(report.getBytes(StandardCharsets.UTF_8));

        assertTrue(JrExpressions.references(design).isEmpty());
    }
}
