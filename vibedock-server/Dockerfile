ARG TWEMOJI_VERSION=17.0.3
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
# its manifest there; the build then clears the cached packages and downloads them again once.
RUN --mount=type=bind,from=git,target=/repo.git --mount=type=cache,id=vibedock-platformio,target=/root/.platformio,sharing=locked \
    mkdir -p /out /w && if git --git-dir=/repo.git rev-parse -q --verify HEAD >/dev/null; then \
      git --git-dir=/repo.git archive HEAD vibedock-client/firmware vibedock-client/scripts | tar -x -C /w \
      && cd /w/vibedock-client/firmware && export GIT_DIR=/repo.git GIT_WORK_TREE=/w \
      && { sh ../scripts/images.sh /out || { rm -rf /root/.platformio/packages /root/.platformio/platforms /root/.platformio/.cache \
             && sh ../scripts/images.sh /out; }; }; \
    fi

# The JAR and the emoji set do not depend on the target platform: built natively, also when building for another one.
FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
# The usage API contract; a test checks real answers against it.
COPY usage.schema.json ./
COPY src ./src
COPY --from=version /version /version
RUN mvn -B verify -Drevision="$(cat /version)"

# Twemoji PNGs (graphics CC-BY 4.0) for the emoji in the status image; Java2D cannot draw colour emoji fonts.
FROM --platform=$BUILDPLATFORM alpine:3.22 AS twemoji
ARG TWEMOJI_VERSION
ARG TWEMOJI_SHA256=a0855654b633045ae2337537e77f1bb4361162f7fcd910e613eaab1d6d9c5fca
RUN wget -qO /tmp/twemoji.tgz "https://github.com/jdecked/twemoji/archive/refs/tags/v${TWEMOJI_VERSION}.tar.gz" \
    && echo "${TWEMOJI_SHA256}  /tmp/twemoji.tgz" | sha256sum -c - \
    && tar xzf /tmp/twemoji.tgz -C /tmp "twemoji-${TWEMOJI_VERSION}/assets/72x72" "twemoji-${TWEMOJI_VERSION}/LICENSE-GRAPHICS" \
    && mkdir /twemoji && mv "/tmp/twemoji-${TWEMOJI_VERSION}/assets/72x72" "/tmp/twemoji-${TWEMOJI_VERSION}/LICENSE-GRAPHICS" /twemoji/

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
COPY --from=twemoji /twemoji /app/twemoji
COPY scripts/license-inventory.mjs /app/license-inventory.mjs
ARG TWEMOJI_VERSION
RUN TWEMOJI_VERSION="$TWEMOJI_VERSION" node /app/license-inventory.mjs

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
COPY --from=twemoji /twemoji/72x72 /twemoji
COPY --from=runtime /app/legal/components.json /legal/components.json
RUN --mount=type=bind,from=git,target=/repo.git mkdir -p /out && if git --git-dir=/repo.git rev-parse -q --verify HEAD >/dev/null; then \
      git --git-dir=/repo.git archive HEAD docs/manual docs/logo.svg | tar -x -C / \
      && DOCS_COMMIT=$(git --git-dir=/repo.git rev-parse HEAD) DOCS_STAMP=$(git --git-dir=/repo.git show -s --format=%ct HEAD) \
         EMOJI_DIR=/twemoji DOCS_VERSION="$(cat /version)" DOCS_PDF=/out/vibedock-manual.pdf LICENSE_INVENTORY=/legal/components.json sh scripts/docs.sh; \
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
ENV DATA_DIR=/data ADMIN_BIND_ADDRESS=0.0.0.0 HOME=/data/home CODEX_HOME=/data/auth/codex CLAUDE_CONFIG_DIR=/data/auth/claude
EXPOSE 10002 10001
ENTRYPOINT ["/app/docker-entrypoint.sh"]
