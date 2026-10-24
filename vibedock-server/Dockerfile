# MANUAL=skip leaves out the manual (PDF and its screenshots), for quick local builds; the administration then shows no
# manual link.
ARG MANUAL=full
# The version comes from the Git tag of the sources (scripts/version.sh). Compose and scripts/image.sh pass the
# repository's .git as the build context "git"; a plain "docker build" gets this empty stage and builds 0.0.0-SNAPSHOT.
FROM scratch AS git
FROM --platform=$BUILDPLATFORM alpine:3.22 AS version
RUN apk add --no-cache git
RUN --mount=type=bind,from=git,target=/repo.git --mount=type=bind,target=/w/vibedock-server \
    GIT_DIR=/repo.git GIT_WORK_TREE=/w sh /w/vibedock-server/scripts/version.sh > /version && cat /version

# The display firmware from the same commit, offered for download and browser flashing in the administration. Without
# the "git" context the image has no firmware.
FROM --platform=$BUILDPLATFORM python:3.12-slim-bookworm AS firmware
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && git config --global safe.directory '*' && pip install --no-cache-dir "platformio==6.2.0"
# PlatformIO keeps its toolchain in a build cache. A build cancelled during the download leaves a package without
# its manifest there; the build then clears the cached packages and downloads them again once. VIBEDOCK_PRUNE drops
# each board's build directory and the ESP-IDF installer copies (cache/tools, several GB) to keep the disk use down.
RUN --mount=type=bind,from=git,target=/repo.git --mount=type=cache,id=vibedock-platformio,target=/root/.platformio,sharing=locked \
    mkdir -p /out /w && if git --git-dir=/repo.git rev-parse -q --verify HEAD >/dev/null; then \
      git --git-dir=/repo.git archive HEAD vibedock-client/firmware vibedock-client/scripts | tar -x -C /w \
      && cd /w/vibedock-client/firmware && export GIT_DIR=/repo.git GIT_WORK_TREE=/w \
      && export VIBEDOCK_PRUNE=1 && { sh ../scripts/images.sh /out || { rm -rf /root/.platformio/packages /root/.platformio/platforms /root/.platformio/.cache /root/.platformio/tools \
             && sh ../scripts/images.sh /out; }; }; \
    fi

# The Twemoji graphics versioned in the repository (third-party/twemoji, graphics CC BY 4.0; origin and checksum in
# its README.md), passed as the build context "twemoji" (compose.yaml, scripts/image.sh, the release workflow). The
# same files serve the tests, the manual and the server. Without that context this stage stops the build.
FROM --platform=$BUILDPLATFORM alpine:3.22 AS twemoji
RUN echo "The Twemoji graphics are missing: build with --build-context twemoji=../third-party/twemoji (see compose.yaml)" >&2 && exit 1

# The JAR does not depend on the target platform: built natively, also when building for another one.
FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
# Where the server and the tests look for them from /build (Twemoji.locate): ../third-party/twemoji/svg.
COPY --from=twemoji / /third-party/twemoji
# The usage API contract; a test checks real answers against it.
COPY usage.schema.json ./
COPY src ./src
COPY --from=version /version /version
RUN mvn -B verify -Drevision="$(cat /version)"

FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS node

FROM node:22-bookworm-slim AS login-clients
RUN npm install -g @openai/codex@0.162.1 @anthropic-ai/claude-code@2.1.296 @xai-official/grok@1.0.52 @google/gemini-cli@0.64.0 esptool-js@0.6.1

# The runtime without the application: everything installed that carries a license, and the inventory of it
# (/app/legal/components.json). Before the manual, so that the PDF lists the same components as the administration.
FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
COPY --from=login-clients /usr/local/bin/node /usr/local/bin/node
COPY --from=login-clients /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/@openai/codex/bin/codex.js /usr/local/bin/codex \
    && ln -s /usr/local/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe /usr/local/bin/claude \
    && ln -s /usr/local/lib/node_modules/@xai-official/grok/bin/grok-native /usr/local/bin/grok \
    && ln -s /usr/local/lib/node_modules/@google/gemini-cli/bundle/gemini.js /usr/local/bin/gemini \
    && mkdir -p /app /data && chown -R 10001:10001 /app /data
COPY --from=twemoji / /app/twemoji
COPY scripts/license-inventory.mjs /app/license-inventory.mjs
RUN node /app/license-inventory.mjs

# The user manual (docs/manual) as PDF, linked in the administration's footer: the screenshots are taken again from
# these sources with the demo data (scripts/docs.sh), then pandoc makes the PDF, ending with the
# licenses of the runtime stage's inventory. Like the firmware, the text comes
# from the "git" context; without it the image has no manual. Built natively, also when building for another platform.
FROM build AS manual-full
RUN apt-get update && apt-get install -y --no-install-recommends git pandoc texlive-xetex texlive-latex-recommended \
      texlive-latex-extra texlive-fonts-recommended lmodern fonts-dejavu-core && rm -rf /var/lib/apt/lists/* \
    && git config --global safe.directory '*'
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
COPY e2e/package.json e2e/package-lock.json ./e2e/
RUN cd e2e && npm ci --silent && npx playwright install --with-deps chromium
COPY scripts ./scripts
COPY e2e/docs ./e2e/docs
COPY --from=runtime /app/legal/components.json /legal/components.json
RUN --mount=type=bind,from=git,target=/repo.git mkdir -p /out && if git --git-dir=/repo.git rev-parse -q --verify HEAD >/dev/null; then \
      git --git-dir=/repo.git archive HEAD docs/manual docs/logo.svg | tar -x -C / \
      && DOCS_COMMIT=$(git --git-dir=/repo.git rev-parse HEAD) DOCS_STAMP=$(git --git-dir=/repo.git show -s --format=%ct HEAD) \
         DOCS_VERSION="$(cat /version)" DOCS_PDF=/out/vibedock-manual.pdf LICENSE_INVENTORY=/legal/components.json sh scripts/docs.sh; \
    fi

FROM alpine:3.22 AS manual-skip
RUN mkdir /out

FROM manual-${MANUAL} AS manual

FROM runtime
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/vibedock-server.jar /app/app.jar
COPY --from=firmware /out /app/firmware
COPY --from=manual /out /app/manual
COPY --from=login-clients /usr/local/lib/node_modules/esptool-js/bundle.js /app/firmware/esptool.js
COPY --chmod=755 docker-entrypoint.sh login-provider.sh /app/
USER 10001:10001
ENV DATA_DIR=/data ADMIN_BIND_ADDRESS=0.0.0.0 HOME=/dev/shm/vibedock-home CODEX_HOME=/dev/shm/vibedock-home/codex CLAUDE_CONFIG_DIR=/dev/shm/vibedock-home/claude
EXPOSE 10002 10001
ENTRYPOINT ["/app/docker-entrypoint.sh"]
