#!/bin/sh
# Installs the myhooks fat jar as the pre-commit and commit-msg git hooks.
#
# Usage: scripts/install-hooks.sh [target-repo]
#   target-repo defaults to the current directory.
set -eu

PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
JAR="$PROJECT_DIR/target/myhooks-0.1.0-SNAPSHOT.jar"
TARGET="${1:-$(pwd)}"

if [ ! -f "$JAR" ]; then
    echo "error: $JAR not found; run 'mvn package' first" >&2
    exit 1
fi

HOOKS_DIR="$TARGET/.git/hooks"
if [ ! -d "$HOOKS_DIR" ]; then
    echo "error: $HOOKS_DIR not found; is $TARGET a git repository?" >&2
    exit 1
fi

cat > "$HOOKS_DIR/pre-commit" <<EOF
#!/bin/sh
exec java -jar "$JAR" "\$@"
EOF
chmod +x "$HOOKS_DIR/pre-commit"

cat > "$HOOKS_DIR/commit-msg" <<EOF
#!/bin/sh
exec java -jar "$JAR" commitmsg "\$1"
EOF
chmod +x "$HOOKS_DIR/commit-msg"

echo "installed pre-commit and commit-msg hooks in $HOOKS_DIR"
