# myhooks (Java) — Refactor User Stories

Work through these **in order**. Each story is a vertical slice: implement it,
run `mvn test` (requires `git` on `PATH`), and — only when green and its
acceptance criteria are met — create the commit shown. Do not bundle stories.

> Workspace: the repository root (this standalone repo; see `EXTRACT.md`).
> All stories touch the Java module at the repo root.

## Current state

- **Done:** US-00 (scaffold), US-01 (`xmlspan`), US-02 (`edit`), US-03 (`textrules`), US-04 (`diffui`), US-05 (`discover`+`git`), US-06 (`step`), US-07 (`jrutil`), US-08 (`commitmsg`), US-09 (`format`), US-10 (`sort`).
- **Extracted:** this is the standalone repository; `reference/` (Go source +
  `SPEC.md` + fixtures) and `EXTRACT.md` are present and the Java module lives
  at the repo root (no `refactor-java/` prefix).
- **Next:** US-11 — `refactor(textcheck): port rendered-text rules on textrules`.

---

### US-00 — Scaffold the Java/Maven module
**As a** developer, **I want** a buildable Maven module with the target package
skeleton, shade fat-jar config, and the plan/story docs, **so that** the Java
refactor has a correct home.

**Scope**: `pom.xml`, `.gitignore`, `README.md`, `PLAN.md`,
`USER_STORIES.md`, `src/main/java/com/myhooks/**/package-info.java`,
`Main.java` stub.

**Acceptance criteria**
- [ ] `pom.xml` targets Java 17, declares JasperReports + Lombok + picocli +
      JLine + JavaParser + java-diff-utils + JUnit 5, and shades a fat jar with
      `Main-Class: com.myhooks.Main` + signature filters.
- [ ] Every package (`discover, git, xmlspan, edit, diffui, step, jrutil,
      textrules, includegraph, steps/*`) exists with a `package-info.java`.
- [ ] `mvn compile` succeeds (network permitting).
- [ ] Docs consistent with the locked decisions.

**Commit**: `chore: scaffold Java/Maven module with shade plugin and plan/stories`

---

### US-01 — `xmlspan`
**As a** step author, **I want** a byte-offset XML index (StAX-backed) with a
Node tree and quote-agnostic attribute spans, **so that** all steps share one
parser and single-quoted attributes are handled.

**Scope**: `src/main/java/com/myhooks/xmlspan/*.java` (+ tests).

**Acceptance criteria**
- [ ] `XmlScanner.scan(byte[])` returns a root `Node` (tag, kind, attrs,
      parent/children, start/end tag byte spans, depth).
- [ ] `kind` resolves `textField`/`staticText`/`frame` from tag name or
      `kind="..."`.
- [ ] `findAttr(name)` returns `Attr{name, valueStart, valueEnd, quote}` for
      `"` and `'`.
- [ ] `directChildren(kind)` returns immediate `<element>` children only.
- [ ] `isRenderedTextContext(node)` walks up to `parent.kind`.
- [ ] Tests on hand-built JRXML fragments; `mvn test` green.

**Commit**: `refactor(xmlspan): add StAX byte-offset XML index`

---

### US-02 — `edit`
**As a** step author, **I want** `Edit`/`EditSet`/`EditMerge` with overlap-safe
apply, **so that** edits compose without corrupting offsets.

**Scope**: `src/main/java/com/myhooks/edit/*.java` (+ tests).

**Acceptance criteria**
- [ ] `Edit{start, end, replacement}` with insert (`end==start`) and delete
      (`replacement.isEmpty()`) semantics.
- [ ] `EditSet.add` validates non-negative offsets; `apply` copies, sorts
      descending, rejects overlaps, splices; never mutates caller data.
- [ ] `EditMerge.mergeSameSpan` combines same-span edits.
- [ ] Tests: replace/insert/delete/multi/overlap-rejected/merge; green.

**Commit**: `refactor(edit): add EditSet and EditMerge`

---

### US-03 — `textrules`
**As a** step author, **I want** pure, I/O-free text and expression transforms,
**so that** `format` and `textcheck` share rules and `JavaExpr` never touches
string/char/text-block content.

**Scope**: `src/main/java/com/myhooks/textrules/*.java` (+ tests).

**Acceptance criteria**
- [ ] `PeriodSpace`, `DoubleSpace`, `Unrenderable` (+ findings), `Newline`
      (markup-aware) as pure functions; replacement table ported.
