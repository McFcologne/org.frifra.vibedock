# PlatformIO post-script: next to firmware.bin (the app, used for updates over Wi-Fi) it writes flash.bin, the image
# installed over USB at address 0x0. On the ESP8266 that is the same file; on ESP32 chips it is bootloader, partition
# table and app merged into one image (esptool merge_bin), so the browser flasher writes a single file at 0x0.
import os, shutil, subprocess
Import("env")

def verify_audio_excluded(build, app, env):
    definitions = [d[0] if isinstance(d, (list, tuple)) else d for d in env.get("CPPDEFINES", [])]
    if "VIBEDOCK_AUDIO_ES8311" in definitions:
        return
    elf = os.path.join(build, env.subst("${PROGNAME}.elf"))
    # PlatformIO's Espressif builders define CC but not necessarily OBJDUMP.
    tool = env.subst("$OBJDUMP")
    if not tool:
        compiler = env.subst("$CC")
        if not compiler.endswith("gcc"):
            raise RuntimeError("Cannot locate the target objdump for audio-exclusion verification")
        tool = compiler[:-3] + "objdump"
    symbols = subprocess.run([tool, "-t", "-C", elf], env=env["ENV"],
                             check=True, capture_output=True, text=True).stdout
    forbidden = ("audio::", "sound_policy::Quotas", "sound_policy::Connection", "sound_policy::VolumeOverlay", "volumeOverlay")
    if any(name in symbols for name in forbidden):
        raise RuntimeError("Board without audio contains linked audio code or buffers")
    with open(app, "rb") as file:
        image = file.read()
    if any(marker in image for marker in (b"X-VibeDock-Audio", b"X-VibeDock-Quota", b"Audio ready (ES8311)")):
        raise RuntimeError("Board without audio contains audio protocol or driver strings")
    print("Verified: no audio code, buffers or quota headers in this firmware")

def merge(source, target, env):
    build = env.subst("$BUILD_DIR")
    app = os.path.join(build, env.subst("${PROGNAME}.bin"))
    out = os.path.join(build, "flash.bin")
    verify_audio_excluded(build, app, env)
    if env.subst("$PIOPLATFORM") == "espressif8266":
        shutil.copyfile(app, out)
        return
    esptool = os.path.join(env.PioPlatform().get_package_dir("tool-esptoolpy"), "esptool.py")
    parts = []
    for offset, image in env.get("FLASH_EXTRA_IMAGES", []):
        parts += [offset, env.subst(image)]
    parts += [env.subst("$ESP32_APP_OFFSET"), app]
    chip = env.BoardConfig().get("build.mcu")
    command = [env.subst("$PYTHONEXE"), esptool, "--chip", chip, "merge_bin", "-o", out,
               "--flash_mode", "dio", "--flash_size", "keep"] + parts
    env.Execute(" ".join('"%s"' % p for p in command))

env.AddPostAction("$BUILD_DIR/${PROGNAME}.bin", merge)
