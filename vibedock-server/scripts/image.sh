#!/bin/sh
# Builds the server image for linux/amd64 and/or linux/arm64 with the version of this checkout and saves each as a
# file for another machine (Synology: Container Manager > Image > Import; elsewhere: docker load -i <file>).
#   scripts/image.sh              both platforms
#   scripts/image.sh amd64        only amd64 (e.g. Intel/AMD NAS), arm64 for ARM servers and Apple Silicon
# Each image is tagged vibedock-server:<arch> only (the version is in the file name and the administration), so an import
# shows one image and a container on vibedock-server:<arch> takes the new one. Building for the other platform runs
# the login clients' installation under emulation (Docker Desktop includes it); the JAR is built natively.
# The files land in target/ as vibedock-server-<version>-<arch>.tar.
set -eu
cd "$(dirname "$0")/.."
# IDE run configurations start with a short PATH; Docker Desktop installs its CLI here.
PATH="$PATH:/usr/local/bin:/Applications/Docker.app/Contents/Resources/bin:$HOME/.docker/bin"
version=$(scripts/version.sh)
mkdir -p target
for arch in ${*:-amd64 arm64}; do
    case "$arch" in amd64|arm64) ;; *) echo "Unknown platform: $arch (amd64 or arm64)" >&2; exit 1 ;; esac
    file="target/vibedock-server-$version-$arch.tar"
    # --load and docker save: the docker driver of Docker Desktop cannot write the file itself (type=docker exporter).
    docker buildx build --platform "linux/$arch" --build-context git=../.git -t "vibedock-server:$arch" --load .
    docker save -o "$file" "vibedock-server:$arch"
    echo "$file"
done
