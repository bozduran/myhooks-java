package com.myhooks.jrutil;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import net.sf.jasperreports.engine.JRException;
import org.junit.jupiter.api.Test;

class JrCompilerTest {

    private static final String VALID = """
            <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
            </jasperReport>
            """;

    private static final String UNCOMPILABLE = """
            <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
              <variable name="v" class="java.lang.Integer">
                <expression><![CDATA[$F{noSuchField}]]></expression>
              </variable>
            </jasperReport>
            """;

    @Test
    void compilesAValidReport() throws Exception {
        assertNotNull(JrCompiler.compile(VALID.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsAnUncompilableReport() {
        assertThrows(JRException.class,
                () -> JrCompiler.compile(UNCOMPILABLE.getBytes(StandardCharsets.UTF_8)));
    }
}
