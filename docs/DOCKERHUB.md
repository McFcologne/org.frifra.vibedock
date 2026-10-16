# VibeDock server

**See how much of your AI coding quota is left – on a small display next to your screen.**

VibeDock signs in to Codex, Claude, Cursor, Grok and Gemini, collects the usage of all your accounts (the 5-hour and
weekly limits and their reset times) and draws an image for every display. Displays are managed centrally in its web
administration: pair them, choose what each one shows and how it looks, and update their firmware over Wi-Fi. A
display can be the VibeTV M1 desk display, an ESP32 board with a screen, or any browser or tablet. Provider
credentials stay on the server; a display only receives its image.

![VibeDock administration](https://raw.githubusercontent.com/McFcologne/org.frifra.vibedock/main/docs/manual/images/02-overview.png)

## Quick start

```sh
docker pull mcfcologne/vibedock-server:latest
```

`compose.yaml`:

```yaml
services:
  vibedock:
    image: mcfcologne/vibedock-server:latest
    container_name: vibedock-server
    restart: unless-stopped
    ports:
      - "10002:10002"                 # displays, web displays, usage API
      - "10001:10001"                 # administration (plain HTTP)
    environment:
      ADMIN_USER: ${ADMIN_USER:-admin}
      ADMIN_PASSWORD: ${ADMIN_PASSWORD:?Set ADMIN_PASSWORD in .env}
      TIME_ZONE: ${TIME_ZONE:-Europe/Berlin}
      INTERNAL_URL: ${INTERNAL_URL:-}   # e.g. http://192.168.1.10:10002
    volumes:
      - /srv/vibedock:/data             # owned by UID 10001, mode 700
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
```

`.env` next to it (`chmod 600`):

```sh
ADMIN_PASSWORD=choose-a-long-password
```

Then `docker compose up -d` and open `http://<host>:10001/admin`. Update with
`docker compose pull && docker compose up -d`; the data stays in `/data`.

| Port | Use |
| --- | --- |
| 10001 | Administration (sign-in with `ADMIN_USER` / `ADMIN_PASSWORD`) |
| 10002 | Displays, web displays and the read-only usage API |

`/data` holds settings, provider sign-ins and paired displays, partly in plain text: keep it and its backups private.

## Tags and platforms

`latest` and `<version>` (e.g. `0.1.0`), each for `linux/amd64` and `linux/arm64`. The same image is published as
`ghcr.io/mcfcologne/vibedock-server`. The image carries the display firmware of every supported board; the
administration installs it over USB from Chrome or Edge and updates paired displays over Wi-Fi.

## More

- Source, issues and releases: <https://github.com/McFcologne/org.frifra.vibedock>
- User manual: <https://github.com/McFcologne/org.frifra.vibedock/blob/main/docs/manual/MANUAL.md>
- License: Apache-2.0

VibeDock is an independent hobby project, provided as is without warranty. It is not affiliated with Vibe TV or any AI
provider.
