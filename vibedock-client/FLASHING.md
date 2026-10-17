[VibeDock](../README.md) › [Client](README.md) › **Backup & flashing**

# ESP8266 flashing, full backup, and restore

This guide targets ESP8266EX boards such as the VibeDock (240×240 ST7789 display). The flash size is read from each device in section 2 and used for the backup; the VibeDock boards seen so far have 4 MB. It uses **esptool 4.8.1**, 115200 baud, and the client's `esp12e` build configuration. Commands use esptool v4's underscore syntax; do not mix them with v5 examples that use hyphenated names.

**This is an instruction guide, not approval to flash.** Writing either the new firmware or a backup to a physical device requires explicit approval for that device and operation. The existing IntelliJ run configuration builds only and never flashes. The backup procedure (sections 1–3) has been carried out on a real device; flashing and restoring have not.

## Original firmware and personal backups

Redistribution rights for the manufacturer's original firmware have not been established. This project does not provide or redistribute original-firmware images or device backups. Every user must read and verify a full backup from **their own device before flashing it**, and retain it privately for recovery of that same device. Do not use another user's backup or a downloaded original-firmware image as a substitute.

Do not commit backups to Git, attach them to releases, include them in source archives or Docker images, or distribute them with this project. Backups may also contain device-specific credentials and configuration. Restore only a matching backup created from the device being restored.

## 1. Prepare the tools and serial port

Run commands from `vibedock-client/`. Complete the PlatformIO installation in [README.md](README.md), then install the pinned serial tool into the same isolated environment:

```sh
.venv/bin/python -m pip install "esptool==4.8.1"
.venv/bin/python -m esptool version
```

Connect the device using a USB data cable. On macOS, list candidate serial ports (`usbserial`, `wchusbserial` or `SLAB_USBtoUART`; zsh aborts on unmatched patterns, so list all `cu.*` entries):

```sh
ls /dev/cu.*
```

In zsh, do not paste lines with trailing `# comments` from other guides; interactive zsh passes them as arguments.

Set the actual port, checking which entry belongs to this device. The following path was observed on the development Mac; it is not guaranteed on another Mac or after reconnecting:

```sh
VIBEDOCK_SERIAL_PORT="/dev/cu.usbserial-10"
lsof "$VIBEDOCK_SERIAL_PORT"
```

Close serial monitors or companion processes that hold this port. Keep other similar devices disconnected to avoid choosing the wrong unit. If `lsof` shows an owner, release the port before using esptool. The commands below reset the device into its serial bootloader, even for a read operation. Do not guess boot-button or wiring procedures if automatic connection fails; see troubleshooting below.

## 2. Identify the device and create a private backup directory

Create a new directory for every backup. Never overwrite the original backup or an earlier snapshot.

```sh
umask 077
VIBEDOCK_BACKUP_DIR="$PWD/backups/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$VIBEDOCK_BACKUP_DIR"
```

Read device identity and detected flash capacity. These commands do not write flash, but they put the device into bootloader mode. `--after no_reset` keeps application firmware from restarting between the backup steps:

```sh
.venv/bin/python -m esptool --chip esp8266 \
  --port "$VIBEDOCK_SERIAL_PORT" --baud 115200 --after no_reset \
  read_mac

.venv/bin/python -m esptool --chip esp8266 \
  --port "$VIBEDOCK_SERIAL_PORT" --baud 115200 --after no_reset \
  flash_id
```

Set the detected flash size in megabytes (`Detected flash size: 4MB` → `4`). All following commands derive sizes and block counts from it:

```sh
VIBEDOCK_FLASH_MB=4
VIBEDOCK_FLASH_BYTES=$((VIBEDOCK_FLASH_MB * 1048576))
```

Record the reported MAC address and flash information in a private note inside the backup directory, together with the date, port, tool version, and purpose of the backup. Confirm that the detected chip is ESP8266. The client firmware is built for a 4 MB layout (`eagle.flash.4m2m.ld`); do not flash it onto a device with a different flash size, although the backup steps work for any size. During restore, compare the connected device's MAC with this note; the serial path alone is not a reliable identity check.

Backups contain firmware, filesystem, RF/SDK configuration, and potentially Wi-Fi passwords and pairing/device keys. Keep them confidential. `backups/` is excluded from Git.

## 3. Read and verify the entire flash

### 3a. Standard: read in one pass

