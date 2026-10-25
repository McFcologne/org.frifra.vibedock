# VibeDock release notes

The release workflow publishes the section of the tagged version (`## 0.1.0` for the tag `v0.1.0` or `0.1.0`) above the list of commits that GitHub generates.

## 0.3.0

About 99 % less data for displays, fifteen new themes and simpler USB pairing. The server sends only the changed
parts of a picture and tells displays when to fetch the next one. The administration adds a gallery of all
thirty-five themes, instance-wide theme availability and a shared Wi-Fi/USB pairing dialog. Touch rotation and
optional, experimental battery power saving extend the firmware.

**Updating:** update the server image first, then update the displays over Wi-Fi from the administration. The new
firmware is needed for delta transfers, scheduled image pulls, the circle gesture, battery power saving and battery
icon, and the Wi-Fi scan and power-cycle fixes. The themes, pairing interface, data-source intervals and preview
fixes are server-side. Older firmware remains supported with complete pictures and its existing polling behaviour;
it can be updated later. Battery power saving is off by default and remains experimental; energy savings have not
been measured.

- **About 99 % less data for displays:** the server still renders the complete picture, but a display with the new
  firmware receives only the parts that changed: usually a few hundred bytes to a few KB instead of 115,200 bytes
  (112.5 KiB) for a complete 240 × 240 picture. Measured on a Waveshare 1.54 with readings every minute: 20 KB
  instead of 1.84 MB in 20 minutes; the saving depends on how much of the picture changes. A complete picture is sent
  after a start, for photos, on a forced refresh, when the previous picture is unavailable or uncertain, or when a
  delta would not be smaller. The server log reports the complete and transferred sizes and the percentage saved.
  Older firmware and older servers keep using complete pictures.
- **Pull when the picture can change:** every answer tells the display when its picture can change next (the
  next update of the sources it shows, a reading turning stale, the next photo), and the display pulls then instead
  of every 30 s, so the unchanged answers in between are gone. Pending source updates get short retries. Waits are
  capped at ten minutes for displays the server has reached directly within the last day, and two minutes otherwise.
  The poll interval under *Settings* is now called *Fallback poll interval*: it applies to older firmware, failed
  pulls and answers without a schedule. Both delta transfers and scheduled pulls need the new server and firmware.
- **Update interval of the data sources:** *Data sources → Update interval* chooses between a fixed and a dynamic
  interval, with a shortest and a longest one (default dynamic, 1 to 10 minutes). Dynamic learns for every source
  how often its provider answers: each HTTP 429 adds a minute, an hour without one tries a minute less, and a 429 at
  that shorter interval waits two, then four hours before the next try. Before, a source halved its interval with
  every successful answer, so after a single 429 the next answer brought it back to one minute and it soon ran into
  the limit again. The card of each source shows its current interval, and the server log every change. Server only.
- **Theme gallery and instance availability:** Display shows all thirty-five themes with previews from the device
  renderer. Choose a theme for the default or a device override, or disable individual themes for new selections
  and swipe cycling across the instance. Existing designs keep their saved theme; at least one stays enabled.
  Availability is stored in `themes.json` in the data directory.
- **Ten new themes:** Mission Control, Departure Board, Nordic, Hi-Fi, Solarized, Pixel Quest, Bauhaus, E-Ink,
  Industrial and Porcelain. All use the existing band layouts and display sizes. Update the server image;
  no firmware update is needed.
- **Five developer themes:** GitHub Dark, VS Code, Terminal, Git Diff and Debugger, with code-style headings,
  reset lines and bars. Their mood emoji sits slightly higher. Server-rendered; no firmware update is needed.
- **Clearer emoji contours:** Handheld LCD outlines icons in dark green; Blueprint adds a light contour and keeps
  eyes and mouths dark enough to read. Icon sizes and positions stay unchanged, including scaled web displays.
- **Pair over USB from the device list:** *Devices → Pair device* switches between **Wi-Fi** (IP and code) and
  **USB**, remembered per browser. USB is the same form as *Firmware → Only pair*, without the detour to the firmware
  page.
- **Choosing a display over USB:** the firmware page and Troubleshooting list the displays allowed in this browser
  before by their name, identifier, board and firmware, as each reports them over USB, instead of the browser's port
  names (*USB JTAG/serial debug unit*). The browser's dialog for a new display lists only the USB chips of the boards.
  Server only.
