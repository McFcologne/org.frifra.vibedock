[VibeDock](../README.md) › [Client](README.md) › **TLS**

# https on the displays

How the firmware connects to an https server address, why it is built this way on the VibeTV M1, and what was learned
while making TLS 1.3 work on the ESP8266. The trust model (root from pairing, pin, full check) is described in
[CLIENT-PROTOCOL.md](../vibedock-server/CLIENT-PROTOCOL.md); this page covers the TLS implementation.

## Status per board

| Board | TLS library | Versions | Notes |
| --- | --- | --- | --- |
| VibeTV M1 (ESP8266) | Mbed TLS 3.6.7 (official pinned source archive, Apache-2.0) | TLS 1.3; TLS 1.2 (BearSSL) only when the server refuses 1.3 | A pin supplied at pairing allows the first pull to use the simple check. Without a pin, or after a certificate change, the M1 does the full check against the root from pairing; later pulls use the pinned certificate. 3.6.7 passes the local OpenSSL tests. The previous dependency was used on the device against a Synology reverse proxy (TLS 1.3 only), at home and over a phone hotspot; revalidate 3.6.7 on hardware. |
| ESP32 boards | Mbed TLS supplied by Arduino-ESP32 3.3.12 (`espressif32@8.0.0`; C6: pioarduino 55.03.312-1) | TLS 1.2 | The core migration alone does not enable TLS 1.3 in its precompiled libraries. A server must still offer TLS 1.2. Revalidate pinning, certificate renewal and memory on hardware. |

## How the server supplies the initial pin

