#if defined(ESP8266)
#define MBEDTLS_ALLOW_PRIVATE_ACCESS  // the received alert (ssl.in_msg) for the device log
#include "tls13.h"
#include <mbedtls/platform_time.h>
#include <mbedtls/platform.h>
#include <mbedtls/oid.h>
#include <bearssl/bearssl_hash.h>
#include <psa/crypto.h>
extern "C" {
#include <mbedtls/psa_util.h>  // has no extern "C" of its own
}
#include <umm_malloc/umm_heap_select.h>

// The handshake runs on a separate stack: checking the server's certificates and RSA signature needs more than the
// 4 kB the sketch has (a stack overflow aborted the display). The same switch as the core's StackThunk for BearSSL,
// with its own 4.5 kB instead of 6.2 kB (the handshake used at most 3.3 kB): DRAM is short during the handshake.
// After the handshake it is freed again, with the certificates: reading and writing (decrypt, encrypt) fit the
// sketch's stack, and session tickets are ignored (MBEDTLS_SSL_SESSION_TICKETS off), so nothing deep follows.
constexpr size_t STACK_WORDS = 4608 / 4;
constexpr uint32_t STACK_PAINT = 0xdeadbeef;
extern "C" {
uint32_t *tls13_stack = nullptr, *tls13_stack_top = nullptr, *tls13_stack_save = nullptr;
int tls13_handshake_step(mbedtls_ssl_context *ssl) { return mbedtls_ssl_handshake_step(ssl); }
void tls13_stack_smashed() { panic(); }
int tls13_thunk_step(mbedtls_ssl_context *ssl);
}
// Calls tls13_handshake_step on tls13_stack (call0 ABI: the argument stays in a2), then checks the lowest word.
__asm__(R"(
.text
.literal_position
.literal .LC_TLS13_PAINT, 0xdeadbeef
.text
.global tls13_thunk_step
.type tls13_thunk_step, @function
.align 4
tls13_thunk_step:
  addi a1, a1, -16
  s32i a0, a1, 12
  s32i a15, a1, 8
  movi a15, tls13_stack_save
  s32i a1, a15, 0
  movi a15, tls13_stack_top
  l32i.n a1, a15, 0
  call0 tls13_handshake_step
  movi a15, tls13_stack
  l32i.n a15, a15, 0
  l32i.n a15, a15, 0
  l32r a0, .LC_TLS13_PAINT
  beq a0, a15, .L_TLS13_OK
  call0 tls13_stack_smashed
.L_TLS13_OK:
  movi a15, tls13_stack_save
  l32i.n a1, a15, 0
  l32i.n a15, a1, 8
  l32i.n a0, a1, 12
  addi a1, a1, 16
  ret
.size tls13_thunk_step, . - tls13_thunk_step
)");
static bool allocateStack() {
  HeapSelectDram dram;  // never IRAM: a stack there is too slow (core note in StackThunk.cpp)
  tls13_stack = static_cast<uint32_t *>(malloc(STACK_WORDS * 4));
  if (!tls13_stack) return false;
  for (size_t i = 0; i < STACK_WORDS; i++) tls13_stack[i] = STACK_PAINT;
  tls13_stack_top = tls13_stack + STACK_WORDS - 1;
  return true;
}
static size_t stackUsed() {
  size_t untouched = 0;
  while (untouched < STACK_WORDS && tls13_stack[untouched] == STACK_PAINT) untouched++;
  return 4 * (STACK_WORDS - untouched);
}
static void freeStack() { free(tls13_stack); tls13_stack = tls13_stack_top = tls13_stack_save = nullptr; }

