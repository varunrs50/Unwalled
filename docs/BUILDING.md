# Building Unwalled from source

This guide is for people who want to build Unwalled themselves or work on its code. **To just use Unwalled, you don't need any of this:** download it as described in the [README](../README.md#install).

## Contents

- [What you need](#what-you-need)
- [Step 1: Install the build tools](#step-1-install-the-build-tools)
- [Step 2: Get the code](#step-2-get-the-code)
- [Step 3: Build and run the Mac app](#step-3-build-and-run-the-mac-app)
- [Step 4: Connect a phone with adb](#step-4-connect-a-phone-with-adb)
- [Step 5: Build and install the Android app](#step-5-build-and-install-the-android-app)
- [Debugging](#debugging)
- [Testing with the emulator](#testing-with-the-emulator)
- [Making a release](#making-a-release)
- [Project layout](#project-layout)
- [Build problems](#build-problems)

## What you need

| | |
|---|---|
| **Mac** | macOS 15 or later, with Xcode 16+ *or* just the Xcode Command Line Tools (Swift 6) |
| **Android** | JDK 17 or later and the Android SDK (platform 37) |
| **Phone** | Android 14 or later, with developer options turned on |

## Step 1: Install the build tools

**Swift (for the Mac app).** Either install **Xcode** from the App Store, or just the Command Line Tools:

```bash
xcode-select --install
```

If you installed full Xcode, accept its license once:

```bash
sudo xcodebuild -license accept
```

**Java (for the Android app).** Install JDK 17 or newer, for example from [Adoptium](https://adoptium.net/) or with Homebrew:

```bash
brew install --cask temurin
```

**Android SDK and adb.** The easiest way is [Android Studio](https://developer.android.com/studio): install it, open it once, and let it download the SDK to `~/Library/Android/sdk`. Then add this to `~/.zshrc` and open a new terminal:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

<details>
<summary>Prefer not to install Android Studio?</summary>

Download the "Command line tools only" package from the bottom of the [Android Studio download page](https://developer.android.com/studio#command-line-tools-only), unzip it to `~/Library/Android/sdk/cmdline-tools/latest`, then:

```bash
~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools"
```

```bash
~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --licenses
```

Gradle downloads the remaining SDK parts on the first build.

</details>

## Step 2: Get the code

```bash
git clone https://github.com/varunrs50/Unwalled.git
cd Unwalled
```

If `ANDROID_HOME` isn't set, tell the Android build where the SDK is:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
```

## Step 3: Build and run the Mac app

```bash
mac/scripts/build-app.sh release
```

This builds `mac/build/Unwalled.app` and signs it ad hoc. Leave out `release` for a debug build with test hooks. To install and open it:

```bash
cp -R mac/build/Unwalled.app /Applications/ && open /Applications/Unwalled.app
```

## Step 4: Connect a phone with adb

1. On the phone, open **Settings → About phone** and tap **Build number** seven times.
2. In **Settings → System → Developer options**, turn on **USB debugging**.
3. Connect with **one** of these:
   - **USB cable:** plug the phone in, tap **Allow** on "Allow USB debugging?", and check that `adb devices` lists it as `device`.
   - **Wireless debugging:** in Developer options, turn on **Wireless debugging** and tap **Pair device with pairing code**. Run `adb pair <ip>:<pairing port>` with the code shown, then `adb connect <ip>:<port>` with the port shown on the Wireless debugging screen.

## Step 5: Build and install the Android app

```bash
(cd android && ./gradlew installDebug)
```

Self-built debug installs use the package name **`app.unwalled.debug`**, so they can sit next to a downloaded copy (`app.unwalled`). Then pair and set up as in the [README](../README.md#set-up).

## Debugging

- **Mac:** the app logs to stdout. Run it with `open --stdout /tmp/unwalled.log --stderr /tmp/unwalled.log mac/build/Unwalled.app` and follow it with `tail -f /tmp/unwalled.log`.
- **Phone:** `adb logcat -s Unwalled`.

## Testing with the emulator

Debug builds of the Mac app accept `UNWALLED_PORT` and `UNWALLED_DATA_DIR`, so a second copy can run next to your real one and pair with an Android emulator, which reaches the Mac at `10.0.2.2`:

```bash
open -n --env UNWALLED_PORT=47821 --env UNWALLED_DATA_DIR=/tmp/unwalled-test mac/build/Unwalled.app --args --pair
```

To pair the emulator, open the `unwalled://pair?…` link from the test copy's log, with `host` changed to `10.0.2.2`:

```bash
adb -s emulator-5554 shell am start -a android.intent.action.VIEW -d '"unwalled://pair?…"'
```

Simulate calls and texts:

```bash
adb -s emulator-5554 emu gsm call 5551234
```

```bash
adb -s emulator-5554 emu sms send 5551234 "Hello"
```

## Making a release

1. Bump the version in both apps together: `versionName`/`versionCode` in `android/app/build.gradle.kts`, and `CFBundleShortVersionString`/`CFBundleVersion` in `mac/Resources/Info.plist`.
2. Run `scripts/release.sh`. It builds the signed APK, the Mac zip and checksums into `dist/<version>`.
3. Run `scripts/release.sh --publish` to also create a **draft** GitHub release, then review and publish it on GitHub.

The release files keep the same names every time (`Unwalled-mac.zip`, `Unwalled.dmg`, `Unwalled.apk`, `SHA256SUMS.txt`), so `releases/latest/download/<name>` always gets the newest; the one-line installer (`scripts/install.sh`) and the Mac's "Get the phone app" code rely on that. To test the installer before a release, point it at local files: `UNWALLED_BASE=file://<folder> UNWALLED_INSTALL_DIR=<folder> UNWALLED_NO_OPEN=1 bash scripts/install.sh`.

The Android signing key (`~/.unwalled/release.jks`, password in the macOS Keychain) must stay the same for every release; otherwise phones can't update.

**App icon:** `cd mac && swift scripts/make-icon.swift` regenerates `Resources/AppIcon.icns`; the Android version is `res/drawable/ic_launcher_foreground.xml`.

**Protocol:** every message is a 4-byte big-endian length followed by a JSON envelope `{v, id, type, ts, body}`. All message types are in [`protocol/PROTOCOL.md`](../protocol/PROTOCOL.md); keep it up to date when you add one. The design and milestones are in [`PLAN.md`](../PLAN.md).

## Project layout

```
Unwalled/
├── mac/                        macOS menu bar app (Swift, SwiftUI, Network.framework)
│   ├── Sources/Unwalled/       AppModel, LinkServer (TLS + Bonjour), NotificationRelay,
│   │                           CallController, MessagesStore, ExtrasController,
│   │                           ScreenMirror (video decoding, touch input), views
│   ├── Resources/              Info.plist, entitlements, app icon
│   └── scripts/                build-app.sh (builds and signs the app), make-icon.swift
├── android/                    Android app (Kotlin, Jetpack Compose)
│   └── app/src/main/java/app/unwalled/
│       ├── link/               LinkService (foreground service), TLS socket, pairing, discovery
│       ├── notifications/      NotificationListenerService bridge
│       ├── calls/  sms/  contacts/
│       ├── extras/             find my phone, media, clipboard, files, photos, share sheet
│       ├── mirror/             screen capture and encoder, phone control (Accessibility), app list
│       └── camera/             Insert from Phone: camera and document scanner
├── protocol/PROTOCOL.md        wire protocol between phone and Mac
├── scripts/release.sh          builds release downloads
└── PLAN.md                     design, milestones and progress
```

## Build problems

- **`SDK location not found`:** create `android/local.properties` as in step 2.
- **`Unsupported class file major version`** or another Java error: use JDK 17 or newer (`java -version`).
- **`You have not agreed to the Xcode license agreements`:** run `sudo xcodebuild -license accept`. The Mac build script also falls back to the Command Line Tools on its own.
