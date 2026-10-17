#!/bin/sh
set -eu

client_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ ! -x "$client_dir/.venv/bin/pio" ]; then
    echo 'PlatformIO is missing. Follow the one-time setup in README.md.' >&2
    exit 1
fi
# The board (PlatformIO environment) to build, default the VibeTV M1; "all" builds every board.
board=${1:-vibedock-m1}
# The C6 uses another platform (Arduino core 3) than the other ESP32 boards. The first build after one of the other
# platform fails while PlatformIO switches the Arduino core; the second goes through.
build() {
    "$client_dir/.venv/bin/pio" run -d "$client_dir/firmware" -e "$1" && return 0
    echo "Building $1 once more (PlatformIO switches the Arduino core)" >&2
    "$client_dir/.venv/bin/pio" run -d "$client_dir/firmware" -e "$1"
}
if [ "$board" = all ]; then
    # Every environment in a run of its own: one run with both platforms would need both Arduino cores at once.
    for env in $(sed -n 's/^\[env:\(.*\)\]$/\1/p' "$client_dir/firmware/platformio.ini"); do build "$env"; done
    exit 0
fi
if ! sed -n 's/^\[env:\(.*\)\]$/\1/p' "$client_dir/firmware/platformio.ini" | grep -qx "$board"; then
    echo "Unknown board $board. Boards (or all):" >&2
    sed -n 's/^\[env:\(.*\)\]$/  \1/p' "$client_dir/firmware/platformio.ini" >&2
    exit 1
fi
build "$board"
