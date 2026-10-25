// What differs between the supported chips (ESP8266, ESP32, ESP32-S3, ESP32-C3): network and web server classes, TLS
// trust, the signed update over Wi-Fi, randomness and reset reasons. main.cpp only uses these names.
#pragma once
#include <Arduino.h>
#include <LittleFS.h>
#include <memory>
#include <functional>
#include "client_policy.h"

#ifndef VIBEDOCK_BOARD
#define VIBEDOCK_BOARD "vibedock-m1"  // board id, as in the server's board inventory
#endif

#if defined(ESP8266)
#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <ESP8266WebServer.h>
#include <WiFiClientSecureBearSSL.h>
#include <vector>
#include "tls13.h"
#include <Updater.h>
#include <libb64/cdecode.h>
extern "C" {
#include <osapi.h>
}
using VibeWebServer = ESP8266WebServer;
#define VIBEDOCK_CHIP "ESP8266"
#else
#include <WiFi.h>
#include <HTTPClient.h>
#include <WebServer.h>
#include <WiFiClientSecure.h>
#include <Update.h>
#include <MD5Builder.h>
#include <esp_system.h>
#include <esp_random.h>
#include <mbedtls/pk.h>
#include <mbedtls/sha256.h>
#include <mbedtls/sha1.h>
#include <mbedtls/x509_crt.h>
#include <mbedtls/base64.h>
using VibeWebServer = WebServer;
#define HTTPC_ERROR_CONNECTION_FAILED HTTPC_ERROR_CONNECTION_REFUSED  // the ESP32 name of "no connection" (-1)
#if CONFIG_IDF_TARGET_ESP32S3
#define VIBEDOCK_CHIP "ESP32-S3"
#elif CONFIG_IDF_TARGET_ESP32C3
#define VIBEDOCK_CHIP "ESP32-C3"
#elif CONFIG_IDF_TARGET_ESP32S2
#define VIBEDOCK_CHIP "ESP32-S2"
#elif CONFIG_IDF_TARGET_ESP32C6
#define VIBEDOCK_CHIP "ESP32-C6"
#else
#define VIBEDOCK_CHIP "ESP32"
#endif
#endif

namespace platform {

inline bool randomBytes(uint8_t *out, size_t length) {
#if defined(ESP8266)
  return os_get_random(out, length) == 0;
#else
  esp_fill_random(out, length);  // hardware RNG; seeded by the radio once Wi-Fi runs
  return true;
#endif
}
// Power-on and external (button, USB) resets count as a power cycle for the factory reset by unplugging.
inline bool powerCycleStart() {
#if defined(ESP8266)
  return client_policy::countsAsPowerCycle(ESP.getResetInfoPtr()->reason);
#else
  auto reason = esp_reset_reason();
  return reason == ESP_RST_POWERON || reason == ESP_RST_EXT;
#endif
}
// crash.cpp: the crash of the run before as text (ESP8266: the crash record; ESP32: what the panic handler saw), and
// for X-VibeDock-Diag as "<code addresses in hex, pc first, comma-separated>" with ";cause=…;fault=…" where known and
// ";abort=<text>" after abort or assert (empty without a crash). crashBegin() reads the record of the run before and
// (Arduino core 3) registers the panic handler; called first in setup().
String lastCrash();
String crashHeader();
void crashBegin();
// crash.cpp: the reset as a number (ESP8266: rst_info reason or the crash record's; ESP32: esp_reset_reason) and
// its kind, and the diagnosis record in memory that survives a reset.
uint32_t resetCode();
client_policy::ResetKind resetKind();
bool loadDiag(client_policy::DiagRecord &record);
void storeDiag(const client_policy::DiagRecord &record);
// The lowest free heap since the start, where the platform keeps it (0: unknown).
inline uint32_t minFreeHeap() {
#if defined(ESP8266)
  return 0;
#else
  return ESP.getMinFreeHeap();
#endif
}
inline String resetReason() {
#if defined(ESP8266)
  String crash = lastCrash();
  return crash.isEmpty() ? ESP.getResetReason() : ESP.getResetReason() + ": " + crash;
#else
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON: return "Power on";
    case ESP_RST_EXT: return "External reset";
    case ESP_RST_SW: return "Software restart";
    case ESP_RST_PANIC: return lastCrash().isEmpty() ? "Exception" : "Exception: " + lastCrash();
    case ESP_RST_INT_WDT: case ESP_RST_TASK_WDT: case ESP_RST_WDT: return lastCrash().isEmpty() ? "Watchdog" : "Watchdog: " + lastCrash();
    case ESP_RST_BROWNOUT: return "Brownout";
    case ESP_RST_DEEPSLEEP: return "Deep sleep";
    default: return "Other";
  }
#endif
}
// Forgets the Wi-Fi credentials the SDK keeps in its own flash area (factory reset).
inline void eraseWifi() {
#if defined(ESP8266)
  WiFi.persistent(true); WiFi.disconnect(true); WiFi.persistent(false); ESP.eraseConfig();
#else
  WiFi.disconnect(true, true);
#endif
}
inline bool openNetwork(int index) {
#if defined(ESP8266)
  return WiFi.encryptionType(index) == ENC_TYPE_NONE;
#else
  return WiFi.encryptionType(index) == WIFI_AUTH_OPEN;
#endif
}
inline bool wrongPassword(wl_status_t status) {
#if defined(ESP8266)
  return status == WL_WRONG_PASSWORD;
#else
  (void)status; return false;  // the ESP32 core reports a wrong password as WL_CONNECT_FAILED or WL_DISCONNECTED
#endif
}
// A function the TLS handshake calls while it waits for the server (ESP8266: seconds); a no-op elsewhere.
inline void onTlsWait(void (*wait)()) {
#if defined(ESP8266)
  tls13::wait = wait;
#else
  (void)wait;
#endif
}