```sh
.venv/bin/python -m esptool --chip esp8266 \
  --port "$VIBEDOCK_SERIAL_PORT" --baud 115200 --after no_reset \
  read_flash 0x000000 "$VIBEDOCK_FLASH_BYTES" "$VIBEDOCK_BACKUP_DIR/flash-full.bin"
```

At 115200 baud this takes roughly 95 seconds per megabyte. If it aborts with `A fatal error occurred: Corrupt data, expected 0x1000 bytes but received 0xff… bytes`, no file is written; continue with **3b**. Otherwise skip to *Check the size* below.

### 3b. Alternative: read in 1 MB blocks

On the development Mac with the built-in macOS CH340 driver (`cu.usbserial-*`) the one-pass read aborted repeatedly at random positions (7 %, 15 %, 27 %, 70 %, 89 %): the serial link occasionally drops bytes on long transfers. Reading 1 MB blocks keeps a failure local. Each block is verified against the device right after reading (the device compares its own flash digest with the file) and repeated up to five times. On 2026-10-14 this produced a complete, verified backup of a 4 MB device; every block succeeded at the first attempt, about 95 seconds per block plus verification.

```sh
for i in $(seq 0 $((VIBEDOCK_FLASH_MB - 1))); do
  ok=0
  for try in 1 2 3 4 5; do
    .venv/bin/python -m esptool --chip esp8266 --port "$VIBEDOCK_SERIAL_PORT" --baud 115200 --after no_reset \
      read_flash $((i*0x100000)) 0x100000 "$VIBEDOCK_BACKUP_DIR/part$i.bin" \
    && .venv/bin/python -m esptool --chip esp8266 --port "$VIBEDOCK_SERIAL_PORT" --baud 115200 --after no_reset \
      verify_flash $((i*0x100000)) "$VIBEDOCK_BACKUP_DIR/part$i.bin" \
    && ok=1 && break
    echo "part $i try $try failed"
  done
  [ $ok = 1 ] || { echo "part $i FAILED"; break; }
  echo "part $i OK"
done
```

Continue only when every block reports `part N OK`. Then join them in order:

```sh
for i in $(seq 0 $((VIBEDOCK_FLASH_MB - 1))); do cat "$VIBEDOCK_BACKUP_DIR/part$i.bin"; done > "$VIBEDOCK_BACKUP_DIR/flash-full.bin"
```

Notes for this method:

- Do not add `--before no_reset` to the verification. After `read_flash` the flasher stub no longer answers a new connection without reset (`Failed to connect to ESP8266: No serial data received`). The default reset re-enters the serial bootloader; the existing firmware does not run and nothing is written.
- If a block fails five times, try another, short data cable. As a slow last resort add `--no-stub` directly after `esptool` in both commands (several minutes per megabyte).
- Record in `device-note.txt` that the backup was read in blocks.

### Check the size and verify the complete backup

Check the size; it must equal the detected flash size:

```sh
[ "$(stat -f%z "$VIBEDOCK_BACKUP_DIR/flash-full.bin")" = "$VIBEDOCK_FLASH_BYTES" ] && echo "size OK" || echo "size WRONG"
```

Verify the complete dump against the device before allowing its application to run again. `keep` preserves the dump's header parameters; no firmware is written by this command. With method 3b each block is already verified; if this full verification aborts with `Corrupt data`, repeat it:

```sh
.venv/bin/python -m esptool --chip esp8266 \
  --port "$VIBEDOCK_SERIAL_PORT" --baud 115200 --after no_reset \
  verify_flash --flash_mode keep --flash_freq keep --flash_size keep \
  0x000000 "$VIBEDOCK_BACKUP_DIR/flash-full.bin"
```

Create and check a portable SHA256 checksum file. The subshell leaves the current terminal directory unchanged:

```sh
(
  cd "$VIBEDOCK_BACKUP_DIR"
  shasum -a 256 flash-full.bin > flash-full.bin.sha256
  shasum -a 256 -c flash-full.bin.sha256
)
```

Keep the binary, checksum, and device-identity note together, and copy them to a second secure location. Proceed only after the size, device comparison, and checksum checks succeed. A checksum verifies a file's integrity; it does not prove that the file belongs to the connected device.

If stopping after backup, power-cycle the device to leave the bootloader and resume its existing firmware. Application firmware may subsequently change SDK/configuration sectors, so later dumps need not match a previous snapshot byte for byte.

## 4. Build and inspect the candidate firmware offline

Build using the documented IntelliJ configuration or the shared script:

