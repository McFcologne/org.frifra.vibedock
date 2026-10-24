- **Emoji graphics in the repository:** the Twemoji 17.0.3 SVGs (unmodified, CC BY 4.0) and their license are
  versioned under `third-party/twemoji`, with their origin, the archive's checksum and the update steps. The server,
  the tests, the manual and the Docker image use these same files; the build no longer downloads Twemoji. Started from
  the repository, the server finds them by itself; a JAR elsewhere uses a copy named `twemoji` next to it, and
  `EMOJI_DIR` still names another folder. `ThemesImageTest` now always runs and draws into `target/themes.png`.
  Rendering, the available emoji and the firmware stay the same.
