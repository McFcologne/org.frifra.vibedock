- **Theme catalog groundwork (toward 0.4):** replaces enum-based theme selection with immutable definitions and an
  ordered catalog keyed by the existing IDs. Existing theme order, disabled selections, design files and emoji
  choices remain compatible. A frozen comparison renderer checks all 35 themes across 1,750 panel, layout, data and
  browser-scale combinations before the later package-based renderer migration. Theme installation and editing
  are not available yet.
