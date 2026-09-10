package com.myhooks.steps.clear;

import com.myhooks.diffui.DiffRenderer;
import com.myhooks.edit.Edit;
import com.myhooks.edit.EditSet;
import com.myhooks.io.XmlSource;
import com.myhooks.jrutil.JrExpressions;
import com.myhooks.jrutil.JrSchema;
import com.myhooks.jrutil.JrStringUtil;
import com.myhooks.step.Context;
import com.myhooks.step.Discoverer;
import com.myhooks.step.EditFix;
import com.myhooks.step.Fix;
import com.myhooks.step.Group;
import com.myhooks.xmlspan.Attr;
import com.myhooks.xmlspan.Node;
import com.myhooks.xmlspan.Query;
import com.myhooks.xmlspan.XmlScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.sf.jasperreports.engine.design.JasperDesign;

/**
 * The clear step's fix discovery: SQL→jsonql query migration, unused-declaration
 * deletion (ground-truth via {@link JrExpressions}), description↔jsonql sync,
 * and jsonql property rename/removal/addition. Each fix-kind is its own group,
 * and the parse model is immutable.
 */
public final class ClearDiscoverer implements Discoverer {

    private static final String JSONQL_FIELD_PROPERTY = "net.sf.jasperreports.jsonql.field.expression";
    private static final String JSON_FIELD_PROPERTY = "net.sf.jasperreports.json.field.expression";
    private static final String CDATA_OPEN = "<![CDATA[";
    private static final String CDATA_CLOSE = "]]>";

    enum DeclKind {
        PARAMETER, FIELD, VARIABLE
    }

    record Declaration(
            DeclKind kind, String name,
            int start, int end, int endTag,
            boolean hasDescription, String descriptionText, int descriptionStart, int descriptionEnd,
            String descriptionIndent,
            boolean hasJsonql, String jsonqlValue,
            boolean hasLegacy, int legacyStart, int legacyEnd, int legacyNameStart, int legacyNameEnd,
            boolean descriptionCdata) {
    }

    record QueryModel(String language, int langValueStart, int langValueEnd,
            int bodyStart, int bodyEnd, int start, int end) {
    }

    private record Cdata(int start, int end, String content) {
    }

    private static final Set<String> BUILTIN_PARAMETERS = Set.of(
            "REPORT_PARAMETERS_MAP", "REPORT_DATA_SOURCE", "REPORT_CONNECTION",
            "JASPER_REPORT", "JASPER_REPORT_PARAMETERS_MAP", "REPORT_CONTEXT",
            "REPORT_CLASS_LOADER", "REPORT_VIRTUALIZER", "REPORT_FORMAT_FACTORY",
            "REPORT_MAX_COUNT", "REPORT_SCRIPTLET", "REPORT_LOCALE",
            "REPORT_RESOURCE_BUNDLE", "REPORT_TIME_ZONE", "REPORT_TEMPLATES",
            "IS_IGNORE_PAGINATION", "SORT_FIELDS", "FILTER", "REPORT_FILE_RESOLVER",
            "REPORT_URL_HANDLER_FACTORY", "JSON_INPUT_STREAM", "JSON_SOURCE",
            "JSON_LOCALE", "JSON_TIME_ZONE", "JSON_DATE_PATTERN", "JSON_NUMBER_PATTERN");

