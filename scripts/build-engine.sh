#!/usr/bin/env bash
# Builds app/src/main/assets/noroot/engine.jar: the official LSPatch (JingMatrix fork) patcher
# converted to dex so it can run inside the app, together with its loader payload
# (assets/lspatch/loader.dex, metaloader.dex, native libs) that ends up inside the patched WhatsApp.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/noroot/engine.jar"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

: "${ANDROID_HOME:?ANDROID_HOME is not set}"

AUTH=()
if [ -n "${GH_TOKEN:-}" ]; then AUTH=(-H "Authorization: Bearer $GH_TOKEN"); fi

# Newest LSPatch build that ships a release patcher jar (stable or canary).
JAR_URL="${LSPATCH_JAR_URL:-}"
if [ -z "$JAR_URL" ]; then
  JAR_URL="$(curl -fsSL "${AUTH[@]}" "https://api.github.com/repos/JingMatrix/LSPatch/releases?per_page=30" \
    | jq -r '[.[] | .assets[] | select(.name | test("^lspatch-.*-release\\.jar$")) | .browser_download_url][0]')"
fi
if [ -z "$JAR_URL" ] || [ "$JAR_URL" = "null" ]; then
  echo "Could not find an LSPatch release jar" >&2
  exit 1
fi
echo "LSPatch engine: $JAR_URL"
curl -fsSL -o "$WORK/lspatch.jar" "$JAR_URL"

BUILD_TOOLS="$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
PLATFORM="$(ls "$ANDROID_HOME/platforms" | sort -V | tail -1)"
D8="$ANDROID_HOME/build-tools/$BUILD_TOOLS/d8"
echo "Using build-tools $BUILD_TOOLS and $PLATFORM"

cp "$WORK/lspatch.jar" "$WORK/in.jar"

# d8 gets only the bytecode: the jar also carries prebuilt .dex payloads (the loader) that it
# refuses to mix with class files. Multi-release and module descriptors are JVM-only.
mkdir -p "$WORK/classes"
(cd "$WORK/classes" && unzip -q "$WORK/in.jar" '*.class' -x 'META-INF/*' '*module-info.class')
(cd "$WORK/classes" && zip -q -r "$WORK/classes.jar" .)

"$D8" --release --min-api 28 \
  --lib "$ANDROID_HOME/platforms/$PLATFORM/android.jar" \
  --output "$WORK/dex.zip" "$WORK/classes.jar"

# Put the non-class resources (the loader payload and the patcher's assets) next to the dex.
mkdir -p "$WORK/res"
(cd "$WORK/res" && unzip -q "$WORK/in.jar" -x '*.class' 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/MANIFEST.MF')
(cd "$WORK/res" && zip -q -r "$WORK/dex.zip" .)

for required in classes.dex assets/lspatch/loader.dex assets/lspatch/metaloader.dex; do
  if ! unzip -l "$WORK/dex.zip" | grep -q " $required\$"; then
    echo "engine.jar is missing $required" >&2
    exit 1
  fi
done

mkdir -p "$(dirname "$OUT")"
mv "$WORK/dex.zip" "$OUT"
echo "Engine ready: $OUT ($(du -h "$OUT" | cut -f1))"
