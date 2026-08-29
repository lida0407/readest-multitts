#!/usr/bin/env bash
# Builds the APK and publishes it as a GitHub release, so the in-app update
# check has something to find.
#
# Releases are cut here rather than in CI because every APK must be signed with
# the SAME key: Android refuses to install an update signed by a different one,
# and a fresh CI runner generates a new debug key on each run.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdks/temurin-17/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

VERSION=$(grep versionName app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
NOTES="${1:-Release v$VERSION}"
APK="Readest-MultiTTS-v${VERSION}.apk"

echo "Building v$VERSION…"
./gradlew assembleDebug --no-daemon -q
cp app/build/outputs/apk/debug/app-debug.apk "$APK"

echo "Signed with: $(keytool -printcert -jarfile "$APK" 2>/dev/null | grep -m1 SHA256 || echo unknown)"

git tag -f "v$VERSION"
git push -f origin "v$VERSION"

gh release create "v$VERSION" "$APK" --title "v$VERSION" --notes "$NOTES" 2>/dev/null \
  || gh release upload "v$VERSION" "$APK" --clobber

rm -f "$APK"
echo "Published v$VERSION"
