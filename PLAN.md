# myhooks (Java) — Refactor Plan

> Companion to [`USER_STORIES.md`](USER_STORIES.md). This is the "what and why";
> the stories are the "how, in what order, one commit each".

## 1. Objective

Rewrite `myhooks` in **Java 17 (Maven)** so it can use the real JasperReports
engine, replacing hand-rolled XML/text parsing where the engine is
authoritative. Deliver as a new module in `refactor-java/`, built into a
**fat jar** (`maven-shade`) invoked by the pre-commit and commit-msg hooks.
Story by story, one commit per passing story.

## 2. Locked decisions

| # | Decision | Choice |
|---|---|---|
| 1 | Language/build | Java 17 + Maven + Lombok + `maven-shade` fat jar |
| 2 | Behavior | Carry over all Go decisions (below) |
| 3 | Staging | **All changes uniformly unstaged** (no `git add`) |
| 4 | jsonql fixes | **Interactive + unstaged** |
| 5 | "All" scope | **Per fix-group** |
| 6 | commit-msg hook | **Include commitmsg** (parity with Go) |
| 7 | git access | **Shell out to git** |
| 8 | JasperReports | **Full integration** (see §5) |
| 9 | Libraries now | Lombok, JUnit 5, picocli, JLine, JavaParser, java-diff-utils; **defer GraalVM + PIT** |
| 10 | Step order | `clear → format → sort → textcheck → validate → report` (data-driven) |
| 11 | Artifacts | gitignore build output; no committed binaries |

## 3. Target architecture

```
refactor-java/
├── pom.xml
├── src/main/java/com/myhooks/
│   ├── Main.java                     picocli CLI, dispatch over List<Step>, exit codes
│   ├── discover/FileDiscovery.java   args-vs-staged selection + .jrxml filter
│   ├── git/GitStaged.java            staged/tracked file lists (shell git, memoized)
│   ├── xmlspan/                      XmlScanner.java, Node.java, Query.java
│   ├── edit/                         Edit.java, EditSet.java, EditMerge.java
│   ├── diffui/                       DiffRenderer.java, Prompt.java, Freeform.java
│   ├── step/                         Step, Fix, Group, Discoverer, FileStep, Engine, Context
│   ├── jrutil/                       JasperReports wrappers (see §5)
│   ├── textrules/                    PeriodSpace, DoubleSpace, Unrenderable, JavaExpr, Newline
│   ├── includegraph/                 Graph.java, Invert.java, Render.java
│   └── steps/
│       ├── commitmsg/CommitMsgStep.java
│       ├── clear/ClearStep.java
│       ├── format/FormatStep.java
│       ├── sort/SortStep.java
│       ├── textcheck/TextcheckStep.java
│       ├── validate/ValidateStep.java
│       └── report/ReportStep.java
└── src/test/java/com/myhooks/        one test class per unit
```

## 4. Core abstractions

```java
// step
interface Step { String name(); String usage(); int run(Context, List<String>); int check(Context, List<String>); }

interface Fix { String describe(); Diff diff(); void apply(EditSet); }

class Group { String label; List<Fix> fixes; }   // one "All" scope per group

interface Discoverer { List<Group> discover(Context, Path) throws Exception; }

// FileStep adapts a Discoverer into a Step via Engine (the 5 file steps).
// CommitMsgStep and ReportStep implement Step directly (not file-oriented).

// xmlspan
class Node { String tag, kind; List<Attr> attrs; Node parent; List<Node> children;
             int startTag, endTag; }
record Attr(String name, int valueStart, int valueEnd, char quote) {}
class Query { static List<Node> directChildren(Node, String kind);
              static Optional<Attr> findAttr(Node, String name);       // quote-agnostic
              static boolean isRenderedTextContext(Node);              // walks Parent.kind }

// edit
record Edit(int start, int end, String replacement) {}
class EditSet { void add(Edit); String apply(String raw); }            // copy, sort desc, reject overlap
class EditMerge { static Edit mergeSameSpan(List<Edit>); }
```

The "All" choice applies to the rest of the **current group**; "Skip file"
applies to the file. The five file steps return a single group per fix-kind
(`clear` returns several), which preserves the per-fix-group semantics.

## 5. JasperReports integration (the reason for Java)

