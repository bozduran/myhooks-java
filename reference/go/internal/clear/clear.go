// Package clear implements the "clear" step of the myhooks pre-commit hook:
//
//  1. Checks the report <query>. When its language is "sql", it suggests
//     migrating to jsonql, asks for the jsonql expression (the JSON path) to
//     replace the SQL, and on approval rewrites language="jsonql" and the
//     query body.
//
//  2. Scans for unused <parameter>, <field> and <variable> declarations. A
//     declaration is "unused" when its name is never referenced through
//     $P{name}, $P!{name}, $F{name} or $V{name} anywhere in the file — the
//     whole raw text is searched, so values used only inside subreport
//     parameter expressions still count as used. Unused declarations are
//     offered for deletion interactively (yes / no / all / skip).
//
//  3. Migrates the legacy net.sf.jasperreports.json.field.expression property
//     to net.sf.jasperreports.jsonql.field.expression — renaming the property
//     in place (or removing it when the jsonql property already exists) — and
//     adds the jsonql property to every <field> that has a <description> but
//     no such property, using the description (the JSON path) as the value.
//
// Deletions and query migrations are left unstaged and Run returns 1 so the
// commit stops for review; jsonql-only fixes are staged automatically.
package clear

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"

	"myhooks/internal/jrxmlutil"
)

const (
	jsonqlFieldProperty = "net.sf.jasperreports.jsonql.field.expression"
	jsonFieldProperty   = "net.sf.jasperreports.json.field.expression"
)

type declKind string

const (
	kindParameter declKind = "parameter"
	kindField     declKind = "field"
	kindVariable  declKind = "variable"
)

// decl is one top-level report declaration (parameter / field / variable).
type decl struct {
	kind         declKind
	name         string
	startOffset  int // '<' of the start tag
	endTagOffset int // '<' of the end tag
	endOffset    int // just after '>' of the end tag

	deleted bool

	// field-only metadata
	hasDescription    bool
	descriptionText   string
	descriptionIndent string
	properties        map[string]string // static <property name= value=>
	propertyExprNames map[string]bool   // dynamic <propertyExpression name=>

	// description <-> jsonql sync: byte span of the description text (inside
	// the CDATA wrapper when present), and whether the user approved changing
	// it to match the jsonql path.
	descriptionTextStart int
	descriptionTextEnd   int
	updateDescription    bool

	// legacy json migration: byte span of a field's legacy <property> element
	// (start of '<property' .. just after its closing '/>'); -1 when absent.
	legacyJSONStart int
	legacyJSONEnd   int
}

func newDecl(kind declKind, name string, start int) *decl {
	return &decl{
		kind:                 kind,
		name:                 name,
		startOffset:          start,
		properties:           map[string]string{},
		propertyExprNames:    map[string]bool{},
		descriptionTextStart: -1,
		descriptionTextEnd:   -1,
		legacyJSONStart:      -1,
		legacyJSONEnd:        -1,
	}
}

// builtinParameters are JasperReports-provided parameters that must never be
// flagged as unused, even if they appear as explicit declarations.
var builtinParameters = map[string]bool{
	"REPORT_PARAMETERS_MAP":        true,
	"REPORT_DATA_SOURCE":           true,
	"REPORT_CONNECTION":            true,
	"JASPER_REPORT":                true,
	"JASPER_REPORT_PARAMETERS_MAP": true,
	"REPORT_CONTEXT":               true,
	"REPORT_CLASS_LOADER":          true,
	"REPORT_VIRTUALIZER":           true,
	"REPORT_FORMAT_FACTORY":        true,
	"REPORT_MAX_COUNT":             true,
	"REPORT_SCRIPTLET":             true,
	"REPORT_LOCALE":                true,
	"REPORT_RESOURCE_BUNDLE":       true,
	"REPORT_TIME_ZONE":             true,
	"REPORT_TEMPLATES":             true,
	"IS_IGNORE_PAGINATION":         true,
	"SORT_FIELDS":                  true,
	"FILTER":                       true,
	"REPORT_FILE_RESOLVER":         true,
	"REPORT_URL_HANDLER_FACTORY":   true,
	"JSON_INPUT_STREAM":            true,
	"JSON_SOURCE":                  true,
	"JSON_LOCALE":                  true,
	"JSON_TIME_ZONE":               true,
	"JSON_DATE_PATTERN":            true,
	"JSON_NUMBER_PATTERN":          true,
}

