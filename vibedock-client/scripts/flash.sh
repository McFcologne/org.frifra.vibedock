#!/usr/bin/env bash
# Finds the connected display over USB, builds the firmware of its board and writes it after a confirmation.
# Settings and pairing in LittleFS stay. Backups: see FLASHING.md.
#   scripts/flash.sh [board] [--yes]    without a board: the chip decides, several boards with that chip are offered
#   scripts/flash.sh --list             the boards
# VibeTV M1: firmware.bin at 0x000000 with the detected flash size. ESP32 boards: flash.bin (bootloader, partition
# table and app) at 0x0, as the browser flasher does.
set -euo pipefail
shopt -s nullglob

client_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
ini="$client_dir/firmware/platformio.ini"
python="$client_dir/.venv/bin/python"
[ -x "$python" ] || { echo "PlatformIO/esptool missing. Follow the one-time setup in README.md." >&2; exit 1; }

boards() { sed -n 's/^\[env:\(.*\)\]$/\1/p' "$ini"; }
# custom_hardware of the board's environment: tested on the real board, or prepared (builds, untested).
hardware_of() { awk -v env="[env:$1]" '$0 == env {e = 1; next} /^\[/ {e = 0} e && $1 == "custom_hardware" {print $3; exit}' "$ini"; }
status_of() { [ "$(hardware_of "$1")" = tested ] && echo "tested on hardware" || echo "PREPARED, NOT TESTED on hardware"; }
# The esptool chip of a board, from the PlatformIO board of its environment.
chip_of() {
    case "$(awk -v env="[env:$1]" '$0 == env {e = 1; next} /^\[/ {e = 0} e && $1 == "board" {print $3; exit}' "$ini")" in
        esp12e) echo esp8266 ;;
        esp32-s3*) echo esp32s3 ;;
        esp32-c3*) echo esp32c3 ;;
        esp32-c6*) echo esp32c6 ;;
        esp32*) echo esp32 ;;
    esac
}
board=""; yes=""
for arg in "$@"; do
    case "$arg" in
        --yes) yes=1 ;;
        --list) for b in $(boards); do printf '%-28s %-9s %s\n' "$b" "$(chip_of "$b")" "$(status_of "$b")"; done; exit 0 ;;
        -*) echo "Unknown option $arg" >&2; exit 1 ;;
        *) board="$arg" ;;
    esac
done
if [ -n "$board" ]; then
    boards | grep -qx "$board" || { echo "Unknown board $board. Boards:" >&2; boards | sed 's/^/  /' >&2; exit 1; }
    chip=$(chip_of "$board")
else
    chip=auto
fi

# The board the VibeDock firmware on the device names (VIBEDOCK-READY {"board":...}); empty for other or older firmware.
# Opening the port with DTR and RTS released does not restart the device; it may still be starting after esptool.
reported_board() {
    "$python" - "$1" <<'PY' 2>/dev/null
import json, sys, time
import serial
port = serial.Serial()
port.port, port.baudrate, port.timeout, port.dtr, port.rts = sys.argv[1], 115200, 0.3, False, False
port.open()
end, buffer = time.time() + 8, b""
while time.time() < end:
    port.write(b"VIBEDOCK-HELLO\n")
    buffer += port.read(4096)
    for line in buffer.split(b"\n"):
        at = line.find(b"VIBEDOCK-READY ")
        if at >= 0:
            try: print(json.loads(line[at + 15:].decode(errors="replace")).get("board") or ""); sys.exit(0)
            except ValueError: pass
    buffer = buffer[-4096:]
PY
}
esptool() { "$python" -m esptool --chip "$chip" --baud "${VIBEDOCK_BAUD:-115200}" "$@"; }

