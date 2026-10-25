- **PSRAM on the ESP32-S3 boards:** the Waveshare 1.54, 1.47 and S3 1.69 and the LilyGO T-Display-S3 (all ESP32-S3R8
  with 8 MB octal PSRAM) now use it. The copy of the picture lives there, so these displays too turn their picture and
  apply a new colour correction without loading it again (before, the 1.54 logged *No memory for a copy of the
  picture*). Wi-Fi buffers and larger allocations move there as well, leaving more internal memory for TLS. Needs the
  new firmware.
