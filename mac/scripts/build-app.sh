#!/bin/zsh
# Builds PixelLink.app from the Swift package: compiles, wraps the binary in an
# app bundle with Info.plist, and ad-hoc signs it so macOS grants notifications
# and local-network access.
set -euo pipefail

cd "$(dirname "$0")/.."
CONFIG="${1:-debug}"

# Fall back to the Command Line Tools while the Xcode license is unaccepted.
if ! xcodebuild -checkFirstLaunchStatus >/dev/null 2>&1; then
    export DEVELOPER_DIR=/Library/Developer/CommandLineTools
fi

swift build -c "$CONFIG"
BIN="$(swift build -c "$CONFIG" --show-bin-path)/PixelLink"

APP="build/PixelLink.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/PixelLink"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

codesign --force --sign - --entitlements Resources/PixelLink.entitlements "$APP"
echo "Built $APP"