# 1. Serial port: VIBEDOCK_SERIAL_PORT if set, otherwise every USB serial port where a (matching) chip answers.
candidates() {
    local ports=(/dev/cu.usbserial* /dev/cu.wchusbserial* /dev/cu.SLAB_USBtoUART* /dev/cu.usbmodem* /dev/ttyUSB* /dev/ttyACM*)
    [ ${#ports[@]} -gt 0 ] && printf '%s\n' "${ports[@]}"
    return 0
}
found="${VIBEDOCK_SERIAL_PORT:-}"
if [ -n "$found" ] && [ ! -e "$found" ]; then
    echo "VIBEDOCK_SERIAL_PORT=$found does not exist; searching instead." >&2
    found=""
fi
if [ -z "$found" ]; then
    # Wait up to 60 s for a USB serial port to appear.
    found=$(candidates)
    if [ -z "$found" ]; then
        echo "Plug in the device (directly, data cable) ..."
        for _ in $(seq 60); do sleep 1; found=$(candidates); [ -n "$found" ] && break; done
        [ -n "$found" ] || { echo "No USB serial port appeared within 60 s." >&2; exit 1; }
        sleep 1
    fi
fi
esp_ports=(); esp_macs=(); esp_chips=()
while IFS= read -r candidate; do
    if lsof "$candidate" >/dev/null 2>&1; then echo "Skipping $candidate: in use by another program." >&2; continue; fi
    # Ask the chip, whatever it is: with a given board's chip esptool stopped on any other chip after it had already
    # put it into its bootloader, without the reset after, so that display stayed dark. hard_reset lets devices that
    # are not chosen start normally again. A port without a chip makes esptool fail; with pipefail that must not end
    # the script.
    answer=$("$python" -m esptool --chip auto --baud "${VIBEDOCK_BAUD:-115200}" --port "$candidate" --connect-attempts 2 \
        --after hard_reset read_mac 2>&1 || true)
    mac=$(sed -n 's/^MAC: *//p' <<< "$answer" | head -1)
    # "Chip is ESP32-S3 (QFN56) ...", "Chip is ESP8266EX", "Chip is ESP32-D0WD-V3 ..."
    case "$(sed -n 's/^Chip is //p' <<< "$answer" | head -1)" in
        ESP8266*) detected=esp8266 ;; ESP32-S3*) detected=esp32s3 ;; ESP32-C3*) detected=esp32c3 ;;
        ESP32-C6*) detected=esp32c6 ;; ESP32-S2*) detected=esp32s2 ;; ESP32*) detected=esp32 ;; *) detected="" ;;
    esac
    if [ -n "$mac" ] && [ "$chip" != auto ] && [ -n "$detected" ] && [ "$detected" != "$chip" ]; then
        echo "Skipping $candidate: $detected, not the $chip of $board." >&2
    elif [ -n "$mac" ]; then esp_ports+=("$candidate"); esp_macs+=("$mac"); esp_chips+=("${detected:-$chip}")
    else echo "No ${board:-ESP} chip on $candidate: $(grep -m1 -iE 'error|fatal' <<< "$answer" || echo 'no answer')" >&2; fi