    @Override
    public List<Group> discover(Context context, Path path) throws Exception {
        String raw = XmlSource.read(path).text();
        Node root = XmlScanner.scan(raw);
        List<Declaration> declarations = parseDeclarations(root, raw);
        List<Group> groups = new ArrayList<>();

        // 1. SQL query migration
        QueryModel query = parseQuery(root, raw);
        if (query != null && query.language().equalsIgnoreCase("sql")) {
            Fix fix = new QueryMigrationFix(query, raw, context::freeform, context.color());
            groups.add(new Group("query migration", List.of(fix)));
        }

        // 2. Unused declarations (ground-truth via the JasperReports engine)
        Set<String> used = collectUsedNames(path);
        Set<String> unusedFieldNames = new HashSet<>();
        List<Fix> unusedFixes = new ArrayList<>();
        for (Declaration d : declarations) {
            if (d.kind() == DeclKind.PARAMETER && BUILTIN_PARAMETERS.contains(d.name())) {
                continue;
            }
            if (!used.contains(d.kind() + ":" + d.name())) {
                if (d.kind() == DeclKind.FIELD) {
                    unusedFieldNames.add(d.name());
                }
                int[] span = removalSpan(raw, d.start(), d.end());
                unusedFixes.add(new EditFix("delete unused " + d.kind().name().toLowerCase() + " '" + d.name() + "'",
                        raw.substring(span[0], d.end()), "",
                        new Edit(span[0], span[1], ""), context.color()));
            }
        }
        if (!unusedFixes.isEmpty()) {
            groups.add(new Group("unused declarations", unusedFixes));
        }

        // 3. Description <-> jsonql synchronization
        List<Fix> syncFixes = new ArrayList<>();
        for (Declaration d : declarations) {
            if (d.kind() != DeclKind.FIELD || !d.hasDescription() || !d.hasJsonql()) {
                continue;
            }
            if (unusedFieldNames.contains(d.name())) {
                continue;
            }
            if (d.jsonqlValue().isEmpty() || d.jsonqlValue().equals(d.descriptionText())) {
                continue;
            }
            syncFixes.add(new EditFix(
                    "change description for field '" + d.name() + "' from \"" + d.descriptionText()
                            + "\" to \"" + d.jsonqlValue() + "\"",
                    d.descriptionText(), d.jsonqlValue(),
                    new Edit(d.descriptionStart(), d.descriptionEnd(),
                            d.descriptionCdata() ? d.jsonqlValue() : JrStringUtil.encode(d.jsonqlValue())),
                    context.color()));
        }
        if (!syncFixes.isEmpty()) {
            groups.add(new Group("description sync", syncFixes));
        }

        // 4. jsonql property rename / removal / addition
        List<Fix> jsonqlFixes = new ArrayList<>();
        for (Declaration d : declarations) {
            if (d.kind() != DeclKind.FIELD) {
                continue;
            }
            if (unusedFieldNames.contains(d.name())) {
                continue;
            }
            if (d.hasLegacy()) {
                if (d.hasJsonql()) {
                    int[] span = removalSpan(raw, d.legacyStart(), d.legacyEnd());
                    jsonqlFixes.add(new EditFix("remove legacy " + JSON_FIELD_PROPERTY + " from field '" + d.name() + "'",
                            raw.substring(span[0], d.legacyEnd()), "",
                            new Edit(span[0], span[1], ""), context.color()));
                } else {
                    jsonqlFixes.add(new EditFix("rename " + JSON_FIELD_PROPERTY + " to " + JSONQL_FIELD_PROPERTY,
                            JSON_FIELD_PROPERTY, JSONQL_FIELD_PROPERTY,
                            new Edit(d.legacyNameStart(), d.legacyNameEnd(), JSONQL_FIELD_PROPERTY), context.color()));
                }
            } else if (!d.hasJsonql() && d.hasDescription() && !d.descriptionText().isBlank()) {
                String line = jsonqlPropertyLine(d);
                int insertAt = lineStart(raw, d.endTag());
                jsonqlFixes.add(new EditFix("add " + JSONQL_FIELD_PROPERTY + " = \"" + d.descriptionText() + "\"",
                        "", line.strip(),
                        new Edit(insertAt, insertAt, line + "\n"), context.color()));
            }
        }
        if (!jsonqlFixes.isEmpty()) {
            groups.add(new Group("jsonql fixes", jsonqlFixes));
        }

        return groups;
    }

    // ------------------------------------------------------------------
    // Parsing (immutable model)
    // ------------------------------------------------------------------