namespace tls13 {
String &lastError() { static String text; return text; }
bool &versionRefused() { static bool refused = false; return refused; }
// Heap Mbed TLS holds now and at most during the last connection: the ESP8266 has little to spare.
size_t used = 0, peak = 0;
size_t sent = 0, received = 0, records = 0; int lastRead = 0;  // traffic after the handshake
size_t recordHave = 0, recordWant = 0; int tcpWaiting = 0; uint32_t lastByte = 0;  // where reading stands
size_t failed = 0, failedLargest = 0;  // allocations Mbed TLS did not get during the handshake
bool handshaking = false;
uint8_t leaf[20]; bool leafSeen = false;  // SHA-1 of the first certificate the server sent
bool leafFingerprint(uint8_t out[20]) { if (leafSeen) memcpy(out, leaf, 20); return leafSeen; }
void (*wait)() = nullptr;
String &lastSuccess() { static String text; return text; }
String traffic() {
  char code[12]; snprintf(code, sizeof(code), "-0x%04X", unsigned(-lastRead));
  return String(F("sent ")) + String(sent) + F(", got ") + String(received) + F(" B, ") + String(records) + F(" reads, last ") + (lastRead < 0 ? String(code) : String(lastRead))
      + F(", record ") + String(recordHave) + "/" + String(recordWant) + F(", TCP ") + String(tcpWaiting) + F(", idle ")
      + String(millis() - lastByte) + F(" ms, heap ") + heapText();
}
}
static void *countedCalloc(size_t count, size_t size) {
  size_t bytes = count * size; if (size && bytes / size != count) return nullptr;
  size_t *block = nullptr;
#if defined(MMU_IRAM_HEAP)
  // Larger blocks (the record buffers first, then certificates and numbers) from the IRAM heap: DRAM is too small
  // for TLS 1.3 next to Wi-Fi, the app and the handshake stack.
  if (bytes >= 256) { HeapSelectIram iram; block = static_cast<size_t *>(calloc(1, bytes + sizeof(size_t))); }
#endif
  if (!block) block = static_cast<size_t *>(calloc(1, bytes + sizeof(size_t)));
  if (!block) { tls13::failed++; if (bytes > tls13::failedLargest) tls13::failedLargest = bytes; return nullptr; }
  *block = bytes; tls13::used += bytes; if (tls13::used > tls13::peak) tls13::peak = tls13::used;
  return block + 1;
}
// The server's certificates are parsed without a copy (linked with --wrap=mbedtls_x509_crt_parse_der): Mbed TLS
// checks them in the same step, straight from the received message, and afterwards only needs their public keys,
// which are copied. Saves the 2.5–4 kB the copies of the chain would take during the check.
extern "C" int __real_mbedtls_x509_crt_parse_der(mbedtls_x509_crt *chain, const unsigned char *buf, size_t length);
extern "C" int __wrap_mbedtls_x509_crt_parse_der(mbedtls_x509_crt *chain, const unsigned char *buf, size_t length) {
  if (tls13::handshaking && !tls13::leafSeen) {  // the server certificate comes first: its fingerprint for the pin
    br_sha1_context sha; br_sha1_init(&sha); br_sha1_update(&sha, buf, length); br_sha1_out(&sha, tls13::leaf); tls13::leafSeen = true;
  }
  return tls13::handshaking ? mbedtls_x509_crt_parse_der_nocopy(chain, buf, length) : __real_mbedtls_x509_crt_parse_der(chain, buf, length);
}
static void countedFree(void *pointer) {
  if (!pointer) return;
  auto *block = static_cast<size_t *>(pointer) - 1; tls13::used -= *block; free(block);
}

// Hooks of the configuration (vibedock_mbedtls_config.h): random numbers from the chip, time in milliseconds.
extern "C" int mbedtls_hardware_poll(void *, unsigned char *output, size_t length, size_t *written) {
  for (size_t i = 0; i < length; i += 4) { uint32_t r = RANDOM_REG32; memcpy(output + i, &r, std::min<size_t>(4, length - i)); }
  *written = length;
  return 0;
}
extern "C" mbedtls_ms_time_t mbedtls_ms_time(void) { return (mbedtls_ms_time_t)millis(); }

// Free DRAM and IRAM heap for the log.
String tls13::heapText() {
  String text = String(ESP.getFreeHeap());
#if defined(MMU_IRAM_HEAP)
  { HeapSelectIram iram; text += "+" + String(ESP.getFreeHeap()) + F(" IRAM"); }
#endif
  return text;
}

static const __FlashStringHelper *alertName(uint8_t alert) {  // texts in flash: RAM is short
  switch (alert) {
    case 10: return F("unexpected message"); case 20: return F("bad record mac"); case 22: return F("record overflow");
    case 40: return F("handshake failure"); case 42: return F("bad certificate"); case 47: return F("illegal parameter");
    case 50: return F("decode error"); case 51: return F("decrypt error"); case 70: return F("protocol version");
    case 80: return F("internal error"); case 109: return F("missing extension"); case 110: return F("unsupported extension");
    case 112: return F("unrecognized name"); case 116: return F("certificate required"); default: return F("see RFC 8446");
  }
}

