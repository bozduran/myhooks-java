package report

import (
	"bytes"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"myhooks/internal/jrxmlutil"
)

func TestReportBaseName(t *testing.T) {
	cases := []struct{ path, want string }{
		{"sub.jrxml", "sub"},
		{"reports/sub.jrxml", "sub"},
		{"a.b.jrxml", "a.b"},
		{"sub", "sub"},
	}
	for _, c := range cases {
		if got := reportBaseName(c.path); got != c.want {
			t.Errorf("reportBaseName(%q) = %q, want %q", c.path, got, c.want)
		}
	}
}

func TestQuotedTokens(t *testing.T) {
	cases := []struct {
		in   string
		want []string
	}{
		{`"sub"`, []string{"sub"}},
		{`$P{SUBREPORT_DIR} + "sub"`, []string{"sub"}},
		{`"a" and "b"`, []string{"a", "b"}},
		{`"sub.jasper"`, []string{"sub.jasper"}}, // extension form is NOT the bare name
		{`no quotes here`, nil},
	}
	for _, c := range cases {
		got := quotedTokens(c.in)
		if len(got) != len(c.want) {
			t.Errorf("quotedTokens(%q) = %v, want %v", c.in, got, c.want)
			continue
		}
		for i := range got {
			if got[i] != c.want[i] {
				t.Errorf("quotedTokens(%q)[%d] = %q, want %q", c.in, i, got[i], c.want[i])
			}
		}
	}
}

func TestBuildUsageTreeInverted(t *testing.T) {
	files := map[string]string{
		"sub.jrxml":       `root`,
		"main.jrxml":      `ref "sub"`,
		"dashboard.jrxml": `ref "main"`,
		"other.jrxml":     `ref "sub"`,
		"unrelated.jrxml": `nothing`,
	}
	trees := buildUsageTree(files, []string{"sub.jrxml"})
	if len(trees) != 2 {
		t.Fatalf("got %d trees, want 2 (dashboard and other): %+v", len(trees), trees)
	}

	// tops sorted by path: dashboard.jrxml then other.jrxml
	dash := trees[0]
	if dash.path != "dashboard.jrxml" {
		t.Fatalf("trees[0] = %q, want dashboard.jrxml", dash.path)
	}
	if len(dash.children) != 1 || dash.children[0].path != "main.jrxml" {
		t.Fatalf("dashboard children = %v, want [main.jrxml]", dash.children)
	}
	main := dash.children[0]
	if len(main.children) != 1 || main.children[0].path != "sub.jrxml" {
		t.Fatalf("main children = %v, want [sub.jrxml]", main.children)
	}

	other := trees[1]
	if other.path != "other.jrxml" {
		t.Fatalf("trees[1] = %q, want other.jrxml", other.path)
	}
	if len(other.children) != 1 || other.children[0].path != "sub.jrxml" {
		t.Fatalf("other children = %v, want [sub.jrxml]", other.children)
	}
}

func TestBuildUsageTreeGroupsMultipleStaged(t *testing.T) {
	// one top template includes two staged files.
	files := map[string]string{
		"top.jrxml":  `ref "sub1" ref "sub2"`,
		"sub1.jrxml": `root`,
		"sub2.jrxml": `root`,
	}
	trees := buildUsageTree(files, []string{"sub1.jrxml", "sub2.jrxml"})
	if len(trees) != 1 {
		t.Fatalf("got %d trees, want 1: %+v", len(trees), trees)
	}
	top := trees[0]
	if top.path != "top.jrxml" {
		t.Fatalf("root = %q, want top.jrxml", top.path)
	}
	if len(top.children) != 2 {
		t.Fatalf("top children = %v, want [sub1.jrxml sub2.jrxml]", top.children)
	}
	if top.children[0].path != "sub1.jrxml" || top.children[1].path != "sub2.jrxml" {
		t.Errorf("top children = [%s %s], want [sub1.jrxml sub2.jrxml]",
			top.children[0].path, top.children[1].path)
	}
}

func TestBuildUsageTreeCycleIsCut(t *testing.T) {
	// a references b and b references a: no top template exists, so the staged
	// file is used as the root and the cycle is cut.
	files := map[string]string{
		"a.jrxml": `ref "b"`,
		"b.jrxml": `ref "a"`,
	}
	trees := buildUsageTree(files, []string{"a.jrxml"})
	if len(trees) != 1 {
		t.Fatalf("got %d trees, want 1", len(trees))
	}
	root := trees[0]
	if len(root.children) != 1 || root.children[0].path != "b.jrxml" {
		t.Fatalf("root children = %v, want [b.jrxml]", root.children)
	}
	if len(root.children[0].children) != 0 {
		t.Fatalf("cycle not cut: b children = %v", root.children[0].children)
	}
}

func TestBuildUsageTreeIgnoresSelfReference(t *testing.T) {
	files := map[string]string{"a.jrxml": `ref "a"`}
	trees := buildUsageTree(files, []string{"a.jrxml"})
	if len(trees) != 1 || len(trees[0].children) != 0 {
		t.Fatalf("self-reference should not produce children: %+v", trees)
	}
}

