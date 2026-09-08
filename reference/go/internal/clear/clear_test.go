package clear

import (
	"encoding/xml"
	"io"
	"os"
	"strings"
	"testing"

	"myhooks/internal/jrxmlutil"
)

// sampleReport covers: used/unused parameters, used field, a field used ONLY
// through a subreport parameter, an unused field, a field with the legacy
// json expression, and a variable.
const sampleReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<parameter name="usedParam" class="java.lang.String">
		<defaultValueExpression><![CDATA["x"]]></defaultValueExpression>
	</parameter>
	<parameter name="unusedParam" class="java.lang.String">
		<defaultValueExpression><![CDATA["y"]]></defaultValueExpression>
	</parameter>
	<query language="jsonql"><![CDATA[doc]]></query>
	<field name="usedField" class="java.lang.String">
		<description><![CDATA[used.field]]></description>
		<property name="net.sf.jasperreports.jsonql.field.expression" value="used.field"/>
	</field>
	<field name="subreportOnly" class="java.lang.String">
		<description><![CDATA[sub.only]]></description>
	</field>
	<field name="unusedField" class="java.lang.String">
		<description><![CDATA[unused.field]]></description>
	</field>
	<field name="legacyField" class="java.lang.String">
		<description><![CDATA[legacy.desc]]></description>
		<property name="net.sf.jasperreports.json.field.expression" value="legacy.value"/>
	</field>
	<variable name="counter" class="java.lang.Integer">
		<expression><![CDATA[$V{counter} + 1]]></expression>
	</variable>
	<detail>
		<band height="100">
			<element kind="textField" x="0" y="0" width="100" height="20">
				<expression><![CDATA[$F{usedField} + $P{usedParam}]]></expression>
			</element>
			<element kind="subreport" x="0" y="30" width="100" height="50">
				<parameter name="childParam">
					<expression><![CDATA[$F{subreportOnly}]]></expression>
				</parameter>
			</element>
		</band>
	</detail>
