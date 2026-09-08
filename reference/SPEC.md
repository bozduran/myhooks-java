# myhooks — Software Specification

## 1. Purpose

`myhooks` is a single Git hook for JasperReports `.jrxml` report files
(JasperReports 7.x "new" schema). Installed as the `commit-msg` hook it
validates the commit message; installed as the `pre-commit` hook it enforces a
consistent report convention by inspecting the staged `.jrxml` files and, on
approval, rewriting them in place. It is a Go executable; the `pre-commit`
invocation runs up to five file-check steps in a fixed sequence.

The hook does not block a commit for "cosmetic" reasons it can fix itself
silently: it is **interactive**, shows every change in git-diff style, and lets
the author accept or reject each change.

---

## 2. Goals and non-goals

### Goals

- Require a Conventional Commit subject (`<type>(<scope>)!: <subject>`) and
  flag common typos in the commit message (via the `commit-msg` hook).
- Remove dead declarations (unused `<parameter>`/`<field>`/`<variable>`) from
  reports.
- Migrate SQL `<query>` elements and legacy `net.sf.jasperreports.json.field.expression`
  properties to the project's jsonql convention.
- Normalize report text, Java expressions, and required element attributes.
- Keep XML document order consistent with visual (reading/paint) order.
- Fix text that renders incorrectly (missing space, double spaces,
  unrenderable characters).
- Report which top-level templates transitively include each changed file.

### Non-goals

- It does not render, validate, or compile reports (no JasperReports engine).
- It does not reformat/indent the whole XML document — edits are **surgical**
  (byte-span replacements), preserving all existing whitespace outside an edit.
- It does not automatically resolve overlapping elements in the `sort` step;
  those need a human z-order decision and are only warned about.
- The `report` step never modifies files and never affects the exit status.

---

## 3. Architecture

```
myhooks/                              module: myhooks  (go 1.27.0)
├── main.go                           dispatcher + CLI + usage
└── internal/
    ├── jrxmlutil/                    shared: edit model, diff rendering,
    │                                 XML attr helpers, staged-file discovery,
    │                                 interactive prompt
    ├── commitmsg/                    step 0 — commit message semantic + typo check
    ├── clear/                        step 1 — query + unused declarations + jsonql
    ├── format/                       step 2 — text/attr/expression/name formatting
    ├── sort/                         step 3 — geometry ordering
    ├── textcheck/                    step 4 — spacing + unrenderable characters
    └── report/                       step 5 — include-chain (informational)
```

Each step package exposes two entry points:

| Function | Behavior |
| --- | --- |
| `Run(args)` | Interactive: lists findings, prompts, applies approved changes. Returns the process exit code (0 or 1). |
| `Check(args)` | Report-only: lists what *would* change, applies nothing, always returns 0. |

The dispatcher in `main.go` selects `Run` or `Check` per step based on the
`stepsEnabled` toggle table.

---

## 4. Command-line interface

```
myhooks [file.jrxml ...]             run all enabled steps on the staged files
myhooks <step> [file.jrxml ...]      run one step (commitmsg|clear|format|sort|textcheck|report)
myhooks commitmsg <message-file>     validate the commit message (commit-msg hook)
myhooks -h | --help                  print usage
```

- A leading argument equal to a known step name (`commitmsg`, `clear`, `format`,
  `sort`, `textcheck`, `report`) runs **only that step**.
- Otherwise, all steps run in order `clear → format → sort → textcheck → report`.
  The `commitmsg` step is not part of the pre-commit sequence: it runs only via
  the `commit-msg` hook (or an explicit `myhooks commitmsg <message-file>`),
  because the message is not finalized during `pre-commit`.
- When the binary is invoked with the name `commit-msg` (copied/symlinked as
  `.git/hooks/commit-msg`), it runs the `commitmsg` step with `$1` (the message
  file) automatically.
- `-h`/`--help` prints top-level usage; each step also accepts `-h`/`--help`
  for a per-step help line.

### File selection

Each step discovers its input files identically:

