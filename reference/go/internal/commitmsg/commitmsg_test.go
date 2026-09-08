package commitmsg

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"myhooks/internal/jrxmlutil"
)

func TestCheckSemantic(t *testing.T) {
	valid := []string{
		"feat: add commit message check",
		"fix(api): correct a typo",
		"chore!: breaking change",
		"feat(scope)!: subject",
		"docs: update readme",
		"refactor(core): split parser",
	}
	for _, m := range valid {
		if err := checkSemantic(m); err != nil {
			t.Errorf("checkSemantic(%q) = %v, want nil", m, err)
		}
	}

	invalid := []string{
		"",
		"   ",
		"feat:add no space",
		"foo: unknown type",
		"feat:",
		"feat: ",
		"feat subject with no colon",
		"FEAT: uppercase type",
		"feat(scope):",
	}
	for _, m := range invalid {
		if err := checkSemantic(m); err == nil {
			t.Errorf("checkSemantic(%q) = nil, want error", m)
		}
	}
}

func TestCheckSemanticOnlyChecksSubject(t *testing.T) {
	msg := "feat: add check\n\nbody may contain a typo teh without failing the semantic check"
	if err := checkSemantic(msg); err != nil {
		t.Errorf("checkSemantic should only validate the subject: %v", err)
	}
}

func TestFindTypos(t *testing.T) {
	got := findTypos("Teh report has an adress and is seperate")
	if len(got) != 3 {
		t.Fatalf("findTypos = %d results, want 3: %+v", len(got), got)
	}
	want := map[string]string{"teh": "the", "adress": "address", "seperate": "separate"}
	for _, d := range got {
		if want[strings.ToLower(d.Original)] != strings.ToLower(d.Corrected) {
			t.Errorf("typo %q -> %q, want -> %q", d.Original, d.Corrected, want[strings.ToLower(d.Original)])
		}
	}
}

func TestCorrectMessagePreservesCase(t *testing.T) {
	in := "Teh teh TEH and an adress"
	got := correctMessage(in)
	for _, want := range []string{"The", "the", "THE", "address"} {
		if !strings.Contains(got, want) {
			t.Errorf("correctMessage(%q) = %q, missing %q", in, got, want)
		}
	}
}

func TestTechnicalAndNonEnglishNotFlagged(t *testing.T) {
	msg := "jsonql field expression für den Bericht über jasperreports"
	if typos := findTypos(msg); len(typos) != 0 {
		t.Errorf("findTypos(%q) = %v, want none", msg, typos)
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

func TestRunSemanticBlocks(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "msg")
	writeFile(t, path, "add a check without a type\n")
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Run([]string{path}); code != 1 {
		t.Fatalf("Run = %d, want 1 (semantic violation)", code)
	}
	if got := readFile(t, path); got != "add a check without a type\n" {
		t.Errorf("file must remain unchanged on semantic block: %q", got)
	}
}

func TestRunTypoCorrect(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "msg")
	writeFile(t, path, "feat: add check\n\nTeh adress is seperate\n")
	jrxmlutil.SetPromptInput(strings.NewReader("y\n"))

	if code := Run([]string{path}); code != 0 {
		t.Fatalf("Run = %d, want 0 (typos corrected)", code)
	}
	got := readFile(t, path)
	for _, want := range []string{"The address is separate"} {
		if !strings.Contains(got, want) {
			t.Errorf("corrected message missing %q:\n%s", want, got)
		}
	}
}

func TestRunTypoNoBlocks(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "msg")
	writeFile(t, path, "feat: add check\n\nTeh adress is seperate\n")
	jrxmlutil.SetPromptInput(strings.NewReader("n\n"))

	if code := Run([]string{path}); code != 1 {
		t.Fatalf("Run = %d, want 1 (typo left unfixed)", code)
	}
	if got := readFile(t, path); got != "feat: add check\n\nTeh adress is seperate\n" {
		t.Errorf("file must remain unchanged on No: %q", got)
	}
}

func TestRunTypoSkip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "msg")
	writeFile(t, path, "feat: add check\n\nTeh adress is seperate\n")
	jrxmlutil.SetPromptInput(strings.NewReader("s\n"))

	if code := Run([]string{path}); code != 0 {
		t.Fatalf("Run = %d, want 0 (typos skipped)", code)
	}
	if got := readFile(t, path); got != "feat: add check\n\nTeh adress is seperate\n" {
		t.Errorf("file must remain unchanged on skip: %q", got)
	}
}

func TestRunClean(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "msg")
	writeFile(t, path, "fix: correct an issue\n")
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Run([]string{path}); code != 0 {
		t.Fatalf("Run = %d, want 0 (valid message)", code)
	}
}

func TestCheckDoesNotModify(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "msg")
	const msg = "feat: add check\n\nTeh adress\n"
	writeFile(t, path, msg)
	jrxmlutil.SetPromptInput(strings.NewReader(""))

	if code := Check([]string{path}); code != 0 {
		t.Fatalf("Check = %d, want 0 (informational)", code)
	}
	if got := readFile(t, path); got != msg {
		t.Errorf("Check must not modify the file: %q", got)
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
