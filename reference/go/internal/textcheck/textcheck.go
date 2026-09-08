// Package textcheck implements the "textcheck" step of the myhooks pre-commit
// hook.
//
// It scans the rendered text of JasperReports .jrxml files — the CDATA body of
// staticText <text> elements and the double-quoted string literals inside every
// <expression> (textField, subreport, variable, ...) — for three kinds of
// problem:
//
//  1. A "." immediately followed by an uppercase letter with no space between
//     ("today.Swill" -> "today. Swill"). Applied only to rendered-text
//     contexts — staticText/textField strings, parameter default values and
//     variable initial values — so JSON paths and code elsewhere are left
//     alone.
//  2. Double (or longer) runs of spaces, collapsed to a single space — applied
//     to string literals everywhere, including leading/trailing runs.
//  3. Characters that commonly fail to render with JasperReports' default
//     fonts (smart quotes, en/em dashes, non-breaking space, ellipsis, bullet,
//     zero-width space, soft hyphen, ...), each replaced by a safe ASCII
//     equivalent (see unrenderableChars below).
//
// Each fix is listed (grouped per element, with line numbers) and applied
// interactively (yes / no / all / skip). Approved fixes are written to the
// working file but left UNSTAGED, and Run returns 1 so the commit stops for
// review.
package textcheck

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"regexp"
	"strings"

	"myhooks/internal/jrxmlutil"
)

var (
	periodRe      = regexp.MustCompile(`\.([A-Z])`)
	doubleSpaceRe = regexp.MustCompile(` {2,}`)
)

// charReplace maps a rune that commonly does not render in JasperReports'
// default fonts to a safe ASCII replacement (an empty string removes it).
type charReplace struct {
	r    rune
	repl string
	name string
}

// unrenderableChars is the "common typography" set: smart quotes and dashes,
// non-breaking and zero-width spaces, soft hyphen, bullet and ellipsis. It is
// a fixed table so replacements are deterministic; extend it here when a
// report shows a new missing-glyph character.
var unrenderableChars = []charReplace{
	// invisible spacing / control characters
	{'\u00A0', " ", "non-breaking space"},
	{'\u2007', " ", "figure space"},
	{'\u2009', " ", "thin space"},
	{'\u200A', " ", "hair space"},
	{'\u202F', " ", "narrow no-break space"},
	{'\u200B', "", "zero-width space"},
	{'\u00AD', "", "soft hyphen"},

	// dashes and hyphens
	{'\u2010', "-", "hyphen"},
	{'\u2011', "-", "non-breaking hyphen"},
	{'\u2012', "-", "figure dash"},
	{'\u2013', "-", "en dash"},
	{'\u2014', "-", "em dash"},
	{'\u2015', "-", "horizontal bar"},

	// quotes
	{'\u2018', "'", "left single quote"},
	{'\u2019', "'", "right single quote"},
	{'\u201A', "'", "single low-9 quote"},
	{'\u201B', "'", "reversed-9 single quote"},
	{'\u201C', `"`, "left double quote"},
	{'\u201D', `"`, "right double quote"},
	{'\u201E', `"`, "double low-9 quote"},
	{'\u201F', `"`, "reversed-9 double quote"},

	// misc typography
	{'\u2022', "-", "bullet"},
	{'\u2023', "-", "triangular bullet"},
	{'\u2026', "...", "ellipsis"},
}

var unrenderableMap = func() map[rune]charReplace {
	m := make(map[rune]charReplace, len(unrenderableChars))
	for _, c := range unrenderableChars {
		m[c.r] = c
	}
	return m
}()

// expressionElements names the elements whose CDATA body can hold rendered
// text (the same set the format step uses).
var expressionElements = map[string]bool{
	"expression":             true,
	"text":                   true,
	"defaultValueExpression": true,
	"connectionExpression":   true,
	"textFieldExpression":    true,
	"textExpression":         true,
	"patternExpression":      true,
	"printWhenExpression":    true,
	"initialValueExpression": true,
	"variableExpression":     true,
	"groupExpression":        true,
}

// textElementKinds are the <element kind="..."> values whose content is
// rendered text and therefore eligible for these checks.
var textElementKinds = map[string]bool{
	"textField":  true,
	"staticText": true,
}

