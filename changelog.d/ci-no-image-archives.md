- **Releases without Docker image files:** a GitHub release now carries the server JAR and the firmware of every
  board only. The image is built and pushed straight to ghcr.io and Docker Hub as before (`<version>`, `latest` for a
  plain `x.y.z`, one tag for amd64 and arm64); for a machine without registry access `scripts/image.sh` or
  `docker save` makes the file. The files of earlier releases stay as they are.
