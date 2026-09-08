// Package jrxmlutil holds helpers shared by the myhooks commands (clear and
// format): surgical edit application, XML attribute access, and staged-file
// discovery.
package jrxmlutil

import (
	"encoding/xml"
	"fmt"
	"os"
	"os/exec"
	"sort"
	"strings"
)

// Edit replaces raw[start:end] with Text. A pure deletion is Text == ""
// (start/end bound the removed range); a pure insertion is end == start.
type Edit struct {
	Start int
	End   int
	Text  string
}

// ApplyEdits applies edits in descending Start order so earlier offsets stay
// valid regardless of length changes.
func ApplyEdits(raw string, edits []Edit) string {
	sort.Slice(edits, func(i, j int) bool { return edits[i].Start > edits[j].Start })
	for _, e := range edits {
		raw = raw[:e.Start] + e.Text + raw[e.End:]
	}
	return raw
}

// ANSI color codes used to render changes in git-diff style: removed lines get
// a red background, added lines a green background, both with black text.
const (
	ansiRed   = "\x1b[41m" // red background
	ansiGreen = "\x1b[42m" // green background
	ansiBlack = "\x1b[30m" // black foreground text
	ansiReset = "\x1b[0m"
)

// Diff renders a before/after change in git-diff style for the terminal: every
// removed line is prefixed "-" on a red background and every added line is
// prefixed "+" on a green background, both with black text. before is the
// original text (empty for a pure insertion) and after is the replacement
// (empty for a pure deletion). Each emitted line is prefixed with indent so it
// aligns with the surrounding list output. Diff returns "" when both sides are
// empty.
func Diff(before, after, indent string) string {
	render := func(prefix, bg, s string) string {
		var b strings.Builder
		for _, ln := range diffLines(s) {
			fmt.Fprintf(&b, "%s%s%s%s %s%s\n", indent, bg, ansiBlack, prefix, ln, ansiReset)
		}
		return b.String()
	}
	return render("-", ansiRed, before) + render("+", ansiGreen, after)
}

// diffLines splits s into display lines, dropping a single trailing newline
// (so an edit text that ends in "\n" does not produce a spurious empty line).
func diffLines(s string) []string {
	s = strings.TrimSuffix(s, "\n")
	if s == "" {
		return nil
	}
	return strings.Split(s, "\n")
}

// EscapeAttr escapes a string for use inside a double-quoted XML attribute.
func EscapeAttr(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch r {
		case '&':
			b.WriteString("&amp;")
		case '<':
			b.WriteString("&lt;")
		case '>':
			b.WriteString("&gt;")
		case '"':
			b.WriteString("&quot;")
		case '\'':
			b.WriteString("&apos;")
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}

// AttrVal returns the value of the named attribute, or "" if absent.
func AttrVal(e xml.StartElement, name string) string {
	for _, a := range e.Attr {
		if a.Name.Local == name {
			return a.Value
		}
	}
	return ""
}

// AttrPresent returns the attribute's value and whether it exists at all
// (which distinguishes an empty value from a missing one).
func AttrPresent(e xml.StartElement, name string) (string, bool) {
	for _, a := range e.Attr {
		if a.Name.Local == name {
			return a.Value, true
		}
	}
	return "", false
}

// StagedFiles returns the .jrxml files to process: the command-line arguments
// when given, otherwise the staged files from `git diff --cached`.
func StagedFiles(args []string) []string {
	var candidates []string
	if len(args) > 0 {
		candidates = args
	} else {
		out, err := exec.Command("git", "diff", "--cached", "--name-only", "--diff-filter=ACMR").Output()
		if err != nil {
			fmt.Fprintf(os.Stderr, "myhooks: failed to list staged files: %v\n", err)
			return nil
		}
		for _, line := range strings.Split(string(out), "\n") {
			if line = strings.TrimSpace(line); line != "" {
				candidates = append(candidates, line)
			}
		}
	}

	var files []string
	for _, f := range candidates {
		if strings.HasSuffix(strings.ToLower(f), ".jrxml") {
			files = append(files, f)
		}
	}
	return files
}
