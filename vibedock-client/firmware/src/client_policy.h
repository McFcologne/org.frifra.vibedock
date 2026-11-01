#pragma once
#include <stdint.h>
#include <stddef.h>
#include <math.h>
#include <string>
// Pure timing and format rules of the VibeDock client (protocol v2), shared with the native test.
namespace client_policy {
// Coarse voltage-only estimate for a single Li-ion cell, following the Waveshare 1.54-inch example.
// This is not a fuel gauge or battery-presence detector; a charger without a cell can also show a voltage.
constexpr int batteryEstimate(uint32_t millivolts) {
  return millivolts < 3000 || millivolts > 4350 ? -1 :
      millivolts < 3520 ? 1 : millivolts < 3640 ? 20 : millivolts < 3760 ? 40 :
      millivolts < 3880 ? 60 : millivolts < 4000 ? 80 : 100;
}
// ---- experimental battery power saving (boards that measure their battery) ----
// Battery operation: no USB host sends frames and the charger does not report charging. A charger without data
// lines next to a full cell also counts; saving power there does no harm.
constexpr bool onBattery(bool usbHost, bool charging) { return !usbHost && !charging; }
// The power source, read every powerCheckMs: it changes only after two readings in a row agree, so a USB host that
// misses a frame or a flickering charge signal does not switch the Wi-Fi mode back and forth.
constexpr uint32_t powerCheckMs = 5000;
struct PowerSource {
  bool battery = false; uint8_t agreeing = 0;
  // True when the source changed with this reading.
  bool update(bool reading) {
    if (reading == battery) { agreeing = 0; return false; }
    if (++agreeing < 2) return false;
    battery = reading; agreeing = 0; return true;
  }
};
// While saving power, the backlight dims after a minute without touch or button; the next touch only wakes it.
constexpr uint32_t dimAfterMs = 60000;
constexpr bool dimDue(uint32_t now, uint32_t lastActivity) { return now - lastActivity >= dimAfterMs; }

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
// The server's announced next pull (X-VibeDock-Next-Pull: "<seconds>; state=current|pending"): seconds 2-3600;
// pending means a source of the display was still being asked. Anything else: the poll interval applies.
constexpr uint32_t minNextPull = 2, maxNextPull = 3600;
inline bool parseNextPull(const char *text, uint32_t &seconds, bool &pending) {
  uint32_t value = 0; int digits = 0;
  while (*text >= '0' && *text <= '9') { if (++digits > 5) return false; value = value * 10 + uint32_t(*text++ - '0'); }
  if (!digits || value < minNextPull || value > maxNextPull) return false;
  const char *current = "; state=current", *waiting = "; state=pending";
  auto is = [](const char *a, const char *b) { while (*b) if (*a++ != *b++) return false; return *a == 0; };
  if (is(text, current)) pending = false; else if (is(text, waiting)) pending = true; else return false;
  seconds = value; return true;
}
// The announced wait counts only for a pull that ended well: after an aborted or refused image the poll interval
// applies again (or the immediate retry), never a wait of up to ten minutes.
constexpr uint32_t waitAfterImage(bool complete, uint32_t announcedMs) { return complete ? announcedMs : 0; }
// An image cut off at row `cut` on the way: which image the rows from there on show. `split` rows of the top were on
// screen before (all of them for a complete image), the rest of the bottom; unknown when nothing was known.
enum class Below : uint8_t { Top, Bottom, Unknown };
constexpr Below belowCut(bool known, uint16_t cut, uint16_t split, uint16_t height) {
  return !known ? Below::Unknown : split >= height ? Below::Top : cut >= split ? Below::Bottom : Below::Unknown;
}
// Bytes outside A-Z, a-z, 0-9 and "-._~" as %XX (upper case), e.g. a Wi-Fi name in a header field.
inline std::string percentEncoded(const char *text) {
  static const char hex[] = "0123456789ABCDEF"; std::string out;
  for (const unsigned char *p = reinterpret_cast<const unsigned char *>(text); *p; p++) {
    unsigned char c = *p;
    if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') out += char(c);
    else { out += '%'; out += hex[c >> 4]; out += hex[c & 15]; }
  }
  return out;
}
// A pull is due once the wait has passed (the announced one, or the poll interval); survives millis() rollover.
constexpr bool waited(uint32_t now, uint32_t since, uint32_t ms) { return uint32_t(now - since) >= ms; }
// Stale after three poll intervals without a successful pull, or two poll intervals after an announced longer wait.
constexpr bool imageStale(uint32_t now, uint32_t lastImage, uint32_t pollSeconds, uint32_t waitMs) {
  return uint32_t(now - lastImage) >= (waitMs + 2u * clampPoll(pollSeconds) * 1000u > 3u * clampPoll(pollSeconds) * 1000u
      ? waitMs + 2u * clampPoll(pollSeconds) * 1000u : 3u * clampPoll(pollSeconds) * 1000u);
}
// After a failed connection the next announced server address is tried.
constexpr uint8_t nextServer(uint8_t current, uint8_t count) { return count == 0 ? 0 : uint8_t((current + 1) % count); }
// The address accepted the TCP connection but sent no HTTP answer: the server is up and another address (the external
// one, usually unreachable from an IoT network and costly in memory over TLS) cannot answer better, so stay. Every
// sixth silent failure in a row still lets the next address try, so a silent wrong service cannot block the working one.
// An address that did not even accept TCP is the case the next one is for. silentInRow counts this failure too.
constexpr uint8_t silentRetriesBeforeNext = 5;
constexpr bool staysAtServer(bool stalledAfterTls, bool firstFailure, uint8_t silentInRow) {
  return (stalledAfterTls && firstFailure) || (silentInRow > 0 && silentInRow % (silentRetriesBeforeNext + 1) != 0);
}
// The server may ask for an immediate pull (POST /pull, e.g. after a design change); at most every 5 seconds.
constexpr uint32_t pullRequestMs = 5000;
constexpr bool pullRequestAllowed(uint32_t now, uint32_t lastRequest) { return uint32_t(now - lastRequest) >= pullRequestMs; }
constexpr bool pairingLocked(uint8_t wrongAttempts) { return wrongAttempts >= maxPairingAttempts; }
// Power-cycling three times, each time within quickBootMs after power-on, resets the device to factory settings.
// Ten seconds: boards with a start sound and a slower start (Waveshare 1.54) left too little time with five.
constexpr uint8_t resetPowerCycles = 3;
constexpr uint32_t quickBootMs = 10000;
constexpr uint32_t usbBootGraceMs = 2000; // allow a flasher HELLO before a third-start factory reset
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
inline bool parseImageFormat(const char *text, ImageSize &size, bool delta = false) {
  const char *prefix = delta ? "rgb565-delta-be-" : "rgb565-be-";
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
// The defaults are calibrated by eye against the uncorrected image on the device page: these on the 240x240 ST7789
// IPS panel of the VibeTV M1 (until 0.2.0: 250/91/94/73), the other boards set their own (VIBEDOCK_TONE_*; the
// Waveshare 1.47 calibrated, the rest neutral until calibrated on the device).
#ifndef VIBEDOCK_TONE_GAMMA
#define VIBEDOCK_TONE_GAMMA 320
#define VIBEDOCK_TONE_RED 100
#define VIBEDOCK_TONE_GREEN 74
#define VIBEDOCK_TONE_BLUE 40
#endif
struct ToneSettings {
  uint16_t gamma = VIBEDOCK_TONE_GAMMA; uint8_t red = VIBEDOCK_TONE_RED, green = VIBEDOCK_TONE_GREEN, blue = VIBEDOCK_TONE_BLUE;
  bool operator==(const ToneSettings &o) const { return gamma == o.gamma && red == o.red && green == o.green && blue == o.blue; }
};
// Settings saved without the "custom" mark (firmware before 0.2.0) held the VibeTV M1's defaults of that time on
// every board: they count as a calibration only where they differ from those, so such devices get the new defaults.
constexpr bool legacyCalibration(long gamma, long red, long green, long blue) {
  return gamma != 250 || red != 91 || green != 94 || blue != 73;
}
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
// Touch panels: a finished touch is a swipe when it went at least minSwipe pixels, clearly more in one direction
// than in the other (1.5 times; narrow panels leave little room sideways).
enum class Swipe : uint8_t { None, Left, Right, Up, Down };
constexpr int minSwipe = 30;
constexpr int distance(int v) { return v < 0 ? -v : v; }
constexpr Swipe swipeOf(int dx, int dy) {
  return distance(dx) >= minSwipe && 2 * distance(dx) >= 3 * distance(dy) ? (dx < 0 ? Swipe::Left : Swipe::Right)
       : distance(dy) >= minSwipe && 2 * distance(dy) >= 3 * distance(dx) ? (dy < 0 ? Swipe::Up : Swipe::Down)
       : Swipe::None;
}
// A tap: a short touch that hardly moved; two within doubleTapMs are a double tap (slideshow on or off).
constexpr int maxTapMove = 12; constexpr uint32_t maxTapMs = 350, doubleTapMs = 400;
constexpr bool isTap(int dx, int dy, uint32_t heldMs) { return distance(dx) < maxTapMove && distance(dy) < maxTapMove && heldMs < maxTapMs; }
constexpr bool doubleTap(uint32_t lastTap, uint32_t now) { return lastTap != 0 && now - lastTap < doubleTapMs; }
// A circle drawn with one finger turns the picture (the touch controllers report only one finger). The points of a
// touch, every 20 ms, must go round their centre by at least minCircleDegrees on an average radius of at least
// minCircleRadius pixels, never cut close through the centre (no back and forth), and end near where they started.
// A circle slower than the points held (about three seconds) does not count.
enum class Circle : uint8_t { None, Clockwise, CounterClockwise };
constexpr uint16_t maxCirclePoints = 160; constexpr float minCircleDegrees = 300; constexpr int minCircleRadius = 35;
struct TouchPath {
  int16_t x[maxCirclePoints], y[maxCirclePoints]; uint16_t count = 0; bool overflow = false;
  void clear() { count = 0; overflow = false; }
  void add(int16_t px, int16_t py) { if (count < maxCirclePoints) { x[count] = px; y[count] = py; count++; } else overflow = true; }
  // In panel coordinates (y downwards): a positive sweep turns clockwise on the screen.
  Circle circle() const {
    if (overflow || count < 12) return Circle::None;
    float cx = 0, cy = 0;
    for (uint16_t i = 0; i < count; i++) { cx += x[i]; cy += y[i]; }
    cx /= count; cy /= count;
    float radius = 0, nearest = 1e9f, sweep = 0, previous = atan2f(y[0] - cy, x[0] - cx);
    for (uint16_t i = 0; i < count; i++) {
      float r = sqrtf((x[i] - cx) * (x[i] - cx) + (y[i] - cy) * (y[i] - cy)); radius += r; if (r < nearest) nearest = r;
      if (i == 0) continue;
      float angle = atan2f(y[i] - cy, x[i] - cx), step = angle - previous;
      if (step > float(M_PI)) step -= 2 * float(M_PI); else if (step < -float(M_PI)) step += 2 * float(M_PI);
      sweep += step; previous = angle;
    }
    radius /= count;
    float gap = sqrtf(float((x[count - 1] - x[0]) * (x[count - 1] - x[0]) + (y[count - 1] - y[0]) * (y[count - 1] - y[0])));
    if (radius < minCircleRadius || nearest < radius * 0.4f || gap > radius * 1.2f) return Circle::None;
    float degrees = sweep * 180 / float(M_PI);
    return degrees >= minCircleDegrees ? Circle::Clockwise : degrees <= -minCircleDegrees ? Circle::CounterClockwise : Circle::None;
  }
};
// Backlight in percent: swipes up and down change it in steps, never below minBrightness (the screen stays readable).
constexpr uint8_t minBrightness = 10, brightnessStep = 15;
constexpr uint8_t clampBrightness(long v) { return v < minBrightness ? minBrightness : v > 100 ? 100 : uint8_t(v); }
constexpr uint8_t nextBrightness(uint8_t percent, bool up) { return clampBrightness(long(percent) + (up ? brightnessStep : -long(brightnessStep))); }
// Dimmed while saving power: the lowest step, or lower than that never.
constexpr uint8_t dimmedBrightness(uint8_t percent) { return percent < minBrightness ? percent : minBrightness; }
// ---- diagnosis: why the display started, and the last state of the run before (kept across a reset) ----
// What the firmware meant to do when it restarted itself; a restart without one is unexpected.
enum class Intent : uint8_t { None = 0, Update = 1, Setup = 2, FactoryReset = 3, LowMemory = 4 };
// The reset as the platform reports it, reduced to what tells planned from unexpected.
enum class ResetKind : uint8_t { PowerOn, External, Software, Panic, Watchdog, Brownout, DeepSleep, Other };
// The start kind for the server (DeviceDiagnostics.KINDS): a software restart with an intent is that intent, any
// other software restart, a panic, a watchdog or a brownout is unexpected.
constexpr const char *startKind(ResetKind reset, Intent intent) {
  return reset == ResetKind::Software && intent == Intent::Update ? "update"
       : reset == ResetKind::Software && intent == Intent::Setup ? "setup"
       : reset == ResetKind::Software && intent == Intent::FactoryReset ? "factory-reset"
       : reset == ResetKind::Software && intent == Intent::LowMemory ? "low-memory"
       : reset == ResetKind::PowerOn ? "power-on" : reset == ResetKind::External ? "external" : reset == ResetKind::Software ? "software"
       : reset == ResetKind::Panic ? "crash" : reset == ResetKind::Watchdog ? "watchdog" : reset == ResetKind::Brownout ? "brownout"
       : reset == ResetKind::DeepSleep ? "deep-sleep" : "other";
}
// What the display was doing; the names the server knows (DeviceDiagnostics.PHASES).
enum class Phase : uint8_t { Start = 1, WiFi, Connect, Tls, Image, Draw, Config, Log, Ota, Idle, Portal };
constexpr const char *phaseName(uint8_t phase) {
  return phase == 1 ? "start" : phase == 2 ? "wifi" : phase == 3 ? "connect" : phase == 4 ? "tls" : phase == 5 ? "image" : phase == 6 ? "draw"
       : phase == 7 ? "config" : phase == 8 ? "log" : phase == 9 ? "ota" : phase == 10 ? "idle" : phase == 11 ? "portal" : nullptr;
}
// The state kept in memory that survives a reset but not a power cut (RTC memory). check covers the other fields:
// memory that holds anything else after a power cut, or a record of another firmware layout, counts as unknown.
struct DiagRecord {
  uint32_t magic, boot, uptime, heapFree, heapMin;
  int8_t rssi; uint8_t phase, intent, drops;
  uint32_t check;
};
constexpr uint32_t diagMagic = 0x56444732;  // "VDG2"
inline uint32_t diagCheck(const DiagRecord &r) {
  const uint8_t *bytes = reinterpret_cast<const uint8_t *>(&r); uint32_t hash = 2166136261u;
  for (size_t i = 0; i < offsetof(DiagRecord, check); i++) { hash ^= bytes[i]; hash *= 16777619u; }
  return hash ^ 0x5a5a5a5au;
}
inline bool diagValid(const DiagRecord &r) {
  return r.magic == diagMagic && r.check == diagCheck(r) && r.boot != 0 && phaseName(r.phase) != nullptr && r.intent <= 3;
}
// X-VibeDock-Orientation as quarter turns clockwise: "upright" 0, "rotated-90" 1, "flipped" 2, "rotated-270" 3. The
// quarter turns only on a square panel (the picture would not fit otherwise); anything else keeps the current turn.
constexpr bool sameText(const char *a, const char *b) { return *a == *b && (*a == 0 || sameText(a + 1, b + 1)); }
constexpr uint8_t turnsOf(const char *orientation, uint8_t current, bool square) {
  return sameText(orientation, "upright") ? 0 : sameText(orientation, "flipped") ? 2
       : square && sameText(orientation, "rotated-90") ? 1 : square && sameText(orientation, "rotated-270") ? 3 : current;
}
// A swipe on the panel turned into the picture's directions: the touch coordinates do not turn with the picture.
inline void turnSwipe(int &dx, int &dy, uint8_t turns) {
  for (uint8_t i = 0; i < turns % 4; i++) { int x = dx; dx = dy; dy = -x; }
}
}
