# VibeDock release notes

The release workflow publishes the section of the tagged version (`## 0.1.0` for the tag `v0.1.0` or `0.1.0`) above the list of commits that GitHub generates.

## 0.1.0

The first release of VibeDock: a server that reads how much of your AI coding plans is left and small desk displays
that show it. VibeDock continues an earlier private project and starts its versions anew; displays with that
project's firmware are installed again over USB and paired.

VibeDock is an independent hobby project, not affiliated with Vibe TV (<https://vibetv.shop/>), which sells the
display hardware with its own firmware.

### Highlight: TLS 1.3 on the ESP8266

The VibeTV M1 (ESP8266, about 80 kB RAM, 4 kB stack) now speaks TLS 1.3, with the same trust model as before: the root from pairing, the host name and the certificate's validity are checked, never an unchecked connection. The core's BearSSL only has TLS 1.2, so the M1 runs Mbed TLS 3.6. What made it fit:

- the 16 kB receive buffer in otherwise unused instruction RAM (a second heap of 20 kB),
- the handshake on its own 4.5 kB stack, freed afterwards with the server's certificates,
- the server's certificates parsed without a copy, the TLS contexts only while connected, closed connections without two minutes of TIME_WAIT,
- after one full check the server certificate pinned: later handshakes skip the RSA check of the root (about 1.2 s and the memory that was short),
- 160 MHz, AES-128-GCM only, no Wi-Fi modem sleep.

A handshake takes about 2 s with the full check and less with the pinned certificate; an image of 115 kB about 3 s. Tested against a Synology reverse proxy with TLS 1.3 only, at home and over a phone hotspot. [vibedock-client/TLS.md](vibedock-client/TLS.md) describes the way there, including the dead ends.

What the M1 supports over TLS 1.3, and what not:

| | Supported | Not supported |
| --- | --- | --- |
| Cipher suite | `TLS_AES_128_GCM_SHA256` (every TLS 1.3 server must offer it, RFC 8446) | AES-256-GCM, ChaCha20-Poly1305 |
| Key exchange | X25519, P-256, P-384 | P-521, FFDHE, post-quantum hybrids (`X25519MLKEM768`) alone; a server must also accept a classic group |
| Server certificate | RSA up to 4096 bit (RSA-PSS and PKCS#1 v1.5 signatures), ECDSA P-256/P-384, SHA-256/384 | Ed25519/Ed448, RSA above 4096 bit, P-521 |
| Session | a full handshake per pull | session resumption (tickets are ignored), 0-RTT, client certificates |

The ESP32 boards still use TLS 1.2; a server with TLS 1.3 only is not reachable from them yet.

### Displays and firmware

- **Boards:** the VibeTV M1 (ESP8266, 240×240) and ESP32, ESP32-S3 and ESP32-C3 boards with a display (Cheap Yellow Display, LilyGO T-Display and T-Display-S3, Waveshare ESP32-S3-LCD-1.69, ESP32-C3 with ST7789). The ESP32 builds are not yet tested on hardware and speak TLS 1.2.
- **Installation from the browser** (Chrome or Edge) over USB after a full backup of the display, then **pairing over the same cable**: name, Wi-Fi from a searchable list of the networks the display sees (*Refresh networks* scans again), password. Pairing with a code from the display's own setup Wi-Fi works as well.
- **Updates over Wi-Fi**, signed with the server's key; the running firmware stays if anything fails. Only newer firmware is offered, never a downgrade. An **own firmware build** can be uploaded per board and replaces the built-in one until you go back.
- **Robust pulls:** the display tries the address that answered last, switches to the other one right away after a failure, retries a stalled answer, works behind reverse proxies that drop `Content-Length`, and keeps Wi-Fi modem sleep off.
- **Diagnostics on the display and over USB:** the problem screens say why (DNS, TCP, the TLS error, a stalled answer) and which address failed; the device log over USB keeps the last 24 events with TLS, memory and crash details. Reading it or flashing does not count as a power cycle.
- **Web displays:** a browser, tablet or program shows the image without hardware, as a full-screen page or a PNG link protected by a renewable secret.

### Display and design

- A **design editor** for the default and per-device designs: up to four usage bands (also compact), split bands, weather and mood, display types from 240×240 upwards; bands a display cannot show are marked with a one-click fix.
- **Thirteen colour schemes**; quota bars change gradually from green to red. The server draws the whole image including the device IP.

### Data sources

- Claude, Codex, Grok, Gemini and Cursor (team Admin API), several accounts per provider, pausing, and an **encrypted export and import** of all sources with their sign-ins.

### Administration and operation

- Administration with overview, devices, display, data sources, firmware and settings; light and dark appearance.
- **User manual** with screenshots of every page, the problem screens, the display log and a troubleshooting chapter. The Docker build makes it as PDF for its own version, ending with every third-party component of that build.
- Docker image for amd64 and arm64 with the firmware of the same commit; `scripts/image.sh` saves it as a file with the single tag `vibedock-server:<arch>` (Synology: *Container Manager › Image › Import*).
- Licensed under the **Apache License 2.0**.
