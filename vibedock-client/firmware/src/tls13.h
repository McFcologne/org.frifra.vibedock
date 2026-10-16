// TLS 1.3 client for the ESP8266 (VibeTV M1) on Mbed TLS 3.6, usable wherever a WiFiClient is: HTTPClient sends
// and reads through it. It trusts only the root certificate from pairing, checks host name and validity, and
// never connects unchecked. The plain TCP connection is the WiFiClient this class extends.
#pragma once
#if defined(ESP8266)
#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <mbedtls/ssl.h>
#include <mbedtls/x509_crt.h>

namespace tls13 {
// Why the last connection failed ("" after a successful one), for the device log; and whether the server refused
// the protocol version (a TLS 1.2-only server).
String &lastError();
bool &versionRefused();
String traffic();       // bytes sent and received since the handshake, and the last read result
String &lastSuccess();  // cipher suite, handshake time and memory of the last successful connection
String heapText();      // free DRAM heap, plus the IRAM heap the record buffers use
// SHA-1 of the server certificate of the last handshake (false if none was seen), for the pin.
bool leafFingerprint(uint8_t out[20]);
extern void (*wait)();   // called while the handshake waits for the server, e.g. to answer the USB console
}

class Tls13Client : public WiFiClient {
 public:
  // With `pin` (SHA-1 of the server certificate, 20 bytes that outlive the client): the simple check. The server must
  // present exactly that certificate and prove its key (TLS 1.3 CertificateVerify); chain, root and validity are not
  // checked again. Without: the full check against the root from pairing.
  Tls13Client(const uint8_t *anchor, size_t length, const uint8_t *pin = nullptr) : anchor_(anchor), anchorLength_(length), pin_(pin) {}
  ~Tls13Client() override { close(); }
  // HTTPClient::begin works on a clone: without this override it would be a plain WiFiClient and send the request
  // unencrypted. HTTPClient clones before it connects, so the clone starts unconnected with the same root.
  std::unique_ptr<WiFiClient> clone() const override { return std::unique_ptr<WiFiClient>(new Tls13Client(anchor_, anchorLength_, pin_)); }
  int connect(IPAddress ip, uint16_t port) override { return open(ip.toString(), ip, port, false); }
  int connect(const char *host, uint16_t port) override { return open(String(host), IPAddress(), port, true); }
  int connect(const String &host, uint16_t port) override { return open(host, IPAddress(), port, true); }
  size_t write(uint8_t b) override { return write(&b, 1); }
  size_t write(const uint8_t *buf, size_t size) override;
  size_t write_P(PGM_P buf, size_t size) override;
  int available() override;
  int read() override { uint8_t b; return read(&b, 1) == 1 ? b : -1; }
  int read(uint8_t *buf, size_t size) override;
  int peek() override;
  size_t peekBytes(uint8_t *buffer, size_t length) override;
  void flush() override { WiFiClient::flush(); }
  void stop() override { close(); }
  uint8_t connected() override;
  operator bool() override { return connected(); }
  // No zero-copy peek API: the bytes of the TCP connection are encrypted.
  bool hasPeekBufferAPI() const override { return false; }
  size_t peekAvailable() override { return 0; }
  const char *peekBuffer() override { return nullptr; }
  void peekConsume(size_t) override {}
  int availableForWrite() override { return ready_ ? 1024 : 0; }
  bool outputCanTimeout() override { return connected(); }
  bool inputCanTimeout() override { return connected(); }

 private:
  int open(const String &host, IPAddress ip, uint16_t port, bool byName);
  void close();
  void noteRecord();
  int fill();  // decrypts the next bytes into the peek byte when none are waiting
  static int sendRaw(void *self, const unsigned char *buf, size_t len);
  static int receiveRaw(void *self, unsigned char *buf, size_t len);
  const uint8_t *anchor_; size_t anchorLength_; const uint8_t *pin_;
  bool ready_ = false, closed_ = false, thunk_ = false;  // thunk_: holds the separate stack
  int peeked_ = -1;
  // The Mbed TLS contexts (about 1.5 kB) only while connecting or connected: HTTPClient keeps the unconnected
  // original of its clone, which then holds no contexts.
  struct Session { mbedtls_ssl_context ssl; mbedtls_ssl_config conf; mbedtls_x509_crt ca; };
  Session *session_ = nullptr;
};
#endif