done <<< "$found"
# Native USB (ESP32-S3/C3/C6) drops off the bus for a moment after the reset.
sleep 2
if [ ${#esp_ports[@]} -eq 0 ]; then
    echo "No ${board:-ESP} chip answered on: $(echo $found)" >&2
    echo "Check the cable, close serial monitors and try again." >&2
    echo "Native USB (ESP32-S3/C3/C6): hold BOOT while plugging in if the chip does not answer." >&2
    exit 1
elif [ ${#esp_ports[@]} -eq 1 ]; then
    pick=0
else
    echo "Several devices found:"
    for i in "${!esp_ports[@]}"; do echo "  $((i + 1))) ${esp_ports[$i]}  ${esp_chips[$i]}  MAC ${esp_macs[$i]}"; done
    read -r -p "Which one? [1-${#esp_ports[@]}] " choice
    [[ "$choice" =~ ^[0-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le ${#esp_ports[@]} ] || { echo "Nothing written."; exit 1; }
    pick=$((choice - 1))
fi
port="${esp_ports[$pick]}"; chip="${esp_chips[$pick]}"
# The others were reset to start their firmware again; the VibeTV M1's USB adapter may leave it in the bootloader.
for i in "${!esp_ports[@]}"; do
    [ "$i" = "$pick" ] || [ "${esp_chips[$i]}" != esp8266 ] && continue
    echo "Not chosen: ${esp_ports[$i]} (esp8266). If its display stays dark, unplug it and plug it in again." >&2
done

# 2. Board: given, or the boards with this chip (one is taken, several are offered).
if [ -z "$board" ]; then
    matching=(); for b in $(boards); do [ "$(chip_of "$b")" = "$chip" ] && matching+=("$b"); done
    if [ ${#matching[@]} -eq 0 ]; then echo "No board with chip $chip in platformio.ini." >&2; exit 1
    elif [ ${#matching[@]} -eq 1 ]; then board="${matching[0]}"
    else
        # A device that already runs VibeDock firmware names its board itself (VIBEDOCK-HELLO over the cable).
        reported=$(reported_board "$port" || true)
        for b in "${matching[@]}"; do [ "$b" = "$reported" ] && board="$b"; done
        [ -n "$board" ] && echo "The device reports its board: $board"
    fi
    if [ -z "$board" ]; then
        echo "Boards with $chip:"
        for i in "${!matching[@]}"; do printf '  %d) %-28s %s\n' "$((i + 1))" "${matching[$i]}" "$(status_of "${matching[$i]}")"; done
        read -r -p "Which board? [1-${#matching[@]}] " choice
        [[ "$choice" =~ ^[0-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le ${#matching[@]} ] || { echo "Nothing written."; exit 1; }
        board="${matching[$((choice - 1))]}"
    fi
fi
build="$client_dir/firmware/.pio/build/$board"
if [ "$chip" = esp8266 ]; then image="$build/firmware.bin"; else image="$build/flash.bin"; export VIBEDOCK_BAUD="${VIBEDOCK_BAUD:-460800}"; fi

# 3. Build, so the current sources are flashed and never an old binary.
"$client_dir/scripts/build.sh" "$board"
if lsof "$port" >/dev/null 2>&1; then
    echo "$port is in use by another program (serial monitor?). Close it first." >&2
    exit 1
fi

# 4. VibeTV M1: flash size as reported by the chip, unless VIBEDOCK_FLASH_MB is set. ESP32 images carry their own.
size=""
if [ "$chip" = esp8266 ]; then
    size="${VIBEDOCK_FLASH_MB:-}"
    if [ -z "$size" ]; then
        size=$( (esptool --port "$port" --after no_reset flash_id 2>/dev/null || true) \
            | sed -n 's/^Detected flash size: \([0-9]*\)MB.*/\1/p' | head -1)
        [ -n "$size" ] || { echo "Could not read the flash size; set VIBEDOCK_FLASH_MB (see FLASHING.md section 2)." >&2; exit 1; }
    fi
fi

echo
echo "Board:      $board ($chip), $(status_of "$board")"
[ "$(hardware_of "$board")" = tested ] || echo "Warning:    this firmware has never run on this board. If it fails, flash the board's previous firmware again."
echo "Port:       $port  (MAC ${esp_macs[$pick]})"
[ -n "$size" ] && echo "Flash size: ${size} MB"
echo "Firmware:   $image"
echo "SHA-256:    $(shasum -a 256 "$image" | cut -d' ' -f1)"
echo
if [ -z "$yes" ]; then
    read -r -p "Write this firmware to the device now? [y/N] " answer
    [ "$answer" = "y" ] || [ "$answer" = "Y" ] || { echo "Nothing written."; exit 0; }
fi

if [ "$chip" = esp8266 ]; then
    esptool --port "$port" --after no_reset \
        write_flash --flash_mode dio --flash_freq 40m --flash_size "${size}MB" 0x000000 "$image"
    echo
    echo "Done. Unplug the device and plug it in again to start the new firmware."
else
    esptool --port "$port" --after hard_reset write_flash 0x0 "$image"
    echo
    echo "Done. The device restarts with the new firmware; if not, press its reset button or plug it in again."
fi
