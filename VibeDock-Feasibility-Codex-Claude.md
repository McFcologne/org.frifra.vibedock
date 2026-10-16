# VibeDock feasibility assessment for Codex and Claude

Initial assessment: 13 October 2026, following the expanded server request. Implementation notes dated 14 October 2026 appear below. The handoff describes an ESP8266EX, 4 MB flash, a 240×240 ST7789 display, and firmware 1.0.47.

This report preserves the evidence and historical findings from the investigation. Current source folders are `vibedock-server/` and `vibedock-client/` in this repository. The administration port is now **10001**; older port and output-folder references below describe earlier implementation stages. Use the [server guide](vibedock-server/README.md) and [client guide](vibedock-client/README.md) for current operation.

## Findings and project scope

The requested device should autonomously obtain **Codex and Claude** data over Wi-Fi after startup and display each service's **five-hour limit, weekly limit, percentage consumed, and both reset times**. The original goal excluded an always-running computer or additional server. A Spring Boot service in Docker was subsequently explicitly authorized to take over the companion application's device communication. Two devices are reachable over LAN/VPN; the third can use a local Docker service on the Mac. This revised architecture requires a running server. The initial legacy-delivery approach could potentially avoid changing the ESP8266 firmware; the later image-client architecture requires a new client implementation.

**Direct collection is technically plausible and described by iOS application vendors. A complete, officially supported implementation on this ESP8266 that is permitted by corporate authentication policies has not yet been demonstrated.** A confirmed direct contract for personal limits and appropriate sign-in/renewal procedures for both providers remains missing. This is an open feasibility decision, not evidence of technical impossibility.

The scope was expanded to Claude and the explicitly requested Docker server. Preparation of direct requests to evidenced internal provider endpoints was authorized. The original firmware source was not modified, no existing corporate credentials were imported, and nothing was flashed.

## Local source and original backup

The inspected repository was `CodexBar-Display`, branch `main`, commit `e9a39a9dda01dcb83c73c0bc821ad61efd1c4977`. Its working tree was initially clean. The handoff was `VibeDock_Standalone_Codex_Projektuebergabe(1).md` from the referenced chat. Subsequent user information included Microsoft Entra for corporate authentication; Claude Team or Enterprise with corporate SSO, exact plan unconfirmed; the required display values; the iOS application “AI Usage” as a comparison; and Spring Boot, Docker, local administration, and IP/CIDR allowlists.

The original full backup of the device was read only to check size and SHA256:

| Check | Result |
|---|---|
| Size | 4,194,304 bytes |
| SHA256 | `55447a9fd331784ea5447d110db7a0d644cf313884d31d53327150e5f1c6ccf0` |
| Comparison with handoff | Exact match |

The backup and original repository were not changed. No credentials were extracted from the backup, Keychain, or authentication files. The serial port was not opened, and no commands were sent to the device. Firmware version, chip, crystal, device state, and device ID therefore remain handoff information rather than fresh hardware measurements.

## Existing firmware and data path

File references apply to the commit above. All relative paths in this section are beneath `CodexBar-Display/`.

| Area | Evidence | Finding |
|---|---|---|
| Main environment | `firmware_esp8266/platformio.ini:1–46` | `esp8266_smalltv_st7789`, `espressif8266@4.2.1`, Arduino, board `esp12e`; monitor/upload at 115200 baud |
| Libraries | `firmware_esp8266/platformio.ini:9–22` | ArduinoJson 7.4.2, TFT_eSPI 2.5.43 |
| Display | `firmware_esp8266/platformio.ini:27–44` | ST7789, 240×240, inversion enabled, SPI 27 MHz; MOSI 13, SCLK 14, CS −1, DC 0, RST 2, backlight 5, active LOW |
| Initialization | `firmware_esp8266/src/renderer_esp8266.cpp:33–43` | Backlight setup, TFT initialization, rotation 0, GIF initialization |
| Flash layout | `firmware_esp8266/platformio.ini:15–16` | LittleFS, linker script `eagle.flash.4m2m.ld` |
| Additional environments | `firmware_esp8266/platformio.ini:49` onward | Bench, Wi-Fi recovery without GIF, probe/Wi-Fi bridge, native tests; the bridge is not a complete standalone firmware |
| Device server | `firmware_esp8266/src/main.cpp:50`, `:4401–4424` | Port 80 HTTP server with `/hello`, `/health`, settings, pairing, assets, `/frame`, and update routes |
| USB display | `firmware_shared/app_transport.h:25–35`, `firmware_esp8266/src/main.cpp:2453–2477` | Line-oriented JSON passed to the shared frame parser |
| Wi-Fi display | `firmware_esp8266/src/main.cpp:4354–4394` | Authenticated `POST /frame`; maximum 2048 bytes, one JSON frame per message |
| Data model | `firmware_shared/codexbar_display_core.h:121` onward | Provider, session/weekly values, reset countdown, optional token fields, unavailable states |
| Quota collection | `companion/internal/codexbar/codexbar.go:308–345`, `providers.go:171–179` | Computer runs CodexBar `usage --json` and normalizes its output |
| Token history | `companion/internal/codexbar/token_stats.go:26–35`, `docs/token-usage-support-matrix.md:7–31` | Separate local `cost --json --refresh --days 30` source; token counts are not quota percentages |

