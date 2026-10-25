- **Rest only after an abort:** a picture that breaks off on the way (weak Wi-Fi) keeps its drawn rows; the next pull
  gets only the rest as a delta (`X-VibeDock-Resume`), and the half-loaded picture stays while each retry gets further.
- **Turns without a new picture:** ESP32 displays with the memory for a copy of their picture turn it themselves
  (180, 90 and 270 degrees) and redraw it for a new colour correction; only the normal changes follow.
- **No modem sleep during pulls:** with battery power saving on, Wi-Fi modem sleep is off while the display pulls its
  picture or loads an update.
- **Battery level unknown:** displays that cannot measure their battery show three dots in the battery icon instead of
  an empty battery.
