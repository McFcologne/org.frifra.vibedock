// VibeDock standalone client, protocol v2: the server invites the device once (POST /pair with the code shown on the
// screen), afterwards the device pulls its RGB565 image. See vibedock-server/CLIENT-PROTOCOL.md.
#include <Arduino.h>
#include "platform.h"
#include <time.h>
#include <memory>
#include <vector>
#include <DNSServer.h>
#include "icons.h"
#include <ArduinoJson.h>
#include <TFT_eSPI.h>
#include "client_policy.h"
#include "touch.h"
#if defined(VIBEDOCK_BOOT_TRACE) && defined(ESP32) && ESP_ARDUINO_VERSION_MAJOR >= 3
#include "esp32-hal-periman.h"  // the pin owners in the start diagnosis
#endif
#include <qrcode.h>

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
bool networkVerified = false;
String pin;  // SHA-1 fingerprint of the https server certificate, learned over a fully verified connection
String servers[2]; uint8_t serverCount = 0, serverIndex = 0, storedIndex = 0;
uint32_t pollSeconds = client_policy::defaultPollSeconds;
uint8_t brightness = 100;  // backlight in percent; touch boards change it with swipes up and down
String pendingGesture;  // a swipe for the server (X-VibeDock-Gesture), sent with the next image pull
bool upsideDown = false;  // mounted upside down: the picture is turned by 180 degrees (set in the administration)
void applyOrientation(bool flipped);
client_policy::ToneSettings toneSettings;  // colour correction of the panel, set on the status page
client_policy::Tone panelTone;

enum class Mode { Starting, Portal, Pairing, Paired };
Mode mode = Mode::Starting;
String pairingCode, shownIp;
uint8_t wrongCodes = 0;
bool haveImage = false, staleScreen = true;
uint32_t lastPull = 0, lastImage = 0, lastFooter = 0, lastReconnect = 0;
int lastStatus = 0;  // HTTP status of the last pull, negative for connection errors, 0 before the first pull

String currentIp() {
  if (mode == Mode::Portal) return WiFi.softAPIP().toString();
  return WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : "-- (no Wi-Fi)";
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
void saveLog() {
  if (!logStored || logParked) return;  // parked: the file is complete, the lines in memory are not
  logDirty = false; logSavedAt = millis();
  auto out = LittleFS.open("/log.tmp", "w"); if (!out) return;
  for (uint8_t i = 0; i < logCount; i++) { out.print(logLines[(logNext + LOG_LINES - logCount + i) % LOG_LINES]); out.print('\n'); }
  out.close(); LittleFS.rename("/log.tmp", "/log.txt");
}
// During a TLS 1.3 handshake the ESP8266 needs every kilobyte: the lines are then only in /log.txt and their heap is
// free. Reading the log meanwhile reads the file; a new event brings the lines back first.
void parkLog() {
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
void serialLog() {
  if (logDirty) saveLog();
  // Written as JSON line by line, without a JsonDocument: the log is also read during a TLS handshake, when the
  // heap is nearly used up.
  Serial.print("VIBEDOCK-LOG [");
  bool first = true;
  auto line = [&first](const String &text) {
    if (!first) Serial.print(',');
    first = false; Serial.print('"');
    for (char c : text) {
      if (c == '"' || c == '\\') { Serial.print('\\'); Serial.print(c); }
      else if (uint8_t(c) < 32) Serial.print(' ');
      else Serial.print(c);
    }
    Serial.print('"');
  };
  if (logParked) {
    auto in = LittleFS.open("/log.txt", "r");
    while (in && in.available()) { String text = in.readStringUntil('\n'); if (!text.isEmpty()) line(text); }
    if (in) in.close();
  } else {
    for (uint8_t i = 0; i < logCount; i++) line(logLines[(logNext + LOG_LINES - logCount + i) % LOG_LINES]);
  }
  Serial.println(']');
}
String footerText;
uint16_t footerBackground = TFT_BLACK, footerDrawn = TFT_BLACK;
// Colours of the device's own screens (RGB565): muted text #8a97a8, track #2a3546, done #34c759, active #7fd6ff,
// problem #ff9f0a with the pill #2a1a05 / #6b4310 / #ffcf8a, as on the device web pages.
constexpr uint16_t MUTED = 0x8CB5, TRACK = 0x29A8, DONE = 0x362B, ACTIVE = 0x7EBF, PROBLEM = 0xFCE1,
                   PILL = 0x28C0, PILL_EDGE = 0x6A02, PILL_TEXT = 0xFE71;
uint32_t drawnRows[TFT_WIDTH > TFT_HEIGHT ? TFT_WIDTH : TFT_HEIGHT];  // hash of each drawn image row; only changed rows are pushed to the display
bool imageOnScreen = false;  // a server image fills the screen, IP included; the device draws no footer over it
void forgetRows() { for (auto &h : drawnRows) h = 0; }
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
    panel.pushImage(left, top + y, side, 1, line);
  }
#else
  (void)force;
#endif
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
bool saveConfig() {
  JsonDocument doc;
  doc["identifier"] = identifier; doc["name"] = deviceName;
  doc["ssid"] = ssid; doc["password"] = password; doc["key"] = key; doc["poll"] = pollSeconds; doc["pin"] = pin;
  doc["verified"] = networkVerified; doc["brightness"] = brightness; doc["flipped"] = upsideDown;
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
      identifier = doc["identifier"].as<String>(); deviceName = doc["name"] | "";
      ssid = doc["ssid"].as<String>(); password = doc["password"].as<String>();
      key = doc["key"] | ""; pin = doc["pin"] | "";
      // Configurations from before this flag worked with their network already.
      networkVerified = doc["verified"].isNull() ? !ssid.isEmpty() : doc["verified"].as<bool>(); pollSeconds = client_policy::clampPoll(doc["poll"] | client_policy::defaultPollSeconds);
      serverCount = 0;
      for (JsonVariant url : doc["servers"].as<JsonArray>()) if (serverCount < 2) servers[serverCount++] = url.as<String>();
      serverIndex = storedIndex = serverCount ? std::min<uint8_t>(doc["server"] | 0, serverCount - 1) : 0;
      brightness = client_policy::clampBrightness(doc["brightness"] | 100);
      upsideDown = doc["flipped"] | false;
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
  if (identifier.isEmpty() || identifier == "null") { identifier = "vibedock-" + WiFi.macAddress(); identifier.replace(":", ""); identifier.toLowerCase(); }
  if (deviceName.isEmpty()) deviceName = "VibeDock " + identifier.substring(identifier.length() - 6);
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
  web.sendContent("<title>VibeDock - " + subtitle + "</title><link rel=\"icon\" href=\"/favicon.ico\" sizes=\"32x32\">"
      "<link rel=\"icon\" href=\"/favicon.svg\" type=\"image/svg+xml\"><link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">"
      "</head><body><main><header>");
  web.sendContent_P(PAGE_LOGO);
  web.sendContent("<div><h1>VibeDock</h1><p>" + subtitle + "</p></div></header>");
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
  web.sendContent(F("</main></body></html>"));
  platform::chunkedEnd(web);
}

// ---- factory reset by power-cycling ----
// Power-on and external resets count; software restarts and crashes clear the counter.
bool quickBootPending = false;
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
  if (updated) logEvent("New firmware: this start is not counted as a power cycle");
  if (updated || !platform::powerCycleStart()) { LittleFS.remove("/boots"); return 0; }
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
  uint8_t boots = countQuickBoot();
  if (client_policy::factoryResetDue(boots)) {
    // Factory reset: our whole configuration (Wi-Fi, pairing key, servers) and the Wi-Fi credentials the ESP8266
    // SDK keeps in its own flash sectors. The identifier is derived from the MAC again; the setup password stays.
    message("Factory reset", Step::None, true); logEvent("Factory reset after " + String(boots) + " quick power cycles");
    LittleFS.remove("/boots"); LittleFS.remove("/standalone.json"); LittleFS.remove("/standalone.tmp"); LittleFS.remove("/anchor.der");
    platform::eraseWifi();
    delay(1500); ESP.restart();
  } else if (boots == client_policy::resetPowerCycles - 1) {
    // Second quick start: unplugging once more resets everything, waiting opens the Wi-Fi setup (pairing stays).
    message("Unplug now for factory reset", Step::None, true); logEvent("Second quick power cycle: Wi-Fi setup unless unplugged");
    showDetail("or wait: Wi-Fi setup");
    while (quickBootPending) { settleQuickBoot(); delay(50); }
    setupRequested = true;
  }
}

