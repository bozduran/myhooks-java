#!/bin/sh
# Removes the pre-commit hook installed by scripts/install-hooks.sh, restoring
# any hook that was backed up.
#
# It also retires the commit-msg hook installed by older myhooks versions:
# commit messages are now checked by the gitlint pre-commit hook rather than by
# myhooks.
#
# Usage: scripts/deactivate-hooks.sh [target-repo]
#   target-repo defaults to the current directory.
#
# Hooks that myhooks did not install are left untouched.
set -eu

TARGET="${1:-$(pwd)}"
HOOKS_DIR="$TARGET/.git/hooks"

if [ ! -d "$HOOKS_DIR" ]; then
    echo "error: $HOOKS_DIR not found; is $TARGET a git repository?" >&2
    exit 1
fi

remove_hook() {
    hook="$1"
    path="$HOOKS_DIR/$hook"
    backup="$path.myhooks-backup"

    if [ ! -f "$path" ]; then
        echo "no $hook installed"
        return
    fi
    if ! grep -q "myhooks-hook:" "$path" 2>/dev/null; then
        echo "skipped $hook (not installed by myhooks)"
        return
    fi

    rm -f "$path"
    if [ -f "$backup" ]; then
        mv "$backup" "$path"
        echo "restored previous $hook"
    else
        echo "removed myhooks $hook"
    fi
}

remove_hook pre-commit
# Legacy: older myhooks versions also installed a commit-msg hook.
remove_hook commit-msg

echo "myhooks hooks deactivated in $HOOKS_DIR"