- [ ] `JavaExpr` uses **JavaParser AST** to format code while leaving string,
      char, and text-block literals untouched (replaces the Go regex tokenizer).
- [ ] Ported `format`/`textcheck` unit tests adapted; green.

**Commit**: `refactor(textrules): add pure text transforms and AST expression formatting`

---

### US-04 — `diffui`
**As a** step author, **I want** diff rendering, the prompt, free-form input,
and color gating in one package with injected I/O, **so that** the terminal UI
is shared and testable.

**Scope**: `src/main/java/com/myhooks/diffui/*.java` (+ tests).

**Acceptance criteria**
- [ ] `DiffRenderer` git-diff style (red `-`, green `+`, trailing-newline rule,
      multi-line hunks via java-diff-utils).
- [ ] `Prompt` (Yes/No/All/SkipFile) using JLine raw terminal with line
      fallback; `Freeform` line-based text prompt.
- [ ] `colorEnabled` false for non-TTY or `NO_COLOR`.
- [ ] Ported PTY/line tests adapted; green.

**Commit**: `refactor(diffui): add terminal UI with prompt and color gating`

---

### US-05 — `discover` + `git`
**As a** step author, **I want** file discovery and git plumbing separated,
memoized, and error-returning, **so that** git runs once and failures are loud.

**Scope**: `src/main/java/com/myhooks/discover/*.java`,
`src/main/java/com/myhooks/git/*.java` (+ tests).

**Acceptance criteria**
- [ ] `GitStaged` (staged/tracked file lists, shell `git`, memoized, errors
      surfaced). No `git add` helper (uniform unstaged).
- [ ] `FileDiscovery.files(args)`: args when given, else staged, then `.jrxml`
      filter.
- [ ] Tests: filter, arg-vs-staged, git-error propagation; green.

**Commit**: `refactor(discover,git): add memoized file discovery and git plumbing`

---

### US-06 — `step`
**As a** step author, **I want** the `Step`/`Fix`/`Group` interfaces and the
shared `Engine`, **so that** the file steps no longer duplicate scaffolding.

**Scope**: `src/main/java/com/myhooks/step/*.java` (+ tests).

**Acceptance criteria**
- [ ] `Step`, `Fix`, `Group`, `Discoverer`, `FileStep`, `Engine`, `Context` as
      in PLAN §4.
- [ ] `Engine` discovers files, prompts per fix-group ("All" = rest of group,
      "SkipFile" = file), accumulates `EditSet`, applies once, atomic-writes,
      aggregates exit code; fresh read per step.
- [ ] Report-only (`check`) lists fixes, never writes, returns 0.
- [ ] Tests: clean (0), changed (1), skipped (0), check-only no-op, git-error
      (1), per-group "All"; green.

**Commit**: `refactor(step): add Step/Fix interfaces and shared engine`

---

### US-07 — `jrutil` (JasperReports wrappers)
**As a** step author, **I want** thin wrappers over the real JasperReports API,
**so that** steps use the engine instead of hand-rolled logic.

**Scope**: `src/main/java/com/myhooks/jrutil/*.java` (+ tests).

**Acceptance criteria**
- [ ] `JrStringUtil` wraps JRStringUtil (xml encode/decode).
- [ ] `JrExpressions` wraps JRExpressionCollector / expression-chunk
      decomposition to list `$F{}`/`$P{}`/`$V{}` tokens with locations.
- [ ] `JrSchema` validates a design against the JRXML XSD (SchemaFactory /
      JRXmlLoader).
- [ ] `JrCompiler` wraps `JasperCompileManager.compileReport`.
- [ ] Unit tests for the wrappers (compile/schema tests may use small valid
      reports); green.

**Commit**: `refactor(jrutil): add JasperReports engine wrappers`

---

### US-08 — `steps/commitmsg`
**As a** developer, **I want** the commit-message step ported to the `Step`
interface, **so that** behavior is preserved (parity with Go).

**Scope**: `src/main/java/com/myhooks/steps/commitmsg/*.java` (+ tests).

**Acceptance criteria**
- [ ] Implements `Step` directly (non-file step); uses `diffui.Prompt`.
- [ ] Conventional Commit semantic check + typo check ported (misspell
      equivalent — use the same misspell dictionary or a bundled word list).
- [ ] Ported tests (valid/invalid subjects, typo correct/skip/no, clean,
      check-only, help); green.

**Commit**: `refactor(commitmsg): port commit-message step to Step interface`

---

