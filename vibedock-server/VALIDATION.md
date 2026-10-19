[VibeDock](../README.md) › [Server](README.md) › **Validation**

# Current validation status

## Waveshare ESP32-S3-Touch-LCD-1.54 (2026-10-19)

The owner confirmed testing on the physical board: display colours (BGR), touch, image delivery, audio and volume
controls work. Initial slow transfers disappeared after locking the display to the nearby access point.
The battery page returns voltage and charging-signal readings; battery presence and operation without USB remain
unverified. The board is marked hardware-tested in both inventories.

The owner reported that only the short volume and swipe cues were inaudible. The NS4150B's typical startup time
is 120 ms, while those cues last 80, 90 and 120 ms. Playback now primes I2S with 160 ms of silence after amplifier
enable. An isolated execution trace of the actual player confirmed the first nonzero samples move from 0.06 ms
to 160.06 ms for all three short cues. Confirmed on the device: the short cues are audible now.

## State of version 0.2.0 (2026-10-17, on the branch 0.2.0)

- **Server:** 122 Java tests, among them swipes through the colour schemes (`X-VibeDock-Gesture`), device logs over
  USB and the network, the test modes (`DEMO_USAGE`, `ADMIN_AUTH=none`) and the layouts of every scheme, the new
  Windows 11, macOS and starship console ones included; the default layout stays pixel-identical to the frozen renderer.
  The images of narrow (172×320) and rounded (240×280) panels were rendered and checked by eye.
- **Firmware builds:** all nine boards build; `scripts/images.sh` ran through as in the Docker build. The ESP32 boards
  use Arduino core 2.0.17 (espressif32 6.9.0), the ESP32-C6 core 3 (pioarduino 55.03.311); the native policy test
  covers swipes, brightness, orientation and the colour defaults.

| Board | Status | What was tested on the device |
| --- | --- | --- |
| VibeTV M1 | tested | Unchanged in use on the owner's displays (see 0.1.0). |
| Waveshare ESP32-S3-Touch-LCD-1.47 | tested | Installed with `flash.sh` (chip detection, board choice), paired over USB, images in the 172×320 type, swipes left/right switch the scheme on the server and in the administration at once, swipes up/down the brightness. |
| Waveshare ESP32-C6-Touch-LCD-1.69 | tested | Installed with `flash.sh`, paired over USB (once needed a restart by hand), images in the 240×280 type with its rounded corners. |
| Cheap Yellow Display, LilyGO T-Display, LilyGO T-Display-S3, Waveshare ESP32-S3-LCD-1.69, ESP32-C3 with ST7789 | prepared | Build only; not run on hardware. |

What the tested boards showed, and what the prepared ones take over:

- **Wrong board, dark screen:** a flashed build for another board leaves the backlight off; `flash.sh` now reads the
  chip and offers only the boards with it.
- **TFT_eSPI on the ESP32-S3:** its default FSPI port gives register address 0 and crashes in `begin()`;
  `USE_HSPI_PORT` fixes it (1.47, and set for the prepared S3-LCD-1.69).
- **JD9853 (1.47):** needs the vendor's register set after the ST7789 initialisation (`initJd9853`).
- **ESP32-C6:** TFT_eSPI's C3 driver writes the SPI registers at address 0 there (crash) and stays dark even with the
  right base; `tft_espi_c6.py` makes it use the generic driver over the Arduino SPI library.
- **Arduino core 3 on the ESP32-S3:** lwIP and heap corruption (`pbuf_free`, `CORRUPT HEAP`) every few minutes; the
  ESP32 boards stay on core 2, only the C6 uses core 3. The first build after a board of the other platform fails
  once; `build.sh` and `images.sh` build every board on its own and retry once.
- **Colours:** the VibeTV M1's calibration (gamma 2.5, reduced blue) does not fit the other panels; the ESP32 boards
  keep only a calibration made on their status page. Calibrated by eye: the M1 at gamma 3.20 with 100/74/40 %, the
  Waveshare 1.47 at 1.50 with 100/91/76 %, which also suits the C6; the other boards start neutral.
