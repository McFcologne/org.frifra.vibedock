// TLS 1.3 client with the firmware's Mbed TLS configuration and the settings of src/tls13.cpp (cipher suites,
// signature algorithms, freeing the certificates after the handshake). Usage: client <root.der> <host> <port>.
// Connects to 127.0.0.1:<port>, checks the chain against the root and the host name, sends a request of about
// 2 kB (several records with the 1 kB send buffer) and reads the answer to the end.
#include <stdio.h>
#include <string.h>
#include <mbedtls/ssl.h>
#include <mbedtls/net_sockets.h>
#include <mbedtls/psa_util.h>
#include <mbedtls/error.h>
#include <mbedtls/platform.h>
#include <psa/crypto.h>

int main(int argc, char **argv) {
  if (argc != 4) { fprintf(stderr, "usage: %s root.der host port\n", argv[0]); return 2; }
  psa_crypto_init();
  mbedtls_net_context net; mbedtls_ssl_context ssl; mbedtls_ssl_config conf; mbedtls_x509_crt ca;
  mbedtls_net_init(&net); mbedtls_ssl_init(&ssl); mbedtls_ssl_config_init(&conf); mbedtls_x509_crt_init(&ca);
  // As the firmware: the root is parsed without a copy, so its bytes must stay.
  static unsigned char der[8192]; FILE *f = fopen(argv[1], "rb");
  size_t derLength = f ? fread(der, 1, sizeof(der), f) : 0; if (f) fclose(f);
  int r = mbedtls_x509_crt_parse_der_nocopy(&ca, der, derLength);
  if (r) { printf("root: -0x%04x\n", -r); return 1; }
  mbedtls_ssl_config_defaults(&conf, MBEDTLS_SSL_IS_CLIENT, MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT);
  mbedtls_ssl_conf_min_tls_version(&conf, MBEDTLS_SSL_VERSION_TLS1_3);
  mbedtls_ssl_conf_max_tls_version(&conf, MBEDTLS_SSL_VERSION_TLS1_3);
  mbedtls_ssl_conf_authmode(&conf, MBEDTLS_SSL_VERIFY_REQUIRED);
  mbedtls_ssl_conf_ca_chain(&conf, &ca, NULL);
  mbedtls_ssl_conf_rng(&conf, mbedtls_psa_get_random, MBEDTLS_PSA_RANDOM_STATE);
  static const int suites[] = {MBEDTLS_TLS1_3_AES_128_GCM_SHA256, 0};
  static const uint16_t signatures[] = {MBEDTLS_TLS1_3_SIG_ECDSA_SECP256R1_SHA256, MBEDTLS_TLS1_3_SIG_RSA_PSS_RSAE_SHA256,
      MBEDTLS_TLS1_3_SIG_ECDSA_SECP384R1_SHA384, MBEDTLS_TLS1_3_SIG_RSA_PSS_RSAE_SHA384,
      MBEDTLS_TLS1_3_SIG_RSA_PKCS1_SHA256, MBEDTLS_TLS1_3_SIG_RSA_PKCS1_SHA384, MBEDTLS_TLS1_3_SIG_NONE};
  mbedtls_ssl_conf_ciphersuites(&conf, suites);
  mbedtls_ssl_conf_sig_algs(&conf, signatures);
  mbedtls_ssl_setup(&ssl, &conf);
  mbedtls_ssl_set_hostname(&ssl, argv[2]);
  if ((r = mbedtls_net_connect(&net, "127.0.0.1", argv[3], MBEDTLS_NET_PROTO_TCP))) { printf("tcp: -0x%04x\n", -r); return 1; }
  mbedtls_ssl_set_bio(&ssl, &net, mbedtls_net_send, mbedtls_net_recv, NULL);
  while ((r = mbedtls_ssl_handshake(&ssl)) == MBEDTLS_ERR_SSL_WANT_READ || r == MBEDTLS_ERR_SSL_WANT_WRITE) {}
  char text[128]; mbedtls_strerror(r, text, sizeof(text));
  if (r) { printf("handshake failed: -0x%04x %s (verify flags 0x%x)\n", -r, text, mbedtls_ssl_get_verify_result(&ssl)); return 1; }
  printf("handshake ok: %s\n", mbedtls_ssl_get_ciphersuite(&ssl));
  // As the firmware: the server's certificates and the root are not needed after the handshake.
  mbedtls_ssl_session *session = ssl.MBEDTLS_PRIVATE(session);
  if (session && session->MBEDTLS_PRIVATE(peer_cert)) {
    mbedtls_x509_crt_free(session->MBEDTLS_PRIVATE(peer_cert)); mbedtls_free(session->MBEDTLS_PRIVATE(peer_cert));
    session->MBEDTLS_PRIVATE(peer_cert) = NULL;
  }
  mbedtls_x509_crt_free(&ca);
  char request[3000];
  int length = snprintf(request, sizeof(request), "GET / HTTP/1.0\r\nX-Padding: %02000d\r\n\r\n", 0), done = 0;
  while (done < length) {
    int sent = mbedtls_ssl_write(&ssl, (const unsigned char *)request + done, (size_t)(length - done));
    if (sent > 0) done += sent;
    else if (sent != MBEDTLS_ERR_SSL_WANT_WRITE) { printf("write: -0x%04x\n", -sent); return 1; }
  }
  unsigned char buffer[512]; int received = 0, got;
  while ((got = mbedtls_ssl_read(&ssl, buffer, sizeof(buffer))) > 0 || got == MBEDTLS_ERR_SSL_WANT_READ) if (got > 0) received += got;
  printf("sent %d bytes, received %d bytes, %s\n", length, received,
         got == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY || got == 0 ? "closed by the server" : "read error");
  return got == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY || got == 0 ? 0 : 1;
}
