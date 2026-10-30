# VibeDock 2D drawing recipes (draft v1)

The manifest names renderer `vibedock-2d`, version `1`. Its definition is a UTF-8 JSON file, at most 64 KiB, validated
by `ThemeDefinition` before drawing. [`drawing.schema.json`](drawing.schema.json) describes the same language for
authoring tools. Duplicate keys, trailing documents, unknown fields, unsupported versions, colours and operations are
rejected. Schema checks do not replace the renderer's validation.

## Recipe

| Field | Meaning |
| --- | --- |
| `format`, `version` | Exactly `vibedock-2d` and `1` |
| `preset` | Reusable built-in drawing recipe, listed below |
| `palette` | Six required RGB colours: `background`, `title`, `text`, `muted`, `track`, `warning`, as `#rrggbb` |
| `font` | `DejaVu Sans`, `DejaVu Sans Mono` or `DejaVu Serif`; uses the existing server font installation |
| `footerBackground` | Optional context-band background colour; otherwise the preset's original background |
| `barColor` | Optional coloured-bar fill; low quota still uses the existing warning rules |
| `layers` | Optional `background` and `band` arrays of bounded decorations |

Recipes: `modern`, `terminal`, `mac`, `windows`, `fluent`, `macos`, `console`, `lcd`, `blueprint`, `neon`, `print`,
`mission`, `departure`, `nordic`, `hifi`, `solarized`, `quest`, `bauhaus`, `ink`, `industrial`, `porcelain`, `github`,
`code`, `shell`, `diff`, `debug`. These are rendering capabilities, not theme IDs. They retain the existing window
geometry, title/reset treatment, quota bars and icon filters. The engine still owns those algorithms; the JSON
selects and configures them. A package cannot supply Java, JavaScript or arbitrary drawing code.

The text, fixed-monochrome and palette-based bar recipes (`terminal`, `mac`, `windows`, `lcd`, `blueprint`, `print`)
reject `barColor`; adjust their palette instead. Other recipes accept it. An absent override preserves the exact
original behaviour. Colour overrides and layer colours may also reference the palette, e.g. `"@title"`.

Usage data, source assignments, band order and display orientation remain instance/design settings. They are never
chosen by the package. Recipe composition does not currently expose arbitrary text positioning or a general layout
language; extend renderer capabilities explicitly if later themes need them.

## Decorations

Each layer has at most 32 operations. `background` draws after the preset wallpaper and before content. `band` draws
after preset band decoration and before the provider content; its origin and bounds follow the provider's full,
split or compact slot. A preset's window may cover an underlying decoration. All operations are clipped to the
layer, preserve the caller's graphics state and inherit the preset's rendering context.

Every operation requires `op`, `x`, `y`, `width`, `height`, `color`; `opacity` is optional (0–1, default 1).

| Operation | Additional fields |
| --- | --- |
| `rect` | `fill` (default true), `radius` (corner radius 0–128 px, default 0) |
| `ellipse` | `fill` (default true) |
| `line` | None; `width` and `height` give the displacement of the endpoint |
| `gradient` | Required `to` colour; `vertical` (default true) |
| `grid` | `step` (whole pixels 1–128, default 8); at most 512 lines per axis |

Coordinates and dimensions are either numbers in logical pixels (−4096 to 4096) or objects with both `relative`
(−4 to 4) and `pixels` (−4096 to 4096). The result is rounded from `relative * layer extent + pixels`. X/width use the
layer width; Y/height use its height. `{ "relative": 1, "pixels": -4 }` is therefore the layer's width/height minus
four pixels. Negative or empty rectangle dimensions draw nothing, as in Java2D. There are no expressions, supplied
loops, file/resource requests, text commands or image commands. Icons are separate package assets.

The [`minimal` example](examples/minimal/style.json) uses a faint background grid and a rounded band outline. It is
rendered by `ThemeDefinitionTest` into `target/theme-package-example.png` without registering the theme in the live
catalog. Import/export is available in administration; the visual editing UI is a later stage.

## Built-in packages and compatibility

All 35 built-in directories live in `src/main/resources/themes/builtin/`. The server verifies each manifest, file
checksum and drawing recipe at startup. Corrupt built-in resources fail initialization rather than silently changing
an existing design. `catalog.json` lists their original order, checked against the current catalog by tests.

The `legacy` icon asset in each package is an unmodified copy of its existing Twemoji illustration, with source,
attribution and full license texts in `license.txt`. The additional `theme` asset is original VibeDock vector artwork
under Apache-2.0, explicitly chosen through the stage-4 theme-default/catalog modes. Existing designs retain their
emoticons. No generated `.vibetheme` archive or firmware binary is checked in.

The frozen 0.3.x renderer remains independent of these files. Its 1,750 pixel comparisons cover the original 35
themes on eight display sizes, six representative states/layouts and two browser scales. An additional test repacks
every built-in directory, reads it through the package API and renders it under an unrelated theme ID. This confirms
that its appearance comes from the recipe rather than a Java branch on that ID.