- **Rounded panels:** the 240×280 type keeps text and mood clear of 36 px corners, the 172×320 one of 18 px.
- **Orientation:** the motion sensor decided unreliably; the boards no longer use it. *Flip image* on the device card
  turns the picture instead (header `X-VibeDock-Orientation`).
- **Open:** touch on the C6 was not tried yet.

## State of version 0.1.0 (2026-10-16)

- **Server:** 93 Java tests (access and port separation, pairing over the network and USB, signed updates, display types and layouts pixel-exact against the frozen renderer, web displays, boards, ordering) and 12 browser end-to-end tests in light and dark mode at 1024 and 360 px pass.
- **VibeTV M1 (ESP8266):** the firmware runs on the owner's displays; USB installation from the browser, USB pairing, Wi-Fi setup and image pulls are in use, and every display has been updated over Wi-Fi (signed) several times. Build: RAM 40068/81920 bytes, flash 559519/1044464 bytes.
- **TLS 1.3 on the M1:** the Mbed TLS configuration passes `vibedock-client/tests/tls-host/run.sh` (RSA and ECDSA certificates, a server preferring AES-256, wrong host name rejected, 2 kB request, session tickets). On the device: image pulls against a Synology reverse proxy offering TLS 1.3 only, at home and over a phone hotspot, with the full check once and the pinned certificate afterwards. See [vibedock-client/TLS.md](../vibedock-client/TLS.md).
- **ESP32 boards** (Cheap Yellow Display, LilyGO T-Display and T-Display-S3, Waveshare ESP32-S3-LCD-1.69, ESP32-S3-Touch-LCD-1.47 and ESP32-C6-Touch-LCD-1.69, ESP32-C3 with ST7789): the firmware compiles (flash 1.06–1.13 MB of 1.88 MB, RAM about 52 KB static) and the merged USB images were checked for their segment offsets. **None has run on hardware yet**: display pins, the canvas scaling of the device's own screens, https with mbedTLS and the signature check of updates are untested.
- **Web displays:** page, PNG link, secret renewal and the external address switch were checked against a local server.

The sections below describe earlier validation stages and are kept as history.

## Earlier stages

24 Java tests passed and the Maven build succeeded during the client-management validation. The resulting JAR passed four local HTTP checks: pending registration receives no images; manual approval enables image delivery and an authenticated PNG preview; re-registration with the same key and a changed receiving port preserves approval and redirects delivery; revocation stops delivery and re-registration remains pending. All simulators and the test server were stopped afterward.

Actual IP changes are covered by a Java test. The Mac had no second IPv4 loopback address, so the additional HTTP test changed the receiving port instead. No network settings were changed. Approval across restarts, invalid keys, CSRF, administration port isolation, exclusion of credentials, and exact PNG/RGB565 pixel equality were also checked. SSO, quota, and legacy device-delivery tests continued to pass. Earlier HTTP SSO checks belong to previous implementation stages; no real corporate login was performed.

The ESP8266 client compiled successfully: RAM 30920/81920 bytes and program flash 372299/1044464 bytes. A native C++ test checks registration retries, missing updates, clock rollover, pixel format, and protection of IP rows 220–239. The client includes a WPA setup network, DNS/captive-portal forwarding, saved Wi-Fi/server settings, and reconnection. These hardware paths have been compiled but not executed on a physical device.

Docker was not installed in the original validation environment. Subsequent troubleshooting confirmed that all three base-image manifests are accessible with a corrected CLI search path. User-provided container logs show application startup reaching the mandatory `ADMIN_PASSWORD` check; this does not confirm successful startup or end-to-end Docker operation. Corporate authentication, quota access, automatic token renewal, and networking in the target environment remain unvalidated. No device was written to or flashed. The supplied firmware is a build candidate, not an approved installation.

The administration port was subsequently changed to 10001. All eight access tests passed with the new port. The Compose configuration with `container_name: vibedock-server` was also validated without starting a deployment.

The original source repository remains unchanged. The original backup remains unchanged (checked by its SHA256). Source now lives in the user-created monorepo; the assistant did not create that repository. Java 21 bytecode was tested with Java 25. Additional evidence is recorded in `verification.json` and the client project.
