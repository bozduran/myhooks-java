# myhooks (Java)

A single Git hook for JasperReports `.jrxml` files (JasperReports 7.x), written
in Java so it can use the real JasperReports engine. Installed as the
`commit-msg` hook it validates the commit message; installed as the
`pre-commit` hook it runs the file-check steps on the staged files.

## Steps

`commitmsg` (commit-msg hook) → `clear` → `format` → `sort` → `textcheck` →
`validate` (XSD + `JasperCompileManager` gate) → `report`.

Every applied change is left **unstaged** and the hook exits non-zero so you
review the diff and re-commit.

## Build

```sh
mvn package                     # -> target/myhooks-0.1.0-SNAPSHOT.jar (fat jar)
java -jar target/myhooks-0.1.0-SNAPSHOT.jar --help
```

## Install as hooks

### Raw git hooks

```sh
mvn package
scripts/install-hooks.sh /path/to/your/repo
```

This writes a `java -jar ...` wrapper as `/path/to/your/repo/.git/hooks/pre-commit`
and `.git/hooks/commit-msg` (which runs `myhooks commitmsg "$1"`).

### pre-commit framework

Add a local hook referencing `.pre-commit-hooks.yaml`:

```yaml
repos:
  - repo: /path/to/myhooks-java
    hooks:
      - id: myhooks
      - id: myhooks-commitmsg
```

`require_serial: true` is set because the steps are interactive and must not run
concurrently.

## Layout

```
├── pom.xml                          Maven build (Java 17, shade fat jar)
├── src/main/java/com/myhooks/
│   ├── Main.java                    CLI entry (picocli)
│   ├── discover/                    file selection (args vs staged, .jrxml filter)
│   ├── git/                         git plumbing (staged/tracked files)
│   ├── xmlspan/                     byte-offset XML index (StAX)
│   ├── edit/                        Edit + EditSet + EditMerge
│   ├── diffui/                      diff render, prompt, freeform, color gating
│   ├── step/                        Step/Fix/Group interfaces + Engine
│   ├── jrutil/                      JasperReports engine wrappers
│   ├── textrules/                   pure text/expression transforms
│   ├── includegraph/                include-chain graph build/invert/render
│   └── steps/
│       ├── commitmsg/               step 0
│       ├── clear/                   step 1
│       ├── format/                  step 2
│       ├── sort/                    step 3
│       ├── textcheck/               step 4
│       ├── validate/                step 5
│       └── report/                  step 6
├── src/test/java/com/myhooks/       mirrors main, one test class per unit
└── reference/                       read-only port source (Go code + SPEC + fixtures)
```

## Docs

- [`PLAN.md`](PLAN.md) — refactor plan and locked decisions.
- [`USER_STORIES.md`](USER_STORIES.md) — step-by-step stories (one commit each).
- [`EXTRACT.md`](EXTRACT.md) — how this folder was moved to a standalone repo.
- [`reference/`](reference/) — original Go implementation, `SPEC.md`, fixtures.
