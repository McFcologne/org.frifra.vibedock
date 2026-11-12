# Writes manifest.json next to firmware.bin and flash.bin: what the build is (board, display, capabilities, how it was
# built) and the SHA-256 of both images, in the form the server's Manifest.java reads. It runs
#   - as a PlatformIO post script (extra_scripts = post:manifest.py, after images.py), for the environment being built,
#   - on the command line, for tests and for people:
#       manifest.py --env ID --build-dir DIR [--ini platformio.ini] [--version V]   write DIR/manifest.json
#       manifest.py --to-ini manifest.json                                           print the [env:ID] of a manifest
#       manifest.py --update manifest.json [--build-dir DIR]                         recalculate checksum (and hashes)
# Everything comes from the environment's custom_* keys and its flags; nobody writes a manifest by hand. The canonical
# text and the checksum are the same as Manifest.canonical in the server (compact JSON, keys sorted, numbers without
# trailing zeros, the checksum field left out), so the server accepts what this script wrote.
#
# custom_label, custom_chip, custom_display (WxH) are required; the others have defaults:
#   custom_hardware    tested | prepared (default prepared)
#   custom_corner      radius of the panel's corners in pixels (default 0)
#   custom_grey        true for a black and white panel (default: the VIBEDOCK_EPAPER flag)
#   custom_touch       true/false (default: any VIBEDOCK_TOUCH_* flag)
#   custom_battery     true/false (default: the VIBEDOCK_BATTERY flag)
#   custom_rotations   e.g. "0 180" (default: 0 90 180 270 on a square panel, else 0 180)
#   custom_power_saving true/false (default false)
#   custom_case_margin "top right bottom left" as multiples of the screen width; custom_case_rim, custom_case_radius,
#   custom_case_colour (#rrggbb): the generic case, only with custom_case_margin
import configparser, hashlib, json, os, re, sys
from decimal import Decimal

SCHEMA = 1
CHIPS = {"ESP8266", "ESP32", "ESP32-S2", "ESP32-S3", "ESP32-C3", "ESP32-C6", "ESP32-H2", "RP2350"}
FLAG = re.compile(r"-D [A-Z][A-Z0-9_]{0,63}(=[A-Za-z0-9_.+-]{0,60})?$")
LIBRARY = re.compile(r"[A-Za-z0-9_.-]{1,50}(/[A-Za-z0-9_.-]{1,50})?@[0-9][0-9A-Za-z.+-]{0,20}$")
MAX_FLAGS, MAX_LIBRARIES = 80, 20


# ---- canonical JSON, the same as Manifest.canonical ----
def _number(value):
    d = Decimal(str(value)).normalize() if not isinstance(value, Decimal) else value.normalize()
    return "0" if d == 0 else format(d, "f")

def _string(text):
    out = ['"']
    for ch in text:
        if ch == '"': out.append('\\"')
        elif ch == "\\": out.append("\\\\")
        elif ord(ch) < 0x20: out.append("\\u%04x" % ord(ch))
        else: out.append(ch)
    out.append('"')
    return "".join(out)

def canonical(node, top=True):
    if isinstance(node, dict):
        names = sorted(k for k in node if not (top and k == "checksum"))
        return "{" + ",".join(_string(k) + ":" + canonical(node[k], False) for k in names) + "}"
    if isinstance(node, list):
        return "[" + ",".join(canonical(v, False) for v in node) + "]"
    if isinstance(node, bool): return "true" if node else "false"
    if node is None: return "null"
    if isinstance(node, (int, float, Decimal)): return _number(node)
    return _string(node)

def sha256(data):
    return hashlib.sha256(data if isinstance(data, bytes) else data.encode("utf-8")).hexdigest()

def _pretty(node, indent=0):
    pad, inner = "  " * indent, "  " * (indent + 1)
    if isinstance(node, dict):
        if not node: return "{}"
        return "{\n" + ",\n".join(inner + _string(k) + ": " + _pretty(node[k], indent + 1) for k in sorted(node)) + "\n" + pad + "}"
    if isinstance(node, list):
        if all(not isinstance(v, (dict, list)) for v in node) and len(canonical(node)) < 60:
            return canonical(node).replace(",", ", ")
        return "[\n" + ",\n".join(inner + _pretty(v, indent + 1) for v in node) + "\n" + pad + "]"
    return canonical(node, False)

def write(manifest, path):
    manifest = dict(manifest); manifest.pop("checksum", None)
    manifest["checksum"] = sha256(canonical(manifest))
    with open(path, "w", encoding="utf-8") as f:
        f.write(_pretty(manifest) + "\n")
    return manifest