```sh
./scripts/build.sh
VIBEDOCK_FIRMWARE="$PWD/firmware/.pio/build/vibedock-m1/firmware.bin"
.venv/bin/python -m esptool --chip esp8266 image_info "$VIBEDOCK_FIRMWARE"
shasum -a 256 "$VIBEDOCK_FIRMWARE"
```

`image_info` reads only the local file and checks its image structure; it does not connect to the device. Record the candidate's checksum in the backup note. The current PlatformIO board configuration uses **DIO**, **40 MHz** flash, and the project's **4 MB / LittleFS `eagle.flash.4m2m.ld`** layout. CPU frequency is a separate setting; 40 MHz here is the flash frequency.

Use the freshly built `firmware/.pio/build/vibedock-m1/firmware.bin`, not an unrelated binary or an untracked older root-level copy. This guide is for the ESP8266 image at address `0x000000`; it is not an ESP32 partition/bootloader procedure. For the ESP32 boards use *Devices → Firmware* (browser or its esptool commands for the chosen board).

## 5. Flash the candidate — only after explicit approval

**From the server:** *Devices → Firmware* in the administration (Docker image) offers the firmware of the server's version for download, with its SHA-256 and esptool commands, and installs it from Chrome or Edge over USB (Web Serial; the administration must be opened over HTTPS or on `localhost`). The browser path first downloads a full flash backup of the connected device, then writes only the firmware at `0x0` with `dio`/`40m`/`4MB` and restarts the device.

**Quick path for updates:** `scripts/flash.sh` builds the firmware, finds the device's serial port by asking every USB serial port for an ESP8266 (waits up to 60 s for one to be plugged in, skips busy ports, lets you choose by MAC if there are several; `VIBEDOCK_SERIAL_PORT` overrides), reads the flash size from the chip (or uses `VIBEDOCK_FLASH_MB`), shows port, size and SHA-256 of the firmware and writes it only after you confirm. It writes only the firmware at `0x000000`; Wi-Fi, pairing and the setup password stay. Make a backup (sections 2–3) before the first flash of a device; the manual steps below remain the reference. Other boards: `scripts/flash.sh` recognises the chip and offers the boards with it (or `scripts/flash.sh <board>`, `--list` shows them), builds that board and writes `flash.bin` (bootloader, partition table and app) at `0x0`, as the browser flasher does.


Before executing this section, confirm the exact device MAC, validated full backup, candidate checksum, port, and explicit approval to install this candidate. Ensure stable USB power and release the serial port. Stop on any failed check; do not continue with a write.

The following command writes firmware sectors. It does not erase the whole flash beforehand. However, the new firmware may modify settings and the filesystem on first boot; LittleFS initialization can format an incompatible filesystem. Do not rely on old settings remaining usable after the firmware change. The verified full backup is the recovery path.

```sh
.venv/bin/python -m esptool --chip esp8266 \
  --port "$VIBEDOCK_SERIAL_PORT" --baud 115200 --after no_reset \
  write_flash --flash_mode dio --flash_freq 40m --flash_size "${VIBEDOCK_FLASH_MB:?set VIBEDOCK_FLASH_MB (section 2)}MB" \
  0x000000 "$VIBEDOCK_FIRMWARE"
```

If the write aborts (for example with `Corrupt data` or a timeout), do not power off the device and do not boot it: run the same `write_flash` command again. The serial bootloader is in the chip's ROM and cannot be overwritten, so the device can always be written again over USB. esptool checks every written block with an MD5 digest and stops instead of leaving silently corrupted data.

After the write succeeds, verify the installed image with the same header parameters. Keep the application stopped until verification finishes:

```sh
.venv/bin/python -m esptool --chip esp8266 \
  --port "$VIBEDOCK_SERIAL_PORT" --baud 115200 --after no_reset \
  verify_flash --flash_mode dio --flash_freq 40m --flash_size "${VIBEDOCK_FLASH_MB:?set VIBEDOCK_FLASH_MB (section 2)}MB" \
  0x000000 "$VIBEDOCK_FIRMWARE"
```

Power-cycle the device after successful verification. Confirm the screen and permanently visible IP footer. If settings are missing, use the device's setup Wi-Fi and browser portal at `http://192.168.4.1` to enter the Wi-Fi credentials. The device then shows its IP and a pairing code; invite it in the server administration under **Devices** (`http://<host>:10001/admin`).

Do not use `erase_flash`, `--erase-all`, an `uploadfs` target, or a factory OTA route for this procedure. They are not required by these steps. Do not run a flash as an automatic IntelliJ pre-build task.

## 6. Restore a full backup — separate explicit approval required

