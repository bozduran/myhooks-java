# myhooks (Java)

A single Git hook for JasperReports `.jrxml` files (JasperReports 7.x), written
in Java so it can use the real JasperReports engine. Installed as the
`pre-commit` hook it runs the file-check steps on the staged files.

Commit messages are **not** handled by this tool. The pre-commit framework
checks them with [gitlint](https://jorisroovers.com/gitlint/) (Conventional
Commits, whitespace and spacing rules) and
[codespell](https://github.com/codespell-project/codespell) (spelling). This
replaced the old LanguageTool-based `commitmsg` step.

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

## Publishing releases

The remote hook is the built fat jar, committed as `bin/myhooks.jar` and driven
by the `bin/myhooks` wrapper. pre-commit clones the repository (there is no
separate download step and no Maven build on the consumer side), so every
release is:

```sh
mvn package
cp target/myhooks-1.0.0.jar bin/myhooks.jar

git add bin/myhooks.jar bin/myhooks .pre-commit-hooks.yaml
git commit -m "build: ship myhooks 1.0.0 fat jar"
git tag v1.0.0
git push origin master --tags
```

Consumers then pin that tag:

```yaml
repos:
  - repo: https://github.com/bozduran/myhooks-java
    rev: v1.0.0
    hooks:
      - id: myhooks
```

The jar is ~18 MB because it embeds the JasperReports engine and its
dependencies. If you would rather not commit a binary, the alternative is the
`coursier` language (needs `cs` on the consumer's `PATH` plus the artifacts on
Maven Central) — heavier setup, no binary in git.

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

#### Using the published hook from GitHub (recommended)

`myhooks` is published as a pre-commit remote hook. Consumers only need the
config below plus Java 17+ on `PATH` — pre-commit clones this repository
(which ships the pre-built `bin/myhooks.jar`) and runs it, no Maven build
needed:

```yaml
repos:
  - repo: https://github.com/bozduran/myhooks-java
    rev: v1.0.0
    hooks:
      - id: myhooks

  # commit-message checking (not part of myhooks)
  - repo: https://github.com/jorisroovers/gitlint
    rev: v0.19.1
    hooks:
      - id: gitlint
  - repo: https://github.com/codespell-project/codespell
    rev: v2.4.1
    hooks:
      - id: codespell
        stages: [commit-msg]
        args: ["-L", "jrxml,jsonql,jasperreports,subreport"]
```

The hook entry is `bin/myhooks` with `language: script`, so pre-commit resolves
the script and its jar relative to the **hook repository** checkout, not the
consumer's working directory. See [Publishing](#publishing-releases) for how the
jar gets there.

#### Local development configuration

This repository's own `.pre-commit-config.yaml` is the development setup:

- a **local** `myhooks` hook that runs `target/myhooks-1.0.0.jar` on staged
  `.jrxml` files (run `mvn package` first),
- the maintained [gitlint](https://jorisroovers.com/gitlint/) hook on the
  `commit-msg` stage, configured by `.gitlint` (Conventional Commit subject;
  no trailing whitespace, tabs or double spaces), and
- [codespell](https://github.com/codespell-project/codespell) for spelling in
  the commit message.

```sh
pre-commit install --hook-type pre-commit --hook-type commit-msg
```

For other repositories, prefer the [published hook](#using-the-published-hook-from-github-recommended)
above so contributors never build anything. The local configuration is only for
developing myhooks itself; its `entry` must point at the **built jar** (absolute
path), because `target/` is relative to this checkout. See
[`PRE_COMMIT.md`](PRE_COMMIT.md) for the full local setup.

`require_serial: true` is set because the steps are interactive and must not run
concurrently. `myhooks` no longer provides a `commit-msg` hook: remove
`myhooks-commitmsg` from an existing config and add the gitlint and codespell
repos (as in this repository's config) for commit-message checking.

### Windows

The interactive prompts open the console directly (`CONIN$`/`CONOUT$` plus
Win32 console raw mode), because git runs hooks with stdin bound to `NUL`,
exactly as it uses `/dev/null` on POSIX.

Install the raw hook with the PowerShell installer, which writes it with LF line
endings, no BOM and the executable bit set — that is what keeps the `#!/bin/sh`
shebang valid on Windows:

```powershell
mvn package
powershell -ExecutionPolicy Bypass -File scripts\install-hooks.ps1 C:\src\MyReports
```

`scripts/install-hooks.sh` works too when run from Git Bash, which ships with
Git for Windows. To wire a raw hook by hand, write this to
`.git/hooks/pre-commit` — with **LF** line endings, since a CRLF shebang fails
with `/bin/sh^M: bad interpreter: No such file or directory`:

```sh
#!/bin/sh
exec java -jar "C:/path/to/myhooks-1.0.0.jar" "$@"
```

For the pre-commit framework, run `pre-commit` from **Git Bash** (or put Git's
`usr\bin` on `PATH`): the published hook's entry is a shell script, so
pre-commit has to be able to find `sh.exe`. A local `language: system` hook is
the alternative; keep forward slashes and quote the absolute path, and do not
append a command-shell wildcard such as `%*` — pre-commit runs `entry` directly,
without a shell:

```yaml
entry: java -jar "C:/tools/myhooks/myhooks-1.0.0.jar"
```

The repository's `.gitattributes` forces LF for text files precisely so a
Windows clone cannot turn `bin/myhooks` into a CRLF script.

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
├── .gitattributes                   force LF so the hook scripts stay runnable on Windows
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

- [`PRE_COMMIT.md`](PRE_COMMIT.md) — add the pre-commit + gitlint setup to your repo.
- [`SPEC.md`](SPEC.md) — the Java software specification.
- [`PLAN.md`](PLAN.md) — refactor plan and locked decisions.
- [`USER_STORIES.md`](USER_STORIES.md) — step-by-step stories (one commit each).
- [`EXTRACT.md`](EXTRACT.md) — how this folder was moved to a standalone repo.
