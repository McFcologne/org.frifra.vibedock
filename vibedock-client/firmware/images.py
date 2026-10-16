# PlatformIO post-script: next to firmware.bin (the app, used for updates over Wi-Fi) it writes flash.bin, the image
# installed over USB at address 0x0. On the ESP8266 that is the same file; on ESP32 chips it is bootloader, partition
# table and app merged into one image (esptool merge_bin), so the browser flasher writes a single file at 0x0.
import os, shutil
Import("env")

def merge(source, target, env):
    build = env.subst("$BUILD_DIR")
    app = os.path.join(build, env.subst("${PROGNAME}.bin"))
    out = os.path.join(build, "flash.bin")
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
