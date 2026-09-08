package sort

import (
	"encoding/xml"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"myhooks/internal/jrxmlutil"
)

// sampleSort has a band whose children are out of y-order (frame y=45,
// textField y=26, subreport y=38) and a frame whose children are also out of
// order (textField y=40, subreport y=0). After sorting:
//
//	band  -> A(textField 26), B(subreport 38), F(frame 45)
//	frame -> F0(subreport 0), F1(textField 40), with the <property> kept first.
const sampleSort = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
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
</jasperReport>`

// cleanSortReport is already in reading order (y asc), so it must not change.
const cleanSortReport = `<jasperReport name="t">
	<detail>
		<band height="20">
			<element kind="textField" uuid="x" x="0" y="0" width="10" height="10"/>
			<element kind="subreport" uuid="y" x="0" y="20" width="10" height="10"/>
		</band>
	</detail>
</jasperReport>`

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func validXML(t *testing.T, s string) {
	t.Helper()
	dec := xml.NewDecoder(strings.NewReader(s))
	for {
		_, err := dec.Token()
		if err == io.EOF {
			return
		}
		if err != nil {
			t.Fatalf("invalid XML: %v", err)
		}
	}
}

func TestParseCollectsContainersAndChildren(t *testing.T) {
	containers, err := parse([]byte(sampleSort))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(containers) != 2 {
		t.Fatalf("got %d containers, want 2 (band + frame): %+v", len(containers), containers)
	}

	var band, frame *container
	for _, c := range containers {
		switch c.kind {
		case "band":
			band = c
		case "frame":
			frame = c
		}
	}
	if band == nil || frame == nil {
		t.Fatal("expected one band and one frame container")
	}

	// band's direct children (document order), with their geometry parsed
	bandKinds := childKinds(band.children)
	if strings.Join(bandKinds, ",") != "frame,textField,subreport" {
		t.Fatalf("band children = %v, want frame,textField,subreport", bandKinds)
	}
	// frame's children are attributed to the frame, not the band
	frameKinds := childKinds(frame.children)
	if strings.Join(frameKinds, ",") != "textField,subreport" {
		t.Fatalf("frame children = %v, want textField,subreport", frameKinds)
	}
	sub := frame.children[1]
	if sub.x != 0 || sub.y != 0 || sub.w != 200 || sub.h != 90 {
		t.Fatalf("frame subreport geometry = (%d,%d,%d,%d), want (0,0,200,90)", sub.x, sub.y, sub.w, sub.h)
	}
}

func TestApplyReorders(t *testing.T) {
	containers, err := parse([]byte(sampleSort))
	if err != nil {
		t.Fatal(err)
	}
	out := applyReorders(sampleSort, containers)
	validXML(t, out)

	// band children reordered to A(26), B(38), F(45)
	iA, iB, iF := strings.Index(out, `uuid="A"`), strings.Index(out, `uuid="B"`), strings.Index(out, `uuid="F"`)
	if !(iA < iB && iB < iF) {
		t.Fatalf("band order wrong (want A,B,F): A=%d B=%d F=%d\n%s", iA, iB, iF, out)
	}
	// frame children reordered to F0(0), F1(40)
	iF0, iF1 := strings.Index(out, `uuid="F0"`), strings.Index(out, `uuid="F1"`)
	if !(iF0 < iF1) {
		t.Fatalf("frame order wrong (want F0,F1): F0=%d F1=%d\n%s", iF0, iF1, out)
	}
	// the <property> child is not an element: it stays inside the frame, first
	ip := strings.Index(out, `name="p"`)
	if ip < 0 || ip > iF0 {
		t.Fatalf("property should stay before frame elements: p=%d F0=%d\n%s", ip, iF0, out)
	}
}

func TestApplyReordersNoChange(t *testing.T) {
	containers, err := parse([]byte(cleanSortReport))
	if err != nil {
		t.Fatal(err)
	}
	if out := applyReorders(cleanSortReport, containers); out != cleanSortReport {
		t.Fatalf("already-sorted report changed:\n%s", out)
	}
}

func TestApplyReordersNestedFrames(t *testing.T) {
	raw := `<jasperReport name="t">
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
</jasperReport>`
	containers, err := parse([]byte(raw))
	if err != nil {
		t.Fatal(err)
	}
	out := applyReorders(raw, containers)
	validXML(t, out)

	// inner frame children: image(0) before textField(30)
	if !(strings.Index(out, `uuid="i0"`) < strings.Index(out, `uuid="i1"`)) {
		t.Fatalf("inner frame not reordered:\n%s", out)
	}
	// outer frame: inner(0,0) and o0(0,0) tie -> stable keeps inner first, then o0, then o1(40)
	iInner, iO0, iO1 := strings.Index(out, `uuid="inner"`), strings.Index(out, `uuid="o0"`), strings.Index(out, `uuid="o1"`)
	if !(iInner < iO0 && iO0 < iO1) {
		t.Fatalf("outer frame order wrong: inner=%d o0=%d o1=%d\n%s", iInner, iO0, iO1, out)
	}
}

// nestedComponentSort has a band containing a <component> (a table) that nests
// its own <element> children. Those nested elements are NOT direct children of
// the band and must not be collected or reordered (regression: they used to be
// collected, producing overlapping spans and a slice-bounds panic).
const nestedComponentSort = `<jasperReport name="t">
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
</jasperReport>`

func TestParseSkipsNestedComponentElements(t *testing.T) {
	containers, err := parse([]byte(nestedComponentSort))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(containers) != 1 {
		t.Fatalf("got %d containers, want 1 (the band): %+v", len(containers), containers)
	}
	got := childKinds(containers[0].children)
	if len(got) != 2 || got[0] != "component" || got[1] != "subreport" {
		t.Fatalf("band children = %v, want [component subreport] (no nested textField)", got)
	}
}

func TestApplyReordersNestedComponentDoesNotPanic(t *testing.T) {
	containers, err := parse([]byte(nestedComponentSort))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	out := applyReorders(nestedComponentSort, containers)
	validXML(t, out)

	// the band children reorder by (y,x): subreport(68,41) before component(120,120),
	// and the nested textField stays inside the component.
	iS, iC, iN := strings.Index(out, `uuid="s1"`), strings.Index(out, `uuid="c1"`), strings.Index(out, `uuid="nested"`)
	if !(iS >= 0 && iS < iC && iC < iN) {
		t.Fatalf("order wrong: s1=%d c1=%d nested=%d\n%s", iS, iC, iN, out)
	}
}

func TestSortedChildrenStable(t *testing.T) {
	// equal (y,x) must preserve original relative order (stable)
	cs := []*child{
		{kind: "a", x: 0, y: 0},
		{kind: "b", x: 0, y: 0},
		{kind: "c", x: 0, y: 5},
		{kind: "d", x: 10, y: 0},
	}
	got := sortedChildren(cs)
	want := []string{"a", "b", "d", "c"} // y0 (a,b stable; then d x=10), then y5 (c)
	for i := range want {
		if got[i].kind != want[i] {
			t.Fatalf("sorted = %v, want %v", childKinds(got), want)
		}
	}
}

func TestOverlaps(t *testing.T) {
	cs := []*child{
		{kind: "a", x: 0, y: 0, w: 10, h: 10},
		{kind: "b", x: 5, y: 5, w: 4, h: 4},       // inside a -> overlaps
		{kind: "c", x: 100, y: 100, w: 10, h: 10}, // isolated
		{kind: "d", x: 0, y: 10, w: 10, h: 10},    // touches a's bottom edge -> not overlap
	}
	ov := overlaps(cs)
	if len(ov) != 1 {
		t.Fatalf("overlaps = %d, want 1: %+v", len(ov), ov)
	}
	o := ov[0]
	if !((o.a.kind == "a" && o.b.kind == "b") || (o.a.kind == "b" && o.b.kind == "a")) {
		t.Fatalf("expected a<->b overlap, got %s<->%s", o.a.kind, o.b.kind)
	}
}

func TestReorderChanged(t *testing.T) {
	containers, _ := parse([]byte(sampleSort))
	changed := 0
	for _, c := range containers {
		if reorderChanged(c) {
			changed++
		}
	}
	if changed != 2 {
		t.Fatalf("changed containers = %d, want 2 (band + frame)", changed)
	}
}

func TestProcessFileReorders(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, sampleSort)
	jrxmlutil.SetPromptInput(strings.NewReader("a\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.modified {
		t.Fatalf("res = %+v, want modified", res)
	}
	out := readFile(t, path)
	validXML(t, out)
	if !(strings.Index(out, `uuid="A"`) < strings.Index(out, `uuid="B"`)) {
		t.Error("band not reordered to A,B,F")
	}
}

func TestProcessFileNoAnswers(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, sampleSort)
	jrxmlutil.SetPromptInput(strings.NewReader("n\nn\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if res.modified {
		t.Fatalf("res = %+v, want unmodified when all answers are No", res)
	}
	if got := readFile(t, path); got != sampleSort {
		t.Error("file must remain byte-identical when all fixes rejected")
	}
}

func TestProcessFileSkip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, sampleSort)
	jrxmlutil.SetPromptInput(strings.NewReader("s\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.skipped {
		t.Fatalf("res = %+v, want skipped", res)
	}
	if got := readFile(t, path); got != sampleSort {
		t.Error("skipped file must remain byte-identical")
	}
}

func TestProcessFileClean(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, cleanSortReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if res.modified || res.skipped {
		t.Fatalf("res = %+v, want no changes", res)
	}
}

func TestProcessFileMissing(t *testing.T) {
	jrxmlutil.SetPromptInput(strings.NewReader(""))
	if _, err := processFile(filepath.Join(t.TempDir(), "nope.jrxml"), false); err == nil {
		t.Fatal("processFile on missing file should error")
	}
}

func TestProcessFileInvalidXML(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "bad.jrxml")
	writeFile(t, path, "<jasperReport><unclosed>")
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	_, err := processFile(path, false)
	if err == nil || !strings.Contains(err.Error(), "parse XML") {
		t.Fatalf("err = %v, want parse XML error", err)
	}
}

func TestCheckDoesNotModify(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, sampleSort)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Check([]string{path}); code != 0 {
		t.Fatalf("Check = %d, want 0 (informational)", code)
	}
	if got := readFile(t, path); got != sampleSort {
		t.Error("Check must not modify the file")
	}
}

func TestRunHelp(t *testing.T) {
	if code := Run([]string{"-h"}); code != 0 {
		t.Fatalf("Run(-h) = %d, want 0", code)
	}
	if code := Run([]string{"--help"}); code != 0 {
		t.Fatalf("Run(--help) = %d, want 0", code)
	}
}

func TestRunStopsCommitOnReorder(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, sampleSort)
	jrxmlutil.SetPromptInput(strings.NewReader("a\n"))

	if code := Run([]string{path}); code != 1 {
		t.Fatalf("Run = %d, want 1 (reorder left unstaged)", code)
	}
	out := readFile(t, path)
	if !(strings.Index(out, `uuid="A"`) < strings.Index(out, `uuid="B"`)) {
		t.Error("band not reordered")
	}
}

func TestRunSkipReturnsZero(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, sampleSort)
	jrxmlutil.SetPromptInput(strings.NewReader("s\n"))

	if code := Run([]string{path}); code != 0 {
		t.Fatalf("Run = %d, want 0 (file skipped)", code)
	}
	if got := readFile(t, path); got != sampleSort {
		t.Error("skipped file must remain byte-identical")
	}
}

func TestRunCleanReturnsZero(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, cleanSortReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Run([]string{path}); code != 0 {
		t.Fatalf("Run = %d, want 0", code)
	}
}

func TestRunNoFiles(t *testing.T) {
	dir := t.TempDir()
	t.Chdir(dir)
	t.Setenv("GIT_DIR", dir)
	if code := Run([]string{}); code != 0 {
		t.Fatalf("Run(no files) = %d, want 0", code)
	}
}

func childKinds(cs []*child) []string {
	out := make([]string, len(cs))
	for i, c := range cs {
		out[i] = c.kind
	}
	return out
}