inline String freeHeap() {
#if defined(ESP8266)
  return tls13::heapText();
#else
  return String(ESP.getFreeHeap());
#endif
}

// Backlight brightness in percent by PWM; 100 drives the pin fully on. Once the PWM is attached the pin stays on it,
// also at 100 %: switching it back to a plain output (pinMode) detaches the PWM, and later dimming had no effect.
inline void backlight(uint8_t pin, bool activeHigh, uint8_t percent) {
  if (percent > 100) percent = 100;
  uint32_t duty = uint32_t(percent) * 255 / 100; if (!activeHigh) duty = 255 - duty;
#if defined(ESP8266)
  // The ESP8266 core stops its PWM waveform in digitalWrite itself.
  if (percent >= 100) { pinMode(pin, OUTPUT); digitalWrite(pin, activeHigh ? HIGH : LOW); return; }
  analogWriteRange(255); analogWrite(pin, duty);
#elif ESP_ARDUINO_VERSION_MAJOR >= 3
  static bool attached = false;
  if (!attached && percent < 100) attached = ledcAttach(pin, 5000, 8);
  if (attached) ledcWrite(pin, duty);
  else { pinMode(pin, OUTPUT); digitalWrite(pin, activeHigh ? HIGH : LOW); }
#else
  static bool attached = false;
  if (!attached && percent < 100) { ledcSetup(0, 5000, 8); ledcAttachPin(pin, 0); attached = true; }
  if (attached) ledcWrite(0, duty);
  else { pinMode(pin, OUTPUT); digitalWrite(pin, activeHigh ? HIGH : LOW); }
#endif
}
inline bool mountStorage() {
#if defined(ESP8266)
  return LittleFS.begin();
#else
  return LittleFS.begin(true);  // formats an empty partition on the first start after flashing
#endif
}
// Chunked HTML answers (the ESP32 web server has no chunkedResponseModeStart).
inline void chunkedStart(VibeWebServer &web, const char *type) {
#if defined(ESP8266)
  web.chunkedResponseModeStart(200, type);
#else
  web.setContentLength(CONTENT_LENGTH_UNKNOWN); web.send(200, type, "");
#endif
}
inline void chunkedEnd(VibeWebServer &web) {
#if defined(ESP8266)
  web.chunkedResponseFinalize();
#else
  web.sendContent("");
#endif
}
inline size_t base64Decode(const String &encoded, uint8_t *out, size_t capacity) {
#if defined(ESP8266)
  (void)capacity;
  return base64_decode_chars(encoded.c_str(), encoded.length(), (char *)out);
#else
  size_t written = 0;
  return mbedtls_base64_decode(out, capacity, &written, (const unsigned char *)encoded.c_str(), encoded.length()) == 0 ? written : 0;
#endif
}