    private static List<Declaration> parseDeclarations(Node root, String raw) {
        List<Declaration> declarations = new ArrayList<>();
        for (Node child : root.children()) {
            String tag = child.tag();
            if (tag.equals("parameter") || tag.equals("field") || tag.equals("variable")) {
                declarations.add(parseDeclaration(child, raw));
            }
        }
        return declarations;
    }

    private static Declaration parseDeclaration(Node node, String raw) {
        DeclKind kind = DeclKind.valueOf(node.tag().toUpperCase());
        String name = attrValue(node, "name", raw);

        boolean hasDescription = false;
        String descriptionText = "";
        int descriptionStart = -1;
        int descriptionEnd = -1;
        String descriptionIndent = "";
        boolean descriptionCdata = false;
        boolean hasJsonql = false;
        String jsonqlValue = "";
        boolean hasLegacy = false;
        int legacyStart = -1;
        int legacyEnd = -1;
        int legacyNameStart = -1;
        int legacyNameEnd = -1;

        if (kind == DeclKind.FIELD) {
            for (Node child : node.children()) {
                if (child.tag().equals("description")) {
                    hasDescription = true;
                    descriptionCdata = cdataSpan(child, raw) != null;
                    Cdata cdata = descriptionTextSpan(child, raw);
                    descriptionText = cdata.content().strip();
                    descriptionStart = cdata.start();
                    descriptionEnd = cdata.end();
                    descriptionIndent = raw.substring(lineStart(raw, child.startTag()), child.startTag());
                } else if (child.tag().equals("property")) {
                    String propertyName = attrValue(child, "name", raw);
                    if (propertyName.equals(JSONQL_FIELD_PROPERTY)) {
                        hasJsonql = true;
                        jsonqlValue = attrValue(child, "value", raw).strip();
                    } else if (propertyName.equals(JSON_FIELD_PROPERTY)) {
                        hasLegacy = true;
                        legacyStart = child.startTag();
                        legacyEnd = child.end();
                        Optional<Attr> nameAttr = Query.findAttr(child, "name");
                        if (nameAttr.isPresent()) {
                            legacyNameStart = nameAttr.get().valueStart();
                            legacyNameEnd = nameAttr.get().valueEnd();
                        }
                    }
                } else if (child.tag().equals("propertyExpression")) {
                    if (attrValue(child, "name", raw).equals(JSONQL_FIELD_PROPERTY)) {
                        hasJsonql = true;
                    }
                }
            }
        }

        return new Declaration(kind, name, node.startTag(), node.end(), node.endTag(),
                hasDescription, descriptionText, descriptionStart, descriptionEnd, descriptionIndent,
                hasJsonql, jsonqlValue, hasLegacy, legacyStart, legacyEnd, legacyNameStart, legacyNameEnd,
                descriptionCdata);
    }

    private static QueryModel parseQuery(Node root, String raw) {
        for (Node child : root.children()) {
            if (child.tag().equals("query")) {
                Optional<Attr> language = Query.findAttr(child, "language");
                int langStart = language.map(Attr::valueStart).orElse(-1);
                int langEnd = language.map(Attr::valueEnd).orElse(-1);
                String languageValue = attrValue(child, "language", raw);
                Cdata body = cdataSpan(child, raw);
                int bodyStart = body == null ? -1 : body.start();
                int bodyEnd = body == null ? -1 : body.end();
                return new QueryModel(languageValue, langStart, langEnd, bodyStart, bodyEnd,
                        child.startTag(), child.end());
            }
        }
        return null;
    }

    private static Cdata descriptionTextSpan(Node description, String raw) {
        Cdata cdata = cdataSpan(description, raw);
        if (cdata != null) {
            return cdata;
        }
        int start = description.startTagEnd();
        int end = description.endTag();
        // Plain-text descriptions use character references; CDATA is literal.
        return new Cdata(start, end, JrStringUtil.decode(raw.substring(start, end).strip()));
    }

