#!/bin/zsh
# Builds Unwalled's release downloads into dist/<version>: the signed Android APK, the Mac app
# as a zip (used by scripts/install.sh) and a disk image, and SHA256SUMS.txt. The files keep the
# same names in every release, so .../releases/latest/download/<name> always points at the newest. With --publish, also creates a *draft* GitHub release with them,
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
cp android/app/build/outputs/apk/release/app-release.apk "$DIST/Unwalled.apk"

echo "Building Mac $VERSION…"
mac/scripts/build-app.sh release
ditto -c -k --keepParent mac/build/Unwalled.app "$DIST/Unwalled-mac.zip"

# Disk image with an Applications shortcut to drag the app onto.
STAGE=$(mktemp -d)
cp -R mac/build/Unwalled.app "$STAGE/"
ln -s /Applications "$STAGE/Applications"
hdiutil create -quiet -volname "Unwalled $VERSION" -srcfolder "$STAGE" -format UDZO -ov "$DIST/Unwalled.dmg"
rm -rf "$STAGE"

(cd "$DIST" && shasum -a 256 Unwalled* > SHA256SUMS.txt)
echo "Built:"
ls -lh "$DIST"

if [[ "${1:-}" == "--publish" ]]; then
    NOTES=$(mktemp)
    cat > "$NOTES" <<EOF
Unwalled $VERSION: use your Android phone from your Mac.

**Install on the Mac** (Apple Silicon, macOS 15 or later): paste this into Terminal.

\`\`\`
curl -fsSL https://raw.githubusercontent.com/varunrs50/Unwalled/main/scripts/install.sh | bash
\`\`\`

Or download \`Unwalled.dmg\` and drag Unwalled to Applications. Unwalled isn't notarized by Apple yet, so the first time, go to **System Settings → Privacy & Security** and click **Open Anyway**.

**Install on the phone** (Android 14 or later): scan the **Get the phone app** code in the Mac's pairing window, or download \`Unwalled.apk\` on the phone and open it.

Then pair and set up as described in the [README](https://github.com/varunrs50/Unwalled#set-up).

Checksums are in \`SHA256SUMS.txt\`.
EOF
    PATH="$HOME/Android/bin:$PATH" gh release create "v$VERSION" "$DIST"/* --draft --title "Unwalled $VERSION" --notes-file "$NOTES"
    rm -f "$NOTES"
fi
