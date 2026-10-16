// Mbed TLS 3.6 for the ESP8266 (VibeTV M1): an https client with TLS 1.3, the only https the display speaks there.
// BearSSL in the ESP8266 core has no TLS 1.3. Only what the client needs: ECDHE (X25519, P-256, P-384), AES-GCM,
// SHA-256/384, RSA (PKCS#1 v1.5 and PSS) and ECDSA certificates, chain checked against the root from pairing.
#pragma once

#define MBEDTLS_HAVE_TIME
#define MBEDTLS_HAVE_TIME_DATE
#define MBEDTLS_PLATFORM_C
#define MBEDTLS_PLATFORM_MEMORY           // counted allocations, see tls13.cpp (peak in the device log)
#define MBEDTLS_PLATFORM_MS_TIME_ALT       // millis(), see tls13.cpp
#define MBEDTLS_NO_PLATFORM_ENTROPY
#define MBEDTLS_ENTROPY_C
#define MBEDTLS_ENTROPY_HARDWARE_ALT       // the chip's random number generator, see tls13.cpp
#define MBEDTLS_CTR_DRBG_C
#define MBEDTLS_PSA_CRYPTO_C               // TLS 1.3 in Mbed TLS 3.6 runs on PSA
#define MBEDTLS_PSA_KEY_SLOT_COUNT 8

#define MBEDTLS_SSL_TLS_C
#define MBEDTLS_SSL_CLI_C
#define MBEDTLS_SSL_PROTO_TLS1_3
#define MBEDTLS_SSL_TLS1_3_KEY_EXCHANGE_MODE_EPHEMERAL_ENABLED
#define MBEDTLS_SSL_TLS1_3_COMPATIBILITY_MODE
#define MBEDTLS_SSL_SERVER_NAME_INDICATION
#define MBEDTLS_SSL_KEEP_PEER_CERTIFICATE  // required by TLS 1.3
#define MBEDTLS_SSL_IN_CONTENT_LEN 16384   // servers send full-size records
#define MBEDTLS_SSL_OUT_CONTENT_LEN 1024   // the display only sends small requests (longer ones go in several records)

#define MBEDTLS_X509_USE_C
#define MBEDTLS_X509_CRT_PARSE_C
#define MBEDTLS_ASN1_PARSE_C
#define MBEDTLS_ASN1_WRITE_C
#define MBEDTLS_OID_C
#define MBEDTLS_PK_C
#define MBEDTLS_PK_PARSE_C
#define MBEDTLS_BIGNUM_C
#define MBEDTLS_RSA_C
#define MBEDTLS_PKCS1_V15
#define MBEDTLS_PKCS1_V21
#define MBEDTLS_X509_RSASSA_PSS_SUPPORT    // offers rsa_pss_rsae_*: TLS 1.3 servers with an RSA certificate sign with it
#define MBEDTLS_ECP_C
#define MBEDTLS_ECDH_C
#define MBEDTLS_ECDSA_C
#define MBEDTLS_ECP_DP_SECP256R1_ENABLED
#define MBEDTLS_ECP_DP_SECP384R1_ENABLED
#define MBEDTLS_ECP_DP_CURVE25519_ENABLED
#define MBEDTLS_MD_C
#define MBEDTLS_SHA224_C
#define MBEDTLS_SHA256_C
#define MBEDTLS_SHA384_C
#define MBEDTLS_SHA512_C
#define MBEDTLS_AES_C
#define MBEDTLS_GCM_C
#define MBEDTLS_CIPHER_C
#define MBEDTLS_HKDF_C

// Memory: the ESP8266 has about 40 kB of heap; small tables at the cost of some speed.
#define MBEDTLS_AES_ROM_TABLES
#define MBEDTLS_AES_FEWER_TABLES
#define MBEDTLS_ECP_WINDOW_SIZE 2
#define MBEDTLS_ECP_FIXED_POINT_OPTIM 0
#define MBEDTLS_MPI_WINDOW_SIZE 1
#define MBEDTLS_MPI_MAX_SIZE 512
#define MBEDTLS_SHA256_SMALLER
#define MBEDTLS_SHA512_SMALLER
