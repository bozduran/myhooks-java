package com.myhooks.steps.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.step.Context;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidateStepTest {

    @TempDir
    Path dir;

    private static final String VALID = """
            <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
            </jasperReport>
            """;

    private static final String STRUCTURALLY_INVALID = "<jasperReport><unclosed></jasperReport>";

    private static final String UNCOMPILABLE = """
            <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555"
                leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
              <variable name="v" class="java.lang.Integer">
                <expression><![CDATA[$F{noSuchField}]]></expression>
              </variable>
            </jasperReport>
            """;

    @Test
    void validReportPasses() throws Exception {
        assertEquals(0, new ValidateStep().run(context(), List.of(write("t.jrxml", VALID))));
    }

    @Test
    void structurallyInvalidReportFails() throws Exception {
        assertEquals(1, new ValidateStep().run(context(), List.of(write("bad.jrxml", STRUCTURALLY_INVALID))));
    }

    @Test
    void uncompilableReportFails() throws Exception {
        assertEquals(1, new ValidateStep().run(context(), List.of(write("bad.jrxml", UNCOMPILABLE))));
    }

    @Test
    void checkIsReportOnlyAndDoesNotStop() throws Exception {
        assertEquals(0, new ValidateStep().check(context(), List.of(write("bad.jrxml", UNCOMPILABLE))));
    }

    private String write(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file.toString();
    }

    private static Context context() {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        return new Context(new FileDiscovery(), out, out, false, q -> Choice.NO);
    }
}
