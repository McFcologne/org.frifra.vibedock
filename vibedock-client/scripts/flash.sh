#!/usr/bin/env bash
# Builds the VibeTV M1 (ESP8266) firmware, finds the serial port and the flash size, and writes it after a confirmation.
# Other boards: install them from the administration (Devices → Firmware) or with its esptool commands.
# Only the firmware at 0x000000 is written; settings and pairing in LittleFS stay. Backups: see FLASHING.md.
#   scripts/flash.sh          build, show what will be written, ask, flash
#   scripts/flash.sh --yes    without the question
set -euo pipefail
shopt -s nullglob

client_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
python="$client_dir/.venv/bin/python"
firmware="$client_dir/firmware/.pio/build/vibedock-m1/firmware.bin"
[ -x "$python" ] || { echo "PlatformIO/esptool missing. Follow the one-time setup in README.md." >&2; exit 1; }

# 1. Build, so the current sources are flashed and never an old binary.
"$client_dir/scripts/build.sh"

# 2. Serial port: VIBEDOCK_SERIAL_PORT if set, otherwise every USB serial port where an ESP8266 actually answers.
esptool() { "$python" -m esptool --chip esp8266 --baud 115200 "$@"; }
candidates() {
    local ports=(/dev/cu.usbserial* /dev/cu.wchusbserial* /dev/cu.SLAB_USBtoUART* /dev/cu.usbmodem* /dev/ttyUSB* /dev/ttyACM*)
    [ ${#ports[@]} -gt 0 ] && printf '%s\n' "${ports[@]}"
    return 0
}
port="${VIBEDOCK_SERIAL_PORT:-}"
if [ -n "$port" ] && [ ! -e "$port" ]; then
    echo "VIBEDOCK_SERIAL_PORT=$port does not exist; searching instead." >&2
    port=""
fi
if [ -z "$port" ]; then
    # Wait up to 60 s for a USB serial adapter to appear.
    found=$(candidates)
    if [ -z "$found" ]; then
        echo "Plug in the device (directly, data cable) ..."
        for _ in $(seq 60); do sleep 1; found=$(candidates); [ -n "$found" ] && break; done
        [ -n "$found" ] || { echo "No USB serial port appeared within 60 s." >&2; exit 1; }
        sleep 1
    fi
    esp_ports=(); esp_macs=()
    while IFS= read -r candidate; do
        if lsof "$candidate" >/dev/null 2>&1; then echo "Skipping $candidate: in use by another program." >&2; continue; fi
        # Ask the chip; hard_reset lets devices that are not chosen start normally again.
        mac=$(esptool --port "$candidate" --connect-attempts 2 --after hard_reset read_mac 2>/dev/null | sed -n 's/^MAC: *//p' | head -1)
        if [ -n "$mac" ]; then esp_ports+=("$candidate"); esp_macs+=("$mac"); fi
    done <<< "$found"
    if [ ${#esp_ports[@]} -eq 0 ]; then
        echo "No ESP8266 answered on: $(echo $found)" >&2
        echo "Check the cable, close serial monitors and try again." >&2
        exit 1
    elif [ ${#esp_ports[@]} -eq 1 ]; then
        port="${esp_ports[0]}"
    else
        echo "Several ESP8266 found:"
        for i in "${!esp_ports[@]}"; do echo "  $((i + 1))) ${esp_ports[$i]}  MAC ${esp_macs[$i]}"; done
        read -r -p "Which one? [1-${#esp_ports[@]}] " choice
        [[ "$choice" =~ ^[0-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le ${#esp_ports[@]} ] || { echo "Nothing written."; exit 1; }
        port="${esp_ports[$((choice - 1))]}"
    fi
fi
if lsof "$port" >/dev/null 2>&1; then
    echo "$port is in use by another program (serial monitor?). Close it first." >&2
    exit 1
fi

# 3. Flash size as reported by the chip, unless VIBEDOCK_FLASH_MB is set.
size="${VIBEDOCK_FLASH_MB:-}"
if [ -z "$size" ]; then
    size=$(esptool --port "$port" --after no_reset flash_id 2>/dev/null \
        | sed -n 's/^Detected flash size: \([0-9]*\)MB.*/\1/p' | head -1)
    [ -n "$size" ] || { echo "Could not read the flash size; set VIBEDOCK_FLASH_MB (see FLASHING.md section 2)." >&2; exit 1; }
fi

echo
echo "Port:       $port  (MAC $(esptool --port "$port" --after no_reset read_mac 2>/dev/null | sed -n 's/^MAC: *//p' | head -1))"
echo "Flash size: ${size} MB"
echo "Firmware:   $firmware"
echo "SHA-256:    $(shasum -a 256 "$firmware" | cut -d' ' -f1)"
echo
if [ "${1:-}" != "--yes" ]; then
    read -r -p "Write this firmware to the device now? [y/N] " answer
    [ "$answer" = "y" ] || [ "$answer" = "Y" ] || { echo "Nothing written."; exit 0; }
fi

esptool --port "$port" --after no_reset \
    write_flash --flash_mode dio --flash_freq 40m --flash_size "${size}MB" 0x000000 "$firmware"
echo
echo "Done. Unplug the device and plug it in again to start the new firmware."
