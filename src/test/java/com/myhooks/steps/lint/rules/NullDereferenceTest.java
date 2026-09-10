package com.myhooks.steps.lint.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.steps.lint.Warning;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.XmlScanner;
import java.util.List;
import org.junit.jupiter.api.Test;

class NullDereferenceTest {

    @Test
    void flagsUncheckedMethodCallReceivers() throws Exception {
        assertFlagged("$V{v}.substring(0)", "$V{v}");
        assertFlagged("$F{f}.length() + $P{p}.trim()", "$F{f}", "$P{p}");
        assertFlagged("$V{v}.equals($F{g})", "$V{v}");
    }

    @Test
    void flagsOnlyTheReferenceThatIsNotChecked() throws Exception {
        assertFlagged("$V{a} != null && $V{b}.length() > 0", "$V{b}");
        // The kind matters: checking $F{f} does not check $V{f}.
        assertFlagged("$F{f} != null && $V{f}.length() > 0", "$V{f}");
    }

    @Test
    void flagsTheNullBranchOfATernary() throws Exception {
        assertFlagged("$V{v} == null ? $V{v}.trim() : \"\"", "$V{v}");
        assertFlagged("$V{v} != null ? $V{v}.trim() : $V{v}.substring(0)", "$V{v}");
    }

    @Test
    void flagsWhenTheCheckIsForTheWrongOutcome() throws Exception {
        // EQUALS(v, null) true means v *is* null, so the right side is unsafe.
        assertFlagged("EQUALS($V{v}, null) && $V{v}.length() > 0", "$V{v}");
    }

    @Test
    void acceptsShortCircuitAndTernaryGuards() throws Exception {
        assertClean("$V{v} != null && $V{v}.substring(0).length() > 0");
        assertClean("null != $V{v} && $V{v}.length() > 0");
        assertClean("$V{v} == null || $V{v}.trim().isEmpty()");
        assertClean("$V{v} != null ? $V{v}.substring(0) : \"\"");
        assertClean("($V{v} != null) && ($V{v}.trim().length() > 0)");
    }

    @Test
    void acceptsNegatedComparisons() throws Exception {
        assertClean("!($V{v} == null) && $V{v}.length() > 0");
        assertClean("!($V{v} != null) ? \"\" : $V{v}.length() > 0");
    }

    @Test
    void acceptsEqualsAndObjectsHelpers() throws Exception {
        assertClean("EQUALS($V{v}, null) ? \"\" : $V{v}.substring(0)");
        assertClean("Equals($V{v}, null) ? \"\" : $V{v}.substring(0)");
        assertClean("Objects.equals($V{v}, null) ? \"\" : $V{v}.substring(0)");
        assertClean("Objects.nonNull($V{v}) && $V{v}.length() > 0");
        assertClean("Objects.isNull($V{v}) ? \"\" : $V{v}.substring(0)");
        assertClean("!Objects.nonNull($V{v}) || $V{v}.length() > 0");
    }

    @Test
    void ignoresPlainReferencesArgumentsAndLiterals() throws Exception {
        assertClean("$V{v}");
        assertClean("String.valueOf($V{v})");
        assertClean("\"$V{v}.substring(0)\"");
        assertClean("$F{f} == null ? \"none\" : $F{f}");
    }

    @Test
    void stillFlagsAReceiverAfterACharLiteral() throws Exception {
        assertFlagged("'\"' + $V{v}.trim()", "$V{v}");
    }

    @Test
    void skipsExpressionsThatAreNotValidJava() throws Exception {
        assertClean("$V{v}.");
        assertClean("foo(");
        assertClean("$V{a-b}.trim()");
        assertClean("");
    }

    private static void assertFlagged(String expression, String... expectedRefs) throws Exception {
        List<Warning> warnings = check(expression);
        assertEquals(expectedRefs.length, warnings.size(), () -> expression + " -> " + warnings);
        for (String ref : expectedRefs) {
            assertTrue(warnings.stream().anyMatch(w -> w.message().contains(ref)),
                    () -> expression + " -> " + warnings);
        }
    }

    private static void assertClean(String expression) throws Exception {
        List<Warning> warnings = check(expression);
        assertTrue(warnings.isEmpty(), () -> expression + " -> " + warnings);
    }

    private static List<Warning> check(String expression) throws Exception {
        String raw = "<r><expression><![CDATA[" + expression + "]]></expression></r>";
        Node root = XmlScanner.scan(raw);
        return new NullDereference().check(root, raw);
    }
}
