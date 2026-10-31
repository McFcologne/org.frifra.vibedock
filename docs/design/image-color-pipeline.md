[VibeDock](../../README.md) › **Image color and update pipeline**

# Adaptive palettes and exact partial-image transport

This technical disclosure describes the implementation published with this document in the existing VibeDock
repository, before any extraction into a separate library. It documents the combination as well as its individual
steps so that another implementer can reproduce it. The public commit/PR records publication; a date inside this
file alone does not establish public availability. This is an implementation description, not a claim of novelty,
patent clearance, or exclusive ownership of palette coding or delta updates. Existing repository and artwork
licenses continue to apply; this document does not replace them.

## Purpose and precision

Small networked displays can spend more time receiving image bytes than displaying them. The server performs
color analysis and change detection; firmware streams compact rows through its existing image path. The method
combines a device-size policy, semantic color protection, collection-wide quality calibration, an exact reduced
reference frame, independently compacted partial images, and selection by actual encoded body length.

There are three different meanings of image size and quality:

1. The original hardware image is the renderer's full RGB565 frame, two bytes per pixel. Conversion from the
   renderer's RGB colors to RGB565 already has the hardware's normal precision limit.
2. The selected hardware image can be quantized to fewer colors. This step can change pixels and is **lossy**,
   even when a viewer sees little difference. The automatic heuristic is not proof of perceptual equivalence.
3. Removing unused palette entries, packing indices and sending patches are **lossless relative to the selected
   RGB565 frame**. They add no further color approximation. Browser images retain their original rendered pixels.

Never describe the entire optional quantization path as mathematically lossless. A pixel-change percentage counts
changed RGB565 pixels, not the magnitude of their errors: many tiny changes and a few severe changes differ.

## Reproducible processing sequence

1. Render a complete new image from the current usage, design, time, weather and artwork. Retain the original
   RGB565 pixels and semantic metadata: image bounds, icon/weather bounds, bar bounds and protected paints.
2. Resolve the first matching width/height rule. Both dimensions must fit. Choose 32, 64, 256 or full colors;
   unmatched frames use full colors. Default rules are 420×420 → 32 and 640×640 → 256.
3. If automatic quality is enabled, obtain the collection-calibrated preferred level. Evaluate current pixels
   against the original frame, not against a previously approximated image. Never quantize an approximation again.
4. Build a histogram of exact RGB565 colors. When it fits the budget, retain all colors exactly. Otherwise reserve
   observed semantic paints first, then distribute remaining slots with weighted median-cut representatives.
5. Map source colors to palette entries by squared RGB distance with double weight for green. Pack the palette
   indices and retain their fully decoded RGB565 frame as the definitive target image.
6. Compare this target with the client's known previous target using the existing ETag/row-hash delta mechanism.
   Generate partial spans from these selected frames, not from separate quantization of old and new patches.
7. For each available delta, collect all exact colors of its transmitted spans, including unchanged pixels enclosed
   by a span. Independently index these colors without approximation, using their actual count and minimum bits.
8. Compare the available RGB565/indexed full and delta bodies, including palette and section overhead. Transmit
   the smallest supported candidate. Preserve the complete selected target for subsequent changes and recovery.

The renderer still produces a complete current result. A delta is a transport representation of that result, not
an independently rendered alternate image. Palette churn can increase the changed area even when content changes
little; actual byte comparison avoids assuming that a delta is always cheaper.

## Semantic colors: reserve scarce slots deliberately

All protected colors share one global budget; artwork does not get an unlimited second palette. Protection uses
only paints actually present in the rendered RGB565 histogram, avoiding slots wasted on unused theme declarations.

- Theme background, title, text, muted, track, warning, window, footer and explicit bar paints contribute candidates.
- Weather and mood artwork contribute opaque flat foreground paints before compositing onto the theme. Flat-run
  extraction requires matching horizontal neighbors and at least one matching vertical neighbor. It excludes
  transparent fringes, counts stable paints, keeps sufficiently represented candidates and limits the set to 16.
- Rendered bar regions are also inspected for flat paints. This preserves rare yellow and red VU-meter segments
  that would be missed by a simple whole-image frequency ranking. Dominant fill/track colors supplement the set.
- When no explicit primary paints exist, the codec has a bounded region/hue representative fallback for callers
  that provide only artwork rectangles.

Preserve **distinct paints**, not just distinct hue buckets: Solarized has two different turquoise stripes. LCARS
needs its purple side rail even when beige is much more frequent. Hi-Fi needs the yellow/red end of its meter even
when green fills most segments. Weather's small yellow sun must not disappear into the background palette.