| Engine capability | Where used |
|---|---|
| `JRExpressionCollector` / expression chunk decomposition (ground-truth `$F{}`/`$P{}`/`$V{}` tokens) | `clear` unused-declaration detection (replaces regex `isUsed`) |
| `JRStringUtil` (xml encode/decode) | `xmlspan`/`edit` entity handling, kept consistent with the engine |
| JRXML XSD + `SchemaFactory` (or `JRXmlLoader`) | `validate` step: catch structurally-invalid-but-well-formed XML |
| `JasperCompileManager.compileReport` | `validate` step: final compile gate after edits |

`validate` is a new step (step 5) that runs **after** the mutating steps and
**before** `report`: XSD validation first (fast), then `JasperCompileManager`
(slow) on files that were modified. A failure warns and stops the commit
(exit 1); it can be disabled via `MYHOOKS_DISABLE=validate`.

## 6. Behavior (carried over + new)

Carried over from the Go refactor: uniform unstaged, interactive jsonql fixes,
per-fix-group "All", data-driven order, atomic writes, ANSI/`NO_COLOR` gating,
unknown-arg error, coordinate warnings, surface git failures (mutating steps
exit 1; informational warn+0). New: JR-based unused detection, XSD + compile
validation gate, single-quote-safe attribute edits via `xmlspan.Attr`.

## 7. Work packages → stories

| Story | What |
|---|---|
| US-00 | Scaffold (pom, source tree, docs) |
| US-01 | `xmlspan` |
| US-02 | `edit` |
| US-03 | `textrules` |
| US-04 | `diffui` |
| US-05 | `discover` + `git` |
| US-06 | `step` (interfaces + Engine) |
| US-07 | `jrutil` (JR wrappers) |
| US-08 | `steps/commitmsg` |
| US-09 | `steps/format` |
| US-10 | `steps/sort` |
| US-11 | `steps/textcheck` |
| US-12 | `steps/clear` |
| US-13 | `steps/validate` |
| US-14 | `includegraph` + `steps/report` |
| US-15 | `Main` (picocli CLI) |
| US-16 | Pre-commit install docs + hook script |
| US-17 | Final docs + release |

## 8. Test strategy

- JUnit 5; one test class per unit, mirroring `src/main`.
- Unit-test `textrules` and `xmlspan` against hand-built JRXML fragments (the
  foundation, no interactivity).
- Integration tests shell out to `git` (as the Go version did) in temp repos.
- New tests: quote-agnostic `findAttr`, `EditSet` overlap rejection,
  `JavaExpr` literal-safety (AST), color gating, git-error propagation,
  unknown-arg handling, interactive jsonql fixes, per-fix-group "All",
  XSD/compile gate.
- `mvn test` green at the end of every story.

## 9. Commit strategy

One conventional commit per completed story (messages in
[`USER_STORIES.md`](USER_STORIES.md)). `refactor-java/` is self-contained and
carries its port source under `reference/`; see
[`EXTRACT.md`](EXTRACT.md) for moving it to a standalone repo.

## 10. Risks & mitigations

- **JDK/Lombok mismatch**: the local environment has JDK 21/25; the pom
  targets `--release 17`. Lombok's annotation processor must support the
  *build* JDK — bump Lombok if compiling on JDK 25+.
- **JasperReports version**: pinned to `7.0.7`; confirm against Maven Central
  on first build.
- **Shaded signed jars**: strip `META-INF/*.SF|DSA|RSA` (already in the pom).
- **Compile-gate latency**: `JasperCompileManager` is slow; run it only on
  modified files and allow `MYHOOKS_DISABLE=validate`.
- **First build needs network** to resolve dependencies; not verifiable in this
  sandbox (offline).

## 11. Definition of done

All 18 stories pass, `mvn package` produces the fat jar, the behavior in §6 is
implemented and documented, and the standalone repo has its own Java `SPEC.md`
and `README.md` reflecting the step order (`validate` included) and staging
behavior, with `reference/` removed once porting is complete.

## 12. Standalone extraction

`refactor-java/` is a self-contained Maven project intended to become its own
repository. The port source is bundled under `reference/` (original Go code,
`SPEC.md`, fixtures). See [`EXTRACT.md`](EXTRACT.md) for the copy and
`git subtree split` commands.