func TestColorPath(t *testing.T) {
	if got := colorPath("x.jrxml", true); got != ansiStaged+"x.jrxml"+ansiReset {
		t.Errorf("colorPath(staged) = %q", got)
	}
	if got := colorPath("x.jrxml", false); got != "x.jrxml" {
		t.Errorf("colorPath(unstaged) = %q, want plain", got)
	}
}

func TestRenderTreeColorsOnlyStaged(t *testing.T) {
	root := &node{path: "sub.jrxml", base: "sub"}
	root.children = append(root.children, &node{path: "main.jrxml", base: "main"})

	staged := map[string]bool{"sub.jrxml": true}
	var buf bytes.Buffer
	renderTree(&buf, root, staged)
	out := buf.String()

	if !strings.Contains(out, ansiStaged+"sub.jrxml"+ansiReset) {
		t.Errorf("staged root not colored: %q", out)
	}
	if strings.Contains(out, ansiStaged+"main.jrxml"+ansiReset) {
		t.Errorf("unstaged child should not be colored: %q", out)
	}
	if !strings.Contains(out, "main.jrxml") {
		t.Errorf("unstaged child missing: %q", out)
	}
	if !strings.Contains(out, "└──") {
		t.Errorf("tree connector missing: %q", out)
	}
}

func TestRenderTreeNotReferenced(t *testing.T) {
	root := &node{path: "sub.jrxml", base: "sub"}
	var buf bytes.Buffer
	renderTree(&buf, root, map[string]bool{"sub.jrxml": true})
	if !strings.Contains(buf.String(), "(not referenced)") {
		t.Errorf("expected (not referenced) note, got %q", buf.String())
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

func TestRunNoFiles(t *testing.T) {
	dir := t.TempDir()
	t.Chdir(dir)
	t.Setenv("GIT_DIR", dir)
	if code := Run([]string{}); code != 0 {
		t.Fatalf("Run(no files) = %d, want 0", code)
	}
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func initRepo(t *testing.T, dir string) {
	t.Helper()
	t.Chdir(dir)
	for _, c := range [][]string{
		{"git", "init", "-q"},
		{"git", "config", "user.email", "t@example.com"},
		{"git", "config", "user.name", "t"},
	} {
		if out, err := exec.Command(c[0], c[1:]...).CombinedOutput(); err != nil {
			t.Fatalf("%v: %v: %s", c, err, out)
		}
	}
}

// TestRunIntegrationBuildsColoredTree exercises the git plumbing end to end:
// main.jrxml is committed (tracked, not staged), sub.jrxml is staged and
// references the subreport by name. The report must color sub.jrxml and leave
// main.jrxml plain, and must always return 0.
func TestRunIntegrationBuildsColoredTree(t *testing.T) {
	dir := t.TempDir()
	initRepo(t, dir)

	const main = `<jasperReport name="main" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<detail>
		<band height="20">
			<element kind="subreport" uuid="u1" x="0" y="0" width="100" height="20">
				<subreportExpression><![CDATA[$P{SUBREPORT_DIR} + "sub"]]></subreportExpression>
			</element>
		</band>
	</detail>
</jasperReport>`
	const sub = `<jasperReport name="sub" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
</jasperReport>`

	writeFile(t, filepath.Join(dir, "main.jrxml"), main)
	if out, err := exec.Command("git", "add", "main.jrxml").CombinedOutput(); err != nil {
		t.Fatalf("git add main.jrxml: %v: %s", err, out)
	}
	if out, err := exec.Command("git", "commit", "-qm", "add main").CombinedOutput(); err != nil {
		t.Fatalf("git commit: %v: %s", err, out)
	}
	writeFile(t, filepath.Join(dir, "sub.jrxml"), sub)
	if out, err := exec.Command("git", "add", "sub.jrxml").CombinedOutput(); err != nil {
		t.Fatalf("git add sub.jrxml: %v: %s", err, out)
	}

	jrxmlutil.SetPromptInput(strings.NewReader(""))

	old := os.Stdout
	r, w, _ := os.Pipe()
	os.Stdout = w
	code := Run([]string{})
	w.Close()
	os.Stdout = old
	out, _ := io.ReadAll(r)

	if code != 0 {
		t.Fatalf("Run = %d, want 0 (informational)", code)
	}
	s := string(out)
	if !strings.Contains(s, ansiStaged+"sub.jrxml"+ansiReset) {
		t.Errorf("staged sub.jrxml not colored:\n%s", s)
	}
	if strings.Contains(s, ansiStaged+"main.jrxml"+ansiReset) {
		t.Errorf("tracked-but-unstaged main.jrxml should not be colored:\n%s", s)
	}
	if !strings.Contains(s, "main.jrxml") {
		t.Errorf("referencing main.jrxml missing from report:\n%s", s)
	}
	// inverted: the top template (main) is the root, the staged file is the leaf.
	if strings.Index(s, "main.jrxml") > strings.Index(s, "sub.jrxml") {
		t.Errorf("expected main.jrxml (top) before sub.jrxml (leaf):\n%s", s)
	}
}
