# Theme packages and editor: stacked delivery toward 0.4

Target: 0.4; assess 1.0 only after implementation and validation. Server-rendered images remain the device protocol;
theme packages do not require firmware changes. Each branch builds on the preceding branch; PRs initially target their
predecessor and are merged from the bottom upward. Keep each stage runnable and independently reviewable.

| Stage | Branch | Acceptance |
| --- | --- | --- |
| 1 | `feat/theme-package-format` | Bounded ZIP reader, manifest schema, self-contained fixture, integrity/reference validation and documentation. No runtime rendering change. |
| 2 | `refactor/theme-catalog` | Stable theme IDs replace enum coupling; existing settings and availability remain readable. Capture deterministic reference renders before changing any rendering. |
| 3 | `feat/declarative-theme-renderer` | All 35 existing themes represented in package data. Exact pixel equality against baseline renders on supported sizes, layouts and representative data states. Extend the language to preserve a theme; never simplify the theme to fit the language. |
| 4 | `feat/theme-icons` | Theme-specific default/catalog icons and emoticon/default/catalog selection. Existing designs keep their emoticons and no-icon choices. Check proposed icons inside each theme; Debugger uses a technical IDE bug silhouette, not a mascot face. |
| 5 | `feat/theme-import-export` | Authenticated import/export, full renderer/asset validation, atomic install and reload without restart. Same ID offers update or independent copy; incompatible packages leave working themes intact. Used/missing/deleted theme and icon references have explicit handling. |
| 6 | `feat/theme-editor` | Duplicate a theme, edit supported drawing properties/assets, preview all display sizes, save and export a self-contained package. Preserve recoverable built-in originals. |
| 7 | `feat/theme-collection-04` | XP, Windows 7, DOS, Nuclear, Halloween, Christmas, Winter, Cyberpunk, Orbital and Oscilloscope as packages with icons and previews; exercise the editor with real themes. |
| 8 | `docs/release-04-themes` | Manual, format examples, screenshots, release notes and 0.3.x upgrade validation, including saved designs, disabled themes and existing emoticons. |

## Non-negotiable compatibility

All existing themes remain **1:1 visually identical** after migration: colours, fonts, text, coordinates, bars, frames,
backgrounds, scaling and existing emoticon appearance/placement. Use fixed test data, clock, fonts and assets for
reference renders. Include narrow, square and tall panels, compact/split bands, changed orientation, unavailable/
partial/rate-limited readings and enlarged web displays. An explicitly selected new theme icon is a separate opt-in.

Theme drawing and instance layout remain separate: package data must not silently choose providers, device assignments
or band order. The existing settings files are not rewritten just by reading them. Version and back up any necessary
persisted migration before its first write. A missing theme must not silently replace a saved reference permanently.

The first end-to-end shareable-theme milestone is stage 5; the requested editor and new collection are complete only
after stage 7. Stage 1 alone is not advertised as user-installable themes. The envelope description and fixture live
in [`vibedock-server/themes/`](../../vibedock-server/themes/README.md).
