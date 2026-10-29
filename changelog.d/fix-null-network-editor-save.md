- **No network "null":** a display whose configuration holds no Wi-Fi name read it as the text *null* and tried to
  connect to a network of that name (*Network "null" was not found*); it now opens its setup instead. Needs the new
  firmware.
- **Editor saves before leaving:** a link of the administration clicked right after an edit in the display editor
  waits for the save; before, the edit was sent along only as the page closed, and could be lost when a save was
  already running.
