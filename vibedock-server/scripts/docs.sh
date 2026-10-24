#!/bin/sh
# Makes the screenshots of the user manual (docs/manual/images) again, always identical for the same sources: builds
# the server, starts it with the demo data of e2e/docs/screenshots.mjs (fixed clock, made-up usage and weather, no
# network access to providers), takes the pictures with Playwright's Chromium and stops it.
#   scripts/docs.sh                 version shown: the checkout's version (scripts/version.sh)
#   scripts/docs.sh serve           no screenshots: the demo server stays up at http://127.0.0.1:10001/admin for local work
#   DEMO_DATA=docker.data scripts/docs.sh serve   the same with that data directory (demo-… tokens give made-up usage)
#   DOCS_VERSION=0.1.0 scripts/docs.sh
#   EMOJI_DIR=…/twemoji/svg         another Twemoji SVG (or 72x72 PNG) set than the repository's third-party/twemoji/svg
#   PLAYWRIGHT_CHROMIUM=…/chromium  a Chromium of your own instead of Playwright's
#   DOCS_PDF=…/vibedock-manual.pdf    also makes the PDF (pandoc and XeLaTeX with DejaVu fonts; the Docker build does)
#   LICENSE_INVENTORY=…/components.json  the Docker image's components for the PDF's license list (the Docker build)
#   DOCS_COMMIT=<sha> DOCS_STAMP=<unix time>  the build's commit and its date (default: the checkout's HEAD)
set -eu
server=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$server"
version=${DOCS_VERSION:-$(sh scripts/version.sh)}
mvn -q -Drevision="$version" -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/docs-classpath.txt
# The Twemoji graphics versioned in the repository (third-party/twemoji, see its README.md), as for the server.
EMOJI_DIR=${EMOJI_DIR:-$(cd .. && pwd)/third-party/twemoji/svg}
[ -d "$EMOJI_DIR" ] || { echo "No Twemoji graphics in $EMOJI_DIR (EMOJI_DIR)" >&2; exit 1; }
[ -d e2e/node_modules ] || (cd e2e && npm ci --silent)
export EMOJI_DIR DOCS_VERSION="$version"
if [ "${1:-}" = serve ]; then cd e2e && exec node docs/screenshots.mjs --serve; fi
(cd e2e && node docs/screenshots.mjs)
if [ -n "${DOCS_PDF:-}" ]; then
    # Layout in docs/manual/style/manual.tex; fonts: DejaVu Sans and DejaVu Sans Mono.
    manual=$(cd ../docs/manual && pwd)
    # The build: version (with the commits since the tag), commit and its date; the date is also the PDF's date.
    commit=${DOCS_COMMIT:-$(git -c safe.directory='*' rev-parse HEAD 2>/dev/null || echo unknown)}
    stamp=${DOCS_STAMP:-$(git -c safe.directory='*' show -s --format=%ct "$commit" 2>/dev/null || echo 1791963660)}
    built=$(date -u -d "@$stamp" +%Y-%m-%d 2>/dev/null || date -u -r "$stamp" +%Y-%m-%d)  # GNU or BSD date
    printf '\\def\\vibedockversion{%s}\n\\def\\vibedockbuild{%s, %s}\n\\def\\vibedocklogo{%s}\n' \
        "$version" "$(echo "$commit" | cut -c1-7)" "$built" "$manual/images/logo.png" > target/manual-vars.tex
    # The commit's date in the PDF, so the same sources give the same file.
    # The third-party licenses of this build at the end of the License chapter: the JAR's Maven report, and in the
    # Docker build the image's inventory as well.
    node scripts/license-appendix.mjs target/classes/MAVEN-THIRD-PARTY.txt "${LICENSE_INVENTORY:-}" > target/manual-licenses.md
    SOURCE_DATE_EPOCH=$stamp FORCE_SOURCE_DATE=1 \
    pandoc "$manual/MANUAL.md" target/manual-licenses.md --resource-path="$manual" -o "$DOCS_PDF" --pdf-engine=xelatex --number-sections \
        --lua-filter="$manual/style/keep-with-figure.lua" -H target/manual-vars.tex -H "$manual/style/manual.tex" -V papersize=a4 -V fontsize=10pt -V linestretch=1.15 \
        -V geometry:top=2.8cm,bottom=2.6cm,left=2.3cm,right=2.3cm,headheight=24pt,headsep=0.7cm,footskip=1.1cm \
        -V mainfont="DejaVu Sans" -V monofont="DejaVu Sans Mono" -V monofontoptions=Scale=0.88
    echo "Manual in $DOCS_PDF"
fi