type change struct {
	owner string
	desc  string
	edit  jrxmlutil.Edit
}

type fixItem struct {
	label   string
	details []string
	edits   []jrxmlutil.Edit
}

type ctx struct {
	name  string // element name
	owner string // owning fix-item label
	kind  string // <element kind="...">
}

type contentOpen struct {
	elemName    string
	owner       string
	parentKind  string
	startTagEnd int
}

type fileResult struct {
	modified bool
	found    bool
	skipped  bool
}

// Run implements the textcheck step (interactive). It returns the process exit
// code: 0 when the commit can continue, 1 when changes were left unstaged (or
// an error).
func Run(args []string) int {
	return run(args, false)
}

// Check lists what the textcheck step would change without prompting or
// applying anything. It is informational and always returns 0.
func Check(args []string) int {
	return run(args, true)
}

func run(args []string, checkOnly bool) int {
	if len(args) > 0 && (args[0] == "-h" || args[0] == "--help") {
		fmt.Println("myhooks textcheck: fix text spacing and replace unrenderable characters in staged .jrxml files.")
		fmt.Println("Usage: myhooks textcheck [file.jrxml ...]")
		return 0
	}

	files := jrxmlutil.StagedFiles(args)
	if len(files) == 0 {
		fmt.Println("myhooks textcheck: no staged .jrxml files to check.")
		return 0
	}

	stopCommit := false
	for _, path := range files {
		res, err := processFile(path, checkOnly)
		if err != nil {
			fmt.Fprintf(os.Stderr, "myhooks textcheck: %s: %v\n", path, err)
			if !checkOnly {
				stopCommit = true
			}
			continue
		}
		if checkOnly {
			if res.found {
				fmt.Printf("  [report] %s: would fix text (not applied)\n", path)
			} else {
				fmt.Printf("  [ok]     %s\n", path)
			}
			continue
		}
		switch {
		case res.skipped:
			fmt.Printf("  [skip] %s left unchanged\n", path)
		case !res.modified:
			fmt.Printf("  [ok]   %s\n", path)
		default:
			stopCommit = true
			fmt.Printf("  [stop] %s: text fixes applied and left UNSTAGED for review.\n", path)
			fmt.Printf("         Review the diff, then 'git add %s' and commit again.\n", path)
		}
	}

	if stopCommit {
		fmt.Fprintln(os.Stderr, "\nmyhooks textcheck: commit stopped — review the .jrxml changes above and re-run.")
		return 1
	}
	return 0
}

func processFile(path string, checkOnly bool) (fileResult, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return fileResult{}, err
	}

	changes, err := parse(data)
	if err != nil {
		return fileResult{}, fmt.Errorf("parse XML: %w", err)
	}
	items := groupChanges(changes)
	if len(items) == 0 {
		return fileResult{}, nil
	}

	// List everything that needs fixing.
	fmt.Printf("checking %s\n", path)
	raw := string(data)
	for i, it := range items {
		fmt.Printf("  %2d. %s\n", i+1, it.label)
		for j, d := range it.details {
			fmt.Printf("        - %s\n", d)
			if j < len(it.edits) {
				fmt.Print(jrxmlutil.Diff(raw[it.edits[j].Start:it.edits[j].End], it.edits[j].Text, "          "))
			}
		}
	}

	if checkOnly {
		return fileResult{found: true}, nil
	}

	// Interactively decide which items to apply.
	skipFile := false
	allSelected := false
	var edits []jrxmlutil.Edit
	for i, it := range items {
		if skipFile {
			break
		}
		if allSelected {
			edits = append(edits, it.edits...)
			continue
		}
		question := fmt.Sprintf("Apply %d/%d %s — %s?", i+1, len(items), it.label, strings.Join(it.details, ", "))
		switch jrxmlutil.Prompt(question) {
		case jrxmlutil.Yes:
			edits = append(edits, it.edits...)
		case jrxmlutil.All:
			edits = append(edits, it.edits...)
			allSelected = true
		case jrxmlutil.Skip:
			skipFile = true
		case jrxmlutil.No:
			// leave this item untouched
		}
	}

	if skipFile {
		return fileResult{skipped: true}, nil
	}
	if len(edits) == 0 {
		return fileResult{}, nil
	}

	newRaw := jrxmlutil.ApplyEdits(string(data), edits)
	if newRaw == string(data) {
		return fileResult{}, nil
	}

	info, err := os.Stat(path)
	if err != nil {
		return fileResult{}, err
	}
	if err := os.WriteFile(path, []byte(newRaw), info.Mode().Perm()); err != nil {
		return fileResult{}, err
	}
	return fileResult{modified: true}, nil
}

