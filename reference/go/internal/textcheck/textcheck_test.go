package textcheck

import (
	"encoding/xml"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"myhooks/internal/jrxmlutil"
)

func TestTransformTextContentPeriod(t *testing.T) {
	cases := []struct{ in, want string }{
		{"today.Swill", "today. Swill"},
		{"Text Field.Today is something else", "Text Field. Today is something else"},
		{"else. This", "else. This"}, // already spaced — untouched
		{"3.14", "3.14"},             // decimal — untouched
		{"something.", "something."}, // end of string — untouched
	}
	for _, c := range cases {
		got, findings := transformTextContent(c.in)
		if got != c.want {
			t.Errorf("period fix %q = %q, want %q", c.in, got, c.want)
		}
		if got != c.in && len(findings) == 0 {
			t.Errorf("period fix %q changed text but reported no finding", c.in)
		}
	}
}

func TestTransformTextContentDoubleSpace(t *testing.T) {
	cases := []struct{ in, want string }{
		{"a  b", "a b"},
		{"a   b", "a b"},
		{"a b", "a b"}, // single space — untouched
		{"a  b  c", "a b c"},
		{"  leading", " leading"},   // leading double space
		{"trailing  ", "trailing "}, // trailing double space
	}
	for _, c := range cases {
		got, findings := transformTextContent(c.in)
		if got != c.want {
			t.Errorf("double space %q = %q, want %q", c.in, got, c.want)
		}
		if got != c.in && !containsPrefix(findings, "remove double space") {
			t.Errorf("double space %q changed text but reported no finding: %v", c.in, findings)
		}
	}
}

func TestTransformTextContentUnrenderable(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"It\u2019s", "It's"},               // right single quote
		{"\u201Cquoted\u201D", `"quoted"`},  // curly double quotes
		{"a\u2013b\u2014c", "a-b-c"},        // en + em dash
		{"a\u00A0b", "a b"},                 // non-breaking space
		{"a\u200Bb", "ab"},                  // zero-width space removed
		{"three\u2026dots", "three...dots"}, // ellipsis
		{"\u2022 item", "- item"},           // bullet
	}
	for _, c := range cases {
		got, findings := transformTextContent(c.in)
		if got != c.want {
			t.Errorf("unrenderable %q = %q, want %q", c.in, got, c.want)
		}
		if len(findings) == 0 {
			t.Errorf("unrenderable %q changed text but reported no finding", c.in)
		}
	}
}

func TestTransformTextContentNBSPThenDoubleSpace(t *testing.T) {
	// a space directly before a non-breaking space collapses to one space.
	got, findings := transformTextContent("a \u00A0b")
	if got != "a b" {
		t.Errorf("got %q, want %q", got, "a b")
	}
	if !containsPrefix(findings, "replace non-breaking space") || !containsPrefix(findings, "remove double space") {
		t.Errorf("findings = %v, want both NBSP and double-space findings", findings)
	}
}

func TestTransformExpressionStringLiteralsOnly(t *testing.T) {
	cases := []struct{ in, want string }{
		{`"today.Swill"`, `"today. Swill"`},                               // literal fixed (text)
		{`"a  b" + $F{x}`, `"a b" + $F{x}`},                               // code untouched
		{`$F{x}.toString() + "foo.Bar"`, `$F{x}.toString() + "foo. Bar"`}, // code .toString untouched
	}
	for _, c := range cases {
		got, findings := transformExpression(c.in, true)
		if got != c.want {
			t.Errorf("expression %q = %q, want %q", c.in, got, c.want)
		}
		if got != c.in && len(findings) == 0 {
			t.Errorf("expression %q changed but reported no finding", c.in)
		}
	}
}

