- **About 99 % less data for displays:** the server still renders every picture, but a display with the new firmware
  receives only the parts that changed: usually a few hundred bytes to a few KB instead of 112 KB on a 240 × 240
  panel. Measured on a Waveshare 1.54 with readings every minute: 20 KB instead of 1.84 MB in 20 minutes. Whole
  pictures still come after a start, a theme change, a forced refresh, for photos and whenever in doubt. The server
  log shows the bytes saved per picture. Older firmware and older servers keep working with whole pictures.
- **Pulls only when new readings are due:** every answer tells the display when its picture can change next (after
  the next poll of the sources it shows), and the display pulls then instead of every 30 s, so the unchanged answers in
  between are gone; with longer source intervals it pulls even less (at most every 10 minutes when the server can reach
  it directly, 2 minutes otherwise). The poll interval under *Settings* becomes the fallback for older firmware and
  failed pulls. Update the server image and the display firmware (over Wi-Fi).
