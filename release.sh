#!/usr/bin/env bash
# Builds and publishes a GitHub release so the in-app update check finds it.
#
# Usage: ./release.sh [standard|bundled|both] "release notes"
#
# Two tracks are published from this repo and they never cross-update:
#   standard  tag v1.2.3          asset Readest-MultiTTS-v1.2.3.apk
#   bundled   tag v1.2.3-bundled  asset Readest-MultiTTS-bundled-v1.2.3.apk
# The app filters releases by the suffix on the tag and the name of the asset,
# so a phone on one track is never offered the other track's build.
#
# Releases are cut here rather than in CI because every APK must be signed with
# the SAME key: Android refuses an update signed by a different one, and a fresh
# CI runner generates a new debug key on each run.
set -euo pipefail
cd "$(dirname "$0")"

TRACK="${1:-both}"
NOTES_ARG="${2:-}"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdks/temurin-17/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

VERSION=$(grep versionName app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
NOTES="${NOTES_ARG:-Release v$VERSION}"

publish() { # flavor tag apk_name gradle_task apk_path
  local flavor="$1" tag="$2" apk="$3" task="$4" built="$5"
  echo "Building $flavor v$VERSION..."
  ./gradlew "$task" --no-daemon -q
  cp "$built" "$apk"

  git tag -f "$tag"
  git push -f origin "$tag"
  gh release create "$tag" "$apk" --title "$tag" --notes "$NOTES" 2>/dev/null \
    || gh release upload "$tag" "$apk" --clobber
  rm -f "$apk"
  echo "Published $tag"
}

if [ "$TRACK" = "standard" ] || [ "$TRACK" = "both" ]; then
  publish standard "v$VERSION" "Readest-MultiTTS-v${VERSION}.apk" \
    assembleStandardDebug app/build/outputs/apk/standard/debug/app-standard-debug.apk
fi

if [ "$TRACK" = "bundled" ] || [ "$TRACK" = "both" ]; then
  # The voice models are not in git; without them this build cannot speak.
  ./fetch-models.sh
  publish bundled "v$VERSION-bundled" "Readest-MultiTTS-bundled-v${VERSION}.apk" \
    assembleBundledDebug app/build/outputs/apk/bundled/debug/app-bundled-debug.apk
fi
