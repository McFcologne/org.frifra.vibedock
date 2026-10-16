#!/bin/sh
# Builds the firmware for every board in firmware/platformio.ini and collects it as the server image expects it:
#   OUT/version, OUT/<board>/flash.bin (installed over USB at 0x0), OUT/<board>/firmware.bin (updates over Wi-Fi)
#   scripts/images.sh OUT [pio]    pio defaults to the one on PATH
set -eu
out=${1:?usage: images.sh OUT [pio]}; pio=${2:-pio}
firmware=$(CDPATH= cd -- "$(dirname -- "$0")/../firmware" && pwd)
mkdir -p "$out"
for board in $(sed -n 's/^\[env:\(.*\)\]$/\1/p' "$firmware/platformio.ini"); do
    "$pio" run -d "$firmware" -e "$board"
    mkdir -p "$out/$board"
    cp "$firmware/.pio/build/$board/flash.bin" "$firmware/.pio/build/$board/firmware.bin" "$out/$board/"
    cp "$firmware/.pio/build/$board/version" "$out/version"
done