// Run implements the clear step (interactive). It returns the process exit
// code: 0 when the commit can continue, 1 when changes were left unstaged (or
// an error).
func Run(args []string) int {
	return run(args, false)
}

// Check lists what the clear step would change without prompting or applying
// anything. It is informational and always returns 0.
func Check(args []string) int {
	return run(args, true)
}

func run(args []string, checkOnly bool) int {
	if len(args) > 0 && (args[0] == "-h" || args[0] == "--help") {
		fmt.Println("myhooks clear: check the query (SQL -> jsonql), remove unused declarations and add missing jsonql field expressions.")
		fmt.Println("Usage: myhooks clear [file.jrxml ...]")
		return 0
	}

	files := jrxmlutil.StagedFiles(args)
	if len(files) == 0 {
		fmt.Println("myhooks clear: no staged .jrxml files to check.")
		return 0
	}

	stopCommit := false
	for _, path := range files {
		res, err := processFile(path, checkOnly)
		if err != nil {
			fmt.Fprintf(os.Stderr, "myhooks clear: %s: %v\n", path, err)
			if !checkOnly {
				stopCommit = true
			}
			continue
		}

		if checkOnly {
			if res.found {
				fmt.Printf("  [report] %s: would remove unused declarations / fix jsonql (not applied)\n", path)
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
		case res.deletedAny || res.descriptionAny || res.queryChanged:
			stopCommit = true
			fmt.Printf("  [stop] %s: changes applied and left UNSTAGED for review.\n", path)
			fmt.Printf("         Review the diff, then 'git add %s' and commit again.\n", path)
		default:
			// jsonql-only fix: stage it so the fix is part of the commit.
			if err := gitAdd(path); err != nil {
				fmt.Fprintf(os.Stderr, "  [error] failed to stage %s: %v\n", path, err)
				stopCommit = true
			} else {
				fmt.Printf("  [fix]  %s: jsonql property added and staged\n", path)
			}
		}
	}

	if stopCommit {
		fmt.Fprintln(os.Stderr, "\nmyhooks clear: commit stopped — review the .jrxml changes above and re-run.")
		return 1
	}
	return 0
}

type fileResult struct {
	modified       bool
	deletedAny     bool
	descriptionAny bool
	queryChanged   bool
	found          bool
	skipped        bool
}

func processFile(path string, checkOnly bool) (fileResult, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return fileResult{}, err
	}
	raw := string(data)

	decls, err := parseReport(data)
	if err != nil {
		return fileResult{}, fmt.Errorf("parse XML: %w", err)
	}
	q, err := parseQuery(data)
	if err != nil {
		return fileResult{}, fmt.Errorf("parse XML: %w", err)
	}

	fmt.Printf("checking %s\n", path)

	// 0) first check: a SQL query is suggested for migration to jsonql.
	sqlQuery := q != nil && strings.EqualFold(q.language, "sql")
	queryMigration := "" // the jsonql expression the user supplied; "" = none
	if sqlQuery {
		fmt.Printf("  [sql] query uses language=\"sql\"; jsonql is preferred\n")
		fmt.Print(jrxmlutil.Diff(raw[q.startOffset:q.endOffset], "", "          "))
		if !checkOnly {
			switch jrxmlutil.Prompt("Migrate this query to jsonql?") {
			case jrxmlutil.Yes, jrxmlutil.All:
				if expr := jrxmlutil.PromptText("jsonql expression to replace the SQL query:"); expr != "" {
					queryMigration = expr
				}
			case jrxmlutil.Skip:
				return fileResult{skipped: true}, nil
			case jrxmlutil.No:
				// keep the SQL query as-is
			}
		}
	}

	// 1) find unused declarations.
	var unused []*decl
	for _, d := range decls {
		if d.kind == kindParameter && builtinParameters[d.name] {
			continue
		}
		if !isUsed(d, raw) {
			unused = append(unused, d)
		}
	}
	for _, d := range unused {
		fmt.Printf("  [unused] %s '%s' is never referenced\n", d.kind, d.name)
		fmt.Print(jrxmlutil.Diff(raw[d.startOffset:d.endOffset], "", "          "))
	}

	// 1b) find fields whose description differs from their jsonql path.
	type mismatch struct {
		d      *decl
		jsonql string
		desc   string
	}
	var mismatches []mismatch
	for _, d := range decls {
		if j, dd, ok := descriptionMismatch(d); ok {
			mismatches = append(mismatches, mismatch{d: d, jsonql: j, desc: dd})
		}
	}
	for _, m := range mismatches {
		fmt.Printf("  [desc] field '%s': description %q differs from jsonql %q\n", m.d.name, m.desc, m.jsonql)
		fmt.Print(jrxmlutil.Diff(m.desc, m.jsonql, "          "))
	}

	if checkOnly {
		found := sqlQuery || len(unused) > 0 || len(mismatches) > 0
		if printJSONQLFindings(raw, decls, false) > 0 {
			found = true
		}
		return fileResult{found: found}, nil
	}

	// 2) interactively decide which unused declarations to delete.
	skipFile := false
	allSelected := false
	for _, d := range unused {
		if skipFile {
			break
		}
		if allSelected {
			d.deleted = true
			continue
		}
		switch jrxmlutil.Prompt(fmt.Sprintf("Delete unused %s '%s'?", d.kind, d.name)) {
		case jrxmlutil.Yes:
			d.deleted = true
		case jrxmlutil.All:
			d.deleted = true
			allSelected = true
		case jrxmlutil.Skip:
			skipFile = true
		case jrxmlutil.No:
			// keep it
		}
	}

	if skipFile {
		return fileResult{skipped: true}, nil
	}

	// 2b) interactively decide which descriptions to sync to their jsonql path.
	allDesc := false
	for _, m := range mismatches {
		if skipFile {
			break
		}
		if m.d.deleted {
			continue
		}
		if allDesc {
			m.d.updateDescription = true
			continue
		}
		question := fmt.Sprintf("Change description for field '%s' from %q to %q?", m.d.name, m.desc, m.jsonql)
		switch jrxmlutil.Prompt(question) {
		case jrxmlutil.Yes:
			m.d.updateDescription = true
		case jrxmlutil.All:
			m.d.updateDescription = true
			allDesc = true
		case jrxmlutil.Skip:
			skipFile = true
		case jrxmlutil.No:
			// keep the description as-is
		}
	}

	if skipFile {
		return fileResult{skipped: true}, nil
	}

	// 3) build the edits: deletions first, then jsonql additions on survivors,
	// then the query migration.
	edits := buildEdits(raw, decls)
	for _, d := range decls {
		if d.deleted {
			fmt.Printf("  - removing unused %s '%s'\n", d.kind, d.name)
		}
	}
	printJSONQLFindings(raw, decls, true)
	for _, m := range mismatches {
		if m.d.updateDescription {
			fmt.Printf("  ~ changing description for field '%s' to %q\n", m.d.name, m.jsonql)
		}
	}
	if queryMigration != "" {
		fmt.Printf("  ~ migrating query from sql to jsonql\n")
		fmt.Print(jrxmlutil.Diff(raw[q.startOffset:q.endOffset], migratedQueryText(raw, q, queryMigration), "          "))
		edits = append(edits, queryMigrationEdits(q, queryMigration)...)
	}

	if len(edits) == 0 {
		return fileResult{}, nil
	}

	newRaw := jrxmlutil.ApplyEdits(raw, edits)
	if newRaw == raw {
		return fileResult{}, nil
	}

	info, err := os.Stat(path)
	if err != nil {
		return fileResult{}, err
	}
	if err := os.WriteFile(path, []byte(newRaw), info.Mode().Perm()); err != nil {
		return fileResult{}, err
	}

	deletedAny := false
	descriptionAny := false
	for _, d := range decls {
		if d.deleted {
			deletedAny = true
		}
		if d.updateDescription {
			descriptionAny = true
		}
	}
	return fileResult{modified: true, deletedAny: deletedAny, descriptionAny: descriptionAny, queryChanged: queryMigration != ""}, nil
}

