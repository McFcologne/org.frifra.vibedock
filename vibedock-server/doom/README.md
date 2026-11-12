# Doom for the Pico 2 W with the Pico-LCD-1.3

The administration builds the Doom firmware for the Pico-LCD-1.3 (board `rpi-pico2w-lcd-13`) when it is downloaded: it
fetches the shareware `DOOM1.WAD` from the source named in the download dialog, converts it with `whd_gen` and appends
the result to the program in `src/main/resources/doom/doom-pico2w-lcd-13-code.uf2`. No WAD data is part of this
repository or of a release.

* `doom-pico2w-lcd-13-code.uf2`: the program only (RP2350, flash 0x10000000..0x10037000), built from the Doom port named
  below with the build flags `VIBEDOCK_DOS_BOOT`, `VIBEDOCK_NO_SOUND`, `VIBEDOCK_STATIC_ZONE`. The matching source (GPL v2)
  is the `doom-pico` release of this repository.
* `whd-gen/`: the WAD converter from the same source (`src/whd_gen` of [RP2040 Doom](https://github.com/kilograham/rp2040-doom),
  BSD-3-Clause by Graham Sanderson, with `lodepng` (zlib) and `adpcm-xq` (BSD, `license.txt`)); `COPYING.md` is the
  license of the Doom sources it includes headers from (GPL v2). The Docker build compiles it (`make`) and the server calls it
  as `/app/doom/whd_gen` (`vibedock.doom.whd-gen`).
* Flash layout of the result: program from 0x10000000, the converted WAD at 0x10041000 (`TINY_WAD_ADDR`).

*Doom* is a trademark of id Software. The shareware episode may be passed on freely, but its license does not allow
changed versions; therefore the conversion happens on the machine of the person who downloads.
