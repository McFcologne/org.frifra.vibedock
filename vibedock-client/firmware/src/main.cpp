// VibeDock standalone client, protocol v2: the server invites the device once (POST /pair with the code shown on the
// screen), afterwards the device pulls its RGB565 image. See vibedock-server/CLIENT-PROTOCOL.md.
#include <Arduino.h>
#include "platform.h"
#include <time.h>
#include <memory>
#include <new>
#include <vector>
#include <DNSServer.h>
#include "icons.h"
#include <ArduinoJson.h>
#include <TFT_eSPI.h>
#include "client_policy.h"
#include "delta_image.h"
#include "touch.h"
#include "battery.h"
#include "audio.h"
#ifdef VIBEDOCK_AUDIO_ES8311
#include "volume_overlay.h"
#endif
#if defined(VIBEDOCK_BOOT_TRACE) && defined(ESP32) && ESP_ARDUINO_VERSION_MAJOR >= 3
#include "esp32-hal-periman.h"  // the pin owners in the start diagnosis
#endif
#include <qrcode.h>
#if defined(ESP32)
#include <esp_heap_caps.h>
#endif

#ifndef VIBEDOCK_VERSION
#define VIBEDOCK_VERSION "dev"  // set by version.py from the Git tag
#endif
TFT_eSPI panel;
// The device's own screens (start, setup, pairing, messages) are laid out for 240 x 240. Panels of another size draw
// them on a 240 x 240 canvas that present() scales onto the panel; server images go to the panel directly.
#if VIBEDOCK_CANVAS
TFT_eSprite canvas(&panel);
TFT_eSprite &display = canvas;
#else
TFT_eSPI &display = panel;
#endif
VibeWebServer web(80);
DNSServer setupDns;

// Persistent configuration. key is issued by the server on pairing; empty means "not paired".
String identifier, deviceName, apPassword, ssid, password, key, etag;
String wifiProblem;  // why the last connection to the saved network failed, shown in the setup portal
bool setupRequested = false;  // two quick power cycles: open the Wi-Fi setup although a network is saved
// True once the saved network has connected at least once. Only such a proven network is waited for when it is
// missing (e.g. a phone hotspot); a newly entered network that never worked opens the setup again with the reason.
bool uiDark = true;  // last appearance received from the server; also used by the setup portal
bool networkVerified = false;
String pin;  // SHA-1 fingerprint of the https server certificate, learned over a fully verified connection
String servers[2]; uint8_t serverCount = 0, serverIndex = 0, storedIndex = 0;
uint32_t pollSeconds = client_policy::defaultPollSeconds;
uint8_t brightness = 100;  // backlight in percent; touch boards change it with swipes up and down
String pendingGesture;  // a swipe for the server (X-VibeDock-Gesture), sent with the next image pull
uint8_t turns = 0;  // how the display is mounted: the picture turned clockwise in quarter turns (set in the administration)
bool applyOrientation(uint8_t next);
// Experimental battery power saving (boards with VIBEDOCK_BATTERY): allowed in the administration, active only on
// battery. See powerPoll.
bool powerSavingAllowed = false, powerSaving = false, dimmed = false;
client_policy::PowerSource powerSource;
uint32_t lastActivity = 0, lastPowerCheck = 0;
int batteryLevel = -1;  // the estimate of the last power check (1.54 and C6 1.69, steps of 20 %), -1 unknown
client_policy::ToneSettings toneSettings;  // colour correction of the panel, set on the status page
client_policy::Tone panelTone;

enum class Mode { Starting, Portal, Pairing, Paired };
Mode mode = Mode::Starting;
String pairingCode, shownIp;
uint8_t wrongCodes = 0;
bool haveImage = false, staleScreen = true;
uint32_t imageRevision = 0;
uint32_t lastPull = 0, lastImage = 0, lastFooter = 0, lastReconnect = 0;
// The wait the server announced after the last pull (X-VibeDock-Next-Pull), 0: every poll interval. pending: it was
// still asking a source of this display, so the picture may change shortly.
uint32_t pullWaitMs = 0; bool pullPending = false;
uint32_t pullIntervalMs() { return pullWaitMs ? pullWaitMs : client_policy::clampPoll(pollSeconds) * 1000u; }
// Pull in the next loop (or after delayMs); an announced wait no longer applies.
void pullSoon(uint32_t delayMs = 0) { pullWaitMs = 0; lastPull = millis() - client_policy::clampPoll(pollSeconds) * 1000u + delayMs; }
int lastStatus = 0;  // HTTP status of the last pull, negative for connection errors, 0 before the first pull

String currentIp() {
  if (mode == Mode::Portal) return WiFi.softAPIP().toString();
  return WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : "-- (no Wi-Fi)";
}
// ---- diagnosis: the current start and its last phase, in memory that survives a reset (crash.cpp) ----
// Written to that memory (no flash) when the phase changes and once per pull; read after the next reset as the state
// of the run before. After a power cut, or when the record does not check out, the run before is unknown.
using client_policy::Phase; using client_policy::Intent;
client_policy::DiagRecord diag{}, previousDiag{};
bool previousKnown = false; const char *startKind = "other"; uint32_t heapLow = UINT32_MAX;
void diagWrite() { diag.check = client_policy::diagCheck(diag); platform::storeDiag(diag); }
void diagPhase(Phase phase) { if (diag.phase != uint8_t(phase)) { diag.phase = uint8_t(phase); diagWrite(); } }
// Sets a phase for a scope and goes back to the one before (a saved configuration inside a pull, ...).
struct PhaseScope { uint8_t before; explicit PhaseScope(Phase phase) : before(diag.phase) { diagPhase(phase); } ~PhaseScope() { diagPhase(Phase(before)); } };
// Before restarting on purpose: the next start is then this intent, not an unexpected restart.
void diagIntent(Intent intent) { diag.intent = uint8_t(intent); diagWrite(); }
void diagSample() {
  uint32_t heap = ESP.getFreeHeap(); if (heap < heapLow) heapLow = heap;
  uint32_t low = platform::minFreeHeap();
  diag.uptime = millis() / 1000; diag.heapFree = heap; diag.heapMin = low ? low : heapLow;
  diag.rssi = WiFi.status() == WL_CONNECTED ? int8_t(constrain(WiFi.RSSI(), -127, 0)) : 0;
  diagWrite();
}
void diagBegin() {
  client_policy::DiagRecord stored{};
  auto reset = platform::resetKind();
  // After a power cut the memory holds anything, even a record that seems to check out.
  previousKnown = reset != client_policy::ResetKind::PowerOn && platform::loadDiag(stored) && client_policy::diagValid(stored);
  if (previousKnown) previousDiag = stored;
  startKind = client_policy::startKind(reset, previousKnown ? Intent(previousDiag.intent) : Intent::None);
  uint32_t boot = 0;
  while (boot == 0) { if (!platform::randomBytes(reinterpret_cast<uint8_t *>(&boot), sizeof(boot))) boot = micros() | 1; }
  diag = client_policy::DiagRecord{client_policy::diagMagic, boot, 0, ESP.getFreeHeap(), ESP.getFreeHeap(), 0, uint8_t(Phase::Start), 0, 0, 0};
  diagWrite();
}
// "boot=… reset=… start=… prev=… phase=… up=… heap=…/… rssi=… drops=…" for the start line of the log.
String diagStart() {
  char text[200];
  int n = snprintf_P(text, sizeof(text), PSTR("boot=%08lx reset=%lu start=%s"), (unsigned long)diag.boot, (unsigned long)platform::resetCode(), startKind);
  if (previousKnown)
    snprintf_P(text + n, sizeof(text) - n, PSTR(" prev=%08lx phase=%s up=%lu heap=%lu/%lu rssi=%d drops=%u"), (unsigned long)previousDiag.boot,
             client_policy::phaseName(previousDiag.phase), (unsigned long)previousDiag.uptime, (unsigned long)previousDiag.heapFree,
             (unsigned long)previousDiag.heapMin, int(previousDiag.rssi), unsigned(previousDiag.drops));
  else snprintf_P(text + n, sizeof(text) - n, PSTR(" prev=unknown"));
  return text;
}
// X-VibeDock-Diag with every request: the current start and, as far as known, the run before (DeviceDiagnostics).
String diagHeader() {
  char text[400];
  int n = snprintf_P(text, sizeof(text), PSTR("boot=%08lx;up=%lu;heap=%lu;min=%lu;rssi=%d;drops=%u;start=%s;reset=%lu"), (unsigned long)diag.boot,
                   (unsigned long)(millis() / 1000), (unsigned long)diag.heapFree, (unsigned long)diag.heapMin, int(diag.rssi), unsigned(diag.drops),
                   startKind, (unsigned long)platform::resetCode());
  if (previousKnown)
    snprintf_P(text + n, sizeof(text) - n, PSTR(";prev=%08lx;phase=%s;prevup=%lu;prevheap=%lu;prevmin=%lu;prevrssi=%d;prevdrops=%u"), (unsigned long)previousDiag.boot,
             client_policy::phaseName(previousDiag.phase), (unsigned long)previousDiag.uptime, (unsigned long)previousDiag.heapFree,
             (unsigned long)previousDiag.heapMin, int(previousDiag.rssi), unsigned(previousDiag.drops));
  String header(text);
  // The Wi-Fi it is in, for its card in the administration: UTF-8, percent-encoded (an SSID may contain ";" and "=").
  String ssid = WiFi.status() == WL_CONNECTED ? WiFi.SSID() : String();
  if (!ssid.isEmpty() && ssid.length() <= 32) { header += F(";ssid="); header += client_policy::percentEncoded(ssid.c_str()).c_str(); }
  String crash = platform::crashHeader();
  return crash.isEmpty() ? header : header + F(";panic=") + crash;
}
// ---- device log: the last events, kept in /log.txt across restarts and read over USB (VIBEDOCK-LOG) ----
// What the display did and why a connection failed; no keys, passwords or tokens.
bool clockSet();
constexpr uint8_t LOG_LINES = 24;  // up to 4 kB of heap, given back during a TLS handshake (parkLog)
String logLines[LOG_LINES]; uint8_t logNext = 0, logCount = 0;
String logLast; uint16_t logRepeats = 0;
bool logStored = false;  // the storage is mounted; until then events stay in memory
bool logDirty = false; uint32_t logSavedAt = 0;
bool logParked = false;  // see parkLog
// VIBEDOCK_LOG_IN_MEMORY: the log is not written to flash (test on the ESP32-C6, whether writing it causes the
// crashes); the lines of a run are lost with a reset, the crash itself is in the next start's line.
void saveLog() {
#ifdef VIBEDOCK_LOG_IN_MEMORY
  logDirty = false; logSavedAt = millis(); return;
#endif
  if (!logStored || logParked) return;  // parked: the file is complete, the lines in memory are not
  logDirty = false; logSavedAt = millis();
  auto out = LittleFS.open("/log.tmp", "w"); if (!out) return;
  for (uint8_t i = 0; i < logCount; i++) { out.print(logLines[(logNext + LOG_LINES - logCount + i) % LOG_LINES]); out.print('\n'); }
  out.close(); LittleFS.rename("/log.tmp", "/log.txt");
}
// During a TLS 1.3 handshake the ESP8266 needs every kilobyte: the lines are then only in /log.txt and their heap is
// free. Reading the log meanwhile reads the file; a new event brings the lines back first.
void parkLog() {
#ifdef VIBEDOCK_LOG_IN_MEMORY
  return;  // the lines are only in memory
#endif
  if (!logStored || logParked) return;
  saveLog();
  for (auto &line : logLines) line = String();
  logParked = true;
}
void unparkLog() {
  if (!logParked) return;
  logParked = false; logNext = logCount = 0;
  auto in = LittleFS.open("/log.txt", "r");
  while (in && in.available() && logCount < LOG_LINES) {
    String line = in.readStringUntil('\n');
    if (!line.isEmpty()) { logLines[logNext] = line; logNext = (logNext + 1) % LOG_LINES; logCount++; }
  }
  if (in) in.close();
}
// frequent: events that can come with every poll (server answers). A new one is written at once like all others, so
// that a reset (e.g. when a USB port is opened) does not lose it; one already among the last lines (two servers
// failing in turn) at most once a minute.
void logEvent(const String &text, bool frequent = false) {
  unparkLog();
  // A repeated event replaces its line with a count instead of filling the log.
  if (text == logLast && logCount > 0) {
    String &line = logLines[(logNext + LOG_LINES - 1) % LOG_LINES];
    int mark = line.indexOf(" [x"); if (mark > 0) line.remove(mark);
    line += " [x" + String(++logRepeats + 1) + "]";
    return;  // not stored again: a repeat changes nothing worth the flash write
  }
  logLast = text; logRepeats = 0;
  char stamp[24]; time_t now = time(nullptr);
  if (clockSet()) { struct tm t; gmtime_r(&now, &t); strftime(stamp, sizeof(stamp), "%m-%d %H:%M:%S", &t); }
  else snprintf(stamp, sizeof(stamp), "+%lus", (unsigned long)(millis() / 1000));
  bool seen = false;
  for (uint8_t i = 1; frequent && i <= 4 && i <= logCount; i++) seen |= logLines[(logNext + LOG_LINES - i) % LOG_LINES].endsWith(" " + text.substring(0, 260));
  logLines[logNext] = String(stamp) + " " + text.substring(0, 260);
  logNext = (logNext + 1) % LOG_LINES; if (logCount < LOG_LINES) logCount++;
  if (!seen || millis() - logSavedAt >= 60000) saveLog(); else logDirty = true;
}
// The log of the earlier runs comes first; this run's events follow.
void loadLog() {
  std::vector<String> earlier = {};
  auto in = LittleFS.open("/log.txt", "r");
  if (in) { while (in.available()) { String line = in.readStringUntil('\n'); if (!line.isEmpty()) earlier.push_back(line); } in.close(); }
  std::vector<String> current;
  for (uint8_t i = 0; i < logCount; i++) current.push_back(logLines[(logNext + LOG_LINES - logCount + i) % LOG_LINES]);
  logNext = logCount = 0;
  auto add = [](const String &line) { logLines[logNext] = line; logNext = (logNext + 1) % LOG_LINES; if (logCount < LOG_LINES) logCount++; };
  for (auto &line : earlier) add(line);
  for (auto &line : current) add(line);
  logStored = true; saveLog();
}
// Only native USB needs protection against an attached host that does not consume its TX buffer.
#if defined(ESP32) && ARDUINO_USB_CDC_ON_BOOT
#include "serial_reply.h"
struct SerialReplyClock { uint32_t now(){return millis();} void pause(){delay(1);} };
class SerialResponse:public Print {
  BoundedSerialReply<decltype(Serial),SerialReplyClock> output;
  bool first=true;
public:
  explicit SerialResponse(decltype(Serial) &port):output(port){}
  using Print::write;
  size_t write(const uint8_t *bytes,size_t length) override{
    if(first){first=false;const uint8_t newline='\n';output.write(&newline,1);} // resync after an abandoned reply
    return output.write(bytes,length);
  }
  size_t write(uint8_t byte) override{return write(&byte,1);}
};
#else
using SerialResponse=decltype(Serial)&; // UART boards keep their existing output and memory footprint.
#endif
void flushSerialReply(){
#if defined(ESP32) && ARDUINO_USB_CDC_ON_BOOT
  Serial.setTxTimeoutMs(100); // bound the final drain before a setup restart
#endif
  Serial.flush();
#if defined(ESP32) && ARDUINO_USB_CDC_ON_BOOT
  Serial.setTxTimeoutMs(0);
#endif
}
void serialLog() {
  SerialResponse reply(Serial);
  if (logDirty) saveLog();
  // Written as JSON line by line, without a JsonDocument: the log is also read during a TLS handshake, when the
  // heap is nearly used up.
  reply.print(F("VIBEDOCK-LOG ["));
  bool first = true;
  auto line = [&first,&reply](const String &text) {
    if (!first) reply.print(',');
    first = false; reply.print('"');
    for (char c : text) {
      if (c == '"' || c == '\\') { reply.print('\\'); reply.print(c); }
      else if (uint8_t(c) < 32) reply.print(' ');
      else reply.print(c);
    }
    reply.print('"');
  };
  if (logParked) {
    auto in = LittleFS.open("/log.txt", "r");
    while (in && in.available()) { String text = in.readStringUntil('\n'); if (!text.isEmpty()) line(text); }
    if (in) in.close();
  } else {
    for (uint8_t i = 0; i < logCount; i++) line(logLines[(logNext + LOG_LINES - logCount + i) % LOG_LINES]);
  }
  reply.println(']');
}
String footerText;
uint16_t footerBackground = TFT_BLACK, footerDrawn = TFT_BLACK;
// Colours of the device's own screens (RGB565): muted text #8a97a8, track #2a3546, done #34c759, active #7fd6ff,
// problem #ff9f0a with the pill #2a1a05 / #6b4310 / #ffcf8a, as on the device web pages.
constexpr uint16_t MUTED = 0x8CB5, TRACK = 0x29A8, DONE = 0x362B, ACTIVE = 0x7EBF, PROBLEM = 0xFCE1,
                   PILL = 0x28C0, PILL_EDGE = 0x6A02, PILL_TEXT = 0xFE71;