Restoration overwrites the entire flash image, including filesystem, settings, device keys, RF/SDK configuration, and firmware, with the saved snapshot. It discards changes made since that backup. It requires its own explicit approval for the selected device and backup.

Run from `vibedock-client/`. If using a new terminal, set `VIBEDOCK_SERIAL_PORT` again. Select the directory containing the matching full backup, replacing the example timestamp:

```sh
VIBEDOCK_RESTORE_DIR="$PWD/backups/REPLACE-WITH-BACKUP-TIMESTAMP"
VIBEDOCK_RESTORE_FILE="$VIBEDOCK_RESTORE_DIR/flash-full.bin"
stat -f%z "$VIBEDOCK_RESTORE_FILE"
(
  cd "$VIBEDOCK_RESTORE_DIR"
  shasum -a 256 -c flash-full.bin.sha256
)
```

The size must match the flash size recorded in the backup note (4 MB = 4194304 bytes) and checksum verification must report **OK**. Backups created before this guide was generalized are named `flash-4MB.bin` / `flash-4MB.bin.sha256`; use those names for them. Compare the connected device's MAC and flash capacity with the saved note using `read_mac` and `flash_id` from section 2. Restore only your own verified backup of this exact device. If you have no matching backup, stop; this project does not supply an original-firmware recovery image.

Only then run the full restore. Preserve the original header parameters from the backup instead of applying the candidate firmware's DIO/40 MHz settings:

```sh
.venv/bin/python -m esptool --chip esp8266 \
  --port "$VIBEDOCK_SERIAL_PORT" --baud 115200 --after no_reset \
  write_flash --flash_mode keep --flash_freq keep --flash_size keep \
  0x000000 "$VIBEDOCK_RESTORE_FILE"
```

Verify the complete restored contents before booting the application:

```sh
.venv/bin/python -m esptool --chip esp8266 \
  --port "$VIBEDOCK_SERIAL_PORT" --baud 115200 \
  --after no_reset \
  verify_flash --flash_mode keep --flash_freq keep --flash_size keep \
  0x000000 "$VIBEDOCK_RESTORE_FILE"
```

After successful verification, power-cycle and confirm the original firmware's display and behavior. Saved Wi-Fi and pairing state return to the backup snapshot; settings created later are not retained. A filesystem restore may also revert a standalone client's identifier/key state, so check its server approval if restoring a standalone snapshot.

## Troubleshooting and scope of validation

- **Serial port busy:** close the process reported by `lsof`. Do not kill unrelated processes or try a different device at random.
- **Cannot connect:** verify the USB data cable and exact port. Some hardware requires a documented bootloader procedure using reset/GPIO0. Follow the specific device's procedure rather than improvising wiring.
- **Unexpected chip, capacity, or MAC:** stop and identify the device. The client firmware requires a 4 MB flash; the backup steps adapt to the detected size.
- **`Corrupt data` while reading the backup:** use the block method in section 3b.
- **`Failed to connect … No serial data received` right after a read:** remove `--before no_reset` from the following command.
- **Transfer or verification failure while writing:** keep the backup unchanged and save the error. Repeating the same approved `write_flash` command is covered by that approval; writing a different image or a restore needs its own approval.
- **Image flashed but no expected display/setup network:** record the symptoms and use the verified full restore after approval. Do not erase the filesystem speculatively.

Esptool 4.8.1 command options and the candidate's offline `image_info` output were checked when writing this guide. The `esp12e` board metadata confirms DIO and 40 MHz. On 2026-10-14 a full backup of the development device (ESP8266EX, MAC d8:bf:c0:58:90:d6, 4 MB) was read with method 3b; every block and the joined 4 MB file were verified against the device (`verify OK (digest matched)`). The one-pass read failed repeatedly on that Mac. No flash, restore or physical post-flash test has been executed yet. All commands here are manual and apply to the identified ESP8266 only.

Sources: [Espressif v4 basic commands](https://docs.espressif.com/projects/esptool/en/release-v4/esp32/esptool/basic-commands.html), [Espressif v4 advanced commands](https://docs.espressif.com/projects/esptool/en/release-v4/esp32/esptool/advanced-commands.html), [ESP8266 flash modes](https://docs.espressif.com/projects/esptool/en/latest/esp8266/esptool/flash-modes.html), [PlatformIO ESP-12E board](https://docs.platformio.org/en/stable/boards/espressif8266/esp12e.html). The v4 command reference is shared across supported chips; the commands above explicitly select ESP8266.
