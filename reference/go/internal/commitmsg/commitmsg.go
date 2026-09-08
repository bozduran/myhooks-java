// Package commitmsg implements the "commitmsg" step of the myhooks hook.
//
// It validates the commit message passed as a file path (the git commit-msg
// hook receives the message file as $1):
//
//  1. Semantic check — the first line must be a Conventional Commit
//     ("<type>(<scope>)!: <subject>"). A non-conforming message blocks the
//     commit (exit 1). This check is not skippable: the message must be fixed.
//
//  2. Typo check — misspellings are flagged using the misspell dictionary
//     (neutral English, so US/UK variants are not treated as errors). The
//     author may correct them (the message file is rewritten), skip the check
//     (accept the message as-is, no block), or leave them unfixed (block).
//     Technical and non-English words are safe: misspell only flags words in
//     its dictionary, and ignoreRules opts specific words out.
package commitmsg

import (
	"errors"
	"fmt"
	"os"
	"regexp"
	"strings"

	"github.com/client9/misspell"

	"myhooks/internal/jrxmlutil"
)

// allowedTypes are the Conventional Commit types accepted by the semantic
// check. Extend or trim this list to match the project's convention.
var allowedTypes = []string{
	"feat", "fix", "docs", "style", "refactor",
	"perf", "test", "build", "ci", "chore", "revert",
}

// semanticRe matches a Conventional Commit subject:
//
//	<type>(<scope>)!: <subject>
//
// The space after ':' is required, so "feat:add x" is rejected.
var semanticRe = regexp.MustCompile(`^(` + strings.Join(allowedTypes, "|") + `)(\([^)]+\))?!?: .+`)

// ignoreRules are misspellings to leave untouched. misspell only flags words in
// its dictionary, so technical and non-English words are already safe; this
// list opts specific words out in case one collides (now or in the future).
var ignoreRules = []string{
	"jsonql",
	"jrxml",
	"jasperreports",
	"subreport",
}

// replacer is the misspell engine with ignoreRules removed. Built once.
var replacer = func() *misspell.Replacer {
	r := misspell.New()
	r.RemoveRule(ignoreRules)
	r.Compile()
	return r
}()

// Run implements the commitmsg step (interactive). It returns 0 when the
// commit may continue, 1 when the semantic check failed or a typo was left
// unfixed (blocking the commit).
func Run(args []string) int {
	return run(args, false)
}

// Check lists what the commitmsg step would flag without prompting or applying
// anything. It is informational and always returns 0.
func Check(args []string) int {
	return run(args, true)
}

func run(args []string, checkOnly bool) int {
	if len(args) > 0 && (args[0] == "-h" || args[0] == "--help") {
		fmt.Println("myhooks commitmsg: validate the commit message (Conventional Commits + typo check).")
		fmt.Println("Usage: myhooks commitmsg <message-file>")
		return 0
	}
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "myhooks commitmsg: missing commit message file argument.")
		return 1
	}

	path := args[0]
	data, err := os.ReadFile(path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "myhooks commitmsg: %v\n", err)
		return 1
	}
	msg := string(data)

	// 1) semantic check.
	semanticErr := checkSemantic(msg)
	if semanticErr != nil {
		fmt.Printf("  [semantic] %v\n", semanticErr)
		fmt.Printf("  [semantic] expected: <type>(<scope>)!: <subject>\n")
		fmt.Printf("  [semantic] allowed types: %s\n", strings.Join(allowedTypes, ", "))
	}

	// 2) typo check.
	corrected, diffs := replacer.Replace(msg)
	if len(diffs) == 0 {
		if semanticErr == nil {
			fmt.Println("  [ok] commit message is valid")
		}
	} else {
		for _, d := range diffs {
			fmt.Printf("  [typo] %q -> %q (line %d)\n", d.Original, d.Corrected, d.Line)
		}
	}

	if checkOnly {
		return 0
	}

	if semanticErr != nil {
		return 1
	}
	if len(diffs) == 0 {
		return 0
	}

	fmt.Println("  Correct the typo(s)? yes = correct, no = stop commit, skip = accept as-is.")
	switch jrxmlutil.Prompt("Correct the typo(s) above?") {
	case jrxmlutil.Yes, jrxmlutil.All:
		if corrected != msg {
			if err := writeMessage(path, corrected); err != nil {
				fmt.Fprintf(os.Stderr, "myhooks commitmsg: %v\n", err)
				return 1
			}
			fmt.Println("  [fix] typos corrected in commit message")
		}
		return 0
	case jrxmlutil.Skip:
		fmt.Println("  [skip] commit message left as-is")
		return 0
	default: // No
		fmt.Fprintln(os.Stderr, "\nmyhooks commitmsg: commit stopped — fix the typos, or re-run and choose skip.")
		return 1
	}
}

// checkSemantic validates the subject (first line) of the commit message. It
// returns nil when the subject is a Conventional Commit.
func checkSemantic(msg string) error {
	subject := firstLine(msg)
	if subject == "" {
		return errors.New("commit message is empty")
	}
	if !semanticRe.MatchString(subject) {
		return fmt.Errorf("subject %q is not a Conventional Commit", subject)
	}
	return nil
}

// firstLine returns the trimmed first non-empty line of msg (the subject).
func firstLine(msg string) string {
	msg = strings.TrimSpace(msg)
	if i := strings.IndexByte(msg, '\n'); i >= 0 {
		return strings.TrimSpace(msg[:i])
	}
	return msg
}

// findTypos returns the misspell diffs found in msg.
func findTypos(msg string) []misspell.Diff {
	_, diffs := replacer.Replace(msg)
	return diffs
}

// correctMessage returns msg with misspellings corrected.
func correctMessage(msg string) string {
	corrected, _ := replacer.Replace(msg)
	return corrected
}

// writeMessage writes content back to path, preserving the file's mode.
func writeMessage(path, content string) error {
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	return os.WriteFile(path, []byte(content), info.Mode().Perm())
}
