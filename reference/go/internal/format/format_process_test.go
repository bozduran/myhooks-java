package format

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"myhooks/internal/jrxmlutil"
)

// messyReport triggers every kind of fix: report name mismatch, missing
// attributes, text period fix, markup newline, and expression formatting.
const messyReport = `<jasperReport name="wrong" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<parameter name="p" class="java.lang.String">
		<defaultValueExpression><![CDATA[(String)IF(a==1?b!=2:c)]]></defaultValueExpression>
	</parameter>
	<detail>
		<band height="100">
			<element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
				<expression><![CDATA["today.Swill"]]></expression>
			</element>
			<element kind="staticText" uuid="u2" x="0" y="30" width="100" height="20" markup="styled">
				<text><![CDATA[First line.Today<br>Second<br/>line]]></text>
			</element>
			<element kind="break" uuid="u3" x="0" y="60" width="100" height="1"/>
		</band>
	</detail>
</jasperReport>`

// cleanFormatReport needs no fixes at all: name matches the file (t.jrxml),
// attributes present, expression clean.
const cleanFormatReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<parameter name="p" class="java.lang.String">
		<defaultValueExpression><![CDATA["clean"]]></defaultValueExpression>
	</parameter>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA["Clean text"]]></expression>
			</element>
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

func TestProcessFileAppliesAllFixes(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "messy.jrxml")
	writeFile(t, path, messyReport)
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
		`name="messy"`,                          // report name aligned to file
		`positionType="Float"`,                  // added to every element
		`textAdjust="StretchHeight"`,            // added to text fields
		`"today. Swill"`,                        // period fix
		`First line. Today<br/>Second<br/>line`, // markup newline -> <br/>
		`(String) IF(a == 1 ? b != 2 : c)`,      // expression formatting
	} {
		if !strings.Contains(out, want) {
			t.Errorf("formatted output missing %q", want)
		}
	}
}

func TestProcessFileNoAnswers(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "messy.jrxml")
	writeFile(t, path, messyReport)
	// answer No to every fix item
	jrxmlutil.SetPromptInput(strings.NewReader("n\nn\nn\nn\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if res.modified {
		t.Fatalf("res = %+v, want unmodified when every fix is rejected", res)
	}
	if got := readFile(t, path); got != messyReport {
		t.Error("file must remain byte-identical when all fixes are rejected")
	}
}

func TestProcessFileSkip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "messy.jrxml")
	writeFile(t, path, messyReport)
	jrxmlutil.SetPromptInput(strings.NewReader("s\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.skipped {
		t.Fatalf("res = %+v, want skipped", res)
	}
	if got := readFile(t, path); got != messyReport {
		t.Error("skipped file must remain byte-identical")
	}
}

func TestProcessFileClean(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, cleanFormatReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if res.modified || res.skipped {
		t.Fatalf("res = %+v, want no changes", res)
	}
	if got := readFile(t, path); got != cleanFormatReport {
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
	writeFile(t, path, messyReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Check([]string{path}); code != 0 {
		t.Fatalf("Check = %d, want 0 (informational)", code)
	}
	if got := readFile(t, path); got != messyReport {
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
	writeFile(t, path, messyReport)
	jrxmlutil.SetPromptInput(strings.NewReader("a\n"))

	if code := Run([]string{path}); code != 1 {
		t.Fatalf("Run = %d, want 1 (fixes left unstaged)", code)
	}
	out := readFile(t, path)
	if !strings.Contains(out, `name="messy"`) {
		t.Error("fixes should have been applied to the file")
	}
}

func TestRunSkipFileReturnsZero(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "messy.jrxml")
	writeFile(t, path, messyReport)
	jrxmlutil.SetPromptInput(strings.NewReader("s\n"))

	if code := Run([]string{path}); code != 0 {
		t.Fatalf("Run = %d, want 0 (file skipped)", code)
	}
	if got := readFile(t, path); got != messyReport {
		t.Error("skipped file must remain byte-identical")
	}
}

func TestRunCleanReturnsZero(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, cleanFormatReport)
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

func TestProcessFileFixesParameterAndVariableDefault(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, `<jasperReport name="t">
	<parameter name="p" class="java.lang.String">
		<defaultValueExpression><![CDATA["param.World"]]></defaultValueExpression>
	</parameter>
	<variable name="v" class="java.lang.String">
		<initialValueExpression><![CDATA["var.World"]]></initialValueExpression>
	</variable>
</jasperReport>`)
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