// printJSONQLFindings prints the jsonql migration/addition findings for fields
// and returns how many it printed. When onlySurvivors is true, deleted fields
// are skipped.
func printJSONQLFindings(raw string, decls []*decl, onlySurvivors bool) int {
	count := 0
	for _, d := range decls {
		if d.kind != kindField {
			continue
		}
		if onlySurvivors && d.deleted {
			continue
		}
		switch {
		case d.legacyJSONStart >= 0:
			count++
			legacyTag := raw[d.legacyJSONStart:d.legacyJSONEnd]
			if hasJSONQL(d) {
				fmt.Printf("  - removing legacy %s from field '%s'\n", jsonFieldProperty, d.name)
				fmt.Print(jrxmlutil.Diff(legacyTag, "", "          "))
			} else {
				fmt.Printf("  + renaming %s to %s on field '%s'\n", jsonFieldProperty, jsonqlFieldProperty, d.name)
				renamed := strings.Replace(legacyTag, jsonFieldProperty, jsonqlFieldProperty, 1)
				fmt.Print(jrxmlutil.Diff(legacyTag, renamed, "          "))
			}
		case !hasJSONQL(d) && d.hasDescription:
			if v := jsonqlValue(d); v != "" {
				count++
				fmt.Printf("  + adding %s = %q to field '%s'\n", jsonqlFieldProperty, v, d.name)
				fmt.Print(jrxmlutil.Diff("", jsonqlPropertyLine(d, v), "          "))
			}
		}
	}
	return count
}

