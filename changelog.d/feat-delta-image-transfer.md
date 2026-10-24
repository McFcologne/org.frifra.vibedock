- **Smaller image transfers:** with the new firmware a display gets only the changed parts of its picture instead
  of the whole image (usually a few hundred bytes to a few KB instead of 112 KB on a 240 × 240 panel). The server
  keeps the last images in memory; after a restart, a forced refresh or any doubt the display gets the whole image.
  Older firmware and older servers keep working with whole images. The server log shows the bytes saved per image.
- **Pulls when something can change:** every answer tells the display when its picture can change next (after the
  next poll of the sources it shows), and the display pulls then instead of every 30 s. With sources polled every
  minute, the unchanged answers in between are gone; with longer intervals it pulls less often (at most every 10
  minutes for displays the server can reach directly, 2 minutes for the others). Needs the new firmware. The poll
  interval under *Settings* is now the fallback for older firmware and failed pulls; the server log shows the
  announced time with each image.