static String describe(int code, uint32_t flags = 0) {
  char hex[12]; snprintf(hex, sizeof(hex), "-0x%04X", (unsigned)-code);
  String text = String(F("TLS 1.3 ")) + String(hex);
  switch (code) {
    case MBEDTLS_ERR_SSL_FATAL_ALERT_MESSAGE: text += F(" alert from the server"); break;
    case MBEDTLS_ERR_SSL_BAD_PROTOCOL_VERSION: text += F(" server does not speak TLS 1.3"); break;
    case MBEDTLS_ERR_X509_CERT_VERIFY_FAILED: text += F(" certificate not trusted"); break;
    case MBEDTLS_ERR_SSL_ALLOC_FAILED: case MBEDTLS_ERR_MPI_ALLOC_FAILED: case MBEDTLS_ERR_X509_ALLOC_FAILED:
      text += String(F(" out of memory (")) + tls13::heapText() + F(" bytes free)"); break;
    case MBEDTLS_ERR_SSL_TIMEOUT: text += F(" timeout"); break;
    case MBEDTLS_ERR_SSL_CONN_EOF: text += F(" connection closed during the handshake"); break;
    case MBEDTLS_ERR_SSL_HANDSHAKE_FAILURE: text += F(" handshake failure"); break;  // also when memory runs out (see block)
    default: break;
  }
  if (flags & MBEDTLS_X509_BADCERT_EXPIRED) text += F(", certificate expired");
  if (flags & MBEDTLS_X509_BADCERT_FUTURE) text += F(", certificate not yet valid (clock?)");
  if (flags & MBEDTLS_X509_BADCERT_CN_MISMATCH) text += F(", host name does not match");
  // A signature check that runs out of memory also counts as "not trusted" in Mbed TLS.
  if (tls13::failed) text += String(F(", out of memory (")) + String(tls13::failed) + F(" allocations up to ") + String(tls13::failedLargest) + F(" bytes failed)");
  else if (flags & MBEDTLS_X509_BADCERT_NOT_TRUSTED) text += F(", root from pairing does not match");
  return text;
}

// The common name of a certificate name, for the log ("ISRG Root X1").
static String commonName(const mbedtls_x509_name *name) {
  for (; name; name = name->next)
    if (MBEDTLS_OID_CMP(MBEDTLS_OID_AT_CN, &name->oid) == 0) {
      char text[48]; size_t length = std::min(name->val.len, sizeof(text) - 1);
      memcpy(text, name->val.p, length); text[length] = 0; return String(text);
    }
  return "?";
}
int Tls13Client::sendRaw(void *self, const unsigned char *buf, size_t len) {
  auto *client = static_cast<Tls13Client *>(self);
  if (!client->WiFiClient::connected()) return MBEDTLS_ERR_SSL_CONN_EOF;
  // Never more than the TCP send buffer takes: a full buffer would make write() wait (yield), and during the
  // handshake this runs on the separate stack, where the sketch must not wait.
  size_t room = size_t(client->WiFiClient::availableForWrite());
  if (room == 0) return MBEDTLS_ERR_SSL_WANT_WRITE;
  size_t sent = client->WiFiClient::write(buf, std::min(len, room));
  tls13::sent += sent;
  return sent > 0 ? int(sent) : MBEDTLS_ERR_SSL_WANT_WRITE;
}
int Tls13Client::receiveRaw(void *self, unsigned char *buf, size_t len) {
  auto *client = static_cast<Tls13Client *>(self);
  int waiting = client->WiFiClient::available();
  if (waiting <= 0) return client->WiFiClient::connected() ? MBEDTLS_ERR_SSL_WANT_READ : MBEDTLS_ERR_SSL_CONN_EOF;
  int got = client->WiFiClient::read(buf, std::min<size_t>(len, size_t(waiting)));
  if (got > 0) { tls13::received += got; tls13::lastByte = millis(); }
  return got > 0 ? got : MBEDTLS_ERR_SSL_WANT_READ;
}

