// Black-and-white e-paper panel with an SSD1681 controller (200 x 200, Waveshare ESP32-S3-ePaper-1.54 V2).
// It takes the place of TFT_eSPI for the calls main.cpp makes on `panel` (width, height, pushImage, drawPixel, fillRect,
// fillScreen, setRotation): they only write a grey frame in memory. service() turns it into one bit per pixel (levels, then
// ordered dither) and refreshes the panel once the picture has been quiet for a moment, so a transfer of 200 rows is one refresh
// and not 200. An unchanged picture is never sent again (every refresh wears the panel).
// - Refresh: a partial one by default (about 0.3 s, the old picture stays in the second RAM as the reference); a full
//   one (about 2 s, flashing) at the first picture, every FULL_EVERY refreshes and when much of the picture changed, to
//   clear the ghosts. VIBEDOCK_EPAPER_PARTIAL=0 makes every refresh a full one.
// - Pins (vendor schematic and example 07_BATT_PWR_Test of waveshareteam/ESP32-S3-ePaper-1.54, V2): DC 10, CS 11, SCK 12,
//   MOSI 13, RST 9, BUSY 8 (high = busy), panel supply GPIO 6 (low = on), audio supply GPIO 42 (high = off).
// The controller commands are those of the SSD1681 data sheet; the values for this panel (driver output C7 00 01, data
// entry mode 01, window with Y counting down, border waveform) follow the vendor's example.
#pragma once
#if defined(VIBEDOCK_EPAPER)
#include <Arduino.h>
#include <SPI.h>
#include <esp_heap_caps.h>

#ifndef VIBEDOCK_EPAPER_DC
#define VIBEDOCK_EPAPER_DC 10
#define VIBEDOCK_EPAPER_CS 11
#define VIBEDOCK_EPAPER_SCK 12
#define VIBEDOCK_EPAPER_MOSI 13
#define VIBEDOCK_EPAPER_RST 9
#define VIBEDOCK_EPAPER_BUSY 8
#define VIBEDOCK_EPAPER_POWER 6
#define VIBEDOCK_EPAPER_AUDIO_POWER 42
#endif
#ifndef VIBEDOCK_EPAPER_PARTIAL
#define VIBEDOCK_EPAPER_PARTIAL 1
#endif

class EPaper {
 public:
  static constexpr int N = 200, ROW_BYTES = N / 8, BYTES = ROW_BYTES * N;
  static constexpr uint8_t FULL_EVERY = 12;          // a full refresh after this many partial ones
  static constexpr uint32_t QUIET_MS = 1500;          // the picture stays unchanged this long before it is sent
  static constexpr uint32_t MIN_GAP_MS = 4000;        // at least this long between two refreshes
  int16_t width() const { return N; }
  int16_t height() const { return N; }
  void setSwapBytes(bool) {}
  // Quarter turns, clockwise: the logical picture is turned before it is stored.
  void setRotation(uint8_t r) { rotation = r & 3; }
  // The own screens are drawn light on dark; on paper they are printed dark on light.
  void setInvert(bool on) { invert = on; }

