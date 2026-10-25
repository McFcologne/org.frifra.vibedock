#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
"${MVN_BIN:-mvn}" verify
node --test scripts/license-inventory.test.mjs scripts/serial-console.test.mjs scripts/firmware-flasher.test.mjs
npm --prefix e2e ci
npm --prefix e2e run install:browser -- "$@"
npm --prefix e2e test