int Tls13Client::open(const String &host, IPAddress ip, uint16_t port, bool byName) {
  close(); closed_ = false; tls13::lastError() = ""; tls13::lastSuccess() = ""; tls13::versionRefused() = false;
  static bool psa = false;
  if (!psa) {
    mbedtls_platform_set_calloc_free(countedCalloc, countedFree);
    if (psa_crypto_init() != PSA_SUCCESS) { tls13::lastError() = "TLS 1.3 crypto init failed"; return 0; }
    psa = true;
  }
  tls13::peak = tls13::used; tls13::failed = tls13::failedLargest = 0; String heapBefore = tls13::heapText();
  // Resolved here: WiFiClient::connect(host) would call the overridden connect(ip) and start a second handshake
  // for the IP address instead of the host name.
  if (byName && !WiFi.hostByName(host.c_str(), ip)) { tls13::lastError() = String(F("name ")) + host + F(" not resolved (DNS)"); return 0; }
  if (!WiFiClient::connect(ip, port)) { tls13::lastError() = String(F("no TCP connection to ")) + ip.toString() + ":" + String(port); return 0; }
  setNoDelay(true);
  session_ = new (std::nothrow) Session;
  if (!session_) { tls13::lastError() = String(F("TLS 1.3 out of memory for its contexts; heap ")) + tls13::heapText(); WiFiClient::stop(); return 0; }
  mbedtls_ssl_context &ssl_ = session_->ssl; mbedtls_ssl_config &conf_ = session_->conf; mbedtls_x509_crt &ca_ = session_->ca;
  mbedtls_ssl_init(&ssl_); mbedtls_ssl_config_init(&conf_); mbedtls_x509_crt_init(&ca_);
  int code = mbedtls_ssl_config_defaults(&conf_, MBEDTLS_SSL_IS_CLIENT, MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT);
  if (!code) {
    mbedtls_ssl_conf_min_tls_version(&conf_, MBEDTLS_SSL_VERSION_TLS1_3);
    mbedtls_ssl_conf_max_tls_version(&conf_, MBEDTLS_SSL_VERSION_TLS1_3);
    // Pinned: no chain check (the RSA signature of the root takes the most memory and a second of computing); the
    // certificate is compared with the pin after the handshake, before any request is sent.
    mbedtls_ssl_conf_authmode(&conf_, pin_ ? MBEDTLS_SSL_VERIFY_NONE : MBEDTLS_SSL_VERIFY_REQUIRED);
    if (!pin_) mbedtls_ssl_conf_ca_chain(&conf_, &ca_, nullptr);
    mbedtls_ssl_conf_rng(&conf_, mbedtls_psa_get_random, MBEDTLS_PSA_RANDOM_STATE);  // PSA's generator, no own DRBG
    // SHA-256 first: SHA-384/512 need about 800 bytes more stack.
    // Only AES-128-GCM-SHA256: every TLS 1.3 server must support it (RFC 8446, 9.1). Offered alongside AES-256 the
    // Synology chose AES-256-GCM-SHA384, slower to decrypt and with a SHA-384 key schedule.
    static const int suites[] = {MBEDTLS_TLS1_3_AES_128_GCM_SHA256, 0};
    static const uint16_t signatures[] = {MBEDTLS_TLS1_3_SIG_ECDSA_SECP256R1_SHA256, MBEDTLS_TLS1_3_SIG_RSA_PSS_RSAE_SHA256,
        MBEDTLS_TLS1_3_SIG_ECDSA_SECP384R1_SHA384, MBEDTLS_TLS1_3_SIG_RSA_PSS_RSAE_SHA384,
        MBEDTLS_TLS1_3_SIG_RSA_PKCS1_SHA256, MBEDTLS_TLS1_3_SIG_RSA_PKCS1_SHA384, MBEDTLS_TLS1_3_SIG_NONE};
    mbedtls_ssl_conf_ciphersuites(&conf_, suites);
    mbedtls_ssl_conf_sig_algs(&conf_, signatures);
    code = mbedtls_ssl_setup(&ssl_, &conf_);  // the record buffers: first, so that they get the IRAM heap
  }
  if (!code && !pin_) code = mbedtls_x509_crt_parse_der_nocopy(&ca_, anchor_, anchorLength_);  // the root stays in anchorDer()
  if (!code) code = mbedtls_ssl_set_hostname(&ssl_, host.c_str());  // SNI and the host name check
  if (code) { tls13::lastError() = describe(code); close(); return 0; }
  mbedtls_ssl_set_bio(&ssl_, this, sendRaw, receiveRaw, nullptr);
  if (!allocateStack()) {
    tls13::lastError() = String(F("TLS 1.3 needs ")) + String(STACK_WORDS * 4) + F(" bytes for its stack, largest free block ")
        + String(ESP.getMaxFreeBlockSize()) + F("; heap ") + tls13::heapText();
    close(); return 0;
  }
  thunk_ = true;
  // The handshake step by step: the ESP8266 watchdog needs the loop to come back in between. Waiting happens here,
  // on the sketch's stack: a step itself never waits.
  // Timeout: 15 s without progress (the server does not answer), at most 60 s in all. The steps themselves take
  // seconds on the ESP8266 (key exchange, certificate check), so they do not count as waiting.
  uint32_t started = millis(), progress = started, computing = 0;
  tls13::handshaking = true; tls13::leafSeen = false;
  while (!mbedtls_ssl_is_handshake_over(&ssl_)) {
    int state = ssl_.state; uint32_t stepStart = millis();
    code = tls13_thunk_step(&ssl_);
    uint32_t now = millis();
    if (ssl_.state != state || code == 0) { computing += now - stepStart; progress = now; }
    if (code == MBEDTLS_ERR_SSL_WANT_READ || code == MBEDTLS_ERR_SSL_WANT_WRITE) {
      if (now - progress > 15000 || now - started > 60000) { code = MBEDTLS_ERR_SSL_TIMEOUT; break; }
      if (tls13::wait) tls13::wait();
      delay(2); continue;
    }
    if (code) break;
    yield();
  }
  tls13::handshaking = false;
  String timing = String(F(" after ")) + String(millis() - started) + F(" ms (") + String(computing) + F(" computing, step ") + String(ssl_.state) + ")";
  String stack = String(F(", stack ")) + String(stackUsed()) + "/" + String(STACK_WORDS * 4);
  if (code) {
    tls13::versionRefused() = code == MBEDTLS_ERR_SSL_BAD_PROTOCOL_VERSION;
    String alert;
    if (code == MBEDTLS_ERR_SSL_FATAL_ALERT_MESSAGE && ssl_.in_msg) alert = " " + String(ssl_.in_msg[1]) + " (" + alertName(ssl_.in_msg[1]) + ")";
    uint32_t flags = mbedtls_ssl_get_verify_result(&ssl_);
    // Which root the server's chain leads to, next to the root from pairing (still valid: the check failed in the
    // step that received the certificates).
    const mbedtls_x509_crt *top = ssl_.session_negotiate ? ssl_.session_negotiate->peer_cert : nullptr;
    if (top && flags != uint32_t(-1) && (flags & MBEDTLS_X509_BADCERT_NOT_TRUSTED) && !tls13::failed) {
      while (top->next && top->next->raw.p) top = top->next;
      alert += String(F("; chain issued by ")) + commonName(&top->issuer) + F(", root ") + commonName(&ca_.subject);
    }
    tls13::lastError() = describe(code, flags) + alert + timing + F("; heap ") + heapBefore + F(", TLS ") + String(tls13::peak)
        + F(", block ") + String(ESP.getMaxFreeBlockSize()) + stack;
    close(); return 0;
  }
  if (pin_ && (!tls13::leafSeen || memcmp(tls13::leaf, pin_, 20) != 0)) {
    tls13::lastError() = String(F("TLS 1.3 certificate is not the pinned one (renewed?)")) + timing;
    close(); return 0;
  }
  // What the connection no longer needs: the separate stack, the server's certificates and the root.
  freeStack(); thunk_ = false;
  if (ssl_.session && ssl_.session->peer_cert) {
    mbedtls_x509_crt_free(ssl_.session->peer_cert); mbedtls_free(ssl_.session->peer_cert); ssl_.session->peer_cert = nullptr;
  }
  mbedtls_x509_crt_free(&ca_);
  ready_ = true; tls13::sent = tls13::received = tls13::records = 0; tls13::lastRead = 0; tls13::lastByte = millis();
  tls13::recordHave = tls13::recordWant = 0; tls13::tcpWaiting = 0;
  String after = ", " + tls13::heapText() + F(" free after");
  tls13::lastSuccess() = String("TLS 1.3 ") + (pin_ ? "pinned " : "full check ") + mbedtls_ssl_get_ciphersuite(&ssl_) + timing + F("; heap ")
      + heapBefore + F(", TLS ") + String(tls13::peak) + stack + after;
  return 1;
}