// ---- TLS trust: the root certificate the server sends when pairing is the only certificate trusted ----
#if defined(ESP8266)
inline std::unique_ptr<BearSSL::X509List> &anchorList() { static std::unique_ptr<BearSSL::X509List> list; return list; }
inline std::vector<uint8_t> &anchorDer() { static std::vector<uint8_t> der; return der; }  // for TLS 1.3 (Mbed TLS)
inline bool singleCertificate(const uint8_t *der, size_t length) { return BearSSL::X509List(der, length).getCount() == 1; }
inline bool setAnchor(const uint8_t *der, size_t length) {
  std::unique_ptr<BearSSL::X509List> list(new BearSSL::X509List(der, length));
  if (list->getCount() != 1) return false;
  anchorList() = std::move(list); anchorDer().assign(der, der + length);
  return true;
}
inline bool hasAnchor() { return bool(anchorList()); }
inline void clearAnchor() { anchorList().reset(); anchorDer().clear(); }
// The simple check over TLS 1.3: exactly the server certificate with this SHA-1 (see Tls13Client).
inline WiFiClient *pinnedClient(const uint8_t fingerprint[20]) {
  static uint8_t pinned[20]; memcpy(pinned, fingerprint, 20);
  return new Tls13Client(anchorDer().data(), anchorDer().size(), pinned);
}
// SHA-1 of the server certificate the last full check verified, as hex: the display pins it itself.
inline String verifiedFingerprint(WiFiClient *client) {
  (void)client;
  uint8_t fingerprint[20]; if (!tls13::leafFingerprint(fingerprint)) return String();
  char hex[41]; for (int i = 0; i < 20; i++) snprintf(hex + 2 * i, 3, "%02x", fingerprint[i]);
  return String(hex);
}
// The full check: chain up to the anchor, host name and validity at the current time.
inline WiFiClient *verifiedClient() { return new Tls13Client(anchorDer().data(), anchorDer().size()); }
// Only for servers that refuse TLS 1.3: BearSSL with TLS 1.2, the same full check against the anchor.
inline WiFiClient *legacyClient() {
  auto *tls = new BearSSL::WiFiClientSecure(); tls->setTrustAnchors(anchorList().get()); tls->setX509Time(time(nullptr)); return tls;
}
inline bool versionRefused() { return tls13::versionRefused(); }
inline String tlsSummary() { return tls13::lastSuccess(); }
inline String tlsTraffic() { return tls13::traffic(); }
// Why the last TLS connection failed, for the device log.
inline String tlsError(WiFiClient *client, bool legacy = false) {
  if (!legacy) return tls13::lastError();
  char text[96] = ""; int code = static_cast<BearSSL::WiFiClientSecure *>(client)->getLastSSLError(text, sizeof(text));
  return code ? "TLS 1.2 " + String(code) + " " + text : String();
}
#else
inline String &anchorPem() { static String pem; return pem; }
inline bool singleCertificate(const uint8_t *der, size_t length) {
  mbedtls_x509_crt crt; mbedtls_x509_crt_init(&crt);
  bool ok = mbedtls_x509_crt_parse_der(&crt, der, length) == 0 && crt.next == nullptr;
  mbedtls_x509_crt_free(&crt);
  return ok;
}
inline bool setAnchor(const uint8_t *der, size_t length) {
  if (!singleCertificate(der, length)) return false;
  size_t needed = 0; mbedtls_base64_encode(nullptr, 0, &needed, der, length);
  std::unique_ptr<unsigned char[]> b64(new unsigned char[needed + 1]); size_t written = 0;
  if (mbedtls_base64_encode(b64.get(), needed + 1, &written, der, length) != 0) return false;
  String pem = "-----BEGIN CERTIFICATE-----\n";
  for (size_t i = 0; i < written; i += 64) { pem += String((const char *)b64.get() + i).substring(0, std::min<size_t>(64, written - i)); pem += "\n"; }
  pem += "-----END CERTIFICATE-----\n";
  anchorPem() = pem;
  return true;
}
inline bool hasAnchor() { return anchorPem().length() > 0; }
inline void clearAnchor() { anchorPem() = ""; }
inline bool leafSha1(const mbedtls_x509_crt *leaf, uint8_t out[20]) {
  return leaf && leaf->raw.p && mbedtls_sha1(leaf->raw.p, leaf->raw.len, out) == 0;
}
// The simple check, as on the M1: only the pinned server certificate (SHA-1 of the leaf) is accepted, and the handshake
// still proves the server holds its key. Chain, host name and validity are not checked; when the pin no longer
// matches (renewed certificate) the full check against the anchor follows.
// How the last connection was checked, for the log ("TLS pinned", "TLS full check"); empty until a handshake succeeded.
inline String &tlsMode() { static String mode; return mode; }
class PinnedClient : public WiFiClientSecure {
 public:
  explicit PinnedClient(const uint8_t fingerprint[20]) { memcpy(pin, fingerprint, 20); setInsecure(); tlsMode() = ""; }
  int connect(IPAddress ip, uint16_t port) override { return accept(WiFiClientSecure::connect(ip, port)); }
  int connect(IPAddress ip, uint16_t port, int32_t timeout) override { return accept(WiFiClientSecure::connect(ip, port, timeout)); }
  int connect(const char *host, uint16_t port) override { return accept(WiFiClientSecure::connect(host, port)); }
  int connect(const char *host, uint16_t port, int32_t timeout) override { return accept(WiFiClientSecure::connect(host, port, timeout)); }
 private:
  uint8_t pin[20];
  int accept(int connected) {
    uint8_t seen[20];
    if (connected && leafSha1(getPeerCertificate(), seen) && memcmp(seen, pin, 20) == 0) { tlsMode() = F("TLS pinned"); return connected; }
    if (connected) stop();
    return 0;
  }
};
// The full check against the anchor: chain, host name and validity.
class VerifiedClient : public WiFiClientSecure {
 public:
  VerifiedClient() { setCACert(anchorPem().c_str()); tlsMode() = ""; }
  int connect(IPAddress ip, uint16_t port) override { return done(WiFiClientSecure::connect(ip, port)); }
  int connect(IPAddress ip, uint16_t port, int32_t timeout) override { return done(WiFiClientSecure::connect(ip, port, timeout)); }
  int connect(const char *host, uint16_t port) override { return done(WiFiClientSecure::connect(host, port)); }
  int connect(const char *host, uint16_t port, int32_t timeout) override { return done(WiFiClientSecure::connect(host, port, timeout)); }
 private:
  int done(int connected) { if (connected) tlsMode() = F("TLS full check"); return connected; }
};
inline WiFiClient *pinnedClient(const uint8_t fingerprint[20]) { return new PinnedClient(fingerprint); }
// SHA-1 of the server certificate the full check on this connection verified, as hex: the display pins it itself.
inline String verifiedFingerprint(WiFiClient *client) {
  uint8_t fingerprint[20];
  if (!client || !leafSha1(static_cast<WiFiClientSecure *>(client)->getPeerCertificate(), fingerprint)) return String();
  char hex[41]; for (int i = 0; i < 20; i++) snprintf(hex + 2 * i, 3, "%02x", fingerprint[i]);
  return String(hex);
}
inline WiFiClient *verifiedClient() { return new VerifiedClient(); }
inline WiFiClient *legacyClient() { return nullptr; }
inline bool versionRefused() { return false; }
inline String tlsSummary() { return tlsMode(); }
inline String tlsTraffic() { return String(); }
inline String tlsError(WiFiClient *client, bool legacy = false) {
  (void)legacy; char text[96] = ""; int code = static_cast<WiFiClientSecure *>(client)->lastError(text, sizeof(text));
  return code ? "TLS " + String(code) + " " + text : String();
}
#endif

