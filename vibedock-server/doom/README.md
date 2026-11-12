# Doom for the Pico 2 W with the Pico-LCD-1.3

The administration builds the Doom firmware for the Pico-LCD-1.3 (board `rpi-pico2w-lcd-13`) when it is downloaded: it
fetches the shareware `DOOM1.WAD` from the first reachable source of the list shown in the download dialog (`vibedock.doom.wad-urls`; a file `doom/sources.txt` in the data directory, one address per line, replaces the list on the fly), converts it with `whd_gen` and appends
the result to the program in `src/main/resources/doom/doom-pico2w-lcd-13-code.uf2`. No WAD data is part of this
repository or of a release.

* `doom-pico2w-lcd-13-code.uf2`: the program only (RP2350, flash 0x10000000..0x10037000), built from the Doom port named
  below with the build flags `VIBEDOCK_DOS_BOOT`, `VIBEDOCK_NO_SOUND`, `VIBEDOCK_STATIC_ZONE`. The matching source (GPL v2)
  is the `doom-pico-2` release of this repository (asset `vibedock-doom-pico2w-lcd-13-source.zip`, with `VIBEDOCK-PORT.md`: the
  tools, versions and the exact commands of the build). SHA-256 of the program:
  `b2311c79743d52332abe27c889a1045ecf8e53f4a4f919da7c3805fe5717941d`; the build from that source reproduces it byte for byte.
* `whd-gen/`: the WAD converter from the same source (`src/whd_gen` of [RP2040 Doom](https://github.com/kilograham/rp2040-doom),
  BSD-3-Clause by Graham Sanderson, with `lodepng` (zlib) and `adpcm-xq` (BSD, `license.txt`)); `COPYING.md` is the
  license of the Doom sources it includes headers from (GPL v2). The Docker build compiles it (`make`) and the server calls it
  as `/app/doom/whd_gen` (`vibedock.doom.whd-gen`).
* Flash layout of the result: program from 0x10000000, the converted WAD at 0x10041000 (`TINY_WAD_ADDR`).

*Doom* is a trademark of id Software. The shareware episode may be passed on freely, but its license does not allow
changed versions; therefore the conversion happens on the machine of the person who downloads.