- **Circle gesture on touch displays:** a circle drawn with one finger turns the picture in that direction, by a
  quarter on the square Waveshare 1.54 and between upright and upside down on the other touch boards. The device
  card turns with it, live. Needs the new server and firmware.
- **Battery power saving (experimental):** displays with a battery connection (Waveshare 1.54, 1.47, C6 1.69,
  S3 1.69, LilyGO T-Display-S3) get a switch on their device card. On battery, without a computer on USB, the display
  uses Wi-Fi modem sleep, runs at 80 MHz and dims its backlight after a minute without touch. USB ends it. Off by
  default; needs the new firmware.
- **Battery icon:** on battery these displays show a small battery at the top right of their picture, on the 1.54
  and the C6 1.69 filled to its level. It goes away on USB.
- **Wi-Fi scan while a saved network is out of reach:** a display that keeps trying a saved network it cannot reach
  (for example when it is paired over USB at another place) found no networks for **Pair now** or its setup portal;
  it now pauses those attempts for the scan. Firmware.
- **Quick power cycles:** the Wi-Fi setup and the factory reset count power cycles within ten seconds after power-on
  instead of five; the Waveshare 1.54, with its start sound, left too little time. Firmware.
- **Display editor preview:** the device preview in the editor fits wide cases again instead of clipping the picture,
  shows the right case after switching between devices, and opens enlarged at full size.
- **IP without a box:** the server draws the display's IP in the theme's font and colour straight over the background;
  the dark box behind it was left over from the time the display drew the IP itself.
  Where it does not fit (narrow displays), `IP:` goes first, then the leading parts of the address (`…123.193`), so
  the last parts stay visible.
- **Emoji graphics in the repository:** the Twemoji 17.0.3 SVGs (unmodified, CC BY 4.0) and their license are
  versioned under `third-party/twemoji`, with their origin, the archive's checksum and the update steps. The server,
  the tests, the manual and the Docker image use these same files; the build no longer downloads Twemoji. Started from
  the repository, the server finds them by itself; a JAR elsewhere uses a copy named `twemoji` next to it, and
  `EMOJI_DIR` still names another folder. `ThemesImageTest` now always runs and draws into `target/themes.png`.
  Rendering, the available emoji and the firmware stay the same.

## 0.2.4

Claude sign-in renewal fixed, the Waveshare ESP32-S3-Touch-LCD-1.54 as a new tested board, quarter turns for square
displays, settings and preview cases for the prepared boards, a web display in the colour of its image, clearer
Synology setup instructions and TLS pin examples. Update the server image, then the displays over
Wi-Fi.

- **Claude sign-in renewal fixed:** the previous `claude auth status` command did not reliably renew stored
  credentials. The server now runs a minimal Haiku request through the official CLI, with no tools, skills,
  MCP servers or saved session, in an empty working folder. These are real model requests and count toward
  the account's usage. Attempts start 30 minutes before the access token expires and repeat every ten minutes
  if it has not been renewed, including the first eligible attempt after expiry. Only after a failed attempt
  with an already-expired token does the retry interval increase to six hours. The server reports
  "renewed until …" only when the stored expiry has advanced.
- **Separate Claude sign-ins for each server instance:** do not copy the same sign-in to another server or local
  test instance. Refresh-token rotation can invalidate the other instance's copy; sign in separately on each
  instance. This does not limit the number of Claude accounts configured on a server.
- **Synology setup and TLS pin documentation:** the manual explains how to point `EXTERNAL_TLS_PROBE_URL` at a
  separate reverse proxy's LAN HTTPS listener and allow its destination port from the Docker source subnet in
  the DSM firewall. It distinguishes external, NAS-host and container reachability. The network screenshot shows
  successful demo TLS pin inspection; pairing and USB setup screenshots reflect the simplified forms. All
  screenshots are refreshed for 0.2.4; generation uses a fixed locale, time zone and refresh line.
- **Waveshare ESP32-S3-Touch-LCD-1.54:** new firmware build, tested on hardware (240×240 ST7789, CST816 touch).
  Its speaker plays start, connection, pairing and quota sounds; volume and mute on the device page and with the
  + / - buttons, with a volume bar on the display. The device page and the USB command `VIBEDOCK-BATTERY` show
  battery diagnostics.
- **Quota sounds:** a display with a speaker (currently the 1.54) that asks for it with `X-VibeDock-Audio: 1` gets
  the remaining quota of its sources in `X-VibeDock-Quota` and sounds on every rise and fall; other displays get no
  extra header.