// buildEdits returns the edits to apply for a report: deletions of the unused
// declarations marked deleted, then jsonql migrations/additions for surviving
// fields. A surviving field's legacy json.field.expression property is renamed
// in place to the jsonql property (or removed when the jsonql property already
// exists); a field with a <description> but no property gets the jsonql
// property added from the description.
func buildEdits(raw string, decls []*decl) []jrxmlutil.Edit {
	var edits []jrxmlutil.Edit
	for _, d := range decls {
		if d.deleted {
			edits = append(edits, deletionEdit(raw, d))
		}
	}
	for _, d := range decls {
		if d.kind != kindField || d.deleted {
			continue
		}
		switch {
		case d.legacyJSONStart >= 0:
			if hasJSONQL(d) {
				edits = append(edits, legacyJSONDeleteEdit(raw, d))
			} else {
				edits = append(edits, legacyJSONRenameEdit(raw, d))
			}
		case !hasJSONQL(d) && d.hasDescription:
			if value := jsonqlValue(d); value != "" {
				edits = append(edits, insertionEdit(raw, d, value))
			}
		}
	}
	for _, d := range decls {
		if d.updateDescription {
			edits = append(edits, descriptionEdit(d, jsonqlPropertyValue(d)))
		}
	}
	return edits
}

// jsonqlValue returns the jsonql expression value for a field that has a
// <description>: the description text, which by this project's convention is
// the JSON path itself. Legacy json.field.expression values are migrated
// separately by renaming the property, so they are not consulted here.
func jsonqlValue(d *decl) string {
	return strings.TrimSpace(d.descriptionText)
}

func hasJSONQL(d *decl) bool {
	if _, ok := d.properties[jsonqlFieldProperty]; ok {
		return true
	}
	return d.propertyExprNames[jsonqlFieldProperty]
}