func groupChanges(changes []change) []*fixItem {
	var items []*fixItem
	index := map[string]*fixItem{}
	for _, c := range changes {
		it, ok := index[c.owner]
		if !ok {
			it = &fixItem{label: c.owner}
			index[c.owner] = it
			items = append(items, it)
		}
		it.details = append(it.details, c.desc)
		it.edits = append(it.edits, c.edit)
	}
	return items
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

// parse walks the XML token stream and collects the rendered-text CDATA spans
// whose content needs one of the text fixes.
func parse(data []byte) ([]change, error) {
	dec := xml.NewDecoder(bytes.NewReader(data))
	lines := newlineIndex(data)

	var nameStack []string
	var ctxStack []ctx
	var contentStack []contentOpen
	var changes []change

	for {
		off := int(dec.InputOffset())
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, err
		}

		switch t := tok.(type) {
		case xml.StartElement:
			name := t.Name.Local
			parent := ""
			if len(nameStack) > 0 {
				parent = nameStack[len(nameStack)-1]
			}
			nameStack = append(nameStack, name)

			switch name {
			case "element":
				kind := jrxmlutil.AttrVal(t, "kind")
				owner := fmt.Sprintf("%s (line %d)", kind, lineOf(lines, off))
				ctxStack = append(ctxStack, ctx{name: "element", owner: owner, kind: kind})

			case "parameter", "variable":
				if parent == "jasperReport" {
					owner := fmt.Sprintf("%s '%s' (line %d)", name, jrxmlutil.AttrVal(t, "name"), lineOf(lines, off))
					ctxStack = append(ctxStack, ctx{name: name, owner: owner})
				}

			default:
				if expressionElements[name] {
					co := contentOpen{elemName: name, startTagEnd: int(dec.InputOffset())}
					if len(ctxStack) > 0 {
						co.owner = ctxStack[len(ctxStack)-1].owner
						co.parentKind = ctxStack[len(ctxStack)-1].kind
					}
					contentStack = append(contentStack, co)
				}
			}

		case xml.EndElement:
			name := t.Name.Local

			if expressionElements[name] && len(contentStack) > 0 {
				co := contentStack[len(contentStack)-1]
				if co.elemName == name {
					contentStack = contentStack[:len(contentStack)-1]
					// locate the CDATA section between the start tag and this end tag
					s := co.startTagEnd
					if idx := strings.Index(string(data[s:off]), "<![CDATA["); idx >= 0 {
						cs := s + idx + len("<![CDATA[")
						if end := strings.Index(string(data[cs:off]), "]]>"); end >= 0 {
							ce := cs + end
							content := string(data[cs:ce])

							var newContent string
							var findings []string
							if co.elemName == "text" {
								newContent, findings = transformTextContent(content)
							} else {
								// Every expression element's string literals get the
								// spacing/character checks; only rendered-text
								// contexts additionally get the period fix (which
								// would corrupt JSON paths and code elsewhere).
								newContent, findings = transformExpression(content, isTextContext(co.parentKind, co.elemName))
							}

							if newContent != content {
								changes = append(changes, change{
									owner: co.owner,
									desc:  strings.Join(findings, ", "),
									edit:  jrxmlutil.Edit{Start: cs, End: ce, Text: newContent},
								})
							}
						}
					}
				}
			}

			if len(ctxStack) > 0 && ctxStack[len(ctxStack)-1].name == name {
				ctxStack = ctxStack[:len(ctxStack)-1]
			}
			if len(nameStack) > 0 {
				nameStack = nameStack[:len(nameStack)-1]
			}
		}
	}

	return changes, nil
}

func newlineIndex(data []byte) []int {
	idx := []int{0}
	for i, b := range data {
		if b == '\n' {
			idx = append(idx, i+1)
		}
	}
	return idx
}

