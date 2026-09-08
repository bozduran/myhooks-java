package com.myhooks.jrutil;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import net.sf.jasperreports.engine.JRException;
import org.junit.jupiter.api.Test;

class JrSchemaTest {

    private static final String VALID = """
            <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
            </jasperReport>
            """;

    @Test
    void validReportLoads() {
        assertDoesNotThrow(() -> JrSchema.validate(VALID.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void jsonqlQueryLoads() {
        String report = """
                <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                    leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                    <query language="jsonql"><![CDATA[document.iddata]]></query>
                    <field name="f" class="java.lang.String"/>
                </jasperReport>
                """;
        assertDoesNotThrow(() -> JrSchema.validate(report.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void structurallyInvalidReportIsRejected() {
        String invalid = "<jasperReport><unclosed></jasperReport>";
        assertThrows(JRException.class,
                () -> JrSchema.validate(invalid.getBytes(StandardCharsets.UTF_8)));
    }
}