</jasperReport>`

func parseSample(t *testing.T) ([]*decl, string) {
	t.Helper()
	decls, err := parseReport([]byte(sampleReport))
	if err != nil {
		t.Fatalf("parseReport: %v", err)
	}
	return decls, sampleReport
}

func findDecl(t *testing.T, decls []*decl, kind declKind, name string) *decl {
	t.Helper()
	for _, d := range decls {
		if d.kind == kind && d.name == name {
			return d
		}
	}
	t.Fatalf("declaration %s '%s' not found", kind, name)
	return nil
}

func declNames(decls []*decl, kind declKind) []string {
	var names []string
	for _, d := range decls {
		if d.kind == kind {
			names = append(names, d.name)
		}
	}
	return names
}

func unusedNames(decls []*decl, raw string) []string {
	var names []string
	for _, d := range decls {
		if d.kind == kindParameter && builtinParameters[d.name] {
			continue
		}
		if !isUsed(d, raw) {
			names = append(names, d.name)
		}
	}
	return names
}

func containsAll(haystack, needles []string) bool {
	for _, n := range needles {
		found := false
		for _, h := range haystack {
			if h == n {
				found = true
				break
			}
		}
		if !found {
			return false
		}
	}
	return true
}

func validXML(t *testing.T, s string) {
	t.Helper()
	dec := xml.NewDecoder(strings.NewReader(s))
	for {
		_, err := dec.Token()
		if err == io.EOF {
			return
		}
		if err != nil {
			t.Fatalf("invalid XML: %v", err)
		}
	}
}

func TestParseReportCollectsTopLevelDecls(t *testing.T) {
	decls, _ := parseSample(t)

	params := declNames(decls, kindParameter)
	if len(params) != 2 || !containsAll(params, []string{"usedParam", "unusedParam"}) {
		t.Fatalf("parameters = %v, want [usedParam unusedParam]", params)
	}
	fields := declNames(decls, kindField)
	if len(fields) != 4 {
		t.Fatalf("fields = %v, want 4", fields)
	}
	// subreport parameter mappings must NOT be collected as report parameters
	if containsAll(params, []string{"childParam"}) {
		t.Fatal("subreport parameter was collected as a report parameter")
	}

	legacy := findDecl(t, decls, kindField, "legacyField")
	if legacy.properties[jsonFieldProperty] != "legacy.value" {
		t.Fatalf("legacy json property = %q, want legacy.value", legacy.properties[jsonFieldProperty])
	}
	used := findDecl(t, decls, kindField, "usedField")
	if used.properties[jsonqlFieldProperty] != "used.field" {
		t.Fatalf("jsonql property = %q, want used.field", used.properties[jsonqlFieldProperty])
	}
	sub := findDecl(t, decls, kindField, "subreportOnly")
	if !sub.hasDescription || strings.TrimSpace(sub.descriptionText) != "sub.only" {
		t.Fatalf("subreportOnly description = %q (has=%v)", sub.descriptionText, sub.hasDescription)
	}

	// offsets must be ordered: start < end-tag < after end-tag
	for _, d := range decls {
		if !(d.startOffset < d.endTagOffset && d.endTagOffset < d.endOffset) {
			t.Fatalf("bad offsets for %s '%s': %d < %d < %d",
				d.kind, d.name, d.startOffset, d.endTagOffset, d.endOffset)
		}
	}
}

func TestIsUsed(t *testing.T) {
	decls, raw := parseSample(t)

	if !isUsed(findDecl(t, decls, kindField, "usedField"), raw) {
		t.Error("usedField should be used")
	}
	// the subreport caveat: referenced only inside a subreport parameter
	if !isUsed(findDecl(t, decls, kindField, "subreportOnly"), raw) {
		t.Error("subreportOnly is referenced in a subreport parameter and must count as used")
	}
	if isUsed(findDecl(t, decls, kindField, "unusedField"), raw) {
		t.Error("unusedField should be unused")
	}
	if !isUsed(findDecl(t, decls, kindParameter, "usedParam"), raw) {
		t.Error("usedParam should be used")
	}
	if isUsed(findDecl(t, decls, kindParameter, "unusedParam"), raw) {
		t.Error("unusedParam should be unused")
	}
	if !isUsed(findDecl(t, decls, kindVariable, "counter"), raw) {
		t.Error("variable counter should be used ($V{counter})")
	}

	// raw parameter substitution $P!{name} also counts as usage
	if !isUsed(&decl{kind: kindParameter, name: "rawP"}, `$P!{rawP}`) {
		t.Error("$P!{name} should count as usage")
	}
}

func TestUnusedDetection(t *testing.T) {
	decls, raw := parseSample(t)
	unused := unusedNames(decls, raw)
	if len(unused) != 3 ||
		!containsAll(unused, []string{"unusedParam", "unusedField", "legacyField"}) {
		t.Fatalf("unused = %v, want [unusedParam unusedField legacyField]", unused)
	}
}

func TestJSONQLValueFromDescription(t *testing.T) {
	decls, _ := parseSample(t)
	// the description (the JSON path) is the value used for the jsonql property
	if got := jsonqlValue(findDecl(t, decls, kindField, "subreportOnly")); got != "sub.only" {
		t.Fatalf("jsonqlValue(subreportOnly) = %q, want sub.only", got)
	}
}

func TestBuildEditsJSONQLOnly(t *testing.T) {
	decls, raw := parseSample(t)

	out := jrxmlutil.ApplyEdits(raw, buildEdits(raw, decls))
	validXML(t, out)

	// fields with a description but no property get jsonql from the description
	for _, want := range []string{`value="sub.only"`, `value="unused.field"`} {
		if !strings.Contains(out, want) {
			t.Errorf("result missing %s", want)
		}
	}
	// the legacy property is renamed to the jsonql property (value preserved),
	// not kept alongside a duplicate
	if strings.Contains(out, jsonFieldProperty) {
		t.Errorf("legacy %s property should be renamed away:\n%s", jsonFieldProperty, out)
	}
	if !strings.Contains(out, `name="`+jsonqlFieldProperty+`" value="legacy.value"`) {
		t.Errorf("legacy property should become jsonql with its value preserved:\n%s", out)
	}
}

func TestBuildEditsWithDeletions(t *testing.T) {
	decls, raw := parseSample(t)
	findDecl(t, decls, kindParameter, "unusedParam").deleted = true
	findDecl(t, decls, kindField, "unusedField").deleted = true

	out := jrxmlutil.ApplyEdits(raw, buildEdits(raw, decls))
	validXML(t, out)

	for _, gone := range []string{"unusedParam", "unusedField"} {
		if strings.Contains(out, gone) {
			t.Errorf("result still contains deleted %s", gone)
		}
	}
	for _, kept := range []string{"usedParam", "usedField", "subreportOnly", "legacyField", "counter"} {
		if !strings.Contains(out, kept) {
			t.Errorf("result lost %s", kept)
		}
	}
	// deleted field must not get a jsonql addition; survivors do
	if strings.Contains(out, `value="unused.field"`) {
		t.Error("deleted unusedField still got a jsonql addition")
	}
	if !strings.Contains(out, `value="sub.only"`) {
		t.Error("subreportOnly should get jsonql added")
	}
	// surviving legacyField's legacy property is migrated to jsonql
	if strings.Contains(out, jsonFieldProperty) {
		t.Errorf("legacy %s property should be renamed away:\n%s", jsonFieldProperty, out)
	}
	if !strings.Contains(out, `name="`+jsonqlFieldProperty+`" value="legacy.value"`) {
		t.Error("legacyField's legacy property should be renamed to jsonql")
	}
}

func TestBuildEditsMigratesLegacyWithoutDescription(t *testing.T) {
	// a field with only the legacy property (no <description>) must still be
	// migrated: the property is renamed in place, not duplicated.
	raw := `<jasperReport name="t">
	<field name="f" class="java.lang.String">
		<property name="net.sf.jasperreports.json.field.expression" value="a.b"/>
	</field>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA[$F{f}]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`
	decls, err := parseReport([]byte(raw))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	out := jrxmlutil.ApplyEdits(raw, buildEdits(raw, decls))
	validXML(t, out)
	if strings.Contains(out, jsonFieldProperty) {
		t.Errorf("legacy property should be renamed even without a description:\n%s", out)
	}
	if !strings.Contains(out, `name="`+jsonqlFieldProperty+`" value="a.b"`) {
		t.Errorf("legacy property should become jsonql with its value preserved:\n%s", out)
	}
}

func TestBuildEditsRemovesLeftoverLegacy(t *testing.T) {
	// a field that already carries both properties: the leftover legacy one is
	// removed, the jsonql one is kept untouched.
	raw := `<jasperReport name="t">
	<field name="f" class="java.lang.String">
		<property name="net.sf.jasperreports.json.field.expression" value="old.value"/>
		<property name="net.sf.jasperreports.jsonql.field.expression" value="new.value"/>
	</field>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA[$F{f}]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`
	decls, err := parseReport([]byte(raw))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	out := jrxmlutil.ApplyEdits(raw, buildEdits(raw, decls))
	validXML(t, out)
	if strings.Contains(out, jsonFieldProperty) {
		t.Errorf("leftover legacy property should be removed:\n%s", out)
	}
	if !strings.Contains(out, `name="`+jsonqlFieldProperty+`" value="new.value"`) {
		t.Errorf("existing jsonql property should be kept:\n%s", out)
	}
}

func TestDescriptionMismatch(t *testing.T) {
	d := &decl{
		kind:            kindField,
		hasDescription:  true,
		descriptionText: "old.path",
		properties:      map[string]string{jsonqlFieldProperty: "new.path"},
	}
	j, dd, ok := descriptionMismatch(d)
	if !ok || j != "new.path" || dd != "old.path" {
		t.Fatalf("mismatch = (%q, %q, %v), want (new.path, old.path, true)", j, dd, ok)
	}

	// matching -> no mismatch
	d.descriptionText = "new.path"
	if _, _, ok := descriptionMismatch(d); ok {
		t.Fatal("matching description/jsonql should not mismatch")
	}

	// missing jsonql -> no mismatch (there is no path to sync to)
	d.descriptionText = "old.path"
	delete(d.properties, jsonqlFieldProperty)
	if _, _, ok := descriptionMismatch(d); ok {
		t.Fatal("missing jsonql should not mismatch")
	}

	// missing description -> no mismatch
	d.properties = map[string]string{jsonqlFieldProperty: "new.path"}
	d.hasDescription = false
	if _, _, ok := descriptionMismatch(d); ok {
		t.Fatal("missing description should not mismatch")
	}
}

func TestBuildEditsUpdatesDescription(t *testing.T) {
	raw := `<jasperReport name="t">
	<field name="f" class="java.lang.String">
		<description><![CDATA[old.path]]></description>
		<property name="net.sf.jasperreports.jsonql.field.expression" value="new.path"/>
	</field>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA[$F{f}]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`
	decls, err := parseReport([]byte(raw))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	f := findDecl(t, decls, kindField, "f")
	if f.descriptionTextStart < 0 || f.descriptionTextEnd <= f.descriptionTextStart {
		t.Fatalf("description text span not captured: [%d, %d)", f.descriptionTextStart, f.descriptionTextEnd)
	}
	f.updateDescription = true

	out := jrxmlutil.ApplyEdits(raw, buildEdits(raw, decls))
	validXML(t, out)
	if !strings.Contains(out, `<description><![CDATA[new.path]]></description>`) {
		t.Errorf("description not updated to jsonql path:\n%s", out)
	}
}

func TestBuiltinParametersSkipped(t *testing.T) {
	raw := `<jasperReport name="t"><parameter name="REPORT_CONNECTION" class="java.lang.String"/></jasperReport>`
	decls, err := parseReport([]byte(raw))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if got := unusedNames(decls, raw); len(got) != 0 {
		t.Fatalf("built-in parameter flagged unused: %v", got)
	}
}

func TestParseQuerySQL(t *testing.T) {
	raw := `<jasperReport name="t">
	<query language="sql"><![CDATA[SELECT id FROM users]]></query>
	<detail><band height="20"></band></detail>
