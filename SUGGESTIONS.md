# Suggestions backlog

Ideas captured during the `format`/output work, kept here for later review.
None of the items below are implemented. The items already landed on
`feat/diff-banners-and-tally` are listed first only as context.

Effort: **S** ≈ under an hour, **M** ≈ half a day, **L** ≈ a day or more.

## Already landed (context)

- Step + file header banner (`format · path/Foo.jrxml`) for every file, including
  fix-free files (`[ok]` now sits under a header).
- Group sub-banner with fix counts (`positionType (3)`).
- `COLUMNS`-derived width, clamped 40–100 (default 52).
- Absolute file line numbers in diffs, threaded through every discoverer.
- Whole changed line painted red/green (indent, marker, space, code, padding).
- Per-step tallies and a run-wide `total:` line
  (`applied 4, skipped 2, 1 file stopped`).

## A. Format-step rules and correctness

### A1. Decide the policy for existing non-conforming values — **S**
`positionTypeFix` only fills a *missing* or *empty* attribute, so
`positionType="Fix"` is silently accepted; any `textAdjust` other than
`StretchHeight` (`CutText`, `ScaleFont`) is untouched.
**Where:** `steps/format/FormatDiscoverer.java`.
**Question:** is the house style always `Float`/`StretchHeight`? If yes, add a
rule that proposes changing non-conforming values; if no, document that they
are intentionally out of scope.

### A2. Skip `positionType` inside frames — **S**
`positionType="Float"` is meaningful for band-level children; inside a `<frame>`
the engine typically ignores it. Either stop requiring it there or emit an
informational warning instead of a fix.
**Where:** `steps/format/FormatDiscoverer.java` (needs the element's parent band
context).

### A3. Multi-line tag insertion and attribute order — **M**
The insertion currently appends `" positionType=..."` at a single offset. For a
start tag spread over several lines the attribute can land awkwardly; consider
matching surrounding indentation and/or a canonical attribute order
(`kind, uuid, positionType, x, y, width, height, textAdjust, …`).
**Where:** `steps/format/FormatDiscoverer.java` (`afterAttr`, `beforeClose`).

### A4. Quote-style matching — **S**
Inserted attributes always use `"`. If a document is consistently single-quoted,
consider matching its dominant style.
**Where:** `steps/format/FormatDiscoverer.java`.

### A5. Generalize to a required-attribute rule table — **M**
Only if your convention has more required attributes (missing/empty `uuid`,
`key` on text fields, `mode`, `vTextAlign`, `blankWhenNull`). A table-driven
"required attribute per kind" design would replace the hard-coded
`POSITION_TYPE_KINDS` set and make rules configurable and documentable.
**Where:** new structure under `steps/format/`.

## B. Output and UX

### B1. Banner color / glyph treatment — **S**
The rules and labels are uncolored. Consider a dim rule and a bold title (gated
by the existing `color` flag), consistent with `DiffRenderer`.
**Where:** `diffui/Section.java`, `step/Engine.java`, `diffui/Review.java`.

### B2. Reduce path duplication in status lines — **S**
The header already names the file, yet `[ok] path`, `[skip] path`, `[stop] path`
and `[report] path` repeat it. Shorten them to `[ok]`, `[skip]`, `[stop]`,
`[report]` under the header.
**Where:** `step/Engine.java`.

### B3. Wrap or truncate long headers — **S**
A long path makes the title line exceed the rule width. Consider truncating the
middle of the path (`…/Foo.jrxml`) or wrapping.
**Where:** `diffui/Section.java`, `step/Engine.java` (`title`).

### B4. Per-file summary in the total — **M**
The run total is a single line. A short per-file recap (file → applied/skipped/
stopped) would help reviewers decide what to inspect.
**Where:** `step/Engine.java`, `Main.java`.

