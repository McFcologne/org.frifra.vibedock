#pragma once
#include <stdint.h>
#include <stddef.h>
#include <math.h>
// Pure timing and format rules of the VibeDock client (protocol v2), shared with the native test.
namespace client_policy {
constexpr uint32_t minPollSeconds = 10, maxPollSeconds = 3600, defaultPollSeconds = 30;
constexpr uint8_t maxPairingAttempts = 5;
constexpr uint32_t clampPoll(uint32_t seconds) {
  return seconds < minPollSeconds ? minPollSeconds : seconds > maxPollSeconds ? maxPollSeconds : seconds;
}
// A pull is due once the poll interval has passed; unsigned arithmetic survives millis() rollover.
constexpr bool pullDue(uint32_t now, uint32_t lastPull, uint32_t pollSeconds) {
  return uint32_t(now - lastPull) >= clampPoll(pollSeconds) * 1000u;
}
// The image counts as stale after three poll intervals without a successful pull (200 or 304).
constexpr bool imageStale(uint32_t now, uint32_t lastImage, uint32_t pollSeconds) {
  return uint32_t(now - lastImage) >= 3u * clampPoll(pollSeconds) * 1000u;
}
// After a failed connection the next announced server address is tried.
constexpr uint8_t nextServer(uint8_t current, uint8_t count) { return count == 0 ? 0 : uint8_t((current + 1) % count); }
// The server may ask for an immediate pull (POST /pull, e.g. after a design change); at most every 5 seconds.
constexpr uint32_t pullRequestMs = 5000;
constexpr bool pullRequestAllowed(uint32_t now, uint32_t lastRequest) { return uint32_t(now - lastRequest) >= pullRequestMs; }
constexpr bool pairingLocked(uint8_t wrongAttempts) { return wrongAttempts >= maxPairingAttempts; }
// Power-cycling three times, each time within quickBootMs after power-on, resets the device to factory settings.
constexpr uint8_t resetPowerCycles = 3;
constexpr uint32_t quickBootMs = 5000;
constexpr bool factoryResetDue(uint8_t quickBoots) { return quickBoots >= resetPowerCycles; }
// Reset reasons of the ESP8266 SDK: 0 power on, 6 external reset. Many boards report 6 when plugged in, because
// their reset line rises after the supply; both count as a power cycle. Watchdog, exception and software
// restarts (1-5) do not.
constexpr bool countsAsPowerCycle(uint32_t resetReason) { return resetReason == 0 || resetReason == 6; }
// The device's own screens (setup, pairing, messages) show the IP in rows 220-239, columns 0-149. Server images
// carry the IP themselves (protocol header X-VibeDock-Footer: server) and are drawn over the whole screen.
constexpr uint16_t footerTop = 220;
// The footer takes the image's background (pixel 0,220) and picks black text on light colours, white otherwise.
constexpr bool lightColor(uint16_t rgb565) {
  return (299u * ((rgb565 >> 11) * 255u / 31u) + 587u * (((rgb565 >> 5) & 63u) * 255u / 63u) + 114u * ((rgb565 & 31u) * 255u / 31u)) / 1000u > 140u;
}
// FNV-1a over one image row; never 0, so a cleared row cache always differs from a drawn row.
inline uint32_t rowHash(const uint8_t *data, size_t length) {
  uint32_t hash = 2166136261u;
  for (size_t i = 0; i < length; i++) { hash ^= data[i]; hash *= 16777619u; }
  return hash ? hash : 1;
}
// SHA-1 fingerprint of the server certificate as 40 lowercase hex characters (the simple https check).
inline bool parseFingerprint(const char *text, uint8_t out[20]) {
  for (int i = 0; i < 40; i++) {
    char c = text[i];
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  }
  if (text[40] != 0) return false;
  auto value = [](char c) { return uint8_t(c <= '9' ? c - '0' : c - 'a' + 10); };
  for (int i = 0; i < 20; i++) out[i] = uint8_t(value(text[2 * i]) << 4 | value(text[2 * i + 1]));
  return true;
}
// The server's request to send the log (X-VibeDock-Send-Log): 32 lowercase hex characters, nothing else.
inline bool validLogRequest(const char *text) {
  for (int i = 0; i < 32; i++) {
    char c = text[i];
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  }
  return text[32] == 0;
}
// Server images come as "rgb565-be-<width>x<height>", rendered for the display type chosen in the administration.
// Any size within the limits is accepted and scaled to the screen.
constexpr uint16_t maxImageSide = 480;
struct ImageSize { uint16_t width = 0, height = 0; uint32_t bytes() const { return 2u * width * height; } };
inline bool parseImageFormat(const char *text, ImageSize &size) {
  const char *prefix = "rgb565-be-";
  for (int i = 0; prefix[i]; i++, text++) if (*text != prefix[i]) return false;
  auto number = [](const char *&t, char end, uint16_t &out) {
    uint32_t value = 0; int digits = 0;
    for (; *t >= '0' && *t <= '9' && digits < 4; t++, digits++) value = value * 10 + (*t - '0');
    if (digits == 0 || *t != end || value < 16 || value > maxImageSide) return false;
    out = uint16_t(value); return true;
  };
  ImageSize parsed;
  if (!number(text, 'x', parsed.width)) return false;
  text++;
  if (!number(text, 0, parsed.height)) return false;
  size = parsed; return true;
}
// Nearest-neighbour scaling to full screen: the first destination row (or column) at or after a source line, and the
// source column of a destination column. Same size maps 1:1.
constexpr uint16_t firstTarget(uint32_t source, uint32_t sourceSize, uint32_t targetSize) {
  return uint16_t((source * targetSize + sourceSize - 1) / sourceSize);
}
constexpr uint16_t sourceOf(uint32_t target, uint32_t targetSize, uint32_t sourceSize) {
  return uint16_t(target * sourceSize / targetSize);
}
constexpr uint16_t pixel(uint8_t high, uint8_t low) { return uint16_t(high) << 8 | low; }
// Colour correction of the panel: one gamma for the brightness curve, one gain per channel for the white balance.
// The gamma registers of the ST7789 shape all three channels alike, so the colour cast can only be fixed here.
// gamma is in hundredths (250 = 2.5, darker mid and dark tones), gains in percent of the full channel.
// The defaults are calibrated on the 240x240 ST7789 IPS panel by eye against the server preview.
struct ToneSettings { uint16_t gamma = 250; uint8_t red = 91, green = 94, blue = 73; };
constexpr uint16_t minGamma = 50, maxGamma = 350; constexpr uint8_t minGain = 40, maxGain = 100;
constexpr uint16_t clampGamma(long v) { return v < minGamma ? minGamma : v > maxGamma ? maxGamma : uint16_t(v); }
constexpr uint8_t clampGain(long v) { return v < minGain ? minGain : v > maxGain ? maxGain : uint8_t(v); }
struct Tone {
  uint8_t r[32], g[64], b[32];
  explicit Tone(const ToneSettings &s = ToneSettings()) { build(s); }
  void build(const ToneSettings &s) {
    curve(r, 31, s.red, s.gamma); curve(g, 63, s.green, s.gamma); curve(b, 31, s.blue, s.gamma);
  }
  uint16_t apply(uint16_t c) const { return uint16_t(r[c >> 11] << 11 | g[(c >> 5) & 63] << 5 | b[c & 31]); }
 private:
  static void curve(uint8_t *out, int top, uint8_t gain, uint16_t gamma) {
    for (int i = 0; i <= top; i++) out[i] = uint8_t(lround(top * (gain / 100.0) * pow(double(i) / top, gamma / 100.0)));
  }
};
}
