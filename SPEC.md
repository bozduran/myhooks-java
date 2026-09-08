# myhooks (Java) — Software Specification

## 1. Purpose

`myhooks` is a single Git hook for JasperReports `.jrxml` report files
(JasperReports 7.x), written in **Java 17** so it can use the real
JasperReports engine. Installed as the `commit-msg` hook it validates the
commit message; installed as the `pre-commit` hook it enforces a consistent
report convention by inspecting the staged `.jrxml` files and, on approval,
rewriting them in place.

The hook does not block a commit for "cosmetic" reasons it can fix itself
silently: it is **interactive**, shows every change in git-diff style, and lets
the author accept or reject each change.

## 2. Goals

- Require a Conventional Commit subject and flag common misspellings
  (`commit-msg` hook).
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
commitmsg (commit-msg hook) → clear → format → sort → textcheck → validate → report
```

| Step | What it does |
| --- | --- |
| `commitmsg` | Conventional Commit subject + misspell dictionary typo check |
| `clear` | SQL→jsonql query migration, unused declarations, jsonql property fixes, description↔jsonql sync |
| `format` | `positionType`/`textAdjust` attributes, `<jasperReport name>` alignment, Java-expression formatting (AST) |
| `sort` | reorder band/frame `<element>` children by geometry (y then x, stable) |
| `textcheck` | period space, double-space, unrenderable characters, newline normalization |
| `validate` | JRXML XSD validation + `JasperCompileManager` compile gate on modified files |
| `report` | include-chain (informational, never modifies, always returns 0) |

The `commitmsg` step is not part of the pre-commit sequence: it runs only via
the `commit-msg` hook (or an explicit `myhooks commitmsg <message-file>`).

## 4. Command-line interface

```
myhooks [file.jrxml ...]          run all enabled steps on the staged files
myhooks <step> [file.jrxml ...]   run one step (commitmsg|clear|format|sort|textcheck|validate|report)
myhooks commitmsg <message-file>  validate the commit message (commit-msg hook)
myhooks -h | --help               print usage
```

- A leading argument equal to a known step name runs only that step.
- Otherwise all steps run in the data-driven order above.
- An unknown leading argument is an error (usage is printed, exit 2).
- When invoked with the program name `commit-msg`, the `commitmsg` step runs
  automatically with the message file.

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
- On a terminal, arrow-key-selectable options via JLine raw mode; otherwise a
  line-based `y/n/a/s` fallback (with a `/dev/tty` fallback under `git commit`).
- A free-form prompt is used only for the SQL→jsonql migration (the jsonql
  expression) and is always line-based.

## 8. JasperReports integration

The Java port uses the real engine instead of hand-rolled logic:

| Capability | Where used |
| --- | --- |
| `JRExpressionCollector` chunk decomposition | `clear` unused-declaration detection (ground-truth `$F{}`/`$P{}`/`$V{}` tokens, not regex) |
| `JRStringUtil` xml encode | attribute-value edits |
| `JRXmlLoader` (Jackson/Woodstox) | `validate` XSD/structural gate |
| `JasperCompileManager` | `validate` compile gate |
| JavaParser AST | `format` Java-expression formatting (string/char/text-block literals untouched) |

## 9. Environment

- `MYHOOKS_DISABLE=format,sort` — comma-separated steps to run in **report-only**
  (check) mode: they list what would change without prompting or applying and
  never stop the commit. For `validate`, this disables the gate.
- `NO_COLOR` — disables ANSI color output (also disabled for non-TTY output).

## 10. Build and install

```sh
mvn package                     # -> target/myhooks-1.0.0.jar (fat jar)
java -jar target/myhooks-1.0.0.jar --help
scripts/install-hooks.sh /path/to/your/repo
```

See [`README.md`](README.md) for the raw-hook and pre-commit-framework install
methods.