void Tls13Client::close() {
  if (ready_) mbedtls_ssl_close_notify(&session_->ssl);
  ready_ = false; peeked_ = -1;
  if (session_) {
    mbedtls_ssl_free(&session_->ssl); mbedtls_ssl_config_free(&session_->conf); mbedtls_x509_crt_free(&session_->ca);
    delete session_; session_ = nullptr;
  }
  if (thunk_) { freeStack(); thunk_ = false; }
  // Abort instead of a regular close: a closed connection stays in TIME_WAIT for two minutes and keeps its lwIP
  // memory. With a pull every 30 s that is up to four connections, about 800 bytes the next handshake was missing
  // (out of memory in the certificate check). The close notify above already ended TLS cleanly.
  if (WiFiClient::connected()) WiFiClient::abort(); else WiFiClient::stop();
}

size_t Tls13Client::write(const uint8_t *buf, size_t size) {
  if (!ready_) return 0;
  size_t done = 0; uint32_t started = millis();
  while (done < size) {
    int sent = mbedtls_ssl_write(&session_->ssl, buf + done, size - done);
    if (sent > 0) { done += sent; continue; }
    if ((sent == MBEDTLS_ERR_SSL_WANT_WRITE || sent == MBEDTLS_ERR_SSL_WANT_READ) && millis() - started < 10000) { delay(1); continue; }
    break;
  }
  return done;
}
size_t Tls13Client::write_P(PGM_P buf, size_t size) {
  uint8_t chunk[64]; size_t done = 0;
  while (done < size) {
    size_t n = std::min(sizeof(chunk), size - done); memcpy_P(chunk, buf + done, n);
    size_t sent = write(chunk, n); done += sent; if (sent < n) break;
  }
  return done;
}