func TestTransformExpressionDoubleSpaceConcat(t *testing.T) {
	// regression: a subreport expression concatenating a variable with string
	// literals must have every double space (including trailing "asd  ")
	// collapsed, even though it is not a text field.
	in := `"asd  "+$V{Variable_2}+"s  ds  ds"`
	want := `"asd "+$V{Variable_2}+"s ds ds"`
	got, findings := transformExpression(in, false)
	if got != want {
		t.Errorf("transformExpression(%q) = %q, want %q (findings=%v)", in, got, want, findings)
	}
	if len(findings) == 0 {
		t.Error("expected a double-space finding")
	}
}

func TestTransformExpressionNonTextKeepsPeriod(t *testing.T) {
	// non-text expressions still get double-space collapse, but NOT the period
	// fix (JSON path / code safety: foo.Bar must stay foo.Bar).
	got, findings := transformExpression(`"foo.Bar  baz"`, false)
	if got != `"foo.Bar baz"` {
		t.Errorf("got %q, want %q (findings=%v)", got, `"foo.Bar baz"`, findings)
	}
}

func TestTransformExpressionCodeUntouched(t *testing.T) {
	// no double-quoted literal: code must be byte-identical
	in := `$F{x} == 1 && $F{y} != 2`
	if got, findings := transformExpression(in, false); got != in || len(findings) != 0 {
		t.Errorf("transformExpression(%q) = %q, %v; want unchanged", in, got, findings)
	}
}

// messyTextReport triggers every text check: a textField literal with a
// missing period space and a double space, and a staticText with smart quote
// and em dash characters that do not render in JasperReports' default fonts.
const messyTextReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<detail>
		<band height="40">
			<element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
				<expression><![CDATA["today.Swill  here"]]></expression>
			</element>
			<element kind="staticText" uuid="u2" x="0" y="20" width="100" height="20">
				<text><![CDATA[It` + "\u2019" + `s a test` + "\u2014" + `okay. Done]]></text>
			</element>
		</band>
	</detail>
</jasperReport>`

// cleanTextReport needs no fixes: proper spacing and ASCII characters.
const cleanTextReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
				<expression><![CDATA["Clean text" + $F{x}]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`

// nonTextReport has a period-before-uppercase inside a non-text element's
// expression; textcheck must NOT period-fix it (JSON path / code safety).
const nonTextReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<detail>
		<band height="20">
			<element kind="image" uuid="u1" x="0" y="0" width="100" height="20">
				<expression><![CDATA["foo.Bar"]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`

// nonTextDoubleSpaceReport has double spaces inside a non-text (subreport)
// expression; those must still be collapsed even though the period fix is not
// applied to non-text expressions.
const nonTextDoubleSpaceReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<detail>
		<band height="20">
			<element kind="subreport" uuid="u1" x="0" y="0" width="100" height="20">
				<expression><![CDATA["asd  "+$V{Variable_2}+"s  ds  ds"]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`

func TestParseFindsTextChanges(t *testing.T) {
	changes, err := parse([]byte(messyTextReport))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(changes) != 2 {
		t.Fatalf("got %d changes, want 2: %+v", len(changes), changes)
	}
}

func TestParseNonTextKeepsPeriod(t *testing.T) {
	changes, err := parse([]byte(nonTextReport))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(changes) != 0 {
		t.Fatalf("non-text expression with no double space produced changes: %+v", changes)
	}
}

func TestParseNonTextDoubleSpace(t *testing.T) {
	changes, err := parse([]byte(nonTextDoubleSpaceReport))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(changes) != 1 {
		t.Fatalf("got %d changes, want 1: %+v", len(changes), changes)
	}
	if changes[0].edit.Text != `"asd "+$V{Variable_2}+"s ds ds"` {
		t.Errorf("edit text = %q", changes[0].edit.Text)
	}
}

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

func TestProcessFileAppliesAllFixes(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "messy.jrxml")
	writeFile(t, path, messyTextReport)
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

	for _, want := range []string{
		`"today. Swill here"`,    // period fix + double space
		`It's a test-okay. Done`, // smart quote + em dash
	} {
		if !strings.Contains(out, want) {
			t.Errorf("text-checked output missing %q", want)
		}
	}
}

