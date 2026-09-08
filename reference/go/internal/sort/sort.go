// Package sort implements the "sort" step of the myhooks pre-commit hook.
//
// JasperReports treats XML document order as both the paint (z) order and the
// Studio outline order, but Studio only updates x/y coordinates when an element
// is visually moved — it does not re-splice the element in the XML. This step
// restores the invariant "document order == reading order" by re-sorting each
// <band> and <element kind="frame"> container's direct <element> children by
// geometry (y then x, stable). Genuine overlaps are warned about (not fixed),
// since they need a human z-order decision. Reorders are left unstaged and Run
// returns 1 so the commit stops for review.
package sort

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"sort"
	"strconv"
	"strings"

	"myhooks/internal/jrxmlutil"
)

// child is one direct <element> child of a container.
type child struct {
	kind       string
	x, y, w, h int
	start, end int // byte span of the <element> ('<' .. just after closing tag)
	line       int
}

// container is a <band> or <element kind="frame"> whose children are sorted.
type container struct {
	kind                 string // "band" or "frame"
	innerStart, innerEnd int    // byte span of the container's inner content
	children             []*child
	line                 int
}

// overlap is a pair of children whose bounding boxes intersect.
type overlap struct {
	a, b *child
}

type fileResult struct {
	modified bool
	found    bool
	skipped  bool
}

// Run implements the sort step (interactive). It returns the process exit
// code: 0 when the commit can continue, 1 when reorders were left unstaged (or
// an error).
func Run(args []string) int {
	return run(args, false)
}

// Check lists what the sort step would change without prompting or applying
// anything. It is informational and always returns 0.
func Check(args []string) int {
	return run(args, true)
}

func run(args []string, checkOnly bool) int {
	if len(args) > 0 && (args[0] == "-h" || args[0] == "--help") {
		fmt.Println("myhooks sort: reorder band/frame element children by geometry (y then x).")
		fmt.Println("Usage: myhooks sort [file.jrxml ...]")
		return 0
	}

	files := jrxmlutil.StagedFiles(args)
	if len(files) == 0 {
		fmt.Println("myhooks sort: no staged .jrxml files to check.")
		return 0
	}

	stopCommit := false
	for _, path := range files {
		res, err := processFile(path, checkOnly)
		if err != nil {
			fmt.Fprintf(os.Stderr, "myhooks sort: %s: %v\n", path, err)
			if !checkOnly {
				stopCommit = true
			}
			continue
		}
		if checkOnly {
			if res.found {
				fmt.Printf("  [report] %s: would reorder elements (not applied)\n", path)
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
			fmt.Printf("  [stop] %s: elements reordered and left UNSTAGED for review.\n", path)
			fmt.Printf("         Review the diff, then 'git add %s' and commit again.\n", path)
		}
	}

	if stopCommit {
		fmt.Fprintln(os.Stderr, "\nmyhooks sort: commit stopped — review the .jrxml changes above and re-run.")
		return 1
	}
	return 0
}

func processFile(path string, checkOnly bool) (fileResult, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return fileResult{}, err
	}

	containers, err := parse(data)
	if err != nil {
		return fileResult{}, fmt.Errorf("parse XML: %w", err)
	}

	// One prompt item per container whose order would change.
	type item struct {
		label   string
		details []string
		cont    *container
	}
	var items []item
	for _, c := range containers {
		if !reorderChanged(c) {
			continue
		}
		it := item{
			label: fmt.Sprintf("%s (line %d)", c.kind, c.line),
			cont:  c,
		}
		it.details = append(it.details, orderSummary(c.children))
		for _, o := range overlaps(c.children) {
			it.details = append(it.details, fmt.Sprintf("warning: overlap %s(%d,%d) <-> %s(%d,%d)",
				o.a.kind, o.a.x, o.a.y, o.b.kind, o.b.x, o.b.y))
		}
		items = append(items, it)
	}

	if len(items) == 0 {
		return fileResult{}, nil
	}

	fmt.Printf("checking %s\n", path)
	raw := string(data)
	for i, it := range items {
		fmt.Printf("  %2d. %s\n", i+1, it.label)
		for _, d := range it.details {
			fmt.Printf("        - %s\n", d)
		}
		if len(it.cont.children) > 0 {
			fmt.Print(jrxmlutil.Diff(childrenText(raw, it.cont.children), childrenText(raw, sortedChildren(it.cont.children)), "          "))
		}
	}

	if checkOnly {
		return fileResult{found: true}, nil
	}

	skipFile := false
	allSelected := false
	var approved []*container
	for i, it := range items {
		if skipFile {
			break
		}
		if allSelected {
			approved = append(approved, it.cont)
			continue
		}
		question := fmt.Sprintf("Apply %d/%d %s?", i+1, len(items), it.label)
		switch jrxmlutil.Prompt(question) {
		case jrxmlutil.Yes:
			approved = append(approved, it.cont)
		case jrxmlutil.All:
			approved = append(approved, it.cont)
			allSelected = true
		case jrxmlutil.Skip:
			skipFile = true
		case jrxmlutil.No:
			// leave this container untouched
		}
	}

	if skipFile {
		return fileResult{skipped: true}, nil
	}
	if len(approved) == 0 {
		return fileResult{}, nil
	}

	newRaw := applyReorders(string(data), approved)
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

// parse walks the XML token stream and collects every band/frame container with
// its direct <element> children and their byte spans.
func parse(data []byte) ([]*container, error) {
	dec := xml.NewDecoder(bytes.NewReader(data))
	lines := newlineIndex(data)

	var containers []*container

	type openElem struct {
		name  string
		cont  *container
		child *child
	}
	var stack []openElem

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
			var cont *container
			var ch *child
			switch name {
			case "band":
				cont = &container{kind: "band", innerStart: int(dec.InputOffset()), line: lineOf(lines, off)}
				containers = append(containers, cont)
			case "element":
				kind := jrxmlutil.AttrVal(t, "kind")
				// Only a DIRECT child of a band/frame container is collected.
				// Nested <element>s (e.g. inside a <component> table/list) are
				// skipped so a container's children never have overlapping byte
				// spans, which the reorder logic relies on.
				if len(stack) > 0 && stack[len(stack)-1].cont != nil {
					parent := stack[len(stack)-1].cont
					ch = &child{
						kind:  kind,
						x:     intAttr(t, "x"),
						y:     intAttr(t, "y"),
						w:     intAttr(t, "width"),
						h:     intAttr(t, "height"),
						start: off,
						line:  lineOf(lines, off),
					}
					parent.children = append(parent.children, ch)
				}
				if kind == "frame" {
					cont = &container{kind: "frame", innerStart: int(dec.InputOffset()), line: lineOf(lines, off)}
					containers = append(containers, cont)
				}
			}
			stack = append(stack, openElem{name: name, cont: cont, child: ch})

		case xml.EndElement:
			if len(stack) == 0 {
				continue
			}
			top := stack[len(stack)-1]
			stack = stack[:len(stack)-1]
			if top.child != nil {
				top.child.end = int(dec.InputOffset())
			}
			if top.cont != nil {
				top.cont.innerEnd = off
			}
		}
	}

	return containers, nil
}

