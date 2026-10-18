[VibeDock](../../README.md) › **Design: device logs and support files**

# Device logs and support files

Status: steps 1 to 4 implemented on branch `0.2.0`.

Goal: everything needed to analyse a problem of a user, server and displays alike, in one file that is safe to
attach to a public ticket.

## Step 1 – keep the logs read from displays (implemented)

- Every log read over USB on the firmware page (`VIBEDOCK-LOG`) is sent to the server and kept in
  `data/device-logs/<identifier>/<millis>-<usb|net>.json`: time it was read, source, firmware and board, the lines.
- The last 10 logs per display are kept; each holds at most 500 lines, 400 characters per line and 64 kB.
  Control characters, bearer tokens, 64-digit hex values and web display links are removed on saving.
- Logs of a display that is not paired are kept under its identifier as well.
- Removing a display removes its logs.
- *Devices › Logs (n)* lists them, newest first, to view, download as text or delete.
- Endpoints (administration port, signed in, CSRF): `POST /admin/clients/{id}/logs`, `GET /admin/clients/{id}/logs`,
  `GET /admin/clients/{id}/logs/{name}.txt`, `POST /admin/clients/{id}/logs/{name}/delete`.

## Step 2 – the display sends its log itself (implemented)

1. *Devices › Read log* marks the display (a one-time request id with an expiry) and asks it to pull now
   (`POST /pull`, only inside the network fixed at pairing).
2. The image response carries `X-VibeDock-Send-Log: <request id>`.
3. The display uploads its log: `POST /api/v1/clients/{id}/log` with its key, over its configured server address
   (TLS when that is https). The server accepts it only for an open request id, at most 64 kB, and saves it with the
   source `net`.
4. A display that cannot be asked directly (e.g. away from home) sends it with its next regular pull.

Needs firmware newer than 0.1.1; displays with older firmware keep the USB way. Protocol: `CLIENT-PROTOCOL.md`, section 3c.

## Step 3 – encrypted support file (implemented)

- *Devices › Troubleshooting* creates one file (`SupportFile`, endpoints `POST /admin/support/file` and
  `POST /admin/support/open`) with the logs of the last 1–14 days:
  - summary: version, Java and platform, Docker, time zone, uptime, poll interval, number of IP rules, data
    sources and displays, test mode
  - data sources: type, name, paused, status and time of the last reading – no tokens, no account ids
  - displays: identifier, name, board, display type, firmware, state, last pull, address, Wi-Fi update state and
    error, flipped, web display
  - the server log (also the rotated files) and the access log of the chosen days, the newest 3 MB at most, and the
    saved display logs of those days
- Everything passes one redactor (`SupportRedactor`): known secrets from the settings (hand-entered keys, web display
  secrets) replaced literally, bearer tokens, 64-digit hex values, web display links, JWT-like strings,
  `password=`/`token=`/`key=` values and e-mail addresses by pattern; optionally host names and public IP addresses
  (private addresses stay).
- **Encryption:** the server creates a random key in GUID form for every file and encrypts the file with it
  (AES-256-GCM, key derived with PBKDF2 like the data source export in `SourceTransfer`, its own format name as
  associated data). The key is shown once, with a copy button, and not stored. The file (`.vdsupport`) can be
  attached to a public issue; the key goes to the maintainer separately, never into the issue.
- **Description:** the user describes the problem when creating the file (`report`: one line, what happened,
  expected, steps, since when, the displays concerned); it passes the redactor like everything else.
- **Opening a support file:** *Troubleshooting › Open a support file* takes the file and the key, decrypts it in
  memory and shows its contents (description, summary, sources, displays, logs) for analysis, also as raw JSON to
  copy or download; nothing of it is stored.
- Open: a GitHub issue template that asks for version, board, description and whether a support file is attached.

## Step 4 – crash diagnosis (implemented)

- The firmware keeps a small record in RTC memory (no flash writes): boot id, run time, free memory and its
  minimum, Wi-Fi signal and drops, the current phase (`wifi`, `connect`, `tls`, `image`, `draw`, `config`, `log`,
  `ota`, `idle`, `portal`) and the intent of a planned restart (update, setup, factory reset). A check value rejects
  memory after a power cut. After a reset the record is the state of the run before.
- At the start the firmware classifies the reset (`client_policy::startKind`): a software restart with an intent is
  that intent; panic, watchdog, brownout and an unplanned software restart are unexpected. The start line of the log
  and the header `X-VibeDock-Diag` (`CLIENT-PROTOCOL.md` 3f) carry it.
- The server (`DeviceDiagnostics`) keeps the current values in memory and each start per display once by boot id;
  logs keep the start they were sent in. Older firmware sends nothing; nothing depends on it.
- Troubleshooting shows the freshness of the newest log (received time and age, source, its firmware and a mark when
  it differs from the current one) and the last start; *Request current logs* asks all displays (the existing
  request per display) and shows per display received, pending, not reachable now, timed out or USB only.
- The support file starts with a diagnosis overview (`SupportDiagnosis`): observed, suspected and missing, server
  warnings and errors, with server times carrying their offset and device run times in seconds.
- Limits: the ESP8266 keeps its crash record (program counter, cause, address); the ESP32 boards keep reset code and
  phase, but a panic's backtrace is only printed on the serial port. A core dump would need its own flash partition:
  a separate change of the partition table, not part of this step. Decoding needs the ELF of the exact build
  (`ELF_OUT` in `scripts/images.sh`).