// ---- Wi-Fi setup portal (access point) ----
struct WifiNetwork { String ssid; int32_t rssi; bool open; };
WifiNetwork networks[12]; uint8_t networkCount = 0;
// Scans once, keeps the strongest entry per name and sorts by signal.
void scanWifi() {
  networkCount = 0;
  int found = WiFi.scanNetworks(false, false);
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
  showDetail("Waiting for " + ssid);
  display.setTextColor(MUTED, TFT_BLACK);
  display.drawCentreString("Trying again every 15 s", 120, 164, 1);
  present();
}
// ESP8266 limits: 2.4 GHz only, WPA2 (or WPA2/WPA3 mixed), no WPA3-only, no required PMF, no WPA Enterprise.
String connectionProblem(wl_status_t status) {
  String name = "\"" + ssid + "\"";
  if (platform::wrongPassword(status)) return "Wrong password for " + name + ".";
  switch (status) {
    case WL_NO_SSID_AVAIL: return "Network " + name + " was not found. VibeDock only uses 2.4 GHz: the network must offer 2.4 GHz and must not be hidden.";
    case WL_CONNECT_FAILED: return "Connecting to " + name + " failed. VibeDock needs WPA2 (WPA2/WPA3 mixed works, WPA3 only does not) and no required PMF (802.11w).";
    default: return "No connection to " + name + " within 20 seconds. Check 2.4 GHz, WPA2 and the signal.";
  }
}
void portalPage() {
  if (web.hasArg("rescan")) scanWifi();
  pageStart(F("Set up Wi-Fi"));
  if (!wifiProblem.isEmpty()) web.sendContent("<p class=\"problem\">" + html(wifiProblem) + "</p>");
  web.sendContent(F("<form action=\"/save\" method=\"post\"><section class=\"card\"><h2>Networks nearby</h2>"));
  if (networkCount == 0) web.sendContent(F("<p class=\"muted\">No networks found.</p>"));
  for (uint8_t i = 0; i < networkCount; i++) {
    const WifiNetwork &n = networks[i];
    String name = html(n.ssid);
    int level = n.rssi > -60 ? 3 : n.rssi > -72 ? 2 : 1;
    web.sendContent("<label class=\"net\"><input type=\"radio\" name=\"net\" value=\"" + name
        + "\" onchange=\"this.form.ssid.value=this.value;this.form.password.focus()\"><span>" + name + "</span>"
        + (n.open ? "" : "<span class=\"lock\" title=\"Secured\">&#128274;</span>")
        + "<b class=\"sig l" + String(level) + "\" title=\"" + String(n.rssi) + " dBm\"><i></i><i></i><i></i></b></label>");
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
  String payload = "WIFI:T:WPA;S:" + name + ";P:" + secret + ";;";
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
    display.drawCentreString(shortProblem(WiFi.status()) + " - see page", 120, 5, 2);
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
  mode = Mode::Portal; logEvent("Wi-Fi setup opened" + (wifiProblem.isEmpty() ? String() : ": " + wifiProblem));
  WiFi.mode(WIFI_AP_STA);
  message("Searching Wi-Fi", Step::WiFi);
  scanWifi();
  // A fresh random WPA2 password every time the setup opens; it only appears inside the QR code on the screen.
  String apName = "VibeDock-" + identifier.substring(identifier.length() - 6);
  apPassword = randomHex(6);
  if (apPassword.length() != 12) { message("Random source error", Step::None, true); return; }  // never open an unprotected network
  WiFi.softAP(apName.c_str(), apPassword.c_str());
  setupDns.start(53, "*", WiFi.softAPIP());
  showSetupCode(apName, apPassword);
  web.on("/", HTTP_GET, portalPage);
  serveIcons();
  web.on("/save", HTTP_POST, [] {
    if (web.arg("ssid").isEmpty() || web.arg("ssid").length() > 32 || web.arg("password").length() > 64) {
      web.sendHeader("Location", "/", true); web.send(303, "text/plain", ""); return;
    }
    ssid = web.arg("ssid"); password = web.arg("password"); networkVerified = false;
    if (!saveConfig()) { web.send(500, "text/plain", "Saving failed"); return; }
    pageStart(F("Saved"));
    web.sendContent("<section class=\"card\"><p class=\"state ok\">Wi-Fi saved</p><p>VibeDock restarts and connects to <b>" + html(ssid)
        + "</b>. Reconnect this phone or computer to your normal network; the display then shows its IP address and a pairing code.</p></section>");
    pageEnd(); delay(500); ESP.restart();
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
    else if (value.startsWith("https://") && !tls) logEvent("Pairing: " + value + " ignored, no root certificate sent");
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
  logEvent("Paired with " + names + (anyTls ? "; https root certificate" : "") + (pin.isEmpty() ? "" : ", pin"));
  return 200;
}
void handlePair() {
  if (mode != Mode::Pairing) { web.send(409, "application/json", "{\"error\":\"already paired\"}"); return; }
  if (client_policy::pairingLocked(wrongCodes)) { web.send(429, "application/json", "{\"error\":\"restart the device\"}"); return; }
  String body = web.arg("plain");
  JsonDocument doc;
  if (body.length() > 4096 || deserializeJson(doc, body)) { web.send(400, "application/json", "{\"error\":\"invalid request\"}"); return; }
  if (!sameCode(doc["code"] | "")) { wrongCodes++; web.send(403, "application/json", "{\"error\":\"wrong code\"}"); return; }
  int status = applyPairing(doc);
  if (status == 400) { web.send(400, "application/json", "{\"error\":\"no usable server address or invalid key\"}"); return; }
  if (status != 200) { web.send(500, "application/json", "{\"error\":\"storage\"}"); return; }
  JsonDocument reply; reply["identifier"] = identifier; reply["name"] = deviceName; reply["board"] = VIBEDOCK_BOARD;
  reply["subnetMask"] = WiFi.subnetMask().toString();  // fixed by the server at pairing; pulls only fill it in once if it is missing
  String out; serializeJson(reply, out);
  web.send(200, "application/json", out);
  mode = Mode::Paired; lastPull = millis() - client_policy::clampPoll(pollSeconds) * 1000u; lastImage = millis();
  message("Loading image", Step::Image);
}
// ---- image pull (device -> server) ----
void unpair() {
  key = ""; pin = ""; serverCount = 0; etag = ""; lastStatus = 0; forgetAnchor(); platform::clearOtaKey(); LittleFS.remove("/ota.pem"); saveConfig(); enterPairing();
}
// Reads the rows of an image and hands each one to onRow; onRow returns false to abort.
String imageProblem;  // why the last image was not read completely, for the device log
// Reads the image row by row. Fails after `timeout` ms without new bytes (not in all: over TLS 1.3 on the M1 the
// image arrives in records of several kB, each decrypted at once) or when the connection ends early.
template <typename Row> bool readRows(WiFiClient *stream, const client_policy::ImageSize &size, Row onRow, uint32_t timeout = 8000) {
  static uint8_t bytes[2 * client_policy::maxImageSide]; const size_t length = 2u * size.width;
  uint32_t began = millis(), progress = began;
  imageProblem = "";
  for (int y = 0; y < size.height; y++) {
    size_t received = 0;
    while (received < length) {
      bool idle = millis() - progress > timeout, closed = !stream->connected() && !stream->available();
      if (idle || closed) {
        imageProblem = String(idle ? "no data for " + String(timeout / 1000) + " s" : "connection closed") + " at row " + String(y) + "/"
            + String(size.height) + " after " + String(millis() - began) + " ms";
        return false;
      }
      int count = stream->read(bytes + received, length - received);
      if (count > 0) { received += count; progress = millis(); }
      if (millis() - lastFooter >= 250) footer();
      yield();
    }
    if (!onRow(y, bytes)) return false;
  }
  return true;
}
// Draws the image straight to the display, scaled to the whole screen (1:1 when the server renders for this
// display); the server draws the IP into it.
bool readImage(WiFiClient *stream, const client_policy::ImageSize &size, uint32_t timeout) {
  static uint16_t pixels[TFT_WIDTH > TFT_HEIGHT ? TFT_WIDTH : TFT_HEIGHT];
  const uint16_t width = panel.width(), height = panel.height();
  imageOnScreen = true;
  return readRows(stream, size, [&](int y, uint8_t *bytes) {
    // Unchanged rows are not redrawn, so a new image only touches the lines that differ.
    uint32_t hash = client_policy::rowHash(bytes, 2u * size.width);
    uint16_t from = client_policy::firstTarget(y, size.height, height), to = client_policy::firstTarget(y + 1, size.height, height);
    if (from >= to || hash == drawnRows[from]) return true;
    for (int x = 0; x < width; x++) {
      int s = client_policy::sourceOf(x, width, size.width);
      pixels[x] = panelTone.apply(client_policy::pixel(bytes[2 * s], bytes[2 * s + 1]));
    }
    for (int row = from; row < to && row < height; row++) { panel.pushImage(0, row, width, 1, pixels); drawnRows[row] = hash; }
    return true;
  }, timeout);
}
// One image request. https uses the simple check first (pinned certificate fingerprint); when that fails, e.g. after
// the certificate was renewed, a full check against the root from pairing follows, and only such a verified
// connection may hand over the new fingerprint.
struct ImageRequest {
  HTTPClient http;
  std::unique_ptr<WiFiClient> client;
  bool verified = false;
  ~ImageRequest() { http.end(); }
};
// While the log is sent (uploadLog): the file streamed as the request body and the server's request id.
File *uploadFile = nullptr; String uploadRequest;
int sendImageRequest(ImageRequest &r, const String &url, bool conditional) {
  if (!r.http.begin(*r.client, url)) return HTTPC_ERROR_CONNECTION_FAILED;
  const char *wanted[] = {"ETag", "X-VibeDock-Image-Format", "X-VibeDock-Poll-Seconds", "X-VibeDock-Tls-Pin", "X-VibeDock-Redraw", "X-VibeDock-Update", "x-MD5", "X-VibeDock-Length", "X-VibeDock-Send-Log", "X-VibeDock-Orientation"};
  r.http.collectHeaders(wanted, 10);
  // HTTP/1.0: a reverse proxy (Synology) then cannot answer chunked; the body ends with the connection or its length.
  r.http.useHTTP10(true);
  r.http.setTimeout(r.verified ? 15000 : 5000);
  r.http.addHeader("Accept-Encoding", "identity");  // never compressed: the image is read byte for byte  // over TLS 1.3 a 16 kB record has to arrive whole before any byte reads r.http.setReuse(false);
  r.http.addHeader("Authorization", "Bearer " + key);
  r.http.addHeader("X-VibeDock-Device-IP", WiFi.localIP().toString());  // the server may only see a NAT gateway
  r.http.addHeader("X-VibeDock-Subnet-Mask", WiFi.subnetMask().toString());  // USB and legacy registrations learn their mask once
  r.http.addHeader("X-VibeDock-Footer", "server");  // this firmware shows the IP the server draws into the image
  r.http.addHeader("X-VibeDock-Firmware", VIBEDOCK_VERSION);  // lets the administration offer updates
  r.http.addHeader("X-VibeDock-Display", String(panel.width()) + "x" + String(panel.height()));  // suggests the display type
  r.http.addHeader("X-VibeDock-Board", VIBEDOCK_BOARD);  // which firmware build an update needs
  // Updates over Wi-Fi: "1" with the server's signing key, "no-key" when paired before the server sent one.
  r.http.addHeader("X-VibeDock-Ota", platform::hasOtaKey() ? "1" : "no-key");
  if (!updateError.isEmpty()) r.http.addHeader("X-VibeDock-Update-Error", updateError);
  if (r.verified) r.http.addHeader("X-VibeDock-Tls-Check", "full");
  if (conditional && !etag.isEmpty() && haveImage) r.http.addHeader("If-None-Match", etag);
  if (conditional && !pendingGesture.isEmpty()) r.http.addHeader("X-VibeDock-Gesture", pendingGesture);
  if (uploadFile) {  // the log, straight from flash: no copy on the heap the TLS handshake needs
    r.http.addHeader("Content-Type", "text/plain; charset=utf-8");
    r.http.addHeader("X-VibeDock-Log-Request", uploadRequest);
    uploadFile->seek(0);  // again from the start when a second TLS attempt follows
    return r.http.sendRequest("POST", uploadFile, uploadFile->size());
  }
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
  if (!WiFi.hostByName(host.c_str(), address)) return "name " + host + " not resolved (DNS)";
  String target = address.toString() + ":" + port;
  WiFiClient probe; probe.setTimeout(4000);
  if (!probe.connect(address, port.toInt())) return "no TCP connection to " + target;
  probe.stop();
  // TCP works, so TLS or the HTTP exchange failed.
  return url.startsWith("https") ? "TCP to " + target + " works, TLS or HTTP failed" : "TCP to " + target + " works, no HTTP answer";
}
int requestImage(ImageRequest &r, bool conditional, const char *resource = "image") {
  String url = servers[serverIndex] + "/api/v1/clients/" + identifier + "/" + resource;
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
      lastConnectionProblem = HTTPClient::errorToString(code) + " (" + String(code) + ") after TLS; " + platform::tlsTraffic() + "; " + platform::tlsSummary();
      return code;
    }
    logEvent("Pinned certificate check failed: " + HTTPClient::errorToString(code) + " " + platform::tlsError(r.client.get()), true);
    r.http.end(); r.client.reset();
  }
  // Full check: chain up to the root from pairing, host name and validity; never an unchecked connection.
  if (!platform::hasAnchor()) { lastConnectionProblem = "no root certificate for https from pairing"; return HTTPC_ERROR_CONNECTION_FAILED; }
  if (!clockSet()) { lastConnectionProblem = "clock not set yet (NTP), needed for https"; return HTTPC_ERROR_CONNECTION_FAILED; }
  r.client.reset(platform::verifiedClient()); r.verified = true;
  parkLog();  // the log's heap for the handshake; the next event brings it back
  int code = sendImageRequest(r, url, conditional);
  if (code < 0) lastConnectionProblem = platform::tlsError(r.client.get());
  // Handshake done, then the HTTP exchange failed: say so instead of guessing at TLS below.
  if (code < 0 && lastConnectionProblem.isEmpty() && !platform::tlsSummary().isEmpty())
    lastConnectionProblem = HTTPClient::errorToString(code) + " (" + String(code) + ") after TLS; " + platform::tlsTraffic() + "; " + platform::tlsSummary();
  // A server that only speaks TLS 1.2: the same full check over TLS 1.2, where the board supports it.
  WiFiClient *legacy;
  if (code < 0 && platform::versionRefused() && (legacy = platform::legacyClient())) {
    logEvent("Server refuses TLS 1.3, using TLS 1.2", true);
    r.http.end(); r.client.reset(legacy);
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
  String next = r.http.hasHeader("X-VibeDock-Tls-Pin") ? r.http.header("X-VibeDock-Tls-Pin") : platform::verifiedFingerprint();
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
  message("Updating to v" + version, Step::None); updateProgress(0, 1); logEvent("Update to v" + version + " started");
  auto fail = [&](const String &reason) {
    updateError = version + ": " + reason; failedUpdate = version; logEvent("Update failed: " + reason);
    message("Update failed", Step::None, true); showDetail(reason.substring(0, 34), PROBLEM); delay(4000);
    haveImage = false; staleScreen = true; etag = ""; forgetRows(); lastPull = millis() - client_policy::clampPoll(pollSeconds) * 1000u;
  };
  ImageRequest request; HTTPClient &http = request.http;
  int code = requestImage(request, false, "firmware");
  int size = http.getSize();
  if (size <= 0) size = http.header("X-VibeDock-Length").toInt();  // behind a reverse proxy that drops Content-Length
  if (code != 200 || size <= 0) { fail(code > 0 ? "download HTTP " + String(code) : String("download failed")); return; }
  String error;
  bool installed = platform::installSigned(http.getStreamPtr(), size_t(size), http.header("x-MD5"), error, updateProgress);
  http.end();
  if (!installed) { fail(error); return; }
  updateProgress(1, 1); message("Update installed, restarting", Step::None); logEvent("Update to v" + version + " installed");
  delay(800); ESP.restart();
}
// Sends /log.txt to the server that asked for it (X-VibeDock-Send-Log), over the same address and TLS as the images.
void uploadLog(const String &requestId) {
  saveLog();  // the file holds every line; while parked it is complete already
  File file = LittleFS.open("/log.txt", "r");
  if (!file) return;
  uploadFile = &file; uploadRequest = requestId;
  int code;
  { ImageRequest request; code = requestImage(request, false, "log"); }
  uploadFile = nullptr; uploadRequest = ""; file.close();
  logEvent(code == 204 ? String("Log sent to the server") : "Log not sent: " + (code > 0 ? "HTTP " + String(code) : HTTPClient::errorToString(code)), true);
}
// The start of an error answer, e.g. the server's reason for a 400, as one printable line.
String answerStart(HTTPClient &http) {
  WiFiClient *stream = http.getStreamPtr(); if (!stream) return String();
  char text[61]; stream->setTimeout(1000); size_t length = stream->readBytes(text, sizeof(text) - 1);
  for (size_t i = 0; i < length; i++) if (text[i] < 32 || text[i] > 126) text[i] = ' ';
  text[length] = 0; String answer(text); answer.trim(); return answer;
}
void pull() {
  lastPull = millis();
  if (WiFi.status() != WL_CONNECTED) return;
  if (reasonOnScreen && (!haveImage || staleScreen)) {  // the reason stays, the address line says what runs now
    display.fillRect(0, 210, 240, 10, TFT_BLACK);
    showWrapped("trying " + servers[serverIndex], 211, 1, MUTED, 1); present();
  }
  // On the heap: the TLS handshake below needs most of the ESP8266's 4 kB stack.
  std::unique_ptr<ImageRequest> holder(new ImageRequest()); ImageRequest &request = *holder; HTTPClient &http = request.http;
  int code = requestImage(request, true);
  if (code != lastStatus || code < 0) {
    String server = servers[serverIndex];
    String answer = code >= 400 && code != 401 ? answerStart(http) : String();
    if (code > 0) logEvent(server + ": HTTP " + String(code) + (answer.isEmpty() ? "" : " \"" + answer + "\"")
        + (server.startsWith("https") ? ", " + platform::tlsSummary() : String()), true);
    else logEvent(server + ": " + HTTPClient::errorToString(code) + (lastConnectionProblem.isEmpty() ? "" : ", " + lastConnectionProblem), true);
  }
  lastStatus = code;
  if (code > 0) {
    failedInRow = 0; pendingGesture = "";  // the server has it (an older one ignores it)
    if (storedIndex != serverIndex) saveConfig();  // flash only when the answering address changes
  }
  if (code == 200 || code == 304) { learnPin(request); updateError = ""; }
  String offered = code == 200 || code == 304 ? http.header("X-VibeDock-Update") : "";
  String logRequest = code == 200 || code == 304 ? http.header("X-VibeDock-Send-Log") : "";
  if (code > 0 && http.hasHeader("X-VibeDock-Poll-Seconds")) {
    uint32_t poll = client_policy::clampPoll(http.header("X-VibeDock-Poll-Seconds").toInt());
    if (poll != pollSeconds) { pollSeconds = poll; saveConfig(); }
  }
  if ((code == 200 || code == 304) && http.hasHeader("X-VibeDock-Orientation")) {
    bool flipped = client_policy::flippedOf(http.header("X-VibeDock-Orientation").c_str(), upsideDown);
    if (flipped != upsideDown) { applyOrientation(flipped); saveConfig(); logEvent(flipped ? "Picture flipped" : "Picture upright", true); }
  }
  client_policy::ImageSize size;
  if (code == 200 && !validImage(http, size))
    logEvent("Image not accepted: format \"" + http.header("X-VibeDock-Image-Format") + "\", " + String(http.getSize()) + " bytes announced", true);
  if (code == 200 && validImage(http, size)) {
    // Force refresh from the administration (or a new image size): draw every row, not only the ones that changed.
    if (http.header("X-VibeDock-Redraw") == "1" || size.width != lastImageSize.width || size.height != lastImageSize.height) forgetRows();
    lastImageSize = size;
    uint32_t reading = millis(); bool first = !haveImage; bool https = servers[serverIndex].startsWith("https");
    // Over https 15 s without data before giving up: on lossy links (phone hotspots) TCP resends after growing pauses.
    if (readImage(http.getStreamPtr(), size, https ? 15000 : 8000)) {
      etag = http.header("ETag"); lastImage = millis(); haveImage = true; staleScreen = false; footer(); imageFailures = 0;
      if (first) logEvent("Image shown, read in " + String(millis() - reading) + " ms", true);
    } else {
      etag = "";  // the next pull fetches the whole image; rows already drawn stay and are not drawn again
      logEvent("Image aborted: " + imageProblem + (https ? ", " + platform::tlsTraffic() : String()), true);
      // Once right away and without the error screen when an image is showing: the screen keeps the image, partly
      // new. The error screen only when that fails too.
      if (++imageFailures == 1 && haveImage) lastPull = millis() - client_policy::clampPoll(pollSeconds) * 1000u + 1000u;
      else {
        haveImage = false; staleScreen = true; message("Image aborted", Step::Image, true);
        showWrapped(imageProblem, 145, 4, PROBLEM); present();
      }
    }
  } else if (code == 304) {
    lastImage = millis(); staleScreen = false;
  } else if (code == 401) {
    logEvent("Server rejected the device key (401): pairing removed");
    http.end(); unpair(); return;
  } else if (code < 0) {
    // Connection failed: try the other announced address next time. An answer that stalled after a successful TLS
    // handshake (lost packets on the way) is first retried at the same address.
    bool stalled = lastConnectionProblem.indexOf(" after TLS;") >= 0;
    uint8_t next = stalled && failedInRow == 0 ? serverIndex : client_policy::nextServer(serverIndex, serverCount);
    if (next != serverIndex) logEvent("Trying " + servers[next] + " next", true);
    else if (stalled) logEvent("Retrying " + servers[next], true);
    String failed = servers[serverIndex];
    serverIndex = next;
    // The next attempt right away (as many as there are addresses), not only after the poll interval.
    if (++failedInRow <= serverCount) lastPull = millis() - client_policy::clampPoll(pollSeconds) * 1000u + 1000u;
    else failedInRow = 0;
    if (!haveImage || staleScreen) {
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
  if (client_policy::validLogRequest(logRequest.c_str())) uploadLog(logRequest);
  if (!offered.isEmpty() && offered != VIBEDOCK_VERSION && offered != failedUpdate && platform::hasOtaKey()) installUpdate(offered);
}

// ---- status page on port 80 (local network) ----
String statusLine(String &css) {
  if (mode == Mode::Pairing) { css = "warn"; return "Waiting for invitation"; }
  if (lastStatus == 0) { css = "warn"; return "Connecting to server"; }
  if (lastStatus < 0) { css = "bad"; return "Server unreachable"; }
  if (lastStatus != 200 && lastStatus != 304) { css = "bad"; return "Server answered HTTP " + String(lastStatus); }
  if (staleScreen) { css = "warn"; return "Updates missing"; }
  css = "ok"; return "Connected";
}
void colourCard();
void statusPage() {
  bool paired = mode == Mode::Paired;
  pageStart(html(deviceName));
  String css, state = statusLine(css);
  String body = "<section class=\"card\"><h2>Status</h2><p class=\"state " + css + "\">" + state + "</p><dl>"
      "<dt>IP address</dt><dd>" + currentIp() + "</dd><dt>Wi-Fi</dt><dd>" + html(WiFi.SSID()) + " (" + String(WiFi.RSSI()) + " dBm)</dd>";
  if (paired) {
    body += "<dt>Server</dt><dd>" + html(servers[serverIndex]) + "</dd><dt>Last update</dt><dd>"
        + (haveImage ? String((millis() - lastImage) / 1000) + " s ago" : String("-")) + "</dd><dt>Poll interval</dt><dd>"
        + String(pollSeconds) + " s</dd>";
  }
  body += "<dt>Device ID</dt><dd>" + identifier + "</dd><dt>Last start</dt><dd>" + html(platform::resetReason()) + "</dd><dt>Firmware</dt><dd>" VIBEDOCK_VERSION "</dd></dl>";
  if (!paired) body += F("<p class=\"muted\">Enter this IP address and the pairing code shown on the display in the VibeDock administration.</p>");
  body += "</section>";
  web.sendContent(body);
  if (paired) colourCard();
  pageEnd();
}
// Colour correction of this panel. Every change is applied when a slider is released and the image is redrawn,
// so the display can be compared with the server preview right away; the BMP above stays uncorrected.
String slider(const char *name, const char *label, int value, int low, int high, int step, bool gamma) {
  String shown = gamma ? String(value / 100.0, 2) : String(value) + " %";
  String show = gamma ? "(this.value/100).toFixed(2)" : "this.value+' %'";
  return "<label class=\"f\" for=\"" + String(name) + "\">" + label + " <b>" + shown + "</b></label><input type=\"range\" id=\""
      + name + "\" name=\"" + name + "\" min=\"" + low + "\" max=\"" + high + "\" step=\"" + step + "\" value=\"" + value
      + "\" oninput=\"this.previousSibling.lastChild.textContent=" + show + "\" onchange=\"const b=new URLSearchParams(new FormData(this.form));b.set('live','1');fetch('/colours',{method:'POST',body:b}).catch(()=>this.form.submit())\">";
}
// The image as the server sends it, uncorrected, next to the sliders: the reference the display is matched to. A
// slider change goes to the display in the background; the page and the image stay as they are.
void colourCard() {
  using namespace client_policy;
  uint16_t w = lastImageSize.width ? lastImageSize.width : 240, h = lastImageSize.height ? lastImageSize.height : 240;
  web.sendContent("<form action=\"/colours\" method=\"post\"><section class=\"card\"><h2>Display colours</h2>"
      "<div style=\"display:flex;gap:18px;flex-wrap:wrap;align-items:flex-start\"><figure style=\"margin:0;flex:none\"><img class=\"screen\" src=\"/image.bmp?t="
      + String(millis()) + "\" width=\"" + w + "\" height=\"" + h + "\" style=\"width:" + w + "px;height:" + h + "px;max-width:100%;object-fit:contain;image-rendering:pixelated\" alt=\"Current image\">"
        "<figcaption class=\"muted\">Uncorrected, as the server sends it. <a href=\"/\">Reload</a></figcaption></figure><div style=\"flex:1;min-width:200px\">"
      + slider("gamma", "Gamma (higher: darker mid tones)", toneSettings.gamma, minGamma, maxGamma, 5, true)
      + slider("red", "Red", toneSettings.red, minGain, maxGain, 1, false)
      + slider("green", "Green", toneSettings.green, minGain, maxGain, 1, false)
      + slider("blue", "Blue", toneSettings.blue, minGain, maxGain, 1, false)
      + "<p class=\"muted\">Applied to this display only: move a slider until the display looks like the image beside it.</p>"
        "<button name=\"reset\" value=\"1\">Reset to defaults</button></div></div></section></form>");
}
void saveColours() {
  using namespace client_policy;
  if (web.hasArg("reset")) toneSettings = ToneSettings();
  else {
    toneSettings.gamma = clampGamma(web.arg("gamma").toInt()); toneSettings.red = clampGain(web.arg("red").toInt());
    toneSettings.green = clampGain(web.arg("green").toInt()); toneSettings.blue = clampGain(web.arg("blue").toInt());
  }
  panelTone.build(toneSettings); saveConfig();
  // Redraw everything with the new correction: unconditional request on the next loop.
  forgetRows(); etag = ""; lastPull = millis() - clampPoll(pollSeconds) * 1000u;
  if (web.hasArg("live")) { web.send(204, "text/plain", ""); return; }  // slider moved, the page stays
  web.sendHeader("Location", "/", true); web.send(303, "text/plain", "");
}
// The device keeps no copy of the image (too little RAM), so it fetches a fresh one from the server and
// passes it on as a top-down 16-bit BMP (RGB565 bit fields); the server sends big-endian, BMP wants little-endian.
void imagePage() {
  if (mode != Mode::Paired) { web.send(404, "text/plain", "Not paired"); return; }
  ImageRequest request; HTTPClient &http = request.http;
  client_policy::ImageSize size;
  if (requestImage(request, false) != 200 || !validImage(http, size)) { web.send(502, "text/plain", "No image from the server"); return; }
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
uint32_t lastPullRequest = 0;
void handlePullRequest() {
  if (mode != Mode::Paired) { web.send(409, "text/plain", ""); return; }
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
  serveIcons();
  web.on("/image.bmp", HTTP_GET, imagePage);
  web.on("/colours", HTTP_POST, saveColours);
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
void serialReady() {
  JsonDocument doc; doc["identifier"] = identifier; doc["name"] = deviceName; doc["version"] = VIBEDOCK_VERSION; doc["paired"] = !key.isEmpty();
  doc["board"] = VIBEDOCK_BOARD; doc["chip"] = VIBEDOCK_CHIP;
  Serial.print("VIBEDOCK-READY "); serializeJson(doc, Serial); Serial.println();
}
void serialNetworks() {
  scanWifi();
  JsonDocument doc; JsonArray list = doc.to<JsonArray>();
  for (uint8_t i = 0; i < networkCount; i++) {
    JsonObject n = list.add<JsonObject>(); n["ssid"] = networks[i].ssid; n["rssi"] = networks[i].rssi; n["open"] = networks[i].open;
  }
  Serial.print("VIBEDOCK-NETWORKS "); serializeJson(doc, Serial); Serial.println();
}
void serialSetup(const String &body) {
  JsonDocument doc;
  if (deserializeJson(doc, body)) { Serial.println("VIBEDOCK-SETUP ERROR invalid request"); return; }
  String newSsid = doc["ssid"] | "", newPassword = doc["password"] | "";
  if (newSsid.isEmpty() || newSsid.length() > 32 || newPassword.length() > 64) { Serial.println("VIBEDOCK-SETUP ERROR invalid Wi-Fi"); return; }
  int status = applyPairing(doc);
  if (status == 400) { Serial.println("VIBEDOCK-SETUP ERROR no usable server address or invalid key"); return; }
  ssid = newSsid; password = newPassword; networkVerified = false;
  if (status != 200 || !saveConfig()) { Serial.println("VIBEDOCK-SETUP ERROR storage"); return; }
  Serial.println("VIBEDOCK-SETUP OK"); Serial.flush();
  message("Set up, restarting", Step::None);
  delay(500); ESP.restart();
}
// duringTls: called from inside a TLS handshake, which takes seconds on the ESP8266; only reading the log and the
// hello answer then, nothing that scans, restarts or changes the configuration.
bool usbDuringTls = false;
void serialPoll(bool duringTls = false) {
  if (!duringTls && usbDuringTls) {
    usbDuringTls = false; quickBootPending = false; LittleFS.remove("/boots"); logEvent("USB console: this start is not counted as a power cycle");
  }
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      String line = serialLine; serialLine = ""; line.trim();
      // Opening the USB port can reset the chip like a power cycle (reset reason "External System"). A start that
      // talks to the administration over USB is not counted, so reading the log or pairing over the cable never
      // adds up to the Wi-Fi setup or a factory reset.
      if (line.startsWith("VIBEDOCK-") && quickBootPending && duringTls) usbDuringTls = true;  // logged afterwards: little stack here
      else if (line.startsWith("VIBEDOCK-") && quickBootPending) {
        quickBootPending = false; LittleFS.remove("/boots"); logEvent("USB console: this start is not counted as a power cycle");
      }
      if (duringTls && line != "VIBEDOCK-HELLO" && line != "VIBEDOCK-LOG") continue;
      if (line == "VIBEDOCK-HELLO") serialReady();
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
  Serial.setRxBufferSize(1024); Serial.begin(115200);  // the flasher sends in paced chunks
#ifdef VIBEDOCK_BOOT_TRACE
  pinMode(TFT_BL, OUTPUT); digitalWrite(TFT_BL, TFT_BACKLIGHT_ON); delay(3000);
#endif
  BOOT_TRACE("serial");
  Serial.println(); Serial.println(reinterpret_cast<const __FlashStringHelper *>(IDENTITY));
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
  pinMode(TFT_BL, OUTPUT); digitalWrite(TFT_BL, TFT_BACKLIGHT_ON);
#if VIBEDOCK_TOUCH
  BOOT_TRACE("touch");
  touch::begin();
#endif
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
  logEvent("Start v" VIBEDOCK_VERSION " " VIBEDOCK_BOARD ", " + platform::resetReason());
  if (!platform::mountStorage() || !loadConfig()) { message("Storage error", Step::None, true); return; }
  if (brightness < 100) platform::backlight(TFT_BL, TFT_BACKLIGHT_ON == HIGH, brightness);
  if (upsideDown) applyOrientation(true);
  loadLog();
  loadAnchor(); loadOtaKey();
  serialReady();
  handleQuickBoots();
  if (ssid.isEmpty() || setupRequested) { startPortal(); return; }
  WiFi.setAutoReconnect(true); WiFi.begin(ssid.c_str(), password.c_str()); uint32_t began = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - began < 20000) { footer(); settleQuickBoot(); serialPoll(); delay(250); }
  bool online = WiFi.status() == WL_CONNECTED;
  if (!online) wifiProblem = connectionProblem(WiFi.status());
  logEvent(online ? "Wi-Fi " + ssid + ": " + WiFi.localIP().toString() + ", " + String(WiFi.RSSI()) + " dBm, heap " + platform::freeHeap() : "Wi-Fi: " + wifiProblem);
  if (!online && !networkVerified) { WiFi.setAutoReconnect(false); WiFi.disconnect(); startPortal(); return; }
  if (online && !networkVerified) { networkVerified = true; saveConfig(); }
  configTime(0, 0, "pool.ntp.org", "time.cloudflare.com");  // UTC; only needed to check https certificates
  shownIp = currentIp(); startPairingServer(); lastReconnect = millis();
  if (key.isEmpty()) enterPairing();
  else {
    mode = Mode::Paired; lastImage = millis(); lastPull = millis() - client_policy::clampPoll(pollSeconds) * 1000u;
    if (online) message("Connecting to server", Step::Server); else showWaiting();
  }
}
#if VIBEDOCK_TOUCH
// Swipes: left and right switch the colour scheme on the server (paired displays only), up and down the brightness.
uint32_t lastTouchPoll = 0, brightnessChangedAt = 0; bool brightnessDirty = false;
void touchPoll() {
  static bool touching = false; static int16_t startX, startY, lastX, lastY; static uint8_t idle = 0;
  if (brightnessDirty && millis() - brightnessChangedAt >= 3000) { brightnessDirty = false; saveConfig(); }  // flash once per series
  if (millis() - lastTouchPoll < 20) return;
  lastTouchPoll = millis();
  int16_t x, y;
  if (touch::read(x, y)) {
    if (!touching) { touching = true; startX = x; startY = y; }
    lastX = x; lastY = y; idle = 0; return;
  }
  // Released only after 60 ms without a point: the controllers drop single readings during a swipe.
  if (!touching || ++idle < 3) return;
  touching = false; idle = 0;
  int dx = lastX - startX, dy = lastY - startY;
  if (upsideDown) { dx = -dx; dy = -dy; }  // the panel's coordinates do not turn with the picture
  auto swipe = client_policy::swipeOf(dx, dy);
  if (swipe == client_policy::Swipe::Up || swipe == client_policy::Swipe::Down) {
    brightness = client_policy::nextBrightness(brightness, swipe == client_policy::Swipe::Up);
    platform::backlight(TFT_BL, TFT_BACKLIGHT_ON == HIGH, brightness);
    brightnessDirty = true; brightnessChangedAt = millis();
  } else if (swipe != client_policy::Swipe::None && mode == Mode::Paired) {
    pendingGesture = swipe == client_policy::Swipe::Left ? "scheme-next" : "scheme-previous";
    logEvent("Touch: " + pendingGesture, true);
    lastPull = millis() - client_policy::clampPoll(pollSeconds) * 1000u;  // the new image right away
  }
}
#endif

// Turns the picture by 180 degrees or back (Flip image in the administration); the next pull draws every row.
void applyOrientation(bool flipped) {
  upsideDown = flipped;
  panel.setRotation((VIBEDOCK_ROTATION + (flipped ? 2 : 0)) % 4);
  forgetRows();
  if (imageOnScreen) { etag = ""; lastPull = millis() - client_policy::clampPoll(pollSeconds) * 1000u; }  // the image again, every row
  else present(true);
}

void loop() {
  serialPoll();
#if VIBEDOCK_TOUCH
  touchPoll();
#endif
  if (logDirty && millis() - logSavedAt >= 60000) saveLog();
  static bool clockLogged = false;
  if (!clockLogged && clockSet()) { clockLogged = true; logEvent("Clock set (NTP)"); }
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
        message("Connecting to server", Step::Server); lastPull = millis() - client_policy::clampPoll(pollSeconds) * 1000u;
      } else showWaiting();
    }
  }
  if (WiFi.status() != WL_CONNECTED && millis() - lastReconnect >= 15000) { lastReconnect = millis(); WiFi.reconnect(); }
  if (WiFi.status() == WL_CONNECTED && !networkVerified) { networkVerified = true; saveConfig(); }
  if (mode == Mode::Paired) {
    if (client_policy::pullDue(millis(), lastPull, pollSeconds)) pull();
    if (mode == Mode::Paired && haveImage && !staleScreen && client_policy::imageStale(millis(), lastImage, pollSeconds)) {
      staleScreen = true;
      if (WiFi.status() == WL_CONNECTED) message("Updates missing", Step::Server, true); else showWaiting();
    }
  }
  yield();
}