// jsonqlPropertyValue returns the static jsonql property value for a field.
func jsonqlPropertyValue(d *decl) string {
	return strings.TrimSpace(d.properties[jsonqlFieldProperty])
}

// descriptionMismatch reports whether a field's <description> differs from its
// jsonql property value (the jsonql path is the source of truth). It returns
// the jsonql path and the current description when they differ and both are
// non-empty.
func descriptionMismatch(d *decl) (jsonql, desc string, ok bool) {
	if d.kind != kindField || !d.hasDescription {
		return "", "", false
	}
	jsonql = jsonqlPropertyValue(d)
	if jsonql == "" {
		return "", "", false
	}
	desc = strings.TrimSpace(d.descriptionText)
	if desc == jsonql {
		return "", "", false
	}
	return jsonql, desc, true
}

// isUsed reports whether the declaration's name is referenced in the raw file
// text. The whole file is searched, so references inside subreport parameter
// expressions, queries, variables, etc. all count as usage.
func isUsed(d *decl, raw string) bool {
	switch d.kind {
	case kindField:
		return strings.Contains(raw, "$F{"+d.name+"}")
	case kindVariable:
		return strings.Contains(raw, "$V{"+d.name+"}")
	case kindParameter:
		return strings.Contains(raw, "$P{"+d.name+"}") || strings.Contains(raw, "$P!{"+d.name+"}")
	}
	return true
}

// ---------------------------------------------------------------------------
// XML parsing
// ---------------------------------------------------------------------------

// query is the report's single <query> element: its language attribute value
// and the byte spans needed to rewrite it into jsonql.
type query struct {
	language       string
	langValueStart int // inside the quotes of language="..."
	langValueEnd   int
	bodyStart      int // the query text (inside the CDATA wrapper when present)
	bodyEnd        int
	startOffset    int // '<' of <query>
	endOffset      int // just after '>' of </query>
}

// parseQuery locates the report's <query> element and its rewrite offsets, or
// returns nil when there is no query. The language value span is found inside
// the start tag (double-quoted, matching the rest of the file); the body span
// is the CDATA content when present, otherwise the trimmed inner text.
func parseQuery(data []byte) (*query, error) {
	dec := xml.NewDecoder(bytes.NewReader(data))
	for {
		off := int(dec.InputOffset())
		tok, err := dec.Token()
		if err == io.EOF {
			return nil, nil
		}
		if err != nil {
			return nil, err
		}
		se, ok := tok.(xml.StartElement)
		if !ok || se.Name.Local != "query" {
			continue
		}

		q := &query{
			language:       jrxmlutil.AttrVal(se, "language"),
			startOffset:    off,
			langValueStart: -1,
			langValueEnd:   -1,
			bodyStart:      -1,
			bodyEnd:        -1,
		}
		startTagEnd := int(dec.InputOffset())
		if i := strings.Index(string(data[off:startTagEnd]), `language="`); i >= 0 {
			vs := off + i + len(`language="`)
			if j := strings.Index(string(data[vs:startTagEnd]), `"`); j >= 0 {
				q.langValueStart = vs
				q.langValueEnd = vs + j
			}
		}

		// a self-closing <query/> has no body and emits no end element.
		if strings.HasSuffix(strings.TrimSpace(string(data[off:startTagEnd])), "/>") {
			q.endOffset = startTagEnd
			return q, nil
		}

		// consume tokens up to the matching </query>; its start offset bounds
		// the body text.
		for {
			endOff := int(dec.InputOffset())
			bt, err := dec.Token()
			if err != nil {
				return nil, err
			}
			if ee, ok := bt.(xml.EndElement); ok && ee.Name.Local == "query" {
				q.endOffset = int(dec.InputOffset())
				body := string(data[startTagEnd:endOff])
				if i := strings.Index(body, "<![CDATA["); i >= 0 {
					cs := i + len("<![CDATA[")
					if j := strings.Index(body[cs:], "]]>"); j >= 0 {
						q.bodyStart = startTagEnd + cs
						q.bodyEnd = startTagEnd + cs + j
					}
				}
				if q.bodyStart < 0 {
					if trimmed := strings.TrimSpace(body); trimmed != "" {
						bi := strings.Index(body, trimmed)
						q.bodyStart = startTagEnd + bi
						q.bodyEnd = q.bodyStart + len(trimmed)
					}
				}
				return q, nil
			}
		}
	}
}

