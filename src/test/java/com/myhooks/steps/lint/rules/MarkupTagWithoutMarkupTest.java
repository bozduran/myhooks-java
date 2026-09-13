package com.myhooks.steps.lint.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.steps.lint.Warning;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.XmlScanner;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkupTagWithoutMarkupTest {

    @Test
    void flagsTagsWhenMarkupIsMissing() throws Exception {
        String raw = report(textField("",
                "\"Le <b>boulanger</b> est sympa. <br/>Apres le film.\""));
        Node root = XmlScanner.scan(raw);

        List<Warning> warnings = new MarkupTagWithoutMarkup().check(root, raw);

        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals("text contains markup tag <b> but markup is not \"styled\", \"html\" or \"rtf\"",
                warnings.get(0).message());
        assertEquals(raw.indexOf("<element"), warnings.get(0).offset());
    }

    @Test
    void flagsNoneAndUnknownMarkupValues() throws Exception {
        List<Warning> warnings = check(
                textField(" markup=\"none\"", "\"<b>x</b>\"")
                        + textField(" markup=\"foo\"", "\"<i>y</i>\""));

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).message().contains("markup tag <b>"), warnings.toString());
        assertTrue(warnings.get(1).message().contains("markup tag <i>"), warnings.toString());
    }

    @Test
    void acceptsStyledHtmlAndRtf() throws Exception {
        assertEquals(List.of(), check(
                textField(" markup=\"styled\"", "\"<b>x</b>")
                        + textField(" markup=\"html\"", "\"<b>x</b>")
                        + textField(" markup=\"rtf\"", "\"<b>x</b>")));
    }

    @Test
    void markupValueAndAttributeQuoteAreFlexible() throws Exception {
        assertEquals(List.of(), check(
                textField(" markup='styled'", "\"<b>x</b>")
                        + textField(" markup=\"Styled\"", "\"<b>x</b>\"")));
    }

    @Test
    void ignoresExpressionsWithoutTags() throws Exception {
        assertEquals(List.of(), check(textField("", "\"plain text\"")));
    }

    @Test
    void ignoresElementsOtherThanTextField() throws Exception {
        String staticText = """
                <element kind="staticText" uuid="t1" x="0" y="0" width="10" height="10">
                    <text><![CDATA[<b>x</b>]]></text>
                </element>
                """;
        assertEquals(List.of(), check(staticText));
    }

    @Test
    void detectsClosingSelfClosingAttributeAndUppercaseTags() throws Exception {
        List<Warning> closing = check(textField("", "\"</u>\""));
        List<Warning> selfClosing = check(textField("", "\"<br/>\""));
        List<Warning> attributed = check(textField("", "\"<font size='12'>x</font>\""));
        List<Warning> uppercase = check(textField("", "\"<B>x</B>\""));

        assertEquals(1, closing.size(), closing.toString());
        assertTrue(closing.get(0).message().contains("markup tag </u>"), closing.toString());
        assertEquals(1, selfClosing.size(), selfClosing.toString());
        assertTrue(selfClosing.get(0).message().contains("markup tag <br/>"), selfClosing.toString());
        assertEquals(1, attributed.size(), attributed.toString());
        assertTrue(attributed.get(0).message().contains("<font size='12'>"), attributed.toString());
        assertEquals(1, uppercase.size(), uppercase.toString());
        assertTrue(uppercase.get(0).message().contains("<B>"), uppercase.toString());
    }

    @Test
    void ignoresComparisonsGenericsAndMalformedTags() throws Exception {
        assertEquals(List.of(), check(textField("", "\"a < b > c\"")));
        assertEquals(List.of(), check(textField("", "\"x<b and c>y\"")));
        assertEquals(List.of(), check(textField("", "new java.util.ArrayList<b>()")));
    }

    @Test
    void detectsOldStyleTextFieldExpression() throws Exception {
        String body = """
                <element kind="textField" uuid="u1" x="0" y="0" width="10" height="10">
                    <textFieldExpression><![CDATA["<b>x</b>"]]></textFieldExpression>
                </element>
                """;

        assertEquals(1, check(body).size());
    }

    @Test
    void detectsTextBlockLiteralsAndNestedElements() throws Exception {
        List<Warning> textBlock = check(textField("", "\"\"\"\n<b>x</b>\n\"\"\""));
        assertEquals(1, textBlock.size(), textBlock.toString());

        String nested = """
                <element kind="frame" uuid="f1" x="0" y="0" width="10" height="10">
                    <element kind="textField" uuid="u1" x="0" y="0" width="10" height="10">
                        <expression><![CDATA["<b>x</b>"]]></expression>
                    </element>
                </element>
                """;
        assertEquals(1, check(nested).size());
    }

    private static List<Warning> check(String body) throws Exception {
        String raw = report(body);
        Node root = XmlScanner.scan(raw);
        return new MarkupTagWithoutMarkup().check(root, raw);
    }

    private static String report(String body) {
        return "<jasperReport name=\"t\">" + body + "</jasperReport>";
    }

    private static String textField(String attributes, String expression) {
        return "<element kind=\"textField\" uuid=\"u1\" x=\"0\" y=\"0\" width=\"10\" height=\"10\""
                + attributes + "><expression><![CDATA[" + expression + "]]></expression></element>";
    }
}