  bool init() {
    if (grey) return true;
    grey = static_cast<uint8_t *>(heap_caps_malloc(N * N, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    if (!grey) grey = static_cast<uint8_t *>(heap_caps_malloc(N * N, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));
    if (!grey) return false;
    memset(grey, 255, N * N);
    pinMode(VIBEDOCK_EPAPER_AUDIO_POWER, OUTPUT); digitalWrite(VIBEDOCK_EPAPER_AUDIO_POWER, HIGH);  // amplifier off
    pinMode(VIBEDOCK_EPAPER_POWER, OUTPUT); digitalWrite(VIBEDOCK_EPAPER_POWER, LOW);               // panel supply on
    pinMode(VIBEDOCK_EPAPER_DC, OUTPUT); pinMode(VIBEDOCK_EPAPER_CS, OUTPUT); digitalWrite(VIBEDOCK_EPAPER_CS, HIGH);
    pinMode(VIBEDOCK_EPAPER_RST, OUTPUT); pinMode(VIBEDOCK_EPAPER_BUSY, INPUT);
    SPI.begin(VIBEDOCK_EPAPER_SCK, -1, VIBEDOCK_EPAPER_MOSI, -1);  // CS is switched by hand
    dirty = true; changedAt = millis();
    return true;
  }
  // Writes of the callers: the grey of a RGB565 colour.
  void fillScreen(uint32_t colour) { fillRect(0, 0, N, N, colour); }
  void fillRect(int32_t x, int32_t y, int32_t w, int32_t h, uint32_t colour) {
    for (int32_t j = y; j < y + h; j++) for (int32_t i = x; i < x + w; i++) put(i, j, greyOf(colour));
    settle();
  }
  void drawPixel(int32_t x, int32_t y, uint32_t colour) { put(x, y, greyOf(colour)); settle(); }
  void pushImage(int32_t x, int32_t y, int32_t w, int32_t h, uint16_t *data) {
    for (int32_t j = 0; j < h; j++) for (int32_t i = 0; i < w; i++) put(x + i, y + j, greyOf(data[j * w + i]));
    settle();
  }
  // Called from loop() and after a finished picture (force): sends the frame when it has been quiet.
  void service(bool force = false) {
    if (!grey || !dirty) return;
    uint32_t now = millis();
    if (!force && (now - changedAt < QUIET_MS || (refreshed && now - lastRefresh < MIN_GAP_MS))) return;
    refresh();
  }
  bool pending() const { return dirty; }
  uint32_t refreshes() const { return count; }

 private:
  uint8_t *grey = nullptr;
  uint8_t shown[BYTES], next[BYTES];
  uint8_t rotation = 0, partials = 0;
  bool invert = false, dirty = false, hasShown = false, refreshed = false, moved = false;
  uint32_t changedAt = 0, lastRefresh = 0, count = 0;

  static uint8_t greyOf(uint32_t c) {  // RGB565 to luminance
    uint32_t r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
    r = (r << 3) | (r >> 2); g = (g << 2) | (g >> 4); b = (b << 3) | (b >> 2);
    return uint8_t((r * 77 + g * 150 + b * 29) >> 8);
  }
  void put(int32_t x, int32_t y, uint8_t value) {
    if (x < 0 || y < 0 || x >= N || y >= N || !grey) return;
    int32_t px = x, py = y;
    switch (rotation) {
      case 1: px = N - 1 - y; py = x; break;
      case 2: px = N - 1 - x; py = N - 1 - y; break;
      case 3: px = y; py = N - 1 - x; break;
    }
    if (invert) value = 255 - value;
    uint8_t &cell = grey[py * N + px];
    if (cell != value) { cell = value; moved = true; }
  }
  // Only a frame that really differs starts the wait: the screens that redraw the same content every few hundred
  // milliseconds must not keep it from ever being quiet.
  void settle() { if (moved) { moved = false; dirty = true; changedAt = millis(); } }

  // The paper tones of the themes (an off-white background, a dark grey ink) become pure white and black: below BLACK_BELOW
  // black, above WHITE_ABOVE white, in between stretched. What stays grey then is dithered with a 4 x 4 ordered pattern.
  static constexpr int BLACK_BELOW = 48, WHITE_ABOVE = 208;
  void toBits(uint8_t *bits) const {
    static const uint8_t bayer[4][4] = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    memset(bits, 0, BYTES);
    for (int y = 0; y < N; y++)
      for (int x = 0; x < N; x++) {
        int g = grey[y * N + x];
        g = g <= BLACK_BELOW ? 0 : g >= WHITE_ABOVE ? 255 : (g - BLACK_BELOW) * 255 / (WHITE_ABOVE - BLACK_BELOW);
        if (g > bayer[y & 3][x & 3] * 16 + 8) bits[y * ROW_BYTES + (x >> 3)] |= 0x80 >> (x & 7);  // 1 = white
      }
  }
  void command(uint8_t c) { digitalWrite(VIBEDOCK_EPAPER_DC, LOW); send(&c, 1); }
  void data(uint8_t d) { digitalWrite(VIBEDOCK_EPAPER_DC, HIGH); send(&d, 1); }
  void send(const uint8_t *bytes, size_t n) {
    SPI.beginTransaction(SPISettings(8000000, MSBFIRST, SPI_MODE0));
    digitalWrite(VIBEDOCK_EPAPER_CS, LOW);
    SPI.writeBytes(bytes, n);
    digitalWrite(VIBEDOCK_EPAPER_CS, HIGH);
    SPI.endTransaction();
  }
  void ram(uint8_t which, const uint8_t *bits) {
    command(0x4E); data(0);                       // X counter
    command(0x4F); data(N - 1); data(0);          // Y counter: counts down from the last row
    command(which);
    digitalWrite(VIBEDOCK_EPAPER_DC, HIGH);
    for (int offset = 0; offset < BYTES; offset += 500) send(bits + offset, 500);
  }
  bool busy(uint32_t timeoutMs) {  // high = busy
    uint32_t start = millis();
    while (digitalRead(VIBEDOCK_EPAPER_BUSY) == HIGH) {
      if (millis() - start > timeoutMs) return false;
      delay(5);
    }
    return true;
  }
  bool reset() {
    digitalWrite(VIBEDOCK_EPAPER_RST, HIGH); delay(20);
    digitalWrite(VIBEDOCK_EPAPER_RST, LOW); delay(10);
    digitalWrite(VIBEDOCK_EPAPER_RST, HIGH); delay(20);
    if (!busy(3000)) return false;
    command(0x12);  // software reset
    delay(10);
    if (!busy(3000)) return false;
    command(0x01); data(0xC7); data(0x00); data(0x01);   // 200 gate lines
    command(0x11); data(0x01);                            // X counts up, Y counts down
    command(0x44); data(0); data(ROW_BYTES - 1);          // RAM window X in bytes
    command(0x45); data(N - 1); data(0); data(0); data(0);  // RAM window Y from the last row to the first
    command(0x18); data(0x80);                            // internal temperature sensor
    return true;
  }
  bool update(uint8_t mode) { command(0x22); data(mode); command(0x20); return busy(8000); }
  void refresh() {
    toBits(next);
    uint32_t changed = 0;
    if (hasShown) for (int i = 0; i < BYTES; i++) changed += __builtin_popcount(uint8_t(next[i] ^ shown[i]));
    if (hasShown && changed == 0) { dirty = false; return; }  // the same picture: nothing to wear out
    bool full = !VIBEDOCK_EPAPER_PARTIAL || !hasShown || partials >= FULL_EVERY || changed > N * N * 35 / 100;
    bool ok = reset();
    if (ok) {
      if (full) {
        command(0x3C); data(0x01);
        ram(0x24, next);
        ok = update(0xF7);                 // temperature, waveform from the OTP, display, power down
        ram(0x24, next); ram(0x26, next);  // the shown picture is the reference of the next partial refresh
        partials = 0;
      } else {
        command(0x3C); data(0x80);
        command(0x22); data(0xF8); command(0x20); ok = busy(3000);  // power up with the partial waveform
        if (ok) { ram(0x24, next); ok = update(0xFC); }
        command(0x22); data(0x83); command(0x20); busy(3000);        // power down
        ram(0x24, next); ram(0x26, next);
        partials++;
      }
    }
    command(0x10); data(0x01);  // deep sleep: the picture stays, the controller draws no current
    lastRefresh = millis(); refreshed = true; count++;
    if (ok) { memcpy(shown, next, BYTES); hasShown = true; dirty = false; }
    else { hasShown = false; dirty = false; }  // the next picture is drawn in full
  }
};
#endif