// queryMigrationEdits returns the edits that rewrite a SQL query into jsonql:
// language="sql" -> language="jsonql" and the body -> expr. A missing body or
// language-value span is skipped (defensive; a SQL query normally has both).
func queryMigrationEdits(q *query, expr string) []jrxmlutil.Edit {
	var edits []jrxmlutil.Edit
	if q.langValueStart >= 0 && q.langValueEnd > q.langValueStart {
		edits = append(edits, jrxmlutil.Edit{Start: q.langValueStart, End: q.langValueEnd, Text: "jsonql"})
	}
	if q.bodyStart >= 0 && q.bodyEnd > q.bodyStart {
		edits = append(edits, jrxmlutil.Edit{Start: q.bodyStart, End: q.bodyEnd, Text: expr})
	}
	return edits
}

// migratedQueryText returns the query element as it will read after migrating
// to jsonql, for display next to the current query.
func migratedQueryText(raw string, q *query, expr string) string {
	edits := queryMigrationEdits(q, expr)
	for i := range edits {
		edits[i].Start -= q.startOffset
		edits[i].End -= q.startOffset
	}
	return jrxmlutil.ApplyEdits(raw[q.startOffset:q.endOffset], edits)
}

// parseReport walks the XML token stream and collects top-level
// <parameter>/<field>/<variable> declarations (direct children of
// <jasperReport>) together with the byte offsets needed for surgical edits.
// Nested <parameter> elements such as subreport parameter mappings are ignored.
func parseReport(data []byte) ([]*decl, error) {
	dec := xml.NewDecoder(bytes.NewReader(data))
	var stack []string
	var decls []*decl
	var current *decl
	inFieldDescription := false
	var descText strings.Builder
	var descContentStart int

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
			parent := ""
			if len(stack) > 0 {
				parent = stack[len(stack)-1]
			}
			name := t.Name.Local
			stack = append(stack, name)

			switch name {
			case "parameter", "field", "variable":
				if parent == "jasperReport" {
					current = newDecl(declKind(name), jrxmlutil.AttrVal(t, "name"), off)
					decls = append(decls, current)
				}
			case "description":
				if current != nil && current.kind == kindField && parent == "field" {
					inFieldDescription = true
					descText.Reset()
					current.hasDescription = true
					current.descriptionIndent = lineIndent(data, off)
					descContentStart = int(dec.InputOffset())
				}
			case "property", "propertyExpression":
				if current != nil && current.kind == kindField && parent == "field" {
					pname := jrxmlutil.AttrVal(t, "name")
					if name == "property" {
						current.properties[pname] = jrxmlutil.AttrVal(t, "value")
						if pname == jsonFieldProperty {
							// remember the legacy property's span so it can be
							// renamed to (or removed in favour of) the jsonql
							// property later.
							current.legacyJSONStart = off
							current.legacyJSONEnd = int(dec.InputOffset())
						}
					} else {
						current.propertyExprNames[pname] = true
					}
				}
			}

		case xml.EndElement:
			name := t.Name.Local
			if name == "description" && inFieldDescription {
				inFieldDescription = false
				if current != nil {
					current.descriptionText = descText.String()
					current.descriptionTextStart, current.descriptionTextEnd = descriptionTextSpan(data, descContentStart, off)
				}
			}
			if current != nil && string(current.kind) == name {
				current.endTagOffset = off
				current.endOffset = int(dec.InputOffset())
				current = nil
			}
			stack = stack[:len(stack)-1]

		case xml.CharData:
			if inFieldDescription {
				descText.WriteString(string(t))
			}
		}
	}

	return decls, nil
}