# ---- platformio.ini: the options of an environment as PlatformIO resolves them (extends, ${section.option}) ----
class Ini:
    def __init__(self, path):
        self.config = configparser.RawConfigParser(interpolation=None, strict=False, inline_comment_prefixes=None)
        self.config.optionxform = str
        with open(path, encoding="utf-8") as f:
            self.config.read_file(f)

    def raw(self, section, option):
        """The option of the section, else of the sections it extends."""
        seen = set()
        while section and section not in seen and self.config.has_section(section):
            seen.add(section)
            if self.config.has_option(section, option):
                return self.config.get(section, option)
            parent = self.config.get(section, "extends") if self.config.has_option(section, "extends") else None
            section = (parent or "").split(",")[0].strip() or None
            if section and not self.config.has_section(section) and self.config.has_section("env:" + section):
                section = "env:" + section
        return None

    def value(self, section, option, default=None, depth=0):
        raw = self.raw(section, option)
        if raw is None: return default
        if depth > 8: raise ValueError("platformio.ini: ${...} nested too deeply in " + section)
        def replace(match):
            name, key = match.group(1), match.group(2)
            found = self.value(name, key, None, depth + 1)
            return "" if found is None else found
        return re.sub(r"\$\{([^.}]+)\.([^}]+)\}", replace, raw)

    def parent(self, section):
        parent = self.config.get(section, "extends").split(",")[0].strip() if self.config.has_option(section, "extends") else None
        return parent or None

    def lines(self, section, option):
        return [l.strip() for l in (self.value(section, option, "") or "").splitlines() if l.strip() and not l.strip().startswith(";")]

    def flags(self, section):
        flags = []
        for line in self.lines(section, "build_flags"):
            m = re.match(r"-D\s*(\S+)$", line)
            if m: flags.append("-D " + m.group(1))
        return flags

    def environments(self):
        return [s[4:] for s in self.config.sections() if s.startswith("env:")]


def _subtract(items, base):
    rest = list(base); out = []
    for item in items:
        if item in rest: rest.remove(item)
        else: out.append(item)
    return out

def _flag(value, default):
    return default if value is None or not value.strip() else value.strip().lower() in ("1", "true", "yes")


def build(ini, env_name, build_dir, version=None):
    """The manifest (without checksum) of an environment, with the hashes of the images in build_dir."""
    section = "env:" + env_name
    if not ini.config.has_section(section): raise ValueError("No environment " + env_name)
    def option(key, default=None): return (ini.value(section, key, default) or default)
    def required(key):
        value = ini.value(section, key)
        if not value or not value.strip(): raise ValueError("%s: %s is missing" % (env_name, key))
        return value.strip()
    flags = ini.flags(section)
    names = {f[3:].split("=")[0] for f in flags}
    size = re.match(r"(\d+)x(\d+)$", required("custom_display"))
    if not size: raise ValueError(env_name + ": custom_display is WIDTHxHEIGHT")
    width, height = int(size.group(1)), int(size.group(2))
    chip = required("custom_chip")
    if chip not in CHIPS: raise ValueError("%s: unknown chip %s" % (env_name, chip))
    rotations = (ini.value(section, "custom_rotations") or "").split()
    rotations = [int(r) for r in rotations] if rotations else ([0, 90, 180, 270] if width == height else [0, 180])
    firmware_path = os.path.join(build_dir, "firmware.bin")
    flash_path = os.path.join(build_dir, "flash.bin")
    with open(firmware_path, "rb") as f: firmware_bin = f.read()
    firmware = {"sha256": sha256(firmware_bin)}
    if os.path.isfile(flash_path):
        with open(flash_path, "rb") as f: firmware["flashSha256"] = sha256(f.read())
    if version is None:
        with open(os.path.join(build_dir, "version"), encoding="utf-8") as f: version = f.read().strip()
    manifest = {
        "schema": SCHEMA, "id": env_name, "label": required("custom_label"), "chip": chip, "version": version,
        "hardware": (ini.value(section, "custom_hardware") or "prepared").strip(), "firmware": firmware,
        "display": {"width": width, "height": height, "corner": int(ini.value(section, "custom_corner") or 0),
                    "grey": _flag(ini.value(section, "custom_grey"), "VIBEDOCK_EPAPER" in names)},
        "capabilities": {
            "touch": _flag(ini.value(section, "custom_touch"), any(n.startswith("VIBEDOCK_TOUCH_") for n in names)),
            "battery": _flag(ini.value(section, "custom_battery"), "VIBEDOCK_BATTERY" in names),
            "rotations": rotations, "powerSaving": _flag(ini.value(section, "custom_power_saving"), False)},
    }
    margin = (ini.value(section, "custom_case_margin") or "").split()
    if margin:
        case = {"kind": "generic", "margin": [Decimal(m) for m in margin]}
        for key, field in (("custom_case_rim", "rim"), ("custom_case_radius", "radius")):
            if ini.value(section, key): case[field] = Decimal(ini.value(section, key).strip())
        if ini.value(section, "custom_case_colour"): case["colour"] = ini.value(section, "custom_case_colour").strip()
        manifest["case"] = case
    parent = ini.parent(section)
    parent_section = parent if parent and ini.config.has_section(parent) else ("env:" + parent if parent else None)
    base_flags = ini.flags(parent_section) if parent_section else []
    base_libs = ini.lines(parent_section, "lib_deps") if parent_section else []
    extra_flags = [f for f in _subtract(flags, base_flags) if FLAG.match(f)]
    libraries = [l for l in _subtract(ini.lines(section, "lib_deps"), base_libs) if LIBRARY.match(l)]
    if len(extra_flags) > MAX_FLAGS or len(libraries) > MAX_LIBRARIES:
        raise ValueError(env_name + ": more flags or libraries than a manifest holds")
    build_section = {"platformioBoard": (ini.value(section, "board") or "").strip(), "flags": extra_flags, "libraries": libraries}
    if parent: build_section["extends"] = parent
    manifest["build"] = build_section
    return manifest


