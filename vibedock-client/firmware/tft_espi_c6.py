# PlatformIO pre-script for ESP32-C6 boards: TFT_eSPI 2.5.43 has no driver for the C6. Its ESP32 drivers write the SPI
# registers directly and stay dark there; the installed copy (.pio/libdeps/<env>/TFT_eSPI) takes its generic driver on
# the C6 instead, which sends through the Arduino SPI library. Runs on every build and changes the files only once.
import os
Import("env")

MARK = "TFT_C6_GENERIC"
lib = os.path.join(env.subst("$PROJECT_LIBDEPS_DIR"), env.subst("$PIOENV"), "TFT_eSPI")


def patch(name, old, new):
    path = os.path.join(lib, name)
    with open(path, encoding="utf-8") as f:
        text = f.read()
    if MARK in text:
        return
    if old not in text:
        raise RuntimeError("tft_espi_c6.py: unexpected " + name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text.replace(old, new, 1))


patch("TFT_eSPI.h", "#if defined(CONFIG_IDF_TARGET_ESP32S3)\n  #include \"Processors/TFT_eSPI_ESP32_S3.h\"",
      "#if defined(CONFIG_IDF_TARGET_ESP32C6) // " + MARK + " (tft_espi_c6.py)\n  #include \"Processors/TFT_eSPI_Generic.h\"\n"
      "#elif defined(CONFIG_IDF_TARGET_ESP32S3)\n  #include \"Processors/TFT_eSPI_ESP32_S3.h\"")
patch("TFT_eSPI.cpp", "#if defined (ESP32)\n  #if defined(CONFIG_IDF_TARGET_ESP32S3)",
      "#if defined(CONFIG_IDF_TARGET_ESP32C6) // " + MARK + " (tft_espi_c6.py)\n  #include \"Processors/TFT_eSPI_Generic.c\"\n"
      "#elif defined (ESP32)\n  #if defined(CONFIG_IDF_TARGET_ESP32S3)")