- **Orientation instead of Flip image:** the device card shows the orientation with a rotate button; square panels
  turn in 90° steps (`rotated-90`, `rotated-270`), the others by 180°. Quarter turns need the new firmware; older
  firmware keeps its orientation.
- **Previews:** the 1.54 in its white case; every case has a fixed screen area. When turned, the picture turns with
  the case and the new one draws from the top as on the display.
- **Pairing forms** only offer **Do not send TLS pin**; the pin status stays under **Settings → Network**.
- **Backlight:** stays dimmable after full brightness, and the display no longer flashes at start.
- **Prepared boards:** settings from the makers' documentation (LilyGO T-Display: power pin 14; Waveshare
  ESP32-S3-LCD-1.69: colour order RGB, power hold on pin 41) and a preview case for each. They remain untested on
  hardware; the manual shows all boards with real renderings in their screen sizes.
- **Web display:** the page and the status bar (also of the iPhone home screen app, now translucent) take the colour
  of the current image: from the start the background of the display's theme, then the top edge of every loaded
  image, also after timed reloads, swipes to another theme and photo changes. Offline the last colour stays.

## 0.2.3

The VibeTV M1 connects over https again after pairing, and the server finds its certificate pin in more networks.
Update the server image; the M1 then offers the update in the administration.

- **VibeTV M1 connects over https again after pairing:** firmware 0.2.x left about 1 kB less memory than 0.1.x, so
  the first full certificate check after pairing could run out of memory and was reported as *certificate not
  trusted* (e.g. over a phone hotspot when the server cannot reach its own public address). Texts of the display's
  pages, log and diagnosis now stay in flash, and Mbed TLS leaves out certificate texts and the AES decryption tables
  it never uses: about 8.5 kB more free memory than 0.2.2.
- **TLS pin behind a local DNS rewrite:** to read the certificate of its external https address, the server now
  also tries the internal host on the external port and the public DNS servers 8.8.8.8 and 1.1.1.1, always with the
  external host name for SNI and the certificate check; `EXTERNAL_TLS_PROBE_URL` names the reverse proxy's HTTPS
  listener explicitly. Pairing then sends the pin, and the M1 skips its first full certificate check.
  **Settings › Network** and the pairing forms show whether a pin is known, with the route and certificate details;
  **Do not send TLS pin** pairs without it, to test the full check.
- **Server log in Troubleshooting:** the latest entries of the application log, including the TLS and DNS checks,
  updated live while open.

## 0.2.2

The administration remembers the browser. Update the server image; nothing to do on the displays.

- **Stay signed in:** on by default, the sign-in lasts 30 days, renewed while in use, and survives browser and
  server restarts. Unchecked, the session still ends after 15 minutes of inactivity. **Sign out** revokes this
  browser's login; changing or resetting the administration password signs out all browsers. The server keeps only
  hashes of the browser tokens in `/data/admin-logins.json`; it is not part of the data-source export.

## 0.2.1

Claude sign-ins stay valid, and stored credentials are encrypted. Update the server image; nothing to do on the
displays.

- **Claude sign-in renewed automatically:** the access token of a Claude Code sign-in lives about eight hours and
  was never renewed, so every Claude source showed *authentication_required* after that, often noticed after an
  image update. Like Grok and Gemini, the server now runs the official Claude CLI (`claude auth status`) in the
  source's folder half an hour before the token expires, and also once it has expired while its refresh token is
  still valid; the CLI renews the token itself. The server log says whether it worked ("renewed until …" or "not
  renewed"); a renewal that changed nothing is tried again after ten minutes.
- **Credentials encrypted on disk:** tokens and keys in `settings.json` and in the sign-in files under `auth/` are
  stored as encrypted fields (AES-256-GCM); configuration and metadata stay readable. The first start migrates
  existing files and creates `/data/.installation-id`: include this hidden file in backups, without it the stored
  sign-ins cannot be read. The key lives in the data directory, so keep the directory private as before. Official
  CLIs see the decrypted files only in RAM (`/dev/shm`) while they sign in or renew. Older versions cannot read the
  encrypted fields; to go back, export the sources with a password and import them into a fresh data directory.

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
- Displays no longer corrupt their heap after an image: the connection was freed before the HTTP client that still
  stopped it. On the ESP32-C6 this crashed in the Wi-Fi driver or in `multi_heap_free`, more often while swiping.
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
