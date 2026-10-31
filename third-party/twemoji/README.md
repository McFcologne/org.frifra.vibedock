# Twemoji graphics

The colour emoji of the status images. Java2D cannot draw colour emoji fonts, so the server draws these SVG files
(`vibedock-server/src/main/java/org/frifra/vibedock/server/Twemoji.java`). They are only used on the server; the display firmware does not contain them.

| | |
| --- | --- |
| Project | Twemoji (Twitter Emoji), maintained at <https://github.com/jdecked/twemoji> |
| Version | 17.0.3 (also in `VERSION`) |
| Source | <https://github.com/jdecked/twemoji/archive/refs/tags/v17.0.3.tar.gz> |
| SHA-256 of that archive | `a0855654b633045ae2337537e77f1bb4361162f7fcd910e613eaab1d6d9c5fca` |
| Taken from it | `assets/svg/` → `svg/` (4009 files), `LICENSE-GRAPHICS` |
| Changes | None: the files are unmodified copies from the archive. |
| Graphics license | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), full text in `LICENSE-GRAPHICS` |
| Attribution | Twemoji graphics by Twitter, Inc. and other contributors, published by the jdecked/twemoji project |

Only the graphics and their license are here, no code of the Twemoji library (MIT) and none of its development
files. VibeDock's own code stays under the Apache License 2.0 (`LICENSE` in the repository); these graphics keep
their CC BY 4.0 license. If a file here is ever changed, list the change in this file (*Changes*), as CC BY 4.0
asks for an indication of changes.

## Where they are used

The built-in theme package directories under `vibedock-server/src/main/resources/themes/builtin/` also contain a small
selection of these unmodified SVGs as `assets/default.svg`. Each package pins the source version/path and includes
the full attribution and graphics license in `license.txt`; its manifest records the file checksum. These are the
existing theme illustrations, not a change to saved emoji selections. When updating a packaged copy, update its
source attribution and manifest checksum together. The package's pinned copy does not change just by updating this
Twemoji directory.

- Running from the sources (`mvn spring-boot:run`, the tests, `scripts/docs.sh`): found here automatically.
- Docker image: copied to `/app/twemoji/svg` and `/app/twemoji/LICENSE-GRAPHICS` (build context `twemoji`, see
  `vibedock-server/compose.yaml`); the license inventory lists them as *Twemoji artwork* with this version.
- Executable JAR elsewhere: copy this folder next to it as `twemoji/` (the server looks for `twemoji/svg` in its
  working directory) or set `EMOJI_DIR` to the `svg` folder.

## Checking the files

```sh
curl -fsSLo twemoji.tgz https://github.com/jdecked/twemoji/archive/refs/tags/v17.0.3.tar.gz
echo "a0855654b633045ae2337537e77f1bb4361162f7fcd910e613eaab1d6d9c5fca  twemoji.tgz" | sha256sum -c -
tar xzf twemoji.tgz twemoji-17.0.3/assets/svg twemoji-17.0.3/LICENSE-GRAPHICS
diff -r twemoji-17.0.3/assets/svg third-party/twemoji/svg && cmp twemoji-17.0.3/LICENSE-GRAPHICS third-party/twemoji/LICENSE-GRAPHICS
```

(macOS: `shasum -a 256 -c -` instead of `sha256sum -c -`.)

## Updating to a new version

1. Choose the release at <https://github.com/jdecked/twemoji/releases> and read its changes (removed or renamed
   files change which emoji the editor accepts).
2. Download the tag's archive, compute its SHA-256 and note it.
3. Replace `svg/` with the archive's `assets/svg/` and `LICENSE-GRAPHICS` with the archive's file, both unchanged.
4. Update `VERSION`, the table above (version, source, SHA-256, file count) and the commands under *Checking the
   files*.
5. Run the tests (`mvn verify` in `vibedock-server`, including `ThemesImageTest`), look at the emoji in the
   editor's picker, and add the update to `CHANGELOG.md`.
