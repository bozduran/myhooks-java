package com.myhooks.steps.sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myhooks.diffui.Choice;
import com.myhooks.discover.FileDiscovery;
import com.myhooks.edit.EditSet;
import com.myhooks.step.Context;
import com.myhooks.step.Fix;
import com.myhooks.step.Group;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.Query;
import com.myhooks.xmlspan.XmlScanner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SortDiscovererTest {

    @TempDir
    Path dir;

    private static final String SAMPLE = """
            <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
              <detail>
                <band height="245">
                  <element kind="frame" uuid="F" x="133" y="45" width="200" height="200">
                    <property name="p" value="v"/>
                    <element kind="textField" uuid="F1" x="20" y="40" width="100" height="30"/>
                    <element kind="subreport" uuid="F0" x="0" y="0" width="200" height="90"/>
                  </element>
                  <element kind="textField" uuid="A" x="399" y="26" width="100" height="30"/>
                  <element kind="subreport" uuid="B" x="45" y="38" width="200" height="200"/>
                </band>
              </detail>
            </jasperReport>
            """;

    private static final String CLEAN = """
            <jasperReport name="t">
              <detail>
                <band height="20">
                  <element kind="textField" uuid="x" x="0" y="0" width="10" height="10"/>
                  <element kind="subreport" uuid="y" x="0" y="20" width="10" height="10"/>
                </band>
              </detail>
            </jasperReport>
            """;

    @Test
    void parseCollectsContainersAndChildren() throws Exception {
        SortDiscoverer.ParsedReport report = SortDiscoverer.parse(SAMPLE);
        assertEquals(2, report.containers().size());

        SortDiscoverer.Container band = report.containers().stream()
                .filter(c -> c.kind().equals("band")).findFirst().orElseThrow();
        SortDiscoverer.Container frame = report.containers().stream()
                .filter(c -> c.kind().equals("frame")).findFirst().orElseThrow();

        assertEquals(List.of("frame", "textField", "subreport"), kinds(band.children()));
        assertEquals(List.of("textField", "subreport"), kinds(frame.children()));

        SortDiscoverer.Child sub = frame.children().get(1);
        assertEquals(0, sub.x());
        assertEquals(0, sub.y());
        assertEquals(200, sub.w());
        assertEquals(90, sub.h());
    }

    @Test
    void applyReorders() throws Exception {
        SortDiscoverer.ParsedReport report = SortDiscoverer.parse(SAMPLE);
        String out = SortDiscoverer.applyReorders(SAMPLE, report.containers());

        assertTrue(idx(out, "uuid=\"A\"") < idx(out, "uuid=\"B\"")
                && idx(out, "uuid=\"B\"") < idx(out, "uuid=\"F\""), out);
        assertTrue(idx(out, "uuid=\"F0\"") < idx(out, "uuid=\"F1\""), out);
        assertTrue(idx(out, "name=\"p\"") >= 0 && idx(out, "name=\"p\"") < idx(out, "uuid=\"F0\""), out);
    }

    @Test
    void applyReordersNoChange() throws Exception {
        SortDiscoverer.ParsedReport report = SortDiscoverer.parse(CLEAN);
        assertEquals(CLEAN, SortDiscoverer.applyReorders(CLEAN, report.containers()));
    }

    @Test
    void applyReordersNestedFrames() throws Exception {
        String raw = """
                <jasperReport name="t">
                  <detail><band height="200">
                    <element kind="frame" uuid="outer" x="0" y="0" width="100" height="100">
                      <element kind="frame" uuid="inner" x="0" y="0" width="50" height="50">
                        <element kind="textField" uuid="i1" x="0" y="30" width="10" height="10"/>
                        <element kind="image" uuid="i0" x="0" y="0" width="10" height="10"/>
                      </element>
                      <element kind="textField" uuid="o1" x="0" y="40" width="10" height="10"/>
                      <element kind="subreport" uuid="o0" x="0" y="0" width="10" height="10"/>
                    </element>
                  </band></detail>
                </jasperReport>
                """;
        SortDiscoverer.ParsedReport report = SortDiscoverer.parse(raw);
        String out = SortDiscoverer.applyReorders(raw, report.containers());
        assertTrue(idx(out, "uuid=\"i0\"") < idx(out, "uuid=\"i1\""), out);
        assertTrue(idx(out, "uuid=\"inner\"") < idx(out, "uuid=\"o0\"")
                && idx(out, "uuid=\"o0\"") < idx(out, "uuid=\"o1\""), out);
    }

    @Test
    void parseSkipsNestedComponentElements() throws Exception {
        String raw = """
                <jasperReport name="t">
                  <detail>
                    <band height="270">
                      <element kind="component" uuid="c1" x="120" y="120" width="200" height="110">
                        <component kind="table">
                          <column kind="single" width="40">
                            <tableHeader height="30">
                              <element kind="textField" uuid="nested" x="0" y="0" width="40" height="30"/>
                            </tableHeader>
                          </column>
                        </component>
                      </element>
                      <element kind="subreport" uuid="s1" x="68" y="41" width="200" height="200"/>
                    </band>
                  </detail>
                </jasperReport>
                """;
        SortDiscoverer.ParsedReport report = SortDiscoverer.parse(raw);
        assertEquals(1, report.containers().size());
        assertEquals(List.of("component", "subreport"), kinds(report.containers().get(0).children()));
    }

    @Test
    void sortedChildrenIsStable() {
        List<SortDiscoverer.Child> children = List.of(
                new SortDiscoverer.Child("a", 0, 0, 0, 0, 0, 0),
                new SortDiscoverer.Child("b", 0, 0, 0, 0, 0, 0),
                new SortDiscoverer.Child("c", 0, 5, 0, 0, 0, 0),
                new SortDiscoverer.Child("d", 10, 0, 0, 0, 0, 0));
        assertEquals(List.of("a", "b", "d", "c"), kinds(SortDiscoverer.sortedChildren(children)));
    }

    @Test
    void overlapsDetectsIntersectingBoxes() {
        List<SortDiscoverer.Child> children = List.of(
                new SortDiscoverer.Child("a", 0, 0, 10, 10, 0, 0),
                new SortDiscoverer.Child("b", 5, 5, 4, 4, 0, 0),
                new SortDiscoverer.Child("c", 100, 100, 10, 10, 0, 0),
                new SortDiscoverer.Child("d", 0, 10, 10, 10, 0, 0));
        List<SortDiscoverer.Overlap> overlaps = SortDiscoverer.overlaps(children);
        assertEquals(1, overlaps.size());
        assertEquals("a", overlaps.get(0).a().kind());
        assertEquals("b", overlaps.get(0).b().kind());
    }

    @Test
    void reorderChangedCountsChangedContainers() throws Exception {
        SortDiscoverer.ParsedReport report = SortDiscoverer.parse(SAMPLE);
        long changed = report.containers().stream().filter(SortDiscoverer::reorderChanged).count();
        assertEquals(2, changed);
    }

    @Test
    void parseIntReturnsEmptyForMissingOrNonNumeric() throws Exception {
        String raw = "<band height=\"20\"><element kind=\"textField\" x=\"abc\" y=\"10\" width=\"10\"/></band>";
        Node band = XmlScanner.scan(raw.getBytes(StandardCharsets.UTF_8));
        Node el = Query.directChildren(band, null).get(0);
        assertTrue(SortDiscoverer.parseInt(el, "x", raw).isEmpty());
        assertTrue(SortDiscoverer.parseInt(el, "y", raw).isPresent());
        assertTrue(SortDiscoverer.parseInt(el, "height", raw).isEmpty());
    }

    @Test
    void missingOrNonNumericCoordinatesWarn() throws Exception {
        String raw = "<jasperReport name=\"t\"><detail><band height=\"20\">" +
                "<element kind=\"textField\" uuid=\"a\" x=\"abc\" width=\"10\" height=\"10\"/>" +
                "</band></detail></jasperReport>";
        SortDiscoverer.ParsedReport report = SortDiscoverer.parse(raw);
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("non-numeric x") || w.contains("missing or non-numeric x")), report.warnings().toString());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("y")), report.warnings().toString());
    }

    @Test
    void discoverReturnsFixThatReorders() throws Exception {
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, SAMPLE);
        List<Group> groups = new SortDiscoverer().discover(context(), file);
        assertEquals(1, groups.size());
        EditSet edits = new EditSet();
        for (Group group : groups) {
            for (Fix fix : group.fixes()) {
                fix.apply(edits);
            }
        }
        String out = edits.apply(SAMPLE);
        assertTrue(idx(out, "uuid=\"A\"") < idx(out, "uuid=\"B\"")
                && idx(out, "uuid=\"B\"") < idx(out, "uuid=\"F\""), out);
        assertTrue(idx(out, "uuid=\"F0\"") < idx(out, "uuid=\"F1\""), out);
    }

    @Test
    void discoverCleanReturnsNoFixes() throws Exception {
        Path file = dir.resolve("t.jrxml");
        Files.writeString(file, CLEAN);
        assertTrue(new SortDiscoverer().discover(context(), file).isEmpty());
    }

    private static int idx(String text, String needle) {
        return text.indexOf(needle);
    }

    private static List<String> kinds(List<SortDiscoverer.Child> children) {
        return children.stream().map(SortDiscoverer.Child::kind).toList();
    }

    private static Context context() {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        return new Context(new FileDiscovery(), out, out, false, q -> Choice.NO);
    }
}
