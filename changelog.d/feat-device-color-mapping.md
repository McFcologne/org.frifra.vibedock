- **Automatic hardware palettes:** ordered width/height rules select 32, 64, 256 or full colors. Defaults use 32
  through 420×420 and 256 through 640×640. One quality switch calibrates all themes across small displays and low,
  average and high quota reference states,
  allowing one lower or at most one higher level. Present theme base/window paints, bar contrast and weather/mood hues receive priority.
- **Only transmit used colors:** unused entries are removed losslessly in every selected palette. Full images and
  exact delta spans use minimum bit widths; compatible firmware streams them with one shared 512-byte palette.
  Legacy firmware keeps RGB565. Web images keep every pixel and use smaller exact PNG palettes where possible.
- **Cached analysis and live comparison:** image fingerprints reuse quality metrics and encoded frames. Changed
  icons or policy are recalculated. The admin live comparison shows all themes at exact native pixel sizes, actual color
  counts, bytes, metrics and compact ↑/↓ indicators; references are labelled separately from live data.

### Smaller full images and exact partial updates

- **Two reductions work together:** hardware images use a size-selected palette, then subsequent updates transfer
  only changed spans when cheaper. Every partial image is independently packed with its exact used colors and
  minimum index width, without applying a second lossy color reduction. Available full/partial representations are
  compared including palette and protocol overhead; unchanged images can still use HTTP 304.
- **Substantial full-image savings:** at 240×240, RGB565 is 115,200 bytes. A full image using 32 colors needs 36,066
  bytes (68.7% less), 16 colors need 28,834 (75.0% less), and four colors need 14,410 (87.5% less). These are calculated
  body sizes, including palettes, not measurements of radio traffic. Exact removal of unused colors applies at every
  selected limit, independently of automatic quality.
- **Partial updates can be smaller again:** an illustrative single 40×10-pixel four-color patch is 160 bytes including
  palette, section and row checks: 98.9% below that four-color full frame and 99.86% below original RGB565. Actual
  savings depend on changed content and exclude HTTP/TLS/network overhead. Transfer logs show the raw full size,
  actual payload, mode and saving; the live comparison shows full-image palette/quality metrics.
- **Quality and compatibility remain explicit:** hardware color quantization can change pixels; palette compaction
  and partial-image coding are exact relative to the selected image. Protected theme paints, icon paints and meter
  segments retain priority. New server plus compatible firmware enables indexed hardware bodies; legacy firmware
  remains supported and web previews/displays retain their original pixels through lossless PNG optimization.
- **Reproducible technical disclosure:** [image-pipeline reference](https://github.com/McFcologne/org.frifra.vibedock/blob/main/docs/design/image-color-pipeline.md) describes
  algorithms, semantic color protection, calibration, cache boundaries, packet layout, byte formulas and extension
  tests. The [manual](https://github.com/McFcologne/org.frifra.vibedock/blob/main/docs/manual/MANUAL.md#much-smaller-full-images-and-partial-updates) explains the controls and
  how to distinguish full-image savings from actual partial transfers.

### Display colors

- **Explicit LCD footer colors:** a custom footer background survives the Handheld LCD color reduction; the
  original four-shade appearance remains the default.
- **Starship theme icon:** a stylized Starfleet-inspired insignia replaces the hand as the theme default.
