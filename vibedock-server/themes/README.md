# Theme package envelope (0.4 work in progress)

A `.vibetheme` file is a ZIP with `theme.json` at its root and all referenced files inside it. It is a portable theme,
not an instance backup: it contains no accounts, device identifiers, credentials, Wi-Fi settings or design assignments.

The archive reader checks the **archive and manifest**. The drawing reader validates the recipe and the server loads
all 35 built-in themes from package directories. Administration can import, export, copy, update and remove installed
themes without restarting. The visual theme editor is available through **Customize** / **Edit theme** on each gallery card. Installation validates the manifest,
renderer/recipe and every graphic; an extension or checksum alone does not make a file safe. No package code or
guide is executed, and no external resources are fetched.

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

The package supplies the theme's default and catalog icons. The design editor now chooses between:

- **Emoticon:** its existing emoji selection (including the existing no-icon choice).
- **Theme default:** resolves the default icon of the currently selected theme, including after a swipe.
- **Theme catalog icon:** a fixed `(theme ID, icon ID)` selection, retained when the design changes theme.

Existing designs are not automatically switched to a theme icon. All 35 existing themes must retain their current
rendering, including their existing emoji style and position, until the user explicitly selects a new icon.
The new Debugger icon uses a technical IDE bug silhouette without a face. All 35 new vector icons have been checked
inside their rendered themes on square and narrow panels, including the Handheld and Blueprint contour filters.
The original illustrations remain available as the `legacy` catalog entries; `theme` is the new opt-in default.

Design JSON keeps the existing `mood` string. An absent/null `icon` retains the original emoticon/no-icon behaviour;
`{"mode":"theme-default"}` follows the current theme, while
`{"mode":"theme-catalog","theme":"blueprint","id":"theme"}` fixes one package icon. The old `mood` value remains
stored when switching modes. Missing fixed references are retained and fall back to that emoticon, with an editor
message; newly choosing an unavailable reference is rejected. Disabling a theme for selection/swipes does not remove
its icons from the catalog or break an existing fixed reference.

The first theme-icon or installed-theme selection over an existing configuration creates the private, one-time
`clients.json.before-theme-icons.bak`. Reading legacy settings never rewrites them. To downgrade to a server that
does not understand `icon`, stop the server and restore that snapshot as `clients.json` (keep a copy of the current
file first). This restores the configuration at first opt-in, not subsequent changes. A fresh configuration with no
older file has no pre-feature snapshot. To retain later changes when downgrading, switch every design back to
Emoticon and a built-in theme before saving; no installed-theme IDs or optional icon fields then remain.

The file is marked as configuration version 4 while any default or device design selects a theme icon or installed
theme. Emoticon-only designs with built-in themes retain version 3; returning every design to those writes version 3. This server
reads both shapes without rewriting them on load.

Only PNG rasters of catalog assets are served by the authenticated administration endpoint. Icons are limited to
128 KiB and rasterized to at most 512×512. PNG sources are at most 1024×1024. SVG uses a small namespace-aware shape
subset (paths, rectangles, ellipses/circles, lines, polygons/polylines and groups), at most 2048 XML nodes and bounded
numeric coordinates. Scripts, CSS, text/fonts, external references, entities/DOCTYPE and nested SVG are rejected.
The SVG loader also denies resource access. These are deliberate limits for shareable theme artwork, not support
for arbitrary SVG documents.

## Compact import and export

Export uses ordinary ZIP/DEFLATE at maximum compression, with stable entry order and timestamps. The definition and
manifest are compact JSON; artwork and license bytes are preserved. No rendered previews, firmware, instance settings
or source archives are added. A full theme including both icons and their licenses is typically a few tens of KB,
not MB; actual size depends on the artwork. Export contains every declared runtime/catalog file and license.

