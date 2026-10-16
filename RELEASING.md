[VibeDock](README.md) › **Releasing**

# Releases, registries and repository settings

Where VibeDock is published, how the places are connected and what has to be set up for a release. Values of secrets
are never written down here or anywhere in the repository.

## Places

| What | Where |
| --- | --- |
| Source repository | <https://github.com/McFcologne/org.frifra.vibedock> (default branch `main`) |
| Releases (JAR, Docker image files, firmware) | <https://github.com/McFcologne/org.frifra.vibedock/releases> |
| Docker image on GitHub | `ghcr.io/mcfcologne/vibedock-server` – <https://github.com/McFcologne/org.frifra.vibedock/pkgs/container/vibedock-server> |
| Docker image on Docker Hub | `mcfcologne/vibedock-server` – <https://hub.docker.com/r/mcfcologne/vibedock-server> |
| Release notes | [`CHANGELOG.md`](CHANGELOG.md), the section `## <version>` |
| Docker Hub description | [`docs/DOCKERHUB.md`](docs/DOCKERHUB.md), sent with every image publication |

Both registries carry the same images: `<version>` and `latest` (only for a plain `x.y.z`) as one image for `amd64` and
`arm64`. On ghcr.io the single-architecture stages `<version>-amd64` and `<version>-arm64` are visible as well.

## How they are connected

```
git tag vX.Y.Z  ──push──▶  GitHub Actions: Release VibeDock (.github/workflows/release.yml)
                              ├─ server    → vibedock-server-<version>.jar
                              ├─ image     → vibedock-server-<version>-<arch>.tar.gz
                              │              and ghcr.io/…/vibedock-server:<version>-<arch>
                              ├─ publish-image → ghcr.io/…:<version>, :latest
                              │                  and Docker Hub …:<version>, :latest and its description
                              │                  from docs/DOCKERHUB.md (with the secrets below)
                              ├─ firmware  → vibedock-<board>-<version>.bin for every board
                              └─ release   → GitHub release (draft, files uploaded, then published)
push / pull request  ──────▶  GitHub Actions: Verify VibeDock (.github/workflows/verify.yml), check server-and-browser
```

- The **version** comes from the Git tag (`vibedock-server/scripts/version.sh`); `v0.1.0` and `0.1.0` both work. The
  server, its image and the firmware cannot be given a version by hand.
- The **ghcr.io package** is linked to the repository by the image labels and annotations
  `org.opencontainers.image.source`; it is public because the repository is, and lists the repository's README.
- **Docker Hub** gets the images only when both repository secrets are set; without them the run publishes to ghcr.io
  and says so in its log.
- **Displays** get their firmware from the server they are paired with, not from GitHub: a new server image brings the
  new firmware, and the administration offers it over Wi-Fi.

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
| Rules › Ruleset `release tags` | tags `v*`: only the repository admin creates, moves or deletes them |
| General › Release immutability | on: a published release can no longer be changed |
| Advanced Security | dependency graph, Dependabot alerts and security updates, secret protection with push protection |

A workflow that uses a new third-party action fails until that action is added to the allowed list.

## Making a release

1. Add a section `## X.Y.Z` to `CHANGELOG.md` and merge everything into `main`.
2. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z`.
3. Wait for *Release VibeDock* (about 15 minutes). The release appears once all files are uploaded.

Because releases are immutable, a mistake is fixed with a new version (`vX.Y.Z+1`), not by replacing files.

**Image only, for an existing tag:** *Actions › Release VibeDock › Run workflow* with the tag (e.g. `v0.1.0`) builds
and publishes that tag's image to the registries and leaves its release alone, for instance after a registry was added.

## Using the published image

- Docker: `docker pull mcfcologne/vibedock-server:latest` (or `ghcr.io/mcfcologne/vibedock-server:latest`).
- Compose: `VIBEDOCK_IMAGE=mcfcologne/vibedock-server:latest` in `vibedock-server/.env`, then
  `docker compose pull && docker compose up -d`.
- Synology Container Manager: *Registry* finds `mcfcologne/vibedock-server` on Docker Hub; *Image* then shows when a new
  version is available. Images from ghcr.io are not searched there and show no updates.