// ---- updates over Wi-Fi: firmware || RSA-2048 signature (SHA-256, PKCS#1 v1.5) || signature length (le32) ----
#if defined(ESP8266)
inline std::unique_ptr<BearSSL::PublicKey> &otaKey() { static std::unique_ptr<BearSSL::PublicKey> key; return key; }
inline bool setOtaKey(const String &pem) {
  std::unique_ptr<BearSSL::PublicKey> key(new BearSSL::PublicKey(pem.c_str()));
  if (!key->isRSA()) return false;
  otaKey() = std::move(key);
  return true;
}
inline bool hasOtaKey() { return bool(otaKey()); }
inline void clearOtaKey() { otaKey().reset(); }
#else
struct PublicKey { mbedtls_pk_context pk; PublicKey() { mbedtls_pk_init(&pk); } ~PublicKey() { mbedtls_pk_free(&pk); } };
inline std::unique_ptr<PublicKey> &otaKey() { static std::unique_ptr<PublicKey> key; return key; }
inline bool setOtaKey(const String &pem) {
  std::unique_ptr<PublicKey> key(new PublicKey());
  if (mbedtls_pk_parse_public_key(&key->pk, (const unsigned char *)pem.c_str(), pem.length() + 1) != 0
      || mbedtls_pk_get_type(&key->pk) != MBEDTLS_PK_RSA || mbedtls_pk_get_bitlen(&key->pk) != 2048) return false;
  otaKey() = std::move(key);
  return true;
}
inline bool hasOtaKey() { return bool(otaKey()); }
inline void clearOtaKey() { otaKey().reset(); }
#endif

