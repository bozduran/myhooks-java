# Using myhooks with the pre-commit framework

This guide shows how to add the **myhooks** JasperReports hook and the
**gitlint** commit-message hook to your own repository using
[pre-commit](https://pre-commit.com/).

When you are done, every commit runs:

| Stage | Hook | What it checks |
| --- | --- | --- |
| `pre-commit` | `myhooks` | `clear` + `format` + `sort` + `textcheck` + `validate` + `lint` + `report` on staged `.jrxml` files |
| `commit-msg` | `gitlint` | Conventional Commit subject (`feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`) |

> Commit messages are no longer checked by `myhooks` itself — the old
> LanguageTool `commitmsg` step was removed. `gitlint` replaces it and is
> configured by `.gitlint`.

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
```

`myhooks` is pinned to `stages: [pre-commit]` so it runs once, on the staged
`.jrxml` files. The `gitlint` hook comes with `stages: [commit-msg]` from its
own manifest.

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
gitlint.

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
```

A normal commit then behaves as expected: `add stuff` is blocked,
`feat(report): add a report` is allowed.

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
# Commit-message checking is the maintained `gitlint` hook, configured by
# `.gitlint`.
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
checking — use the pre-commit framework with gitlint for that. To remove the raw
hooks again, run `scripts/deactivate-hooks.sh /path/to/your-repo`.
