package jrxmlutil

import (
	"encoding/xml"
	"os"
	"strings"
	"testing"
)

func TestDiff(t *testing.T) {
	got := Diff("old", "new", "  ")
	want := "  \x1b[41m\x1b[30m- old\x1b[0m\n  \x1b[42m\x1b[30m+ new\x1b[0m\n"
	if got != want {
		t.Errorf("Diff = %q, want %q", got, want)
	}

	// pure deletion: only a red-background "-" line
	if got := Diff("gone", "", ""); got != "\x1b[41m\x1b[30m- gone\x1b[0m\n" {
		t.Errorf("deletion Diff = %q", got)
	}
	// pure insertion: only a green-background "+" line
	if got := Diff("", "added", ""); got != "\x1b[42m\x1b[30m+ added\x1b[0m\n" {
		t.Errorf("insertion Diff = %q", got)
	}
	// multi-line both sides
	want = "\x1b[41m\x1b[30m- a\x1b[0m\n\x1b[41m\x1b[30m- b\x1b[0m\n\x1b[42m\x1b[30m+ a\x1b[0m\n\x1b[42m\x1b[30m+ c\x1b[0m\n"
	if got := Diff("a\nb", "a\nc", ""); got != want {
		t.Errorf("multiline Diff = %q, want %q", got, want)
	}
	// both empty
	if got := Diff("", "", ""); got != "" {
		t.Errorf("empty Diff = %q", got)
	}
	// a trailing newline in the edit text does not emit a spurious empty line
	if got := Diff("old", "new\n", ""); got != "\x1b[41m\x1b[30m- old\x1b[0m\n\x1b[42m\x1b[30m+ new\x1b[0m\n" {
		t.Errorf("trailing-newline Diff = %q", got)
	}
}

func TestPromptText(t *testing.T) {
	SetPromptInput(strings.NewReader("document.iddata\n"))
	if got := PromptText("jsonql expression:"); got != "document.iddata" {
		t.Errorf("PromptText = %q, want document.iddata", got)
	}
	// blank line -> ""
	SetPromptInput(strings.NewReader("   \n"))
	if got := PromptText("again:"); got != "" {
		t.Errorf("PromptText blank = %q, want empty", got)
	}
	// exhausted input -> ""
	SetPromptInput(strings.NewReader(""))
	if got := PromptText("once more:"); got != "" {
		t.Errorf("PromptText eof = %q, want empty", got)
	}
}

func TestApplyEdits(t *testing.T) {
	// replace
	if got := ApplyEdits("abcXYZdef", []Edit{{Start: 3, End: 6, Text: "123"}}); got != "abc123def" {
		t.Errorf("replace = %q", got)
	}
	// insertion (end == start)
	if got := ApplyEdits("ab", []Edit{{Start: 1, End: 1, Text: "X"}}); got != "aXb" {
		t.Errorf("insert = %q", got)
	}
	// deletion (empty text)
	if got := ApplyEdits("abcd", []Edit{{Start: 1, End: 3, Text: ""}}); got != "ad" {
		t.Errorf("delete = %q", got)
	}
	// multiple edits: replace [1,2)="b"->"Y" and [4,5)="e"->"Z"
	if got := ApplyEdits("abcdef", []Edit{{Start: 4, End: 5, Text: "Z"}, {Start: 1, End: 2, Text: "Y"}}); got != "aYcdZf" {
		t.Errorf("multi = %q, want aYcdZf", got)
	}
}

func TestEscapeAttr(t *testing.T) {
	if got := EscapeAttr(`a&b"c<d>e'f`); got != "a&amp;b&quot;c&lt;d&gt;e&apos;f" {
		t.Errorf("EscapeAttr = %q", got)
	}
}

func TestAttrValPresent(t *testing.T) {
	e := xml.StartElement{
		Name: xml.Name{Local: "element"},
		Attr: []xml.Attr{
			{Name: xml.Name{Local: "kind"}, Value: "textField"},
			{Name: xml.Name{Local: "textAdjust"}, Value: ""},
		},
	}
	if v := AttrVal(e, "kind"); v != "textField" {
		t.Errorf("AttrVal(kind) = %q", v)
	}
	if v := AttrVal(e, "missing"); v != "" {
		t.Errorf("AttrVal(missing) = %q", v)
	}
	if v, ok := AttrPresent(e, "textAdjust"); !ok || v != "" {
		t.Errorf("AttrPresent(textAdjust) = %q,%v, want empty,true", v, ok)
	}
	if _, ok := AttrPresent(e, "missing"); ok {
		t.Error("AttrPresent(missing) should be false")
	}
}

func TestStagedFilesFilters(t *testing.T) {
	files := StagedFiles([]string{"a.jrxml", "b.JRXML", "c.xml", "d.txt"})
	if len(files) != 2 || files[0] != "a.jrxml" || files[1] != "b.JRXML" {
		t.Errorf("StagedFiles = %v", files)
	}
}

func TestShouldFallbackToTTY(t *testing.T) {
	// /dev/null is a char device that is not a terminal -> use /dev/tty
	null, err := os.OpenFile(os.DevNull, os.O_RDONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer null.Close()
	info, err := null.Stat()
	if err != nil {
		t.Fatal(err)
	}
	if !shouldFallbackToTTY(info, false) {
		t.Error("/dev/null stdin should trigger the /dev/tty fallback")
	}
	if shouldFallbackToTTY(info, true) {
		t.Error("real TTY stdin must not trigger the fallback")
	}

	// a pipe is not a char device -> piped input must be respected as-is
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	defer w.Close()
	pinfo, err := r.Stat()
	if err != nil {
		t.Fatal(err)
	}
	if shouldFallbackToTTY(pinfo, false) {
		t.Error("piped input must not be replaced by /dev/tty")
	}
}
