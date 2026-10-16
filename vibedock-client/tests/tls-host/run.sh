#!/bin/sh
# Checks the M1 firmware's TLS 1.3 setup on the computer: builds Mbed TLS from the PlatformIO dependency with
# firmware/include/vibedock_mbedtls_config.h and runs client.c against local `openssl s_server -tls1_3` with an RSA and
# an ECDSA certificate, a server that prefers AES-256 (the client must still get AES-128) and a wrong host name (must be rejected).
# Needs a C compiler, OpenSSL 1.1.1 or newer and one firmware build before (vibedock-client/scripts/build.sh), which
# downloads Mbed TLS. Work files go to a temporary directory.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
firmware="$here/../../firmware"
mbedtls="$firmware/.pio/libdeps/vibedock-m1/mbedtls"
[ -d "$mbedtls/library" ] || { echo "Mbed TLS not found: build the vibedock-m1 firmware once first" >&2; exit 1; }
work=$(mktemp -d); servers=""
trap 'kill $servers 2>/dev/null || true; rm -rf "$work"' EXIT
cd "$work"
# The firmware configuration plus what the test needs on the computer (sockets, files, error texts).
{ cat "$firmware/include/vibedock_mbedtls_config.h"
  printf '#define MBEDTLS_NET_C\n#define MBEDTLS_FS_IO\n#define MBEDTLS_ERROR_C\n'; } > config.h
cc -O1 -w -I. -I"$mbedtls/include" -I"$mbedtls/library" -DMBEDTLS_CONFIG_FILE='"config.h"' \
    "$mbedtls"/library/*.c "$here/hooks.c" "$here/client.c" -Wl,--wrap=mbedtls_x509_crt_parse_der -o client
openssl req -x509 -newkey rsa:2048 -nodes -keyout root.key -out root.pem -days 2 -subj /CN=Test-Root \
    -addext basicConstraints=critical,CA:true 2>/dev/null
openssl x509 -in root.pem -outform der -out root.der
printf 'subjectAltName=DNS:home.example.org\n' > san
openssl genrsa -out rsa.key 2048 2>/dev/null
openssl ecparam -name prime256v1 -genkey -noout -out ec.key
for key in rsa ec; do
    openssl req -new -key $key.key -subj /CN=home.example.org -out $key.csr
    openssl x509 -req -in $key.csr -CA root.pem -CAkey root.key -CAcreateserial -days 2 -extfile san -out $key.pem 2>/dev/null
done
openssl s_server -tls1_3 -accept 14431 -cert rsa.pem -key rsa.key -www -quiet >/dev/null 2>&1 &
servers="$servers $!"
openssl s_server -tls1_3 -accept 14432 -cert ec.pem -key ec.key -www -quiet >/dev/null 2>&1 &
servers="$servers $!"
openssl s_server -tls1_3 -ciphersuites TLS_AES_256_GCM_SHA384:TLS_AES_128_GCM_SHA256 -serverpref -accept 14433 -cert rsa.pem -key rsa.key -www -quiet >/dev/null 2>&1 &
servers="$servers $!"
sleep 1
failed=0
check() { name=$1; shift; if ./client "$@"; then echo "  ok: $name"; else echo "  FAILED: $name"; failed=1; fi; }
check "RSA certificate" root.der home.example.org 14431
check "ECDSA certificate" root.der home.example.org 14432
check "server preferring AES-256" root.der home.example.org 14433
if ./client root.der other.example.org 14431 >/dev/null; then echo "  FAILED: wrong host name was accepted"; failed=1
else echo "  ok: wrong host name rejected"; fi
exit $failed
