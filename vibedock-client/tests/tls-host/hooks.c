// The two hooks the firmware's Mbed TLS configuration expects (the firmware has them in src/tls13.cpp).
#include <stdlib.h>
#include <time.h>
#include <mbedtls/platform_time.h>

int mbedtls_hardware_poll(void *data, unsigned char *output, size_t length, size_t *written) {
  (void)data;
  for (size_t i = 0; i < length; i++) output[i] = (unsigned char)rand();  // test only: not a secure source
  *written = length;
  return 0;
}

mbedtls_ms_time_t mbedtls_ms_time(void) {
  struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
  return (mbedtls_ms_time_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

// As the firmware (linked with --wrap): the server's certificates are parsed without a copy.
#include <mbedtls/x509_crt.h>
int __wrap_mbedtls_x509_crt_parse_der(mbedtls_x509_crt *chain, const unsigned char *buf, size_t length) {
  return mbedtls_x509_crt_parse_der_nocopy(chain, buf, length);
}