If protected candidates exceed a palette budget, the codec reports that fact. Automatic selection rejects a lower
palette that cannot retain them and may take its one permitted increase. The increase cap still applies: a fixed
small palette or an unusually rich protected set can exceed what can be preserved. This is not an unconditional
promise that every protected paint fits every configured limit. Antialiasing shades remain approximation candidates.

## Automatic calibration and bounded adaptation

The only user switch is **Allow quality fallback**. Internally, `ImageQuality` renders each installed theme's default
artwork in three reference usage states (normal, low/rate-limited and sign-in-required), at the inventory's small
resolutions whose width and height are each at most 420. It measures 16/32/64/128/256-color candidates. Missing
protected colors make a reference candidate unacceptable (recorded as 100% change).

For each theme and level, retain the worst changed-pixel percentage across reference scenes and sizes. A shared
target is the ceiling of the collection's worst next-higher-level error, clamped to the internal 40–50% corridor.
This is the current tuning rule, not an objectively optimal perceptual metric. There is no manually configured 43%
setting. Reference scenes and corridor changes must be evaluated on the whole theme/display matrix.

| Configured budget | One lower candidate | At most one higher candidate |
| --- | --- | --- |
| 32 | 16 | 64 |
| 64 | 32 | 256 |
| 256 | 128 | Full RGB565 quality |

Reference results choose a preferred level. A lower preferred candidate must also pass the current frame's metric
and protected-paint checks. The normal candidate can rise once when it fails. A reference-preferred higher level
is used directly. No recursive escalation is allowed; the one-increase cap wins over the target. A changed weather
icon or custom artwork changes pixels/metadata and requires evaluation again.

Calibration runs on one daemon worker. A new theme-library snapshot invalidates the reference and cancels obsolete
work. Until a reference is ready, requests use one conservative higher level instead of waiting. Worker shutdown
is tied to application shutdown; failures are logged and do not spin a retry loop on each pull.

## Lossless shrinking applies at every budget

After selection, the palette contains only colors that occur. The minimum index width is
`b = max(1, ceil(log2(K)))`, where `K` is the actual color count. The configured limit is not the transmitted count:
a four-color result uses four entries and two bits even if the setting says 32, 64 or 256. Exact shrinking can cross
any number of levels, independently of the automatic one-level quality limit. Full-quality hardware images with
at most 256 RGB565 colors can also use exact indexing; richer images keep RGB565.

Web/status previews bypass lossy quantization. `LosslessPng` tries an exact indexed PNG (including transparency)
when the original has at most 256 distinct ARGB colors and keeps it only if its encoded size is smaller. Otherwise
it retains the original PNG. This does not apply the hardware's RGB565 conversion to web pixels.

## Transport format and decoder contract

