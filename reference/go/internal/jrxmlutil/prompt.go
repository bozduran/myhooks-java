package jrxmlutil

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"unsafe"
)

// Choice is the user's answer to an interactive prompt.
type Choice int

const (
	Yes Choice = iota
	No
	All
	Skip
)

type option struct {
	label  string
	choice Choice
}

var options = []option{
	{"Yes", Yes},
	{"No", No},
	{"All", All},
	{"Skip file", Skip},
}

// promptFile is the file used for interactive input: os.Stdin when it is a
// real terminal, otherwise the controlling terminal (/dev/tty) when one is
// available — git runs hooks with /dev/null as stdin, so without this fallback
// interactive prompts under `git commit` could never read input. Piped input
// (a pipe or regular file) is respected as-is. promptFile stays nil when
// promptOverride is set (SetPromptInput), which forces line-based prompts.
var promptFile *os.File

// promptReader is shared across all line-based prompts so that buffered input
// is not lost between prompts.
var promptReader *bufio.Reader

// promptOverride, when set via SetPromptInput, replaces the real prompt input
// source. It exists so tests can feed canned yes/no/all/skip answers through
// the real line-based prompt path; it is nil in normal operation.
var promptOverride io.Reader

// SetPromptInput replaces the input source used by line-based prompts with r.
// It is intended for tests that drive interactive prompts with canned answers;
// normal callers never need it. Setting it disables the terminal (raw-mode)
// prompt path, so the arrow-key UI is not used while an override is active.
func SetPromptInput(r io.Reader) {
	promptOverride = r
	promptFile = nil
	promptReader = nil
}

func initPromptInput() {
	if promptReader != nil {
		return
	}
	if promptOverride != nil {
		promptReader = bufio.NewReader(promptOverride)
		return
	}
	if promptFile != nil {
		return
	}
	promptFile = os.Stdin
	if info, err := os.Stdin.Stat(); err == nil && shouldFallbackToTTY(info, isRealTerminal(os.Stdin)) {
		// char device but not a TTY (e.g. /dev/null under git hooks):
		// fall back to the controlling terminal.
		if tty, err := os.OpenFile("/dev/tty", os.O_RDWR, 0); err == nil && isRealTerminal(tty) {
			promptFile = tty
		}
	}
	promptReader = bufio.NewReader(promptFile)
}

// shouldFallbackToTTY reports whether stdin is a character device that is not
// a real terminal (e.g. /dev/null under git hooks) — the case where the prompt
// should try the controlling terminal (/dev/tty) for interactive input. Piped
// input (a pipe or regular file) is respected as-is.
func shouldFallbackToTTY(info os.FileInfo, stdinIsTTY bool) bool {
	return info.Mode()&os.ModeCharDevice != 0 && !stdinIsTTY
}

// Prompt asks the user a yes/no/all/skip question (question should end with
// "?"). In a terminal it renders arrow-key selectable options; otherwise it
// falls back to line-based y/n/a/s input. The default answer is No.
func Prompt(question string) Choice {
	initPromptInput()
	if promptFile == nil || !isRealTerminal(promptFile) {
		return promptLine(question)
	}

	restore, err := enableRawMode(promptFile)
	if err != nil {
		return promptLine(question)
	}
	defer restore()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(sig)
	go func() {
		<-sig
		restore()
		fmt.Fprintln(os.Stderr)
		os.Exit(130)
	}()

	sel := 1 // default to "No"
	for {
		renderPrompt(question, sel)
		key, err := readKeyRaw(promptFile)
		if err != nil {
			fmt.Println()
			return No
		}
		switch key {
		case "left", "up":
			sel--
			if sel < 0 {
				sel = len(options) - 1
			}
		case "right", "down":
			sel++
			if sel >= len(options) {
				sel = 0
			}
		case "enter":
			fmt.Println()
			return options[sel].choice
		case "y", "Y":
			fmt.Println()
			return Yes
		case "n", "N":
			fmt.Println()
			return No
		case "a", "A":
			fmt.Println()
			return All
		case "s", "S":
			fmt.Println()
			return Skip
		}
	}
}

// PromptText asks a free-form question and returns the user's answer as a
// single trimmed line, or "" when the input ends or is blank. It always uses
// line-based input (free-form text needs a normal, canonical terminal — the
// arrow-key raw-mode UI is only for the fixed-choice Prompt).
func PromptText(question string) string {
	initPromptInput()
	fmt.Printf("  %s ", question)
	line, err := promptReader.ReadString('\n')
	if err != nil && line == "" {
		fmt.Println()
		return ""
	}
	return strings.TrimSpace(line)
}

func promptLine(question string) Choice {
	fmt.Printf("  %s [y]es/[n]o/[a]ll/[s]kip (default no): ", question)
	line, err := promptReader.ReadString('\n')
	if err != nil && line == "" {
		fmt.Println("no")
		return No
	}
	switch strings.ToLower(strings.TrimSpace(line)) {
	case "y", "yes":
		return Yes
	case "a", "all":
		return All
	case "s", "skip":
		return Skip
	default:
		return No
	}
}

func renderPrompt(question string, sel int) {
	fmt.Printf("\r\x1b[2K%s  ", question)
	for i, o := range options {
		if i == sel {
			fmt.Printf("\x1b[7m%s\x1b[0m  ", o.label)
		} else {
			fmt.Printf("%s  ", o.label)
		}
	}
}

// isRealTerminal reports whether f is an actual terminal (TCGETS succeeds).
// A plain character device such as /dev/null is not a terminal.
func isRealTerminal(f *os.File) bool {
	fd := f.Fd()
	var t syscall.Termios
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, uintptr(syscall.TCGETS), uintptr(unsafe.Pointer(&t)))
	return errno == 0
}

// readKeyRaw reads a single logical key from a terminal already in raw mode.
func readKeyRaw(f *os.File) (string, error) {
	var b [3]byte
	n, err := f.Read(b[:1])
	if err != nil {
		return "", err
	}
	if n == 0 {
		return "", io.EOF
	}
	switch b[0] {
	case 0x1b: // ESC, likely an arrow sequence
		n, err = f.Read(b[1:3])
		if err != nil || n < 2 || b[1] != '[' {
			return "esc", nil
		}
		switch b[2] {
		case 'C':
			return "right", nil
		case 'D':
			return "left", nil
		case 'A':
			return "up", nil
		case 'B':
			return "down", nil
		}
		return "esc", nil
	case '\r', '\n':
		return "enter", nil
	default:
		return string(b[0]), nil
	}
}

// enableRawMode switches the terminal into non-canonical, no-echo mode and
// returns a function that restores the previous settings.
func enableRawMode(f *os.File) (restore func(), err error) {
	fd := f.Fd()
	var old syscall.Termios
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, uintptr(syscall.TCGETS), uintptr(unsafe.Pointer(&old))); errno != 0 {
		return func() {}, errno
	}
	raw := old
	raw.Lflag &^= syscall.ICANON | syscall.ECHO
	raw.Iflag &^= syscall.ICRNL | syscall.IXON
	raw.Cc[syscall.VMIN] = 1
	raw.Cc[syscall.VTIME] = 0
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, uintptr(syscall.TCSETS), uintptr(unsafe.Pointer(&raw))); errno != 0 {
		return func() {}, errno
	}
	return func() {
		_, _, _ = syscall.Syscall(syscall.SYS_IOCTL, fd, uintptr(syscall.TCSETS), uintptr(unsafe.Pointer(&old)))
	}, nil
}
