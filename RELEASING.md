[VibeDock](README.md) › **Releasing**

# Releases, registries and repository settings

Where VibeDock is published, how the places are connected and what has to be set up for a release. Values of secrets
are never written down here or anywhere in the repository.

## Places

| What | Where |
| --- | --- |
| Source repository | <https://github.com/McFcologne/org.frifra.vibedock> (default branch `main`) |
| Releases (server JAR; the firmware has releases of its own) | <https://github.com/McFcologne/org.frifra.vibedock/releases> |
| Docker image on GitHub | `ghcr.io/mcfcologne/vibedock-server` – <https://github.com/McFcologne/org.frifra.vibedock/pkgs/container/vibedock-server> |
| Docker image on Docker Hub | `mcfcologne/vibedock-server` – <https://hub.docker.com/r/mcfcologne/vibedock-server> |
| Release notes | [`CHANGELOG.md`](CHANGELOG.md), the section `## Server <version>` or `## Client <version>` (`## <version>` up to 0.5.1); until the release, one file per pull request in [`changelog.d/`](changelog.d/README.md) |
| Docker Hub description | [`docs/DOCKERHUB.md`](docs/DOCKERHUB.md), sent with every image publication |

Both registries carry the same images: `<version>` and `latest` (only for a plain `x.y.z`) as one image for `amd64` and
`arm64`. On ghcr.io the single-architecture stages `<version>-amd64` and `<version>-arm64` are visible as well.

## How they are connected

```
git tag server-vX.Y.Z  ──push──▶  GitHub Actions: Release VibeDock server (.github/workflows/release.yml)
                              ├─ server    → vibedock-server-<version>.jar
                              ├─ image     → ghcr.io/…/vibedock-server:<version>-<arch> (pushed from the build,
                              │              no image file)
                              ├─ publish-image → ghcr.io/…:<version>, :latest
                              │                  and Docker Hub …:<version>, :latest and its description
                              │                  from docs/DOCKERHUB.md (with the secrets below)
                              └─ release   → GitHub release (draft, files uploaded, then published)
push to main / release/**, pull request  ──▶  GitHub Actions: Verify VibeDock (.github/workflows/verify.yml), check server-and-browser
```

- The **version** comes from the Git tag (`vibedock-server/scripts/version.sh`): `server-v0.6.0` gives 0.6.0. Until the
  first `server-v` tag exists, the plain tags of the earlier common releases (`v0.5.1` or `0.5.1`) apply. The server and
  its image cannot be given a version by hand; the firmware has its own tags (see *Client releases*).
- The **ghcr.io package** is linked to the repository by the image labels and annotations
  `org.opencontainers.image.source`; it is public because the repository is, and lists the repository's README.
- **Docker Hub** gets the images only when both repository secrets are set; without them the run publishes to ghcr.io
  and says so in its log.
- **Release files** are only the server JAR; the release job downloads exactly this artifact. The firmware is not part
  of a server release (up to 0.5.1 it was). The Docker image comes from the registries; releases up to 0.3.0 also carried it as
  `vibedock-server-<version>-<arch>.tar.gz`, and those files stay as they are.
- **Displays** get their firmware from the server they are paired with. The image carries no firmware: the server
  downloads it from the client releases of the sources in `firmware-sources.yaml` (by default this repository's
  `client-v` releases), a short while after its start and then daily, and the administration offers it over Wi-Fi.

## Secrets and tokens

| Name | Kind | Used by | Created at |
| --- | --- | --- | --- |
| `GITHUB_TOKEN` | automatic, per run | release upload, `ghcr.io` login | nothing to do; rights from `permissions:` in the workflow |
| `DOCKERHUB_USERNAME` | repository secret | Docker Hub login and image name | GitHub › Settings › Secrets and variables › Actions |
| `DOCKERHUB_TOKEN` | repository secret | Docker Hub login and the repository description | Docker Hub › Account settings › Personal access tokens (Read, Write, Delete; Read & Write publishes the images but cannot change the description) |