// reorderChanged reports whether a container's children would change order.
func reorderChanged(c *container) bool {
	if len(c.children) < 2 {
		return false
	}
	s := sortedChildren(c.children)
	for i := range s {
		if s[i] != c.children[i] {
			return true
		}
	}
	return false
}

// sortedChildren returns the children sorted by y then x (stable).
func sortedChildren(children []*child) []*child {
	s := make([]*child, len(children))
	copy(s, children)
	sort.SliceStable(s, func(i, j int) bool {
		if s[i].y != s[j].y {
			return s[i].y < s[j].y
		}
		return s[i].x < s[j].x
	})
	return s
}

// overlaps returns every pair of children whose bounding boxes intersect.
func overlaps(children []*child) []overlap {
	var out []overlap
	for i := 0; i < len(children); i++ {
		for j := i + 1; j < len(children); j++ {
			if rectsOverlap(children[i], children[j]) {
				out = append(out, overlap{a: children[i], b: children[j]})
			}
		}
	}
	return out
}

func rectsOverlap(a, b *child) bool {
	return a.x < b.x+b.w && b.x < a.x+a.w && a.y < b.y+b.h && b.y < a.y+a.h
}

// applyReorders permutes each container's children in place, deepest container
// first. Every reorder is length-preserving (it only permutes the container's
// inner byte chunks), so child/container offsets stay valid throughout.
func applyReorders(raw string, containers []*container) string {
	ordered := append([]*container(nil), containers...)
	sort.Slice(ordered, func(i, j int) bool { return ordered[i].innerStart > ordered[j].innerStart })
	for _, c := range ordered {
		raw = reorderContainer(raw, c)
	}
	return raw
}

// reorderContainer returns raw with one container's children permuted by the
// (y, x) order. Non-<element> content (whitespace, <property>, comments) stays
// in place; only the <element> substrings are moved among their slots.
func reorderContainer(raw string, c *container) string {
	orig := c.children
	s := sortedChildren(orig)
	if !reorderChanged(c) {
		return raw
	}

	var b strings.Builder
	b.WriteString(raw[c.innerStart:orig[0].start]) // glue before the first slot
	for k := 0; k < len(s); k++ {
		b.WriteString(raw[s[k].start:s[k].end]) // element in its new slot
		if k == len(s)-1 {
			b.WriteString(raw[orig[len(orig)-1].end:c.innerEnd]) // trailing glue
		} else {
			b.WriteString(raw[orig[k].end:orig[k+1].start]) // glue between slots
		}
	}
	return raw[:c.innerStart] + b.String() + raw[c.innerEnd:]
}

// childrenText returns the concatenation of the children's <element> blocks in
// the given order, one per line, for showing a reorder as a git-style diff.
func childrenText(raw string, children []*child) string {
	parts := make([]string, len(children))
	for i, c := range children {
		parts[i] = strings.TrimSuffix(raw[c.start:c.end], "\n")
	}
	return strings.Join(parts, "\n")
}

func orderSummary(children []*child) string {
	desc := func(c *child) string { return fmt.Sprintf("%s(%d,%d)", c.kind, c.x, c.y) }
	oldOrder := make([]string, len(children))
	for i, c := range children {
		oldOrder[i] = desc(c)
	}
	newOrder := make([]string, len(children))
	for i, c := range sortedChildren(children) {
		newOrder[i] = desc(c)
	}
	return fmt.Sprintf("reorder [%s] -> [%s]", strings.Join(oldOrder, ", "), strings.Join(newOrder, ", "))
}

func intAttr(e xml.StartElement, name string) int {
	n, err := strconv.Atoi(strings.TrimSpace(jrxmlutil.AttrVal(e, name)))
	if err != nil {
		return 0
	}
	return n
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
