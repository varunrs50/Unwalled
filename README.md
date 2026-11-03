# Unwalled

### Fuck ecosystems.

Your phone shouldn't decide which computer you're allowed to use. Unwalled links your **Android phone to your Mac** the way an iPhone works with a Mac: open source, local only, no accounts.

Calls, text messages, notifications, files, photos, clipboard and media controls from your Android phone, built into your Mac's menu bar, as native macOS notifications and windows. When you do need the phone itself, its screen opens in a Mac window you can control with your mouse and keyboard. It works over your home Wi-Fi with no cloud, no account and no server in between.

> **Status: early, but working.** Milestones M0–M8 are built and tested on a Pixel 9 Pro (Android 17) with an Apple Silicon MacBook Air (macOS 15+). Expect rough edges, and please [open an issue](../../issues) if something breaks.

---

## Contents

- [What it does](#what-it-does)
- [What it can't do (yet)](#what-it-cant-do-yet)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Quick install (downloads)](#quick-install-downloads)
- [Setup, step by step](#setup-step-by-step)
  - [Step 1: Install the build tools on your Mac](#step-1-install-the-build-tools-on-your-mac)
  - [Step 2: Get the code](#step-2-get-the-code)
  - [Step 3: Build and start the Mac app](#step-3-build-and-start-the-mac-app)
  - [Step 4: Turn on developer options on the phone](#step-4-turn-on-developer-options-on-the-phone)
  - [Step 5: Connect the phone to adb](#step-5-connect-the-phone-to-adb)
  - [Step 6: Build and install the Android app](#step-6-build-and-install-the-android-app)
  - [Step 7: Pair the phone with the Mac](#step-7-pair-the-phone-with-the-mac)
  - [Step 8: Grant the phone permissions](#step-8-grant-the-phone-permissions)
  - [Step 9 (optional): Automatic phone → Mac clipboard](#step-9-optional-automatic-phone--mac-clipboard)
  - [Step 10 (optional): Phone screen without asking](#step-10-optional-phone-screen-without-asking-even-when-locked)
  - [Step 11 (optional): Start Unwalled at login](#step-11-optional-start-unwalled-at-login)
- [Using Unwalled](#using-unwalled)
- [Troubleshooting](#troubleshooting)
- [Privacy and security](#privacy-and-security)
- [Updating](#updating)
- [Uninstalling](#uninstalling)
- [Project layout](#project-layout)
- [For developers](#for-developers)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Acknowledgements](#acknowledgements)

---

## What it does

| Feature | On the Mac |
|---|---|
| **Notifications** | Phone notifications appear as native macOS notifications with the app's icon. Reply inline (WhatsApp, Google Messages, Telegram…), tap action buttons, or dismiss them, and they clear on the phone too. |
| **Calls** | An incoming-call panel shows the caller's name and photo, with **Answer**, **Decline** and **Silence**. A **Calls** window has recent calls and contacts. Click a number, or any `tel:` link in Safari, Mail or Contacts, to dial it on the phone. Missed calls become notifications with **Call Back**. |
| **Text messages (SMS)** | A **Messages** window with your conversations. Read and send SMS from the Mac; texts go out from the phone's SIM. |
| **Find my phone** | Rings the phone at full alarm volume for up to a minute, even on silent. |
| **Now playing** | Track, artist and artwork from whatever is playing on the phone, with ⏮ ⏯ ⏭ in the menu bar. |
| **Clipboard** | Copy on the Mac and paste on the phone, or the other way round. Password-manager copies are never sent. |
| **Files** | Drag files onto the menu bar panel to send them to the phone, or share anything from the phone with **Send to Mac**. Every transfer is checked with a SHA-256 checksum. |
| **Photos** | A **Photos** window with every photo on the phone, browsable by album. Click one to download it. |
| **Insert from Phone** | Take a photo or scan a document with the phone and it's pasted straight into the Mac app you're in (Notes, Mail, Pages…). Photos work even with the phone locked. |
| **Phone screen** | The phone's screen in a Mac window: click to tap, drag or scroll to swipe, type into text fields, with Back, Home, Recents and an **Apps** list in the toolbar. Approve it once on the phone and it opens without asking after that. |
| **Status** | Battery, signal and Wi-Fi of the phone in the menu bar. |

## What it can't do (yet)

- **Call audio:** you answer on the Mac, but you talk through the phone, a headset or the speaker. Android doesn't let apps route cellular call audio to another device. Experiments are planned for 2.0.
- **RCS / iMessage-style chats:** only SMS are read and sent. Google Messages RCS chats still show up as notifications you can reply to.
- **One-time codes (OTPs):** Android 15+ hides SMS one-time codes from apps, so these show "No preview" in the Messages window. They still appear as notifications.
- **Away from home:** both devices must be on the same Wi-Fi network for now.
- **Separate app windows:** the phone screen opens as one window. Putting single apps in windows of their own needs system-level access that Android doesn't give regular apps.
- **Phone sound in the mirroring window:** not yet.

## How it works

```
┌───────────────────────────┐        Wi-Fi (same network)       ┌──────────────────────────┐
│ Android phone             │                                   │ Mac                      │
│                           │  ──── mutual-TLS 1.3, TCP 47820 ─▶│                          │
│ Unwalled app             │◀── JSON messages both directions ─│ Unwalled.app (menu bar) │
│  • foreground service     │                                   │  • Bonjour advertiser    │
│  • notification listener  │◀── UDP 47822 "wake up" nudge ─────│  • native notifications  │
│  • calls / SMS / media    │                                   │  • Calls, Messages,      │
│  • share target, QS tile  │                                   │    Photos windows        │
└───────────────────────────┘                                   └──────────────────────────┘
```

- The **Mac is the server.** It advertises itself on the local network with Bonjour (`_unwalled._tcp`) and listens on TCP port **47820**.
- The **phone is the client.** It finds the Mac with Bonjour, connects and stays connected from a foreground service. If the Mac restarts, it sends a small UDP "nudge" to the phone on port **47822** so the phone reconnects within a second.
- **Pairing** happens once by scanning a QR code. Both sides exchange certificate fingerprints, and every later connection uses mutual TLS 1.3 with those fingerprints pinned. A device that isn't paired can't connect.
- The full wire protocol is in [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md); the design and milestones are in [`PLAN.md`](PLAN.md).

## Requirements

| | Minimum | Tested on |
|---|---|---|
| **Phone** | Android 14 (API 34) with Google Play services, which handles QR scanning | Pixel 9 Pro, Android 17 |
| **Mac** | macOS 15 Sequoia | MacBook Air (Apple Silicon) |
| **Network** | Phone and Mac on the same Wi-Fi network, with devices allowed to see each other (no "client isolation") | Home Wi-Fi |
| **To build** | Xcode 16+ *or* the Xcode Command Line Tools (Swift 6), JDK 17+, Android SDK (platform 37) | JDK 21, Command Line Tools |

Unwalled is built for Pixel phones and tested on one, but any Android 14+ phone should work. Some manufacturers (Samsung, Xiaomi, OnePlus…) stop background apps more aggressively; see [Troubleshooting](#troubleshooting).

---

## Quick install (downloads)

The easiest way: download both apps from the [latest release](../../releases/latest). No developer tools needed.

**On the Mac** (Apple Silicon, macOS 15 or later):

1. Download **Unwalled-⟨version⟩-mac.zip**, open it, and drag **Unwalled** into **Applications**.
2. Open Unwalled. Unwalled isn't notarized by Apple yet, so the first time macOS says it can't be checked: open **System Settings → Privacy & Security**, scroll down, and click **Open Anyway** next to Unwalled.
3. Allow notifications and local network access when asked. The **Pair Phone** window opens with a QR code.

**On the phone** (Android 14 or later):

1. Download **Unwalled-⟨version⟩.apk** on the phone and open it. Allow your browser or Files app to install apps when asked.
2. Open Unwalled, allow local network access and notifications, and tap **Scan pairing code**. You can also scan the Mac's QR code with the normal **Camera** app; it opens Unwalled.
3. Tap **Pair**, then **Allow** on the Mac.
4. Work through the **Set up** checklist in the app (see [step 8](#step-8-grant-the-phone-permissions) for what each item does).

The optional extras in steps 9 and 10 need a one-time command over adb; for the downloaded app the package name is `app.unwalled` (without `.debug`).

## Setup, step by step

To build both apps from source instead, follow these steps. It takes about 15 minutes the first time.

### Step 1: Install the build tools on your Mac

**1.1 Swift (for the Mac app).** Either install **Xcode** from the App Store, or just the Command Line Tools:

```bash
xcode-select --install
```

If you installed full Xcode, accept its license once:

```bash
sudo xcodebuild -license accept
```

**1.2 Java (for the Android app).** Install JDK 17 or newer, for example from [Adoptium](https://adoptium.net/) or with Homebrew:

```bash
brew install --cask temurin
```

Check it works:

```bash
java -version
```

**1.3 Android SDK and adb.** The easiest way is [Android Studio](https://developer.android.com/studio): install it, open it once, and let it download the SDK. It goes to `~/Library/Android/sdk`. Then make `adb` available in your terminal by adding this to `~/.zshrc`:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

Open a new terminal window and check:

```bash
adb version
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

Gradle downloads the remaining SDK parts (platform 37, build tools) on the first build.

</details>

### Step 2: Get the code

```bash
git clone https://github.com/varunrs50/Unwalled.git
cd Unwalled
```

Tell the Android build where the SDK is. You can skip this if `ANDROID_HOME` is set from step 1.3:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
```

### Step 3: Build and start the Mac app

```bash
mac/scripts/build-app.sh release
```

This builds `mac/build/Unwalled.app` and signs it ad hoc for your Mac. Move it to Applications and open it:

```bash
cp -R mac/build/Unwalled.app /Applications/
open /Applications/Unwalled.app
```

A **Unwalled icon** appears in the menu bar; there's no Dock icon. macOS asks two things. Answer **Allow** to both:

1. **"Unwalled would like to send you notifications"**: needed for phone notifications, calls and file alerts.
2. **"Unwalled would like to find devices on your local network"**: needed for the phone to connect.

> **Firewall:** if the macOS firewall is on (System Settings → Network → Firewall), allow incoming connections for Unwalled when asked, or add it under **Options…**.

### Step 4: Turn on developer options on the phone

You only need this to install the app from your Mac and for the optional automatic clipboard.

1. On the phone, open **Settings → About phone**.
2. Tap **Build number** seven times until it says "You are now a developer". You may need to enter your PIN.
3. Go back to **Settings → System → Developer options** and turn on **USB debugging**.

### Step 5: Connect the phone to adb

Choose **one**:

**Option A: USB cable (simplest)**

1. Plug the phone into the Mac.
2. On the phone, tap **Allow** on "Allow USB debugging?". Tick "Always allow from this computer".
3. Check that the Mac sees it:

   ```bash
   adb devices
   ```

   It should list one device as `device`, not `unauthorized`.

**Option B: Wireless debugging (no cable)**

1. On the phone: **Developer options → Wireless debugging**. Turn it on, then tap **Pair device with pairing code**. It shows an IP address with a port, and a 6-digit code.
2. On the Mac, using the numbers shown on the phone:

   ```bash
   adb pair 192.168.1.23:37115
   ```

   Enter the 6-digit code when asked.
3. Back on the **Wireless debugging** screen, note the **IP address & Port** (a different port from pairing), then:

   ```bash
   adb connect 192.168.1.23:41234
   adb devices
   ```

### Step 6: Build and install the Android app

```bash
cd android
./gradlew installDebug
cd ..
```

The first build downloads Gradle and the libraries, which takes a few minutes. When it finishes, **Unwalled** is installed on the phone.

> Building and installing yourself produces the *debug* variant, whose package name is `app.unwalled.debug`. The commands below use that name.

### Step 7: Pair the phone with the Mac

1. On the **Mac**, the **Pair Phone** window opens by itself the first time (otherwise click the Unwalled menu bar icon, then **Pair a phone…**). It shows a QR code.
2. On the **phone**, open **Unwalled**.
   - Tap **Allow** for local network access, and **Allow** for notifications.
   - Tap **Scan pairing code** and point the camera at the QR code on the Mac, or scan it with the normal **Camera** app, which opens Unwalled.
   - No camera handy? Click **Copy code** on the Mac, get the text to the phone some other way, and paste it into the box.
3. The phone asks **"Pair with ⟨your Mac⟩?"**. Tap **Pair**.
4. The Mac asks **"Allow ⟨your phone⟩ to connect?"**. Click **Allow**.

The menu bar now shows your phone's name, battery and signal. Pairing is done once; after that the phone reconnects on its own whenever both devices are on the same Wi-Fi.

### Step 8: Grant the phone permissions

The Unwalled app on the phone shows a **Set up** checklist: a tick for each part that's done, and a button for each that isn't. Grant the ones you want:

| Item | What to do | Turns on |
|---|---|---|
| **Notifications** | Opens a settings screen. Find **Unwalled**, switch it on, and confirm. | Notifications on the Mac, replies, now playing |
| **Calls** | Allow the phone, call log and contacts prompts | Incoming calls, call history, dialling from the Mac |
| **Messages** | Allow the SMS prompt | Messages window |
| **Photos** | Choose **Allow all** | Photos window |
| **Run in the background** | Allow Unwalled to run in the background | Keeps the connection alive when the phone is idle |
| **Copy to Mac tile** (optional) | Tap **Add tile** | One-tap phone → Mac clipboard from Quick Settings |
| **Phone control** | In Accessibility, tap **Unwalled phone control** and switch it on. If Android says it's a *restricted setting*: **Settings → Apps → Unwalled → ⋮ → Allow restricted settings**, then try again. | Controlling the phone from its screen window on the Mac, and opening the camera for Insert from Phone |

> **Important:** don't skip the background (battery) permission. Without it Android may cut the connection after a while.

### Step 9 (optional): Automatic phone → Mac clipboard

Android doesn't let background apps read the clipboard. Without this step, sending the phone's clipboard takes one tap: the **Copy to Mac** Quick Settings tile, or the **Copy to Mac** button on Unwalled's notification.

To make it **automatic**, so copying on the phone is enough, Unwalled uses the same trick as KDE Connect. Android logs a line whenever an app is refused clipboard access. Unwalled watches for that line, then opens an invisible screen for a fraction of a second to read the clipboard. It needs two one-time permissions, run from the Mac with the phone connected to adb (step 5):

```bash
adb shell pm grant app.unwalled.debug android.permission.READ_LOGS
```

```bash
adb shell appops set app.unwalled.debug SYSTEM_ALERT_WINDOW allow
```

Then open Unwalled on the phone. Android 13+ shows **"Allow Unwalled to access all device logs?"**. Tap **Allow**.

Things to know:

- Android asks for log access again after the phone restarts or Unwalled is restarted. If automatic copying stops, open Unwalled and tap **Allow** again.
- The keyboard may close briefly right after you copy, because of the invisible screen.
- Text that just came from the Mac is never sent back, so nothing bounces between the two.
- To turn it off, clear the Mac clipboard sync checkbox, or remove the permission: `adb shell pm revoke app.unwalled.debug android.permission.READ_LOGS`.

### Step 10 (optional): Phone screen without asking, even when locked

By default, Android asks you to approve screen sharing on the phone the first time you open **Phone Screen**. Unwalled then keeps that approval until the phone restarts **or locks**. To skip the approval completely, so the phone's screen opens from the Mac even when the phone is locked in another room, run this once with the phone connected to adb (step 5):

```bash
adb shell appops set app.unwalled.debug PROJECT_MEDIA allow
```

It lasts until Unwalled is uninstalled; Wireless debugging can be switched off afterwards. To undo it: `adb shell appops set app.unwalled.debug PROJECT_MEDIA default`.

> Android may hide the lock screen from screen captures, so a locked phone can show up black on the Mac until it's unlocked on the phone.

### Step 11 (optional): Start Unwalled at login

In the Unwalled menu, choose **Settings…** (⌘,) and turn on **Open Unwalled when you log in**. The phone app starts on its own after the phone reboots.

---

## Using Unwalled

**Menu bar.** Click the Unwalled icon for the phone's status, recent notifications, now playing, file transfers, and these items:

| Menu item | What it does |
|---|---|
| **Messages…** | Opens your SMS conversations. Click **New Message** in the toolbar to start one. |
| **Calls…** | Recent calls and contacts. Click one to call it from the phone. |
| **Photos…** | All photos on the phone; pick an album from the toolbar. Click a photo to download it to **Downloads** and open it. |
| **Send File to Phone…** | Pick files to send. You can also drag files onto the open menu bar panel. |
| **Phone Screen** | Opens the phone's screen in a window. The first time, tap **Start** on the phone. Click to tap, drag or scroll to swipe, hold for a long press, right-click or Esc for Back, and type into the field you're in (⌘V types the Mac clipboard). The toolbar has Back, Home, Recents, Notifications and **Apps**. Closing the window pauses sharing; **End** in the menu stops it on the phone. |
| **Take Photo with Phone** / **Scan Documents with Phone** | Opens the camera or Google's document scanner on the phone. Take the shot and it's pasted where your cursor was on the Mac. Photos work with the phone locked; scans ask you to unlock. Several scanned pages arrive as one PDF. The first time, macOS asks to let Unwalled use **Accessibility** so it can paste for you; without it the result is put on the clipboard for you to press ⌘V. |
| **Find My Phone** | Rings the phone. Click **Stop Ringing Phone**, or tap **Stop** on the phone. |
| **Settings…** | Open at login, clipboard sharing, clear the history this Mac keeps from your phone, and unpair. |

**Calls.** When the phone rings, a panel appears at the top right of the screen: **Answer**, **Decline**, or **Silence** to stop the ringing only. After answering, talk on the phone or a connected headset.

**Notifications.** Phone notifications appear in macOS Notification Center. Hover and click **Reply** to answer messaging apps inline, or use the app's own buttons (Mark as read, Archive…). Clearing a notification on the Mac clears it on the phone.

**Files from the phone.** Share a file or photo from any app (Photos, Files, Chrome…) and choose **Send to Mac**. It lands in your Mac's **Downloads** folder, and clicking the notification reveals it in Finder. Files sent from the Mac go to **Downloads/Unwalled** on the phone.

**Dialling from anywhere.** Click any phone-number link on the Mac (`tel:` links in Safari, Mail, Contacts…) to call it from the phone.

---

## Troubleshooting

<details>
<summary><b>The menu bar says "Waiting for phone on Wi-Fi…"</b></summary>

- Check that both devices are on the **same Wi-Fi network**. Guest networks and some office or university networks block devices from seeing each other.
- Open Unwalled on the phone. If it shows a **local network** card, tap **Allow**.
- On the Mac: **System Settings → Privacy & Security → Local Network**: Unwalled must be on.
- If the macOS firewall is on, allow incoming connections for Unwalled.
- Restart the Unwalled Mac app. It nudges the phone to reconnect straight away.

</details>

<details>
<summary><b>The connection drops when the phone has been idle for a while</b></summary>

Grant the **background / battery** permission in the Unwalled app (step 8). On Samsung: **Settings → Battery → Background usage limits**, and add Unwalled to **Never sleeping apps**. On Xiaomi, OnePlus and others, see [dontkillmyapp.com](https://dontkillmyapp.com).

</details>

<details>
<summary><b>No notifications on the Mac</b></summary>

- On the phone: **Settings → Notifications → Device & app notifications**: Unwalled must have access.
- On the Mac: **System Settings → Notifications → Unwalled**: allow notifications, with the **Banners** or **Alerts** style.
- Notifications that were already on the phone before connecting only show in the menu bar, not as new banners.

</details>

<details>
<summary><b>The incoming-call panel doesn't appear</b></summary>

Make sure **Allow calls** was granted in the phone app. If it was granted while the apps were already connected, quit and reopen the Mac app once.

</details>

<details>
<summary><b>The Photos window is empty, or only shows some photos</b></summary>

Unwalled needs **Allow all** photo access. If you chose "Select photos", change it in **Settings → Apps → Unwalled → Permissions → Photos and videos → Allow all**. Photos stored only in Google Photos' cloud (not on the phone) can't be shown.

</details>

<details>
<summary><b>Phone → Mac clipboard doesn't work</b></summary>

- Without step 9, you need to tap **Copy to Mac** (Quick Settings tile or the notification button) **after** copying.
- With step 9, open Unwalled on the phone after a restart and tap **Allow** on the log-access prompt.
- Make sure **Share clipboard with phone** is on in **Settings…** on the Mac.

</details>

<details>
<summary><b>"Scan pairing code" does nothing</b></summary>

QR scanning uses Google Play services. Update Google Play services, or click **Copy code** on the Mac and paste the text into the box on the phone instead.

</details>

<details>
<summary><b>The build fails</b></summary>

- `SDK location not found`: create `android/local.properties` as in step 2.
- `Unsupported class file major version` or a Java error: use JDK 17 or newer (`java -version`).
- `You have not agreed to the Xcode license agreements`: run `sudo xcodebuild -license accept`. The Mac build script also falls back to the Command Line Tools on its own.

</details>

---

## Privacy and security

- **Nothing leaves your network.** There's no Unwalled server, no account, no analytics and no tracking. The phone talks only to the Mac it was paired with.
- **Encrypted and authenticated.** Every connection uses mutual TLS 1.3. The Mac and phone each have their own key: the phone's lives in the Android Keystore, the Mac's in Unwalled's app data. Each side pins the other's certificate fingerprint from pairing. Another device on your Wi-Fi can't connect or listen in.
- **Pairing needs both sides.** You confirm on the phone *and* on the Mac. Pairing codes expire after a few minutes.
- **Stored locally.** The Mac keeps caches of the message list, call log and contacts in `~/Library/Application Support/Unwalled/` so its windows open instantly. **Settings… → Clear History** deletes those caches, **Unpair** deletes the pairing, and deleting that folder removes everything.
- **Password managers.** Clipboard text that password managers mark as concealed is never sent to the phone.
- **Permissions are yours to grant.** Each feature has its own permission, and you can revoke any of them in Android settings at any time.

## Updating

```bash
cd Unwalled
git pull
mac/scripts/build-app.sh release && cp -R mac/build/Unwalled.app /Applications/
(cd android && ./gradlew installDebug)
```

Quit and reopen Unwalled on the Mac. You don't need to pair again.

## Uninstalling

- **Mac:** quit Unwalled from its menu, delete `/Applications/Unwalled.app` and `~/Library/Application Support/Unwalled/`.
- **Phone:** uninstall Unwalled like any app, or run `adb uninstall app.unwalled.debug`.

---

## Project layout

```
Unwalled/
├── mac/                        macOS menu bar app (Swift, SwiftUI, Network.framework)
│   ├── Sources/Unwalled/      AppModel, LinkServer (TLS + Bonjour), NotificationRelay,
│   │                           CallController, MessagesStore, ExtrasController,
│                           ScreenMirror (video decoding, touch input), views
│   ├── Resources/              Info.plist, entitlements
│   └── scripts/build-app.sh    builds and signs Unwalled.app
├── android/                    Android app (Kotlin, Jetpack Compose)
│   └── app/src/main/java/app/unwalled/
│       ├── link/               LinkService (foreground service), TLS socket, pairing, discovery
│       ├── notifications/      NotificationListenerService bridge
│       ├── calls/  sms/  contacts/
│       ├── extras/             find my phone, media, clipboard, files, photos, share sheet
│       ├── mirror/             screen capture and encoder, phone control (Accessibility), app list
│       └── camera/             Insert from Phone: camera and document scanner
├── protocol/PROTOCOL.md        wire protocol between phone and Mac
└── PLAN.md                     design, milestones and progress
```

## For developers

**Debug builds.**

```bash
mac/scripts/build-app.sh
```

```bash
(cd android && ./gradlew assembleDebug)
```

- The Mac app logs to stdout. Run it with `open --stdout /tmp/unwalled.log --stderr /tmp/unwalled.log mac/build/Unwalled.app` and follow the log with `tail -f`.
- The phone logs under the `Unwalled` tag: `adb logcat -s Unwalled`.

**Testing with the emulator and a second Mac instance.** Debug builds of the Mac app accept `UNWALLED_PORT` and `UNWALLED_DATA_DIR`, so a second copy can run next to your real one and pair with an Android emulator, which reaches the Mac at `10.0.2.2`:

```bash
open -n --env UNWALLED_PORT=47821 --env UNWALLED_DATA_DIR=/tmp/unwalled-test mac/build/Unwalled.app --args --pair
```

Simulate calls and texts on the emulator:

```bash
adb -s emulator-5554 emu gsm call 5551234
```

```bash
adb -s emulator-5554 emu sms send 5551234 "Hello"
```

**Making a release.** `scripts/release.sh` builds the signed APK, the Mac zip and checksums into `dist/<version>`; `scripts/release.sh --publish` also creates a draft GitHub release to review and publish. Bump `versionName`/`versionCode` in `android/app/build.gradle.kts` and `CFBundleShortVersionString`/`CFBundleVersion` in `mac/Resources/Info.plist` together first. The Android signing key must stay the same for every release.

**App icon.** `cd mac && swift scripts/make-icon.swift` regenerates `Resources/AppIcon.icns`; the Android version is `res/drawable/ic_launcher_foreground.xml`.

**Protocol.** Every message is a 4-byte big-endian length followed by a JSON envelope `{v, id, type, ts, body}`. All message types are documented in [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md). Keep it up to date when you add one.

## Roadmap

| Milestone | | Status |
|---|---|---|
| M0 | Project setup, build tooling | ✅ |
| M1 | Pairing, encrypted connection, phone status | ✅ |
| M2 | Notifications with reply, actions and dismiss | ✅ |
| M3 | Calls: incoming panel, answer/decline, dialling, call log | ✅ |
| M4 | SMS: conversations, read and send | ✅ |
| M5 | Files, photos, clipboard, find my phone, media controls | ✅ |
| M6 | Phone screen on the Mac: native capture, mouse and keyboard control, approve once | ✅ |
| M7 | Insert from Phone: photo and document scan pasted into the Mac app you're in | ✅ |
| M8 | Polish: settings, launch at login, setup checklist, app icon, prebuilt releases | ✅ |
| 2.0 | Away-from-home connection, call audio experiments, wireless webcam | Ideas |

## Contributing

Bug reports and pull requests are welcome. When reporting a bug, please include:

- Phone model and Android version, and your macOS version
- What you did, what you expected, and what happened
- Logs if you can: `adb logcat -s Unwalled` from the phone, and the Mac log (see [For developers](#for-developers))

Pull requests should keep the existing code style and update `protocol/PROTOCOL.md` for any new message type.

## License

Unwalled is released under the [Apache License 2.0](LICENSE). Copyright 2026 Varun Kumar R S.

## Acknowledgements

- [KDE Connect](https://kdeconnect.kde.org/), whose approach to automatic clipboard reading on modern Android Unwalled follows.

Unwalled is an independent project. It isn't affiliated with or endorsed by Google or Apple. Android and Pixel are trademarks of Google LLC; Mac, macOS and iPhone are trademarks of Apple Inc.
