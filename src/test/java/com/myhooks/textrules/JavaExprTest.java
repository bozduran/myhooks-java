package com.myhooks.textrules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JavaExprTest {

    @Test
    void spacesBinaryOperators() {
        assertEquals("a == b", JavaExpr.format("a==b"));
        assertEquals("a != b", JavaExpr.format("a!=b"));
        assertEquals("a >= b", JavaExpr.format("a>=b"));
        assertEquals("a <= b", JavaExpr.format("a<=b"));
        assertEquals("a && b", JavaExpr.format("a&&b"));
        assertEquals("a || b", JavaExpr.format("a||b"));
    }

    @Test
    void normalizesExistingOperatorWhitespaceToSingleSpace() {
        assertEquals("a == b", JavaExpr.format("a  ==  b"));
    }

    @Test
    void spacesLessThanAndGreaterThan() {
        assertEquals("a < b", JavaExpr.format("a<b"));
        assertEquals("a > b", JavaExpr.format("a>b"));
    }

    @Test
    void spacesTernary() {
        assertEquals("a ? b : c", JavaExpr.format("a?b:c"));
    }

    @Test
    void spacesCommasAndCasts() {
        assertEquals("fun(a, b)", JavaExpr.format("fun(a,b)"));
        assertEquals("(String) IF(a == 1 ? b != 2 : c)", JavaExpr.format("(String)IF(a==1?b!=2:c)"));
    }

    @Test
    void preservesJasperReportsReferences() {
        assertEquals("(String) $F{x}.toString()", JavaExpr.format("(String)$F{x}.toString()"));
        assertEquals("IF($F{a} == 1, foo(1, 2), bar($F{b}, 3))",
                JavaExpr.format("IF($F{a}==1,foo(1,2),bar($F{b},3))"));
    }

    @Test
    void spacesMixedComparisonOperators() {
        assertEquals("$F{a} >= 1 && $F{b} < 5 || $F{c} <= 3",
                JavaExpr.format("$F{a}>=1&&$F{b}<5||$F{c}<=3"));
    }

    @Test
    void leavesStringLiteralContentUntouched() {
        assertEquals("IF(a == 1 ? \"x\" : \"y\")", JavaExpr.format("IF(a==1?\"x\":\"y\")"));
        assertEquals("\"a==b\"", JavaExpr.format("\"a==b\""));
    }
}