**Include agent guide in exports** adds three small, compressed reference files under `authoring/`: `agent.txt`,
`drawing.schema.json` and `package.schema.json`. Uncheck it for the smallest export. The guide helps an explicitly
instructed coding agent change the recipe, keep attribution, update versions/checksums and repack the theme. Archive
text remains untrusted data; the server never runs it or invokes an agent. Installation drops these generated
references from the stored package, and export regenerates current references. Runtime files must not use these
three reserved paths when exporting with the guide. Bundled schemas are checked against the authoring sources by tests.

Import validates before changing anything. A new ID installs directly; the same installed ID offers an update
with a strictly newer theme version or an independent copy with a new ID/name. Exact retries of an already installed
package are harmless. Built-ins cannot be overwritten or removed: make a copy to develop them. Saving a theme that
is already selected immediately changes subsequent device/web renders, including icons and browser background.
Import and removal do not select another design on the user's behalf.

Installed compact archives are stored as `DATA_DIR/themes/<id>.vibetheme` using atomic replacement. The instance limit
is 64 installed packages and 64 MiB expanded, in addition to the built-ins. An invalid/incompatible import leaves the
current package and live catalog intact. Startup skips damaged/incompatible files with a log warning, keeps their
saved design IDs and draws Midnight temporarily. An unloaded file is never silently overwritten by a new import.
Manual file changes are read at startup; use the UI/API for live changes. Missing fixed icons retain their reference
and use the stored emoticon. Removal is blocked while any default/device design uses the theme or a fixed icon from
it, or while it would remove the last enabled theme. Disabled IDs are kept for a later reinstallation. If the last
enabled package is missing, Midnight is temporarily available without rewriting saved preferences.

Authenticated administration endpoints: `POST /admin/themes/inspect`, `POST /admin/themes/import?mode=add|update|copy`,
`GET /admin/themes/<id>/export?agent=true|false`, `POST /admin/themes/<id>/delete`. Writes require CSRF. Requests contain
raw archive bytes, bounded by the 8 MiB archive limit. Copies also give `copyId` and optional `copyName` query values.
The saved data model retains IDs and resolves the latest definitions at render time; there is no global mutable
catalog shared between server instances.

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
directory: `theme.json` is at its root. This example can now be imported through administration.


## Visual theme editor

**Customize** opens a built-in theme as an independent draft in another tab; **Edit theme** updates an installed
package, and **Create copy** starts another independent ID. The design tab refreshes its catalog when focused again.
The editor changes palette colors, preset, font, optional bar/footer colors, bounded background/band decorations,
icon labels/IDs, default icon and uploaded PNG/safe SVG artwork. Existing definitions and original license files
remain intact until edited; removing an icon does not remove inherited attribution. Unreferenced editor-uploaded
artwork is omitted on subsequent saves so repeated replacements do not accumulate in the archive.

Previews use the actual device renderer and selectable supported panel sizes with stable sample readings.
Drafts are page-local: no autosave, installation, device action or provider request during preview/export.
**Save theme** explicitly installs a new ID or updates an installed theme with a newer version, through the same
validated atomic importer. It does not select a theme in any design. **Export draft** produces the same compact
`.vibetheme` archive, optionally with the agent guide/schemas, without installing it. Leaving an edited page warns
about unsaved changes; closing it discards the draft. A source package revision digest prevents a stale editor from
overwriting an intervening update, even if it submits a larger version; the page retains its controls on error.
If the source was updated or removed, reopen a current editor in another tab to compare and reapply your changes.
This first editor uses preset-based layouts and visual controls, not arbitrary scripts or draggable text layouts.

The admin-only editor endpoints are `GET /admin/themes/{id}/edit`, `GET /admin/themes/{id}/editor?copy=true`,
`POST /admin/theme-editor/preview.png?display=<panel-id>`, `POST /admin/theme-editor/export?agent=true|false`
and `POST /admin/theme-editor/save`. Writes require the existing CSRF token. JSON drafts are bounded to 12 MiB
before binding (including base64 artwork); all existing package/graphic/renderer limits also apply.