// How far the record being received is, for the error text when an answer stalls.
void Tls13Client::noteRecord() {
  const mbedtls_ssl_context &ssl = session_->ssl;
  tls13::recordHave = ssl.in_left;
  tls13::recordWant = ssl.in_left >= 5 && ssl.in_hdr ? 5 + ((size_t(ssl.in_hdr[3]) << 8) | ssl.in_hdr[4]) : 0;
  tls13::tcpWaiting = WiFiClient::available();
}
int Tls13Client::fill() {
  if (peeked_ >= 0) return 1;
  if (!ready_ || closed_) return 0;
  if (mbedtls_ssl_get_bytes_avail(&session_->ssl) == 0 && !WiFiClient::available() && WiFiClient::connected()) return 0;
  uint8_t b;
  for (;;) {
    int got = mbedtls_ssl_read(&session_->ssl, &b, 1); tls13::records++; tls13::lastRead = got; noteRecord();
    if (got == 1) { peeked_ = b; return 1; }
    // Tickets the server sends after the handshake are not used; reading goes on.
    if (got == MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET) continue;
    if (got == MBEDTLS_ERR_SSL_WANT_READ || got == MBEDTLS_ERR_SSL_WANT_WRITE) return 0;
    closed_ = true; return 0;  // close notify, end of stream or an error
  }
}
int Tls13Client::available() {
  if (!ready_) return peeked_ >= 0;
  fill();
  return int(mbedtls_ssl_get_bytes_avail(&session_->ssl)) + (peeked_ >= 0 ? 1 : 0);
}
int Tls13Client::read(uint8_t *buf, size_t size) {
  if (!ready_ || size == 0) return -1;
  size_t done = 0;
  if (peeked_ >= 0) { buf[done++] = uint8_t(peeked_); peeked_ = -1; }
  while (done < size) {
    if (mbedtls_ssl_get_bytes_avail(&session_->ssl) == 0 && !WiFiClient::available()) break;
    int got = mbedtls_ssl_read(&session_->ssl, buf + done, size - done); tls13::records++; tls13::lastRead = got; noteRecord();
    if (got > 0) { done += got; continue; }
    if (got == MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET) continue;
    if (got == MBEDTLS_ERR_SSL_WANT_READ || got == MBEDTLS_ERR_SSL_WANT_WRITE) break;
    closed_ = true; break;
  }
  return done ? int(done) : -1;
}
int Tls13Client::peek() { return fill() ? peeked_ : -1; }
size_t Tls13Client::peekBytes(uint8_t *buffer, size_t length) {
  if (length == 0 || !fill()) return 0;
  buffer[0] = uint8_t(peeked_); return 1;
}
uint8_t Tls13Client::connected() {
  if (!ready_ || closed_) return peeked_ >= 0;
  return WiFiClient::connected() || peeked_ >= 0 || mbedtls_ssl_get_bytes_avail(&session_->ssl) > 0;
}
#endif
