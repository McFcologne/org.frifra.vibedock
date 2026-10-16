#!/bin/sh
set -eu

client_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ ! -x "$client_dir/.venv/bin/pio" ]; then
    echo 'PlatformIO is missing. Follow the one-time setup in README.md.' >&2
    exit 1
fi
# The board (PlatformIO environment) to build, default the VibeTV M1; "all" builds every board.
board=${1:-vibedock-m1}
if [ "$board" = all ]; then
    # Every environment explicitly: without -e PlatformIO builds only default_envs (the VibeTV M1).
    set --
    for env in $(sed -n 's/^\[env:\(.*\)\]$/\1/p' "$client_dir/firmware/platformio.ini"); do set -- "$@" -e "$env"; done
    exec "$client_dir/.venv/bin/pio" run -d "$client_dir/firmware" "$@"
fi
exec "$client_dir/.venv/bin/pio" run -d "$client_dir/firmware" -e "$board"
