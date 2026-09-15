package com.myhooks.xmlspan;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class QueryTest {

    private static final String REPORT = """
            <jasperReport name="r">
              <field name="f" class="java.lang.String">
                <description><![CDATA[path.to.field]]></description>
              </field>
              <detail>
                <band height="20">
                  <element kind="textField" uuid="u1">
                    <expression><![CDATA[$F{f}]]></expression>
                  </element>
                </band>
              </detail>
            </jasperReport>
            """;

    @Test
    void enclosingUnitPrefersTheNearestElementOrRootChild() throws Exception {
        Node root = XmlScanner.scan(REPORT);
        Node field = root.children().get(0);
        Node description = field.children().get(0);
        Node band = root.children().get(1).children().get(0);
        Node element = band.children().get(0);
        Node expression = element.children().get(0);

        assertSame(root, Query.enclosingUnit(root), "the root is its own unit");
        assertSame(field, Query.enclosingUnit(field));
        assertSame(field, Query.enclosingUnit(description), "a declaration wins over the root");
        assertSame(element, Query.enclosingUnit(element));
        assertSame(element, Query.enclosingUnit(expression), "a band element wins over the root");
    }
}
