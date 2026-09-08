package jrxmlutil

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"strings"
	"syscall"
	"testing"
	"unsafe"
)

// setPrompt sets a canned line-based input for the next Prompt calls.
func setPrompt(t *testing.T, input string) {
	t.Helper()
	SetPromptInput(strings.NewReader(input))
}

// openPTY allocates a pseudo-terminal pair. It skips the test when the
// environment cannot provide one (e.g. restricted sandboxes).
func openPTY(t *testing.T) (master, slave *os.File) {
	t.Helper()
	m, err := os.OpenFile("/dev/ptmx", os.O_RDWR, 0)
	if err != nil {
		t.Skipf("no /dev/ptmx: %v", err)
	}
	var n int32
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, m.Fd(), uintptr(syscall.TIOCGPTN), uintptr(unsafe.Pointer(&n))); errno != 0 {
		m.Close()
		t.Skipf("TIOCGPTN failed: %v", errno)
	}
	var unlock int32
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, m.Fd(), uintptr(syscall.TIOCSPTLCK), uintptr(unsafe.Pointer(&unlock))); errno != 0 {
		m.Close()
		t.Skipf("TIOCSPTLCK failed: %v", errno)
	}
	s, err := os.OpenFile(fmt.Sprintf("/dev/pts/%d", n), os.O_RDWR, 0)
	if err != nil {
		m.Close()
		t.Skipf("cannot open slave pty: %v", err)
	}
	return m, s
}

// pipeReader returns a reader over the given bytes (write end closed).
func pipeReader(t *testing.T, data string) *os.File {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { r.Close() })
	if data != "" {
		if _, err := w.WriteString(data); err != nil {
			t.Fatal(err)
		}
	}
	w.Close()
	return r
}

func TestPromptLineAnswers(t *testing.T) {
	cases := []struct {
		input string
		want  Choice
	}{
		{"y\n", Yes},
		{"Y\n", Yes},
		{"yes\n", Yes},
		{"n\n", No},
		{"no\n", No},
		{"a\n", All},
		{"all\n", All},
		{"s\n", Skip},
		{"skip\n", Skip},
		{"garbage\n", No}, // unrecognized -> default No
		{"", No},          // EOF with no input -> No
	}
	for _, c := range cases {
		setPrompt(t, c.input)
		if got := Prompt("question?"); got != c.want {
			t.Errorf("Prompt(%q) = %v, want %v", c.input, got, c.want)
		}
	}
}

func TestPromptLinePartialLine(t *testing.T) {
	// input without trailing newline: line is read with io.EOF but still parsed
	setPrompt(t, "y")
	if got := Prompt("question?"); got != Yes {
		t.Errorf("Prompt('y' no newline) = %v, want Yes", got)
	}
}

func TestPromptLineSharedReader(t *testing.T) {
	// buffered input must survive across prompts (multi-line answers)
	setPrompt(t, "y\nn\n")
	if got := Prompt("first?"); got != Yes {
		t.Fatalf("first Prompt = %v, want Yes", got)
	}
	if got := Prompt("second?"); got != No {
		t.Fatalf("second Prompt = %v, want No", got)
	}
}

func TestSetPromptInputResets(t *testing.T) {
	// after a raw-mode session (promptFile set), SetPromptInput must clear
	// all state so the next Prompt uses the override
	setPrompt(t, "a\n")
	if got := Prompt("q?"); got != All {
		t.Fatalf("Prompt = %v, want All", got)
	}
	SetPromptInput(strings.NewReader("s\n"))
	if got := Prompt("q?"); got != Skip {
		t.Fatalf("Prompt after re-set = %v, want Skip", got)
	}
}

// routePromptToPTY points the interactive prompt at a pty slave, mirroring
// what initPromptInput sets up for a real terminal: both the terminal file
// (raw mode) and a line reader (fallback path) are installed, and any
// leftover override from a previous test is cleared.
func routePromptToPTY(t *testing.T, slave *os.File) {
	t.Helper()
	SetPromptInput(nil) // clear override + stale state
	promptFile = slave
	promptReader = bufio.NewReader(slave)
}

func TestPromptRawModeKeys(t *testing.T) {
	master, slave := openPTY(t)
	defer master.Close()
	defer slave.Close()

	routePromptToPTY(t, slave)

	cases := []struct {
		keys string
		want Choice
	}{
		{"y", Yes},
		{"n", No},
		{"a", All},
		{"s", Skip},
		{"\x1b[C\r", All}, // right arrow wraps to All
		{"\x1b[D\r", Yes}, // left arrow from No wraps to Yes
		{"\x1b[B\r", All}, // down arrow -> All
		{"\x1b[A\r", Yes}, // up arrow -> Yes
	}
	for _, c := range cases {
		if _, err := master.WriteString(c.keys); err != nil {
			t.Fatalf("write keys %q: %v", c.keys, err)
		}
		got := Prompt("q?")
		if got != c.want {
			t.Errorf("keys %q -> %v, want %v", c.keys, got, c.want)
		}
	}
}

