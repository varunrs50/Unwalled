#!/bin/bash
# Installs (or updates) Unwalled on this Mac from the latest GitHub release:
#
#   curl -fsSL https://raw.githubusercontent.com/varunrs50/Unwalled/main/scripts/install.sh | bash
#
# It checks the Mac, downloads the app, verifies its checksum, puts it in Applications and opens
# it. Nothing else is changed. Written for the bash that ships with macOS.
set -euo pipefail

REPO="varunrs50/Unwalled"
# UNWALLED_BASE overrides where the release files come from (for testing).
BASE="${UNWALLED_BASE:-https://github.com/$REPO/releases/latest/download}"
APP_NAME="Unwalled.app"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

say "Installing Unwalled…"

# The Mac app is built for Apple Silicon and macOS 15 or later.
[[ "$(uname -s)" == "Darwin" ]] || fail "Unwalled's Mac app only runs on macOS."
[[ "$(uname -m)" == "arm64" ]] || fail "Unwalled needs a Mac with Apple Silicon (M1 or later)."
major=$(sw_vers -productVersion | cut -d. -f1)
(( major >= 15 )) || fail "Unwalled needs macOS 15 or later (this Mac has $(sw_vers -productVersion))."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# UNWALLED_ZIP points at a local zip instead of downloading (for testing a build before release).
if [[ -n "${UNWALLED_ZIP:-}" ]]; then
    cp "$UNWALLED_ZIP" "$work/Unwalled-mac.zip"
else
    curl -fsSL -o "$work/Unwalled-mac.zip" "$BASE/Unwalled-mac.zip" \
        || fail "Couldn't download Unwalled. Check your internet connection and try again."
    curl -fsSL -o "$work/SHA256SUMS.txt" "$BASE/SHA256SUMS.txt" \
        || fail "Couldn't download Unwalled's checksums. Try again in a moment."
    expected=$(awk '$2 == "Unwalled-mac.zip" { print $1 }' "$work/SHA256SUMS.txt")
    actual=$(shasum -a 256 "$work/Unwalled-mac.zip" | awk '{ print $1 }')
    [[ -n "$expected" && "$expected" == "$actual" ]] || fail "The download didn't match its checksum, so it wasn't installed. Try again."
fi

ditto -x -k "$work/Unwalled-mac.zip" "$work/app"
[[ -d "$work/app/$APP_NAME" ]] || fail "The download didn't contain $APP_NAME."

# Applications if we can write there (admin accounts can), otherwise the user's own Applications.
# UNWALLED_INSTALL_DIR overrides it (for testing).
dest="${UNWALLED_INSTALL_DIR:-/Applications}"
[[ -w "$dest" ]] || { dest="$HOME/Applications"; mkdir -p "$dest"; }

# Quit a running copy so it can be replaced; pairing and settings are kept.
if [[ -z "${UNWALLED_NO_OPEN:-}" ]] && pgrep -xq Unwalled; then
    say "Quitting the running Unwalled…"
    osascript -e 'tell application id "app.unwalled.mac" to quit' >/dev/null 2>&1 || pkill -x Unwalled || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do pgrep -xq Unwalled || break; sleep 0.5; done
fi

rm -rf "$dest/$APP_NAME"
ditto "$work/app/$APP_NAME" "$dest/$APP_NAME"
# Downloaded with curl, so macOS hasn't marked it as quarantined; make sure anyway.
xattr -dr com.apple.quarantine "$dest/$APP_NAME" 2>/dev/null || true

version=$(/usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" "$dest/$APP_NAME/Contents/Info.plist" 2>/dev/null || echo "")
say "Unwalled ${version:+$version }is installed in $dest."
[[ -n "${UNWALLED_NO_OPEN:-}" ]] || open "$dest/$APP_NAME"

cat <<EOF

Next:
  1. Allow notifications and local network access when your Mac asks.
  2. In the Pair Phone window, choose "Get the phone app" and scan the code
     with your phone's Camera to install Unwalled on the phone.
  3. Switch back to "Pair", open Unwalled on the phone and scan the code.

Unwalled lives in your menu bar. Help: https://github.com/$REPO#set-up
EOF