**In the existing firmware, Wi-Fi replaces USB as the transport to the companion application; it does not replace the computer that collects usage.** Searching `firmware_esp8266/src` and `firmware_shared` for `WiFiClientSecure`, `HTTPClient`, OAuth, `refresh_token`, `chatgpt.com`, and `api.openai.com` found no matches. Startup and main loops handle the display, incoming frames, Wi-Fi management, and clock. They do not collect provider data (`main.cpp:4618–4738`).

The original architecture prohibits outgoing firmware connections except SNTP (`docs/firmware-guardrails.md:20–21`). The explicitly requested standalone version is therefore an intentional architecture change. This is not a technical blocker and requires no additional permission for analysis.

### Storage and Wi-Fi mode

Wi-Fi SSID/password are stored in EEPROM (`main.cpp:1580–1595`, `:1639–1650`). Additional known networks use LittleFS `/wk`, settings use `/s`, and pending mode changes use `/cm` (`:98–103`). Device pairing uses `/auth` (`:121`, `:769–790`). This device token does not authorize access to Codex or Claude.

Cable mode explicitly disables Wi-Fi (`main.cpp:4675–4688`); that does not establish deletion of saved Wi-Fi settings. Connection and reconnection support exists. SNTP starts after Wi-Fi connects, using `pool.ntp.org` (`:4703`).

Actual USB request formats were derived from `main.cpp:2261–2437`. These examples document the contract; **they were not sent**:

```json
{"kind":"request","op":"set-connection-mode","deviceId":"5804246","mode":"wifi"}
{"kind":"request","op":"confirm-connection-mode","deviceId":"5804246"}
{"kind":"request","op":"configure-wifi","deviceId":"5804246","ssid":"<SSID>","password":"<password>"}
```

Mode changes and Wi-Fi configuration persist data and schedule a restart. Confirmation is possible only after restarting. A failed mode change rolls back; confirmation must occur within 60 seconds (`main.cpp:555–632`, `src/device_settings.h:22`, `docs/hardware-contract.md:88–116`). Enabling Wi-Fi alone does not create autonomous provider access.

Claude is already supported in the existing **computer-based collection path** (`docs/providers.md:29–33`). A saved CodexBar 0.63.0 response contains Claude quotas with 300- and 10080-minute windows, percentages, and resets (`companion/internal/codexbar/testdata/cli/codexbar-macos-0.63.0/usage-claude.json`, source `web`). This is parser evidence, not a current account test or proof of an official API. The macOS package is pinned to 0.63.0 with a hash in `scripts/fetch-codexbar.sh:5–10`; it was not executed.

## Official interfaces and authentication

### OpenAI Codex

