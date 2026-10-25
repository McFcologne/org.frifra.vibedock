- **Pinned certificate on every board:** the ESP32 boards (Waveshare 1.54, 1.47, S3 1.69, C6 1.69, LilyGO, CYD, C3)
  now use the simple check the M1 already had: they compare the server certificate with its pin and run the full
  chain check only when that fails, for example after the certificate was renewed. Before, they checked the whole
  chain with every pull. The device log names the check after the status: *HTTP 200, TLS pinned* or *TLS full check*
  (M1: *TLS 1.3 pinned …* or *TLS 1.3 full check …*). Needs the new firmware.
- **Pin after a renewed certificate:** after a full check every board keeps the server's pin only when it matches the
  certificate it has just verified, otherwise that certificate (log line *Server pin differs from the verified
  certificate, pinned the verified one*), and the server no longer sends an older pin after a failed refresh. Before,
  a pin from before the renewal could make every pull fall back to the full check for up to ten minutes. Needs the new
  firmware and server.
- **No waiting for the server's own TLS check:** image answers after a display's full certificate check no longer
  wait for the server to check its external address. Where the server could not reach it (no route from the
  container to the reverse proxy, no NAT loopback), every pull of the ESP32 boards took the 5-second connect timeout;
  the check now runs in the background and a failure is retried after ten minutes. Server only.
- **Back to the LAN after a restart:** a display that last answered on its second (usually external) address tries
  the first one again once after each start, without an error message when it is not reachable. Before, it stayed on
  the external address until that failed. Needs the new firmware.