</jasperReport>`
	q, err := parseQuery([]byte(raw))
	if err != nil {
		t.Fatalf("parseQuery: %v", err)
	}
	if q == nil {
		t.Fatal("query not found")
	}
	if q.language != "sql" {
		t.Fatalf("language = %q, want sql", q.language)
	}
	if raw[q.langValueStart:q.langValueEnd] != "sql" {
		t.Errorf("language span = %q, want sql", raw[q.langValueStart:q.langValueEnd])
	}
	if raw[q.bodyStart:q.bodyEnd] != "SELECT id FROM users" {
		t.Errorf("body span = %q, want the SQL", raw[q.bodyStart:q.bodyEnd])
	}
}

func TestParseQueryJSONQL(t *testing.T) {
	raw := `<jasperReport name="t"><query language="jsonql"><![CDATA[document.iddata]]></query></jasperReport>`
	q, err := parseQuery([]byte(raw))
	if err != nil {
		t.Fatalf("parseQuery: %v", err)
	}
	if q == nil || q.language != "jsonql" {
		t.Fatalf("language = %v, want jsonql", q)
	}
}

func TestParseQueryAbsentAndSelfClosing(t *testing.T) {
	q, err := parseQuery([]byte(`<jasperReport name="t"></jasperReport>`))
	if err != nil {
		t.Fatalf("parseQuery: %v", err)
	}
	if q != nil {
		t.Fatalf("expected nil query, got %+v", q)
	}

	// a self-closing <query/> must not error and must report no body
	q, err = parseQuery([]byte(`<jasperReport name="t"><query language="sql"/></jasperReport>`))
	if err != nil {
		t.Fatalf("parseQuery self-closing: %v", err)
	}
	if q == nil || q.language != "sql" || q.bodyStart >= 0 {
		t.Fatalf("self-closing query = %+v, want language=sql and no body", q)
	}
}

func TestQueryMigrationEdits(t *testing.T) {
	raw := `<query language="sql"><![CDATA[SELECT 1]]></query>`
	q, err := parseQuery([]byte(raw))
	if err != nil || q == nil {
		t.Fatalf("parseQuery: %v, %v", q, err)
	}
	edits := queryMigrationEdits(q, "document.root")
	out := jrxmlutil.ApplyEdits(raw, edits)
	if out != `<query language="jsonql"><![CDATA[document.root]]></query>` {
		t.Errorf("migrated = %q", out)
	}
	if got := migratedQueryText(raw, q, "document.root"); got != out {
		t.Errorf("migratedQueryText = %q, want %q", got, out)
	}
}

func TestParseRealExample(t *testing.T) {
	data, err := os.ReadFile("example_document.jrxml")
	if err != nil {
		t.Skip("example_document.jrxml not present")
	}
	decls, err := parseReport(data)
	if err != nil {
		t.Fatalf("parse example_document.jrxml: %v", err)
	}
	raw := string(data)

	unused := unusedNames(decls, raw)
	wantUnused := []string{"Parameter2", "ip_address", "subnet_mask", "hostname", "network_provider", "speed_test_result", "connection_status"}
	if len(unused) != len(wantUnused) || !containsAll(unused, wantUnused) {
		t.Fatalf("unused = %v, want %v", unused, wantUnused)
	}
	// gateway is used only as a subreport parameter — must NOT be flagged
	if containsAll(unused, []string{"gateway"}) {
		t.Fatal("gateway must not be flagged (subreport usage)")
	}
	if containsAll(unused, []string{"connection_type", "Parameter1"}) {
		t.Fatal("connection_type / Parameter1 must not be flagged")
	}
}