    private static Cdata cdataSpan(Node node, String raw) {
        int from = node.startTagEnd();
        int to = node.endTag();
        int open = raw.indexOf(CDATA_OPEN, from);
        if (open < 0 || open >= to) {
            return null;
        }
        int start = open + CDATA_OPEN.length();
        int close = raw.indexOf(CDATA_CLOSE, start);
        if (close < 0 || close >= to) {
            return null;
        }
        return new Cdata(start, close, raw.substring(start, close));
    }

    private static String attrValue(Node node, String name, String raw) {
        return Query.findAttr(node, name)
                .map(a -> JrStringUtil.decode(raw.substring(a.valueStart(), a.valueEnd())))
                .orElse("");
    }

    // ------------------------------------------------------------------
    // Unused detection (ground-truth via JasperReports)
    // ------------------------------------------------------------------

    private static Set<String> collectUsedNames(Path path) throws Exception {
        JasperDesign design = JrSchema.validate(Files.readAllBytes(path));
        Set<String> used = new HashSet<>();
        for (JrExpressions.Reference reference : JrExpressions.references(design)) {
            used.add(reference.kind() + ":" + reference.name());
        }
        return used;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String jsonqlPropertyLine(Declaration d) {
        return d.descriptionIndent() + "<property name=\"" + JSONQL_FIELD_PROPERTY
                + "\" value=\"" + JrStringUtil.encodeAttribute(d.descriptionText()) + "\"/>";
    }

    private static int lineStart(String raw, int offset) {
        int i = offset;
        while (i > 0 && raw.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    /**
     * The span to delete for a whole-element removal: the element plus its
     * indentation and line terminator when it is the only thing on its line, or
     * just the element when other content shares the line. This never removes a
     * neighbouring declaration or comment, and consumes a CRLF pair whole.
     */
    private static int[] removalSpan(String raw, int start, int end) {
        int from = start;
        boolean alone = raw.substring(lineStart(raw, start), start).isBlank();
        if (alone) {
            from = lineStart(raw, start);
        }
        int to = end;
        if (alone) {
            int lineEnd = end;
            while (lineEnd < raw.length() && raw.charAt(lineEnd) != '\n' && raw.charAt(lineEnd) != '\r') {
                lineEnd++;
            }
            if (raw.substring(end, lineEnd).isBlank()) {
                to = lineEnd;
                if (to < raw.length() && raw.charAt(to) == '\r') {
                    to++;
                }
                if (to < raw.length() && raw.charAt(to) == '\n') {
                    to++;
                }
            }
        }
        return new int[] {from, to};
    }

    /**
     * A fix whose edit depends on a free-form answer (the jsonql expression)
     * supplied when the fix is applied.
     */
    private static final class QueryMigrationFix implements Fix {
        private final QueryModel query;
        private final String raw;
        private final Function<String, String> freeform;
        private final boolean color;

        QueryMigrationFix(QueryModel query, String raw, Function<String, String> freeform, boolean color) {
            this.query = query;
            this.raw = raw;
            this.freeform = freeform;
            this.color = color;
        }

        @Override
        public String describe() {
            return "migrate SQL query to jsonql";
        }

        @Override
        public String diff() {
            return DiffRenderer.render(raw.substring(query.start(), query.end()), "", "          ", color);
        }

        @Override
        public void apply(EditSet editSet) {
            String expression = freeform.apply("jsonql expression to replace the SQL query:");
            if (expression.isEmpty()) {
                return;
            }
            if (query.langValueStart() >= 0 && query.langValueEnd() > query.langValueStart()) {
                editSet.add(new Edit(query.langValueStart(), query.langValueEnd(), "jsonql"));
            }
            // bodyStart == bodyEnd is a valid (empty) CDATA body: insert the
            // expression instead of replacing.
            if (query.bodyStart() >= 0) {
                editSet.add(new Edit(query.bodyStart(), query.bodyEnd(), expression));
            }
        }
    }
}