func lineOf(idx []int, off int) int {
	lo, hi := 0, len(idx)-1
	for lo < hi {
		mid := (lo + hi + 1) / 2
		if idx[mid] <= off {
			lo = mid
		} else {
			hi = mid - 1
		}
	}
	return lo + 1
}

// ---------------------------------------------------------------------------
// Content transformations
// ---------------------------------------------------------------------------

// transformTextContent applies all three text checks to a block of rendered
// text (staticText bodies and text-element string literals).
func transformTextContent(content string) (string, []string) {
	return transformContent(content, true)
}

// transformLiteral applies only the checks that are safe for any string
// literal — unrenderable replacement and double-space collapse — but not the
// period fix, which would corrupt JSON paths and code (foo.Bar -> foo. Bar).
func transformLiteral(content string) (string, []string) {
	return transformContent(content, false)
}

// isTextContext reports whether an expression element's string literals are
// rendered text and should get the period fix: expressions inside text fields
// and static text, plus parameter default values and variable initial values.
func isTextContext(parentKind, elemName string) bool {
	if textElementKinds[parentKind] {
		return true
	}
	return elemName == "defaultValueExpression" || elemName == "initialValueExpression"
}

// transformContent applies the checks in order and returns the fixed text plus
// a human-readable finding per change. Unrenderable characters are replaced
// first because a non-breaking space -> " " replacement can introduce a double
// space that the final collapse pass then removes.
func transformContent(content string, withPeriod bool) (string, []string) {
	var findings []string

	if newS, f := replaceUnrenderable(content); len(f) > 0 {
		content = newS
		findings = append(findings, f...)
	}

	if withPeriod {
		if n := len(periodRe.FindAllString(content, -1)); n > 0 {
			findings = append(findings, fmt.Sprintf("add space after '.' (%s)", occurrences(n)))
			content = periodRe.ReplaceAllString(content, ". $1")
		}
	}

	if n := len(doubleSpaceRe.FindAllString(content, -1)); n > 0 {
		findings = append(findings, fmt.Sprintf("remove double space (%s)", occurrences(n)))
		content = doubleSpaceRe.ReplaceAllString(content, " ")
	}

	return content, findings
}

// transformExpression applies the text checks only to double-quoted string
// literals inside an expression, leaving code (field references, JSON paths,
// operators) untouched. When isText is true the period fix is also applied;
// otherwise only the spacing/character checks run.
func transformExpression(content string, isText bool) (string, []string) {
	var findings []string
	var b strings.Builder
	i, n := 0, len(content)
	for i < n {
		if content[i] != '"' {
			b.WriteByte(content[i])
			i++
			continue
		}
		j := i + 1
		for j < n {
			if content[j] == '\\' {
				j += 2
				continue
			}
			if content[j] == '"' {
				break
			}
			j++
		}
		if j >= n {
			// unterminated literal — leave the rest untouched
			b.WriteString(content[i:])
			break
		}
		lit := content[i+1 : j]
		var newLit string
		var f []string
		if isText {
			newLit, f = transformTextContent(lit)
		} else {
			newLit, f = transformLiteral(lit)
		}
		if len(f) > 0 {
			findings = append(findings, f...)
		}
		b.WriteByte('"')
		b.WriteString(newLit)
		b.WriteByte('"')
		i = j + 1
	}
	return b.String(), findings
}

// replaceUnrenderable maps every unrenderable rune to its ASCII replacement,
// returning the new string and one finding per distinct rune encountered.
func replaceUnrenderable(s string) (string, []string) {
	needs := false
	for _, r := range s {
		if _, ok := unrenderableMap[r]; ok {
			needs = true
			break
		}
	}
	if !needs {
		return s, nil
	}

	var b strings.Builder
	seen := map[rune]bool{}
	var findings []string
	for _, r := range s {
		if c, ok := unrenderableMap[r]; ok {
			if !seen[r] {
				seen[r] = true
				findings = append(findings, fmt.Sprintf("replace %s (U+%04X) with %q", c.name, r, c.repl))
			}
			b.WriteString(c.repl)
		} else {
			b.WriteRune(r)
		}
	}
	return b.String(), findings
}

func occurrences(n int) string {
	if n == 1 {
		return "1 occurrence"
	}
	return fmt.Sprintf("%d occurrences", n)
}