uint32_t drawnRows[TFT_WIDTH > TFT_HEIGHT ? TFT_WIDTH : TFT_HEIGHT];  // hash of each drawn image row; only changed rows are pushed to the display
bool imageOnScreen = false;  // a server image fills the screen, IP included; the device draws no footer over it
bool deltaReady = false;  // only a complete, unscaled image is a basis for partial pixel updates
// After an image was cut off on the way (weak Wi-Fi): rows 0 to resumeRows-1 show resumeTop, the others resumeBottom
// ("" unknown). The next pull names both (X-VibeDock-Resume) and gets only what is still missing, as a delta.
bool resuming = false; uint16_t resumeRows = 0; String resumeTop, resumeBottom;
String resumeToken() { return resumeTop + F(";rows=") + String(resumeRows) + F(";base=") + resumeBottom; }
#if defined(ESP32)
// The image's pixels as the server sent them (before the colour correction), on boards with the memory for it: a turn
// or a new colour correction redraws from it instead of loading the whole image again. Every row with a known hash
// matches it; a scaled image clears shadowOk until the rows are forgotten.
uint16_t *shadow = nullptr; uint16_t shadowWidth = 0, shadowHeight = 0; bool shadowOk = true;
#endif
void forgetRows() {
  deltaReady = false; resuming = false; for (auto &h : drawnRows) h = 0;
#if defined(ESP32)
  shadowOk = true;
#endif
}
bool redrawFromShadow();
// Only the main task touches the panel; the audio task reports button activity through an atomic flag.
#ifdef VIBEDOCK_AUDIO_ES8311
sound_policy::VolumeOverlay volumeOverlay;
#endif
void volumeFeedback(bool force=false) {
#ifdef VIBEDOCK_AUDIO_ES8311
  bool touched=audio::takeVolumeInteraction(); auto now=millis();
  if(touched) volumeOverlay.show(now);
  if(volumeOverlay.expired(now)) {
    for(int row=0;row<volumeOverlay.height;row++) if(auto data=volumeOverlay.savedRow(row))
      panel.pushImage(volumeOverlay.x,volumeOverlay.y+row,volumeOverlay.width,1,const_cast<uint16_t*>(data));
    volumeOverlay.dismiss(); return;
  }
  if(!volumeOverlay.visible(now) || (!touched&&!force)) return;
  constexpr uint16_t background=0x1082;
  panel.fillRoundRect(volumeOverlay.x,volumeOverlay.y,volumeOverlay.width,volumeOverlay.height,8,background);
  panel.drawRoundRect(volumeOverlay.x,volumeOverlay.y,volumeOverlay.width,volumeOverlay.height,8,MUTED);
  panel.setTextColor(TFT_WHITE,background);
  panel.drawCentreString(audio::volume() ? String(F("Volume "))+String(audio::volume())+" %" : String(F("Muted")),120,volumeOverlay.y+6,2);
  panel.fillRoundRect(volumeOverlay.x+12,volumeOverlay.y+31,176,6,3,TRACK);
  int filled=176*audio::volume()/100;
  if(filled) panel.fillRoundRect(volumeOverlay.x+12,volumeOverlay.y+31,filled,6,3,ACTIVE);
#else
  (void)force;
#endif
}
void imageRow(int row,int width,uint16_t *pixels) {
#ifdef VIBEDOCK_AUDIO_ES8311
  volumeOverlay.capture(row,pixels,width);
  if(volumeOverlay.visible(millis()) && row>=volumeOverlay.y && row<volumeOverlay.y+volumeOverlay.height) {
    panel.pushImage(0,row,volumeOverlay.x,1,pixels);
    panel.pushImage(volumeOverlay.x+volumeOverlay.width,row,width-volumeOverlay.x-volumeOverlay.width,1,
        pixels+volumeOverlay.x+volumeOverlay.width);
    return;
  }
#endif
  panel.pushImage(0,row,width,1,pixels);
}
void imageSpan(int row,int x,int width,uint16_t *pixels) {
#ifdef VIBEDOCK_AUDIO_ES8311
  volumeOverlay.captureSpan(row,x,pixels,width);
  if(volumeOverlay.visible(millis())&&row>=volumeOverlay.y&&row<volumeOverlay.y+volumeOverlay.height){
    for(int i=0;i<width;i++)if(x+i<volumeOverlay.x||x+i>=volumeOverlay.x+volumeOverlay.width)
      panel.drawPixel(x+i,row,pixels[i]);
    return;
  }
#endif
  panel.pushImage(x,row,width,1,pixels);
}
// Shows the own screen on the panel: scaled to fit and centred, the rest black. No-op when the panel is 240 x 240.
uint32_t lastPresent = 0;
void present(bool force = true) {
#if VIBEDOCK_CANVAS
  if (imageOnScreen || (!force && millis() - lastPresent < 120)) return;
  lastPresent = millis();
  const int width = panel.width(), height = panel.height(), side = std::min(width, height);
  const int left = (width - side) / 2, top = (height - side) / 2;
  static uint16_t line[TFT_WIDTH > TFT_HEIGHT ? TFT_WIDTH : TFT_HEIGHT];
  if (left > 0) { panel.fillRect(0, 0, left, height, TFT_BLACK); panel.fillRect(left + side, 0, width - left - side, height, TFT_BLACK); }
  if (top > 0) { panel.fillRect(0, 0, width, top, TFT_BLACK); panel.fillRect(0, top + side, width, height - top - side, TFT_BLACK); }
  for (int y = 0; y < side; y++) {
    int sy = client_policy::sourceOf(y, side, 240);
    for (int x = 0; x < side; x++) line[x] = canvas.readPixel(client_policy::sourceOf(x, side, 240), sy);
#ifdef VIBEDOCK_AUDIO_ES8311
    volumeOverlay.capture(top+y,line,side);
#endif
    panel.pushImage(left, top + y, side, 1, line);
  }
#else
  (void)force;
#endif
  volumeFeedback(true);
}
// The footer is redrawn only when its text or background changes; a periodic full redraw made the bottom row flicker.
void spin();
void footer(bool force = false) {
  lastFooter = millis();
  if (imageOnScreen) return;
  spin(); present(false);
  // In the setup the footer is the browser fallback for when the captive portal does not open by itself.
  String text = (mode == Mode::Portal ? "Browser: " : "IP: ") + currentIp();
  if (!force && text == footerText && footerBackground == footerDrawn) return;
  footerText = text; footerDrawn = footerBackground;
  uint16_t ink = client_policy::lightColor(footerBackground) ? TFT_BLACK : (mode == Mode::Portal ? TFT_WHITE : MUTED);
  display.setTextColor(ink, footerBackground);
  if (mode == Mode::Portal) {
    // The setup screen owns the whole bottom row: centred under the QR code.
    display.fillRect(0, client_policy::footerTop, 240, 240 - client_policy::footerTop, footerBackground);
    display.drawCentreString(text, 120, 222, 2);
    present();
    return;
  }
  display.fillRect(0, client_policy::footerTop, 240, 240 - client_policy::footerTop, footerBackground);
  display.drawCentreString(text, 120, 222, 2);
  present();
}
// Rendering of docs/logo.svg in 40 x 40 pixels times s: the device itself, a white case with a dark screen
// showing quota bars.
void drawLogo(int x, int y, int s = 1) {
  display.fillRoundRect(x + 3 * s, y + 4 * s, 34 * s, 35 * s, 7 * s, 0xBDF6);  // case edge #b9bcb4
  display.fillRoundRect(x + 3 * s, y + 2 * s, 34 * s, 34 * s, 7 * s, 0xEF7D);  // case, off-white #efefeb as in the SVG gradient
  display.drawRoundRect(x + 3 * s, y + 2 * s, 34 * s, 34 * s, 7 * s, 0xBDF6);  // outline, so the case stands out on white too
  display.fillRoundRect(x + 7 * s, y + 6 * s, 26 * s, 22 * s, 2 * s, 0x1129);  // screen #14254d
  display.fillRect(x + 9 * s, y + 8 * s, 7 * s, 2 * s, 0x7EBF);                // title #7fd6ff
  const uint16_t track = 0x2A0E, green = 0x362B, orange = 0xFCE1;
  const int fills[] = {17, 13, 5};
  for (int i = 0; i < 3; i++) {
    display.fillRoundRect(x + 9 * s, y + (12 + 4 * i) * s, 17 * s, 2 * s, s, track);
    display.fillRoundRect(x + 9 * s, y + (12 + 4 * i) * s, fills[i] * s, 2 * s, s, i == 2 ? orange : green);
  }
  display.fillCircle(x + 29 * s, y + 24 * s, 2 * s, 0xFE69);                    // emoji #ffcc4d
}
// Progress of the start shown under the status: Wi-Fi, pairing, server, image. The step in work spins, a problem
// marks it orange; screens without progress (factory reset, storage error) pass Step::None.
enum class Step : int8_t { None = -1, WiFi, Pairing, Server, Image };
const char *const STEP_NAMES[] = {"Wi-Fi", "Pair", "Server", "Image"};
int8_t spinningStep = -1; uint8_t spinPhase = 0;
int stepX(int i) { return 30 + 60 * i; }
constexpr int STEP_Y = 180;
void spin() {
  if (spinningStep < 0) return;
  int x = stepX(spinningStep); uint32_t from = (spinPhase++ % 12) * 30;
  display.drawSmoothArc(x, STEP_Y, 6, 4, 0, 360, TRACK, TFT_BLACK, false);
  display.drawSmoothArc(x, STEP_Y, 6, 4, from, from + 120 > 360 ? 360 : from + 120, ACTIVE, TFT_BLACK, false);
  if (from + 120 > 360) display.drawSmoothArc(x, STEP_Y, 6, 4, 0, from + 120 - 360, ACTIVE, TFT_BLACK, false);
}
void drawSteps(Step step, bool problem) {
  int current = int(step);
  for (int i = 0; i < 3; i++) display.fillRect(stepX(i) + 9, STEP_Y - 1, 42, 2, i < current ? DONE : TRACK);
  for (int i = 0; i < 4; i++) {
    int x = stepX(i);
    if (i < current) {
      display.fillSmoothCircle(x, STEP_Y, 6, DONE, TFT_BLACK);
      display.drawWideLine(x - 3, STEP_Y, x - 1, STEP_Y + 2, 2, TFT_BLACK, DONE);
      display.drawWideLine(x - 1, STEP_Y + 2, x + 3, STEP_Y - 3, 2, TFT_BLACK, DONE);
    } else if (i == current && problem) {
      display.fillSmoothCircle(x, STEP_Y, 6, PROBLEM, TFT_BLACK);
      display.fillRect(x - 1, STEP_Y - 4, 2, 5, TFT_BLACK); display.fillRect(x - 1, STEP_Y + 2, 2, 2, TFT_BLACK);
    } else {
      display.drawSmoothArc(x, STEP_Y, 6, 4, 0, 360, TRACK, TFT_BLACK, false);
    }
    display.setTextColor(i <= current ? TFT_WHITE : MUTED, TFT_BLACK);
    display.drawCentreString(STEP_NAMES[i], x, STEP_Y + 10, 2);
  }
  spinningStep = problem ? -1 : current; spinPhase = 0; spin();
}
// One line under the status, e.g. the network that is waited for.
void showDetail(const String &value, uint16_t colour = TFT_WHITE) {
  display.setTextColor(colour, TFT_BLACK);
  display.drawCentreString(value, 120, 148, 2);
  present();
}
// Text over several lines under the status, wrapped at spaces, centred; what does not fit ends in "...".
void showWrapped(const String &text, int top, int lines, uint16_t colour, uint8_t font = 2) {
  display.setTextColor(colour, TFT_BLACK);
  const int width = 228, height = display.fontHeight(font);
  int start = 0, length = text.length();
  for (int line = 0; line < lines && start < length; line++) {
    while (start < length && text[start] == ' ') start++;
    int end = start, cut = -1;
    while (end < length && display.textWidth(text.substring(start, end + 1), font) <= width) { end++; if (end < length && text[end] == ' ') cut = end; }
    if (end < length && cut > start) end = cut;  // break at the last space that fits
    String part = text.substring(start, end);
    if (line == lines - 1 && end < length) {
      while (part.length() && display.textWidth(part + "...", font) > width) part.remove(part.length() - 1);
      part += "...";
    }
    display.drawCentreString(part, 120, top + line * height, font);
    start = end;
  }
}
// The device's own screen: logo, name, status (as an orange pill for problems) and the progress of the start.
bool reasonOnScreen = false;  // "Server unreachable" with its reason: the address line shows the next attempt
uint8_t failedInRow = 0, imageFailures = 0;      // connection failures since the last answer, to try every address once right away
void message(const String &value, Step step = Step::None, bool problem = false) {
  PhaseScope drawing(Phase::Draw);
  reasonOnScreen = false;
  display.fillRect(0, 0, 240, client_policy::footerTop, TFT_BLACK);
  spinningStep = -1;
  drawLogo(80, 4, 2);
  display.setTextColor(TFT_WHITE, TFT_BLACK);
  display.drawCentreString("VibeDock", 120, 86, 4);
  display.setTextColor(MUTED, TFT_BLACK);
  display.drawCentreString(String("v") + VIBEDOCK_VERSION, 120, 109, 1);
  if (problem) {
    int width = display.textWidth(value, 2) + 24;
    display.fillRoundRect(120 - width / 2, 120, width, 22, 8, PILL);
    display.drawRoundRect(120 - width / 2, 120, width, 22, 8, PILL_EDGE);
    display.setTextColor(PILL_TEXT, PILL);
  } else display.setTextColor(MUTED, TFT_BLACK);
  display.drawCentreString(value, 120, 123, 2);
  if (step != Step::None) drawSteps(step, problem);
  forgetRows(); imageOnScreen = false;
  footerBackground = TFT_BLACK;
  footer(true);
}
String randomHex(size_t bytes) {
  uint8_t value[32]; if (bytes > sizeof(value) || !platform::randomBytes(value, bytes)) return "";
  String out; char hex[3];
  for (size_t i = 0; i < bytes; i++) { snprintf(hex, sizeof(hex), "%02x", value[i]); out += hex; }
  return out;
}

// ---- configuration ----
bool writeConfig();
void saveLater();
bool saveConfig() { PhaseScope saving(Phase::Config); return writeConfig(); }
bool writeConfig() {
  JsonDocument doc;
  doc["identifier"] = identifier; doc["name"] = deviceName;
  doc["ssid"] = ssid; doc["password"] = password; doc["key"] = key; doc["poll"] = pollSeconds; doc["pin"] = pin;
  doc["verified"] = networkVerified; doc["brightness"] = brightness; doc["flipped"] = turns == 2; doc["turns"] = turns;
  doc["powerSaving"] = powerSavingAllowed; doc["uiDark"] = uiDark;
#ifdef VIBEDOCK_AUDIO_ES8311
  doc["volume"] = audio::volume();
#endif
  JsonObject t = doc["tone"].to<JsonObject>();
  t["gamma"] = toneSettings.gamma; t["red"] = toneSettings.red; t["green"] = toneSettings.green; t["blue"] = toneSettings.blue;
  t["custom"] = !(toneSettings == client_policy::ToneSettings());  // calibrated on the status page
  JsonArray list = doc["servers"].to<JsonArray>();
  for (uint8_t i = 0; i < serverCount; i++) list.add(servers[i]);
  doc["server"] = storedIndex = serverIndex;  // the address that answered last: tried first after a restart
  auto file = LittleFS.open("/standalone.tmp", "w");
  if (!file) return false;
  bool written = serializeJson(doc, file) > 0; file.close();
  return written && LittleFS.rename("/standalone.tmp", "/standalone.json");
}
bool loadConfig() {
  auto file = LittleFS.open("/standalone.json", "r");
  if (file && file.size() <= 2048) {
    JsonDocument doc;
    if (!deserializeJson(doc, file)) {
      uiDark = doc["uiDark"] | true;
      identifier = doc["identifier"].as<String>(); deviceName = doc["name"] | "";
      ssid = doc["ssid"].as<String>(); password = doc["password"].as<String>();
      key = doc["key"] | ""; pin = doc["pin"] | "";
      // Configurations from before this flag worked with their network already.
      networkVerified = doc["verified"].isNull() ? !ssid.isEmpty() : doc["verified"].as<bool>(); pollSeconds = client_policy::clampPoll(doc["poll"] | client_policy::defaultPollSeconds);
      serverCount = 0;
      for (JsonVariant url : doc["servers"].as<JsonArray>()) if (serverCount < 2) servers[serverCount++] = url.as<String>();
      serverIndex = storedIndex = serverCount ? std::min<uint8_t>(doc["server"] | 0, serverCount - 1) : 0;
      brightness = client_policy::clampBrightness(doc["brightness"] | 100);
#ifdef VIBEDOCK_AUDIO_ES8311
      audio::setVolume(doc["volume"] | 30);
#endif
      // "flipped" alone: saved by firmware before quarter turns (and still written for it after a downgrade).
      turns = (doc["turns"] | (doc["flipped"] | false ? 2 : 0)) % 4;
      powerSavingAllowed = doc["powerSaving"] | false;
      client_policy::ToneSettings d; JsonObject t = doc["tone"];
      long gamma = t["gamma"] | long(d.gamma), red = t["red"] | long(d.red), green = t["green"] | long(d.green), blue = t["blue"] | long(d.blue);
      // Only a calibration made on this display is kept; otherwise the board's defaults apply.
      bool custom = t["custom"].isNull() ? !t.isNull() && client_policy::legacyCalibration(gamma, red, green, blue) : t["custom"].as<bool>();
      if (custom) {
        toneSettings.gamma = client_policy::clampGamma(gamma); toneSettings.red = client_policy::clampGain(red);
        toneSettings.green = client_policy::clampGain(green); toneSettings.blue = client_policy::clampGain(blue);
      } else toneSettings = d;
    }
  }
  file.close();
  panelTone.build(toneSettings);
  // Devices of the project's earlier name keep their settings but get the identifier of this one.
  if (identifier.startsWith("vibetv-")) identifier = "";
  if (identifier.isEmpty() || identifier == "null") { identifier = String(F("vibedock-")) + WiFi.macAddress(); identifier.replace(":", ""); identifier.toLowerCase(); }
  if (deviceName.isEmpty()) deviceName = String(F("VibeDock ")) + identifier.substring(identifier.length() - 6);
  LittleFS.remove("/setup.key");  // setup passwords are no longer stored, see startPortal()
  // Configurations of protocol v1 carried a device-generated key and no server list: they are not paired.
  if (key.length() != 64 || serverCount == 0) { key = ""; serverCount = 0; }
  return saveConfig();
}

