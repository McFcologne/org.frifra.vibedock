[VibeDock](../README.md) › [Client](README.md) › **Dependency upgrades**

# Client dependency upgrades (0.3.0)

## Selected versions

| Component | Before | Selected | Boards |
| --- | --- | --- | --- |
| ArduinoJson | 7.4.2 | 7.4.3 | All |
| Mbed TLS | kochcodes 3.6.2 | Upstream 3.6.7 LTS | VibeTV M1 only |
| PlatformIO Espressif 32 | 6.9.0 / Arduino 2.0.17 | 8.0.0 / Arduino 3.3.12 | ESP32, ESP32-S3, ESP32-C3 |
| pioarduino | 55.03.311 | 55.03.312-1 / Arduino 3.3.12 | ESP32-C6 |
| PlatformIO Espressif 8266 | 4.2.1 | 4.2.1 (unchanged) | M1 |
| PlatformIO Core | 6.2.0 | 6.2.0 (unchanged) | Build tooling |
| TFT_eSPI / QRCode | 2.5.43 / 0.0.1 | Unchanged | All |

Mbed TLS stays on the maintained 3.6 LTS branch rather than moving to the incompatible 4.x architecture. The stale
PlatformIO registry repackaging is replaced with the pinned upstream source archive. `firmware/mbedtls_package.py`
checks its version and a SHA-256 over every file below `include/` and `library/`, which must match the official
release archive `mbedtls-3.6.7.tar.bz2` (the tag archive itself is not byte-stable), and adds a PlatformIO build
manifest to the downloaded copy, selecting only `library/` and `include/`. Upstream source files and their license are unchanged. Other boards use the TLS library supplied by
Arduino-ESP32; this update does not put a second TLS library or audio code on them.

The old Core 2 pin was a workaround after S3 runs showed `pbuf_free` / `CORRUPT HEAP`. This establishes neither
that Core 3 caused the corruption nor that the latest Core 3 fixes it. All ESP32 boards therefore need new hardware
validation. Existing LEDC, update-signature and crash-handler compatibility paths must continue working on Core 3.

`images.py` supports both PlatformIO 8's prebuilt esptool executable and pioarduino's Python esptool package. It
uses each platform's own tool and its generated segment offsets; a failed merge fails the build. Builds never flash.
GCC 14 retained some empty audio stubs as symbols on non-audio boards, so these stubs are explicitly always-inlined.
The strict check for linked audio symbols, buffers and protocol strings remains enabled; it was not weakened.

## Compatibility and recovery

Device identifiers, pairing keys, Wi-Fi configuration and the image protocol are not intentionally changed.
Update the server first, then install the matching firmware for one board at a time. Keep your own verified full
flash backup and a known-good firmware image before testing. Do not assume that a successful compile proves
Wi-Fi, TLS, audio, ADC calibration or OTA compatibility across Arduino/ESP-IDF versions.

If an update cannot boot or reconnect, use USB and the firmware for the exact same board. Restore a full backup
only to the same physical device, following [FLASHING.md](FLASHING.md). Never use another device's backup. No device
was flashed as part of the automated checks below.

## Validation

- M1: compilation passed with Mbed TLS 3.6.7 (sources checked against the release tree hash); RAM 46,708 / 81,920
  bytes; flash 713,147 / 1,044,464 bytes (measured again with the transfer changes, 2026-10-25).
- TLS host test with the firmware configuration and Mbed TLS 3.6.7: RSA and ECDSA certificates pass; a server
  preferring AES-256 still negotiates the supported AES-128; a wrong hostname is rejected. This Linux container
  test does not exercise ESP8266 IRAM allocation, physical Wi-Fi or embedded stack limits.
- Native policy and serial-reply tests: passed with AddressSanitizer and UndefinedBehaviorSanitizer; the quick-boot
  test runs the real start functions with both sanitizers.
- Waveshare S3 Touch 1.54: Core 3 compilation and merged USB image passed; RAM 75,168 / 327,680 bytes;
  flash 1,632,968 / 1,966,080 bytes. Hardware and longer-duration validation are pending.
- Waveshare S3 Touch 1.47: Core 3 compilation and merged USB image passed; audio-exclusion checks passed.
- All nine final board environments compiled with the shared UI protocol, appearance push and live-page changes;
  all eight non-audio boards passed strict audio-exclusion checks.
- The owner observed S3 Touch 1.47 starting slowly/resetting while attached to a Mac; reset reason 11 is
  `ESP_RST_USB`. It ran from a USB power supply and then also after reconnecting to the Mac. The owner later observed a battery-powered device resume immediately when USB was unplugged. The likely
  mechanism is startup USB output under host backpressure. Native USB now uses nonblocking writes and bounded
  solicited replies (250 ms without progress, five seconds total); unsolicited READY is removed. Host tests cover
  stalled/slow readers, complete streamed logs, clock rollover and silent-device HELLO. The owner confirmed on 2026-10-25 that the USB startup issue is resolved on all devices they tested.
  No exact board inventory was supplied; this does not establish complete Core 3 stability.
- Shared UI state, push and live browser updates add 2,388 bytes of M1 flash and 92 bytes of static RAM compared
  with the dependency-only build (711,671 flash / 46,652 RAM bytes). No image buffer or persistent socket is added.
- `node tests/ui_live_test.mjs` covers live theme/image changes, failed-image retries, transfer overlap and hidden tabs.
- `python3 tests/flash_port_test.py` checks that detection probes only the selected USB port.

## Device checks

1. Boot, keep existing pairing/Wi-Fi, pull images and verify colours and orientation.
2. Exercise HTTP and HTTPS, a pinned certificate and a full certificate check where practical; recover after a
   network/server interruption. On the M1 watch peak heap and the full-check allocation diagnostics.
3. Update over Wi-Fi and verify restart, pairing and a first complete picture followed by delta pictures.
4. On touch boards test both swipe directions, brightness, double tap and circle rotation. On the 1.54 also test
   audio, volume buttons and local overlay restoration while pictures arrive.
5. On battery-capable boards check USB versus battery, voltage/level where supported, dimming and wake-up.
6. Leave the S3 running with regular image updates for at least several hours and inspect reset reasons, heap and
   dropped connections. Earlier crashes appeared after minutes; a short successful demonstration is insufficient.

Sources: [Mbed TLS 3.6.7](https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.7),
[Espressif 32 8.0.0](https://github.com/platformio/platform-espressif32/releases/tag/v8.0.0),
[pioarduino 55.03.312-1](https://github.com/pioarduino/platform-espressif32/releases/tag/55.03.312-1),
[ArduinoJson 7.4.3](https://github.com/bblanchon/ArduinoJson/releases/tag/v7.4.3).

USB response regression test (no hardware access):

```sh
c++ -std=c++17 -Wall -Wextra -fsanitize=address,undefined tests/serial_reply_test.cpp -o /tmp/vibedock-serial-reply-test
/tmp/vibedock-serial-reply-test
```
