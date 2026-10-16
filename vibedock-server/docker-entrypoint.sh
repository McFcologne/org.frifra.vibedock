#!/bin/sh
set -eu
umask 077
mkdir -p "$DATA_DIR" "$HOME" "$CODEX_HOME" "$CLAUDE_CONFIG_DIR"
exec java -jar /app/app.jar
