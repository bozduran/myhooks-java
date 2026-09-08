package clear

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"myhooks/internal/jrxmlutil"
)

// jsonqlFixReport has no unused declarations but one field that needs the
// jsonql property added (jsonql-only fix, staged automatically).
const jsonqlFixReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<field name="usedField" class="java.lang.String">
		<description><![CDATA[path.to.field]]></description>
	</field>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA[$F{usedField}]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`

// cleanJSONQLReport is fully clean for the clear step: every field used and
// every field with a description already carries its jsonql property.
const cleanJSONQLReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<field name="usedField" class="java.lang.String">
		<description><![CDATA[path.to.field]]></description>
		<property name="net.sf.jasperreports.jsonql.field.expression" value="path.to.field"/>
	</field>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA[$F{usedField}]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`

// descriptionMismatchReport has a used field whose <description> differs from
// its jsonql property value; the clear step should offer to fix the
// description to match the jsonql path.
const descriptionMismatchReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<field name="f" class="java.lang.String">
		<description><![CDATA[old.path]]></description>
		<property name="net.sf.jasperreports.jsonql.field.expression" value="new.path"/>
	</field>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA[$F{f}]]></expression>
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

func TestProcessFileDeletesAll(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, sampleReport)
	jrxmlutil.SetPromptInput(strings.NewReader("a\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.modified || !res.deletedAny {
		t.Fatalf("res = %+v, want modified+deletedAny", res)
	}
	out := readFile(t, path)
	validXML(t, out)
	for _, gone := range []string{"unusedParam", "unusedField", "legacyField"} {
		if strings.Contains(out, gone) {
			t.Errorf("result still contains deleted %s", gone)
		}
	}
	if !strings.Contains(out, `value="sub.only"`) {
		t.Error("surviving field subreportOnly should get its jsonql property")
	}
}

func TestProcessFileDeletesFirstOnly(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, sampleReport)
	// y for the first unused decl, n for the next two
	jrxmlutil.SetPromptInput(strings.NewReader("y\nn\nn\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.modified || !res.deletedAny {
		t.Fatalf("res = %+v, want modified+deletedAny", res)
	}
	out := readFile(t, path)
	validXML(t, out)
	if strings.Contains(out, "unusedParam") {
		t.Error("unusedParam should have been deleted (answered y)")
	}
	if !strings.Contains(out, "unusedField") || !strings.Contains(out, "legacyField") {
		t.Error("decls answered 'no' should have been kept")
	}
}

func TestProcessFileNoDeletionsStillAddsJSONQL(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, sampleReport)
	jrxmlutil.SetPromptInput(strings.NewReader("n\nn\nn\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.modified || res.deletedAny {
		t.Fatalf("res = %+v, want modified without deletions", res)
	}
	out := readFile(t, path)
	validXML(t, out)
	for _, kept := range []string{"unusedParam", "unusedField", "legacyField"} {
		if !strings.Contains(out, kept) {
			t.Errorf("no-deletion run lost %s", kept)
		}
	}
	if !strings.Contains(out, `value="sub.only"`) {
		t.Error("jsonql property should still be added to subreportOnly")
	}
}

func TestProcessFileSkipFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, sampleReport)
	jrxmlutil.SetPromptInput(strings.NewReader("s\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.skipped {
		t.Fatalf("res = %+v, want skipped", res)
	}
	if got := readFile(t, path); got != sampleReport {
		t.Error("skipped file must remain byte-identical")
	}
}

