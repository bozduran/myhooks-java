package com.myhooks.steps.lint.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.steps.lint.Warning;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.XmlScanner;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoveLineWhenBlankTest {

    @Test
    void flagsTextFieldAndSubreportWithoutTheAttribute() throws Exception {
        List<Warning> warnings = check("""
                <element kind="textField" uuid="u1" x="0" y="0" width="10" height="10"/>
                <element kind="subreport" uuid="s1" x="0" y="0" width="10" height="10"/>
                """);

        assertEquals(2, warnings.size(), warnings.toString());
        assertEquals("textField is missing removeLineWhenBlank=\"true\"", warnings.get(0).message());
        assertEquals("subreport is missing removeLineWhenBlank=\"true\"", warnings.get(1).message());
    }

    @Test
    void flagsAnExplicitNonTrueValue() throws Exception {
        List<Warning> warnings = check("""
                <element kind="textField" uuid="u1" removeLineWhenBlank="false"
                        x="0" y="0" width="10" height="10"/>
                """);

        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals("removeLineWhenBlank is \"false\" on textField; expected \"true\"",
                warnings.get(0).message());
    }

    @Test
    void acceptsTrueAndIgnoresOtherKinds() throws Exception {
        assertEquals(List.of(), check("""
                <element kind="textField" uuid="u1" removeLineWhenBlank="true"
                        x="0" y="0" width="10" height="10"/>
                <element kind="subreport" uuid="s1" removeLineWhenBlank="true"
                        x="0" y="0" width="10" height="10"/>
                <element kind="staticText" uuid="t1" x="0" y="0" width="10" height="10"/>
                <element kind="image" uuid="i1" x="0" y="0" width="10" height="10"/>
                """));
    }

    @Test
    void flagsNestedElementsToo() throws Exception {
        List<Warning> warnings = check("""
                <element kind="frame" uuid="f1" x="0" y="0" width="10" height="10">
                    <element kind="textField" uuid="u1" x="0" y="0" width="10" height="10"/>
                </element>
                """);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).message().startsWith("textField is missing"), warnings.toString());
    }

    private static List<Warning> check(String body) throws Exception {
        String raw = "<jasperReport name=\"t\">" + body + "</jasperReport>";
        Node root = XmlScanner.scan(raw);
        return new RemoveLineWhenBlank().check(root, raw);
    }
}
