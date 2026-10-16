#!/bin/sh
# Prints the version of the current checkout from its Git tag: "0.1.0" on the tag 0.1.0 or v0.1.0,
# "0.1.0-3-g503ce5c" three commits later, with "-dirty" for uncommitted changes of the server. Without any version
# tag: "0.0.0-g<commit>"; without Git: "0.0.0-SNAPSHOT". The Docker build runs it as well, with GIT_DIR pointing to
# the repository's .git (see Dockerfile), so the image always shows the version of the sources it was built from.
set -eu
cd "$(dirname "$0")/.."
git() { command git -c safe.directory='*' -c core.fileMode=false "$@"; }
if version=$(git describe --tags --match 'v[0-9]*' --match '[0-9]*' 2>/dev/null); then :
elif commit=$(git rev-parse --short HEAD 2>/dev/null); then version="0.0.0-g$commit"
else echo "0.0.0-SNAPSHOT"; exit 0
fi
git diff --quiet HEAD -- . 2>/dev/null || version="$version-dirty"
echo "${version#v}"