### B5. Color gating for piped output — **S**
Color is dropped whenever `System.console()`/TTY is absent, with no override
besides `NO_COLOR`. Consider honoring `CLICOLOR_FORCE` / `FORCE_COLOR` so
`myhooks … | less -R` can keep color.
**Where:** `diffui/DiffRenderer.colorEnabled`, `diffui/Terminals.available`.

## C. CLI and engine

### C1. First-class `--check` / `--dry-run` flag — **S**
Report-only mode is currently reachable only by listing the step in
`MYHOOKS_DISABLE`, which is a confusing overload. A real flag also makes CI
usage sane, since interactive mode blocks without a TTY.
**Where:** `Main.java`, `Step`/`FileStep`.

### C2. `--diff` mode — **M**
Print one unified diff of everything that would change, for non-interactive
review and CI logs.
**Where:** `step/Engine.java`, `Main.java`.

### C3. Config file — **M**
`.myhooks.toml` (or similar) for which rules run, attribute policy per kind,
banner width, and per-step enablement. Would turn the hard-coded
`positionType`/`textAdjust` scope into data.
**Where:** new config package; consumed by discoverers and `Main`.

### C4. `myhooks rules <step>` — **S**
Print the actual rule set (`kind → attribute → expected value → applies when`),
generated from the same registry that drives discovery so docs cannot drift.
**Where:** new command in `Main.java`.

## D. Testing and robustness

### D1. Golden-fixture tests over `examples/` — **M**
`examples/` is now tracked. Add a snapshot test that runs every step over each
example and asserts the exact resulting text. Strongest guard against
behavioral regressions like the `positionType` scoping change.
**Where:** `src/test/java/com/myhooks/`.

### D2. Table-driven kind matrix for format — **S**
One test enumerating every `<element kind=...>` and asserting exactly which
attributes are/are not touched. Locks in the `textField`/`subreport` scoping.
**Where:** `steps/format/FormatDiscovererTest.java`.

### D3. Idempotency as a shared invariant — **S**
`f(f(x)) == f(x)` for every step over every fixture. A partial version exists in
`ExamplesDerivedEdgeCasesTest`; make it a reusable helper so new rules cannot
regress it.
**Where:** test utilities.

### D4. Round-trip well-formedness fuzz test — **M**
Randomize attribute order/quoting/self-closing/whitespace, apply
`FormatDiscoverer`, re-scan with `XmlScanner`, and assert no exception and no
duplicate attributes. Targets the insertion-offset bug class directly (cf. the
self-closing collision already hardened in `afterAttr`).
**Where:** `steps/format/` tests.

### D5. Determinism test for width-dependent output — **S**
The `myhooks.width` system property is a test backdoor used by
`EngineTest`/`OutputTest`. Add a test that output is identical for the same
width, and document the property (or make it package-private test config).
**Where:** `diffui/OutputTest.java`.

## E. Docs and process

### E1. Rule table in `SPEC.md` — **S**
`SPEC.md` gained an output-layout section, but the format rules themselves
(`kind → attribute → expected value → applies when`) are still only implicit in
code and tests. Add the contract table.
**Where:** `SPEC.md`.

### E2. Open decision: `report name` / `expression formatting` grouping — **S**
The format step currently emits four questions (`report name`, `positionType`,
`textAdjust`, `expression formatting`). Decide whether `report name` and
`expression formatting` should stay separate or re-bundle under one "format"
question; it affects `All` semantics.
**Where:** `steps/format/FormatDiscoverer.java`.

### E3. Header rule style decision — **S**
The file header uses `=` rules and group banners use `-` rules so adjacent
banners read as distinct. Confirm this is preferred, and confirm the `·`
separator renders acceptably on Windows consoles.
**Where:** `diffui/Section.java`, `step/Engine.java` (`title`).

## Priority

| Prio | Items |
| --- | --- |
| 1 | A1, A2 (rule scope), D1, D2 |
| 2 | C1 (`--check`), D3, D4, B2 |
| 3 | E1, E3, B1, B3, B5 |
| 4 | A3, A4, C2, B4 |
| 5 | A5, C3, C4 (larger refactors) |
