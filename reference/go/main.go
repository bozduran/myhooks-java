// Command myhooks is a single hook for JasperReports .jrxml files. It runs the
// commit message check (when installed as the commit-msg hook) and five
// file-check steps in sequence on the staged files:
//
//  0. commitmsg — validate the commit message: require a Conventional Commit
//     subject ("<type>(<scope>)!: <subject>") and flag common typos. A
//     non-conforming message blocks the commit; typos can be corrected,
//     skipped, or left unfixed (which also blocks).
//  1. clear     — check the <query> (suggest migrating a SQL query to jsonql),
//     remove unused <parameter>/<field>/<variable> declarations and add missing
//     jsonql field expressions.
//  2. format    — reformat text, attributes and Java expressions, and align the
//     <jasperReport name> with the file name.
//  3. sort      — reorder each <band>/<frame> container's element children by
//     geometry (y then x) so document order matches reading order.
//  4. textcheck — fix rendered-text quality: add a missing space after a "."
//     before an uppercase letter, collapse double spaces, and replace
//     characters that do not render in JasperReports' default fonts.
//  5. report    — informational: print the include-chain of the staged files,
//     inverted so the root is the top-most template that (transitively)
//     includes the staged file.
//
// Every step except report is interactive (yes / no / all / skip) and leaves
// any applied change UNSTAGED, exiting non-zero so the commit stops for
// review. A step toggled false in stepsEnabled runs in report-only mode (list
// what it would change, apply nothing, never stop the commit). report always
// runs last and never stops the commit.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"myhooks/internal/clear"
	"myhooks/internal/commitmsg"
	"myhooks/internal/format"
	"myhooks/internal/report"
	"myhooks/internal/sort"
	"myhooks/internal/textcheck"
)

func main() {
	// When this binary is installed as the git commit-msg hook (copied or
	// symlinked as .git/hooks/commit-msg), git passes the commit-message file
	// as $1 and we run only the commitmsg step.
	if filepath.Base(os.Args[0]) == "commit-msg" {
		os.Exit(runStep("commitmsg", os.Args[1:]))
	}
	os.Exit(run(os.Args[1:]))
}

// stepsEnabled controls which mutating steps run normally (true) or in
// report-only mode (false). Set a value to false to make that step only list
// what it would change, without prompting or applying. The report step always
// runs (it never changes files).
var stepsEnabled = map[string]bool{
	"commitmsg": true,
	"clear":     true,
	"format":    true,
	"sort":      true,
	"textcheck": true,
}

// isStep reports whether s is a known step name.
func isStep(s string) bool {
	switch s {
	case "commitmsg", "clear", "format", "sort", "textcheck", "report":
		return true
	}
	return false
}

// runStep invokes one step, honoring its enabled toggle.
func runStep(name string, args []string) int {
	switch name {
	case "commitmsg":
		if stepsEnabled["commitmsg"] {
			return commitmsg.Run(args)
		}
		return commitmsg.Check(args)
	case "clear":
		if stepsEnabled["clear"] {
			return clear.Run(args)
		}
		return clear.Check(args)
	case "format":
		if stepsEnabled["format"] {
			return format.Run(args)
		}
		return format.Check(args)
	case "sort":
		if stepsEnabled["sort"] {
			return sort.Run(args)
		}
		return sort.Check(args)
	case "textcheck":
		if stepsEnabled["textcheck"] {
			return textcheck.Run(args)
		}
		return textcheck.Check(args)
	case "report":
		// informational; always shown.
		return report.Run(args)
	}
	return 0
}

// headerColor wraps a step title/divider in green (bold).
const (
	headerColor = "\x1b[1;32m"
	headerReset = "\x1b[0m"
)

// printStepHeader prints a green, dash-delimited title for a step so the hook
// output is easy to scan.
func printStepHeader(name string) {
	line := strings.Repeat("-", 60)
	green := func(s string) string { return headerColor + s + headerReset }
	fmt.Printf("\n%s\n%s\n%s\n\n", green(line), green("  "+strings.ToUpper(name)), green(line))
}

func run(args []string) int {
	if len(args) > 0 && (args[0] == "-h" || args[0] == "--help") {
		usage()
		return 0
	}

	// A leading step name runs just that step (e.g. "myhooks report").
	if len(args) > 0 && isStep(args[0]) {
		rest := args[1:]
		if len(rest) == 0 || (rest[0] != "-h" && rest[0] != "--help") {
			printStepHeader(args[0])
		}
		return runStep(args[0], rest)
	}

	// Default: run every step in sequence. Run them all regardless of each
	// other's results, so the author reviews all changes in one pass. Any
	// enabled step stopping the commit stops it.
	printStepHeader("clear")
	code1 := runStep("clear", args)
	printStepHeader("format")
	code2 := runStep("format", args)
	printStepHeader("sort")
	code3 := runStep("sort", args)
	printStepHeader("textcheck")
	code4 := runStep("textcheck", args)
	printStepHeader("report")
	runStep("report", args) // informational; never stops the commit
	if code1 != 0 || code2 != 0 || code3 != 0 || code4 != 0 {
		return 1
	}
	return 0
}

func usage() {
	fmt.Println("myhooks: JasperReports pre-commit hook (commitmsg + clear + format + sort + textcheck + report).")
	fmt.Println("Usage:")
	fmt.Println("  myhooks [file.jrxml ...]          run all enabled steps on the staged files")
	fmt.Println("  myhooks <step> [file.jrxml ...]   run one step (commitmsg|clear|format|sort|textcheck|report)")
	fmt.Println("  myhooks commitmsg <message-file>  validate the commit message (commit-msg hook)")
	fmt.Println("Steps toggled false in main.go (stepsEnabled) only report, without applying.")
}
