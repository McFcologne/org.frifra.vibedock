#!/bin/sh
set -eu
umask 077
# The helper stages credentials in RAM and encrypts the updated CLI files before returning.
case "${1:-}" in
  codex|claude|codex-logout|claude-logout)
    exec java -Dloader.main=org.frifra.vibedock.server.CredentialCli -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$1" ;;
  *) echo 'Usage: /app/login-provider.sh codex|claude|codex-logout|claude-logout' >&2; exit 2 ;;
esac