// lineIndent returns the leading whitespace of the line that contains byte
// offset off (which must point at the first byte of a tag).
func lineIndent(data []byte, off int) string {
	start := off
	for start > 0 && data[start-1] != '\n' {
		start--
	}
	return string(data[start:off])
}

// descriptionTextSpan returns the byte span of a description's text content:
// inside the CDATA wrapper when present, otherwise the whole inner content.
func descriptionTextSpan(data []byte, start, end int) (int, int) {
	seg := string(data[start:end])
	if i := strings.Index(seg, "<![CDATA["); i >= 0 {
		cs := start + i + len("<![CDATA[")
		if j := strings.Index(seg[i+len("<![CDATA["):], "]]>"); j >= 0 {
			return cs, cs + j
		}
	}
	return start, end
}

// ---------------------------------------------------------------------------
// Edits (surgical, preserves all formatting)
// ---------------------------------------------------------------------------

func deletionEdit(raw string, d *decl) jrxmlutil.Edit {
	start := d.startOffset
	if i := strings.LastIndex(raw[:start], "\n"); i >= 0 {
		start = i + 1
	}
	end := d.endOffset
	if end < len(raw) && raw[end] == '\n' {
		end++
	}
	return jrxmlutil.Edit{Start: start, End: end} // empty Text = deletion
}

// jsonqlPropertyLine returns the single <property> line added to a field to
// carry its jsonql expression, indented to the field's child indentation.
func jsonqlPropertyLine(d *decl, value string) string {
	return d.descriptionIndent +
		`<property name="` + jsonqlFieldProperty + `" value="` + jrxmlutil.EscapeAttr(value) + `"/>`
}

func insertionEdit(raw string, d *decl, value string) jrxmlutil.Edit {
	// Insert at the start of the line that holds </field>, so the existing
	// indentation of that closing tag stays where it is and the new property
	// line gets the field's child indentation.
	start := d.endTagOffset
	if i := strings.LastIndex(raw[:start], "\n"); i >= 0 {
		start = i + 1
	}
	return jrxmlutil.Edit{Start: start, End: start, Text: jsonqlPropertyLine(d, value) + "\n"}
}

// legacyJSONRenameEdit renames a field's legacy json property to the jsonql
// property in place, preserving its value, position and formatting.
func legacyJSONRenameEdit(raw string, d *decl) jrxmlutil.Edit {
	tag := raw[d.legacyJSONStart:d.legacyJSONEnd]
	i := strings.Index(tag, jsonFieldProperty)
	if i < 0 {
		return jrxmlutil.Edit{} // defensive no-op; should not happen
	}
	start := d.legacyJSONStart + i
	return jrxmlutil.Edit{Start: start, End: start + len(jsonFieldProperty), Text: jsonqlFieldProperty}
}

// legacyJSONDeleteEdit removes a field's leftover legacy json property (the
// jsonql property already exists), including the surrounding line.
func legacyJSONDeleteEdit(raw string, d *decl) jrxmlutil.Edit {
	start := d.legacyJSONStart
	if i := strings.LastIndex(raw[:start], "\n"); i >= 0 {
		start = i + 1
	}
	end := d.legacyJSONEnd
	if end < len(raw) && raw[end] == '\n' {
		end++
	}
	return jrxmlutil.Edit{Start: start, End: end}
}

// descriptionEdit replaces a field's description text with the given value
// (the jsonql path), preserving the surrounding <description> and CDATA markup.
func descriptionEdit(d *decl, value string) jrxmlutil.Edit {
	return jrxmlutil.Edit{Start: d.descriptionTextStart, End: d.descriptionTextEnd, Text: value}
}

// ---------------------------------------------------------------------------
// Git helpers
// ---------------------------------------------------------------------------

func gitAdd(path string) error {
	cmd := exec.Command("git", "add", "--", path)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}
