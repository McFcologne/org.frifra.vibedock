#!/usr/bin/env python3
"""Turns the firmware builds (scripts/images.sh, one directory per board) into the files of a client release.

usage: release-index.py BUILD_DIR OUT_DIR VERSION

OUT_DIR gets, per board, vibedock-<board>-<version>.bin (the USB image flash.bin), .app.bin (firmware.bin, the update
over Wi-Fi) and .manifest.json, and index.json: the one file a server needs to find and check the release. It names
the client version, the protocol version the firmware speaks (from the heading of CLIENT-PROTOCOL.md) and for every
board the three files with SHA-256 and size. The hashes are checked against the manifest first, a mismatch stops the
release."""
import hashlib, json, os, re, shutil, sys

PROTOCOL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "vibedock-server", "CLIENT-PROTOCOL.md")
SCHEMA = 1


def sha256(path):
    with open(path, "rb") as f: return hashlib.sha256(f.read()).hexdigest()


def protocol_version(path=PROTOCOL):
    with open(path, encoding="utf-8") as f: match = re.search(r"^# VibeDock image client protocol, version (\d+)\s*$", f.read(), re.M)
    if not match: raise ValueError("no protocol version in the heading of " + path)
    return int(match.group(1))


def entry(directory, out, name, version, source, suffix, expected=None):
    target = "%s-%s%s" % (name, version, suffix)
    path = os.path.join(directory, source)
    digest = sha256(path)
    if expected is not None and digest != expected: raise ValueError("%s: %s does not match the manifest" % (directory, source))
    shutil.copyfile(path, os.path.join(out, target))
    return {"file": target, "sha256": digest, "size": os.path.getsize(path)}


def build(build_dir, out_dir, version, protocol=None):
    if not re.fullmatch(r"[0-9A-Za-z.+-]{1,40}", version): raise ValueError("not a version: " + version)
    os.makedirs(out_dir, exist_ok=True)
    boards = []
    for board in sorted(os.listdir(build_dir)):
        directory = os.path.join(build_dir, board)
        if not os.path.isfile(os.path.join(directory, "manifest.json")): continue
        with open(os.path.join(directory, "manifest.json"), encoding="utf-8") as f: manifest = json.load(f)
        if manifest.get("id") != board: raise ValueError("%s: the manifest says id %s" % (board, manifest.get("id")))
        if manifest.get("version") != version: raise ValueError("%s: the manifest says version %s, not %s" % (board, manifest.get("version"), version))
        name = board if board.startswith("vibedock-") else "vibedock-" + board
        hashes = manifest["firmware"]
        boards.append({
            "id": board, "label": manifest["label"], "chip": manifest["chip"], "hardware": manifest["hardware"],
            "manifest": entry(directory, out_dir, name, version, "manifest.json", ".manifest.json"),
            "flash": entry(directory, out_dir, name, version, "flash.bin", ".bin", hashes.get("flashSha256")),
            "app": entry(directory, out_dir, name, version, "firmware.bin", ".app.bin", hashes["sha256"]),
        })
    if not boards: raise ValueError("no board with a manifest in " + build_dir)
    index = {"schema": SCHEMA, "client": version, "protocol": protocol if protocol is not None else protocol_version(), "boards": boards}
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, indent=2); f.write("\n")
    return index


if __name__ == "__main__":
    if len(sys.argv) != 4: sys.exit(__doc__)
    result = build(*sys.argv[1:4])
    print("%d boards, client %s, protocol %d" % (len(result["boards"]), result["client"], result["protocol"]))
