package com.myhooks.xmlspan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;

class XmlScannerTest {

    private static Node scan(String xml) throws XMLStreamException {
        return XmlScanner.scan(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void scanBuildsTreeWithTagKindDepthAndOffsets() throws Exception {
        String xml = "<jasperReport name=\"r\">\n"
                + "  <element kind=\"textField\" x=\"0\">\n"
                + "    <text><![CDATA[hi]]></text>\n"
                + "  </element>\n"
                + "</jasperReport>";

        Node root = scan(xml);
        assertEquals("jasperReport", root.tag());
        assertEquals("jasperReport", root.kind());
        assertEquals(0, root.depth());
        assertEquals(0, root.startTag());
        assertEquals(xml.indexOf('>') + 1, root.startTagEnd());
        assertEquals(xml.indexOf("</jasperReport>"), root.endTag());
        assertEquals(xml.indexOf("</jasperReport>") + "</jasperReport>".length(), root.end());

        assertEquals(1, root.children().size());
        Node element = root.children().get(0);
        assertEquals("element", element.tag());
        assertEquals("textField", element.kind());
        assertEquals(1, element.depth());
        assertEquals(root, element.parent());

        String open = "<element kind=\"textField\" x=\"0\">";
        assertEquals(xml.indexOf(open), element.startTag());
        assertEquals(xml.indexOf(open) + open.length(), element.startTagEnd());
        assertEquals(xml.indexOf("</element>"), element.endTag());
        assertEquals(xml.indexOf("</element>") + "</element>".length(), element.end());

        Node text = element.children().get(0);
        assertEquals("text", text.tag());
        assertEquals("text", text.kind());
        assertEquals(2, text.depth());
        assertEquals(xml.indexOf("<text>"), text.startTag());
        assertEquals(xml.indexOf("<text>") + "<text>".length(), text.startTagEnd());
        assertEquals(xml.indexOf("</text>"), text.endTag());
        assertEquals(xml.indexOf("</text>") + "</text>".length(), text.end());
    }

    @Test
    void kindResolvesFromKindAttributeOrTagName() throws Exception {
        String xml = "<root>"
                + "<element kind=\"textField\"/>"
                + "<element kind=\"staticText\"/>"
                + "<element kind=\"frame\"/>"
                + "<band/>"
                + "</root>";
        Node root = scan(xml);
        List<Node> children = root.children();
        assertEquals("textField", children.get(0).kind());
        assertEquals("staticText", children.get(1).kind());
        assertEquals("frame", children.get(2).kind());
        assertEquals("band", children.get(3).kind());
    }

    @Test
    void selfClosingElementUsesStartTagSpanForEnd() throws Exception {
        String xml = "<root><empty/></root>";
        Node root = scan(xml);
        Node empty = root.children().get(0);
        int start = xml.indexOf("<empty/>");
        assertEquals(start, empty.startTag());
        assertEquals(start + "<empty/>".length(), empty.startTagEnd());
        assertEquals(start, empty.endTag());
        assertEquals(start + "<empty/>".length(), empty.end());
    }

    @Test
    void findAttrIsQuoteAgnosticAndReportsValueSpans() throws Exception {
        String xml = "<element kind='staticText' x=\"5\" y='7'/>";
        Node n = scan(xml);

        Attr kind = Query.findAttr(n, "kind").orElseThrow();
        assertEquals("kind", kind.name());
        assertEquals('\'', kind.quote());
        assertEquals(xml.indexOf("staticText"), kind.valueStart());
        assertEquals(xml.indexOf("staticText") + "staticText".length(), kind.valueEnd());

        Attr x = Query.findAttr(n, "x").orElseThrow();
        assertEquals('"', x.quote());
        assertEquals(xml.indexOf("5"), x.valueStart());
        assertEquals(xml.indexOf("5") + 1, x.valueEnd());

        Attr y = Query.findAttr(n, "y").orElseThrow();
        assertEquals('\'', y.quote());
        assertEquals(xml.indexOf("7"), y.valueStart());
        assertEquals(xml.indexOf("7") + 1, y.valueEnd());

        assertTrue(Query.findAttr(n, "missing").isEmpty());
    }

    @Test
    void directChildrenReturnsImmediateElementChildrenOnly() throws Exception {
        String xml = "<band>"
                + "<element kind=\"textField\" x=\"0\"/>"
                + "<property name=\"p\"/>"
                + "<component><element kind=\"textField\" x=\"1\"/></component>"
                + "<element kind=\"frame\" x=\"2\"/>"
                + "</band>";
        Node band = scan(xml);

        List<Node> all = Query.directChildren(band, null);
        assertEquals(2, all.size());
        assertEquals("textField", all.get(0).kind());
        assertEquals("frame", all.get(1).kind());

        List<Node> frames = Query.directChildren(band, "frame");
        assertEquals(1, frames.size());
        assertEquals("frame", frames.get(0).kind());

        List<Node> textFields = Query.directChildren(band, "textField");
        assertEquals(1, textFields.size());
        assertEquals("textField", textFields.get(0).kind());
    }

    @Test
    void isRenderedTextContextWalksUpToParentKind() throws Exception {
        String xml = "<jasperReport>"
                + "<element kind=\"textField\"><text>a</text></element>"
                + "<element kind=\"staticText\"><text>b</text></element>"
                + "<element kind=\"subreport\"><expression>c</expression></element>"
                + "<parameter><defaultValueExpression>d</defaultValueExpression></parameter>"
                + "</jasperReport>";
        Node root = scan(xml);

        Node textField = root.children().get(0);
        Node staticText = root.children().get(1);
        Node subreport = root.children().get(2);
        Node parameter = root.children().get(3);

        assertTrue(Query.isRenderedTextContext(textField.children().get(0)));
        assertTrue(Query.isRenderedTextContext(staticText.children().get(0)));
        assertFalse(Query.isRenderedTextContext(subreport.children().get(0)));
        assertFalse(Query.isRenderedTextContext(parameter.children().get(0)));
    }
}
