- **Theme drawing recipes (toward 0.4):** all 35 built-in themes now load their manifest, palette, font and drawing
  recipe from self-contained package directories. Their appearance remains pixel-identical to the original renderer.
  The versioned JSON format supports existing drawing presets and bounded vector decorations, with an authoring
  schema and a renderable example. Existing designs keep their themes and emoji selections. Import/export UI,
  the editor and new theme icons remain later stages; no firmware update is required for these server changes.