At network or USB pairing, the server can supply both the trusted root (`anchor`) and the live leaf certificate's
SHA-1 fingerprint (`pin`). Every board can use that pin immediately, avoiding the first full chain check on the display.
Without a pin it performs the full check and learns the verified certificate itself. The M1 pins in its own TLS 1.3
client; the ESP32 boards accept a connection of `WiFiClientSecure` without chain check only when the SHA-1 of the
server certificate matches the pin (the handshake still proves the server's key). A certificate change triggers another full check; a root change requires re-pairing.

For an external HTTPS address, the server tries system DNS, the configured internal host on the external HTTPS
port, then public DNS at **8.8.8.8** and **1.1.1.1**. It connects directly to the public answer IPs but keeps the
external hostname for **SNI and certificate hostname verification**, with the chain checked against Java's trust
store. This is a server-side alternative for local DNS rewrites; it changes no client DNS or server addresses.
An explicit `EXTERNAL_TLS_PROBE_URL` takes precedence. If no live route is reachable, the certificate-log fallback
can supply a root only, without a pin. Invalid certificates and hostname mismatches remain errors.

**Settings → Network** shows whether the server knows a pin. **Do not send TLS pin** keeps
the root but omits the pin for that pairing, allowing the M1's full-check path to be tested. Re-pairing clears the
old pin when none is supplied; USB **Only pair** keeps the installed firmware. Resolver results and TLS probe
errors are available under **Devices → Troubleshooting → Server log**. See the
[server-side lookup protocol](../vibedock-server/CLIENT-PROTOCOL.md#server-side-certificate-inspection) and
[reverse-proxy configuration](../vibedock-server/README.md#https-pinning-behind-a-reverse-proxy).

## Why Mbed TLS on the M1

- BearSSL, the TLS library of the ESP8266 Arduino core, speaks TLS 1.2 only. A reverse proxy that offers only TLS
  1.3 (for example a Synology with the "modern" profile) answers it with `protocol version` and the display cannot
  connect. Allowing TLS 1.2 on the server again was not an option.
- Mbed TLS 3.6 is the only small TLS 1.3 client with a permissive licence that builds for the ESP8266. wolfSSL was
  rejected because of its GPL licence.
- Mbed TLS is only in the `vibedock-m1` environment. Its configuration is
  [`firmware/include/vibedock_mbedtls_config.h`](firmware/include/vibedock_mbedtls_config.h), selected with
  `-D MBEDTLS_CONFIG_FILE`. The client is [`firmware/src/tls13.cpp`](firmware/src/tls13.cpp): a `WiFiClient` subclass
  that runs Mbed TLS over the plain TCP connection of its base class, so `HTTPClient` uses it like any client.

## Configuration that matters

| Setting | Why |
| --- | --- |
| `MBEDTLS_SSL_PROTO_TLS1_3`, PSA crypto, ephemeral key exchange, compatibility mode | TLS 1.3 in Mbed TLS 3.6 runs on PSA. |
| `MBEDTLS_X509_RSASSA_PSS_SUPPORT` | Without it the client offers no `rsa_pss_rsae_*` signature algorithms. A TLS 1.3 server with an RSA certificate then finds nothing to sign with and aborts with a `handshake failure` alert (`-0x7780` in the log). |
| Cipher suite `AES_128_GCM_SHA256` only; signature algorithms SHA-256 first | Every TLS 1.3 server must support it (RFC 8446, 9.1). Offered next to AES-256 the Synology chose `AES_256_GCM_SHA384`, slower to decrypt. SHA-384/512 need about 800 bytes more stack; SHA-384 signatures stay available for certificates. |
| `MBEDTLS_SSL_KEEP_PEER_CERTIFICATE` | Required by Mbed TLS for TLS 1.3 (`check_config.h`). The firmware frees the certificates itself after the handshake. |
| `MBEDTLS_SSL_SESSION_TICKETS` off | Tickets the server sends after the handshake are ignored. Processing them derives keys as deep in the stack as the handshake; the display does not resume sessions anyway. |
| `MBEDTLS_SSL_IN_CONTENT_LEN 16384`, `OUT_CONTENT_LEN 1024` | Servers send full 16 kB records (record size limit / max fragment length are not reliably supported by servers). The display only sends small requests; longer ones go in several records. |
| Curves P-256, P-384, X25519; RSA up to 4096 bit (`MPI_MAX_SIZE 512`) | Common server keys and roots. |
| Size options (`AES_ROM_TABLES`, `ECP_WINDOW_SIZE 2`, `SHA256_SMALLER` …) | Less RAM and flash at some speed. |

What a server must offer for the M1 over TLS 1.3:

| | Supported | Not supported |
| --- | --- | --- |
| Cipher suite | `TLS_AES_128_GCM_SHA256` (mandatory for every TLS 1.3 server, RFC 8446) | AES-256-GCM, ChaCha20-Poly1305 |
| Key exchange | X25519, P-256, P-384 | P-521, FFDHE, post-quantum hybrids alone (`X25519MLKEM768`) |
| Server certificate | RSA up to 4096 bit (PSS, PKCS#1 v1.5), ECDSA P-256/P-384, SHA-256/384 | Ed25519/Ed448, RSA above 4096 bit, P-521 |
| Session | one full handshake per pull | resumption (tickets are ignored), 0-RTT, client certificates |

**PlatformIO does not rebuild Mbed TLS when only the configuration header changes.** After any change to
`vibedock_mbedtls_config.h` delete `firmware/.pio/build/vibedock-m1` (or run
`.venv/bin/pio run -d firmware -e vibedock-m1 -t clean`) before building, otherwise the old library is linked. Docker
builds always start clean.

## Memory on the ESP8266

The ESP8266 has about 80 kB of data RAM. After Wi-Fi and the app about 19 kB of heap are free, about 12–14 kB when a
pull starts. A TLS 1.3 handshake needs about 25 kB of heap plus a deep stack, so the firmware spreads the load:

- **IRAM heap:** `PIO_FRAMEWORK_ARDUINO_MMU_CACHE16_IRAM48_SECHEAP_SHARED` gives about 20 kB of unused instruction RAM
  as a second heap (16 kB less instruction cache). The allocator hook in `tls13.cpp` puts every Mbed TLS block of
  256 bytes or more there first: the 16.7 kB receive buffer, the send buffer, certificates and big numbers. The record
  buffers are allocated first (`mbedtls_ssl_setup` before parsing the root) so that they get it. Byte access to IRAM
  goes through the core's exception handler; that is slower but works.
- **Separate handshake stack:** the sketch has a 4 kB stack. Checking the server's certificate chain and the RSA-PSS
  signature inside `HTTPClient::GET()` overflowed it (`stack 0 bytes free`, abort). The handshake steps therefore run
  on a separate 4.5 kB stack in DRAM, switched in assembly like the core's `StackThunk` for BearSSL (which uses
  6.2 kB, too much here). A canary at the end stops the display if it is ever exceeded. The waiting between steps
  (`delay`, the USB console) happens on the normal stack; a step itself never waits. For the same reason the send
  callback writes at most what the TCP buffer takes and never blocks.
- **Contexts only when needed:** the Mbed TLS contexts are allocated when a connection opens and freed with it.
  `HTTPClient` keeps the unconnected original of its `clone()`; with the contexts inside the object that original held
  about 3 kB for nothing. The random numbers come from PSA's generator (`mbedtls_psa_get_random`) instead of an own
  CTR-DRBG and entropy context, and the root is parsed without a copy (`mbedtls_x509_crt_parse_der_nocopy`, the bytes
  stay in the firmware's root store). The server's certificates are parsed without a copy as well: the firmware is
  linked with `--wrap=mbedtls_x509_crt_parse_der`, Mbed TLS checks the chain in the same step straight from the
  received message and afterwards only needs the public key, which it copies. That saves 2.5–4 kB during the check.
  Before this, parsing the server's chain failed after about a second with
  `-0x2880` (out of memory, 704 bytes DRAM and 256 bytes IRAM free).
- **Given back during the handshake:** the device log keeps its lines only in `/log.txt` while a handshake runs
  (`parkLog`), which frees up to 4 kB; the next event loads them again. The image request object is on the heap, not
  on the stack.
- **Given back after the handshake:** the separate stack, the server's certificate chain (about 4–5 kB) and the parsed
  root. Reading and writing (decrypt, encrypt) fit the normal stack.

With too little memory for the separate stack the connection fails with a log line naming the largest free block
instead of aborting.

## Time

- The M1 runs at 160 MHz (`board_build.f_cpu`); key exchange and certificate check take seconds at 80 MHz.
- The handshake fails after 15 s **without progress** (the server does not answer) or after 60 s in all. Time spent
  computing does not count as waiting; a first version that counted the whole handshake timed out at 15 s.
- The HTTP answer may wait 15 s instead of 5 s over the full check: a TLS record (up to 16 kB) has to arrive whole
  before its first byte can be decrypted. If the request still fails after the handshake, the reason names the traffic
  since the handshake: `read Timeout (-11) after TLS; sent 554, got 8110 B, 6 reads, last -0x6900, record 3000/16406,
  TCP 0, idle 15000 ms, heap …`. `got 0` means the server never answered; a record that stays incomplete with nothing
  waiting in TCP means the answer stopped arriving.
- Wi-Fi modem sleep is off on all boards. With it the access point holds packets for the display until the next
  beacon, and phone hotspots drop some; the answer after a TLS 1.3 handshake then arrived in bursts seconds apart
  (`got 606 B … idle 9156 ms`) or stopped halfway.
- Each https pull does a complete handshake (no session resumption). After the first full check the M1 pins the
  server certificate (SHA-1, from `X-VibeDock-Tls-Pin` or from its own check) and connects with `VERIFY_NONE` and a
  comparison with the pin right after the handshake, before the request goes out. TLS 1.3 still makes the server
  prove its key (CertificateVerify), so only the holder of that certificate's key gets through. That saves the RSA
  check of the root (about 1.2 s and the memory that ran out on later pulls). A different certificate fails the pin
  (`certificate is not the pinned one`), and the full check follows at once.

## Pitfalls found on the way

- **`HTTPClient::begin(client, url)` works on `client.clone()`.** Without a `clone()` override the copy is a plain
  `WiFiClient`, and the request goes out unencrypted, Authorization header included, to the https port. A Synology
  answers that with its HTTP-to-HTTPS page and status 400, which looks like a server error. `Tls13Client::clone()`
  returns a new, unconnected TLS client with the same root. Any future `WiFiClient` subclass needs the same.
- **`WiFiClient::connect(host, port)` calls the virtual `connect(ip, port)`.** A subclass that overrides both starts a
  second handshake for the IP address inside the first. `Tls13Client` resolves the name itself and calls the base
  class with the address.
- **Opening the USB port resets the M1** (reset reason "External System"). Reading the log during a pull therefore
  aborts the pull; read it only once the display shows an image or "Server unreachable". USB starts are not counted
  as quick power cycles.
- **A reverse proxy may drop `Content-Length`.** The Synology answered the image chunked (`-1 bytes announced`), and
  the firmware discarded every image after `HTTP 200`. Requests now go out as HTTP/1.0 with `Accept-Encoding:
  identity`, full image size comes from `X-VibeDock-Image-Format`; delta and update sizes come from `X-VibeDock-Length`.
  Delta responses additionally check the exact body length, terminator and payload checksum (see
  [the image protocol](../vibedock-server/CLIENT-PROTOCOL.md#optional-delta-images-v1)).
- **Closed connections keep memory.** A regular close leaves the connection in TCP TIME_WAIT for two minutes with its
  lwIP memory; with a pull every 30 s about 800 bytes were missing, and every handshake after the first failed with
  `out of memory` in the certificate check. The client aborts the TCP connection after the TLS close notify instead.
- **Allocation failures surface as other errors.** With the heap fragmented to blocks of a few hundred bytes the
  handshake failed after about a second with `-0x6E00` (handshake failure) instead of an out-of-memory error. A
  signature check of the chain that runs out of memory makes Mbed TLS report the certificate as not trusted
  (`-0x2700`), which looks like a wrong root. The firmware therefore counts failed allocations and reports `out of
  memory (n allocations up to x bytes failed)` instead; a real mismatch names the chain's issuer and the root from
  pairing (`chain issued by ISRG Root X1, root ISRG Root X2`).

## Reading the device log

**Devices → Troubleshooting → Read log over USB** (or `VIBEDOCK-LOG` on the serial console) shows the last 24 events. For https:

- Success (figures as an example): `HTTP 200, TLS 1.3 TLS1-3-AES-128-GCM-SHA256 after 4200 ms (3900 computing, step 16); heap 12000+20240
  IRAM, TLS 23000, stack 3300/4608, 30000+19000 IRAM free after`.
- Failure: the Mbed TLS code and its meaning, the alert number and name when the server sent one (`40 (handshake
  failure)`), the verification flags (certificate expired, not yet valid, host name, root), duration and the
  handshake step, then heap before, Mbed TLS peak, largest free block and stack use.
- `Server refuses TLS 1.3, using TLS 1.2`: the server only speaks TLS 1.2; the same full check runs over BearSSL.
- A crash is recorded in RTC memory and shown on the next start, for example
  `Start …, Software/System restart: stack overflow (reason 253 …), stack 0 bytes free`.
- An error answer of the server adds its first 60 characters, so a 400 from a reverse proxy can be told from one of
  the VibeDock server.

## Testing a build on the display

**Devices → Firmware → Own firmware build** takes `firmware.bin` from `firmware/.pio/build/vibedock-m1/` and installs
it over USB or, signed, over Wi-Fi, without a server rebuild. **Use the built-in firmware** goes back.

## Testing on the computer

[`tests/tls-host/run.sh`](tests/tls-host/run.sh) builds Mbed TLS from the PlatformIO dependency with the firmware's
configuration and runs [`client.c`](tests/tls-host/client.c), which uses the same cipher suites and signature
algorithms as the firmware, against local `openssl s_server -tls1_3` instances: RSA certificate, ECDSA certificate, a
server that prefers AES-256 (the client must still get AES-128), and a wrong host name that must be rejected. It sends a 2 kB request and reads the
answer, including session tickets. Run it after changing the configuration:

```sh
vibedock-client/scripts/build.sh vibedock-m1   # downloads Mbed TLS once
vibedock-client/tests/tls-host/run.sh
```

It does not cover the ESP8266 specifics (IRAM, stack, timing); those show only on the device log.

## Open points

- TLS 1.3 for the ESP32 boards, waiting for hardware to test on. The M1's approach does not carry over as it is:
  the boards now use Arduino-ESP32 3.3.12, but its precompiled libraries do not enable TLS 1.3. A second Mbed TLS
  next to the core's library clashes by symbol name. Enabling it still needs a compatible custom ESP-IDF build
  with `CONFIG_MBEDTLS_SSL_PROTO_TLS1_3=y`, followed by validation of certificate checks, pinning and memory.
  Renaming the symbols of a second Mbed TLS (objcopy) would work too, but breaks with every core update.
- The heap margin of the full check is small; the log's heap and stack figures show how much is left. The pinned check needs less.
