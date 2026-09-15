# Using myhooks with the pre-commit framework

This guide shows how to add the **myhooks** JasperReports hook, the **gitlint**
commit-message hook and the **codespell** spell-checker to your own repository
using [pre-commit](https://pre-commit.com/).

When you are done, every commit runs:

| Stage | Hook | What it checks |
| --- | --- | --- |
| `pre-commit` | `myhooks` | `clear` + `format` + `sort` + `textcheck` + `validate` + `lint` + `report` on staged `.jrxml` files |
| `commit-msg` | `gitlint` | Conventional Commit subject; no trailing whitespace/tabs; no double spaces; title/body length |
| `commit-msg` | `codespell` | common misspellings in the commit message |

> Commit messages are no longer checked by `myhooks` itself — the old
> LanguageTool `commitmsg` step was removed. The `commit-msg` checks are now the
> maintained `gitlint` hook (configured by `.gitlint`) and `codespell`.

## Using the published hook from GitHub (recommended)

`myhooks` is published as a pre-commit **remote hook**. You do not copy config
files or build the jar: add the repo to your `.pre-commit-config.yaml` and
pre-commit clones it (the pre-built `bin/myhooks.jar` is committed) and runs it.

```yaml
repos:
  - repo: https://github.com/bozduran/myhooks-java
    rev: v1.0.0
    hooks:
      - id: myhooks

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

Prerequisites for consumers: **Java 17+ on `PATH`** and `pre-commit`. Nothing
else — pre-commit downloads everything automatically the first time a hook runs.

```sh
pre-commit install --hook-type pre-commit --hook-type commit-msg
pre-commit run --all-files
```

The hook's `entry` is `bin/myhooks` with `language: script`, which pre-commit
resolves relative to the *hook repository* checkout — so the consumer's working
directory and the location of the cloned repo never matter.

## Local / self-hosted setup (build the jar yourself)

The rest of this guide describes the older flow where you copy the config files
and point at a jar you built. Prefer the [published hook](#using-the-published-hook-from-github-recommended)
unless you are developing myhooks or need to run an un-published build.

## Prerequisites

- **Java 17+** on `PATH` (the hook runs `java -jar ...`).
- **Maven** to build the `myhooks` fat jar (step 1).
- **Python + pip** — pre-commit creates gitlint's own environment.
- **Git 2.x**.

## 1. Build the jar

```sh
cd /path/to/myhooks-java
mvn package
# -> target/myhooks-1.0.0.jar
```

The jar is gitignored, so it must exist on every machine that runs the hook (or
be placed at a shared location — see [Teams](#teams-and-multiple-machines)).

## 2. Copy the config files into your repo

```sh
cd /path/to/your-repo
cp /path/to/myhooks-java/.gitlint .
cp /path/to/myhooks-java/.pre-commit-config.yaml .
```

`.gitlint` is repository-agnostic: copy it verbatim. If your repository already
has a `.pre-commit-config.yaml`, **merge** the two `repos:` entries from the
[appendix](#appendix-file-contents) instead of overwriting the file.

## 3. Point the hook at the built jar

Edit `.pre-commit-config.yaml` and replace the local hook's `entry` with the
**absolute** path to the jar:

```yaml
entry: java -jar /path/to/myhooks-java/target/myhooks-1.0.0.jar
```

This is the one edit that is always required. A relative `target/...` path is
resolved against **your** repository, not the `myhooks-java` checkout, so it
would fail with "Unable to access jarfile".

The rest of the file can stay as copied:

```yaml
repos:
  - repo: local
    hooks:
      - id: myhooks
        name: myhooks (jrxml clear + format + sort + textcheck + validate + lint + report)
        entry: java -jar /path/to/myhooks-java/target/myhooks-1.0.0.jar
        language: system
        files: \.jrxml$
        pass_filenames: true
        require_serial: true
        stages: [pre-commit]

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

`myhooks` is pinned to `stages: [pre-commit]` so it runs once, on the staged
`.jrxml` files. `gitlint` brings `stages: [commit-msg]` from its own manifest;
`codespell` is pinned to the same stage here.

## 4. Install pre-commit and the git hooks

```sh
python3 -m pip install --user pre-commit    # or: pipx install pre-commit
```

If you previously installed the **raw** myhooks git hooks, remove them first so
the two setups do not fight over `.git/hooks/pre-commit`:

```sh
/path/to/myhooks-java/scripts/deactivate-hooks.sh /path/to/your-repo
```

Now install both hook types from your repository root:

```sh
cd /path/to/your-repo
pre-commit install --hook-type pre-commit --hook-type commit-msg
```

Both are required: `pre-commit` for the `.jrxml` steps and `commit-msg` for
gitlint and codespell.

## 5. Verify

```sh
cd /path/to/your-repo

# 1. Run the jrxml steps over every tracked .jrxml file.
pre-commit run --all-files

# 2. gitlint should reject a non-conventional subject...
printf 'add stuff\n' > /tmp/msg
pre-commit run gitlint --hook-stage commit-msg --commit-msg-filename /tmp/msg

# 3. ...and accept a conventional one.
printf 'feat(report): add a report\n' > /tmp/msg
pre-commit run gitlint --hook-stage commit-msg --commit-msg-filename /tmp/msg

# 4. codespell should flag a misspelling.
printf 'feat(report): add teh report\n' > /tmp/msg
pre-commit run codespell --hook-stage commit-msg --commit-msg-filename /tmp/msg
```

A normal commit then behaves as expected: `add stuff` is blocked,
`feat(report): add a report` is allowed, and `feat(report): add teh report`
fails on the `teh` misspelling.

## What the commit-message checks enforce

`gitlint` (`.gitlint`):

- Conventional Commit subject with the 11 accepted types (CT1).
- No trailing whitespace or hard tabs in the subject or body — the default
  `title-trailing-whitespace` (T2), `body-trailing-whitespace` (B2),
  `title-hard-tab` (T4) and `body-hard-tab` (B3) rules.
- No double spaces in the subject or body — the named `no-double-space` rules.
- Subject ≤ 72 characters, body lines ≤ 100 characters.

`codespell`:

- Common misspellings in the whole message. It does **not** check grammar.
- It **blocks** the commit. Domain words are listed in `args` via `-L`
  (`jrxml`, `jsonql`, …); add more there or in a `.codespellrc` when you get a
  false positive.
- A commit that intentionally contains a misspelling (for example
  `fix: rename recieve to receive`) is rejected too. To only warn instead of
  block, replace the codespell entry with a local advisory hook:

  ```yaml
  - repo: local
    hooks:
      - id: codespell-commit-msg
        name: codespell (advisory)
        entry: sh -c 'codespell -L jrxml,jsonql,jasperreports,subreport "$1" || true' --
        language: system
        always_run: true
        stages: [commit-msg]
  ```

  This needs `codespell` on `PATH` (for example `pipx install codespell`).

## Teams and multiple machines

- The local hook uses `language: system`, so pre-commit never builds or
  downloads the jar. Every machine (and every contributor) needs it at the path
  in `entry`.
- For a stable, machine-independent path, build once and copy the jar somewhere
  shared, then point `entry` at its **absolute** path:

  ```sh
  mkdir -p "$HOME/.local/share/myhooks"
  cp /path/to/myhooks-java/target/myhooks-1.0.0.jar "$HOME/.local/share/myhooks/"
  ```

  ```yaml
  entry: java -jar /home/you/.local/share/myhooks/myhooks-1.0.0.jar
  ```

  pre-commit runs `entry` without a shell, so `~` and `$HOME` are **not**
  expanded — write the full absolute path.
- Keep the gitlint pin current with `pre-commit autoupdate`.
- **Windows:** keep forward slashes and quote paths that contain spaces:

  ```yaml
  entry: java -jar "C:/tools/myhooks/myhooks-1.0.0.jar"
  ```

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `java: command not found` | Java 17+ is not on the `PATH` seen by the hook process. |
| `Unable to access jarfile target/...` | `entry` is still relative; use the absolute jar path (step 3). |
| `myhooks: unknown argument: commitmsg` | A stale raw `commit-msg` hook from an older myhooks install. Run `scripts/deactivate-hooks.sh /path/to/your-repo`. |
| `No such rule 'contrib-title-conventional-commits'` | `.gitlint` is missing or not at the repository root; gitlint reads it from there. |
| pre-commit: `hook id 'myhooks-commitmsg' not found` | That hook id was removed. Delete it from your config and add the gitlint repo instead. |
| gitlint: `Title does not match regex (^(?!.*  ))` | The subject contains two consecutive spaces. |
| gitlint: `Body does not match regex (...)` | The body contains two consecutive spaces. |
| codespell flags a word you use deliberately | Add it to `-L` in `.pre-commit-config.yaml`, or to a `.codespellrc`. |
| codespell blocks a commit that fixes a typo | It blocks by design; use the advisory hook or `git commit --no-verify`. |
| The commit is stopped with "applied" changes | By design: myhooks leaves edits unstaged so you review the diff, then re-stage and commit. |

## Appendix: file contents

### `.pre-commit-config.yaml`

```yaml
# pre-commit configuration.
#
# Install once (myhooks runs on the pre-commit stage, gitlint on the
# commit-msg stage):
#
#   pre-commit install --hook-type pre-commit --hook-type commit-msg
#
# `myhooks` is declared locally so it runs a prebuilt fat jar; run
# `mvn package` in the myhooks-java checkout first.
#
# Commit-message checking is the maintained `gitlint` hook (configured by
# `.gitlint`) plus codespell for spelling.
repos:
  - repo: local
    hooks:
      - id: myhooks
        name: myhooks (jrxml clear + format + sort + textcheck + validate + lint + report)
        entry: java -jar /path/to/myhooks-java/target/myhooks-1.0.0.jar
        language: system
        files: \.jrxml$
        pass_filenames: true
        require_serial: true
        stages: [pre-commit]

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

### `.gitlint`

```ini
# Commit-message rules.
#
# Enforced by the `gitlint` pre-commit hook on the commit-msg stage. This
# replaces the old myhooks `commitmsg` step, which enforced a Conventional
# Commit subject and reported LanguageTool spelling/grammar issues.

[general]
# Enable gitlint's Conventional Commits rule (CT1).
contrib=contrib-title-conventional-commits

# The removed `commitmsg` step validated the subject only, so keep
# subject-only commits valid and short bodies allowed; gitlint otherwise
# requires a body and a body of at least 20 characters.
ignore=body-is-missing,body-min-length

[contrib-title-conventional-commits]
# The accepted commit types.
types=feat,fix,docs,style,refactor,perf,test,build,ci,chore,revert

[title-max-length]
line-length=72

[body-max-line-length]
line-length=100

# No double spaces in the subject or body (gitlint has no built-in rule).
[title-match-regex:no-double-space]
regex=^(?!.*  )

[body-match-regex:no-double-space]
regex=(?s)\A(?!.*  )
```

## Alternative: raw git hooks (no pre-commit framework)

If you do not want to install pre-commit, the repository also ships raw git
hooks:

```sh
cd /path/to/myhooks-java
mvn package
scripts/install-hooks.sh /path/to/your-repo
```

This installs only the `pre-commit` hook. It does **not** provide commit-message
checking — use the pre-commit framework with gitlint and codespell for that. To
remove the raw hooks again, run
`scripts/deactivate-hooks.sh /path/to/your-repo`.
