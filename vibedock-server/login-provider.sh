#!/bin/sh
set -eu
umask 077
case "${1:-}" in
  codex) exec codex -c 'cli_auth_credentials_store="file"' login --device-auth ;;
  claude) exec claude auth login --claudeai --sso ;;
  codex-logout) exec codex -c 'cli_auth_credentials_store="file"' logout ;;
  claude-logout) exec claude auth logout ;;
  *) echo 'Usage: /app/login-provider.sh codex|claude|codex-logout|claude-logout' >&2; exit 2 ;;
esac