See [CLIENT-PROTOCOL.md](../../vibedock-server/CLIENT-PROTOCOL.md#optional-indexed-images-and-deltas-palette-v1)
for the normative protocol context. Hardware opts in with `X-VibeDock-Palette: 1`; old firmware keeps RGB565.
Previews and web clients bypass lossy hardware rules even if the capability is supplied.

Full format: `indexed<bits>-rgb565-be-<width>x<height>`, with 1–8 bits per index.

- Big-endian u16 actual count, 1–256 and no greater than `2^bits`.
- Exactly that many big-endian RGB565 entries.
- MSB-first indices, each row independently byte-aligned; unused low padding bits are zero.
- Exact body length `2 + 2*K + height*ceil(width*b/8)`, declared by `X-VibeDock-Length`.

Delta format: `indexed<bits>-rgb565-delta-be-<width>x<height>`. The same count/palette prefix precedes the existing
v1 stream. Each section contains four u16 values (y, row count, x, span width), complete-row hashes (four bytes per
row), then packed span rows. Each span row is byte-aligned independently; a u16 `0xffff` terminates the stream.
FNV covers the transmitted prefix and section stream. ETags and row checks describe reconstructed selected RGB565
pixels. Palette indices are local to each response and are not stable identifiers across transfers.

Firmware validates dimensions, count, declared full length and every index. One shared 512-byte palette suffices;
it reads packed bytes into the existing RGB565 row buffer and expands backwards to avoid overwriting unread indices.
Existing tone correction, drawing and row checks follow. No additional full framebuffer is required. Interrupted
transfers keep the existing resume/check mechanism. Corrupt data must not silently become a new valid base frame.

## Byte accounting: full image plus partial images

The following are **calculated format sizes**, not measured radio traffic or a promise of transfer latency. HTTP,
TLS, TCP/IP and retransmission overhead are excluded. Full RGB565 is `2*width*height` bytes.

| 240×240 full frame | Palette entries | Index bits | Body bytes | Saved versus RGB565 |
| --- | ---: | ---: | ---: | ---: |
| RGB565 | — | 16 | 115,200 | — |
| Indexed | 256 | 8 | 58,114 | 49.6% |
| Indexed | 32 | 5 | 36,066 | 68.7% |
| Indexed | 16 | 4 | 28,834 | 75.0% |
| Indexed | 4 | 2 | 14,410 | 87.5% |
| Indexed | 2 | 1 | 7,206 | 93.7% |

An indexed delta with `K` colors and sections `(n_i, w_i)` uses
`2 + 2*K + sum(8 + 4*n_i + n_i*ceil(w_i*b/8)) + 2` bytes.
For a hypothetical one-section 40×10 patch with four colors, this is
`10 + 8 + 40 + 100 + 2 = 160` bytes: 98.9% below the 14,410-byte four-color full frame and 99.86% below raw
115,200-byte RGB565. That example depends on the actual changed area; it is not a typical measured result.
An RGB565 version of the same section is 850 bytes, so exact patch indexing saves a further 81.2% there.
A tiny patch may instead cost less as RGB565 because it avoids palette overhead. Unchanged images can receive 304.

For meaningful comparisons record raw full size, optimized full size and actual chosen payload separately.
`Image transfer` logs report raw RGB565 `full`, actual body `payload`, their byte/percentage difference and mode;
`palette-K` is the actual transmitted count. The comparison page reports used colors, bit width, body/PNG sizes and
changed-pixel percentage. PNG bytes and hardware payload bytes are different encodings and must not be conflated.

[Reference pictures and measurements for all 45 bundled themes](color-examples/README.md) accompany this
implementation. They use synthetic reference data, not private provider or device data.

## Cache, bounds and invalidation

`ImagePalette.Cache` hashes original RGB565 bytes (SHA-256) and keys results by dimensions, configured budget,
automatic policy/target/preferred level, artwork/bar rectangles and primary paints. It reuses the selected pixels,
encoded body and metric only while these inputs match. New weather, artwork, quota pixels or policy causes a miss.
It is an in-memory access-ordered cache, at most 64 entries and 8 MiB of retained image byte arrays (object overhead
is additional). Oversized entries are not retained; eviction/restart recalculates. It is not a permanent disk store.
The separate existing delta/frame state keeps the information needed for the client's previous target.

## Source map and extension checklist

| Responsibility | Implementation |
| --- | --- |
| Renderer and semantic metadata | `StatusImage.java` |
| Ordered persisted policy | `ImageColorSettings.java` |
| Reference calibration and bounded level choice | `ImageQuality.java` |
| Quantization, exact indexing, delta repacking, cache | `ImagePalette.java` |
| Lossless web PNG candidate | `LosslessPng.java` |
| Capability, candidate selection, ETags and logs | `DeviceApiController.java`, `ImageDelta.java` |
| Streaming full/delta decoder | client `palette_image.h`, `delta_image.h`, `main.cpp` |
| Interactive experiment | `/admin/color-preview`, `ImageColorPreviewController.java` |

When extending this implementation:

1. Keep web pixel identity and legacy-client compatibility. Do not make old clients interpret indexed bytes as RGB565.
2. Add new semantic paints at render time rather than guessing important colors solely from whole-image frequency.
3. Include every new selection input in cache identity and invalidate reference calibration on relevant changes.
4. Preserve one-step quality limits and unrestricted exact shrinking as separate operations.
5. Compute deltas and checks against the same decoded target; never compare raw frames but patch quantized frames.
6. Evaluate all supported candidate lengths including palette, padding, section headers, hashes and terminator.
7. Extend Java and C++ fixtures together before changing wire semantics; version incompatible formats/capabilities.
8. Test odd widths, all bit widths, one/256 entries, bad count/index/length, partial input, exact decoded pixels,
   row/payload checks and interruption recovery. Keep LCARS purple, Solarized stripes and Hi-Fi VU regressions.
9. Compare every theme at narrow and square resolutions, with weather and quota/status changes. Inspect native-size
   pictures; changed-pixel counts alone do not certify visual quality.
10. Compile every board and measure hardware memory/latency separately. Host tests and byte calculations do not
    replace a device soak test, especially on ESP8266.

Server tests are `ImagePaletteTest`, `ImageQualityTest`, `LosslessPngTest` and the affected API/access tests. Client
host fixtures are `vibedock-client/tests/palette_test.cpp` and `policy_test.cpp`; browser tests cover settings and
comparison. The repository verification workflow executes the host fixtures as well as server/browser checks.