// ---- web pages (setup portal and status page share the look) ----
const char PAGE_HEAD[] PROGMEM = R"HTML(<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#000;color:#e8edf5;font:16px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;-webkit-font-smoothing:antialiased}
main{max-width:420px;margin:0 auto;padding:24px 16px 40px}header{display:flex;align-items:center;gap:12px;margin-bottom:20px}header svg{width:48px;height:48px;flex:none}
h1{font-size:22px;font-weight:650;letter-spacing:-.01em;margin:0}header p{margin:0;color:#8b97a8;font-size:14px}
.card{background:#0c1219;border:1px solid #1d2633;border-radius:14px;padding:16px;margin-bottom:14px}h2{font-size:12px;text-transform:uppercase;letter-spacing:.07em;color:#8b97a8;margin:0 0 8px;font-weight:600}
.net{display:flex;align-items:center;gap:10px;padding:11px 2px;border-top:1px solid #1d2633;cursor:pointer}.net:first-of-type{border-top:0}.net span{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
input[type=radio]{accent-color:#6d5cff;width:18px;height:18px;margin:0}input[type=range]{width:100%;accent-color:#6d5cff;margin:0}.f{display:block;font-size:13px;color:#8b97a8;margin:12px 0 5px}.f:first-child{margin-top:0}
input[type=text],input[type=password]{width:100%;padding:11px 12px;border-radius:10px;border:1px solid #2a3546;background:#05080d;color:inherit;font:inherit}input:focus{outline:2px solid #6d5cff;border-color:transparent}
button{width:100%;margin-top:16px;padding:12px;border:0;border-radius:10px;background:linear-gradient(135deg,#3b5bfd,#7c3aed);color:#fff;font:inherit;font-weight:600;cursor:pointer}
.sig{display:inline-flex;gap:2px;align-items:flex-end;height:14px}.sig i{width:4px;background:#2a3546;border-radius:1px}.sig i:nth-child(1){height:5px}.sig i:nth-child(2){height:9px}.sig i:nth-child(3){height:14px}
.l1 i:nth-child(-n+1),.l2 i:nth-child(-n+2),.l3 i{background:#34c759}.lock{font-size:12px;opacity:.7}
.problem{background:#2a1a05;border:1px solid #6b4310;color:#ffcf8a;border-radius:10px;padding:10px 12px;font-size:14px;margin:0 0 14px}
.hint{font-size:13px;color:#9aa6b8;border-left:3px solid #7c3aed;padding:2px 0 2px 10px;margin:18px 0 0}.muted{color:#8b97a8;font-size:13px;margin:10px 0 0}a{color:#9fb0ff}
dl{display:grid;grid-template-columns:auto 1fr;gap:6px 14px;margin:0}dt{color:#8b97a8}dd{margin:0;overflow-wrap:anywhere}
.state{font-weight:600;margin:0 0 12px}.state:before{content:"";display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:8px;background:currentColor}.ok{color:#34c759}.warn{color:#ff9f0a}.bad{color:#ff453a}
.screen{display:block;width:100%;max-width:240px;aspect-ratio:1;margin:0 auto;border-radius:6px;image-rendering:pixelated;background:#000}
</style>)HTML";
const char PAGE_LOGO[] PROGMEM = R"SVG(<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><defs><linearGradient id="case" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#e6e7e2"/></linearGradient><linearGradient id="screen" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#14254d"/><stop offset="1" stop-color="#0a1633"/></linearGradient></defs><rect x="5" y="5" width="54" height="56" rx="11" fill="#b9bcb4"/><rect x="5" y="3" width="54" height="55" rx="11" fill="url(#case)" stroke="#b9bcb4" stroke-width="1.2"/><rect x="11" y="9" width="42" height="35" rx="2.5" fill="url(#screen)"/><rect x="15" y="13" width="11" height="3" rx="1" fill="#7fd6ff"/><rect x="15" y="19.5" width="27" height="3" rx="1.5" fill="#2a4373"/><rect x="15" y="19.5" width="27" height="3" rx="1.5" fill="#34c759"/><rect x="15" y="25.5" width="27" height="3" rx="1.5" fill="#2a4373"/><rect x="15" y="25.5" width="20" height="3" rx="1.5" fill="#34c759"/><rect x="15" y="31.5" width="27" height="3" rx="1.5" fill="#2a4373"/><rect x="15" y="31.5" width="8" height="3" rx="1.5" fill="#ff9f0a"/><circle cx="46.5" cy="37.5" r="3.2" fill="#ffcc4d"/></svg>)SVG";
const char PAGE_HINT[] PROGMEM = R"HTML(<p class="hint">VibeDock displays need a running <b>VibeDock server</b> (Docker container) in your network. Invite the display in the VibeDock administration with the IP address and the pairing code shown on its screen.</p>)HTML";

String html(const String &value) {
  String out; out.reserve(value.length() + 8);
  for (char c : value) {
    switch (c) {
      case '&': out += F("&amp;"); break;  case '<': out += F("&lt;"); break;   case '>': out += F("&gt;"); break;
      case '"': out += F("&quot;"); break; case '\'': out += F("&#39;"); break; default: out += c;
    }
  }
  return out;
}
// Starts a chunked HTML answer; subtitle must already be escaped.
void pageStart(const String &subtitle) {
  web.sendHeader("Cache-Control", "no-store");
  platform::chunkedStart(web, "text/html; charset=utf-8");
  web.sendContent_P(PAGE_HEAD);
  web.sendContent(uiDark ? F("<style id=\"ui-light\" media=\"not all\">") : F("<style id=\"ui-light\">"));
  web.sendContent(F(":root{color-scheme:light}body{background:#f3f5fa;color:#162030}"
      ".card{background:#fff;border-color:#dce2ed}.net{border-color:#dce2ed}"
      "header p,h2,.f,.muted,dt,.hint{color:#586b87}input[type=text],input[type=password]{background:#fff;border-color:#ccd5e4}"
      "a{color:#5145d9}.problem{background:#fff3d6;border-color:#eed49c;color:#855000}"
      ".sig:not(.l1):not(.l2):not(.l3) i{background:#ccd5e4}.ok{color:#167344}.warn{color:#965600}.bad{color:#b52230}</style>"));
  web.sendContent(String(F("<title>VibeDock - ")) + subtitle + F("</title><link rel=\"icon\" href=\"/favicon.ico\" sizes=\"32x32\">"
      "<link rel=\"icon\" href=\"/favicon.svg\" type=\"image/svg+xml\"><link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">"
      "</head><body><main><header>"));
  web.sendContent_P(PAGE_LOGO);
  web.sendContent(String(F("<div><h1>VibeDock</h1><p>")) + subtitle + F("</p></div></header>"));
}
// A tiny live status for open browser pages; no image buffer or persistent socket on the device.
const char PAGE_LIVE[] PROGMEM = R"JS((()=>{
  const light=document.getElementById('ui-light'), image=document.querySelector('img.screen');
  let version=image?.dataset.imageVersion, loading=false;
  async function poll(){
    try {
      if(document.hidden||loading)return;
      const response=await fetch('/ui-state',{cache:'no-store'});
      if(!response.ok)return;
      const state=await response.json();
      light.media=state.dark?'not all':'all';
      const next=state.boot+':'+state.image;
      if(image&&state.image&&version!==next){
        loading=true;
        image.onload=()=>{version=next;loading=false;};
        image.onerror=()=>{loading=false;};
        image.src='/image.bmp?v='+encodeURIComponent(next);
      }
    }catch(e){}finally{setTimeout(poll,5000);}
  }
  setTimeout(poll,5000);
})();)JS";
void uiState() {
  JsonDocument doc;doc["dark"]=uiDark;doc["boot"]=diag.boot;
  doc["image"]=haveImage && imageOnScreen && !staleScreen ? imageRevision : 0;
  String text;serializeJson(doc,text);web.sendHeader("Cache-Control","no-store");web.send(200,"application/json",text);
}
void serveUi() {
  web.on("/ui-state",HTTP_GET,uiState);
  web.on("/ui.js",HTTP_GET,[]{web.sendHeader("Cache-Control","no-store");web.send_P(200,"text/javascript",PAGE_LIVE);});
}
// Browser icons, the same as the administration's; registered in the setup portal and on the status page.
void serveIcons() {
  auto icon = [](const char *path, const char *type, PGM_P data, size_t size) {
    web.on(path, HTTP_GET, [=] { web.sendHeader("Cache-Control", "max-age=86400"); web.send_P(200, type, data, size); });
  };
  icon("/favicon.ico", "image/x-icon", (PGM_P)ICON_ICO, sizeof(ICON_ICO));
  icon("/apple-touch-icon.png", "image/png", (PGM_P)ICON_TOUCH, sizeof(ICON_TOUCH));
  icon("/favicon.svg", "image/svg+xml", PAGE_LOGO, strlen_P(PAGE_LOGO));
}
void pageEnd() {
  web.sendContent_P(PAGE_HINT);
  web.sendContent(F("</main><script src=\"/ui.js\" defer></script></body></html>"));
  platform::chunkedEnd(web);
}

// ---- factory reset by power-cycling ----
// Power-on and external resets count; software restarts and crashes clear the counter.
bool quickBootPending = false, usbBootConfirmed = false;
void serialPoll(bool duringTls = false);
// The first start of a new firmware is no power cycle either: flashing over USB resets the device several times
// (scripts/flash.sh asks the chip, writes, then the device is plugged in again), which used to open the Wi-Fi setup.
bool newFirmware() {
  String running = VIBEDOCK_VERSION, stored;
  auto in = LittleFS.open("/version", "r");
  if (in) { stored = in.readString(); in.close(); }
  if (stored == running) return false;
  auto out = LittleFS.open("/version", "w");
  if (out) { out.print(running); out.close(); }
  return true;
}
uint8_t countQuickBoot() {
  bool updated = newFirmware();
  if (updated) logEvent(F("New firmware: this start is not counted as a power cycle"));
  if (updated || usbBootConfirmed || !platform::powerCycleStart()) { LittleFS.remove("/boots"); return 0; }
  int count = 0;
  auto in = LittleFS.open("/boots", "r");
  if (in) { count = max(in.read(), 0); in.close(); }
  count = min(count + 1, 255);
  auto out = LittleFS.open("/boots", "w");
  if (out) { out.write(uint8_t(count)); out.close(); }
  quickBootPending = true;
  return count;
}
// Once the device has run for quickBootMs, this start no longer counts as a quick power cycle.
void settleQuickBoot() {
  if (quickBootPending && millis() >= client_policy::quickBootMs) { quickBootPending = false; LittleFS.remove("/boots"); }
}
void handleQuickBoots() {
  serialPoll(); // accept a HELLO already queued during startup before counting this boot
  uint8_t boots = countQuickBoot();
  if (client_policy::factoryResetDue(boots)) {
    // Give the flasher time to identify the new application before any destructive reset. Real unplugging
    // still works: without an active USB confirmation the third quick start resets after this short grace.
    uint32_t began = millis();
    while (!usbBootConfirmed && uint32_t(millis() - began) < client_policy::usbBootGraceMs) { serialPoll(); delay(10); }
    if (usbBootConfirmed) return;
    // Factory reset: our whole configuration (Wi-Fi, pairing key, servers) and the Wi-Fi credentials the ESP8266
    // SDK keeps in its own flash sectors. The identifier is derived from the MAC again; the setup password stays.
    message("Factory reset", Step::None, true); logEvent(String(F("Factory reset after ")) + String(boots) + F(" quick power cycles"));
    LittleFS.remove("/boots"); LittleFS.remove("/standalone.json"); LittleFS.remove("/standalone.tmp"); LittleFS.remove("/anchor.der");
    platform::eraseWifi();
    diagIntent(Intent::FactoryReset); delay(1500); ESP.restart();
  } else if (boots == client_policy::resetPowerCycles - 1) {
    // Second quick start: unplugging once more resets everything, waiting opens the Wi-Fi setup (pairing stays).
    message("Unplug now for factory reset", Step::None, true); logEvent(F("Second quick power cycle: Wi-Fi setup unless unplugged"));
    showDetail("or wait: Wi-Fi setup");
    while (quickBootPending) { serialPoll(); settleQuickBoot(); delay(50); }
    setupRequested = !usbBootConfirmed;
  }
}

// ---- Wi-Fi setup portal (access point) ----
struct WifiNetwork { String ssid; int32_t rssi; bool open; };
WifiNetwork networks[12]; uint8_t networkCount = 0;
// Scans once, keeps the strongest entry per name and sorts by signal.
void scanWifi() {
  networkCount = 0;
  int found = WiFi.scanNetworks(false, false);
  if (found < 0) {
    // The ESP32 refuses to scan while the station keeps trying a saved network that is out of reach (e.g. at another
    // place, for USB pairing there): stop the attempts for the scan, then try that network again.
    bool reconnect = WiFi.getAutoReconnect();
    WiFi.setAutoReconnect(false); WiFi.disconnect(); delay(100);
    found = WiFi.scanNetworks(false, false);
    logEvent(String(F("Wi-Fi scan while connecting: ")) + (found < 0 ? String(F("failed")) : String(found) + F(" networks")));
    WiFi.setAutoReconnect(reconnect);
    if (reconnect && !ssid.isEmpty()) WiFi.begin(ssid.c_str(), password.c_str());
  }
  for (int i = 0; i < found; i++) {
    String name = WiFi.SSID(i); int32_t rssi = WiFi.RSSI(i);
    if (name.isEmpty() || name.length() > 32) continue;
    int slot = -1;
    for (uint8_t n = 0; n < networkCount; n++) if (networks[n].ssid == name) slot = n;
    if (slot < 0 && networkCount < 12) { slot = networkCount++; networks[slot].rssi = INT32_MIN; }
    if (slot < 0) { slot = 0; for (uint8_t n = 1; n < networkCount; n++) if (networks[n].rssi < networks[slot].rssi) slot = n; }
    if (networks[slot].rssi >= rssi) continue;  // a stronger access point of that name, or a full list of stronger ones
    networks[slot] = {name, rssi, platform::openNetwork(i)};
  }
  WiFi.scanDelete();
  for (uint8_t i = 1; i < networkCount; i++)
    for (uint8_t j = i; j > 0 && networks[j].rssi > networks[j - 1].rssi; j--) std::swap(networks[j], networks[j - 1]);
}
// Short form of the connection problem for the screen.
String shortProblem(wl_status_t status) {
  if (platform::wrongPassword(status)) return "Wrong password";
  switch (status) {
    case WL_NO_SSID_AVAIL: return "Network not found";
    case WL_CONNECT_FAILED: return "Connection failed (WPA2?)";
    default: return "Network not reachable";
  }
}
// The saved network is not there (e.g. a phone hotspot that is switched off): wait for it instead of opening the
// setup; the loop keeps reconnecting and the screen switches back as soon as the network returns.
void showWaiting() {
  message(shortProblem(WiFi.status()), Step::WiFi, true);
  showDetail(String(F("Waiting for ")) + ssid);
  display.setTextColor(MUTED, TFT_BLACK);
  display.drawCentreString("Trying again every 15 s", 120, 164, 1);
  present();
}
// ESP8266 limits: 2.4 GHz only, WPA2 (or WPA2/WPA3 mixed), no WPA3-only, no required PMF, no WPA Enterprise.
String connectionProblem(wl_status_t status) {
  String name = "\"" + ssid + "\"";
  if (platform::wrongPassword(status)) return String(F("Wrong password for ")) + name + ".";
  switch (status) {
    case WL_NO_SSID_AVAIL: return String(F("Network ")) + name + F(" was not found. VibeDock only uses 2.4 GHz: the network must offer 2.4 GHz and must not be hidden.");
    case WL_CONNECT_FAILED: return String(F("Connecting to ")) + name + F(" failed. VibeDock needs WPA2 (WPA2/WPA3 mixed works, WPA3 only does not) and no required PMF (802.11w).");
    default: return String(F("No connection to ")) + name + F(" within 20 seconds. Check 2.4 GHz, WPA2 and the signal.");
  }
}
void portalPage() {
  if (web.hasArg("rescan")) scanWifi();
  pageStart(F("Set up Wi-Fi"));
  if (!wifiProblem.isEmpty()) web.sendContent(String(F("<p class=\"problem\">")) + html(wifiProblem) + F("</p>"));
  web.sendContent(F("<form action=\"/save\" method=\"post\"><section class=\"card\"><h2>Networks nearby</h2>"));
  if (networkCount == 0) web.sendContent(F("<p class=\"muted\">No networks found.</p>"));
  for (uint8_t i = 0; i < networkCount; i++) {
    const WifiNetwork &n = networks[i];
    String name = html(n.ssid);
    int level = n.rssi > -60 ? 3 : n.rssi > -72 ? 2 : 1;
    web.sendContent(String(F("<label class=\"net\"><input type=\"radio\" name=\"net\" value=\"")) + name
        + F("\" onchange=\"this.form.ssid.value=this.value;this.form.password.focus()\"><span>") + name + F("</span>")
        + (n.open ? "" : "<span class=\"lock\" title=\"Secured\">&#128274;</span>")
        + F("<b class=\"sig l") + String(level) + F("\" title=\"") + String(n.rssi) + F(" dBm\"><i></i><i></i><i></i></b></label>"));
  }
  web.sendContent(F("<p class=\"muted\"><a href=\"/?rescan=1\">Scan again</a></p></section><section class=\"card\">"
      "<label class=\"f\" for=\"ssid\">Network name</label><input type=\"text\" id=\"ssid\" name=\"ssid\" maxlength=\"32\" required autocomplete=\"off\" autocapitalize=\"none\" spellcheck=\"false\">"
      "<label class=\"f\" for=\"password\">Password</label><input type=\"password\" id=\"password\" name=\"password\" maxlength=\"64\">"
      "<button>Save and connect</button></section></form>"));
  pageEnd();
}
// Wi-Fi QR code for the setup network: a phone camera joins it and the captive portal opens. The browser
// address remains as the fallback when the portal does not open by itself.
void showSetupCode(const String &name, const String &secret) {
  String payload = String(F("WIFI:T:WPA;S:")) + name + ";P:" + secret + ";;";
  // Version 4 with quartile error correction (25 %) leaves room for the device icon in the middle.
  QRCode qr; uint8_t data[qrcode_getBufferSize(4)];
  qrcode_initText(&qr, data, 4, ECC_QUARTILE, payload.c_str());
  const int scale = 5, quiet = 2, size = (qr.size + 2 * quiet) * scale;
  const int left = (240 - size) / 2, top = 26;
  display.fillRect(0, 0, 240, client_policy::footerTop, TFT_BLACK);
  spinningStep = -1; forgetRows(); imageOnScreen = false; footerBackground = TFT_BLACK; footer(true);
  if (wifiProblem.isEmpty()) {
    display.setTextColor(TFT_WHITE, TFT_BLACK);
    display.drawCentreString("Scan to set up Wi-Fi", 120, 5, 2);
  } else {
    display.setTextColor(TFT_ORANGE, TFT_BLACK);
    display.drawCentreString(shortProblem(WiFi.status()) + F(" - see page"), 120, 5, 2);
    display.setTextColor(TFT_WHITE, TFT_BLACK);
  }
  display.fillRect(left, top, size, size, TFT_WHITE);
  for (uint8_t y = 0; y < qr.size; y++)
    for (uint8_t x = 0; x < qr.size; x++)
      if (qrcode_getModule(&qr, x, y)) display.fillRect(left + (x + quiet) * scale, top + (y + quiet) * scale, scale, scale, TFT_BLACK);
  // Device icon on a white field of 9 x 9 modules in the centre; the error correction restores the covered modules.
  const int field = 9 * scale, centre = left + size / 2, middle = top + size / 2;
  display.fillRect(centre - field / 2, middle - field / 2, field, field, TFT_WHITE);
  drawLogo(centre - 20, middle - 20);
  present();
}
void startPortal() {
  audio::play(audio::Cue::PairWifi);
  diagPhase(Phase::Portal);
  mode = Mode::Portal; logEvent(String(F("Wi-Fi setup opened")) + (wifiProblem.isEmpty() ? String() : ": " + wifiProblem));
  WiFi.mode(WIFI_AP_STA);
  message("Searching Wi-Fi", Step::WiFi);
  scanWifi();
  // A fresh random WPA2 password every time the setup opens; it only appears inside the QR code on the screen.
  String apName = String(F("VibeDock-")) + identifier.substring(identifier.length() - 6);
  apPassword = randomHex(6);
  if (apPassword.length() != 12) { message("Random source error", Step::None, true); return; }  // never open an unprotected network
  WiFi.softAP(apName.c_str(), apPassword.c_str());
  setupDns.start(53, "*", WiFi.softAPIP());
  showSetupCode(apName, apPassword);
  web.on("/", HTTP_GET, portalPage);
  serveIcons(); serveUi();
  web.on("/save", HTTP_POST, [] {
    if (web.arg("ssid").isEmpty() || web.arg("ssid").length() > 32 || web.arg("password").length() > 64) {
      web.sendHeader("Location", "/", true); web.send(303, "text/plain", ""); return;
    }
    ssid = web.arg("ssid"); password = web.arg("password"); networkVerified = false;
    if (!saveConfig()) { web.send(500, "text/plain", String(F("Saving failed"))); return; }
    pageStart(F("Saved"));
    web.sendContent(String(F("<section class=\"card\"><p class=\"state ok\">Wi-Fi saved</p><p>VibeDock restarts and connects to <b>")) + html(ssid)
        + F("</b>. Reconnect this phone or computer to your normal network; the display then shows its IP address and a pairing code.</p></section>"));
    pageEnd(); diagIntent(Intent::Setup); delay(500); ESP.restart();
  });
  web.onNotFound([] { web.sendHeader("Location", "http://192.168.4.1/", true); web.send(302, "text/plain", ""); });
  web.begin();
}

// ---- pairing (server -> device) ----
void showPairing() {
  // The IP to enter in the administration is in the footer.
  message("Invite in the administration", Step::Pairing);
  display.setTextColor(DONE, TFT_BLACK);
  display.drawCentreString(pairingCode.substring(0, 3) + " " + pairingCode.substring(3), 120, 142, 4);
  present();
}
void enterPairing() {
  audio::play(audio::Cue::PairWifi);
  mode = Mode::Pairing; wrongCodes = 0; haveImage = false; staleScreen = true; etag = "";
  uint8_t random[4]; platform::randomBytes(random, sizeof(random));
  uint32_t value = (uint32_t(random[0]) << 24 | uint32_t(random[1]) << 16 | uint32_t(random[2]) << 8 | random[3]) % 1000000;
  char code[7]; snprintf(code, sizeof(code), "%06lu", (unsigned long)value); pairingCode = code;
  showPairing();
}
bool sameCode(const String &candidate) {
  if (candidate.length() != pairingCode.length()) return false;
  uint8_t difference = 0;
  for (size_t i = 0; i < pairingCode.length(); i++) difference |= candidate[i] ^ pairingCode[i];
  return difference == 0;
}
// https addresses are only accepted together with the trust anchor the server sends when pairing.
bool validServer(const String &url, bool tlsAllowed) {
  int start = url.startsWith("http://") ? 7 : tlsAllowed && url.startsWith("https://") ? 8 : 0;
  return start > 0 && url.length() <= 120 && url.indexOf('@') < 0 && url.indexOf('?') < 0
      && url.indexOf('#') < 0 && url.indexOf(' ') < 0 && url.indexOf('/', start) < 0;
}
// ---- TLS trust for https server addresses ----
// The root certificate of the https address, sent by the server when pairing; the only certificate trusted.
bool setAnchor(const uint8_t *der, size_t length) { return platform::setAnchor(der, length); }
void loadAnchor() {
  auto file = LittleFS.open("/anchor.der", "r");
  if (!file) return;
  size_t length = file.size();
  if (length > 0 && length <= 2048) {
    std::unique_ptr<uint8_t[]> der(new uint8_t[length]);
    if (file.read(der.get(), length) == length) setAnchor(der.get(), length);
  }
  file.close();
}
bool saveAnchor(const uint8_t *der, size_t length) {
  auto file = LittleFS.open("/anchor.tmp", "w");
  if (!file) return false;
  bool written = file.write(der, length) == length; file.close();
  return written && LittleFS.rename("/anchor.tmp", "/anchor.der");
}
void forgetAnchor() { platform::clearAnchor(); LittleFS.remove("/anchor.der"); }
// ---- updates over Wi-Fi ----
// The public key of the paired server; firmware is installed over Wi-Fi only with a valid signature of that server.
String updateError;  // why the last update failed; reported with the next pull
String failedUpdate;  // not tried again until the next start
bool setOtaKey(const String &pem) { return platform::setOtaKey(pem); }
void loadOtaKey() {
  auto file = LittleFS.open("/ota.pem", "r");
  if (file && file.size() > 0 && file.size() <= 1024) setOtaKey(file.readString());
  file.close();
}
bool saveOtaKey(const String &pem) {
  auto file = LittleFS.open("/ota.tmp", "w");
  if (!file) return false;
  bool written = file.print(pem) == pem.length(); file.close();
  return written && LittleFS.rename("/ota.tmp", "/ota.pem");
}
// Certificate dates can only be checked with the real time, which comes from NTP after Wi-Fi is up.
bool clockSet() { return time(nullptr) > 1700000000; }
// Takes over what the server sends when pairing: addresses, device key, trust anchor, poll interval and name. Used by
// the invitation over the network and by the setup over USB; 200 when saved, otherwise the HTTP status of the problem.
int applyPairing(JsonDocument &doc) {
  String newKey = doc["key"] | "";
  bool hexKey = newKey.length() == 64;
  for (size_t i = 0; hexKey && i < newKey.length(); i++) hexKey = isxdigit(newKey[i]) && !isupper(newKey[i]);
  // Optional trust anchor for https addresses: base64 of one DER certificate.
  String encoded = doc["anchor"] | "";
  std::unique_ptr<uint8_t[]> der; size_t derLength = 0;
  if (!encoded.isEmpty() && encoded.length() <= 2900) {
    der.reset(new uint8_t[encoded.length()]);
    derLength = platform::base64Decode(encoded, der.get(), encoded.length());
  }
  bool tls = derLength > 0 && derLength <= 2048 && platform::singleCertificate(der.get(), derLength);
  String list[2]; uint8_t count = 0; bool anyTls = false;
  for (JsonVariant url : doc["servers"].as<JsonArray>()) {
    String value = url.as<String>(); while (value.endsWith("/")) value.remove(value.length() - 1);
    if (count < 2 && validServer(value, tls)) { anyTls |= value.startsWith("https://"); list[count++] = value; }
    else if (value.startsWith("https://") && !tls) logEvent(String(F("Pairing: ")) + value + F(" ignored, no root certificate sent"));
  }
  if (!hexKey || count == 0) return 400;
  if (anyTls) {
    if (!saveAnchor(der.get(), derLength) || !setAnchor(der.get(), derLength)) return 500;
  } else forgetAnchor();
  // A new pairing also replaces the update key: only the server paired last may update this device.
  String newOtaKey = doc["otaKey"] | "";
  if (newOtaKey.length() <= 1024 && setOtaKey(newOtaKey)) { if (!saveOtaKey(newOtaKey)) return 500; }
  else { platform::clearOtaKey(); LittleFS.remove("/ota.pem"); }
  key = newKey; serverCount = count; serverIndex = 0;
  for (uint8_t i = 0; i < count; i++) servers[i] = list[i];
  pollSeconds = client_policy::clampPoll(doc["pollSeconds"] | client_policy::defaultPollSeconds);
  uint8_t fingerprint[20]; String newPin = doc["pin"] | "";
  pin = anyTls && client_policy::parseFingerprint(newPin.c_str(), fingerprint) ? newPin : "";
  String name = doc["name"] | ""; if (!name.isEmpty() && name.length() <= 40) deviceName = name;
  if (!saveConfig()) { key = ""; serverCount = 0; return 500; }
  String names; for (uint8_t i = 0; i < count; i++) names += (i ? ", " : "") + list[i];
  logEvent(String(F("Paired with ")) + names + (anyTls ? "; https root certificate" : "") + (pin.isEmpty() ? "" : ", pin"));
  return 200;
}
void handlePair() {
  if (mode != Mode::Pairing) { web.send(409, "application/json", String(F("{\"error\":\"already paired\"}"))); return; }
  if (client_policy::pairingLocked(wrongCodes)) { web.send(429, "application/json", String(F("{\"error\":\"restart the device\"}"))); return; }
  String body = web.arg("plain");
  JsonDocument doc;
  if (body.length() > 4096 || deserializeJson(doc, body)) { web.send(400, "application/json", String(F("{\"error\":\"invalid request\"}"))); return; }
  if (!sameCode(doc["code"] | "")) { wrongCodes++; web.send(403, "application/json", String(F("{\"error\":\"wrong code\"}"))); return; }
  int status = applyPairing(doc);
  if (status == 400) { web.send(400, "application/json", String(F("{\"error\":\"no usable server address or invalid key\"}"))); return; }
  if (status != 200) { web.send(500, "application/json", String(F("{\"error\":\"storage\"}"))); return; }
  JsonDocument reply; reply["identifier"] = identifier; reply["name"] = deviceName; reply["board"] = VIBEDOCK_BOARD;
  reply["subnetMask"] = WiFi.subnetMask().toString();  // fixed by the server at pairing; pulls only fill it in once if it is missing
  String out; serializeJson(reply, out);
  web.send(200, "application/json", out);
  mode = Mode::Paired; pullSoon(); lastImage = millis();
  message("Loading image", Step::Image);
}
// ---- image pull (device -> server) ----
void unpair() {
  audio::quotas("");  // a different pairing starts with a silent quota baseline
  key = ""; pin = ""; serverCount = 0; etag = ""; lastStatus = 0; forgetAnchor(); platform::clearOtaKey(); LittleFS.remove("/ota.pem"); saveConfig(); enterPairing();
}
// Reads the rows of an image and hands each one to onRow; onRow returns false to abort.
String imageProblem;  // why the last image was not read completely, for the device log
bool transferCut = false;  // the last image broke off on the way (timeout, connection closed), not on bad data
uint16_t rowsRead = 0;     // rows of the last image drawn in order before it ended
static uint8_t imageBytes[2 * client_policy::maxImageSide];  // shared by full and delta transfers
static uint16_t imagePixels[TFT_WIDTH > TFT_HEIGHT ? TFT_WIDTH : TFT_HEIGHT];
// Reads the image row by row. Fails after `timeout` ms without new bytes (not in all: over TLS 1.3 on the M1 the
// image arrives in records of several kB, each decrypted at once) or when the connection ends early.
template <typename Row> bool readRows(WiFiClient *stream, const client_policy::ImageSize &size, Row onRow, uint32_t timeout = 8000) {
  uint8_t *bytes=imageBytes; const size_t length = 2u * size.width;
  uint32_t began = millis(), progress = began;
  imageProblem = ""; transferCut = false; rowsRead = 0;
  for (int y = 0; y < size.height; y++) {
    size_t received = 0;
    while (received < length) {
      bool idle = millis() - progress > timeout, closed = !stream->connected() && !stream->available();
      if (idle || closed) {
        imageProblem = String(idle ? String(F("no data for ")) + String(timeout / 1000) + " s" : "connection closed") + F(" at row ") + String(y) + "/"
            + String(size.height) + F(" after ") + String(millis() - began) + " ms";
        transferCut = true; return false;
      }
      volumeFeedback();
      int count = stream->read(bytes + received, length - received);
      if (count > 0) { received += count; progress = millis(); }
      if (millis() - lastFooter >= 250) footer();
      yield();
    }
    if (!onRow(y, bytes)) return false;
    rowsRead = y + 1;
  }
  return true;
}
#if defined(ESP32)
void shadowSpan(int row, int x, int width, const uint8_t *bytes) {
  if (!shadow) return;
  uint16_t *to = shadow + size_t(row) * shadowWidth + x;
  for (int i = 0; i < width; i++) to[i] = client_policy::pixel(bytes[2 * i], bytes[2 * i + 1]);
}
// Once, before the first image in the panel's size, when enough memory stays free for the next TLS handshake.
constexpr size_t SHADOW_RESERVE = 64 * 1024;
// First at the first picture; when the heap is short then because its TLS connection is still open, once more right
// after that pull (afterPull), which then loads the picture again to fill the copy.
bool shadowLater = false;
bool keepShadow(bool afterPull) {
  static bool tried = false;
  if (shadow || (tried && !afterPull)) return false;
  tried = true;
  size_t bytes = size_t(panel.width()) * panel.height() * 2;
  void *memory = psramFound() ? ps_malloc(bytes) : nullptr; bool psram = memory != nullptr;
  if (!memory && heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT) >= bytes
      && heap_caps_get_free_size(MALLOC_CAP_INTERNAL) >= bytes + SHADOW_RESERVE)
    memory = heap_caps_malloc(bytes, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
  if (!memory) {
    if (!afterPull && !psramFound()) { shadowLater = true; return false; }
    logEvent(F("No memory for a copy of the picture: a turn loads it again"), true); return false;
  }
  shadow = static_cast<uint16_t *>(memory); shadowWidth = panel.width(); shadowHeight = panel.height();
  forgetRows();  // every row of this image goes into the copy
  logEvent(String(F("Copy of the picture kept (")) + String(bytes / 1024) + (psram ? F(" kB in PSRAM") : F(" kB")) + F("): turns redraw it locally"), true);
  return true;
}
#endif
// The picture again from the copy, e.g. after a turn of the panel: no transfer, the ETag stays valid. False when
// there is no complete copy.
bool redrawFromShadow() {
#if defined(ESP32)
  const int width = panel.width(), height = panel.height();
  if (!shadow || !shadowOk || !imageOnScreen || width != shadowWidth || height != shadowHeight) return false;
  for (int y = 0; y < height; y++) if (!drawnRows[y]) return false;
  PhaseScope drawing(Phase::Draw);
  for (int y = 0; y < height; y++) {
    const uint16_t *from = shadow + size_t(y) * width;
    for (int x = 0; x < width; x++) imagePixels[x] = panelTone.apply(from[x]);
    imageRow(y, width, imagePixels);
  }
  return true;
#else
  return false;
#endif
}
bool readImageDelta(WiFiClient *stream,const client_policy::ImageSize &size,uint32_t length,uint32_t payload,uint32_t check,uint32_t timeout) {
  PhaseScope receiving(Phase::Image);
  uint16_t *pixels=imagePixels;
  uint32_t progress=millis();imageProblem=F("invalid delta, length or checksum");transferCut=false;rowsRead=0;
  return client_policy::readDelta(size,length,payload,check,drawnRows,imageBytes,[&](uint8_t *data,size_t n){
    size_t received=0;
    while(received<n){
      if(millis()-progress>timeout||(!stream->connected()&&!stream->available())){imageProblem=F("delta transfer interrupted");transferCut=true;return false;}
      volumeFeedback();
      int count=stream->read(data+received,n-received);
      if(count>0){received+=count;progress=millis();}
      yield();
    }
    return true;
  },[&](uint16_t row,uint16_t x,uint16_t width,const uint8_t *data){
    for(uint16_t i=0;i<width;i++)pixels[i]=panelTone.apply(client_policy::pixel(data[2*i],data[2*i+1]));
    imageSpan(row,x,width,pixels);
#if defined(ESP32)
    shadowSpan(row,x,width,data);
#endif
    rowsRead=row+1;return true;
  });
}
// Draws the image straight to the display, scaled to the whole screen (1:1 when the server renders for this
// display); the server draws the IP into it.
bool readImage(WiFiClient *stream, const client_policy::ImageSize &size, uint32_t timeout) {
  PhaseScope receiving(Phase::Image);  // rows are read and drawn in turn
  uint16_t *pixels=imagePixels;
  const uint16_t width = panel.width(), height = panel.height();
  imageOnScreen = true; reasonOnScreen = false; spinningStep = -1;  // the image takes the whole screen
#if defined(ESP32)
  bool copy = shadow && size.width == width && size.height == height && width == shadowWidth && height == shadowHeight;
  if (!copy) shadowOk = false;
#endif
  return readRows(stream, size, [&](int y, uint8_t *bytes) {
    // Unchanged rows are not redrawn, so a new image only touches the lines that differ.
    uint32_t hash = client_policy::rowHash(bytes, 2u * size.width);
    uint16_t from = client_policy::firstTarget(y, size.height, height), to = client_policy::firstTarget(y + 1, size.height, height);
    if (from >= to || hash == drawnRows[from]) return true;
    for (int x = 0; x < width; x++) {
      int s = client_policy::sourceOf(x, width, size.width);
      pixels[x] = panelTone.apply(client_policy::pixel(bytes[2 * s], bytes[2 * s + 1]));
    }
    for (int row = from; row < to && row < height; row++) { imageRow(row, width, pixels); drawnRows[row] = hash; }
#if defined(ESP32)
    if (copy) shadowSpan(y, 0, width, bytes);
#endif
    return true;
  }, timeout);
}
// One image request. https uses the simple check first (pinned certificate fingerprint); when that fails, e.g. after
// the certificate was renewed, a full check against the root from pairing follows, and only such a verified
// connection may hand over the new fingerprint.
// The client is declared before http, so http is destroyed first: HTTPClient's destructor calls stop() on the client
// it was given, and http.end() forgets it only while the connection is still open. With HTTP/1.0 the server closes it
// after the image, so the client used to be freed first and stop() then wrote into freed memory (heap corruption on
// the ESP32-C6: "assert failed: multi_heap_free", crashes in the Wi-Fi driver; every image change, more with swipes).
struct ImageRequest {
  std::unique_ptr<WiFiClient> client;
  HTTPClient http;
  bool verified = false;
  ~ImageRequest() { http.end(); }
  // Another connection for a second attempt: a fresh HTTPClient first, while the old client still exists, for the
  // same reason (http.end() would leave it pointing to the freed client).
  void retry(WiFiClient *next) { http.~HTTPClient(); new (&http) HTTPClient(); client.reset(next); }
};
// While the log is sent (uploadLog): the file streamed as the request body and the server's request id.
File *uploadFile = nullptr; String uploadRequest;
int sendImageRequest(ImageRequest &r, const String &url, bool conditional) {
  if (!r.http.begin(*r.client, url)) return HTTPC_ERROR_CONNECTION_FAILED;
  const char *wanted[] = {"ETag", "X-VibeDock-Image-Format", "X-VibeDock-Poll-Seconds", "X-VibeDock-Tls-Pin", "X-VibeDock-Redraw", "X-VibeDock-Update", "x-MD5", "X-VibeDock-Length", "X-VibeDock-Send-Log", "X-VibeDock-Orientation",
      "X-VibeDock-Base", "X-VibeDock-Check", "X-VibeDock-Payload", "X-VibeDock-Next-Pull", "X-VibeDock-UI"
#ifdef VIBEDOCK_AUDIO_ES8311
      , "X-VibeDock-Quota"
#endif
#ifdef VIBEDOCK_BATTERY
      , "X-VibeDock-Power-Saving"
#endif
  };
  r.http.collectHeaders(wanted, sizeof(wanted)/sizeof(wanted[0]));
  // HTTP/1.0: a reverse proxy (Synology) then cannot answer chunked; the body ends with the connection or its length.
  r.http.useHTTP10(true);
  r.http.setTimeout(r.verified ? 15000 : 5000);
  r.http.addHeader("Accept-Encoding", "identity");  // never compressed: the image is read byte for byte  // over TLS 1.3 a 16 kB record has to arrive whole before any byte reads r.http.setReuse(false);
  r.http.addHeader("Authorization", String(F("Bearer ")) + key);
  r.http.addHeader("X-VibeDock-Device-IP", WiFi.localIP().toString());  // the server may only see a NAT gateway
  r.http.addHeader("X-VibeDock-Subnet-Mask", WiFi.subnetMask().toString());  // USB and legacy registrations learn their mask once
  r.http.addHeader("X-VibeDock-UI", "1");
  r.http.addHeader("X-VibeDock-Footer", "server");  // this firmware shows the IP the server draws into the image
  r.http.addHeader("X-VibeDock-Firmware", VIBEDOCK_VERSION);  // lets the administration offer updates
  r.http.addHeader("X-VibeDock-Display", String(panel.width()) + "x" + String(panel.height()));  // suggests the display type
  r.http.addHeader("X-VibeDock-Board", VIBEDOCK_BOARD);  // which firmware build an update needs
  r.http.addHeader("X-VibeDock-Diag", diagHeader());  // start, run time, memory, Wi-Fi (older servers ignore it)
  // Updates over Wi-Fi: "1" with the server's signing key, "no-key" when paired before the server sent one.
  r.http.addHeader("X-VibeDock-Ota", platform::hasOtaKey() ? "1" : "no-key");
  if (!updateError.isEmpty()) r.http.addHeader("X-VibeDock-Update-Error", updateError);
  if (r.verified) r.http.addHeader("X-VibeDock-Tls-Check", "full");
  if (conditional && !etag.isEmpty() && haveImage && imageOnScreen) r.http.addHeader("If-None-Match", etag);
  if (conditional && !uploadFile && resuming && imageOnScreen) r.http.addHeader("X-VibeDock-Resume", resumeToken());
  if (conditional && !uploadFile) r.http.addHeader("X-VibeDock-Delta", "1");
  if (conditional && !uploadFile) r.http.addHeader("X-VibeDock-Schedule", "1");  // follows X-VibeDock-Next-Pull
  if (conditional && !pendingGesture.isEmpty()) r.http.addHeader("X-VibeDock-Gesture", pendingGesture);
  if (uploadFile) {  // the log, straight from flash: no copy on the heap the TLS handshake needs
    r.http.addHeader("Content-Type", "text/plain; charset=utf-8");
    r.http.addHeader("X-VibeDock-Log-Request", uploadRequest);
    uploadFile->seek(0);  // again from the start when a second TLS attempt follows
    return r.http.sendRequest("POST", uploadFile, uploadFile->size());
  }
#ifdef VIBEDOCK_AUDIO_ES8311
  if (conditional && audio::available()) r.http.addHeader("X-VibeDock-Audio", "1");
#endif
#ifdef VIBEDOCK_BATTERY
  // On its battery: the server draws a small battery icon (with the level when the board measures it).
  if (powerSource.battery) r.http.addHeader("X-VibeDock-Power", batteryLevel >= 0 ? String(F("battery;level=")) + batteryLevel : String(F("battery")));
#endif
  if (!conditional) r.http.addHeader("X-VibeDock-Preview", "1");  // the status page's copy; leaves a forced redraw for the screen
  return r.http.GET();
}
String lastConnectionProblem;  // why the last request failed before an HTTP answer, for the device log
// Without a TLS error the connection itself failed: name lookup or TCP.
String connectionDetail(const String &url) {
  int start = url.indexOf("://") + 3, end = url.indexOf('/', start); String host = url.substring(start, end < 0 ? url.length() : end);
  int colon = host.lastIndexOf(':'); String port = colon > 0 ? host.substring(colon + 1) : (url.startsWith("https") ? "443" : "80");
  if (colon > 0) host.remove(colon);
  IPAddress address;
  if (!WiFi.hostByName(host.c_str(), address)) return String(F("name ")) + host + F(" not resolved (DNS)");
  String target = address.toString() + ":" + port;
  WiFiClient probe; probe.setTimeout(4000);
  if (!probe.connect(address, port.toInt())) return String(F("no TCP connection to ")) + target;
  probe.stop();
  // TCP works, so TLS or the HTTP exchange failed.
  return url.startsWith("https") ? String(F("TCP to ")) + target + F(" works, TLS or HTTP failed") : String(F("TCP to ")) + target + F(" works, no HTTP answer");
}
int requestImage(ImageRequest &r, bool conditional, const char *resource = "image") {
  String url = servers[serverIndex] + F("/api/v1/clients/") + identifier + "/" + resource;
  PhaseScope connecting(url.startsWith("https://") ? Phase::Tls : Phase::Connect);
  lastConnectionProblem = "";
  if (!url.startsWith("https://")) {
    r.client.reset(new WiFiClient()); int code = sendImageRequest(r, url, conditional);
    if (code < 0) lastConnectionProblem = connectionDetail(url);
    return code;
  }
  uint8_t fingerprint[20]; WiFiClient *pinned;
  if (client_policy::parseFingerprint(pin.c_str(), fingerprint) && (pinned = platform::pinnedClient(fingerprint))) {
    r.client.reset(pinned);
    parkLog();  // the log's heap for the handshake; the next event brings it back
    int code = sendImageRequest(r, url, conditional);
    if (code > 0) return code;
    // The pinned handshake worked and the request failed afterwards: the network, not the certificate.
    if (platform::tlsError(r.client.get()).isEmpty() && !platform::tlsSummary().isEmpty()) {
      lastConnectionProblem = HTTPClient::errorToString(code) + " (" + String(code) + F(") after TLS; ") + platform::tlsTraffic() + "; " + platform::tlsSummary();
      return code;
    }
    logEvent(String(F("Pinned certificate check failed: ")) + HTTPClient::errorToString(code) + " " + platform::tlsError(r.client.get()), true);
    r.retry(nullptr);
  }
  // Full check: chain up to the root from pairing, host name and validity; never an unchecked connection.
  if (!platform::hasAnchor()) { lastConnectionProblem = "no root certificate for https from pairing"; return HTTPC_ERROR_CONNECTION_FAILED; }
  if (!clockSet()) { lastConnectionProblem = "clock not set yet (NTP), needed for https"; return HTTPC_ERROR_CONNECTION_FAILED; }
  r.retry(platform::verifiedClient()); r.verified = true;
  parkLog();  // the log's heap for the handshake; the next event brings it back
  int code = sendImageRequest(r, url, conditional);
  if (code < 0) lastConnectionProblem = platform::tlsError(r.client.get());
  // Handshake done, then the HTTP exchange failed: say so instead of guessing at TLS below.
  if (code < 0 && lastConnectionProblem.isEmpty() && !platform::tlsSummary().isEmpty())
    lastConnectionProblem = HTTPClient::errorToString(code) + " (" + String(code) + F(") after TLS; ") + platform::tlsTraffic() + "; " + platform::tlsSummary();
  // A server that only speaks TLS 1.2: the same full check over TLS 1.2, where the board supports it.
  WiFiClient *legacy;
  if (code < 0 && platform::versionRefused() && (legacy = platform::legacyClient())) {
    logEvent(F("Server refuses TLS 1.3, using TLS 1.2"), true);
    r.retry(legacy);
    code = sendImageRequest(r, url, conditional);
    if (code < 0) lastConnectionProblem = platform::tlsError(r.client.get(), true);
  }
  if (code < 0 && lastConnectionProblem.isEmpty()) lastConnectionProblem = connectionDetail(url);
  return code;
}
// After a full check the server names the current certificate fingerprint; later requests use the simple check.
void learnPin(ImageRequest &r) {
  if (!r.verified) return;
  // The server's pin for its current certificate, otherwise the certificate this full check verified itself.
  String next = r.http.hasHeader("X-VibeDock-Tls-Pin") ? r.http.header("X-VibeDock-Tls-Pin") : platform::verifiedFingerprint(r.client.get());
  next.toLowerCase(); uint8_t fingerprint[20];
  if (next != pin && client_policy::parseFingerprint(next.c_str(), fingerprint)) { pin = next; saveConfig(); }
}
client_policy::ImageSize lastImageSize;  // a different size invalidates the row cache
bool validImage(HTTPClient &http, client_policy::ImageSize &size) {
  // The length may be missing behind a reverse proxy (-1); the format header gives it, and reading checks it.
  return client_policy::parseImageFormat(http.header("X-VibeDock-Image-Format").c_str(), size) && (http.getSize() < 0 || http.getSize() == int(size.bytes()));
}
// Downloads the firmware the server offered and installs it; the updater checks the server's signature before it
// switches over. On any problem the running firmware stays and the reason goes to the server with the next pull.
void updateProgress(size_t done, size_t total) {
  int width = total ? int(160ull * done / total) : 0;
  display.fillRoundRect(40, 156, 160, 8, 4, TRACK); display.fillRoundRect(40, 156, max(width, 8), 8, 4, ACTIVE);
  present();
}
void installUpdate(const String &version) {
  PhaseScope updating(Phase::Ota);
  message(String(F("Updating to v")) + version, Step::None); updateProgress(0, 1); logEvent(String(F("Update to v")) + version + F(" started"));
  auto fail = [&](const String &reason) {
    updateError = version + ": " + reason; failedUpdate = version; logEvent(String(F("Update failed: ")) + reason);
    message("Update failed", Step::None, true); showDetail(reason.substring(0, 34), PROBLEM); delay(4000);
    haveImage = false; staleScreen = true; etag = ""; forgetRows(); pullSoon();
  };
  ImageRequest request; HTTPClient &http = request.http;
  int code = requestImage(request, false, "firmware");
  int size = http.getSize();
  if (size <= 0) size = http.header("X-VibeDock-Length").toInt();  // behind a reverse proxy that drops Content-Length
  if (code != 200 || size <= 0) { fail(code > 0 ? String(F("download HTTP ")) + String(code) : String("download failed")); return; }
  String error;
  bool installed = platform::installSigned(http.getStreamPtr(), size_t(size), http.header("x-MD5"), error, updateProgress);
  http.end();
  if (!installed) { fail(error); return; }
  updateProgress(1, 1); message("Update installed, restarting", Step::None); logEvent(String(F("Update to v")) + version + F(" installed"));
  diagIntent(Intent::Update); delay(800); ESP.restart();
}
// Sends /log.txt to the server that asked for it (X-VibeDock-Send-Log), over the same address and TLS as the images.
void uploadLog(const String &requestId) {
  PhaseScope sending(Phase::Log);
  saveLog();  // the file holds every line; while parked it is complete already
  File file = LittleFS.open("/log.txt", "r");
  if (!file) return;
  uploadFile = &file; uploadRequest = requestId;
  int code;
  { ImageRequest request; code = requestImage(request, false, "log"); }
  uploadFile = nullptr; uploadRequest = ""; file.close();
  logEvent(code == 204 ? String("Log sent to the server") : String(F("Log not sent: ")) + (code > 0 ? String(F("HTTP ")) + String(code) : HTTPClient::errorToString(code)), true);
}
// The start of an error answer, e.g. the server's reason for a 400, as one printable line.
String answerStart(HTTPClient &http) {
  WiFiClient *stream = http.getStreamPtr(); if (!stream) return String();
  char text[61]; stream->setTimeout(1000); size_t length = stream->readBytes(text, sizeof(text) - 1);
  for (size_t i = 0; i < length; i++) if (text[i] < 32 || text[i] > 126) text[i] = ' ';
  text[length] = 0; String answer(text); answer.trim(); return answer;
}
// Modem sleep stretches a transfer over many beacon intervals, which weak connections do not survive: off for the
// whole pull on battery (with an update or log that follows it), on again afterwards.
struct ModemAwake {
  bool sleeping = powerSaving;
  ModemAwake() {
#if defined(ESP32)
    if (sleeping) WiFi.setSleep(false);
#endif
  }
  ~ModemAwake() {
#if defined(ESP32)
    if (sleeping && powerSaving) WiFi.setSleep(true);
#endif
  }
};
void pull() {
  lastPull = millis();
  if (WiFi.status() != WL_CONNECTED) return;
  ModemAwake awake;
  diagSample();
  if (reasonOnScreen && (!haveImage || staleScreen)) {  // the reason stays, the address line says what runs now
    display.fillRect(0, 210, 240, 10, TFT_BLACK);
    showWrapped(String(F("trying ")) + servers[serverIndex], 211, 1, MUTED, 1); present();
  }
  // On the heap: the TLS handshake below needs most of the ESP8266's 4 kB stack.
  std::unique_ptr<ImageRequest> holder(new ImageRequest()); ImageRequest &request = *holder; HTTPClient &http = request.http;
  // After a start, a display that last answered on its second address (e.g. the external one after a time on another
  // network) tries the first one (the LAN address) once more. Not reachable: no log line, no error screen, the usual
  // address right after.
  static bool preferredTried = false;
  uint8_t usual = serverIndex; bool probing = false;
  if (!preferredTried && serverIndex != 0 && serverCount > 1) { serverIndex = 0; probing = true; }
  preferredTried = true;
  int code = requestImage(request, true);
  if (probing && code < 0) {
    serverIndex = usual; holder.reset(); pullSoon(); return;
  }
  if (probing) logEvent(String(F("Back on ")) + servers[serverIndex], true);
  if (code != lastStatus || code < 0) {
    String server = servers[serverIndex];
    String answer = code >= 400 && code != 401 ? answerStart(http) : String();
    if (code > 0) logEvent(server + F(": HTTP ") + String(code) + (answer.isEmpty() ? "" : " \"" + answer + "\"")
        + (server.startsWith("https") && !platform::tlsSummary().isEmpty() ? ", " + platform::tlsSummary() : String()), true);
    else logEvent(server + ": " + HTTPClient::errorToString(code) + (lastConnectionProblem.isEmpty() ? "" : ", " + lastConnectionProblem), true);
  }
  lastStatus = code;
  if (code > 0) {
    failedInRow = 0; pendingGesture = "";  // the server has it (an older one ignores it)
    if (storedIndex != serverIndex) saveConfig();  // flash only when the answering address changes
  }
  if (code == 200 || code == 304) {
    learnPin(request); updateError = "";
#ifdef VIBEDOCK_AUDIO_ES8311
    audio::quotas(http.header("X-VibeDock-Quota").c_str());
#endif
  }
  if (code == 200 || code == 304) {
    String appearance = http.header("X-VibeDock-UI");
    if ((appearance == "dark" || appearance == "light") && uiDark != (appearance == "dark")) {
      uiDark = appearance == "dark"; saveLater();
    }
  }
  String offered = code == 200 || code == 304 ? http.header("X-VibeDock-Update") : "";
  String logRequest = code == 200 || code == 304 ? http.header("X-VibeDock-Send-Log") : "";
  if (code > 0 && http.hasHeader("X-VibeDock-Poll-Seconds")) {
    uint32_t poll = client_policy::clampPoll(http.header("X-VibeDock-Poll-Seconds").toInt());
    if (poll != pollSeconds) { pollSeconds = poll; saveConfig(); }
  }
  // When the picture can change next: pull then, also after a 304, instead of every poll interval. Older servers
  // send nothing and the poll interval applies.
  uint32_t nextSeconds = 0; bool nextPending = false, turnedWithoutCopy = false;
  if ((code == 200 || code == 304) && client_policy::parseNextPull(http.header("X-VibeDock-Next-Pull").c_str(), nextSeconds, nextPending)) {
    pullWaitMs = nextSeconds * 1000u; pullPending = nextPending; lastPull = millis();
  } else { pullWaitMs = 0; pullPending = false; }
  if ((code == 200 || code == 304) && http.hasHeader("X-VibeDock-Orientation")) {
    uint8_t next = client_policy::turnsOf(http.header("X-VibeDock-Orientation").c_str(), turns, panel.width() == panel.height());
    if (next != turns) {
      // Without a copy the picture comes again, whole; a patch in this answer was made for the old rows.
      bool onScreen = imageOnScreen;
      if (!applyOrientation(next) && onScreen) turnedWithoutCopy = true;
      saveConfig();
      logEvent(next == 0 ? String(F("Picture upright")) : next == 2 ? String(F("Picture flipped")) : String(F("Picture turned ")) + String(next * 90) + F(" degrees"), true);
    }
  }
#ifdef VIBEDOCK_BATTERY
  // Battery power saving, switched in the administration ("battery" or "off"); powerPoll applies it right away.
  if ((code == 200 || code == 304) && http.hasHeader("X-VibeDock-Power-Saving")) {
    bool allowed = http.header("X-VibeDock-Power-Saving") == "battery";
    if (allowed != powerSavingAllowed) {
      powerSavingAllowed = allowed; saveConfig(); lastPowerCheck = millis() - client_policy::powerCheckMs;
      logEvent(allowed ? F("Battery power saving allowed (experimental)") : F("Battery power saving switched off"), true);
    }
  }
#endif
  client_policy::ImageSize size;
  if (code == 200) {
    bool delta=client_policy::parseImageFormat(http.header("X-VibeDock-Image-Format").c_str(),size,true);
    bool full=!delta&&validImage(http,size);
    uint32_t length=0,payload=0,check=0;
#if defined(ESP32)
    if (full&&size.width==panel.width()&&size.height==panel.height()) keepShadow(false);
#endif
    // The basis of a delta: the complete image on screen (its ETag) or, after a cut-off transfer, what it shows.
    String base=http.header("X-VibeDock-Base");
    bool basis=(deltaReady&&haveImage&&imageOnScreen&&!etag.isEmpty()&&base==etag)||(resuming&&imageOnScreen&&base==resumeToken());
    bool accepted=full || (delta&&basis&&http.header("X-VibeDock-Redraw")!="1"
        &&size.width==panel.width()&&size.height==panel.height()
        &&size.width==lastImageSize.width&&size.height==lastImageSize.height
        &&client_policy::deltaLength(http.header("X-VibeDock-Length").c_str(),size.bytes(),length)
        &&(http.getSize()<0||http.getSize()==int(length))
        &&client_policy::parseHash(http.header("X-VibeDock-Payload").c_str(),payload)
        &&client_policy::parseHash(http.header("X-VibeDock-Check").c_str(),check));
    if (full&&(http.header("X-VibeDock-Redraw")=="1"||size.width!=lastImageSize.width||size.height!=lastImageSize.height)) forgetRows();
    // What the screen shows before this transfer, for a resume when it is cut off.
    bool priorComplete=deltaReady&&haveImage&&imageOnScreen&&!etag.isEmpty(),priorOnScreen=imageOnScreen,priorResuming=resuming;
    uint16_t priorSplit=priorComplete?size.height:resumeRows,priorRows=resumeRows;
    String priorTop=priorComplete?etag:resumeTop,priorBottom=resumeBottom,priorResumeTop=resumeTop;
    uint32_t reading=millis();bool first=!haveImage;bool https=servers[serverIndex].startsWith("https");
    imageProblem=F("unsupported image or delta basis");
    bool complete=accepted&&(delta
        ?readImageDelta(http.getStreamPtr(),size,length,payload,check,https?15000:8000)
        :readImage(http.getStreamPtr(),size,https?15000:8000));
    if (!complete && delta && turnedWithoutCopy) {
      pullSoon();  // not an error: the whole picture in the new orientation follows right away
    } else if (complete) {
      ++imageRevision;
      lastImageSize=size;deltaReady=size.width==panel.width()&&size.height==panel.height();
      etag = http.header("ETag"); lastImage = millis(); haveImage = true; staleScreen = false; footer(); imageFailures = 0; resuming = false;
      if (first) logEvent(String(F("Image shown, read in ")) + String(millis() - reading) + " ms", true);
    } else {
      pullWaitMs = client_policy::waitAfterImage(false, pullWaitMs); pullPending = false;
      // Cut off on the way: the rows drawn so far stay, the next pull asks only for the rest (X-VibeDock-Resume).
      String shown=http.header("ETag");
      bool cut=transferCut&&accepted&&!shown.isEmpty()&&size.width==panel.width()&&size.height==panel.height();
      bool progressed=false;
      if (cut&&full&&rowsRead==0) {
        imageOnScreen=priorOnScreen;  // nothing drawn: the screen and what is known about it stay
      } else if (cut) {
        auto below=client_policy::belowCut(priorComplete||priorResuming,rowsRead,priorSplit,size.height);
        progressed=!priorResuming||shown!=priorResumeTop||rowsRead>priorRows;
        resumeBottom=below==client_policy::Below::Top?priorTop:below==client_policy::Below::Bottom?priorBottom:String();
        resumeTop=shown;resumeRows=rowsRead;resuming=true;
        lastImageSize=size;deltaReady=false;etag="";
      } else {
        etag=""; resuming=false;
        // A rejected/partial patch can have changed pixels without a trustworthy row hash. Redraw the whole basis.
        if(delta)forgetRows();
      }
      logEvent(String(F("Image aborted: ")) + imageProblem + (resuming ? String(F(", rest from row ")) + String(resumeRows) : String())
          + (https ? ", " + platform::tlsTraffic() : String()), true);
      if (progressed) imageFailures = 0;  // weak but moving: on with the rest instead of the error screen
      if (++imageFailures == 1 && (haveImage || resuming)) pullSoon(1000);
      else {
        haveImage = false; staleScreen = true; message("Image aborted", Step::Image, true);
        showWrapped(imageProblem, 145, 4, PROBLEM); present();
      }
    }
  } else if (code == 304) {
    lastImage = millis(); staleScreen = false;
  } else if (code == 401) {
    logEvent(F("Server rejected the device key (401): pairing removed"));
    http.end(); unpair(); return;
  } else if (code < 0) {
    // Connection failed: try the other announced address next time. An answer that stalled after a successful TLS
    // handshake (lost packets on the way) is first retried at the same address.
    bool stalled = lastConnectionProblem.indexOf(" after TLS;") >= 0;
    uint8_t next = stalled && failedInRow == 0 ? serverIndex : client_policy::nextServer(serverIndex, serverCount);
    if (next != serverIndex) logEvent(String(F("Trying ")) + servers[next] + F(" next"), true);
    else if (stalled) logEvent(String(F("Retrying ")) + servers[next], true);
    String failed = servers[serverIndex];
    serverIndex = next;
    // The next attempt right away (as many as there are addresses), not only after the poll interval.
    if (++failedInRow <= serverCount) pullSoon(1000);
    else failedInRow = 0;
    // A half-loaded picture stays during the immediate retries, so its rest can follow.
    if ((!haveImage || staleScreen) && !(resuming && failedInRow)) {
      // Why, without a USB cable (reading the log over USB restarts the M1): the reason, e.g. the TLS error, wrapped
      // in place of the progress row, and the address that failed at the bottom.
      if (lastConnectionProblem.isEmpty()) message("Server unreachable", Step::Server, true);
      else {
        message("Server unreachable", Step::None, true);
        showWrapped(lastConnectionProblem, 145, 4, PROBLEM);
        showWrapped(failed, 211, 1, MUTED, 1);
        present(); reasonOnScreen = true;
      }
    }
  }
  http.end();
  holder.reset();  // the image connection's memory back before the next one
#if defined(ESP32)
  if (shadowLater && haveImage && imageOnScreen) {
    shadowLater = false;
    if (keepShadow(true)) { etag = ""; pullSoon(1000); }  // the copy is filled with the next, whole picture
  }
#endif
  if (client_policy::validLogRequest(logRequest.c_str())) uploadLog(logRequest);
  if (!offered.isEmpty() && offered != VIBEDOCK_VERSION && offered != failedUpdate && platform::hasOtaKey()) installUpdate(offered);
  diagPhase(Phase::Idle);
}

// ---- status page on port 80 (local network) ----
String statusLine(String &css) {
  if (mode == Mode::Pairing) { css = "warn"; return "Waiting for invitation"; }
  if (lastStatus == 0) { css = "warn"; return "Connecting to server"; }
  if (lastStatus < 0) { css = "bad"; return "Server unreachable"; }
  if (lastStatus != 200 && lastStatus != 304) { css = "bad"; return String(F("Server answered HTTP ")) + String(lastStatus); }
  if (staleScreen) { css = "warn"; return "Updates missing"; }
  css = "ok"; return "Connected";
}
void colourCard();
void audioCard();
// Power source and battery power saving for the status page: closes the open <dd> of the row before, then <dt>/<dd>
// pairs (the Power card of boards without battery measurement drops the leading "</dd>").
String powerRows() {
  String rows = F("</dd><dt>Power source</dt><dd>");
  rows += powerSource.battery ? F("Battery (no USB host)") : F("USB or charging");
  rows += F("</dd><dt>Power saving</dt><dd>");
  rows += !powerSavingAllowed ? F("Off (set in the administration)") : powerSaving ? (dimmed ? F("On, backlight dimmed") : F("On"))
      : F("Allowed, waits for battery operation");
  return rows + F("</dd>");
}
void statusPage() {
  bool paired = mode == Mode::Paired;
  pageStart(html(deviceName));
  String css, state = statusLine(css);
  String body = String(F("<section class=\"card\"><h2>Status</h2><p class=\"state ")) + css + "\">" + state + F("</p><dl>"
      "<dt>IP address</dt><dd>") + currentIp() + F("</dd><dt>Wi-Fi</dt><dd>") + html(WiFi.SSID()) + " (" + String(WiFi.RSSI()) + F(" dBm)</dd>");
  if (paired) {
    body += String(F("<dt>Server</dt><dd>")) + html(servers[serverIndex]) + F("</dd><dt>Last update</dt><dd>")
        + (haveImage ? String((millis() - lastImage) / 1000) + F(" s ago") : String("-")) + F("</dd><dt>Fallback poll interval</dt><dd>")
        + String(pollSeconds) + F(" s</dd>") + (pullWaitMs ? String(F("<dt>Next pull</dt><dd>in "))
        + String((pullWaitMs - min(pullWaitMs, uint32_t(millis() - lastPull)) + 999) / 1000) + F(" s")
        + (pullPending ? F(" (new readings expected)") : F(" (as announced by the server)")) + F("</dd>") : String());
  }
  body += String(F("<dt>Device ID</dt><dd>")) + identifier + F("</dd><dt>Last start</dt><dd>") + html(String(startKind) + " (" + platform::resetReason() + ")") + F("</dd><dt>Firmware</dt><dd>" VIBEDOCK_VERSION "</dd></dl>");
  if (!paired) body += F("<p class=\"muted\">Enter this IP address and the pairing code shown on the display in the VibeDock administration.</p>");
  body += F("</section>");
  web.sendContent(body);
  auto power = battery::read();
  if (power.supported) {
    String card = F("<section class=\"card\"><h2>Battery</h2><dl><dt>Measured voltage</dt><dd>");
    card += String(power.millivolts / 1000.0, 2) + F(" V</dd><dt>Estimated level</dt><dd>");
    card += power.estimatedPercent < 0 ? String(F("Unknown")) : String("~") + String(power.estimatedPercent) + " %";
#ifdef VIBEDOCK_BATTERY_CHARGE
    card += F("</dd><dt>Charging signal</dt><dd>"); card += power.chargeSignal;
#else
    card += F("</dd><dt>Charging signal</dt><dd>none on this board");
#endif
    card += powerRows();
    card += F("<dt>Battery present</dt><dd>Unknown</dd></dl><p class=\"muted\">Voltage-based estimate only. "
        "USB power and charging affect the reading. A voltage or charging signal does not prove a battery is fitted; "
        "an inactive signal does not mean it is full. Reload this page to measure again.</p></section>");
    web.sendContent(card);
  }
#ifdef VIBEDOCK_BATTERY
  else web.sendContent(String(F("<section class=\"card\"><h2>Power</h2><dl>")) + powerRows().substring(5) + F("</dl></section>"));
#endif
  audioCard();
  if (paired) colourCard();
  pageEnd();
}
// Audio controls stay local to the device; no server setting or credentials are needed.
void audioCard() {
#ifdef VIBEDOCK_AUDIO_ES8311
  if (!audio::available()) {
    web.sendContent(F("<section class=\"card\"><h2>Sounds</h2><p>Audio unavailable: codec initialization failed.</p></section>")); return;
  }
  web.sendContent(String(F("<section class=\"card\"><h2>Sounds</h2><form method=\"post\" action=\"/audio\">"
      "<label class=\"f\">Volume (0 = mute) <input type=\"range\" name=\"volume\" min=\"0\" max=\"100\" step=\"10\" value=\""))
      + String(audio::volume()) + F("\"></label>"));
  web.sendContent(String(F("<p>Current volume: ")) + String(audio::volume()) + F(" %</p><button>Save volume</button>"
      "<button name=\"test\" value=\"1\">Save and play test tone</button><button name=\"mute\" value=\"1\">Mute</button>"
      "</form><p class=\"muted\">The + and - buttons adjust volume in steps of 10. Settings survive restarts. "
      "Each reported quota increase or decrease sounds once; full uses the rising melody, empty the low beep. "
      "A sustained connection loss sounds once. Pairing prompts do not repeat continuously.</p></section>"));
#endif
}
// Colour correction of this panel. Every change is applied when a slider is released and the image is redrawn,
// so the display can be compared with the server preview right away; the BMP above stays uncorrected.
String slider(const char *name, const __FlashStringHelper *label, int value, int low, int high, int step, bool gamma) {
  // Text in flash (F): on the ESP8266 plain literals take RAM the TLS handshake needs.
  String s = F("<label class=\"f\" for=\""); s += name; s += F("\">"); s += label; s += F(" <b>");
  s += gamma ? String(value / 100.0, 2) : String(value) + F(" %");
  s += F("</b></label><input type=\"range\" id=\""); s += name; s += F("\" name=\""); s += name;
  s += F("\" min=\""); s += low; s += F("\" max=\""); s += high; s += F("\" step=\""); s += step; s += F("\" value=\""); s += value;
  s += F("\" oninput=\"this.previousSibling.lastChild.textContent=");
  s += gamma ? F("(this.value/100).toFixed(2)") : F("this.value+' %'");
  s += F("\" onchange=\"const b=new URLSearchParams(new FormData(this.form));b.set('live','1');fetch('/colours',{method:'POST',body:b}).catch(()=>this.form.submit())\">");
  return s;
}
// The image as the server sends it, uncorrected, next to the sliders: the reference the display is matched to. A
// slider change goes to the display in the background; the page and the image stay as they are.
void colourCard() {
  using namespace client_policy;
  uint16_t w = lastImageSize.width ? lastImageSize.width : 240, h = lastImageSize.height ? lastImageSize.height : 240;
  String card = F("<form action=\"/colours\" method=\"post\"><section class=\"card\"><h2>Display colours</h2>"
      "<div style=\"display:flex;gap:18px;flex-wrap:wrap;align-items:flex-start\"><figure style=\"margin:0;flex:none\"><img class=\"screen\" src=\"/image.bmp?t=");
  card += millis(); card += F("\" data-image-version=\""); card += String(diag.boot) + ":" + String(imageRevision); card += F("\" width=\""); card += w; card += F("\" height=\""); card += h; card += F("\" style=\"width:"); card += w;
  card += F("px;height:"); card += h;
  card += F("px;max-width:100%;object-fit:contain;image-rendering:pixelated\" alt=\"Current image\">"
      "<figcaption class=\"muted\">Uncorrected, as the server sends it. <a href=\"/\">Reload</a></figcaption></figure><div style=\"flex:1;min-width:200px\">");
  card += slider("gamma", F("Gamma (higher: darker mid tones)"), toneSettings.gamma, minGamma, maxGamma, 5, true);
  card += slider("red", F("Red"), toneSettings.red, minGain, maxGain, 1, false);
  card += slider("green", F("Green"), toneSettings.green, minGain, maxGain, 1, false);
  card += slider("blue", F("Blue"), toneSettings.blue, minGain, maxGain, 1, false);
  card += F("<p class=\"muted\">Applied to this display only: move a slider until the display looks like the image beside it.</p>"
      "<button name=\"reset\" value=\"1\">Reset to defaults</button></div></div></section></form>");
  web.sendContent(card);
}
// Settings changed in quick series (brightness swipes, colour sliders): written to flash once, 3 s after the last
// change, between pulls. Writing at once with every slider move restarted the ESP32-C6 ("Exception").
uint32_t configChangedAt = 0; bool configDirty = false;
void saveLater() { configDirty = true; configChangedAt = millis(); }
void savePending() { if (configDirty && millis() - configChangedAt >= 3000) { configDirty = false; saveConfig(); } }
void saveAudio() {
#ifdef VIBEDOCK_AUDIO_ES8311
  if (!audio::available()) { web.send(503, "text/plain", "Audio unavailable"); return; }
  String value = web.arg("volume");
  if (!web.hasArg("mute") && (value.isEmpty() || value.length() > 3 || value.toInt() < 0 || value.toInt() > 100)) {
    web.send(400, "text/plain", "Invalid volume"); return;
  }
  for (char c : value) if (c < '0' || c > '9') { web.send(400, "text/plain", "Invalid volume"); return; }
  auto before = audio::volume();
  audio::setVolume(web.hasArg("mute") ? 0 : value.toInt()); audio::showVolume(); saveLater();
  if (web.hasArg("test")) audio::play(audio::Cue::Up);
  else if (audio::volume() > before) audio::play(audio::Cue::Louder);
  else if (audio::volume() < before) audio::play(audio::Cue::Quieter);
  web.sendHeader("Location", "/", true); web.send(303, "text/plain", "");
#else
  web.send(404, "text/plain", "Audio not supported");
#endif
}
void saveColours() {
  using namespace client_policy;
  if (web.hasArg("reset")) toneSettings = ToneSettings();
  else {
    toneSettings.gamma = clampGamma(web.arg("gamma").toInt()); toneSettings.red = clampGain(web.arg("red").toInt());
    toneSettings.green = clampGain(web.arg("green").toInt()); toneSettings.blue = clampGain(web.arg("blue").toInt());
  }
  panelTone.build(toneSettings); saveLater();
  // Redraw everything with the new correction: from the copy of the picture, else unconditional request next loop.
  if (!redrawFromShadow()) { forgetRows(); etag = ""; pullSoon(); }
  if (web.hasArg("live")) { web.send(204, "text/plain", ""); return; }  // slider moved, the page stays
  web.sendHeader("Location", "/", true); web.send(303, "text/plain", "");
}
// The device keeps no copy of the image (too little RAM), so it fetches a fresh one from the server and
// passes it on as a top-down 16-bit BMP (RGB565 bit fields); the server sends big-endian, BMP wants little-endian.
void imagePage() {
  if (mode != Mode::Paired) { web.send(404, "text/plain", String(F("Not paired"))); return; }
  ImageRequest request; HTTPClient &http = request.http;
  client_policy::ImageSize size;
  if (requestImage(request, false) != 200 || !validImage(http, size)) { web.send(502, "text/plain", String(F("No image from the server"))); return; }
  uint8_t head[66] = {'B', 'M'};
  auto put = [&](int offset, uint32_t value, int bytes) { for (int i = 0; i < bytes; i++) head[offset + i] = value >> (8 * i); };
  // BMP rows are padded to 4 bytes: odd widths get 2 extra bytes per row.
  const uint32_t row = 2u * size.width, padded = (row + 3) & ~3u, data = padded * size.height;
  put(2, sizeof(head) + data, 4); put(10, sizeof(head), 4); put(14, 40, 4); put(18, size.width, 4); put(22, uint32_t(-int32_t(size.height)), 4);
  put(26, 1, 2); put(28, 16, 2); put(30, 3, 4); put(34, data, 4); put(38, 2835, 4); put(42, 2835, 4);
  put(54, 0xF800, 4); put(58, 0x07E0, 4); put(62, 0x001F, 4);
  web.sendHeader("Cache-Control", "no-store");
  web.setContentLength(sizeof(head) + data);
  web.send(200, "image/bmp", "");
  web.sendContent((const char *)head, sizeof(head));
  readRows(http.getStreamPtr(), size, [&](int, uint8_t *bytes) {
    for (uint32_t i = 0; i < row; i += 2) std::swap(bytes[i], bytes[i + 1]);
    web.sendContent((const char *)bytes, row);
    if (padded != row) web.sendContent("\0\0", padded - row);
    return web.client().connected();
  }, 15000);
  http.end();
}
// The server asks for an immediate pull, e.g. after a design change. No data: the pull itself is authenticated.
uint32_t lastPullRequest = 0, lastUiRequest = 0;
void handlePullRequest() {
  // Cosmetic state only, on the existing LAN callback. A UI-only push never requests another image. The callback is
  // unauthenticated and a change is saved to flash: at most one change per pause, like image pulls; one refused
  // here arrives with the next image response (X-VibeDock-UI).
  if (web.hasArg("ui")) {
    String appearance=web.arg("ui");
    if (appearance!="dark" && appearance!="light") { web.send(400,"text/plain",""); return; }
    if (uiDark != (appearance=="dark")) {
      if (lastUiRequest != 0 && !client_policy::pullRequestAllowed(millis(), lastUiRequest)) { web.send(429,"text/plain",""); return; }
      lastUiRequest = millis(); if (lastUiRequest == 0) lastUiRequest = 1;
      uiDark=appearance=="dark"; saveLater();
    }
    web.send(202,"text/plain",""); return;
  }
  if (mode != Mode::Paired) { web.send(409, "text/plain", ""); return; }
  pullWaitMs = 0;  // the poll interval applies until the server answers again
  uint32_t poll = client_policy::clampPoll(pollSeconds) * 1000u;
  if (lastPullRequest != 0 && !client_policy::pullRequestAllowed(millis(), lastPullRequest)) {
    // Too soon after the last one (e.g. a setting switched on and off again): pulled when the pause is over, not
    // only at the next poll. Still at most one pull per pause.
    uint32_t wait = client_policy::pullRequestMs - (millis() - lastPullRequest);
    if (millis() - lastPull < poll - wait) lastPull = millis() - poll + wait;
    web.send(202, "text/plain", ""); return;
  }
  lastPullRequest = millis(); if (lastPullRequest == 0) lastPullRequest = 1;
  lastPull = millis() - poll;  // due in the next loop
  web.send(202, "text/plain", "");
}
void startPairingServer() {
  web.on("/", HTTP_GET, statusPage);
  serveIcons(); serveUi();
  web.on("/image.bmp", HTTP_GET, imagePage);
  web.on("/colours", HTTP_POST, saveColours);
#ifdef VIBEDOCK_AUDIO_ES8311
  web.on("/audio", HTTP_POST, saveAudio);
#endif
  web.on("/pull", HTTP_POST, handlePullRequest);
  web.on("/pair", HTTP_POST, handlePair);
  web.onNotFound([] { web.send(404, "text/plain", ""); });
  web.begin();
}

// ---- setup over USB ----
// Right after flashing, the administration's browser flasher sets the device up over the same cable: it asks for the
// identifier (VIBEDOCK-HELLO), the networks in reach (VIBEDOCK-SCAN) and then sends Wi-Fi and pairing in one line
// (VIBEDOCK-SETUP {json}). Only reachable over the cable, which can rewrite the whole flash anyway.
String serialLine;
void batteryJson(JsonObject target) {
  auto power = battery::read();
  target["supported"] = power.supported;
  target["present"] = nullptr;  // the charging circuit has no reliable battery-presence output
  target["voltageMv"] = nullptr; target["estimatedPercent"] = nullptr;
  target["chargeSignal"] = power.chargeSignal;
#ifdef VIBEDOCK_BATTERY
  target["powerSource"] = powerSource.battery ? "battery" : "usb";
  target["powerSaving"] = !powerSavingAllowed ? "off" : powerSaving ? "active" : "allowed";
#endif
  if (power.supported) {
    target["voltageMv"] = power.millivolts;
    if (power.estimatedPercent >= 0) target["estimatedPercent"] = power.estimatedPercent;
  }
}
void serialBattery() {
  SerialResponse reply(Serial);
  JsonDocument doc; batteryJson(doc.to<JsonObject>());
  reply.print(F("VIBEDOCK-BATTERY ")); serializeJson(doc, reply); reply.println();
}
void serialReady() {
  SerialResponse reply(Serial);
  JsonDocument doc; doc["identifier"] = identifier; doc["name"] = deviceName; doc["version"] = VIBEDOCK_VERSION; doc["paired"] = !key.isEmpty();
  doc["board"] = VIBEDOCK_BOARD; doc["chip"] = VIBEDOCK_CHIP;
  // Leave HELLO lightweight on other boards, including the ESP8266 TLS callback.
#ifdef VIBEDOCK_BATTERY
  batteryJson(doc["battery"].to<JsonObject>());
#endif
  reply.print(F("VIBEDOCK-READY ")); serializeJson(doc, reply); reply.println();
}
void serialNetworks() {
  SerialResponse reply(Serial);
  scanWifi();
  JsonDocument doc; JsonArray list = doc.to<JsonArray>();
  for (uint8_t i = 0; i < networkCount; i++) {
    JsonObject n = list.add<JsonObject>(); n["ssid"] = networks[i].ssid; n["rssi"] = networks[i].rssi; n["open"] = networks[i].open;
  }
  reply.print(F("VIBEDOCK-NETWORKS ")); serializeJson(doc, reply); reply.println();
}
void serialSetup(const String &body) {
  SerialResponse reply(Serial);
  JsonDocument doc;
  if (deserializeJson(doc, body)) { reply.println(F("VIBEDOCK-SETUP ERROR invalid request")); return; }
  String newSsid = doc["ssid"] | "", newPassword = doc["password"] | "";
  if (newSsid.isEmpty() || newSsid.length() > 32 || newPassword.length() > 64) { reply.println(F("VIBEDOCK-SETUP ERROR invalid Wi-Fi")); return; }
  int status = applyPairing(doc);
  if (status == 400) { reply.println(F("VIBEDOCK-SETUP ERROR no usable server address or invalid key")); return; }
  ssid = newSsid; password = newPassword; networkVerified = false;
  if (status != 200 || !saveConfig()) { reply.println(F("VIBEDOCK-SETUP ERROR storage")); return; }
  reply.println(F("VIBEDOCK-SETUP OK")); flushSerialReply();
  message("Set up, restarting", Step::None);
  diagIntent(Intent::Setup); delay(500); ESP.restart();
}
// duringTls: called from inside a TLS handshake, which takes seconds on the ESP8266; only reading the log and the
// hello answer then, nothing that scans, restarts or changes the configuration.
bool usbDuringTls = false;
void serialPoll(bool duringTls) {
  if (!duringTls && usbDuringTls) {
    usbDuringTls = false; quickBootPending = false; LittleFS.remove("/boots"); logEvent(F("USB console: this start is not counted as a power cycle"));
  }
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      String line = serialLine; serialLine = ""; line.trim();
      // Opening the USB port can reset the chip like a power cycle (reset reason "External System"). A start that
      // talks to the administration over USB is not counted, so reading the log or pairing over the cable never
      // adds up to the Wi-Fi setup or a factory reset.
      if (line.startsWith("VIBEDOCK-")) usbBootConfirmed = true;
      if (line.startsWith("VIBEDOCK-") && quickBootPending && duringTls) usbDuringTls = true;  // logged afterwards: little stack here
      else if (line.startsWith("VIBEDOCK-") && quickBootPending) {
        quickBootPending = false; LittleFS.remove("/boots"); logEvent(F("USB console: this start is not counted as a power cycle"));
      }
      if (duringTls && line != "VIBEDOCK-HELLO" && line != "VIBEDOCK-LOG") continue;
      if (line == "VIBEDOCK-HELLO") {
        serialReady();
#ifdef VIBEDOCK_AUDIO_ES8311
        if (!duringTls && key.isEmpty()) audio::pairingUsb(millis());
#endif
      }
      else if (line == "VIBEDOCK-BATTERY") serialBattery();
      else if (line == "VIBEDOCK-SCAN") serialNetworks();
      else if (line == "VIBEDOCK-LOG") serialLog();
      else if (line.startsWith("VIBEDOCK-SETUP ")) serialSetup(line.substring(strlen("VIBEDOCK-SETUP ")));
    } else if (c != '\r') {
      serialLine += c;
      if (serialLine.length() > 4096) serialLine = "";  // not a line of ours; boot noise is dropped at the next newline
    }
  }
}

// ---- lifecycle ----
// Version and board, readable in the binary: the server takes them from an uploaded own build.
const char IDENTITY[] PROGMEM = "VIBEDOCK-FIRMWARE " VIBEDOCK_VERSION " " VIBEDOCK_BOARD;
#ifdef VIBEDOCK_PANEL_JD9853
// The JD9853 takes the ST7789 commands but needs the vendor's register set after them (from Waveshare's demo for the
// ESP32-S3-Touch-LCD-1.47). Entries: command, number of data bytes, data.
void initJd9853() {
  static const uint8_t sequence[] = {
    0xDF, 2, 0x98, 0x53, 0xB2, 1, 0x23, 0xB7, 4, 0x00, 0x47, 0x00, 0x6F, 0xBB, 6, 0x1C, 0x1A, 0x55, 0x73, 0x63, 0xF0,
    0xC0, 2, 0x44, 0xA4, 0xC1, 1, 0x16, 0xC3, 8, 0x7D, 0x07, 0x14, 0x06, 0xCF, 0x71, 0x72, 0x77,
    0xC4, 12, 0x00, 0x00, 0xA0, 0x79, 0x0B, 0x0A, 0x16, 0x79, 0x0B, 0x0A, 0x16, 0x82,
    0xC8, 32, 0x3F, 0x32, 0x29, 0x29, 0x27, 0x2B, 0x27, 0x28, 0x28, 0x26, 0x25, 0x17, 0x12, 0x0D, 0x04, 0x00,
              0x3F, 0x32, 0x29, 0x29, 0x27, 0x2B, 0x27, 0x28, 0x28, 0x26, 0x25, 0x17, 0x12, 0x0D, 0x04, 0x00,
    0xD0, 5, 0x04, 0x06, 0x6B, 0x0F, 0x00, 0xD7, 2, 0x00, 0x30, 0xE6, 1, 0x14, 0xDE, 1, 0x01,
    0xB7, 5, 0x03, 0x13, 0xEF, 0x35, 0x35, 0xC1, 3, 0x14, 0x15, 0xC0, 0xC2, 2, 0x06, 0x3A, 0xC4, 2, 0x72, 0x12,
    0xBE, 1, 0x00, 0xDE, 1, 0x02, 0xE5, 3, 0x00, 0x02, 0x00, 0xE5, 3, 0x01, 0x02, 0x00, 0xDE, 1, 0x00,
    0x35, 1, 0x00, 0x3A, 1, 0x05, 0xDE, 1, 0x02, 0xE5, 3, 0x00, 0x02, 0x00, 0xDE, 1, 0x00, 0x21, 0};
  panel.writecommand(0x11); delay(120);
  for (size_t i = 0; i < sizeof(sequence); i += 2 + sequence[i + 1]) {
    panel.writecommand(sequence[i]);
    for (uint8_t k = 0; k < sequence[i + 1]; k++) panel.writedata(sequence[i + 2 + k]);
  }
  delay(10); panel.writecommand(0x29);
}
#endif

// Diagnosis of a board that stays dark (build with PLATFORMIO_BUILD_FLAGS=-DVIBEDOCK_BOOT_TRACE): the backlight comes on
// before the panel, the start waits 3 s for a serial monitor, and every step of the start is printed.
#ifdef VIBEDOCK_BOOT_TRACE
#define BOOT_TRACE(step) do { Serial.println("TRACE " step); Serial.flush(); } while (0)
#else
#define BOOT_TRACE(step) do {} while (0)
#endif
void setup() {
  platform::crashBegin();  // the crash of the run before, and from now on the panic handler
  diagBegin();  // first: anything before would overwrite the state of the run before
  Serial.setRxBufferSize(1024); Serial.begin(115200);  // the flasher sends in paced chunks
#if defined(ESP32) && ARDUINO_USB_CDC_ON_BOOT
  Serial.setTxTimeoutMs(0); // an attached Mac must never hold the network startup waiting for USB reads
#endif
#ifdef VIBEDOCK_BOOT_TRACE
  pinMode(TFT_BL, OUTPUT); digitalWrite(TFT_BL, TFT_BACKLIGHT_ON); delay(3000);
#endif
  BOOT_TRACE("serial");
  { SerialResponse reply(Serial); reply.println(); reply.println(reinterpret_cast<const __FlashStringHelper *>(IDENTITY)); }
  platform::onTlsWait([] { serialPoll(true); });  // the log stays readable during a long TLS handshake
#ifdef VIBEDOCK_POWER_PIN
  pinMode(VIBEDOCK_POWER_PIN, OUTPUT); digitalWrite(VIBEDOCK_POWER_PIN, HIGH);  // boards that switch the panel's supply
#endif
#ifndef VIBEDOCK_ROTATION
#define VIBEDOCK_ROTATION 0
#endif
  BOOT_TRACE("panel init");
  panel.init();
  BOOT_TRACE("panel init done");
#if defined(VIBEDOCK_BOOT_TRACE) && defined(ESP32) && ESP_ARDUINO_VERSION_MAJOR >= 3
  // Which driver holds the display pins (GPIO, SPI_MASTER_*, ...).
  for (int pin : {TFT_SCLK, TFT_MOSI, TFT_CS, TFT_DC, TFT_RST, TFT_BL})
    Serial.printf("TRACE pin %d: %s\n", pin, perimanGetTypeName(perimanGetPinBusType(pin)));
#endif
#ifdef VIBEDOCK_PANEL_JD9853
  initJd9853();
#endif
  panel.setRotation(VIBEDOCK_ROTATION); panel.setSwapBytes(true); panel.fillScreen(TFT_BLACK);
  BOOT_TRACE("panel cleared");
#ifdef VIBEDOCK_BOOT_TRACE
  // Red, green, blue for a second each: shows whether the panel receives anything at all.
  for (uint16_t colour : {uint16_t(TFT_RED), uint16_t(TFT_GREEN), uint16_t(TFT_BLUE)}) { panel.fillScreen(colour); delay(1000); }
  BOOT_TRACE("test colours shown");
#endif
#if VIBEDOCK_CANVAS
  canvas.setColorDepth(VIBEDOCK_CANVAS); canvas.createSprite(240, 240); canvas.fillSprite(TFT_BLACK);
  BOOT_TRACE("canvas ready");
#endif
  // Dark until the saved brightness is known: a dimmed display no longer flashes at full brightness on every start.
  pinMode(TFT_BL, OUTPUT); digitalWrite(TFT_BL, TFT_BACKLIGHT_ON == HIGH ? LOW : HIGH);
#if VIBEDOCK_TOUCH
  BOOT_TRACE("touch");
  touch::begin();
#endif
  battery::begin();
  BOOT_TRACE("wifi");
  WiFi.persistent(false); WiFi.mode(WIFI_STA);
  // No modem sleep: with it the access point holds incoming packets until the next beacon, and phone hotspots drop
  // them. Over TLS 1.3 the M1 needs whole 16 kB records through a 2 kB TCP window; with sleep the answers stalled.
#if defined(ESP8266)
  WiFi.setSleepMode(WIFI_NONE_SLEEP);
#else
  WiFi.setSleep(false);
#endif
  message("Starting network", Step::WiFi);
  BOOT_TRACE("first screen drawn");
  // The start with its boot id, kind and the run before first, then the platform's reset text (it may be long).
  logEvent(String(F("Start v" VIBEDOCK_VERSION " " VIBEDOCK_BOARD ": ")) + diagStart() + "; " + platform::resetReason());
  if (!platform::mountStorage() || !loadConfig()) { platform::backlight(TFT_BL, TFT_BACKLIGHT_ON == HIGH, 100); message("Storage error", Step::None, true); return; }
  platform::backlight(TFT_BL, TFT_BACKLIGHT_ON == HIGH, brightness);
#ifdef VIBEDOCK_AUDIO_ES8311
  if (audio::begin()) { audio::takeVolumeInteraction(); logEvent(F("Audio ready (ES8311)")); audio::play(audio::Cue::Boot); }
  else logEvent(F("Audio unavailable: ES8311/I2S initialization failed"));
#endif
  if (turns % 2 && panel.width() != panel.height()) turns = 0;  // a quarter turn does not fit a rectangular panel
  if (turns) applyOrientation(turns);
  loadLog();
  loadAnchor(); loadOtaKey();
  // READY is sent only in response to HELLO; the UI and flash.sh actively query it.

  handleQuickBoots();
  if (ssid.isEmpty() || setupRequested) { startPortal(); return; }
  diagPhase(Phase::WiFi);
  WiFi.setAutoReconnect(true); WiFi.begin(ssid.c_str(), password.c_str()); uint32_t began = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - began < 20000) { footer(); settleQuickBoot(); serialPoll(); delay(250); }
  bool online = WiFi.status() == WL_CONNECTED;
  if (!online) wifiProblem = connectionProblem(WiFi.status());
  logEvent(online ? String(F("Wi-Fi ")) + ssid + ": " + WiFi.localIP().toString() + ", " + String(WiFi.RSSI()) + F(" dBm, heap ") + platform::freeHeap() : String(F("Wi-Fi: ")) + wifiProblem);
  if (!online && !networkVerified) { WiFi.setAutoReconnect(false); WiFi.disconnect(); startPortal(); return; }
  if (online && !networkVerified) { networkVerified = true; saveConfig(); }
  configTime(0, 0, "pool.ntp.org", "time.cloudflare.com");  // UTC; only needed to check https certificates
  shownIp = currentIp(); startPairingServer(); lastReconnect = millis(); diagPhase(Phase::Idle);
  if (key.isEmpty()) enterPairing();
  else {
    mode = Mode::Paired; lastImage = millis(); pullSoon();
    if (online) message("Connecting to server", Step::Server); else showWaiting();
  }
}
// ---- experimental battery power saving ----
// Allowed in the administration (X-VibeDock-Power-Saving) and active only on battery, i.e. without USB host and without
// charging, two readings 5 s apart (client_policy::PowerSource): Wi-Fi modem sleep, the CPU at 80 MHz and the backlight
// dimmed after a minute without touch or button. USB power or charging switches back to the normal mode. Modem sleep
// was switched off for all boards because some access points and phone hotspots then delay or drop packets; on battery
// that risk is taken for the runtime.
// Touch or a button: restarts the dimming time; true when it woke the dimmed backlight (that touch does nothing else).
bool powerWake() {
  lastActivity = millis();
  if (!dimmed) return false;
  dimmed = false; platform::backlight(TFT_BL, TFT_BACKLIGHT_ON == HIGH, brightness);
  return true;
}
void setPowerSaving(bool on) {
  if (on == powerSaving) return;
  powerSaving = on;
#if defined(ESP32)
  static const uint32_t normalMhz = getCpuFrequencyMhz();
  WiFi.setSleep(on);  // modem sleep (minimum) on battery, none as at the start otherwise
  setCpuFrequencyMhz(on ? 80 : normalMhz);
#endif
  if (on) lastActivity = millis(); else powerWake();
  logEvent(on ? F("Power saving on: Wi-Fi modem sleep, 80 MHz, dims after 60 s") : F("Power saving off"), true);
}
void powerPoll() {
#ifdef VIBEDOCK_BATTERY
  if (millis() - lastPowerCheck >= client_policy::powerCheckMs) {
    lastPowerCheck = millis();
    // Only the 1.54 reports charging; on the others a USB host alone means external power. The level comes from
    // the boards that measure the battery voltage (1.54, C6 1.69).
    auto reading = battery::read();
    bool charging = strcmp(reading.chargeSignal, "active") == 0 || strcmp(reading.chargeSignal, "unstable") == 0;
    batteryLevel = reading.supported ? reading.estimatedPercent : -1;
    if (powerSource.update(client_policy::onBattery(battery::usbHost(), charging))) {
      logEvent(powerSource.battery ? F("Power: battery") : F("Power: USB or charging"), true);
      // The battery icon in the image appears or goes away with the next image, right away.
      if (mode == Mode::Paired) pullSoon();
    }
    setPowerSaving(powerSavingAllowed && powerSource.battery && mode == Mode::Paired);
  }
  if (powerSaving && !dimmed && client_policy::dimDue(millis(), lastActivity)) {
    dimmed = true; platform::backlight(TFT_BL, TFT_BACKLIGHT_ON == HIGH, client_policy::dimmedBrightness(brightness));
  }
#endif
}

#if VIBEDOCK_TOUCH
// Swipes: left and right switch the colour scheme on the server (paired displays only), up and down the brightness.
// A double tap starts or stops the slideshow of the design on the server, whatever its schedule says.
uint32_t lastTouchPoll = 0;
void touchPoll() {
  static bool touching = false; static int16_t startX, startY, lastX, lastY; static uint8_t idle = 0;
  static uint32_t touchedAt = 0, lastTap = 0;
  static client_policy::TouchPath path;  // the points of this touch, for the circle gesture
  static bool waking = false;  // this touch woke the dimmed backlight: it does nothing else
  if (millis() - lastTouchPoll < 20) return;
  lastTouchPoll = millis();
  int16_t x, y;
  if (touch::read(x, y)) {
    if (!touching) { touching = true; startX = x; startY = y; touchedAt = millis(); path.clear(); waking = powerWake(); }
    lastX = x; lastY = y; idle = 0; path.add(x, y); return;
  }
  // Released only after 60 ms without a point: the controllers drop single readings during a swipe.
  if (!touching || ++idle < 3) return;
  touching = false; idle = 0;
  if (waking) { waking = false; return; }
  // A circle turns the picture (the server sets the orientation and sends it back with the answer to this gesture);
  // the direction does not depend on how the panel is turned. Before the swipes: a circle may end some way off.
  auto circle = path.circle();
  if (circle != client_policy::Circle::None) {
    if (mode != Mode::Paired) return;
    audio::play(audio::Cue::Swipe);
    pendingGesture = circle == client_policy::Circle::Clockwise ? "turn-clockwise" : "turn-counterclockwise";
    logEvent(String(F("Touch: ")) + pendingGesture, true);
    pullSoon();  // the new orientation right away
    return;
  }
  int dx = lastX - startX, dy = lastY - startY;
  client_policy::turnSwipe(dx, dy, turns);  // the panel's coordinates do not turn with the picture
  auto swipe = client_policy::swipeOf(dx, dy);
  if (swipe != client_policy::Swipe::None) audio::play(audio::Cue::Swipe);
  // The release is noticed 60 ms late (see above): that time is not part of the touch.
  if (swipe == client_policy::Swipe::None && client_policy::isTap(dx, dy, millis() - touchedAt > 60 ? millis() - touchedAt - 60 : 0)) {
    if (!client_policy::doubleTap(lastTap, millis())) { lastTap = millis(); return; }
    lastTap = 0;
    if (mode != Mode::Paired) return;
    pendingGesture = "photos-toggle"; logEvent(F("Touch: double tap, photos on or off"), true);
    pullSoon();  // the new image right away
    return;
  }
  if (swipe == client_policy::Swipe::Up || swipe == client_policy::Swipe::Down) {
    brightness = client_policy::nextBrightness(brightness, swipe == client_policy::Swipe::Up);
    platform::backlight(TFT_BL, TFT_BACKLIGHT_ON == HIGH, brightness);
    saveLater();
  } else if (swipe != client_policy::Swipe::None && mode == Mode::Paired) {
    pendingGesture = swipe == client_policy::Swipe::Left ? "scheme-next" : "scheme-previous";
    logEvent(String(F("Touch: ")) + pendingGesture, true);
    pullSoon();  // the new image right away
  }
}
#endif

// Turns the picture clockwise in quarter turns (Flip image or Rotate image in the administration). The server's
// picture does not depend on the turn: with a copy of it the display draws it again itself and keeps its ETag, so
// only the normal changes follow; without one the next pull draws every row.
bool applyOrientation(uint8_t next) {
  turns = next % 4;
  panel.setRotation((VIBEDOCK_ROTATION + turns) % 4);
  if (redrawFromShadow()) return true;
  forgetRows();
  if (imageOnScreen) { etag = ""; pullSoon(); }  // the image again, every row
  else present(true);
  return false;
}

void loop() {
  serialPoll();
  if (audio::takeVolumeChange()) { saveLater(); powerWake(); }
  powerPoll();
  volumeFeedback();
  savePending();
#ifdef VIBEDOCK_AUDIO_ES8311
  audio::connection(mode == Mode::Paired && WiFi.status() == WL_CONNECTED &&
      (lastStatus == 200 || lastStatus == 304) && !staleScreen, millis());
#endif
#if VIBEDOCK_TOUCH
  touchPoll();
#endif
  if (logDirty && millis() - logSavedAt >= 60000) saveLog();
  static bool clockLogged = false;
  if (!clockLogged && clockSet()) { clockLogged = true; logEvent(F("Clock set (NTP)")); }
  settleQuickBoot();
  if (millis() - lastFooter >= 250) footer();
  if (mode == Mode::Portal) {
    // No connection attempts while the setup is open: they would switch the radio channel and drop the phone.
    setupDns.processNextRequest(); web.handleClient();
    yield(); return;
  }
  if (mode == Mode::Starting) { yield(); return; }
  web.handleClient();
  String ip = currentIp();
  if (ip != shownIp) {
    shownIp = ip; logEvent("IP " + ip);
    if (mode == Mode::Pairing) showPairing();
    else {
      haveImage = false; staleScreen = true; etag = "";
      if (WiFi.status() == WL_CONNECTED) {
        // Network back (or new address): fetch the image right away instead of waiting a whole poll interval.
        message("Connecting to server", Step::Server); pullSoon();
      } else showWaiting();
    }
  }
  // Wi-Fi lost: counted for the diagnosis (the log has the IP change).
  static bool wasOnline = false; bool online = WiFi.status() == WL_CONNECTED;
  if (wasOnline && !online && diag.drops < 255) { diag.drops++; diagWrite(); }
  wasOnline = online;
  if (!online && millis() - lastReconnect >= 15000) { lastReconnect = millis(); WiFi.reconnect(); }
  if (WiFi.status() == WL_CONNECTED && !networkVerified) { networkVerified = true; saveConfig(); }
  if (mode == Mode::Paired) {
    if (client_policy::waited(millis(), lastPull, pullIntervalMs())) pull();
    if (mode == Mode::Paired && haveImage && !staleScreen && client_policy::imageStale(millis(), lastImage, pollSeconds, pullWaitMs)) {
      staleScreen = true;
      if (WiFi.status() == WL_CONNECTED) message("Updates missing", Step::Server, true); else showWaiting();
    }
  }
  yield();
}
