# Adapt the official Mbed TLS LTS archive to PlatformIO without a stale registry repackaging.
# Runs after dependency installation, before library discovery. Only the local build copy is changed.
import hashlib
import json
import os
Import("env")
root = os.path.join(env.subst("$PROJECT_LIBDEPS_DIR"), env.subst("$PIOENV"), "mbedtls")
if not os.path.isfile(os.path.join(root, "include", "mbedtls", "version.h")):
    raise RuntimeError("Mbed TLS archive is missing its headers")
with open(os.path.join(root, "include", "mbedtls", "build_info.h"), encoding="utf-8") as f:
    version = f.read()
if '#define MBEDTLS_VERSION_PATCH  7' not in version or '#define MBEDTLS_VERSION_MINOR  6' not in version or '#define MBEDTLS_VERSION_MAJOR  3' not in version:
    raise RuntimeError("Expected the pinned Mbed TLS 3.6.7 sources")
# The tag archive is not byte-stable and a tag can move: the files that get built must be the released ones. SHA-256
# over path and content of every file below include/ and library/ (sorted), the same as in the official release
# archive mbedtls-3.6.7.tar.bz2 (SHA-256 a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6).
TREE = "095deeadec6b08f225c8431b1b8228caf7a8f19f561452707f218978511719dd"
def tree(base):
    digest = hashlib.sha256()
    for part in ("include", "library"):
        for directory, _, files in sorted(os.walk(os.path.join(base, part))):
            for name in sorted(files):
                path = os.path.join(directory, name)
                digest.update(os.path.relpath(path, base).replace(os.sep, "/").encode() + b"\0")
                with open(path, "rb") as f:
                    digest.update(f.read())
                digest.update(b"\0")
    return digest.hexdigest()
if tree(root) != TREE:
    raise RuntimeError("The Mbed TLS sources differ from the 3.6.7 release; delete .pio/libdeps/%s/mbedtls and build again" % env.subst("$PIOENV"))
manifest = json.dumps({"name": "mbedtls", "version": "3.6.7", "license": "Apache-2.0",
                       "build": {"srcDir": "library", "includeDir": "include"}})
path = os.path.join(root, "library.json")
previous = None
if os.path.isfile(path):
    with open(path, encoding="utf-8") as f:
        previous = f.read()
if previous != manifest:
    with open(path, "w", encoding="utf-8") as f:
        f.write(manifest)
