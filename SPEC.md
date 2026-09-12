# myhooks (Java) — Software Specification

## 1. Purpose

`myhooks` is a single Git hook for JasperReports `.jrxml` report files
(JasperReports 7.x), written in **Java 17** so it can use the real
JasperReports engine. Installed as the `pre-commit` hook it enforces a
consistent report convention by inspecting the staged `.jrxml` files and, on
approval, rewriting them in place. Commit-message validation is not part of
this tool: it is delegated to `gitlint` through the pre-commit framework (see
`.pre-commit-config.yaml` and `.gitlint`).

The hook does not block a commit for "cosmetic" reasons it can fix itself
silently: it is **interactive**, shows every change in git-diff style, and lets
the author accept or reject each change.

## 2. Goals

- Remove dead declarations (unused `<parameter>`/`<field>`/`<variable>`).
- Migrate SQL `<query>` elements and legacy
  `net.sf.jasperreports.json.field.expression` properties to the jsonql
  convention.
- Normalize report text, Java expressions, and required element attributes.
- Keep XML document order consistent with visual (reading/paint) order.
- Fix text that renders incorrectly (missing space, double spaces,
  unrenderable characters, newline representation).
- Validate each modified report against the JRXML schema and the
  JasperReports compiler before commit.
- Report which top-level templates transitively include each changed file.

## 3. Step order

```
clear → format → sort → textcheck → validate → lint → report
```

| Step | What it does |
| --- | --- |
| `clear` | SQL→jsonql query migration, unused declarations, jsonql property fixes, description↔jsonql sync |
| `format` | `positionType="Float"` on textField/subreport, `textAdjust="StretchHeight"` on textField, `<jasperReport name>` alignment, Java-expression formatting (AST) |
| `sort` | reorder band/frame `<element>` children by geometry (y then x, stable) |
| `textcheck` | period space, double-space, unrenderable characters, newline normalization |
| `validate` | JRXML XSD validation + `JasperCompileManager` compile gate on modified files |
| `lint` | static-analysis warnings (constant `printWhenExpression`, unchecked null dereference, missing `removeLineWhenBlank="true"` on textField/subreport); informational, never modifies, always returns 0 |
| `report` | include-chain (informational, never modifies, always returns 0) |

The step order above is the complete pre-commit sequence. Commit-message
checking is out of scope for this tool and is handled by the `gitlint`
`commit-msg` hook wired in `.pre-commit-config.yaml`.

## 4. Command-line interface

```
myhooks [file.jrxml ...]          run all enabled steps on the staged files
myhooks <step> [file.jrxml ...]   run one step (clear|format|sort|textcheck|validate|lint|report)
myhooks -h | --help               print usage
```

- A leading argument equal to a known step name runs only that step.
- Otherwise all steps run in the data-driven order above.
- An unknown leading argument is an error (usage is printed, exit 2).

### File selection

1. If file arguments are given, they are the candidates.
2. Otherwise, candidates are the staged files from
   `git diff --cached --name-only --diff-filter=ACMR`.
3. Candidates ending in `.jrxml` (case-insensitive) are processed.

## 5. Exit codes

| Code | Meaning |
| --- | --- |
| 0 | The commit may continue. |
| 1 | At least one change was left **unstaged** for review, or an error occurred. |
| 2 | Unknown argument. |

- Every mutating step returns 1 when it applied changes (left unstaged) or hit
  an error. `report` always returns 0.
- All steps run regardless of each other's results, so the author reviews all
  changes in one pass; any step returning 1 makes the overall exit code 1.

## 6. Staging behavior

**All changes are uniformly unstaged.** There is no `git add` anywhere. Any
applied change — including jsonql fixes (which the Go original auto-staged) —
is left unstaged and stops the commit, so the author reviews the diff and
re-commits.

## 7. Interactive prompt model

The shared prompt offers four choices per fix:

| Choice | Meaning |
| --- | --- |
| **Yes** | Apply this single fix. |
| **No** | Skip this single fix. |
| **All** | Apply this fix and the rest of its **group**. |
| **Skip file** | Leave the rest of the current file untouched. |