### US-09 — `steps/format`
**As a** developer, **I want** `format` reduced to structural and code concerns
on `xmlspan`/`textrules`, **so that** it no longer duplicates text rules or
relies on raw-tag string surgery.

**Scope**: `src/main/java/com/myhooks/steps/format/*.java` (+ tests).

**Acceptance criteria**
- [ ] Discovers fixes as `List<Group>` (one group).
- [ ] `positionType`/`textAdjust` attributes and `<jasperReport name>`
      alignment via `xmlspan.findAttr` (quote-agnostic).
- [ ] Java-expression formatting via `textrules.JavaExpr` (AST).
- [ ] No period/newline rules (owned by `textcheck`).
- [ ] Tests: attributes, name, expression, single-quote case; green.

**Commit**: `refactor(format): port structural/code fixes on xmlspan`

---

### US-10 — `steps/sort`
**As a** developer, **I want** the geometry-sort step ported with coordinate
warnings, **so that** it surfaces bad geometry instead of silently `0`.

**Scope**: `src/main/java/com/myhooks/steps/sort/*.java` (+ tests).

**Acceptance criteria**
- [ ] Band/frame containers, direct children, stable y-then-x sort, overlap
      warnings via `xmlspan.directChildren`.
- [ ] Missing/non-numeric coordinates warn (parse to `OptionalInt`).
- [ ] Ported tests (parse, apply, nested frames/components, overlaps, stable
      sort, process paths); green.

**Commit**: `refactor(sort): port geometry sort with coordinate warnings`

---

### US-11 — `steps/textcheck`
**As a** developer, **I want** `textcheck` to own all rendered-text rules via
`textrules`, **so that** the rule set lives in one place.

**Scope**: `src/main/java/com/myhooks/steps/textcheck/*.java` (+ tests).

**Acceptance criteria**
- [ ] Classifies via `xmlspan.isRenderedTextContext`; transforms via
      `textrules` (period/double-space/unrenderable/newline).
- [ ] Period + newline rules absorbed (moved from `format`).
- [ ] Tests moved from `format` (period/newline) and ported from `textcheck`;
      green.

**Commit**: `refactor(textcheck): port rendered-text rules on textrules`

---

### US-12 — `steps/clear`
**As a** developer, **I want** `clear` decomposed into independent fix-groups
with JR-based unused detection, interactive jsonql fixes, and quote-safe query
handling, **so that** the monolith becomes readable and correct.

**Scope**: `src/main/java/com/myhooks/steps/clear/*.java` (+ tests).

**Acceptance criteria**
- [ ] Discovers multiple `List<Group>`: query migration, unused-decl deletion,
      description↔jsonql sync, jsonql rename/removal/addition.
- [ ] Unused-decl detection uses `jrutil.JrExpressions` (ground-truth tokens),
      not regex.
- [ ] Parse model immutable; user decisions in a separate set.
- [ ] jsonql fixes interactive (`diff` + prompt) and unstaged.
- [ ] Query language span via `xmlspan.findAttr` (single-quote safe); migration
      edits language and body atomically.
- [ ] "All" scoped per group.
- [ ] Tests: ported `clear` tests adapted; single-quote query test; interactive
      jsonql tests; green.

**Commit**: `refactor(clear): decompose checks and make jsonql fixes interactive`

---

### US-13 — `steps/validate`
**As a** developer, **I want** an XSD + `JasperCompileManager` validation gate
that runs after edits, **so that** structurally invalid or uncompilable reports
are caught before commit.

**Scope**: `src/main/java/com/myhooks/steps/validate/*.java` (+ tests).

**Acceptance criteria**
- [ ] Implements `Step`/`Discoverer` (or a `FileStep` whose fixes are
      non-interactive reports).
- [ ] XSD validation first, then `JasperCompileManager` on modified files only.
- [ ] Failure warns and stops the commit (exit 1); `MYHOOKS_DISABLE=validate`
      disables it.
- [ ] Tests: valid report passes, structurally-invalid XML and uncompilable
      report fail; green.

**Commit**: `refactor(validate): add XSD and compile validation gate`

---

### US-14 — `includegraph` + `steps/report`
**As a** developer, **I want** the include-graph and informational report ported
onto `discover`/`git` with color gating, **so that** report reuses shared
helpers.

**Scope**: `src/main/java/com/myhooks/includegraph/*.java`,
`src/main/java/com/myhooks/steps/report/*.java` (+ tests).