A Docker Hub token that expires or is revoked makes the next release fail in *publish-image*; create a new one and
replace the secret.

## Repository settings that the workflows rely on

| Setting | Value |
| --- | --- |
| Actions › General › Actions permissions | own and GitHub's actions, plus `docker/setup-qemu-action@*`, `docker/setup-buildx-action@*` |
| Actions › General › Workflow permissions | read only (the release workflow asks for `contents: write` and `packages: write` itself) |
| Actions › General › Fork pull requests | approval required for external contributors |
| Rules › Ruleset `main` | default branch: no deletion, no force push, pull request required, check `server-and-browser`; bypass: repository admin |
| Rules › Ruleset `release tags` | tags `server-v*`, `client-v*` and `v*`: only the repository admin creates, moves or deletes them |
| General › Release immutability | on: a published release can no longer be changed |
| Advanced Security | dependency graph, Dependabot alerts and security updates, secret protection with push protection |

A workflow that uses a new third-party action fails until that action is added to the allowed list.

## Making a release

1. Merge everything into `main`. Then add a section `## Server X.Y.Z` to `CHANGELOG.md`: a short introduction (what changed,
   what to update), then the bullets of the files in `changelog.d/` (one file per merged pull request). Delete those
   files, except its README, and merge that change as well.
2. Tag and push: `git tag server-vX.Y.Z && git push origin server-vX.Y.Z`.
3. Wait for *Release VibeDock server* (about 10 minutes). The release appears once the JAR is uploaded.

Because releases are immutable, a mistake is fixed with a new version (`server-vX.Y.Z+1`), not by replacing files.

**Image only, for an existing tag:** *Actions › Release VibeDock server › Run workflow* with the tag (e.g. `server-v0.6.0`) builds
and publishes that tag's image to the registries and leaves its release alone, for instance after a registry was added.

## Client releases (firmware)

The firmware has its own releases and versions, independent of the server's. A server downloads the firmware from there.

1. Merge everything into `main`. Add a section `## Client X.Y.Z` to `CHANGELOG.md` (bullets of the firmware-related
   files in `changelog.d/`, as for a server release).
2. Tag and push: `git tag client-vX.Y.Z && git push origin client-vX.Y.Z`.
3. Wait for *Release VibeDock client* (about 25 minutes). The release appears once all files are uploaded.

The release carries, per board, `vibedock-<board>-<version>.bin` (USB image), `.app.bin` (update over Wi-Fi) and
`.manifest.json`, and `index.json`: the client version, the protocol version the firmware speaks (the heading of
[`CLIENT-PROTOCOL.md`](vibedock-server/CLIENT-PROTOCOL.md)) and the SHA-256 and size of every file. A server finds the
release by its `client-v` tag and checks the files against `index.json` (`vibedock-client/scripts/release-index.py`).
Change the protocol version in that heading when the wire format changes incompatibly; servers take only firmware whose
protocol they understand. Until the first client tag exists, the firmware version is taken from the plain version tags.

The ruleset `release tags` has to cover `server-v*` and `client-v*` as well as `v*`.

**Order for the first release after the split:** publish a client release (`client-v…`) first and check that it carries
`index.json`, then publish the server release. A server whose image carries no firmware shows none until it has
downloaded a client release; a server without network access gets its firmware by upload (*Devices → Firmware*).

## Using the published image

- Docker: `docker pull mcfcologne/vibedock-server:latest` (or `ghcr.io/mcfcologne/vibedock-server:latest`).
- Compose: `VIBEDOCK_IMAGE=mcfcologne/vibedock-server:latest` in `vibedock-server/.env`, then
  `docker compose pull && docker compose up -d`.
- Synology Container Manager: *Registry* finds `mcfcologne/vibedock-server` on Docker Hub; *Image* then shows when a new
  version is available. Images from ghcr.io are not searched there and show no updates.
