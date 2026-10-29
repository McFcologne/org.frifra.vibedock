# Theme package envelope (0.4 work in progress)

A `.vibetheme` file is a ZIP with `theme.json` at its root and all referenced files inside it. It is a portable theme,
not an instance backup: it contains no accounts, device identifiers, credentials, Wi-Fi settings or design assignments.

The archive reader defines and tests the **archive and manifest**. The drawing reader now validates the recipe and
the server loads all 35 built-in themes from package directories. There is still no upload endpoint, installation UI
or editor. A successful `ThemePackage.read` establishes structure and integrity only; `ThemeDefinition.read` separately
validates the renderer version and recipe. Graphics still need validation before installation or rendering. In particular,
an `.svg` extension and checksum do not make SVG content safe to render. No package content is executed or fetched.

The format remains a draft during the stacked implementation. The drawing recipes reproduce all existing themes,
with pixel comparisons against the frozen renderer. Their supported primitives and limits are documented in
[`drawing.md`](drawing.md); adding a theme using those capabilities does not require adding a Java theme constant.
Introducing a new kind of drawing primitive would require a compatible renderer update.

## Manifest

[`package.schema.json`](package.schema.json) describes the JSON envelope. The reader also checks cross-file references,
unique icon IDs, byte limits, duplicate JSON keys and ZIP paths, and file digests; these checks cannot all be expressed
in JSON Schema. Unrecognised manifest properties and schema versions are rejected rather than ignored.

| Field | Meaning |
| --- | --- |
| `format` | Exactly `org.frifra.vibedock.theme`; recognition, not proof of authenticity |
| `schemaVersion` | Envelope version, currently `1` |
| `id` | Stable lowercase identifier, up to 80 ASCII characters (`a-z`, digits, `.`, `_`, `-`); keep it on updates |
| `version` | Theme revision as `major.minor.patch`; independent of the server release |
| `name`, `author`, `license` | Human-readable name, attribution and licensing declaration; include required license texts under a declared `.txt` path |
| `renderer.id`, `renderer.version` | Required rendering language and version, checked against installed capabilities before installation |
| `definition` | Package-relative JSON file consumed by that renderer |
| `icons` | 1–32 icons, each with a unique `id`, `label` and `file` (PNG or SVG) |
| `defaultIcon` | ID of one of this theme's icons |
| `preview` | Optional PNG or SVG preview; an untrusted illustration, not proof of how the renderer will draw the theme |
| `files` | Every non-manifest ZIP file and its lowercase SHA-256 digest, over the original file bytes |

All references must name a declared file. Every declared file must occur exactly once in the ZIP; unlisted files are
rejected. Paths are lowercase, relative, use `/`, and contain no empty, `.` or `..` segments. URLs, absolute paths,
backslashes and executable file types are not supported. ZIP directories are optional and count toward the entry limit.

Limits: 8 MiB archive, 8 MiB expanded **including the manifest**, 2 MiB per file, 64 KiB manifest, and 64 entries
including directories. Only `.json`, `.png`, `.svg` and `.txt` package files are accepted in this draft. Reading keeps
bounded bytes in memory and never extracts to the filesystem. A malformed ZIP/JSON or invalid manifest is rejected.

Checksums detect a mismatch with the manifest; anyone changing both a file and its checksum can produce a valid new
archive. Neither ID nor checksum verifies the author. Digital signatures and an online theme marketplace are outside
this first implementation. Author attribution and redistribution rights remain part of each package's license.

## Icon selection contract

The package supplies the theme's default and catalog icons. A design will choose between:

- **Emoticon:** its existing emoji selection (including the existing no-icon choice).
- **Theme default:** resolves the default icon of the currently selected theme, including after a swipe.
- **Theme catalog icon:** a fixed `(theme ID, icon ID)` selection, retained when the design changes theme.

Existing designs are not automatically switched to a theme icon. All 35 existing themes must retain their current
rendering, including their existing emoji style and position, until the user explicitly selects a new icon.
The Debugger icon will use a restrained, recognisable IDE bug silhouette without a face; every proposed icon must be
reviewed in its actual rendered theme at the supported display sizes, not accepted from the contact sheet alone.

## Example archive

[`examples/minimal/`](examples/minimal/) contains original example artwork and palette under the repository's Apache-2.0
license. Its manifest has real checksums; tests pack and read the files without checking a generated archive into Git.
To build one locally with Python 3 (from `vibedock-server/`):

```sh
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
source = Path('themes/examples/minimal')
with ZipFile('/tmp/example.vibetheme', 'w', ZIP_DEFLATED) as archive:
    for path in sorted(source.rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(source).as_posix())
PY
```

Editing an asset also requires updating its entry in `theme.json.files`. The archive has no enclosing `minimal/`
directory: `theme.json` is at its root. This fixture deliberately cannot yet be installed through the administration.