func TestPromptTerminalLostFallsBackToLineMode(t *testing.T) {
	master, slave := openPTY(t)
	routePromptToPTY(t, slave)
	master.Close() // terminal is gone: Prompt must fall back to line input
	defer slave.Close()

	got := Prompt("q?")
	if got != No {
		t.Errorf("Prompt with dead terminal = %v, want No (line EOF)", got)
	}
}

func TestReadKeyRaw(t *testing.T) {
	cases := []struct {
		data string
		want string
		err  bool
	}{
		{"a", "a", false},
		{"\r", "enter", false},
		{"\n", "enter", false},
		{"\x1b", "esc", false},  // bare ESC
		{"\x1bX", "esc", false}, // ESC + non-[ byte
		{"\x1b[C", "right", false},
		{"\x1b[D", "left", false},
		{"\x1b[A", "up", false},
		{"\x1b[B", "down", false},
		{"\x1b[Z", "esc", false}, // unknown sequence
		{"", "", true},           // EOF
	}
	for _, c := range cases {
		f := pipeReader(t, c.data)
		got, err := readKeyRaw(f)
		if c.err {
			if err == nil {
				t.Errorf("readKeyRaw(%q) = %q, want error", c.data, got)
			}
			continue
		}
		if err != nil || got != c.want {
			t.Errorf("readKeyRaw(%q) = %q, %v; want %q", c.data, got, err, c.want)
		}
	}
}

func TestRenderPrompt(t *testing.T) {
	out := captureStdout(t, func() { renderPrompt("Question?", 1) })
	for _, want := range []string{"Question?", "Yes", "No", "All", "Skip file"} {
		if !strings.Contains(out, want) {
			t.Errorf("renderPrompt output missing %q: %q", want, out)
		}
	}
	if !strings.Contains(out, "\x1b[7mNo\x1b[0m") {
		t.Errorf("renderPrompt should invert the selected (No) option: %q", out)
	}
}

func TestRenderPromptFirstSelected(t *testing.T) {
	out := captureStdout(t, func() { renderPrompt("Q?", 0) })
	if !strings.Contains(out, "\x1b[7mYes\x1b[0m") {
		t.Errorf("renderPrompt(sel=0) should invert Yes: %q", out)
	}
}

func TestIsRealTerminal(t *testing.T) {
	r := pipeReader(t, "")
	if isRealTerminal(r) {
		t.Error("pipe must not be a real terminal")
	}

	master, slave := openPTY(t)
	defer master.Close()
	defer slave.Close()
	if !isRealTerminal(slave) {
		t.Error("pty slave should be a real terminal")
	}
}

func TestEnableRawMode(t *testing.T) {
	// on a pipe, TCGETS fails -> error and a no-op restore
	r := pipeReader(t, "")
	restore, err := enableRawMode(r)
	if err == nil {
		t.Error("enableRawMode(pipe) should fail")
	}
	restore() // must not panic

	master, slave := openPTY(t)
	defer master.Close()
	defer slave.Close()
	restore, err = enableRawMode(slave)
	if err != nil {
		t.Fatalf("enableRawMode(pty) = %v", err)
	}
	restore()
	// after restore the terminal must still be a usable terminal
	if !isRealTerminal(slave) {
		t.Error("slave no longer a terminal after restore")
	}
}

func TestInitPromptInput(t *testing.T) {
	// override path: reader is installed and reused
	setPrompt(t, "n\n")
	initPromptInput()
	if promptReader == nil {
		t.Fatal("initPromptInput did not install a reader for the override")
	}
	// idempotent: a second call must not reset the buffered reader
	initPromptInput()
	if got := Prompt("q?"); got != No {
		t.Fatalf("Prompt = %v, want No", got)
	}

	// raw-mode path: a directly-set promptFile must not be clobbered
	master, slave := openPTY(t)
	defer master.Close()
	defer slave.Close()
	promptFile = slave
	promptReader = nil
	initPromptInput()
	if promptFile != slave {
		t.Fatal("initPromptInput clobbered a directly-set promptFile")
	}
	SetPromptInput(nil) // restore clean state for later tests
}

// captureStdout runs fn with os.Stdout redirected to a pipe and returns what
// fn wrote to stdout.
func captureStdout(t *testing.T, fn func()) string {
	t.Helper()
	old := os.Stdout
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stdout = w
	defer func() { os.Stdout = old }()
	fn()
	w.Close()
	out, _ := io.ReadAll(r)
	r.Close()
	return string(out)
}