1. If file arguments are given, they are the candidates.
2. Otherwise, candidates are the staged files from
   `git diff --cached --name-only --diff-filter=ACMR`.
3. Candidates whose name ends in `.jrxml` (case-insensitive) are processed;
   everything else is ignored.
4. If no `.jrxml` files remain, the step prints a "no files" notice and
   returns 0.

---

## 5. Exit codes

| Code | Meaning |
| --- | --- |
| 0 | The commit may continue (no changes, or only automatically-staged jsonql fixes). |
| 1 | At least one change was left **unstaged** for review (or an error occurred). The commit is stopped. |

Rules:

- Every step except `report` returns 1 when it applied changes and left them
  unstaged, or when it hit an error.
- `report` always returns 0.
- `Check` (report-only mode) always returns 0.
- The dispatcher runs **every** step regardless of the others' results, so the
  author reviews all changes in one pass; if any step returned 1, the overall
  exit code is 1.

---

## 6. Edit model and change display

Changes are expressed as byte-span edits:

```go
type Edit struct {
    Start int    // first byte of the replaced range
    End   int    // one past the last replaced byte
    Text  string // replacement; "" = deletion, End==Start = insertion
}
```

- Edits are applied in **descending `Start` order** so earlier offsets remain
  valid regardless of length changes.
- All edits are surgical: content outside the replaced byte span is never
  touched, so indentation and formatting elsewhere are preserved exactly.
- An applied change is written back to the working file **preserving its
  existing file mode** (`os.WriteFile` with the original `FileMode`).

### Diff rendering

Every proposed change is shown in git-diff style on the terminal:

- Removed lines: `-` prefix, red background (`\x1b[41m`), black text.
- Added lines: `+` prefix, green background (`\x1b[42m`), black text.
- A single trailing newline is dropped so an edit ending in `\n` does not
  produce a spurious empty line.

---

## 7. Interactive prompt model

All mutating steps are interactive. The shared prompt offers four choices:

| Choice | Meaning |
| --- | --- |
| **Yes** | Apply this single fix. |
| **No** | Skip this single fix. |
| **All** | Apply this and every remaining fix in the file. |
| **Skip file** | Leave the rest of the current file untouched (no further edits to it). |

Behavior details:

- **Default answer is No.**
- On a real terminal, the prompt renders arrow-key-selectable options
  (`Yes No All Skip file`), and also accepts `y`/`n`/`a`/`s`.
- On a non-terminal (piped input, or `/dev/null`), it falls back to line-based
  `[y]es/[n]o/[a]ll/[s]kip` input. Because `git commit` runs hooks with
  `/dev/null` as stdin, the prompt falls back to the controlling terminal
  (`/dev/tty`) when stdin is a non-TTY character device, so interactive
  prompts still work under `git commit`.
- A free-form text prompt (`PromptText`) is used only for the jsonql migration
  question; it is always line-based and returns the trimmed line (empty for
  blank/EOF).

---

## 8. Step specifications

### 8.0 `commitmsg` — commit message validation (commit-msg hook)

Runs when the tool is invoked as the `commit-msg` hook (or explicitly as
`myhooks commitmsg <message-file>`). It reads the message file and performs two
checks in order.

#### 8.0.1 Semantic (Conventional Commit) check

- The first line (subject) must match
  `<type>(<scope>)!: <subject>`, where `type` is one of
  `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`,
  `chore`, `revert`; `(<scope>)` and `!` are optional; the **space after `:` is
  required** (`feat:add x` is rejected).
- An empty message or a non-conforming subject **blocks the commit** (exit 1)
  with a message showing the expected format and allowed types. This check is
  not skippable.

#### 8.0.2 Typo check

- Flags misspellings using the `github.com/client9/misspell` dictionary
  (neutral English, so US/UK spelling variants are not treated as errors). Only
  dictionary words are flagged, so technical terms and non-English words pass
  untouched; `ignoreRules` (`internal/commitmsg/commitmsg.go`) opts specific
  words out in case one collides.
