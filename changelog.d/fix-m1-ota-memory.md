- **Wi-Fi update of the M1:** the update failed with *no space: Out of memory* when the heap had no 4 KB in one piece
  left after the TLS handshake. The display now keeps that block from before the download's connection and hands it to
  the updater. A failed download names its reason (for example the TLS error) instead of *download failed*. Needs the
  new firmware, installed once over USB.
