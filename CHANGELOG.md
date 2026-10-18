# VibeDock release notes

The release workflow publishes the section of the tagged version (`## 0.1.0` for the tag `v0.1.0` or `0.1.0`) above the list of commits that GitHub generates.

## 0.2.0

New boards, touch, photos and better diagnosis. Update the server image; paired displays then offer the update in
the administration (over Wi-Fi where their firmware can, otherwise once over USB).

- **Waveshare touch displays:** ESP32-S3-Touch-LCD-1.47 (172×320) and ESP32-C6-Touch-LCD-1.69 (240×280) are tested
  on hardware. A swipe left or right changes the theme, up and down the brightness; a double tap starts or stops the
  slideshow, whatever its schedule. The other ESP32 boards are prepared (they build, but have not run on hardware);
  the administration, `flash.sh` and the manual mark both.
- **Sharp web displays:** the page asks for its window's size and pixel ratio, so phones, tablets and TVs get a
  sharp image; *Fit the browser* takes the window's shape. Emoji are drawn from the Twemoji SVGs. Added to the home
  screen of an iPhone, the page has the display icon and opens full screen. Changes appear within seconds, and a
  swipe on the page changes the theme (or the photo).
- **Five usage bands** for tall screens, spread evenly or aligned to the top.
- **Flip image** on the device card turns a display mounted upside down, also its own screens and the swipes.
- **Photos and slideshows:** a design can show uploaded photos instead of the status, one after the other, optionally
  only at certain times of day. The server keeps reduced copies (600 × 600 at most) and cuts them to each display; a
  swipe shows the next photo.
- **Seven new themes:** Windows 11, macOS, a starship console, a handheld LCD, a blueprint, neon and a newspaper
  page. Narrow and rounded displays get their own layout (no clipped text, corners kept free).
- **Administration:** previews in the case of each board, live updates after a swipe, the preview builds up like the
  display, the design editor saves every change right away, a link to each display's own page.
- **Troubleshooting page:** read a display's log over USB from any board (no board to choose) or over the network,
  logs refresh by themselves, and an **encrypted support file** (the user's description of the problem, summary,
  data sources, displays, logs; opened again as sections or raw JSON to copy; secrets removed) for problem reports,
  with the key shown once.
- **Crash diagnosis:** displays report how each start came about (power on, update, reset button, crash, watchdog,
  …), the platform's reset code and the last phase, run time and memory before a reset; each start counts once. After
  a crash, ESP32 boards also send its code addresses (on the C6 a backtrace), the cause and the text of an abort, to
  decode with the ELF without a USB cable. The
  Troubleshooting page shows how old the newest log is and from which firmware, asks all displays for current logs at
  once, and the support file adds a diagnosis per display (observed, suspected, missing). Closing an administration
  page no longer logs "Saving failed".
- **Colours:** calibrated defaults for the VibeTV M1 (gamma 3.20, 100/74/40 %) and the Waveshare boards (1.50,
  100/91/76 %); the device page shows the uncorrected image beside the sliders and applies changes without reloading.
- **Firmware updates** are also offered for another build of the same version (e.g. a local build).
- **`flash.sh`** finds the chip, takes the board the device reports, and asks only when it cannot tell.
- **Test mode for development:** made-up usage and no sign-in (`DEMO_USAGE`, `ADMIN_AUTH=none`), and a quicker Docker
  build without the manual.
- The C6 sends over Wi-Fi without aggregation (A-MPDU): its Wi-Fi driver crashed in `ppResortTxAMPDU` when swipes
  sent requests in quick succession.
- Photos: a very wide or tall photo is cut before it is scaled (no huge image in between), and photos still uploading
  when the editor switches to another display only join the library.
- ESP32 boards stay on Arduino core 2 (core 3 crashed on the ESP32-S3); only the C6 uses core 3. Docker needs room
  for its toolchain: free space in Docker Desktop if the build reports *No space left on device*.

## 0.1.1

Security fixes for the server and the display firmware. Update the server image; displays then offer
**Update to 0.1.1** in the administration.

- **No secrets in the access log:** web display links are logged as `/web/[redacted]`, query strings not at all.
  Access logs written by 0.1.0 can contain web display links; delete them or create new links for your web displays.
- **Password changes sign out every session**, the current one included; the login page says why.
- **Immediate redraws only reach the display's own network.** The server asks a display to pull a new image right
  away only inside the network fixed when it was paired: the subnet mask the display reports (at least `/16`), or
  `/24` around its first address; displays paired over USB get it with their first image pull. Addresses a display
  cannot have (loopback, link-local, unspecified, multicast) are ignored.
- **HTTP pairing shows a warning** that it is unencrypted; **Only pair** over USB is the alternative.
- **Firmware 0.1.1** reports its subnet mask with every image pull, so displays paired before learn their network once.
  Displays with 0.1.0 keep working.

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
