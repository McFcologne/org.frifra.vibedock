[VibeDock](../../README.md) › **Design: device logs and support files**

# Device logs and support files

Status: step 1 implemented on branch `0.2.0`; steps 2 and 3 planned.

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

## Step 2 – the display sends its log itself

1. *Devices › Read log* marks the display (a one-time request id with an expiry) and asks it to pull now
   (`POST /pull`, only inside the network fixed at pairing).
2. The image response carries `X-VibeDock-Send-Log: <request id>`.
3. The display uploads its log: `POST /api/v1/clients/{id}/log` with its key, over its configured server address
   (TLS when that is https). The server accepts it only for an open request id, at most 64 kB, and saves it with the
   source `net`.
4. A display that cannot be asked directly (e.g. away from home) sends it with its next regular pull.

Needs new firmware; displays with older firmware keep the USB way.

## Step 3 – encrypted support file

- *Settings › Support* creates one file with:
  - `summary.json`: version, commit, Java and platform, Docker, time zone, uptime, storage state, poll interval,
    number of IP rules
  - `sources.json`: per data source its type, status, last HTTP code or error and time – no tokens, no account ids
  - `devices.json`: per display its identifier, board, display type, firmware, last pull, Wi-Fi update state,
    callback network, paired over HTTP or USB
  - the server log and access log of the chosen time range, and the saved display logs
- Everything passes one redactor (`SupportRedactor`): known secrets from the settings replaced literally, bearer
  tokens, 64-digit hex values, web display links, JWT-like strings, `password=`/`token=` values and e-mail
  addresses by pattern; optionally host names, public IP addresses and Wi-Fi names.
- **Encryption:** the server creates a random key in GUID form for every file and encrypts the file with it
  (AES-256-GCM, key derived with PBKDF2 like the data source export in `SourceTransfer`, its own format name as
  associated data). The key is shown once, with a copy button, and not stored. The file (`.vdsupport`) can be
  attached to a public issue; the key goes to the maintainer separately, never into the issue.
- **Opening a support file:** *Settings › Support › Open support file* takes the file and the key, decrypts it in
  memory and shows its contents (summary, sources, displays, logs) for analysis; nothing of it is stored.
- A GitHub issue template asks for version, board, description and whether a support file is attached.