func TestProcessFileClean(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, cleanJSONQLReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if res.modified || res.deletedAny || res.skipped {
		t.Fatalf("res = %+v, want no changes", res)
	}
	if got := readFile(t, path); got != cleanJSONQLReport {
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
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, sampleReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Check([]string{path}); code != 0 {
		t.Fatalf("Check = %d, want 0 (informational)", code)
	}
	if got := readFile(t, path); got != sampleReport {
		t.Error("Check must not modify the file")
	}
}

func TestProcessFileSyncsDescription(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, descriptionMismatchReport)
	jrxmlutil.SetPromptInput(strings.NewReader("y\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.modified || !res.descriptionAny || res.deletedAny {
		t.Fatalf("res = %+v, want modified+descriptionAny without deletion", res)
	}
	out := readFile(t, path)
	validXML(t, out)
	if !strings.Contains(out, `<description><![CDATA[new.path]]></description>`) {
		t.Errorf("description not synced to jsonql path:\n%s", out)
	}
}

func TestCheckReportsDescriptionMismatch(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, descriptionMismatchReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Check([]string{path}); code != 0 {
		t.Fatalf("Check = %d, want 0", code)
	}
	if got := readFile(t, path); got != descriptionMismatchReport {
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

func TestRunStopsCommitOnDeletion(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, sampleReport)
	jrxmlutil.SetPromptInput(strings.NewReader("a\n"))

	if code := Run([]string{path}); code != 1 {
		t.Fatalf("Run = %d, want 1 (deletions left unstaged)", code)
	}
	out := readFile(t, path)
	if strings.Contains(out, "unusedParam") {
		t.Error("unusedParam should have been deleted")
	}
}

func TestRunJSONQLOnlyStagesFix(t *testing.T) {
	dir := t.TempDir()
	initGit(t, dir)
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, jsonqlFixReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Run([]string{path}); code != 0 {
		t.Fatalf("Run = %d, want 0 (jsonql fix staged)", code)
	}
	out, err := exec.Command("git", "diff", "--cached", "--name-only").Output()
	if err != nil {
		t.Fatalf("git diff: %v", err)
	}
	if !strings.Contains(string(out), "doc.jrxml") {
		t.Errorf("jsonql fix not staged: %s", out)
	}
}

func TestRunJSONQLGitAddFailureStopsCommit(t *testing.T) {
	dir := t.TempDir()
	t.Chdir(dir)
	// make git add fail deterministically: index in a missing directory
	t.Setenv("GIT_INDEX_FILE", filepath.Join(dir, "no", "such", "index"))
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, jsonqlFixReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Run([]string{path}); code != 1 {
		t.Fatalf("Run = %d, want 1 (staging failed)", code)
	}
}

func TestRunSkipFileReturnsZero(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, sampleReport)
	jrxmlutil.SetPromptInput(strings.NewReader("s\n"))

	if code := Run([]string{path}); code != 0 {
		t.Fatalf("Run = %d, want 0 (file skipped)", code)
	}
	if got := readFile(t, path); got != sampleReport {
		t.Error("skipped file must remain byte-identical")
	}
}

func TestRunCleanReturnsZero(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "doc.jrxml")
	writeFile(t, path, cleanJSONQLReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Run([]string{path}); code != 0 {
		t.Fatalf("Run = %d, want 0", code)
	}
}

func TestRunNoFiles(t *testing.T) {
	// force the staged-files discovery to fail (not a git repo) -> no files
	dir := t.TempDir()
	t.Chdir(dir)
	t.Setenv("GIT_DIR", dir)
	if code := Run([]string{}); code != 0 {
		t.Fatalf("Run(no files) = %d, want 0", code)
	}
}

func initGit(t *testing.T, dir string) {
	t.Helper()
	t.Chdir(dir)
	if out, err := exec.Command("git", "init", "-q").CombinedOutput(); err != nil {
		t.Fatalf("git init: %v: %s", err, out)
	}
}

// sqlQueryReport is clean for the clear step except for its SQL query, which
// should be suggested for migration to jsonql.
const sqlQueryReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<query language="sql"><![CDATA[SELECT id FROM users]]></query>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA["Text"]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`

func TestProcessFileMigratesSQLQuery(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, sqlQueryReport)
	// answer "y" to migrate, then supply the jsonql expression
	jrxmlutil.SetPromptInput(strings.NewReader("y\ndocument.users.id\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.modified || !res.queryChanged {
		t.Fatalf("res = %+v, want modified+queryChanged", res)
	}
	out := readFile(t, path)
	validXML(t, out)
	if !strings.Contains(out, `language="jsonql"`) {
		t.Errorf("query language not migrated:\n%s", out)
	}
	if !strings.Contains(out, `document.users.id`) {
		t.Errorf("query body not replaced:\n%s", out)
	}
	if strings.Contains(out, "SELECT id") {
		t.Errorf("SQL body still present:\n%s", out)
	}
}

func TestProcessFileKeepsSQLQueryOnNo(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, sqlQueryReport)
	jrxmlutil.SetPromptInput(strings.NewReader("n\n"))

	res, err := processFile(path, false)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if res.modified || res.queryChanged {
		t.Fatalf("res = %+v, want unmodified", res)
	}
	if got := readFile(t, path); got != sqlQueryReport {
		t.Error("file must remain byte-identical when the migration is declined")
	}
}

func TestCheckReportsSQLQuery(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, sqlQueryReport)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	res, err := processFile(path, true)
	if err != nil {
		t.Fatalf("processFile: %v", err)
	}
	if !res.found {
		t.Error("SQL query should be reported as a finding")
	}
	if got := readFile(t, path); got != sqlQueryReport {
		t.Error("Check must not modify the file")
	}
}

func TestRunStopsCommitOnQueryMigration(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.jrxml")
	writeFile(t, path, sqlQueryReport)
	jrxmlutil.SetPromptInput(strings.NewReader("y\ndocument.users.id\n"))

	if code := Run([]string{path}); code != 1 {
		t.Fatalf("Run = %d, want 1 (query migration left unstaged)", code)
	}
	out := readFile(t, path)
	if !strings.Contains(out, `language="jsonql"`) {
		t.Error("query should have been migrated to jsonql")
	}
}
