- **Theme package groundwork (toward 0.4):** defines the `.vibetheme` archive and manifest with theme/icon IDs,
  renderer requirements, package-relative files and SHA-256 integrity checks. A bounded reader, JSON Schema and an
  original example prepare import/export and the theme editor. This first stage adds no import UI and changes no
  existing design or renderer; all 35 existing themes must retain their exact appearance during the migration.