**Acceptance criteria**
- [ ] `includegraph` builds/inverts/renders the "X used by Y" graph (base-name
      matching, cycle cut, grouping).
- [ ] `steps/report` implements `Step` directly (cross-file); never modifies
      files; always returns 0; colors via `diffui.colorEnabled`.
- [ ] Ported tests (tree build, cycle cut, coloring, integration); green.

**Commit**: `refactor(report): port include-chain report with includegraph`

---

### US-15 — `Main` (picocli CLI)
**As a** developer, **I want** a picocli `Main` dispatching over a
`List<Step>` registry with data-driven order, env toggles, and argument
validation, **so that** adding/reordering steps is a one-line change.

**Scope**: `src/main/java/com/myhooks/Main.java` (+ tests).

**Acceptance criteria**
- [ ] Registry order `clear → format → sort → textcheck → validate → report`
      (+ `commitmsg` registered but not in the pre-commit sequence).
- [ ] `commit-msg` basename detection preserved (runs `commitmsg` with `$1`).
- [ ] Unknown first argument → error + usage.
- [ ] `MYHOOKS_DISABLE=format,sort` env toggles steps to report-only.
- [ ] `-h`/`--help` and per-step help via picocli.
- [ ] Tests: dispatch, unknown-arg, env-toggle; green.

**Commit**: `refactor(main): picocli dispatch with env toggles and arg validation`

---

### US-16 — Hook install (script + docs)
**As a** maintainer, **I want** a scripted way to install the fat jar as the
pre-commit and commit-msg hooks, **so that** the standalone project is usable
in any repo.

**Scope**: `scripts/install-hooks.sh`, `.pre-commit-hooks.yaml`, `README.md`.

**Acceptance criteria**
- [ ] `mvn package` produces the fat jar.
- [ ] `scripts/install-hooks.sh` installs a `java -jar ...` wrapper as
      `.git/hooks/pre-commit` and `.git/hooks/commit-msg` (or copies the jar).
- [ ] `.pre-commit-hooks.yaml` provided for the pre-commit framework.
- [ ] `README.md` documents both install methods.

**Commit**: `feat: add hook install script and docs`

---

### US-17 — Final docs + release
**As a** maintainer, **I want** a Java-specific spec and final usage docs,
**so that** the standalone project is self-describing.

**Scope**: `SPEC.md` (Java edition), `README.md`, version bump in `pom.xml`.

**Acceptance criteria**
- [ ] Java `SPEC.md` written: step order (`validate` included), uniform
      unstaged, interactive jsonql, `MYHOOKS_DISABLE`.
- [ ] `README.md` reflects build/run/install and the step list.
- [ ] `mvn package` green.
- [ ] `reference/` removed (or archived) once porting is complete.

**Commit**: `docs: finalize Java spec and usage docs`

---

## Progress tracker

| Story | Done | Commit |
|---|---|---|
| US-00 | ☑ | `chore: scaffold Java/Maven module with shade plugin and plan/stories` |
| US-01 | ☑ | `refactor(xmlspan): add StAX byte-offset XML index` |
| US-02 | ☑ | `refactor(edit): add EditSet and EditMerge` |
| US-03 | ☑ | `refactor(textrules): add pure text transforms and AST expression formatting` |
| US-04 | ☑ | `refactor(diffui): add terminal UI with prompt and color gating` |
| US-05 | ☑ | `refactor(discover,git): add memoized file discovery and git plumbing` |
| US-06 | ☑ | `refactor(step): add Step/Fix interfaces and shared engine` |
| US-07 | ☑ | `refactor(jrutil): add JasperReports engine wrappers` |
| US-08 | ☑ | `refactor(commitmsg): port commit-message step to Step interface` |
| US-09 | ☑ | `refactor(format): port structural/code fixes on xmlspan` |
| US-10 | ☑ | `refactor(sort): port geometry sort with coordinate warnings` |
| US-11 | ☐ | `refactor(textcheck): port rendered-text rules on textrules` |
| US-12 | ☐ | `refactor(clear): decompose checks and make jsonql fixes interactive` |
| US-13 | ☐ | `refactor(validate): add XSD and compile validation gate` |
| US-14 | ☐ | `refactor(report): port include-chain report with includegraph` |
| US-15 | ☐ | `refactor(main): picocli dispatch with env toggles and arg validation` |
| US-16 | ☐ | `feat: add hook install script and docs` |
| US-17 | ☐ | `docs: finalize Java spec and usage docs` |
