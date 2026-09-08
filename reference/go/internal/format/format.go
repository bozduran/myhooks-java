// Package format implements the "format" step of the myhooks pre-commit hook.
//
// It lists every fix it wants to make (grouped per element, with line
// numbers) and asks for each: Yes / No / All / Skip file. Approved fixes are
// written to the working file but left UNSTAGED, and Run returns 1 so the
// commit stops for review.
//
// Rules:
//  1. Inside double-quoted strings of rendered-text contexts (text elements,
//     parameter default values and variable initial values), a "." directly
//     followed by an uppercase letter gets a space ("today.Swill" -> "today. Swill").
//  2. Newlines in text are normalized to the element's markup:
//     none -> "\n", styled -> "<br/>", html -> "<br>".
//  3. textField gets textAdjust="StretchHeight" when missing/empty.
//  4. Every <element> gets positionType="Float" when missing/empty.
//  5. Light Java expression formatting: fun(a,b) -> fun(a, b),
//     a?b:c -> a ? b : c, a==b / a!=b -> a == b / a != b,
//     a>=b / a<=b -> a >= b / a <= b, a&&b / a||b -> a && b / a || b,
//     (String)IF(x) -> (String) IF(x).
//  6. The <jasperReport name="..."> must match the file name; when it does
//     not, the name is replaced with the file's base name.
package format

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"myhooks/internal/jrxmlutil"
)

const (
	positionTypeDefault = "Float"
	textAdjustDefault   = "StretchHeight"
)

var (
	periodRe       = regexp.MustCompile(`\.([A-Z])`)
	newlineRe      = regexp.MustCompile(`<br/>|<br>|\\n`)
	commaRe        = regexp.MustCompile(`,(\S)`)
	ternaryQRe     = regexp.MustCompile(`\s*\?\s*`)
	ternaryColonRe = regexp.MustCompile(`\s*:\s*`)
	eqRe           = regexp.MustCompile(`\s*==\s*`)
	neRe           = regexp.MustCompile(`\s*!=\s*`)
	leRe           = regexp.MustCompile(`\s*<=\s*`)
	geRe           = regexp.MustCompile(`\s*>=\s*`)
	andRe          = regexp.MustCompile(`\s*&&\s*`)
	orRe           = regexp.MustCompile(`\s*\|\|\s*`)
	castRe         = regexp.MustCompile(`(\([A-Z][A-Za-z0-9_$.]*\))([^\s)])`)
	placeholderRe  = regexp.MustCompile("\x00S([0-9]+)\x00")
)

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

var textElementKinds = map[string]bool{
	"textField":  true,
	"staticText": true,
}

