#!/bin/sh
# Builds every board's images into OUT (OUT/<board>/flash.bin, firmware.bin, OUT/version).
# ELF_OUT=DIR also keeps each board's firmware.elf as DIR/<board>-<version>.elf: the symbols that turn a crash address
# or backtrace of that build back into source lines (see FLASHING.md, "Decoding a crash"). The images do not need it.
# VIBEDOCK_PRUNE=1 (Docker image build) removes what is not needed between boards: each board's build directory and
# the installer copies of the ESP-IDF tools (core dir/tools, already installed into packages; keeps disk use down).
set -eu
out=${1:?usage: images.sh OUT [pio]}; pio=${2:-pio}
firmware=$(CDPATH= cd -- "$(dirname -- "$0")/../firmware" && pwd)
core=${PLATFORMIO_CORE_DIR:-$HOME/.platformio}
prune() { [ "${VIBEDOCK_PRUNE:-0}" = 1 ] || return 0; rm -rf "$core/tools" "$core/.cache/tmp" "$@"; }
mkdir -p "$out"
prune
for board in $(sed -n 's/^\[env:\(.*\)\]$/\1/p' "$firmware/platformio.ini"); do
    "$pio" run -d "$firmware" -e "$board" || "$pio" run -d "$firmware" -e "$board"
    mkdir -p "$out/$board"
    cp "$firmware/.pio/build/$board/flash.bin" "$firmware/.pio/build/$board/firmware.bin" "$out/$board/"
    cp "$firmware/.pio/build/$board/version" "$out/version"
    if [ -n "${ELF_OUT:-}" ]; then mkdir -p "$ELF_OUT"; cp "$firmware/.pio/build/$board/firmware.elf" "$ELF_OUT/$board-$(cat "$out/version").elf"; fi
    prune "$firmware/.pio/build/$board" "$firmware/.pio/libdeps/$board"
done
