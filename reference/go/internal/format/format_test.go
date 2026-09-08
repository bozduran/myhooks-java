package format

import (
	"encoding/xml"
	"io"
	"strings"
	"testing"

	"myhooks/internal/jrxmlutil"
)

func TestPeriodFix(t *testing.T) {
	cases := []struct{ in, want string }{
		{`"today.Swill"`, `"today. Swill"`},
		{`"Text Field.Today is something else"`, `"Text Field. Today is something else"`},
		{`"else. This"`, `"else. This"`}, // already spaced — untouched
		{`"3.14"`, `"3.14"`},             // decimal — untouched
		{`"something."`, `"something."`}, // end of string — untouched
	}
	for _, c := range cases {
		if got := transformExpression(c.in, true, ""); got != c.want {
			t.Errorf("period fix %q = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestPeriodFixTextOnly(t *testing.T) {
	// non-text expressions must NOT get the period fix (JSON path safety)
	if got := transformExpression(`"today.Swill"`, false, ""); got != `"today.Swill"` {
		t.Errorf("non-text period fix = %q, want unchanged", got)
	}
}

func TestMarkupNewline(t *testing.T) {
	cases := []struct {
		in, markup, want string
	}{
		{`"a\nb"`, "", `"a\nb"`},     // no markup -> \n
		{`"a\nb"`, "none", `"a\nb"`}, // explicit none -> \n
		{`"a\nb"`, "styled", `"a<br/>b"`},
		{`"a<br>b"`, "styled", `"a<br/>b"`},
		{`"a\nb"`, "html", `"a<br>b"`},
		{`"a<br/>b"`, "html", `"a<br>b"`},
		{`"a<br>b"`, "none", `"a\nb"`}, // <br> in a no-markup field -> \n
		{`"a\nb"`, "rtf", `"a\nb"`},    // unknown markup left alone
	}
	for _, c := range cases {
		if got := transformExpression(c.in, true, c.markup); got != c.want {
			t.Errorf("markup %q in %q = %q, want %q", c.markup, c.in, got, c.want)
		}
	}
}

func TestCodeFormatting(t *testing.T) {
	cases := []struct{ in, want string }{
		{`fun(a,b)`, `fun(a, b)`},
		{`a?b:c`, `a ? b : c`},
		{`a==b`, `a == b`},
		{`a!=b`, `a != b`},
		{`a>=b`, `a >= b`},
		{`a<=b`, `a <= b`},
		{`a&&b`, `a && b`},
		{`a||b`, `a || b`},
		{`a  ==  b`, `a == b`}, // exactly one space
		{`(String)IF(a==1?b!=2:c)`, `(String) IF(a == 1 ? b != 2 : c)`},
		{`(String)$F{x}.toString()`, `(String) $F{x}.toString()`},
		{`IF($F{a}==1,foo(1,2),bar($F{b},3))`, `IF($F{a} == 1, foo(1, 2), bar($F{b}, 3))`},
		{`$F{a}>=1&&$F{b}<5||$F{c}<=3`, `$F{a} >= 1 && $F{b}<5 || $F{c} <= 3`}, // bare < untouched
		{`IF(a==1?"x":"y")`, `IF(a == 1 ? "x" : "y")`},                         // strings preserved
		{`"a==b"`, `"a==b"`}, // string content untouched
	}
	for _, c := range cases {
		if got := transformExpression(c.in, false, ""); got != c.want {
			t.Errorf("format %q = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestTransformRawText(t *testing.T) {
	if got := transformRawText(`First line.Today\nSecond line.<br>Third<br/>line`, "styled"); got != `First line. Today<br/>Second line.<br/>Third<br/>line` {
		t.Errorf("staticText transform = %q", got)
	}
}

func TestFixElementStartTag(t *testing.T) {
	cases := []struct {
		name string
		tag  string
		e    *elementFix
		want string // "" means no change
	}{
		{
			name: "add both to textField",
			tag:  `<element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">`,
			e:    &elementFix{kind: "textField"},
			want: `<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">`,
		},
		{
			name: "staticText gets positionType but no textAdjust",
			tag:  `<element kind="staticText" uuid="s1" x="0" y="0" width="100" height="20">`,
			e:    &elementFix{kind: "staticText"},
			want: `<element kind="staticText" uuid="s1" positionType="Float" x="0" y="0" width="100" height="20">`,
		},
		{
			name: "add positionType to break",
			tag:  `<element kind="break" uuid="b1" x="0" y="0" width="100" height="1"/>`,
			e:    &elementFix{kind: "break"},
			want: `<element kind="break" uuid="b1" positionType="Float" x="0" y="0" width="100" height="1"/>`,
		},
		{
			name: "fill empty attributes",
			tag:  `<element kind="textField" uuid="u2" positionType="" x="0" y="0" width="100" height="20" textAdjust="">`,
			e:    &elementFix{kind: "textField", hasPositionType: true, positionTypeEmpty: true, hasTextAdjust: true, textAdjustEmpty: true},
			want: `<element kind="textField" uuid="u2" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">`,
		},
		{
			name: "keep existing non-empty values",
			tag:  `<element kind="textField" uuid="u3" positionType="FixRelativeToTop" x="0" y="0" width="100" height="20" textAdjust="CutText">`,
			e:    &elementFix{kind: "textField", hasPositionType: true, hasTextAdjust: true},
			want: "",
		},
		{
			name: "already correct",
			tag:  `<element kind="textField" uuid="u4" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">`,
			e:    &elementFix{kind: "textField", hasPositionType: true, hasTextAdjust: true},
			want: "",
		},
	}
	for _, c := range cases {
		got := fixElementStartTag(c.tag, c.e)
		if got != c.want {
			t.Errorf("%s: fixElementStartTag = %q, want %q", c.name, got, c.want)
		}
	}
}

const sampleReport = `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<parameter name="p" class="java.lang.String">
		<defaultValueExpression><![CDATA[(String)IF(a==1?b!=2:c)]]></defaultValueExpression>
	</parameter>
	<detail>
		<band height="100">
			<element kind="textField" uuid="u1" x="0" y="0" width="100" height="20">
				<expression><![CDATA["today.Swill"]]></expression>
			</element>
			<element kind="break" uuid="u2" x="0" y="30" width="100" height="1"/>
			<element kind="textField" uuid="u3" positionType="Float" x="0" y="60" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA["clean"]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`

func TestParseAndGroupItems(t *testing.T) {
	changes, err := parse([]byte(sampleReport), "")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	items := groupChanges(changes)

	if len(items) != 3 {
		t.Fatalf("got %d items, want 3: %+v", len(items), items)
	}

	// parameter item
	if !strings.Contains(items[0].label, "parameter 'p'") {
		t.Errorf("item[0] label = %q", items[0].label)
	}
	if !containsDetail(items[0], "format expression") {
		t.Errorf("parameter item missing 'format expression': %v", items[0].details)
	}

	// textField item: attributes (combined) + expression
	tf := items[1]
	if !strings.Contains(tf.label, "textField (line") {
		t.Errorf("item[1] label = %q", tf.label)
	}
	for _, want := range []string{`add positionType="Float", add textAdjust="StretchHeight"`, "format expression"} {
		if !containsDetail(tf, want) {
			t.Errorf("textField item missing %q: %v", want, tf.details)
		}
	}

	// break item: only positionType
	br := items[2]
	if !strings.Contains(br.label, "break (line") {
		t.Errorf("item[2] label = %q", br.label)
	}
	if len(br.details) != 1 || br.details[0] != `add positionType="Float"` {
		t.Errorf("break item details = %v", br.details)
	}
}

func TestNoChangesWhenClean(t *testing.T) {
	clean := `<jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
	<parameter name="p" class="java.lang.String">
		<defaultValueExpression><![CDATA["clean"]]></defaultValueExpression>
	</parameter>
	<detail>
		<band height="20">
			<element kind="textField" uuid="u1" positionType="Float" x="0" y="0" width="100" height="20" textAdjust="StretchHeight">
				<expression><![CDATA["Clean text"]]></expression>
			</element>
		</band>
	</detail>
</jasperReport>`
	changes, err := parse([]byte(clean), "")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(changes) != 0 {
		t.Fatalf("clean report produced changes: %+v", changes)
	}
}

func TestExpectedReportName(t *testing.T) {
	cases := []struct{ path, want string }{
		{"example_document_code.jrxml", "example_document_code"},
		{"reports/filename.jrxml", "filename"},
		{"dir/sub/a.b.jrxml", "a.b"},
	}
	for _, c := range cases {
		if got := expectedReportName(c.path); got != c.want {
			t.Errorf("expectedReportName(%q) = %q, want %q", c.path, got, c.want)
		}
	}
}

func TestReportNameMatchesFilename(t *testing.T) {
	report := `<jasperReport name="wrong_name" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
</jasperReport>`

	// mismatched name -> a single change fixes it to the file's base name
	changes, err := parse([]byte(report), "right_name")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(changes) != 1 {
		t.Fatalf("got %d changes, want 1: %+v", len(changes), changes)
	}
	if changes[0].desc != `set name="right_name"` {
		t.Errorf("desc = %q, want set name=\"right_name\"", changes[0].desc)
	}
	if !strings.Contains(changes[0].owner, "jasperReport") {
		t.Errorf("owner = %q, want jasperReport item", changes[0].owner)
	}

	out := jrxmlutil.ApplyEdits(report, []jrxmlutil.Edit{changes[0].edit})
	if !strings.Contains(out, `<jasperReport name="right_name"`) {
		t.Errorf("name not replaced: %s", out)
	}
	validXML(t, out)

	// matching name -> no name change
	changes, err = parse([]byte(report), "wrong_name")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	for _, c := range changes {
		if strings.Contains(c.owner, "jasperReport") {
			t.Errorf("name change emitted for matching name: %+v", c)
		}
	}
}

func TestApplyEditsReplacesRanges(t *testing.T) {
	raw := "abcXYZdef"
	edits := []jrxmlutil.Edit{{Start: 3, End: 6, Text: "123"}}
	if got := jrxmlutil.ApplyEdits(raw, edits); got != "abc123def" {
		t.Fatalf("applyEdits = %q, want abc123def", got)
	}
}

func TestParsePeriodFixInParameterDefaultAndVariableInitial(t *testing.T) {
	raw := `<jasperReport name="t">
	<parameter name="p" class="java.lang.String">
		<defaultValueExpression><![CDATA["param.World"]]></defaultValueExpression>
	</parameter>
	<variable name="v" class="java.lang.String">
		<initialValueExpression><![CDATA["var.World"]]></initialValueExpression>
	</variable>
</jasperReport>`
	changes, err := parse([]byte(raw), "")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(changes) != 2 {
		t.Fatalf("got %d changes, want 2: %+v", len(changes), changes)
	}
	if changes[0].edit.Text != `"param. World"` {
		t.Errorf("parameter default = %q", changes[0].edit.Text)
	}
	if changes[1].edit.Text != `"var. World"` {
		t.Errorf("variable initial = %q", changes[1].edit.Text)
	}
}

func containsDetail(it *fixItem, want string) bool {
	for _, d := range it.details {
		if d == want {
			return true
		}
	}
	return false
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