- Default answer is **No**.
- On a terminal, arrow-key-selectable options via a platform raw-mode terminal:
  POSIX opens `/dev/tty` and enters raw mode with `stty`; Windows opens
  `CONIN$`/`CONOUT$` and uses `SetConsoleMode` (VT input/output). Otherwise a
  line-based `y/n/a/s` fallback is used.
- If no controlling terminal can be opened at all, the prompt throws
  `NoTerminalException` and the hook **blocks** with a clear error instead of
  silently answering the default No. (git runs hooks with stdin bound to
  `/dev/null` on POSIX and `NUL` on Windows, so reading stdin would otherwise
  make every prompt decline instantly.)
- A free-form prompt is used only for the SQL→jsonql migration (the jsonql
  expression) and is always line-based.

### Output layout

- Each file is announced with an `=` header naming the step and file
  (`format · path/Foo.jrxml`), including files with no fixes; a fix-free file
  then prints `[ok] path` under that header.
- Each fix-group prints under a dashed sub-banner with its fix count
  (`positionType (3)`), so `positionType` and `textAdjust` are visibly separate
  questions.
- Diffs show the absolute file line number in a gutter. The red/green background
  covers the whole changed line — indentation, `-`/`+` marker, separating space,
  code, and padding out to the output width.
- Output width and rule width come from `COLUMNS`, clamped to 40–100 (default
  52).
- Each step prints `applied X, skipped Y, Z file(s) stopped`; a full run prints
  a final `total:` line. `applied` counts applied fixes, `skipped` counts
  declined fixes plus skipped files, and `stopped` counts written or failed
  files.

## 8. JasperReports integration

The Java port uses the real engine instead of hand-rolled logic:

| Capability | Where used |
| --- | --- |
| `JRExpressionCollector` chunk decomposition | `clear` unused-declaration detection (ground-truth `$F{}`/`$P{}`/`$V{}` tokens, not regex) |
| `JRStringUtil` xml encode | attribute-value edits |
| `JRXmlLoader` (Jackson/Woodstox) | `validate` XSD/structural gate |
| `JasperCompileManager` | `validate` compile gate |
| JavaParser AST | `format` Java-expression formatting (string/char/text-block literals untouched) |

**Trust boundary.** The `validate` compile gate runs in the hook's JVM and
resolves the classes a report declares (`class="..."`, expression types) with
the JVM's class loaders, which also runs their static initializers. Only classes
already present on the hook's own classpath can be reached: a reference to a
class that is not on the classpath fails compilation and blocks the commit. The
hook classpath and the report repository are therefore trusted inputs; run the
hook in an environment without credentials or network access you would not grant
to the repository. Class loading cannot be restricted to an allow-list without
rejecting legitimate reports that reference project classes, so this is accepted
by design rather than sandboxed.

## 9. Environment

- `MYHOOKS_DISABLE=format,sort` — comma-separated steps to run in **report-only**
  (check) mode: they list what would change without prompting or applying and
  never stop the commit. For `validate`, this disables the gate.
- `NO_COLOR` — disables ANSI color output (also disabled for non-TTY output).

## 10. Build and install

```sh
mvn package                     # -> target/myhooks-1.0.0.jar (fat jar)
java -jar target/myhooks-1.0.0.jar --help

scripts/install-hooks.sh /path/to/your/repo          # Linux/macOS hooks
scripts/deactivate-hooks.sh /path/to/your/repo       # remove them again
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-hooks.ps1 C:\path\to\your\repo
powershell -ExecutionPolicy Bypass -File scripts\deactivate-hooks.ps1 C:\path\to\your\repo
```

Installation backs up a pre-existing hook to `<hook>.myhooks-backup` the first
time; deactivation restores it (or removes the hook) only when the hook carries
the myhooks marker, so foreign hooks are never touched.

See [`README.md`](README.md) for the raw-hook and pre-commit-framework install
methods.