// Writes the signed firmware from the stream next to the running one and switches over only if the signature of the
// paired server and the MD5 (x-MD5, over the whole body) match. false with the reason otherwise.
inline bool installSigned(WiFiClient *stream, size_t size, const String &md5, String &error,
                          const std::function<void(size_t, size_t)> &progress) {
  uint8_t buffer[1024]; size_t done = 0; uint32_t lastData = millis(), lastDrawn = 0;
#if defined(ESP8266)
  BearSSL::HashSHA256 hash; BearSSL::SigningVerifier verifier(otaKey().get());
  Update.installSignature(&hash, &verifier);
  if (!Update.begin(size)) { error = "no space: " + Update.getErrorString(); return false; }
  if (md5.length() == 32) Update.setMD5(md5.c_str());
  while (done < size && millis() - lastData < 15000) {
    size_t available = stream->available();
    if (!available) { if (!stream->connected()) break; delay(2); continue; }
    size_t n = stream->readBytes(buffer, std::min(std::min(available, sizeof(buffer)), size - done));
    if (Update.write(buffer, n) != n) break;
    done += n; lastData = millis();
    if (millis() - lastDrawn > 250) { progress(done, size); lastDrawn = millis(); }
    yield();
  }
  if (done != size) { Update.end(false); error = Update.hasError() ? Update.getErrorString() : String("download interrupted"); return false; }
  if (!Update.end()) { error = Update.getErrorString(); return false; }  // signature, MD5 or image check failed
  return true;
#else
  constexpr size_t tail = 256 + 4;
  if (size <= tail + 4096) { error = "firmware too small"; return false; }
  const size_t image = size - tail;
  if (!Update.begin(image)) { error = String("no space: ") + Update.errorString(); return false; }
  mbedtls_sha256_context sha; mbedtls_sha256_init(&sha); mbedtls_sha256_starts(&sha, 0);
  MD5Builder whole; whole.begin();
  uint8_t signature[tail];
  while (done < size && millis() - lastData < 15000) {
    size_t available = stream->available();
    if (!available) { if (!stream->connected()) break; delay(2); continue; }
    size_t n = stream->readBytes(buffer, std::min(std::min(available, sizeof(buffer)), size - done));
    whole.add(buffer, n);
    size_t toImage = done < image ? std::min(n, image - done) : 0;
    if (toImage && Update.write(buffer, toImage) != toImage) break;
    mbedtls_sha256_update(&sha, buffer, toImage);
    if (n > toImage) memcpy(signature + (done + toImage - image), buffer + toImage, n - toImage);
    done += n; lastData = millis();
    if (millis() - lastDrawn > 250) { progress(done, size); lastDrawn = millis(); }
    yield();
  }
  uint8_t digest[32]; mbedtls_sha256_finish(&sha, digest); mbedtls_sha256_free(&sha);
  if (done != size) { Update.abort(); error = "download interrupted"; return false; }
  whole.calculate();
  if (md5.length() == 32 && !md5.equalsIgnoreCase(whole.toString())) { Update.abort(); error = "MD5 mismatch"; return false; }
  uint32_t length = signature[256] | signature[257] << 8 | signature[258] << 16 | uint32_t(signature[259]) << 24;
  if (!otaKey() || length != 256
      || mbedtls_pk_verify(&otaKey()->pk, MBEDTLS_MD_SHA256, digest, sizeof(digest), signature, 256) != 0) {
    Update.abort(); error = "signature check failed"; return false;
  }
  if (!Update.end(true)) { error = Update.errorString(); return false; }
  return true;
#endif
}

}  // namespace platform
