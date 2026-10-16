[VibeDock](../README.md) › [Server](README.md) › **Validation**

# Current validation status

## State of version 0.1.0 (2026-10-16)

- **Server:** 93 Java tests (access and port separation, pairing over the network and USB, signed updates, display types and layouts pixel-exact against the frozen renderer, web displays, boards, ordering) and 12 browser end-to-end tests in light and dark mode at 1024 and 360 px pass.
- **VibeTV M1 (ESP8266):** the firmware runs on the owner's displays; USB installation from the browser, USB pairing, Wi-Fi setup and image pulls are in use, and every display has been updated over Wi-Fi (signed) several times. Build: RAM 40068/81920 bytes, flash 559519/1044464 bytes.
- **TLS 1.3 on the M1:** the Mbed TLS configuration passes `vibedock-client/tests/tls-host/run.sh` (RSA and ECDSA certificates, a server preferring AES-256, wrong host name rejected, 2 kB request, session tickets). On the device: image pulls against a Synology reverse proxy offering TLS 1.3 only, at home and over a phone hotspot, with the full check once and the pinned certificate afterwards. See [vibedock-client/TLS.md](../vibedock-client/TLS.md).
- **ESP32 boards** (Cheap Yellow Display, LilyGO T-Display and T-Display-S3, Waveshare ESP32-S3-LCD-1.69, ESP32-C3 with ST7789): the firmware compiles (flash 1.06–1.13 MB of 1.88 MB, RAM about 52 KB static) and the merged USB images were checked for their segment offsets. **None has run on hardware yet**: display pins, the canvas scaling of the device's own screens, https with mbedTLS and the signature check of updates are untested.
- **Web displays:** page, PNG link, secret renewal and the external address switch were checked against a local server.

The sections below describe earlier validation stages and are kept as history.

## Earlier stages

24 Java tests passed and the Maven build succeeded during the client-management validation. The resulting JAR passed four local HTTP checks: pending registration receives no images; manual approval enables image delivery and an authenticated PNG preview; re-registration with the same key and a changed receiving port preserves approval and redirects delivery; revocation stops delivery and re-registration remains pending. All simulators and the test server were stopped afterward.

Actual IP changes are covered by a Java test. The Mac had no second IPv4 loopback address, so the additional HTTP test changed the receiving port instead. No network settings were changed. Approval across restarts, invalid keys, CSRF, administration port isolation, exclusion of credentials, and exact PNG/RGB565 pixel equality were also checked. SSO, quota, and legacy device-delivery tests continued to pass. Earlier HTTP SSO checks belong to previous implementation stages; no real corporate login was performed.

The ESP8266 client compiled successfully: RAM 30920/81920 bytes and program flash 372299/1044464 bytes. A native C++ test checks registration retries, missing updates, clock rollover, pixel format, and protection of IP rows 220–239. The client includes a WPA setup network, DNS/captive-portal forwarding, saved Wi-Fi/server settings, and reconnection. These hardware paths have been compiled but not executed on a physical device.

Docker was not installed in the original validation environment. Subsequent troubleshooting confirmed that all three base-image manifests are accessible with a corrected CLI search path. User-provided container logs show application startup reaching the mandatory `ADMIN_PASSWORD` check; this does not confirm successful startup or end-to-end Docker operation. Corporate authentication, quota access, automatic token renewal, and networking in the target environment remain unvalidated. No device was written to or flashed. The supplied firmware is a build candidate, not an approved installation.

The administration port was subsequently changed to 10001. All eight access tests passed with the new port. The Compose configuration with `container_name: vibedock-server` was also validated without starting a deployment.

The original source repository remains unchanged. The original backup remains unchanged (checked by its SHA256). Source now lives in the user-created monorepo; the assistant did not create that repository. Java 21 bytecode was tested with Java 25. Additional evidence is recorded in `verification.json` and the client project.
