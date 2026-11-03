#!/bin/zsh
# Builds Unwalled's release downloads into dist/<version>: the signed Android APK, the Mac app
# as a zip, and SHA256SUMS.txt. With --publish, also creates a *draft* GitHub release with them,
# to review and publish on GitHub.
#
# The Android signing key defaults to ~/.unwalled/release.jks with its password in the macOS
# Keychain (service "unwalled-release-key"); UNWALLED_KEYSTORE / UNWALLED_KEYSTORE_PASSWORD
# override them. Every release must be signed with the same key, or phones won't update.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(sed -nE 's/.*versionName = "(.*)".*/\1/p' android/app/build.gradle.kts)
MAC_VERSION=$(/usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" mac/Resources/Info.plist)
[[ "$VERSION" == "$MAC_VERSION" ]] || { echo "Version mismatch: Android $VERSION, Mac $MAC_VERSION" >&2; exit 1; }

export UNWALLED_KEYSTORE="${UNWALLED_KEYSTORE:-$HOME/.unwalled/release.jks}"
[[ -f "$UNWALLED_KEYSTORE" ]] || { echo "No signing key at $UNWALLED_KEYSTORE" >&2; exit 1; }
export UNWALLED_KEYSTORE_PASSWORD="${UNWALLED_KEYSTORE_PASSWORD:-$(security find-generic-password -s unwalled-release-key -a release -w)}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"

DIST="dist/$VERSION"
rm -rf "$DIST"
mkdir -p "$DIST"

echo "Building Android $VERSION…"
(cd android && ./gradlew assembleRelease -q)
cp android/app/build/outputs/apk/release/app-release.apk "$DIST/Unwalled-$VERSION.apk"

echo "Building Mac $VERSION…"
mac/scripts/build-app.sh release
ditto -c -k --keepParent mac/build/Unwalled.app "$DIST/Unwalled-$VERSION-mac.zip"

(cd "$DIST" && shasum -a 256 Unwalled-* > SHA256SUMS.txt)
echo "Built:"
ls -lh "$DIST"

if [[ "${1:-}" == "--publish" ]]; then
    NOTES=$(mktemp)
    cat > "$NOTES" <<EOF
Unwalled $VERSION: use your Android phone from your Mac.

**Downloads**
- \`Unwalled-$VERSION-mac.zip\`: the Mac app, for Apple Silicon Macs on macOS 15 or later. Unzip and move **Unwalled** to Applications.
- \`Unwalled-$VERSION.apk\`: the Android app, for Android 14 or later. Open it on the phone to install (allow installing from your browser or Files app when asked).

**First launch on the Mac:** Unwalled isn't notarized by Apple yet, so macOS blocks it the first time. Open it once, then go to **System Settings → Privacy & Security** and click **Open Anyway**.

Then follow the setup steps in the [README](https://github.com/varunrs50/Unwalled#setup-step-by-step), starting from pairing.

Checksums are in \`SHA256SUMS.txt\`.
EOF
    PATH="$HOME/Android/bin:$PATH" gh release create "v$VERSION" "$DIST"/* --draft --title "Unwalled $VERSION" --notes-file "$NOTES"
    rm -f "$NOTES"
fi
