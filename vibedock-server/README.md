[VibeDock](../README.md) › **Server** · [Client](../vibedock-client/README.md) · [Protocol](CLIENT-PROTOCOL.md) · [Repository](https://github.com/McFcologne/org.frifra.vibedock)

# VibeDock server with Spring Boot and Docker

The server reads the usage of Codex, Claude, Cursor, Grok and Gemini accounts (*data sources*), exposes compact JSON to allowed source IPs, and renders the status image for every device it has paired; the devices pull it. Legacy delivery to up to three manually configured devices remains available separately. It does not install or update firmware.

Sign-in uses the official CLIs bundled in the Docker image; the server reads the credentials they save. Codex, Claude (including corporate SSO) and Grok are in use with real business and private accounts. The server renews no tokens itself: Grok and Gemini sign-ins are kept fresh by running their official CLIs without sending model prompts, Codex and Claude use the tokens their clients saved, and an expired token produces `authentication_required` until the next sign-in. Long-term unattended operation without a new sign-in is not demonstrated.

## Administration at a glance

The top navigation uses **Overview**, **Devices**, **Display**, **Data sources** and **Settings**. Light/dark administration appearance is saved in the browser and is independent of each device's display theme. The footer shows the build version, README link and a dedicated **Licenses & notices** page.

![Current Overview with demo accounts and devices](../docs/manual/images/02-overview.png)

Screenshots use an isolated local installation with empty credentials and synthetic devices. They show the current UI, not real usage or an authenticated provider account.

| Area | What you can do |
| --- | --- |
| **Overview** | Inspect source names, remaining usage and collection status; **Refresh** collects new readings while respecting provider cooldowns. See the default image and theme beside the sources, with device connection status below, refreshed live; **Update to …** starts a pending firmware update. **Edit design** and **Manage devices →** open the relevant editor or list. |
| **Devices** | Pair a device using IP, six-digit code, optional name and optional HTTP port, or add a **web display** (page and image link for a browser or another program). Each card shows the device image, IP, identifier, board, firmware and **Uses default / Custom design**; states refresh live. The pen renames, dragging a card reorders. **Edit design** opens the editor for that device; the reload button forces a refresh, the bin removes it. |
| **Display** | Select the default or a device with **Design for**. The context bar identifies the target; **Custom design** enables an independent device copy. Choose the display type, add, remove, reorder, split or compact usage bands, choose source accounts, weather and mood, swap slots, and choose a display theme under **Appearance**, directly below **Preview**. |
| **Data sources** | **Add data source** creates a named provider account; cards are renamed with the pen and reordered by dragging. **Manage** opens sign-in, display-name editing and optional credential fields; sign-in and data status are shown separately. **Export / Import** transfers encrypted sources and sign-ins, with a review and source selection before applying. |
| **Settings** | Configure internal/external addresses and the poll interval, read-only API IP/CIDR access and optional legacy delivery. **Storage** reports whether the data directory is writable. |

Create forms hide their matching top-level button while open. **Cancel** or Escape closes a create form and restores its button. Validation errors open the relevant action's form; an error saving a source or removing a device does not open an unrelated create form. Saving settings and sources reloads the page while retaining scroll/open sections. Save actions appear only when fields change.

In **Display**, every edit is saved right away (shortly after the last change); switching the target saves a pending edit first. Turning off **Custom design** for a device makes it follow the default again. The preview is rendered by the same code as the device image and is not a save operation. Older firmware can require the weather/mood band at the bottom; the editor reports that restriction.

<details>
<summary>Devices and per-device actions</summary>

![Device cards, inherited/custom design and device actions](../docs/manual/images/10-devices.png)

</details>

<details>
<summary>Display editor</summary>

![Shared default/device editor, preview and Appearance](../docs/manual/images/30-display-default.png)

</details>

<details>
<summary>Data source management</summary>

![Accounts, separate data status, Manage, Export and Import](../docs/manual/images/40-sources.png)

</details>

<details>
<summary>Dark administration appearance</summary>

![Overview in dark mode](../docs/manual/images/03-overview-dark.png)

</details>

### Add and configure accounts

1. Open **Data sources → Add data source**, choose **Type**, enter **Name on the display** and confirm. A provider can have several independently named accounts.
2. Open **Manage** on the source. For Codex, Claude, Grok and Gemini, start the provider's browser sign-in. For Cursor, enter the team Admin API key and the member's e-mail, then save.
3. Use **Name on the display** to rename the source. A stable random id keeps existing design assignments intact. Optional manual-token fields remain available; a stored token is never prefilled.
4. Assign the account in **Display**. Each usage band holds one full-width widget or two half-width widgets, or one compact row (one source, half the height, no reset times), up to five bands. The VibeTV M1 shows two full bands, one full and two compact ones or four compact ones. Adding more accounts does not add more bands.
5. Removing a source also deletes its saved sign-in. A design still referencing that id shows it as unavailable until another source is selected. Account transfer is described under [Exporting and importing data sources](#exporting-and-importing-data-sources).

| Provider | Authentication | Quota shown |
| --- | --- | --- |
| Codex | Codex CLI browser/device-code sign-in | Short and long windows, usually 5 hours and week; matched by actual duration |
| Claude | Claude Code browser/SSO sign-in | 5-hour and weekly windows |
| Cursor | Team Admin API key and member e-mail | Monthly billing-period limit share; no individual-subscription integration |
| Grok | Grok Build device-code sign-in | Weekly or monthly credits |
| Gemini | Google account through Gemini CLI | Daily Code Assist quota of the model with the least remaining share; accounts requiring a custom Google Cloud project are not supported yet |

See [PROVIDERS.md](PROVIDERS.md) for provider-specific limitations, renewal behaviour and unsupported integrations.

## Start with Docker Compose

From this project directory:

```sh
cp .env.example .env  # First setup only; keep an existing .env when updating.
# Set your own ADMIN_PASSWORD in .env.
docker compose up -d --build
```

The container name is `vibedock-server`. Open administration on the Docker host at **http://127.0.0.1:10001/admin**. The default username is `admin`. The public port is **10002**, exposing **GET /api/v1/usage** and the device image pull **GET /api/v1/clients/{identifier}/image**. The usage API allowlist starts empty and denies all reads.

To administer a remote host, use an SSH tunnel:

```sh
ssh -L 10001:127.0.0.1:10001 your-server
```

The browser address remains `http://127.0.0.1:10001/admin`. Compose publishes port 10001 on all host interfaces by default, so administration is reachable from the network at `http://<host-LAN-IP>:10001/admin`. It uses plain HTTP, so the password crosses the network unencrypted: use a strong password and a trusted network, or set `ADMIN_PUBLISH_ADDRESS=127.0.0.1` in `.env` to restrict it to the host. Inside the container, it listens on the container's network interface. Other containers on a shared Docker network may reach this port but still require authentication. Public routing should expose only port 10002.

The container runs as UID 10001. Compose mounts the host directory `vibedock-server/docker.data/` at `/data` (created on first start; override with `VIBEDOCK_DATA_PATH` in `.env`). The data survives container restarts, recreation and image rebuilds. The directory is excluded from Git and the image build. Data sources and settings are stored as readable JSON in `/data/settings.json` with permissions `0600`; manually entered tokens are encrypted individually. An administration password set under **Settings** is kept as a BCrypt hash in `/data/admin-password` (`0600`) and replaces `ADMIN_PASSWORD` until it is reset; deleting the file restores `ADMIN_PASSWORD`. Changing or resetting the password in administration expires all existing administration sessions; sign in again. Official clients keep their login caches separately under `/data/auth`, one folder per data source. Only credential fields (tokens, refresh tokens, API keys and client secrets) in settings and CLI caches are encrypted on disk; configuration and other metadata remain readable. On the first start after an upgrade, existing plaintext credential fields and files in the earlier whole-file encryption format are migrated automatically, with atomic replacement after encryption has been verified. A private `/data/.installation-id` is created once; include this hidden file when backing up or moving the data directory. Missing or incorrect installation identity stops startup without replacing credentials. Keep the entire directory confidential: the installation identity is in the same directory, so this protects against casual inspection, not someone who has the complete directory and application. Tokens are neither exposed in public JSON nor prefilled in administration forms. Older releases cannot read the encrypted local files; before a downgrade, export the sources with a password and import that export into a fresh data directory.

The sign-in page enables **Stay signed in** by default. It remembers this browser for 30 days and renews that period during use (at most once an hour). Browser and server restarts preserve the login. Without it, sessions still expire after 15 minutes of inactivity. **Sign out** revokes this browser's token, including after session expiry; a password change or reset revokes all browser tokens and sessions. Cookies are HttpOnly and SameSite=Strict, and Secure over HTTPS. `/data/admin-logins.json` (`0600`) stores token hashes and the configured password's salted BCrypt identity; no browser bearer tokens are saved. It is local server state and is excluded from data-source exports.

## Start directly from IntelliJ IDEA

**One click:** the shared run configuration *VibeDock server (Docker Compose)* (`.run/`) builds the image and starts it with `compose.yaml`, like `docker compose up -d --build`. It reads `.env` (create it once from `.env.example`, see above) and sets `COMPOSE_BAKE=false`: newer Compose versions build with *bake*, which asks on the terminal before it reads `../.git` for the version, and the IDE shows no terminal, so the build would wait without any output. A Maven run beforehand is not needed; the image build compiles and tests the server itself. *VibeDock server quick (Docker Compose, no manual)* does the same without the manual PDF and its screenshots (`VIBEDOCK_MANUAL=skip docker compose up -d --build`), for quicker local tests. *VibeDock test server (Docker, demo data, no sign-in)* replaces the same container with the same data and displays for quick tests: no manual, made-up usage for every data source (`DEMO_USAGE=true`, the real accounts are not asked, so no rate limits) and the administration without sign-in (`ADMIN_AUTH=none`), published on this host only (`ADMIN_PUBLISH_ADDRESS=127.0.0.1`). By hand: `VIBEDOCK_MANUAL=skip DEMO_USAGE=true ADMIN_AUTH=none ADMIN_PUBLISH_ADDRESS=127.0.0.1 docker compose up -d --build`; a plain `docker compose up -d` brings the normal server back. `ADMIN_AUTH=none` makes everyone who reaches the administration port the administrator.

A Dockerfile run configuration does not automatically read `.env` or apply Compose settings. Set the following runtime environment variables in that configuration:

```text
ADMIN_USER=admin
ADMIN_PASSWORD=<your own password>
```

Set **Container name** to `vibedock-server` and bind administration as `10001:10001` (or `127.0.0.1:10001:10001` for host-only access). Configure port 10002 as required by the client network. If using this direct run method, add a bind mount from `vibedock-server/docker.data` to `/data`; the mount declared in Compose is not applied automatically.

If the build reports that `docker-credential-desktop` is missing from PATH, check Docker Desktop's CLI tool installation and the IDE's search path. On the inspected Mac, the helper exists in Docker.app and `~/.docker/bin`; all three base-image manifests were retrieved successfully after adding Docker.app's CLI directory to the test process's PATH. Keep the configured credential store enabled.

## Browser sign-in

Every Codex, Claude, Grok and Gemini data source has its own sign-in under **Data sources › Manage** (`Sign in with Codex`, `Sign in with Claude`, `Sign in with Grok`, `Sign in with Gemini`). Opening `sources.html` directly only shows a preview; sign-in requires a running Spring Boot server.

1. Click the source's sign-in button. The server starts the official client for that source and prepares a browser tab.
2. Complete the sign-in, corporate SSO included, in your browser. If the browser blocks new tabs, use the displayed sign-in link (`Open sign-in page`).
3. For Codex and Grok, enter the one-time code shown in the administration on the provider's page. For Claude and Gemini, paste the code shown in the browser into the administration field and click the completion button (`Complete sign-in`). Do not copy access or refresh tokens.
4. Wait for the saved-login confirmation (`Signed in. Credentials saved`). The server automatically uses the new login. A previously entered manual token for that provider is cleared on successful sign-in. Other settings are preserved.

Status requests and code submission are available only on the authenticated administration port. Every write action requires CSRF protection. A second click does not start another parallel login for the same account. The cancel button (`Cancel`) stops the pending login process; it also expires after ten minutes. Cancellation does not log out a previously signed-in account. A server restart cancels pending sign-in flows while preserving successfully saved credentials.

The Docker image includes the official **Codex 0.162.1**, **Claude Code 2.1.296**, **Grok Build 1.0.52** and **Gemini CLI 0.64.0** clients. Codex is controlled through its official App Server using `account/login/start`; Claude uses `auth login --claudeai --sso`; Grok uses `grok login --device-auth`; Gemini runs the CLI in a terminal with `NO_BROWSER=true` (details in [PROVIDERS.md](PROVIDERS.md)). The clients perform OAuth/SSO and credential storage. The Java service does not accept corporate passwords or certificates and does not generate tokens itself. Raw CLI output and credentials are not copied into logs or status responses. Successful sign-in does not immediately trigger device delivery; previously enabled periodic delivery continues.

The terminal login script remains available for maintenance, but normal setup uses the buttons:

```sh
docker compose exec vibedock /app/login-provider.sh codex
docker compose exec vibedock /app/login-provider.sh claude
```

Codex uses the official device-code flow, avoiding a browser callback into Docker. Workspace settings must permit this flow. An organization that blocks it prevents this integrated login path. The official alternative is a local browser login followed by secure transfer of the complete login cache; the server does not automatically copy it from the Mac.

Claude uses `claude auth login --claudeai --sso`. A real sign-in test must establish whether the organization permits Claude Code access and login from the container. No OAuth client IDs from third-party applications are copied.

Successful logins are stored per data source in `/data/auth/accounts/<source id>/` (`auth.json` for Codex and Grok, `.credentials.json` for Claude, `.gemini/oauth_creds.json` for Gemini); the two default sources of earlier versions keep `/data/auth/codex` and `/data/auth/claude`. The server rereads these caches when displaying status and collecting data, so tokens renewed by a client are picked up without a restart. The persistent copies remain readable JSON with encrypted credential fields, including refresh tokens, and are not copied into `settings.json`. Login and renewal stage the CLI's files on a RAM filesystem (`/dev/shm` in Linux containers), encrypt updated credential fields after the CLI stops, then remove the RAM workspace. The scratch folders `tmp/` and `.tmp/` of a sign-in folder (Codex's launcher links and downloaded plugins) are neither encrypted nor staged; the CLI creates them again. Use `/app/login-provider.sh` for terminal sign-in as shown above; do not point a CLI directly at the encrypted files. A concurrent sign-in/renewal for the same source is rejected. `AUTH_RUNTIME_DIR` can select another `tmpfs`/`ramfs` directory; a regular disk directory is refused. Direct macOS/Windows Java operation can read encrypted settings and tokens, but CLI login/renewal requires the Linux container's RAM workspace. A missing or unreadable cache is treated as unconfigured. Provider rejection produces `authentication_required`. An account change immediately hides previously collected quota values.

**Persistence does not renew tokens.** The Java service performs no token refresh itself; for Grok and Gemini it lets the CLI renew the sign-in shortly before it expires. Sign in again after expiry or revocation.

The terminal script below covers only the two default sources. To log out:

```sh
docker compose exec vibedock /app/login-provider.sh codex-logout
docker compose exec vibedock /app/login-provider.sh claude-logout
```

The token-removal checkboxes clear only manually stored tokens. For a complete logout, also log out the relevant official client; otherwise its cache becomes the active credential source again.

Sources: [Codex App Server](https://learn.chatgpt.com/docs/app-server), [Codex authentication and headless operation](https://developers.openai.com/codex/auth), [Claude CLI authentication](https://code.claude.com/docs/en/cli-reference), [Claude credential storage](https://code.claude.com/docs/en/authentication). Cache fields are client implementation details and may change with client versions.

## Data directory

By default the data lives in `vibedock-server/docker.data/`. To keep it elsewhere, set `VIBEDOCK_DATA_PATH` in `.env` to a confidential directory writable by UID 10001. Data is not transferred automatically between directories or from an earlier Docker volume; copy it with `docker cp vibedock-server:/data/. <directory>/` before switching. The directory contains credentials and must not be published or committed.


**Logs:** `/data/logs/` (set `LOG_DIR` to move them). `vibedock.log` holds start-up settings, pairings, device pulls (first pull, address changes, rejected keys), provider status changes with the HTTP code and weather problems; `access-YYYY-MM-DD.log` lists every request with port, status and duration. Both rotate daily (application log also at 10 MB) and are kept 14 days, the application log at most 200 MB. Web-display paths are replaced with `/web/[redacted]` and query strings are omitted. They contain IP addresses but no keys, tokens, cookies or passwords. Logs made by older versions may contain reusable web-display links: rotate those links with **New links** and restrict or remove the old logs. `ACCESS_LOG=false` turns the access log off. **Devices → Troubleshooting → Server log** shows the latest 500 application-log lines (bounded to 256 KiB), with manual refresh and a text view. It opens at the latest entry and, by default, refreshes every five seconds while expanded and visible. Scroll up to keep your reading position; scroll to the bottom to follow new entries again, or disable **Live updates** to pause. TLS probe failures include the target host and port, exception type and reason in this log.

### HTTPS pinning behind a reverse proxy

The server validates the external HTTPS certificate and sends its fingerprint and root certificate during pairing
(over the device's pairing portal or USB). The M1 firmware can use this pin for its first HTTPS connection; ESP32 boards currently always use the full check.
Use a trusted LAN for HTTP pairing, or USB: the pairing channel carries the device key and TLS trust data.

If the router does not support NAT loopback, the server first tries the public address and then connects to the
**configured internal address's host on the external HTTPS port**. The port of the internal HTTP backend is not
used. If both routes fail to establish TLS, the server queries public DNS at `8.8.8.8` and `1.1.1.1` for the
configured external hostname and tries those IPs on its external HTTPS port. This bypasses a local DNS rewrite;
it does not change system DNS or the addresses announced to devices. SNI and certificate hostname verification
still use the external hostname, and the chain must be trusted by Java. Wrong hostnames and rejected certificates
stop the inspection. Public DNS only changes the route: it cannot bypass a firewall or missing NAT loopback.
Neither address announced to devices changes.

If the TLS proxy has a different LAN address or port, set, for example:

```yaml
environment:
  EXTERNAL_TLS_PROBE_URL: "https://192.168.1.10:10002"
```

With the supplied Compose file, the same variable can be set in `.env`. Point it at the reverse proxy's **HTTPS
listener**, not the server's plain HTTP backend. An explicit probe URL is used directly for the configured external
address. The TLS certificate must still match that external hostname; the probe URL's hostname is only a transport
address. Recreate the container after changing the environment, then pair the device again (USB **Only pair** does
not reinstall firmware) to transfer the pin. A failed certificate check never supplies an unverified pin. If no TLS
route is reachable, the existing certificate-log fallback can supply a root only; the device must then complete
its own full certificate check before learning a pin.

The saved external address's pin status and fingerprint are shown under **Settings → Network** and in both pairing
forms. The route and connected IP/port are shown, including **Public DNS fallback**. **Certificate details** shows
subject, issuer, validity dates, TLS version and SHA-256 fingerprint; cached results retain the original route and
are marked **cached**. **Check pin again** repeats the lookup (successful inspections are cached for one minute). In network and USB
pairing, **Do not send TLS pin** omits the pin while retaining the root certificate. It is off by default and applies
only to that pairing; use it to test the M1's full certificate check and subsequent local pin learning.

For diagnosis, open **Devices → Troubleshooting → Server log**, trigger **Check pin again**, then refresh the log.
Each status query writes a short `TLS pin refresh for …: available/unavailable (… ms)` summary, including when the
cached inspection is reused. The UI also shows the time, outcome and request duration of the last refresh below
the refresh button, including failed requests.
`Public DNS … resolved …` shows the public answers; `TLS certificate for … verified through public DNS address …`
confirms successful verification. `Unsupported or unrecognized SSL message` usually means an HTTP listener was
reached on a port expected to speak TLS. Public resolver queries require access to DNS port 53; the TLS target must
also be reachable. The full lookup order and pairing fields are documented in [CLIENT-PROTOCOL.md](CLIENT-PROTOCOL.md#server-side-certificate-inspection).

## Client management and image display

Devices are invited by the server and then pull their image; see [CLIENT-PROTOCOL.md](CLIENT-PROTOCOL.md) for the details.

1. Connect the device to Wi-Fi through its local setup portal. An unpaired device shows its IP address (bottom line) and a 6-digit pairing code.
2. In administration under **Devices**, click `Pair device`, enter IP and code and confirm. The server sends its addresses, a new device key and the poll interval to the device. **HTTP pairing is unencrypted** and remains enabled for trusted home networks; the form displays a warning. A network attacker can capture the device key or replace TLS/OTA trust keys. **Devices → Firmware → Only pair** over USB is an alternative without reinstalling firmware.
3. The device pulls `GET /api/v1/clients/{identifier}/image` with its key every poll interval (default 30 s). Unchanged images are answered with **304**. After a design is saved, the server asks the affected devices to pull right away (`POST /pull` on the device); after a restart it asks devices with an established callback address, once the first usage readings are in. Device-reported IPs may change the callback destination only inside the network fixed at HTTP pairing, using the entered IP and the device-reported subnet mask. Older devices without a mask use the original IPv4 pairing IP's `/24` (first three octets); IPv6 stays at the original address. Clients on 0.1.0 remain supported without the mask header. If no actual mask has been saved, the first authenticated pull from recognized firmware newer than 0.1.0 with a valid mask may replace the `/24` fallback once, anchored to the original IP. Once learned, later reports and firmware updates cannot widen or move that network. Invalid masks keep the exact original IP and show a warning. USB-paired devices enable immediate callbacks after their first authenticated image pull: the reported device IP (or socket peer if the header is absent/invalid) fixes the origin, and subsequent IPv4 address changes must stay in its `/24` until the actual mask is learned once as above. This survives server restarts; existing USB registrations without an origin learn it on their next authenticated pull. Web displays never receive callbacks.
4. Under **Settings** set the **internal** address (LAN, default: the host used to open the administration UI with port 10002) and an optional **external** address, e.g. a public host name behind a reverse proxy. `INTERNAL_URL` and `EXTERNAL_URL` in `.env` provide defaults. Devices try the address that answered last, then the other one.
5. **Boards:** besides the VibeTV M1 (ESP8266), the firmware is built for ESP32 boards with a display: Cheap Yellow Display (ESP32-2432S028R), LilyGO T-Display, LilyGO T-Display-S3, Waveshare ESP32-S3-LCD-1.69, Waveshare ESP32-S3-Touch-LCD-1.47, Waveshare ESP32-C6-Touch-LCD-1.69 and an ESP32-C3 with a 240×240 ST7789 (table in [CLIENT-PROTOCOL.md](CLIENT-PROTOCOL.md#boards)). Every device has a board; it decides which build is installed over USB or Wi-Fi and sets the device's default display type. Current firmware reports its board with every pull and over USB; for older firmware choose it when pairing. The card shows it next to the identifier. Tested on hardware are the Waveshare ESP32-S3-Touch-LCD-1.47 and ESP32-C6-Touch-LCD-1.69; the other ESP32 builds are prepared and untested on hardware (see [VALIDATION.md](VALIDATION.md)).
6. **Firmware:** the Docker image contains the display firmware for every board, built from the same commit (version from the Git tag, like the server). *Devices → Firmware* offers it per board (chosen in the firmware card) as a download with SHA-256 and esptool commands, and installs it from the browser over USB (Web Serial, Chrome or Edge, administration opened over HTTPS or on `localhost`): connect (the page checks the chip and switches to the matching board when only one board has it), download a full flash backup, then write the board's image at `0x0` (on ESP32 chips bootloader, partition table and app in one); Wi-Fi, pairing and colour calibration stay. After installing, the page offers to pair the display right away: it asks the display over USB for its identifier and the Wi-Fi networks in reach, pairs it on the server and sends Wi-Fi and pairing over the cable; the display restarts and shows its image, without setup Wi-Fi and pairing code (the Wi-Fi password goes from the browser to the display only). *Only pair* does the same for a display that already runs this firmware. *Restart display* restarts it over the cable like its reset button (any firmware). Opened on `localhost`, the page needs the internal address under *Settings*, since it cannot know the server's LAN address. Each device reports its firmware with every pull; the device list, the overview and the firmware page offer *Update to …* for devices with another version and show the progress live. Without the build context `git` (plain `docker build`), or with `java -jar`, there is no firmware to offer. **Updates over Wi-Fi:** a device with current firmware gets *Update to <version>* (*Update all* in the device list for several); it downloads its board's firmware signed with this server's key (created in the data directory), checks the signature, installs it and restarts. A device the server cannot reach directly picks the update up with its next image pull. Older firmware shows *Update … via USB* until it was installed once over USB and paired. **Own firmware build:** the firmware page takes a build of your own per board (`firmware.bin`, on ESP32 boards also `flash.bin` from `.pio/build/<board>/`); the server reads version and board from the file (`VIBEDOCK-FIRMWARE <version> <board>`, stored in `firmware/<board>/` of the data directory), refuses files for another board, and offers the build over USB and, signed, over Wi-Fi until *Use the built-in firmware*. Updates use the version of each board's firmware. *Display log* reads the display's events over USB; [Troubleshooting a display](../docs/manual/MANUAL.md#troubleshooting-a-display) explains them. **Diagnosis:** firmware 0.2.0 sends `X-VibeDock-Diag` with every request (boot id, run time, memory, Wi-Fi, how the start came about and the last phase before a reset, see `CLIENT-PROTOCOL.md` 3f); the server keeps the last 20 starts per display (`device-logs/<id>/starts.json`, one per boot id) and shows them with the freshness of the newest log on *Troubleshooting*. *Request current logs* asks all displays at once; the support file adds a diagnosis overview (observed, suspected, missing). A browser that closes a page (also the event stream) is no storage error: it is logged at debug level only.
7. **Device actions:** the reload button next to the status and the bin next to *Edit design*. The pen next to a device or data source name renames it in place. Device and data source cards are reordered by dragging them like the bands in the editor (the grip ⠿ left of the name also takes the arrow keys); the order is saved at once and used on the overview and in the editor as well. `Force refresh` makes the device redraw its whole screen on the next pull, even if the image did not change; a trusted, reachable callback endpoint can trigger that pull immediately. `Remove device` deletes a device; its next pull gets **401** and it returns to pairing mode.
8. **Web displays:** *Add web display* on the device page creates a display that fetches its image itself, for a browser, tablet or another program: name, display type and reload interval (default 30 s). *Fit the browser* (`fit`, web displays only) takes the window's shape: short side 360 layout pixels, larger windows zoomed. The page requests `image.png?w=&h=&r=` with its safe-area size in CSS pixels and `devicePixelRatio`; the server draws the image at that resolution (scale rounded up to quarter steps, at most 16 megapixels, full colour), device types as large as the window holds. Without parameters the PNG has the type's pixels. `/web/<secret>/icon.png` is the home screen icon (iOS web clip, the display icon of the device pages). The page polls `GET /web/<secret>/state` every 3 s (a tag of the current image) and loads a new image when it changes; `POST /web/<secret>/swipe?direction=next|previous` (swipe or arrow keys on the page) changes the theme or, in a slideshow, the photo, like a touch display. Its card shows two links with copy buttons: the **page** `/web/<secret>` shows the image full screen on black and reloads it every interval (it swaps the image only once the next one has loaded and keeps the last one with an *offline* note when the server is unreachable), the **image** `/web/<secret>/image.png` is the plain PNG. Both are served on the public port only; the 64-character secret in the path is the only access, so treat the links like a password. *New links* replaces the secret, the old links stop working. *External address* builds the links from the external instead of the internal server address. A web display has its own design or follows the default like a device; where a device shows its IP, its image says `VibeDock server v<version>`. The secret is kept in `clients.json` (0600) so the links can be shown again. Web displays are never asked to pull and have no firmware.
9. **Display:** one editor for the default design of all devices and for device designs. **Edit design →** on a device opens it with that device selected; **Custom design** makes a complete copy for it, turning it off again follows the default. The default mood emoji is 🤖.
   - **Layout:** one to five usage bands (**+ Add usage band**, **×** removes one) and the weather/mood band, reordered with the arrows or by dragging. A usage band shows one source at full width, two at half width (a monthly window is then labelled `30d`) or one as a **compact row**: title and status, below it both windows as small bars side by side, no reset times. Bands keep their height (usage 92 px, compact 46 px, weather/mood 56 px) and shrink to 88/44 px where that makes one more band fit. Usage bands the chosen display cannot show sit below a dashed "ends here" line, folded to one line; a click opens them, and a button makes them fit (as compact rows) where possible. The default layout gives the same image as earlier versions. *On tall screens* (`alignTop` in the layout) spreads the spare height evenly between the bands (default) or keeps the bands at the top with a trailing weather/mood band at the bottom edge.
   - **Display type:** a device design also chooses the display (default VibeTV M1, 240×240). The image is rendered in that resolution with the first usage bands that fit its height; narrow screens show the first source of a split band. The note below the selection says what is left out.
   - **Weather/mood band:** weather, the update time, the mood emoji and the device IP; all of it moves with the band. Weather and mood can swap sides: the left slot is shorter because the IP sits below it. Moving the band away from the bottom needs current firmware; for older firmware the server keeps it at the bottom and the editor says so.
   - **Preview/save:** the preview uses the same renderer and data as the device pull. Previewing saves nothing; a saved design applies with the next image pull.
   - **Mood emoji** in the default right slot, 44 px. *Choose* opens a picker with search that shows the same Twemoji images the display uses; Java cannot draw colour emoji fonts, so the Docker image bundles the [Twemoji](https://github.com/jdecked/twemoji) SVG set (drawn with [JSVG](https://github.com/weisJ/jsvg), MIT; sharp at any size) (pinned version and SHA-256 in the `Dockerfile`, graphics licensed [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/), copyright Twitter, Inc. and other contributors). Outside Docker, point `EMOJI_DIR` to the `assets/svg` folder of that set (an `assets/72x72` PNG folder works too, sharp up to 72 px).
   - **Colour scheme:** dark (Midnight, Ocean, Forest, Sunset, Lavender, Mono), light (Paper, Sky, Sand) or classic: green and amber terminal with ASCII bars and scan lines, 1-bit classic Mac windows, classic Windows windows on a teal desktop, Windows 11 and macOS windows, a starship console with orange elbows, a four-shade handheld LCD with block bars, a gridded blueprint with hatched bars, neon with glowing bars over a horizon grid, and a newspaper page with serif type and black bars marked by a small coloured dot. See [the theme overview](../docs/manual/themes.png). Schemes change background, headings, text and bar tracks; the quota bars keep their colour scale: green from 60 % left, then gradually yellow-green, yellow and orange down to red at 8 %.
   - **Weather** uses an icon and temperature only; the location is never drawn. The default slot is bottom left. Set a city name or `latitude, longitude`, resolved on preview/save (the saved resolved location is reused). Current temperature and weather icon come from [Open-Meteo](https://open-meteo.com/) (no API key, data CC-BY 4.0), cached per place and refreshed at most every 15 minutes; readings older than two hours are not shown.

The registry is stored in `/data/clients.json` (permissions 0600) with only a SHA-256 hash of each device key: one section each for the server settings, the default design and the devices. Files of earlier versions are migrated on start without changing what any device shows; the old file is kept as `clients.json.v2.bak`.

**Administration areas:** *Overview* (data sources with their remaining usage, refresh, preview of the default design, device status; read only), *Devices* (pair, list with installed firmware, force refresh, remove; *Firmware*), *Display* (design editor), *Data sources* (accounts with their sign-in, export and import) and *Settings* (addresses and poll interval, read-only API access, legacy devices, storage). The footer shows the version and links to README and the dedicated *Licenses & notices* page.

**Data sources:** every source is one provider account of type Codex, Claude, Cursor, Grok or Gemini with its own name on the display, its own browser sign-in (stored separately under `/data/auth`) and an optional manual token, so private and business accounts can be shown side by side. A new server starts with the two sources `codex` and `claude`; settings of earlier versions become these two. Every source has a random id that the designs use, so it can be renamed freely; sources of earlier versions get one on the first start. Removing a source deletes its stored sign-in. *Pause data source* (under *Manage*) keeps a source with its sign-in but stops polling it; the display shows it as *paused* until it is resumed. Cursor sources use a team Admin API key and a member e-mail instead of a sign-in. Supported types, and what Antigravity and individual Cursor plans would need: [PROVIDERS.md](PROVIDERS.md).

The legacy device section (`Legacy connection to manually configured devices`) retains `/frame` delivery for the existing firmware. It requires a known device address and pairing token. Enabled delivery writes to hardware. New client approval is separate. Both delivery methods were initially validated with local simulators; paired-device operation is also in use on real hardware. This does not establish physical validation of every legacy delivery path.

The JSON read API can still be enabled for individual source IPs or CIDR ranges. Its allowlist does not apply to the device image pull. **Clients never receive provider tokens.**

## IP allowlists

Only the socket peer address observed by the server is used. `Forwarded` and `X-Forwarded-For` are ignored. Administration shows the observed browser address for reference.

With NAT, Docker Desktop, or a reverse proxy, this may be a gateway address. Allowing that gateway then allows all readers forwarded through it. For individual source-IP controls, expose the API port directly or enforce the IP policy at the proxy and restrict the backend accordingly. Do not trust unchecked client headers as source addresses.

## Usage JSON contract

```http
GET http://SERVER:10002/api/v1/usage
Accept: application/json
```

Allowed source IP: `200` with JSON. Disallowed source IP: `403`. Other HTTP methods: `405`. Administration requested on the public port: `404`.

The complete structure is in [usage.schema.json](usage.schema.json). This example is synthetic and is never emitted as default or actual usage:

```json
{
  "schemaVersion": 1,
  "generatedAt": 1791925200,
  "providers": {
    "3f2c9a4e-0b7d-4c1e-9a55-2d8e6f1b7c90": {
      "status": "ok",
      "observedAt": 1791925190,
      "fiveHour": { "usedPercent": 12.5, "resetsAt": 1791936000, "seconds": 18000 },
      "week": { "usedPercent": 42, "resetsAt": 1792440000, "seconds": 604800 },
      "period": null
    },
    "8a1d6b2f-5e3c-4f70-b1a9-7c4e0d2f9b16": {
      "status": "authentication_required",
      "observedAt": null,
      "fiveHour": null,
      "week": null,
      "period": null
    }
  },
  "labels": {
    "3f2c9a4e-0b7d-4c1e-9a55-2d8e6f1b7c90": "Codex Work",
    "8a1d6b2f-5e3c-4f70-b1a9-7c4e0d2f9b16": "Claude"
  }
}
```

`providers` and `labels` are keyed by the data source id, in the order of the sources; `labels` holds the display names. `seconds` is the length of the window. `resetsAt` 0 means the window has not started yet. `period` names a single window (`Day`, `Week` or `Month`, e.g. Gemini, Grok and Cursor), otherwise it is `null`. All timestamps are UTC Unix seconds, not milliseconds. `usedPercent` means **consumed**, not remaining. `null` means a missing value, not zero consumption. `generatedAt` is the response time; `observedAt` is the last successful provider collection time. A fresh response timestamp does not make old provider data fresh.

`partial` indicates that only one quota window is available. Missing authentication, collection errors, or stale data produce `null` windows. Data becomes `stale` after the source’s current polling interval plus 90 seconds without successful collection (150 seconds at the normal one-minute pace). Display labels are exposed through `labels`. Provider account details, member e-mail addresses, workspace IDs and credentials are not included as separate fields; choose display labels accordingly.

## Provider collection and display

The first poll runs right after start and after every new login; each following collection waits at least the source’s interval and then for the next full minute. Each provider has its own pace: once a minute normally; after HTTP 429 the interval doubles (2, 4, 8, 16, up to 30 minutes, never earlier than `Retry-After`), and every successful poll halves it again until it is back at one minute. A reading counts as stale after one interval plus 90 seconds. The image shows the time of the newest reading (`Updated HH:mm`), not of the rendering, so it only changes with new data and unchanged pulls get **304**. While rate limited, the display keeps the last values and shows `Rate limited · HH:mm` with the time they were read; a window whose reset time has passed shows `--`. The usage endpoints are undocumented and publish no limits. Codex windows are matched by their actual duration, not by position: up to a day counts as the short window, longer as the weekly one; a plan with a single 30-day window is shown as `Month`. Claude uses `five_hour` and `seven_day`; a window without a reset time is shown as not started. Cursor, Grok and Gemini show one window (billing period or day); see [PROVIDERS.md](PROVIDERS.md). Unknown response structures are treated as missing or invalid. One provider's failure does not block the other. A manual refresh polls immediately unless the provider is in a rate-limit back-off.

Endpoints and fields are derived from the VibeDock project's pinned **CodexBar 0.63.0**:

- [Codex quota collection](https://github.com/steipete/CodexBar/blob/f3e718c897d5ed76af4e07182df899c722118546/Sources/CodexBarCore/Providers/Codex/CodexOAuth/CodexOAuthUsageFetcher.swift): `https://chatgpt.com/backend-api/wham/usage`, bearer token, optional `ChatGPT-Account-Id`.
- [Claude quota collection](https://github.com/steipete/CodexBar/blob/f3e718c897d5ed76af4e07182df899c722118546/Sources/CodexBarCore/Providers/Claude/ClaudeOAuth/ClaudeOAuthUsageFetcher.swift): `https://api.anthropic.com/api/oauth/usage`, bearer token and beta header.

These internal endpoints are not guaranteed public usage APIs. A standard OpenAI or Anthropic API key does not substitute for personal subscription quotas. The service uses its own User-Agent and does not impersonate a native provider application. Actual SSO permission and quota access must be tested in the workspace. An Entra token is not used as a provider token.

The display shows the remaining share of each window and its absolute reset time. Unavailable values appear as `--`. Legacy static ThemeSpec frames stay below the device's 2048-byte budget. No GIFs or assets are installed. Display time defaults to `Europe/Berlin`; change it with `TIME_ZONE`.

## Build locally

Use Java 21 or newer and Maven 3.6.3 or newer:

```sh
mvn verify
ADMIN_PASSWORD='YOUR-OWN-PASSWORD' java -jar target/vibedock-server.jar
```

Direct Java execution binds administration to `127.0.0.1` by default and the public port to all interfaces. Settings are stored under `./data`. Docker also uses Java 21. Spring Boot 3.5.16 is pinned.

## Validation and remaining deployment checks

`mvn verify` runs the Java test suite, including the renderer, all layouts, the device protocol including force refresh, data source export/import, provider mappings including Gemini, generated license information and administration access rules. The combined [automated regression checks](#automated-regression-checks) also run the license-inventory test and Chromium browser tests. The server runs in Docker on a Synology NAS with paired devices and real Codex, Claude and Grok accounts. The notes below describe the earlier validation stages.

The Maven build and 24 focused Java tests ran on the Mac in the first stage. Earlier implementation stages also tested sign-in flows against the JAR using local client simulators; those process and access tests remain in the suite. Coverage includes quota mapping, missing data, IP/CIDR rules, login, CSRF, port separation, persistence, independent provider failures, rate-limit cooldowns, and HTTP delivery to a local device simulator. The generated legacy frame was also checked with the original VibeDock parser outside hardware.

The new registry, approval across restarts, IP changes, invalid keys, revoked clients, PNG/RGB565 pixel equality, and authenticated image delivery were checked. Four additional HTTP tests against the JAR cover registration, approval, preview, changed receiving port, and revocation using local simulators.

Docker was unavailable during the original validation. Subsequent base-image metadata checks succeeded with a corrected CLI PATH; user-provided container logs reached the missing-`ADMIN_PASSWORD` startup check. At that stage end-to-end Docker operation, real provider sign-in and physical displays were not yet validated. After changing the administration port to 10001, all eight access tests passed. Compose configuration validation also passed with the fixed container name.

[status-preview-example.png](status-preview-example.png) shows unconfigured providers, the default emoji and a synthetic IP. It is not an actual device or usage state. See [VALIDATION.md](VALIDATION.md) for detailed evidence.

## Version and license information

The version comes from the Git tag. Tag a release on `main` with `git tag 0.1.0 && git push origin 0.1.0` (a `v` prefix such as `v0.1.0` works too): the *Release VibeDock* workflow builds and tests that version and creates a GitHub release with the version's section of [CHANGELOG.md](../CHANGELOG.md) and notes from the commits since the last tag and these files to download: the server JAR, the Docker images for `amd64` and `arm64` as `vibedock-server-<version>-<arch>.tar.gz` (Synology: *Container Manager › Image › Import*, elsewhere `docker load -i`) and the client firmware of every board (flash after a backup, see the client README). The same image is published as `ghcr.io/<owner>/vibedock-server:<version>` (and `:latest` for a plain `x.y.z`) for both platforms, and with the repository secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` also as `<username>/vibedock-server` on Docker Hub. *Actions › Release VibeDock › Run workflow* with an existing tag publishes only that tag's image and leaves its release alone, e.g. when the release is already published and locked. A release created on GitHub (*Releases › Draft a new release* with a new tag) gets the same files. The server, its Docker image and the firmware read the version from the Git tag of the sources they are built from; it cannot be set by hand. `scripts/version.sh` prints it for the current checkout (`0.1.0` on the tag, `0.1.0-3-g503ce5c` three commits later, `-dirty` with uncommitted changes of the server, `0.0.0-g<commit>` before the first tag). `docker compose up -d --build`, the IntelliJ Docker configuration with `compose.yaml` and `scripts/image.sh` hand the repository's `.git` to the Docker build as the build context `git`; Maven builds from the checkout (`mvn verify`, IntelliJ) read it directly. A plain `docker build .` without that context builds `0.0.0-SNAPSHOT`.

```sh
docker compose up -d --build
scripts/image.sh amd64   # image file for another machine, see below
```

`scripts/image.sh` (in IntelliJ the run configuration *VibeDock images (amd64 + arm64)*) builds both platforms and saves them as `target/vibedock-server-<version>-amd64.tar` and `target/vibedock-server-<version>-arm64.tar`, each with the single tag `vibedock-server:<arch>`, ready for `docker load -i` or the Synology import. `mvn clean` deletes them with the rest of `target/`.

The administration footer shows the Maven project version from Spring Boot's generated build information and links to the repository README and `/admin/licenses`. Without generated build metadata, local IDE runs show `development`.

Every Maven build generates `MAVEN-THIRD-PARTY.txt` from the resolved Java runtime dependencies, including transitive dependencies and excluding test/provided dependencies. It is bundled in the JAR; missing upstream license declarations are reported rather than guessed. The report is dependency metadata, not a replacement for the upstream license texts.

Every Docker image build also runs `scripts/license-inventory.mjs` against the installed image. It inventories the login clients and their npm dependencies, bundled license/notice files, Twemoji's graphics license, Java runtime legal files and system package copyright files (including fonts). The result is `/app/legal/components.json`. The administration page lists these components and serves their bundled notices as plain text. Node.js links to the notices for its installed version. No provider credentials or runtime data are read, and the page makes no external requests.

For another packaging system, generate the same inventory and set `vibedock.license-inventory` to its JSON path. An installation without an inventory explicitly shows that it is unavailable. The endpoints remain on the authenticated administration port. VibeDock itself is licensed under the Apache License 2.0 (`LICENSE` in the repository root).

## Exporting and importing data sources

**Data sources › Export** asks for a password of at least ten characters and its confirmation, then downloads every data source with its manual token and the credential file of its browser sign-in (`auth.json`, `.credentials.json` or Gemini’s `.gemini/oauth_creds.json`; no other content of the sign-in folders). The file is encrypted with the entered password: PBKDF2-HMAC-SHA256 with 600 000 iterations and a random salt derives an AES-256-GCM key. Without the password the file cannot be restored. Export/import encryption is independent of `.installation-id`: a fresh installation uses the export password to import and then encrypts the imported credentials with its own installation identity. If the local identity is lost, start with a fresh data directory and restore a password-protected export. Keep it like a password, because it grants access to the provider accounts.

**Import** first decrypts the file and lists its sources; nothing changes until you confirm. The review shows source names, types, whether credentials are included, and whether the source already exists. Select the accounts to import; a single source is selected automatically. **Cancel** abandons the pending import. Two modes are offered:

- **Add** adds the chosen sources to the current ones, each with its own sign-in folder. A source whose id already exists here is added as a copy with a new id. Existing sources remain unchanged.
- **Replace** removes all current sources and their sign-ins. The chosen sources then keep their ids, so designs exported from the same server show them again.

The decrypted import waits in the administrator's session for at most ten minutes. Signed-in sources may need a fresh sign-in after the import when the provider has already rotated the refresh token on the exporting server.

## Automated regression checks

Run `./scripts/verify.sh` from the server folder to run Maven verification, the license-inventory test, and the Chromium browser suite. Java 21+, Maven, Node.js 22+ and npm are required. Set `MVN_BIN` to an absolute Maven executable if it is not on the path. The first run installs the pinned browser-test dependencies and Chromium. On Linux, `./scripts/verify.sh --with-deps` also installs the browser's system libraries.

Browser tests start a fresh, loopback-only server for every test with temporary settings, a synthetic device, dummy authentication and polling disabled. They never reuse the review or production server, never sign into a provider and never pair or flash physical hardware. Temporary state and server processes are removed afterwards. Build `target/vibedock-server.jar` first if running `npm --prefix e2e test` alone.

The browser suite covers create-panel triggers and cancellation, adding and renaming sources, conditional save actions, saving designs right away, device overrides/inheritance, saving before switching targets, Flip image, photo upload and slideshows, support files, footer/license navigation, and eight pages at desktop/mobile widths in light/dark modes. Layout checks cover overflow, footer placement, overview alignment and Appearance below Preview. These are structural layout checks, not pixel-by-pixel screenshot baselines. Existing Java renderer tests cover the display output and its themes. Playwright retains screenshots, browser traces and isolated-server logs on failures; open `e2e/playwright-report/index.html` for diagnostics.

The GitHub Actions workflow runs the same checks on pushes and pull requests. It does not deploy, pair devices, flash firmware or use provider credentials.
