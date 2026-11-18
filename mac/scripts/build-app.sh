#!/bin/zsh
# Builds Unwalled.app from the Swift package: compiles, wraps the binary in an
# app bundle with Info.plist, and ad-hoc signs it so macOS grants notifications
# and local-network access.
set -euo pipefail

cd "$(dirname "$0")/.."
CONFIG="${1:-debug}"

# Prefer Xcode when it's set up, even if xcode-select or DEVELOPER_DIR points at the Command Line Tools: since
# macOS 27.2 only Xcode has the SwiftUI macro plugin. Otherwise use whatever xcode-select chose.
XCODE=/Applications/Xcode.app/Contents/Developer
if DEVELOPER_DIR=$XCODE xcodebuild -checkFirstLaunchStatus >/dev/null 2>&1; then
    export DEVELOPER_DIR=$XCODE
elif ! xcodebuild -checkFirstLaunchStatus >/dev/null 2>&1; then
    export DEVELOPER_DIR=/Library/Developer/CommandLineTools
fi

swift build -c "$CONFIG"
BIN="$(swift build -c "$CONFIG" --show-bin-path)/Unwalled"

APP="build/Unwalled.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/Unwalled"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

codesign --force --sign - --entitlements Resources/Unwalled.entitlements "$APP"
echo "Built $APP"