The official **Codex App Server** documents `account/rateLimits/read` with percentage consumed, window duration, reset epoch, and optional multiple limit groups. `account/usage/read` provides different usage statistics. This is a JSON-RPC contract to a running `codex app-server`, not a documented direct ESP8266 HTTP endpoint. Required windows must be read from responses; 300 or 10080 minutes must not be assumed. [OpenAI App Server](https://learn.chatgpt.com/docs/app-server)

The **Daily Usage Analytics API** provides aggregated workspace reports. It requires a workspace administrator key with `enterprise.analytics.usage.read`; `codex.enterprise.analytics.read` is insufficient. The public overview points to the administrator reference for routes and schemas. This does not establish current personal five-hour/weekly quotas with reset times. A standard Platform API key does not replace Codex subscription quotas. [OpenAI Analytics API](https://learn.chatgpt.com/docs/enterprise/analytics-api)

Codex documents browser sign-in, administrator-enabled device-code login, and automatic token renewal in its official client. A 15-minute activation code is not a permanent credential; the specific code's origin remains unknown. Client documentation alone does not authorize a custom ESP8266 OAuth implementation. [OpenAI Authentication](https://learn.chatgpt.com/docs/auth)

Enterprise supports administrator-permitted **Codex Access Tokens** for noninteractive use. Scoped tokens must expire; older variants may offer different options. App Server can use such credentials. This still does not establish a direct quota endpoint for the microcontroller. [OpenAI Access Tokens](https://learn.chatgpt.com/docs/enterprise/access-tokens)

OpenAI also documents **Workload Identity Federation** for Codex using external identity tokens and administrator rules. X.509 federation is explicitly described only for the Platform API path. An Entra sign-in certificate therefore cannot be assumed to provide direct Codex usage access. [OpenAI Workload Identity Federation](https://developers.openai.com/api/docs/guides/workload-identity-federation)

### Anthropic Claude

Claude documents five-hour and weekly limits for paid plans. Consumption depends on context, model, and features. Percentages must not be calculated from token counts and assumed maximums. [Anthropic Usage Limits](https://support.claude.com/en/articles/11647753-how-do-usage-and-length-limits-work)

The official **Usage and Cost API** at `/v1/organizations/usage_report/messages` provides historical Platform API usage, not evidenced personal Claude subscription quotas. It requires administrator API credentials; regular workspace API keys are insufficient. [Anthropic Usage and Cost API](https://platform.claude.com/docs/en/manage-claude/usage-cost-api)

The **Claude Enterprise Analytics API** uses a separate analytics key with `read:analytics`, created by the primary owner. It reports activity, adoption, and plan-dependent token/cost data. These reports do not establish a current personal five-hour/weekly percentage with reset times. [Anthropic Analytics APIs](https://platform.claude.com/docs/en/manage-claude/analytics-api)

Claude Code documents browser sign-in and a long-lived token generated by `claude setup-token` for CLI automation. Expired logins that cannot be renewed require another sign-in. This CLI contract is not documented permission for a custom display client. [Claude Code Authentication](https://code.claude.com/docs/en/authentication)

Anthropic restricts Claude.ai credential use in third-party applications and distinguishes this from API keys and unmodified Claude Code. This report **does not provide a definitive legal assessment** of a personal, read-only quota display. Explicit provider confirmation for the proposed authentication path remains missing. [Claude Code Credential Use](https://code.claude.com/docs/en/legal-and-compliance)

### Microsoft Entra and corporate sign-in

The user confirmed Entra. Entra certificate-based authentication supports X.509 sign-in for applications and browsers and integrates with Conditional Access. The organization's actual rules are unknown. [Microsoft Entra CBA](https://learn.microsoft.com/en-us/entra/identity/authentication/concept-certificate-based-authentication)

Conditional Access can restrict Entra device-code flows; origin-related checks may also affect token renewal. This establishes a possible policy boundary, not an observed restriction on this account. OpenAI device-code login and Entra's device-code flow are not automatically the same procedure. Browser SSO, OpenAI credential issuance, and company rules must be assessed together. [Microsoft Authentication Flows](https://learn.microsoft.com/en-us/entra/identity/conditional-access/concept-authentication-flows)

## Findings from iOS applications

The comparison with iOS applications is valid: **an always-running computer is not inherently necessary.**

The vendor of **Usages** describes Claude Code sign-in through a browser followed by an authorization code, and Codex sign-in through ChatGPT. The vendor states that the iPhone makes direct requests, stores credentials in iOS Keychain, and requires no Usages server. This is concrete evidence for an autonomous client architecture, but not proof of provider approval or successful Entra sign-in in this organization. [Usages setup](https://usages.app/guides/check-ai-usage-on-iphone/)

The publicly available **AI Usage** product by AI-Created instead describes a Mac as the data source and iPhone/Watch as CloudKit displays. Its source repository lists direct requests to `claude.ai/api/organizations/.../usage` and `chatgpt.com/backend-api/codex/usage` executed on the Mac. **The name alone does not establish whether this is the application the user meant.** [AI Usage product](https://www.ai-created.com/products/ai-usage), [AI Usage source](https://github.com/TheMarco/claude-usage)

Another open iOS project, **ClaudeUsageMonitor**, documents direct access to the Claude web endpoint `GET https://claude.ai/api/organizations/{organization_id}/usage` using a browser session. Its developer explicitly labels it undocumented and unversioned. This is an evidenced technical approach, not an invented API, but also not an officially supported contract. [ClaudeUsageMonitor implementation](https://github.com/MarkRWatts/ClaudeUsageMonitor/blob/main/README.md)

For an ESP8266 implementation, browser-based initial setup, permitted credential issuance, storage, expiry, and renewal remain unresolved. The device has neither an iOS browser nor iOS Keychain. An officially permitted one-time browser setup could still support autonomous operation afterward. Extracting credentials from the corporate Mac or copying client identities without verification was not part of the investigated approach.

## Evidenced continuation and initial server implementation

Pinned CodexBar 0.63.0 resolved to commit `f3e718c897d5ed76af4e07182df899c722118546`. Six collection, refresh, and test files were saved with SHA256 evidence. Standard Codex collection uses `GET https://chatgpt.com/backend-api/wham/usage`, bearer authentication, and an optional account header. Windows contain `used_percent`, `reset_at`, and `limit_window_seconds`. Claude OAuth collection uses `GET https://api.anthropic.com/api/oauth/usage`, bearer authentication, and a beta header; `five_hour`/`seven_day` contain `utilization` and `resets_at`. The source therefore establishes concrete direct quota requests. [Codex source](https://github.com/steipete/CodexBar/blob/f3e718c897d5ed76af4e07182df899c722118546/Sources/CodexBarCore/Providers/Codex/CodexOAuth/CodexOAuthUsageFetcher.swift), [Claude source](https://github.com/steipete/CodexBar/blob/f3e718c897d5ed76af4e07182df899c722118546/Sources/CodexBarCore/Providers/Claude/ClaudeOAuth/ClaudeOAuthUsageFetcher.swift)

CodexBar implements direct Codex token renewal using an existing client contract. Claude delegates renewal to the Claude CLI. These procedures were not blindly copied into the custom service. The initial server used only explicitly entered access tokens and reported missing authentication. [Codex refresh](https://github.com/steipete/CodexBar/blob/f3e718c897d5ed76af4e07182df899c722118546/Sources/CodexBarCore/Providers/Codex/CodexOAuth/CodexTokenRefresher.swift), [Claude refresh](https://github.com/steipete/CodexBar/blob/f3e718c897d5ed76af4e07182df899c722118546/Sources/CodexBarCore/Providers/Claude/ClaudeOAuth/ClaudeOAuthDelegatedRefreshCoordinator.swift)

OpenAI documents an open-source client flow with dynamic registration, browser loopback callback, PKCE, and ID-token validation. This could enable independent sign-in instead of importing credentials. The browser callback is bound to `127.0.0.1`; a LAN callback to an ESP8266 is not established by that contract. Its token audience is `https://api.openai.com/v1`, so authorization for the internal quota endpoint remains unconfirmed. [OpenAI registration](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)

For that OpenAI flow, documented lifetimes are one hour for access tokens and 30 days for rotating refresh tokens. Successful renewal replaces the refresh token. This provides a concrete lifecycle option for an appropriate custom client, but not proven access to corporate quotas. [OpenAI token contract](https://developers.openai.com/siwc/token-sharing-open-source/token-reference), [OpenAI renewal](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)

Both quota endpoints were reached anonymously from the Mac with TLS 1.2, SNI, and certificate verification. Both returned HTTP 401; OpenAI OIDC metadata returned HTTP 200. No credentials were sent. This proves transport reachability, not authorized account collection or ESP8266 TLS compatibility.

The initial server at `outputs/CODEX-vibedock-server/` exposed `GET /api/v1/usage` on port 8080, restricted reads to saved IPv4/IPv6 networks, and provided authenticated administration on port 8081. Docker published administration only on host loopback. Empty rules denied all reads. Administration supported two provider credentials and three device targets initially disabled. Current source is `vibedock-server/`, and the administration port has since changed to 10001.

The legacy combined display uses the existing VibeDock v2/ThemeSpec frame. A native test with the original parser confirmed acceptance and eight compiled display elements. Tests used synthetic usage and a local HTTP device simulator, not real account data or hardware authorization. Docker was not installed during that original validation. Instructions and the JSON schema are in the server project.

## ESP8266 resources and build

BearSSL supports HTTPS, trust anchors, time checks, and RSA/EC client certificates. Documented overhead is approximately 6 KB of additional stack and 22 KB of connection buffers; smaller buffers require verified prerequisites. A client certificate also requires its private key. [ESP8266 BearSSL 3.1.2](https://arduino-esp8266.readthedocs.io/en/3.1.2/esp8266wifi/bearssl-client-secure-class.html)

**Technical assessment:** Small sequential HTTPS/JSON requests are plausible. Two services need not require two simultaneous TLS connections. Reliable coexistence with the parser, existing renderer, and GIF decoder still needs measurement. A full 240×240×16-bit buffer alone requires 115200 bytes, so additional full-frame buffers are unsuitable. This is a calculation, not measured consumption of the existing firmware.

`main.cpp:2601–2606` exposes free heap, largest free block, and fragmentation. No live values were collected. Existing storage of device/Wi-Fi secrets also does not establish suitable protection for corporate/provider credentials.

The referenced 4m2m linker script in Arduino core 3.1.2 allocates approximately 1 MB to the sketch, a roughly 1 MB gap, and almost 2 MB to the filesystem, plus EEPROM/RF/Wi-Fi regions. The successful build resolved core 3.1.2, whose local linker script confirmed this layout. Four MB of flash is neither four MB of RAM nor four MB of unrestricted sketch space. [ESP8266 linker script](https://raw.githubusercontent.com/esp8266/Arduino/3.1.2/tools/sdk/ld/eagle.flash.4m2m.ld)

PlatformIO 6.2.0 was installed only under `work/`, matching the repository's CI pin. An isolated copy of unchanged source built successfully: static RAM 49036/81920 bytes (59.9%), flash 479635/1044464 bytes (45.9%), and binary size 483792 bytes. Baseline SHA256: `0af7e174f1e279b7dcb7c3fae04690a03206320833adf4a3ec751b391304f3cb`. The one-time remote check returned the same commit as local. No upload target ran. The remaining approximately 32884 RAM bytes are not measured free heap; stack, Wi-Fi, and dynamic allocations still consume memory. `custom_fw_version` is `1.0.40-dev` but can be overridden by `CODEXBAR_DISPLAY_FW_VERSION` (`platformio.ini:6`, `scripts/pio_set_fw_version.py:15–18`). This source is therefore not proven identical to device firmware 1.0.47. The upload script includes an additional source-upload guard (`:21–30`).

## Blockers and the next verifiable step

| Open item | Evidence required | Consequence |
|---|---|---|
| Direct personal Codex quotas | Official HTTP contract with five-hour/weekly percentages and resets, or provider approval of the direct client | App Server alone does not meet the original server-free requirement |
| Direct personal Claude quotas | Corresponding contract/authentication evidence for subscription quotas | Token/cost reports do not satisfy the display requirement |
| Corporate Entra permission | Permitted browser/device/workload flow, Conditional Access, unattended-use rules | Certificate-based Mac sign-in is not automatically transferable |
| Operation without repeated sign-in | Documented lifecycle for both providers, expiry/revocation handling, renewal | One successful collection is insufficient |
| Claude account | Corporate SSO confirmed; Team versus Enterprise unresolved | Actual windows must come from the response |
| Referenced iOS application | App Store link or developer for “AI Usage” | Establish identity and authentication architecture |
| Resources | Baseline build passed; live heap and peak TLS usage still unknown | With server operation, its JVM handles provider TLS |

The next deployment must establish permitted provider credentials, actual account quotas, and renewal. The requested direct requests are implemented and tested with saved responses, not real corporate data. Hardware testing remains subject to separate explicit approval.

The display must treat providers independently, preserve two actual quota windows and resets where available, show collection time, and visibly mark missing/stale data. A Claude failure must not block Codex or vice versa. Reset times come from the provider and must not be invented as boot time plus five hours.

## Recovery before any later flash

`scripts/esp8266-restore.sh` exists in the original repository and writes a full image at address `0x000000`. It normally requires a manifest; a suitable manifest for this original backup was not confirmed. The documented skip-verification path is not a substitute and was not used.

Before explicit flash approval, establish backup hash, device identity, actual flash parameters, tool version, serial availability, and post-write verification. The original backup notes esptool 4.8.1 and 115200 baud. A restore overwrites saved settings and credentials with backup contents and itself requires explicit approval to flash. **Recovery is documented as a validation requirement, not verified on hardware.**

The Spring Boot project is prepared. Persistent corporate SSO operation and physical display output remain unproven.

## Update: browser SSO and storage, 14 October 2026

The Docker project includes the official Codex 0.162.1 and Claude Code 2.1.296 clients and a login script. Codex device-code login requires workspace permission; Claude uses `auth login --claudeai --sso`. Sign-in completes in the Mac browser. The service reads only its own client caches in Docker storage. Settings and credentials remain in a Docker-managed volume by default; an external host directory is optional. Current instructions are in [vibedock-server/README.md](vibedock-server/README.md).

Persistence, token changes, and logout were tested with synthetic caches: 16 Java tests and three new HTTP checks passed at that stage. The installed pinned clients' CLI help confirmed their login options. Docker execution, real corporate SSO, and provider quota access were not tested. Automatic renewal by the custom service remained unimplemented; storage alone did not establish unattended operation. Original source and backup remained unchanged, with no hardware access.

Sources: [Codex authentication](https://developers.openai.com/codex/auth), [Claude sign-in and credential storage](https://code.claude.com/docs/en/authentication), [Claude CLI options](https://code.claude.com/docs/en/cli-reference).

### Sign-in from administration

Administration gained Codex and Claude sign-in buttons (`Mit Codex anmelden`, `Mit Claude anmelden`). The server starts official clients, displays the browser link and Codex device code, and accepts any Claude browser return code over the protected administration port. Clients own credential storage; successful sign-in activates the new credentials without manual token entry. Status, cancellation, expiry, and CSRF protection are implemented. At this stage, 20 Java tests and four additional HTTP checks against the JAR passed with local login simulators. The official Codex App Server initialized successfully in isolation; its generated schema confirmed the device-code contract used. Real corporate login, end-to-end Docker operation, and automatic renewal remained open.

## Update: approved image clients

The server implements a registry, registration, manual approval/revocation, key validation, permanent identity across IP changes, and image delivery. Administration displays a PNG preview of the same RGB565 pixels delivered to clients. A separate ESP8266 project receives images row by row, reserves the bottom 20 pixels for its own permanently visible IP, and actively re-registers after missing updates. Wi-Fi and server settings are entered through a WPA-protected setup network and browser portal on the device.

24 Java tests, four additional local HTTP checks with simulators, and a native C++ policy test passed; firmware compilation succeeded. IP changes were checked in a Java test. The additional HTTP test changed the callback port because the Mac had no second configured IPv4 loopback address. Physical Wi-Fi/display tests, Docker source-address handling, and corporate SSO remained open. Nothing was flashed; the original backup and repository remained unchanged. Both source projects include `.gitignore`, guides, protocol documentation, and validation evidence.

## Current repository and deployment follow-up

The user created the monorepo containing `vibedock-server/` and `vibedock-client/`. Docker troubleshooting confirmed that all three base-image manifests are accessible when Docker's credential helper is on PATH. User-provided container logs reached application startup but failed because `ADMIN_PASSWORD` was unset. This establishes neither successful startup nor a completed provider login.

The administration port is now 10001; eight access tests passed after the change. Compose now specifies `container_name: vibedock-server`, and its configuration validates successfully. These follow-up checks did not deploy firmware or send commands to a physical device.
