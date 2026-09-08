package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"myhooks/internal/jrxmlutil"
)

// cleanReport is fully compliant for both steps: no unused declarations, name
// matches the file, all attributes present, no formatting needed.
const cleanReport = `<jasperReport name="doc" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<parameter name="p" class="java.lang.String">
		<defaultValueExpression><![CDATA["v"]]></defaultValueExpression>
	</parameter>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA["Text" + $P{p}]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`

func TestRunCleanFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	if err := os.WriteFile(path, []byte(cleanReport), 0o644); err != nil {
		t.Fatal(err)
	}
	if code := run([]string{path}); code != 0 {
		t.Fatalf("run(clean file) = %d, want 0", code)
	}
}

func TestRunHelp(t *testing.T) {
	if code := run([]string{"-h"}); code != 0 {
		t.Fatalf("run(-h) = %d, want 0", code)
	}
}

// reportWithUnused has a parameter never referenced: the clear step will want
// to delete it and the format step will want attribute fixes on the element.
const reportWithUnused = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<parameter name="orphan" class="java.lang.String">
		<defaultValueExpression><![CDATA["v"]]></defaultValueExpression>
	</parameter>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
				<expression><![CDATA["Text"]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`

func TestRunStopsCommitOnChanges(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	if err := os.WriteFile(path, []byte(reportWithUnused), 0o644); err != nil {
		t.Fatal(err)
	}
	// "a" answers All for every prompt: the clear step deletes the unused
	// parameter, the format step applies the attribute fixes. Both steps stop
	// the commit, so run returns 1. Extra "a" lines are harmless.
	jrxmlutil.SetPromptInput(strings.NewReader("a\na\na\na\na\na\n"))

	if code := run([]string{path}); code != 1 {
		t.Fatalf("run = %d, want 1 (changes left unstaged)", code)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "orphan") {
		t.Error("unused parameter should have been deleted by the clear step")
	}
}

// formatOnlyReport only needs format-step fixes (missing attributes); the
// other steps find nothing.
const formatOnlyReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
				<expression><![CDATA["Text"]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`

func TestRunSubcommandReportDoesNotModify(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	if err := os.WriteFile(path, []byte(reportWithUnused), 0o644); err != nil {
		t.Fatal(err)
	}
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	// "report" is a step name, so only the report step runs; clear must not
	// delete the unused parameter.
	if code := run([]string{"report", path}); code != 0 {
		t.Fatalf("run(report) = %d, want 0", code)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), "orphan") {
		t.Error("report subcommand must not delete the unused parameter")
	}
}

func TestRunToggleFalseReportsOnly(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	if err := os.WriteFile(path, []byte(formatOnlyReport), 0o644); err != nil {
		t.Fatal(err)
	}
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	stepsEnabled["format"] = false
	defer func() { stepsEnabled["format"] = true }()

	if code := run([]string{path}); code != 0 {
		t.Fatalf("run = %d, want 0 (format toggled false = report only)", code)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), `positionType="Float"`) {
		t.Error("toggled-false format must not apply changes")
	}
}

func TestRunNoFiles(t *testing.T) {
	dir := t.TempDir()
	t.Chdir(dir)
	t.Setenv("GIT_DIR", dir)
	if code := run([]string{}); code != 0 {
		t.Fatalf("run(no files) = %d, want 0", code)
	}
}
