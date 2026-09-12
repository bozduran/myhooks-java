# myhooks (Java)

A single Git hook for JasperReports `.jrxml` files (JasperReports 7.x), written
in Java so it can use the real JasperReports engine. Installed as the
`pre-commit` hook it runs the file-check steps on the staged files.

Commit messages are **not** handled by this tool. They are checked by
[gitlint](https://jorisroovers.com/gitlint/) through the pre-commit framework
(this repository's `.pre-commit-config.yaml` + `.gitlint`), which replaced the
old LanguageTool-based `commitmsg` step.

## Steps

`clear` → `format` → `sort` → `textcheck` →
`validate` (XSD + `JasperCompileManager` gate) → `lint` (warnings) → `report`.

Every applied change is left **unstaged** and the hook exits non-zero so you
review the diff and re-commit.

## Build

```sh
mvn package                     # -> target/myhooks-1.0.0.jar (fat jar)
java -jar target/myhooks-1.0.0.jar --help
```

## Install as hooks

### Raw git hooks

Linux/macOS:

```sh
mvn package
scripts/install-hooks.sh /home/duran/JaspersoftWorkspace/MyReports
```

Windows (PowerShell):

```powershell
mvn package
powershell -ExecutionPolicy Bypass -File scripts\install-hooks.ps1 C:\src\MyReports
```

Both write a `java -jar ...` wrapper as `/path/to/your/repo/.git/hooks/pre-commit`.
Re-running is safe; the first time an existing hook is taken over it is copied to
`<hook>.myhooks-backup`. A `commit-msg` hook installed by an older myhooks
version is retired automatically, so an upgraded jar cannot break `git commit`.

To remove the hooks again (Linux/macOS `scripts/deactivate-hooks.sh`, Windows
`scripts/deactivate-hooks.ps1`):

```sh
scripts/deactivate-hooks.sh /path/to/your/repo
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deactivate-hooks.ps1 C:\src\MyReports
```

Deactivation only removes hooks carrying the myhooks marker and restores any
`.myhooks-backup`; hooks installed by something else are left untouched.

### pre-commit framework

This repository ships a ready-to-use `.pre-commit-config.yaml`:

- a **local** `myhooks` hook that runs `target/myhooks-1.0.0.jar` on staged
  `.jrxml` files (run `mvn package` first), and
- the maintained [gitlint](https://jorisroovers.com/gitlint/) hook on the
  `commit-msg` stage, configured by `.gitlint` (Conventional Commit subject;
  the allowed types match the old `commitmsg` step).

```sh
pre-commit install --hook-type pre-commit --hook-type commit-msg
```

To consume the hook from another repository, reference this one and its
`.pre-commit-hooks.yaml` manifest:

```yaml
repos:
  - repo: /path/to/myhooks-java
    hooks:
      - id: myhooks
```

`require_serial: true` is set because the steps are interactive and must not run
concurrently. `myhooks` no longer provides a `commit-msg` hook: remove
`myhooks-commitmsg` from an existing config and add the gitlint repo (as in this
repository's config) for commit-message checking.

### Windows

The interactive prompts open the console directly (`CONIN$`/`CONOUT$` plus
Win32 console raw mode), because git runs hooks with stdin bound to `NUL`,
exactly as it uses `/dev/null` on POSIX. Install with
`scripts\install-hooks.ps1` from PowerShell (or `scripts/install-hooks.sh` from
Git Bash, which ships with Git for Windows), or wire the hook entry
manually:

```
pre-commit : java -jar C:\path\to\myhooks-1.0.0.jar %*
```

Prompts need a console attached to the hook process. If there is none (a GUI
git client, a detached CI run), the hook now **blocks with a clear error**
instead of silently declining every fix; set
`MYHOOKS_DISABLE=clear,format,sort,textcheck` to run those steps report-only, or
commit with `--no-verify`.

## Layout

```
├── pom.xml                          Maven build (Java 17, shade fat jar)
├── .pre-commit-config.yaml          local myhooks + gitlint commit-msg hook
├── .gitlint                         Conventional Commit rules for gitlint
├── src/main/java/com/myhooks/
│   ├── Main.java                    CLI entry (picocli)
│   ├── discover/                    file selection (args vs staged, .jrxml filter)
│   ├── git/                         git plumbing (staged/tracked files)
│   ├── xmlspan/                     byte-offset XML index (StAX)
│   ├── edit/                        Edit + EditSet + EditMerge
│   ├── diffui/                      diff render, terminal (POSIX/Windows), prompt, freeform, color gating
│   ├── step/                        Step/Fix/Group interfaces + Engine
│   ├── jrutil/                      JasperReports engine wrappers
│   ├── textrules/                   pure text/expression transforms
│   ├── includegraph/                include-chain graph build/invert/render
│   └── steps/
│       ├── clear/                   step 1
│       ├── format/                  step 2
│       ├── sort/                    step 3
│       ├── textcheck/               step 4
│       ├── validate/                step 5
│       ├── lint/                    step 6 (static-analysis warnings)
│       └── report/                  step 7
└── src/test/java/com/myhooks/       mirrors main, one test class per unit
```

## Docs

- [`SPEC.md`](SPEC.md) — the Java software specification.
- [`PLAN.md`](PLAN.md) — refactor plan and locked decisions.
- [`USER_STORIES.md`](USER_STORIES.md) — step-by-step stories (one commit each).
- [`EXTRACT.md`](EXTRACT.md) — how this folder was moved to a standalone repo.