func TestProcessFileNoAnswers(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "messy.jrxml")
	writeFile(t, path, messyTextReport)
	jrxmlutil.SetPromptInput(strings.NewReader("n\nn\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if res.modified {
		t.Fatalf("res = %+v, want unmodified when every fix is rejected", res)
	}
	if got := readFile(t, path); got != messyTextReport {
		t.Error("file must remain byte-identical when all fixes are rejected")
	}
}

func TestProcessFileSkip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "messy.jrxml")
	writeFile(t, path, messyTextReport)
	jrxmlutil.SetPromptInput(strings.NewReader("s\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.skipped {
		t.Fatalf("res = %+v, want skipped", res)
	}
	if got := readFile(t, path); got != messyTextReport {
		t.Error("skipped file must remain byte-identical")
	}
}

func TestProcessFileClean(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, cleanTextReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if res.modified || res.skipped {
		t.Fatalf("res = %+v, want no changes", res)
	}
	if got := readFile(t, path); got != cleanTextReport {
		t.Error("clean file must remain byte-identical")
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
	path := filepath.Join(dir, "messy.jrxml")
	writeFile(t, path, messyTextReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Check([]string{path}); code != 0 {
		t.Fatalf("Check = %d, want 0 (informational)", code)
	}
	if got := readFile(t, path); got != messyTextReport {
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

func TestRunStopsCommitOnFixes(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "messy.jrxml")
	writeFile(t, path, messyTextReport)
	jrxmlutil.SetPromptInput(strings.NewReader("a\n"))

	if code := Run([]string{path}); code != 1 {
		t.Fatalf("Run = %d, want 1 (fixes left unstaged)", code)
	}
	out := readFile(t, path)
	if !strings.Contains(out, `"today. Swill here"`) {
		t.Error("fixes should have been applied to the file")
	}
}

func TestRunSkipFileReturnsZero(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "messy.jrxml")
	writeFile(t, path, messyTextReport)
	jrxmlutil.SetPromptInput(strings.NewReader("s\n"))

	if code := Run([]string{path}); code != 0 {
		t.Fatalf("Run = %d, want 0 (file skipped)", code)
	}
	if got := readFile(t, path); got != messyTextReport {
		t.Error("skipped file must remain byte-identical")
	}
}

func TestRunCleanReturnsZero(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, cleanTextReport)
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

// paramVarPeriodReport has a ".Uppercase" in a parameter default value and a
// variable initial value; both render as text and must get the period fix.
const paramVarPeriodReport = `<jasperReport name="t">
	<parameter name="p" class="java.lang.String">
		<defaultValueExpression><![CDATA["param.World"]]></defaultValueExpression>
	</parameter>
	<variable name="v" class="java.lang.String">
		<initialValueExpression><![CDATA["var.World"]]></initialValueExpression>
	</variable>
</jasperReport>`

func TestParsePeriodFixInParameterDefaultAndVariableInitial(t *testing.T) {
	changes, err := parse([]byte(paramVarPeriodReport))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(changes) != 2 {
		t.Fatalf("got %d changes, want 2: %+v", len(changes), changes)
	}
	if changes[0].edit.Text != `"param. World"` {
		t.Errorf("parameter default = %q", changes[0].edit.Text)
	}
	if changes[1].edit.Text != `"var. World"` {
		t.Errorf("variable initial = %q", changes[1].edit.Text)
	}
}

func TestProcessFileFixesParameterAndVariableDefault(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, paramVarPeriodReport)
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
	for _, want := range []string{`"param. World"`, `"var. World"`} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q:\n%s", want, out)
		}
	}
}

func containsPrefix(findings []string, prefix string) bool {
	for _, f := range findings {
		if strings.HasPrefix(f, prefix) {
			return true
		}
	}
	return false
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