// textAdjustKinds are the <element kind="..."> values that should carry
// textAdjust="StretchHeight". staticText is deliberately excluded: its content
// is fixed, so stretching it adds no value.
var textAdjustKinds = map[string]bool{
	"textField": true,
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

type elementFix struct {
	kind              string
	markup            string
	startOffset       int
	startTagEnd       int
	hasPositionType   bool
	positionTypeEmpty bool
	hasTextAdjust     bool
	textAdjustEmpty   bool
}

type ctx struct {
	name   string // element name
	owner  string // owning fix-item label
	kind   string // for <element>
	markup string
}

type contentOpen struct {
	elemName    string
	owner       string
	parentKind  string
	markup      string
	startTagEnd int
}

type fileResult struct {
	modified bool
	found    bool
	skipped  bool
}

// Run implements the format step (interactive). It returns the process exit
// code: 0 when the commit can continue, 1 when changes were left unstaged (or
// an error).
func Run(args []string) int {
	return run(args, false)
}

// Check lists what the format step would change without prompting or applying
// anything. It is informational and always returns 0.
func Check(args []string) int {
	return run(args, true)
}

func run(args []string, checkOnly bool) int {
	if len(args) > 0 && (args[0] == "-h" || args[0] == "--help") {
		fmt.Println("myhooks format: interactively reformat staged JasperReports .jrxml files.")
		fmt.Println("Usage: myhooks format [file.jrxml ...]")
		return 0
	}

	files := jrxmlutil.StagedFiles(args)
	if len(files) == 0 {
		fmt.Println("myhooks format: no staged .jrxml files to check.")
		return 0
	}

	stopCommit := false
	for _, path := range files {
		res, err := processFile(path, checkOnly)
		if err != nil {
			fmt.Fprintf(os.Stderr, "myhooks format: %s: %v\n", path, err)
			if !checkOnly {
				stopCommit = true
			}
			continue
		}
		if checkOnly {
			if res.found {
				fmt.Printf("  [report] %s: would reformat (not applied)\n", path)
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
			fmt.Printf("  [stop] %s: fixes applied and left UNSTAGED for review.\n", path)
			fmt.Printf("         Review the diff, then 'git add %s' and commit again.\n", path)
		}
	}

	if stopCommit {
		fmt.Fprintln(os.Stderr, "\nmyhooks format: commit stopped — review the .jrxml changes above and re-run.")
		return 1
	}
	return 0
}

func processFile(path string, checkOnly bool) (fileResult, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return fileResult{}, err
	}

	changes, err := parse(data, expectedReportName(path))
	if err != nil {
		return fileResult{}, fmt.Errorf("parse XML: %w", err)
	}
	items := groupChanges(changes)
	if len(items) == 0 {
		return fileResult{}, nil
	}

	// List everything that needs formatting.
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

// expectedReportName returns the report name a file should carry: the file's
// base name without its extension (report/example.jrxml -> "example").
func expectedReportName(path string) string {
	base := filepath.Base(path)
	return strings.TrimSuffix(base, filepath.Ext(base))
}

func parse(data []byte, expectedName string) ([]change, error) {
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
			case "jasperReport":
				if expectedName != "" {
					cur := jrxmlutil.AttrVal(t, "name")
					if cur != expectedName {
						startTagEnd := int(dec.InputOffset())
						tag := string(data[off:startTagEnd])
						prefix := `name="`
						owner := fmt.Sprintf("jasperReport (line %d)", lineOf(lines, off))
						desc := fmt.Sprintf("set name=%q", expectedName)
						if i := strings.Index(tag, prefix); i >= 0 {
							if closeQ := strings.Index(tag[i+len(prefix):], `"`); closeQ >= 0 {
								valStart := off + i + len(prefix)
								valEnd := valStart + closeQ
								changes = append(changes, change{
									owner: owner,
									desc:  desc,
									edit:  jrxmlutil.Edit{Start: valStart, End: valEnd, Text: jrxmlutil.EscapeAttr(expectedName)},
								})
							}
						} else {
							pos := off + len("<jasperReport")
							changes = append(changes, change{
								owner: owner,
								desc:  desc,
								edit:  jrxmlutil.Edit{Start: pos, End: pos, Text: ` name="` + jrxmlutil.EscapeAttr(expectedName) + `"`},
							})
						}
					}
				}

			case "element":
				kind := jrxmlutil.AttrVal(t, "kind")
				e := &elementFix{
					kind:        kind,
					markup:      jrxmlutil.AttrVal(t, "markup"),
					startOffset: off,
					startTagEnd: int(dec.InputOffset()),
				}
				if v, ok := jrxmlutil.AttrPresent(t, "positionType"); ok {
					e.hasPositionType = true
					e.positionTypeEmpty = v == ""
				}
				if v, ok := jrxmlutil.AttrPresent(t, "textAdjust"); ok {
					e.hasTextAdjust = true
					e.textAdjustEmpty = v == ""
				}

				owner := fmt.Sprintf("%s (line %d)", kind, lineOf(lines, off))
				ctxStack = append(ctxStack, ctx{name: "element", owner: owner, kind: kind, markup: e.markup})

				tag := string(data[off:e.startTagEnd])
				if newTag := fixElementStartTag(tag, e); newTag != "" {
					changes = append(changes, change{
						owner: owner,
						desc:  strings.Join(attributeDescriptions(e), ", "),
						edit:  jrxmlutil.Edit{Start: off, End: e.startTagEnd, Text: newTag},
					})
				}

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
						co.markup = ctxStack[len(ctxStack)-1].markup
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
						ce := cs
						if end := strings.Index(string(data[cs:off]), "]]>"); end >= 0 {
							ce = cs + end
							content := string(data[cs:ce])
							newContent := content
							if co.elemName == "text" {
								newContent = transformRawText(content, co.markup)
							} else {
								newContent = transformExpression(content, isTextContext(co.parentKind, co.elemName), co.markup)
							}
							if newContent != content {
								desc := "format expression"
								if co.elemName == "text" {
									desc = "format text"
								}
								changes = append(changes, change{
									owner: co.owner,
									desc:  desc,
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

func attributeDescriptions(e *elementFix) []string {
	var descs []string
	switch {
	case !e.hasPositionType:
		descs = append(descs, `add positionType="Float"`)
	case e.positionTypeEmpty:
		descs = append(descs, `set positionType="Float"`)
	}
	if textAdjustKinds[e.kind] {
		switch {
		case !e.hasTextAdjust:
			descs = append(descs, `add textAdjust="StretchHeight"`)
		case e.textAdjustEmpty:
			descs = append(descs, `set textAdjust="StretchHeight"`)
		}
	}
	return descs
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
// Attribute fixes
// ---------------------------------------------------------------------------

func fixElementStartTag(tag string, e *elementFix) string {
	orig := tag

	if !e.hasPositionType {
		after := "uuid"
		if !strings.Contains(tag, `uuid="`) {
			after = "kind"
		}
		tag = insertAfterAttr(tag, after, `positionType="`+positionTypeDefault+`"`)
	} else if e.positionTypeEmpty {
		tag = strings.Replace(tag, `positionType=""`, `positionType="`+positionTypeDefault+`"`, 1)
	}

	if textAdjustKinds[e.kind] {
		if !e.hasTextAdjust {
			tag = insertBeforeClose(tag, `textAdjust="`+textAdjustDefault+`"`)
		} else if e.textAdjustEmpty {
			tag = strings.Replace(tag, `textAdjust=""`, `textAdjust="`+textAdjustDefault+`"`, 1)
		}
	}

	if tag == orig {
		return ""
	}
	return tag
}

func insertAfterAttr(tag, attrName, insert string) string {
	prefix := attrName + `="`
	i := strings.Index(tag, prefix)
	if i < 0 {
		return tag
	}
	j := strings.Index(tag[i+len(prefix):], `"`)
	if j < 0 {
		return tag
	}
	pos := i + len(prefix) + j + 1
	return tag[:pos] + " " + insert + tag[pos:]
}

func insertBeforeClose(tag, insert string) string {
	i := strings.LastIndex(tag, ">")
	if i < 0 {
		return tag
	}
	if i > 0 && tag[i-1] == '/' {
		i--
	}
	return tag[:i] + " " + insert + tag[i:]
}

// ---------------------------------------------------------------------------
// Content transformations
// ---------------------------------------------------------------------------

// isTextContext reports whether an expression element's string literals are
// rendered text and should get the period fix: expressions inside text fields
// and static text, plus parameter default values and variable initial values.
func isTextContext(parentKind, elemName string) bool {
	if textElementKinds[parentKind] {
		return true
	}
	return elemName == "defaultValueExpression" || elemName == "initialValueExpression"
}

func transformRawText(s, markup string) string {
	s = periodRe.ReplaceAllString(s, ". $1")
	s = applyMarkup(s, markup)
	return s
}

func transformExpression(s string, isText bool, markup string) string {
	var literals []string
	var b strings.Builder
	i, n := 0, len(s)
	for i < n {
		if s[i] != '"' {
			b.WriteByte(s[i])
			i++
			continue
		}
		j := i + 1
		for j < n {
			if s[j] == '\\' {
				j += 2
				continue
			}
			if s[j] == '"' {
				break
			}
			j++
		}
		if j >= n {
			b.WriteString(s[i:])
			break
		}
		content := s[i+1 : j]
		if isText {
			content = periodRe.ReplaceAllString(content, ". $1")
			content = applyMarkup(content, markup)
		}
		literals = append(literals, content)
		fmt.Fprintf(&b, "\x00S%d\x00", len(literals)-1)
		i = j + 1
	}

	code := b.String()
	code = castRe.ReplaceAllString(code, "${1} ${2}")
	code = commaRe.ReplaceAllString(code, ", $1")
	code = ternaryQRe.ReplaceAllString(code, " ? ")
	code = ternaryColonRe.ReplaceAllString(code, " : ")
	code = eqRe.ReplaceAllString(code, " == ")
	code = neRe.ReplaceAllString(code, " != ")
	code = leRe.ReplaceAllString(code, " <= ")
	code = geRe.ReplaceAllString(code, " >= ")
	code = andRe.ReplaceAllString(code, " && ")
	code = orRe.ReplaceAllString(code, " || ")

	return placeholderRe.ReplaceAllStringFunc(code, func(m string) string {
		sub := placeholderRe.FindStringSubmatch(m)
		idx := 0
		fmt.Sscanf(sub[1], "%d", &idx)
		if idx < 0 || idx >= len(literals) {
			return m
		}
		return `"` + literals[idx] + `"`
	})
}

func applyMarkup(s, markup string) string {
	token := `\n`
	switch markup {
	case "styled":
		token = "<br/>"
	case "html":
		token = "<br>"
	case "", "none":
		token = `\n`
	default:
		return s
	}
	return newlineRe.ReplaceAllString(s, token)
}