def to_ini(manifest):
    """The [env:ID] section a manifest describes, to be added to platformio.ini (the base environment must exist there)."""
    b, d, c, cs = manifest.get("build", {}), manifest["display"], manifest.get("capabilities", {}), manifest.get("case")
    lines = ["[env:%s]" % manifest["id"], "custom_hardware = " + manifest.get("hardware", "prepared"),
             "custom_label = " + manifest["label"], "custom_chip = " + manifest["chip"], "custom_display = %dx%d" % (d["width"], d["height"])]
    if d.get("corner"): lines.append("custom_corner = %d" % d["corner"])
    lines.append("custom_grey = " + ("true" if d.get("grey") else "false"))
    lines.append("custom_touch = " + ("true" if c.get("touch") else "false"))
    lines.append("custom_battery = " + ("true" if c.get("battery") else "false"))
    lines.append("custom_rotations = " + " ".join(str(r) for r in c.get("rotations", [0])))
    lines.append("custom_power_saving = " + ("true" if c.get("powerSaving") else "false"))
    if cs:
        lines.append("custom_case_margin = " + " ".join(_number(m) for m in cs["margin"]))
        for key, field in (("custom_case_rim", "rim"), ("custom_case_radius", "radius"), ("custom_case_colour", "colour")):
            if field in cs: lines.append("%s = %s" % (key, _number(cs[field]) if field != "colour" else cs[field]))
    if b.get("extends"): lines.append("extends = " + b["extends"])
    if b.get("platformioBoard"): lines.append("board = " + b["platformioBoard"])
    lines.append("build_flags =")
    lines.append("  ${%s.build_flags}" % b["extends"] if b.get("extends") else "  ${common.build_flags}")
    lines.append('  -D VIBEDOCK_BOARD=\\"%s\\"' % manifest["id"])
    lines += ["  " + f for f in b.get("flags", [])]
    if b.get("libraries"):
        lines.append("lib_deps =")
        lines.append("  ${%s.lib_deps}" % b["extends"] if b.get("extends") else "  ${common.lib_deps}")
        lines += ["  " + l for l in b["libraries"]]
    return "\n".join(lines) + "\n"


def update(path, build_dir=None):
    """Recalculates the checksum (and, with build_dir, the hashes) after a change by hand."""
    with open(path, encoding="utf-8") as f: manifest = json.load(f, parse_float=Decimal)
    if build_dir:
        with open(os.path.join(build_dir, "firmware.bin"), "rb") as f: manifest["firmware"]["sha256"] = sha256(f.read())
        flash = os.path.join(build_dir, "flash.bin")
        if os.path.isfile(flash):
            with open(flash, "rb") as f: manifest["firmware"]["flashSha256"] = sha256(f.read())
    return write(manifest, path)


def main(argv):
    args = dict(zip(argv[::2], argv[1::2]))
    if "--to-ini" in args:
        with open(args["--to-ini"], encoding="utf-8") as f: sys.stdout.write(to_ini(json.load(f, parse_float=Decimal)))
    elif "--update" in args:
        update(args["--update"], args.get("--build-dir"))
    elif "--env" in args and "--build-dir" in args:
        ini = Ini(args.get("--ini", os.path.join(os.path.dirname(os.path.abspath(__file__)), "platformio.ini")))
        write(build(ini, args["--env"], args["--build-dir"], args.get("--version")), os.path.join(args["--build-dir"], "manifest.json"))
    else:
        sys.exit("usage: manifest.py --env ID --build-dir DIR | --to-ini FILE | --update FILE")


try:
    Import("env")  # PlatformIO
except NameError:
    if __name__ == "__main__": main(sys.argv[1:])
else:
    def _write(source, target, env):
        build_dir = env.subst("$BUILD_DIR")
        ini = Ini(os.path.join(env.subst("$PROJECT_DIR"), "platformio.ini"))
        manifest = write(build(ini, env.subst("$PIOENV"), build_dir), os.path.join(build_dir, "manifest.json"))
        print("Manifest %s %s written (checksum %s)" % (manifest["id"], manifest["version"], manifest["checksum"][:12]))
    env.AddPostAction("$BUILD_DIR/${PROGNAME}.bin", _write)  # registered after images.py's: flash.bin exists by now