- Each flagged typo is listed with its correction and line number, then the
  author is prompted **"Correct the typo(s) above?"** using the standard four
  choices:
  - **Yes** / **All** — rewrite the message file with the corrections, exit 0.
  - **Skip** — leave the message as-is, exit 0 (the author accepts the spelling).
  - **No** — leave the message as-is, exit 1 (the commit is blocked).
- Corrections preserve the original word's capitalization (`Teh` → `The`,
  `teh` → `the`, `TEH` → `THE`).
- `Check` (report-only) lists semantic and typo findings without prompting and
  returns 0.

---

### 8.1 `clear` — query, unused declarations, jsonql

Scans the report `<query>` and the top-level declarations, and migrates to the
project's jsonql convention.

#### 8.1.1 SQL query migration

- When the report's `<query>` has `language="sql"` (case-insensitive), it is
  reported as `[sql]` and the current query element is shown as a diff.
- The user is asked **"Migrate this query to jsonql?"**.
  - On **Yes**/**All**, a free-form prompt asks **"jsonql expression to replace
    the SQL query:"**; the supplied JSON path becomes the new query body.
  - On **No**, the SQL query is left as-is.
  - On **Skip file**, the whole file is left untouched.
- On approval, two edits are applied:
  1. `language="sql"` → `language="jsonql"` (value inside the quotes only).
  2. The query body (the CDATA content, or the trimmed inner text when there is
     no CDATA) → the supplied expression.
- A migrated query is left **unstaged** and stops the commit.

#### 8.1.2 Unused declarations

- Declarations are the **top-level** `<parameter>`, `<field>`, and `<variable>`
  elements that are direct children of `<jasperReport>`. Nested declarations
  (e.g. subreport parameter mappings) are ignored.
- A declaration is **unused** when its name is never referenced anywhere in the
  raw file text:
  - field → `$F{name}`
  - variable → `$V{name}`
  - parameter → `$P{name}` or `$P!{name}`
- Because the whole raw text is searched, a value used only inside a subreport
  parameter expression still counts as **used**.
- JasperReports built-in parameters are never flagged, even if declared
  explicitly (the full built-in list is hard-coded; see `builtinParameters` in
  `internal/clear/clear.go`).
- Each unused declaration is shown with a diff and prompted
  **"Delete unused <kind> '<name>'?"**.
- A deletion removes the declaration's whole line(s) (from the start of its
  line through its closing tag, including a trailing newline).
- Deletions are left **unstaged** and stop the commit.

#### 8.1.3 jsonql field-expression migration/addition

For every surviving `<field>`:

- **Legacy rename** — a static
  `net.sf.jasperreports.json.field.expression` property is renamed **in place**
  to `net.sf.jasperreports.jsonql.field.expression`, preserving its value,
  position, and formatting.
- **Legacy removal** — if that legacy property exists *and* the field already
  has the jsonql property, the legacy property is removed (its whole line).
- **Addition** — a field that has a `<description>` but no jsonql property gets
  a new property
  `<property name="net.sf.jasperreports.jsonql.field.expression" value="..."/>`
  whose value is the trimmed description text (the description is the JSON
  path, by convention). The new property line is inserted at the field's child
  indentation.
- Property names with dynamic `<propertyExpression name="...">` are recognized
  as jsonql for the "already present" checks.

#### 8.1.4 Description ↔ jsonql synchronization

- For a field that has both a `<description>` and a jsonql property value, and
  the two differ (jsonql is the source of truth), the hook reports the
  mismatch and asks whether to change the description to match the jsonql path.
- On approval, the description's text (inside its CDATA wrapper, when present)
  is replaced with the jsonql value.
- Description changes are left **unstaged** and stop the commit.

#### 8.1.5 Staging behavior (summary)

| Kind of change | Staged? | Stops commit? |
| --- | --- | --- |
| Unused declaration deleted | no (left unstaged) | yes |
| Description changed to match jsonql | no (left unstaged) | yes |
| SQL query migrated to jsonql | no (left unstaged) | yes |
| Legacy property renamed/removed, or jsonql property added | yes (`git add`) | no |

---

### 8.2 `format` — text, attribute, expression, and name formatting

Groups fixes per element (with line numbers) and prompts per fix-item.

#### 8.2.1 Period-space rule

- Inside double-quoted string literals of **rendered-text** contexts — text
  elements, parameter `<defaultValueExpression>`, and variable
  `<initialValueExpression>` — a `.` directly followed by an uppercase letter
  gets a space: `today.Swill` → `today. Swill`.
- JSON paths and code elsewhere are left alone.

#### 8.2.2 Newline normalization

- Newlines inside text are normalized to the element's `markup` attribute:

  | markup | token |
  | --- | --- |
  | *(none)* / `none` | `\n` |
  | `styled` | `<br/>` |
  | `html` | `<br>` |
  | any other value | left unchanged |

- Existing `<br/>`, `<br>`, and literal `\n` sequences are all replaced with
  the target token.

#### 8.2.3 Required attributes

- Every `<element>` gets `positionType="Float"` when the attribute is missing
  or empty.
- `textField` elements additionally get `textAdjust="StretchHeight"` when
  missing or empty.
- Attributes are inserted at the appropriate position in the start tag
  (`positionType` after `uuid`/`kind`; `textAdjust` before the closing `>`).

#### 8.2.4 Java expression formatting

Applied to the code of expression elements (string literals are protected via
placeholders so their contents are not reformatted):

| Before | After |
| --- | --- |
| `fun(a,b)` | `fun(a, b)` |
| `a?b:c` | `a ? b : c` |
| `a==b` | `a == b` |
| `a!=b` | `a != b` |
| `a>=b` | `a >= b` |
| `a<=b` | `a <= b` |
| `a&&b` | `a && b` |
| `a\|\|b` | `a \|\| b` |
| `(String)IF(x)` | `(String) IF(x)` |

#### 8.2.5 Report name alignment

- The `<jasperReport name="...">` must equal the file's base name without its
  extension (`reports/example.jrxml` → `example`).
- On mismatch, the `name` attribute value is replaced (or the attribute added,
  if absent) with the expected name.

#### 8.2.6 Staging behavior

- All approved `format` changes are written to the working file and left
  **unstaged**; the step returns 1 to stop the commit.

---

### 8.3 `sort` — geometry ordering

JasperReports treats XML document order as both the paint (z) order and the
Studio outline order, but Studio only updates x/y when an element is visually
moved and does not re-splice it in the XML. This step restores the invariant
**document order == reading order**.

- Containers are every `<band>` and every `<element kind="frame">`.
- Each container's **direct** `<element>` children are reordered by geometry:
  `y` ascending, then `x` ascending, using a **stable** sort.
- Only direct children are collected; nested `<element>` children (e.g. inside
  a `<component>` table/list) are skipped so child byte-spans never overlap.
- A container is offered as a fix-item only when its order would change; the
  old→new order is summarized (e.g. `reorder [a(10,20), b(5,30)] ->
  [b(5,30), a(10,20)]`) and shown as a diff.
- **Overlaps** — pairs of children whose bounding boxes intersect — are
  reported as `warning:` details but are **not** auto-fixed.
- Reorders are **length-preserving**: only the `<element>` substrings are moved
  among their slots; non-element content (whitespace, `<property>`, comments)
  stays in place. Containers are processed deepest-first so offsets remain
  valid.
- Approved reorders are left **unstaged**; the step returns 1 to stop the
  commit.

---

### 8.4 `textcheck` — spacing and unrenderable characters

Scans rendered text: the CDATA body of staticText `<text>` elements and the
double-quoted string literals inside every `<expression>` element.

#### 8.4.1 Period-space

- A `.` directly followed by an uppercase letter gets a space
  (`today.Swill` → `today. Swill`).
- Applied **only** to rendered-text contexts — staticText/textField strings,
  parameter `<defaultValueExpression>`, and variable
  `<initialValueExpression>` — so JSON paths and code are not corrupted.

#### 8.4.2 Double-space collapse

- Runs of two or more spaces are collapsed to a single space.
- Applied to string literals everywhere, including leading/trailing runs.

#### 8.4.3 Unrenderable-character replacement

- Characters that commonly fail to render in JasperReports' default fonts are
  replaced with safe ASCII equivalents (or removed). The replacement table is
  fixed and deterministic:

  | Character | Code point | Replacement |
  | --- | --- | --- |
  | non-breaking space | U+00A0 | ` ` |
  | figure space | U+2007 | ` ` |
  | thin space | U+2009 | ` ` |
  | hair space | U+200A | ` ` |
  | narrow no-break space | U+202F | ` ` |
  | zero-width space | U+200B | *(removed)* |
  | soft hyphen | U+00AD | *(removed)* |
  | hyphen | U+2010 | `-` |
  | non-breaking hyphen | U+2011 | `-` |
  | figure dash | U+2012 | `-` |
  | en dash | U+2013 | `-` |
  | em dash | U+2014 | `-` |
  | horizontal bar | U+2015 | `-` |
  | left single quote | U+2018 | `'` |
  | right single quote | U+2019 | `'` |
  | single low-9 quote | U+201A | `'` |
  | reversed-9 single quote | U+201B | `'` |
  | left double quote | U+201C | `"` |
  | right double quote | U+201D | `"` |
  | double low-9 quote | U+201E | `"` |
  | reversed-9 double quote | U+201F | `"` |
  | bullet | U+2022 | `-` |
  | triangular bullet | U+2023 | `-` |
  | ellipsis | U+2026 | `...` |

- Replacement order matters: unrenderable characters are replaced first (a
  non-breaking-space → space can introduce a double space), then the
  period-space fix, then double-space collapse.

#### 8.4.4 Staging behavior

- Approved `textcheck` fixes are left **unstaged**; the step returns 1 to stop
  the commit.

---

### 8.5 `report` — include-chain (informational)

Runs last, modifies nothing, and always returns 0 (never stops the commit).

- For each staged (changed) file, prints its include-chain **inverted**: the
  root of each tree is the top-most template that transitively includes the
  staged file; the staged file is the leaf.
- A file `X.jrxml` is "used by" another file `Y.jrxml` when `Y` contains the
  base name `X` as a double-quoted string (the usual Jaspersoft subreport
  reference).
- Grouping:
  - A top template that includes several staged files is shown once, with all
    of them under it.
  - A staged file included by several top templates appears under each of them.
- Staged files are printed green/bold; every other file is plain.
- The tree is rendered with box-drawing branches (`├──`, `└──`).

---

## 9. Step toggles (report-only mode)

`main.go` holds a toggle table:

```go
var stepsEnabled = map[string]bool{
    "commitmsg": true,
    "clear":     true,
    "format":    true,
    "sort":      true,
    "textcheck": true,
}
```

- A step toggled `false` runs in **report-only** mode (`Check`): it lists what
  it would change but does not prompt or apply anything, and never stops the
  commit.
- The `report` step always runs (it never changes files) and has no toggle.
- Changing the toggles requires rebuilding the executable.

---

## 10. Build and install

### Build

```sh
cd myhooks
go build -o myhooks .
```

### Install as raw hooks

```sh
cp myhooks ../.git/hooks/pre-commit
chmod +x ../.git/hooks/pre-commit

printf '#!/bin/sh\nexec myhooks commitmsg "$1"\n' > ../.git/hooks/commit-msg
chmod +x ../.git/hooks/commit-msg
```

The `commit-msg` binary may also be a direct copy of `myhooks` (it detects its
own name and runs the message check with `$1`).

### Install via the pre-commit framework

```yaml
- repo: local
  hooks:
    - id: myhooks
      name: jrxml clear + format + sort + textcheck + report
      entry: myhooks
      language: golang
      files: \.jrxml$
      pass_filenames: true
      require_serial: true
    - id: myhooks-commitmsg
      name: commit message semantic + typo check
      entry: myhooks commitmsg
      language: golang
      stages: [commit-msg]
      always_run: true
```

`require_serial: true` is required because the steps are interactive and must
not run concurrently.
