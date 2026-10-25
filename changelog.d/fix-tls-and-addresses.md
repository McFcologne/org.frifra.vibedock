- **Pinned certificate on every board:** the ESP32 boards (Waveshare 1.54, 1.47, S3 1.69, C6 1.69, LilyGO, CYD, C3)
  now use the simple check the M1 already had: they compare the server certificate with its pin and run the full
  chain check only when that fails, for example after the certificate was renewed. Before, they checked the whole
  chain with every pull. Needs the new firmware.
- **No waiting for the server's own TLS check:** image answers after a display's full certificate check no longer
  wait for the server to check its external address. Where the server could not reach it (a wrong
  `EXTERNAL_TLS_PROBE_URL`, no NAT loopback), every pull of the ESP32 boards took the 5-second connect timeout; the
  check now runs in the background and a failure is retried after ten minutes. Server only.
- **Back to the LAN after a restart:** a display that last answered on its second (usually external) address tries
  the first one again once after each start, without an error message when it is not reachable. Before, it stayed on
  the external address until that failed. Needs the new firmware.
