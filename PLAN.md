# PixelLink — v1 Build Plan

*Working name. Continuity-style link between a Pixel 9 Pro (Android 17) and a Mac, same Wi-Fi only.*

## 1. Goal

Make the Mac feel like it "has" the phone — the way an iPhone and Mac work together — without screen mirroring. Every feature is a native Mac UI backed by data the phone sends over the local network.

**v1 scope**

| Area | What the user gets on the Mac |
|---|---|
| Notifications | Every phone notification as a real macOS notification, inline reply where the app supports it, dismiss syncs both ways |
| SMS | Messages window: threads, history, send texts through the SIM, live updates |
| Calls | Incoming-call popup (name, photo) with Answer / Decline; click-to-call; call log; contacts |
| Files & photos | "Send to Mac" from Android share sheet; drag files onto the menu bar to send to phone; recent photos browser |
| Clipboard | Mac → phone automatic; phone → Mac with one tap |
| Status & extras | Battery / charging / signal in menu bar; Find my phone (ring); now-playing media controls |
| Screen mirroring | The phone's screen in a Mac window with mouse and keyboard control and phone audio; any single Android app in its own Mac window |
| Camera | "Insert from Pixel": take a photo or scan a document on the phone and drop it into the current Mac app. Webcam use relies on the Pixel's built-in USB webcam mode. |

**Not in v1** (planned for 2.0): call audio on the Mac (Bluetooth hands-free and adb voice-call capture experiments), wireless webcam (virtual camera), working away from home Wi-Fi, RCS history.

**Known platform limits** (accepted):
- RCS chats can't be read by third-party apps. New RCS messages still arrive as notifications and can be replied to from there.
- Call audio stays on the phone (or its earbuds) in v1.
- Android blocks background clipboard reads and background screen launches, so phone → Mac clipboard and "open link on phone" each need one tap.

## 2. Architecture

```
┌──────────── Pixel (Kotlin) ────────────┐        ┌────────────── Mac (Swift) ──────────────┐
│ LinkService (foreground service)       │        │ LinkServer (Network.framework)          │
│  ├─ Connection: discover + TLS client  │◀──────▶│  ├─ Bonjour advertise _pixellink._tcp   │
│  ├─ NotificationBridge                 │  TLS   │  ├─ TLS 1.3, pinned phone certificate   │
│  ├─ SmsBridge                          │ frames │  └─ Router → feature stores             │
│  ├─ CallBridge                         │        │ Feature stores (Notifications, Sms,     │
│  ├─ MediaBridge (photos, files)        │        │   Calls, Files, Status)                 │
│  ├─ ClipboardBridge                    │        │ UI: MenuBarExtra + Messages window +    │
│  └─ StatusBridge                       │        │   Calls window + macOS notifications    │
│ NotificationListenerService            │        │ Local cache (SQLite) for SMS + call log │
│ Share target, Quick Settings tile      │        │ Keychain: Mac identity + paired phone   │
│ Pairing screen (QR scanner)            │        └─────────────────────────────────────────┘
└────────────────────────────────────────┘
```

**Roles.** The Mac is the server (always on, stable address) and the phone is the client. The phone reconnects whenever it joins the home Wi-Fi.

**Phone app (Kotlin, Jetpack Compose UI)**
- `LinkService` is a foreground service (type `connectedDevice`) with a small permanent notification. It owns the connection and all feature bridges.
- `NotificationListenerService` reads posted and removed notifications, triggers reply actions (`RemoteInput`) and dismissals, and grants access to media sessions.
- `SmsBridge` reads `content://sms` through a ContentObserver for live changes and sends with `SmsManager`.
- `CallBridge` watches call state with `TelephonyCallback`. It answers and ends calls through `TelecomManager` (`acceptRingingCall`, `endCall`) and dials with `placeCall`.
- Files: a share-target activity ("Send to Mac"). Incoming files are saved to `Downloads/PixelLink` through MediaStore.
- Clipboard: Mac → phone uses `setPrimaryClip` from the service. Phone → Mac uses a Quick Settings tile that opens a transparent activity for a moment, so the read happens in the foreground.
- Onboarding: a checklist screen that walks through each permission in order and shows what is still missing.

**Mac app (Swift 6, SwiftUI)**
- A `MenuBarExtra` shows status, recent notifications and quick actions. It is also the drop target for sending files.
- Separate windows for **Messages** (thread list plus conversation) and **Calls** (call log, contacts, dial pad).
- `UserNotifications` handles native notifications, with a text-input action for reply and buttons for Answer / Decline.
- `Network.framework` provides `NWListener` with Bonjour advertising and TLS.
- SMS threads and the call log are cached in SQLite, so the windows open instantly and show recent history even while the phone is offline.
- Launch at login uses `SMAppService`.

**Screen mirroring (native)**

Mirroring runs inside PixelLink itself, so nobody needs developer options. An earlier version used adb and scrcpy; it was dropped because Android 17 won't let apps switch Wireless debugging on, and Android switches it off after every restart and Wi-Fi change.

- The phone captures its screen with MediaProjection and the hardware H.264 encoder, and streams it over a pinned TLS connection to the Mac's port + 3. The Mac decodes it in hardware (AVSampleBufferDisplayLayer) in a window shaped like the phone.
- Control goes through an Accessibility service the user switches on once: a live finger made of continued gesture strokes (clicks, drags, trackpad scrolling with Android's own fling), global actions (Back, Home, Recents, Notifications) and typing into the focused field.
- **Approve once:** Android asks for every capture session, so PixelLink keeps an approved session open but paused (nothing captured or sent) and resumes it without asking. Android still ends it on restart and on lock (`STOP_REASON_KEYGUARD`), unless the app has the `PROJECT_MEDIA` app-op, which is granted once over adb and also skips the dialog.
- While showing, the phone holds a dim-screen wake lock (the mirrored screen is the real one); closing the window releases it.
- **Not possible natively:** separate windows for single apps. Launching other apps onto an extra display needs system privileges. A later option is a Shizuku-style helper started over Wireless debugging once per restart.

**Camera**

- **Insert from Pixel:** the Mac sends `camera.capture` (photo or document scan). The phone shows a "Tap to take photo" notification, because Android blocks apps from opening screens in the background. The result is sent back and pasted into the app you're using.
- **Webcam:** use the Pixel's built-in USB webcam mode (plug in, choose "Webcam"). A wireless virtual camera needs a macOS camera system extension, which likely requires a paid Apple Developer account, so it's planned for 2.0.

## 3. Pairing & security

Nothing ever leaves the local network, and there is no cloud or account.

1. **Identity.** On first run each side generates a long-term key pair and a self-signed certificate, stored in the Android Keystore on the phone. On the Mac it is a file in Application Support readable only by you (moves to the Keychain once the app is signed with a real developer certificate).
2. **Pairing (one time).** The Mac shows a QR code containing `{mac_name, ip:port, mac_cert_sha256, one_time_token}`. The phone scans it with Google's code scanner (no camera permission needed) and asks you to confirm. It connects over TLS, checks that the Mac's certificate matches the fingerprint, and sends its own certificate along with the token. The Mac asks "Allow <phone>?" Both sides then store the other's fingerprint. Full details in `protocol/PROTOCOL.md`.
3. **Every connection after that.** Mutual TLS 1.3. Each side accepts only the pinned certificate. Unknown devices on the Wi-Fi are rejected before any data is exchanged.
4. **Unpairing.** Either side can forget the other, which deletes the pinned fingerprint.
5. **Data at rest.** The Mac's SMS and call cache lives in the app's sandbox container. A "clear history" button wipes it.

## 4. Connection & protocol

- **Discovery.** The Mac advertises `_pixellink._tcp` over Bonjour. The phone finds it with `NsdManager` and falls back to the last known IP.
- **Reconnect.** The phone listens for Wi-Fi changes (`ConnectivityManager.NetworkCallback`) and reconnects with backoff (1 s → 30 s).
- **Heartbeat** every 30 s in both directions. After 2 missed heartbeats the connection is marked offline.
- **Framing.** Each frame is a 4-byte length followed by a JSON envelope `{v, id, type, ts, body}`. Files and photos are sent as chunked binary frames (256 KB) with a SHA-256 check at the end.
- **Requests and events.** The phone pushes events (`*.posted`, `*.changed`). The Mac sends requests and gets a matching reply using the same `id`.

| Type | Direction | Purpose |
|---|---|---|
| `hello` / `hello.ack` | both | Protocol version, device names, capabilities |
| `status.update` | phone → Mac | Battery, charging, signal, Wi-Fi |
| `notif.posted` / `notif.removed` | phone → Mac | Notification with app name, icon, title, text and available actions |
| `notif.action` / `notif.reply` / `notif.dismiss` | Mac → phone | Act on a phone notification |
| `sms.threads` / `sms.messages` | Mac → phone (request) | Page through threads and messages |
| `sms.changed` | phone → Mac | New or updated message |
| `sms.send` | Mac → phone | Send a text, with a delivery status reply |
| `call.state` | phone → Mac | Ringing, active or ended, with number, contact and photo |
| `call.answer` / `call.end` / `call.dial` | Mac → phone | Control calls |
| `calllog.page` / `contacts.sync` | Mac → phone | History and contacts |
| `photos.recent` / `photos.get` | Mac → phone | Thumbnails, then full image on demand |
| `file.offer` / `file.chunk` / `file.done` | both | File transfer |
| `clip.set` | both | Clipboard text |
| `media.state` / `media.control` | both | Now playing and play/pause/skip |
| `phone.ring` | Mac → phone | Find my phone (alarm stream, ignores silent mode) |
| `camera.capture` / `camera.result` | both | Insert from Pixel (photo or scan) |
| `screen.*` / `input.*` / `apps.*` | both | Screen mirroring, control and app launching (see PROTOCOL.md) |

The protocol is written down in `protocol/PROTOCOL.md`, with JSON samples that both apps test against so they can't drift apart.

## 5. Android permissions & onboarding order

The app is installed directly (adb or APK), not from the Play Store, which is what allows the SMS and call permissions.

| Step | Permission | Why |
|---|---|---|
| 1 | Local network access (Android 17, `ACCESS_LOCAL_NETWORK`) | Reach the Mac on the Wi-Fi; without it connections are silently dropped |
| 2 | Post notifications | Show the permanent "connected" notification |
| 3 | Notification access (special setting) | Mirror, reply to and dismiss notifications; media controls |
| 4 | SMS: `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS` | Messages window |
| 5 | Phone: `READ_PHONE_STATE`, `READ_CALL_LOG`, `ANSWER_PHONE_CALLS`, `CALL_PHONE` | Calls |
| 6 | Contacts | Names and photos |
| 7 | Photos (`READ_MEDIA_IMAGES`, full access) | Photo browser |
| 8 | Ignore battery optimisation | Stop Android from killing the connection |
| 9 | Screen capture approval (each session) and the Accessibility service "PixelLink phone control"; optionally `PROJECT_MEDIA` granted once over adb | Phone screen on the Mac and controlling it |

If Android shows "Restricted setting" for notification access or SMS, the onboarding screen links to **App info → ⋮ → Allow restricted settings**.

## 6. Repository layout

```
pixellink/
├── PLAN.md
├── protocol/        PROTOCOL.md + JSON samples
├── android/         Gradle project (Kotlin, Compose), minSdk 34
└── mac/             Xcode project (Swift 6, SwiftUI), macOS 15+
```

## 7. Milestones

Each milestone ends with something running on your Pixel and Mac that you can try.

| # | Milestone | Done when |
|---|---|---|
| **M0** | Toolchain & skeletons | JDK, Android SDK and Gradle installed; Xcode installed (by you). Empty phone app installs through adb; empty menu bar app launches. |
| **M1** | Pairing & connection | QR pairing works; battery and signal appear in the menu bar; the connection survives Wi-Fi off/on, phone reboot and 1 hour with the phone asleep. |
| **M2** | Notifications | Phone notifications appear on the Mac within 1 s; reply works in WhatsApp and Google Messages; dismissing on either side clears both. |
| **M3** | Calls | Incoming call popup with name and photo; Answer and Decline work; click-to-call dials; call log window. |
| **M4** | SMS | Messages window shows all threads; sending works with delivery status; new messages appear live. |
| **M5** | Files, photos, clipboard, extras | Share sheet → Mac and drag → phone; recent photos; clipboard both ways; Find my phone; media controls. |
| **M6** | Screen mirroring | Phone window with mouse and keyboard control from the Mac, native (no developer options), approved once. |
| **M7** | Camera | Insert from Pixel (photo and scan) works in Notes, Mail and Finder; USB webcam mode documented in onboarding. |
| **M8** | Polish & hardening | Onboarding checklist, settings, launch at login, unpair, clear history; battery drain under 3 %/day from the app. |

M1 has to be solid before anything else. A flaky connection would make every later feature feel broken.

Mirroring comes after the everyday features (M2–M5) because those are what you'll use all day.

## 8. Testing

- **Protocol.** Both apps decode and encode the shared JSON samples in unit tests.
- **Phone side through adb.** `adb shell cmd notification post …` creates test notifications; logcat is filtered to the app's tag; `dumpsys battery` simulates battery levels.
- **End to end on real devices** for each milestone's checklist: real SMS and calls, and a test call from another number.
- **Soak test.** Leave both connected overnight and check reconnects, memory and battery.

## 9. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Android kills the background service | Connection drops silently | Foreground service, battery exemption, reconnect on Wi-Fi events; measured in the M1 soak test |
| Reply only works where apps provide `RemoteInput` | Some apps can't be replied to | Show the reply box only when the notification supports it |
| Silencing the ringer needs privileges a normal app lacks | "Silence" button may not work | Offer Decline instead; check `silenceRinger` in M3 |
| Android 17 is a beta | APIs may change | Pin target SDK; re-test after each beta update |
| Bonjour unreliable on some routers | Phone can't find the Mac | Fall back to the last known IP; manual IP entry in settings |
| Mac app without full Xcode | Notifications and signing are fiddly | Install Xcode before M0 |
| RCS history inaccessible | Messages window misses RCS chats | Explain it in the UI; RCS still works through notifications |
| Android ends screen sharing on lock | Phone screen needs approving again | Keep the approved session paused; document the one-time `PROJECT_MEDIA` grant |
| Accessibility is a "restricted setting" for sideloaded apps | Users can't switch phone control on | In-app card explains "Allow restricted settings" |

## 10. After v1 (2.0 ideas)

- **Call audio on the Mac.** Two experiments: (1) the Mac acts as a Bluetooth hands-free device (`IOBluetoothHandsFreeDevice`), which needs testing on current macOS and Apple Silicon; (2) capture call audio over adb (scrcpy's voice-call source), which may only work for hearing the caller and varies by device.
- **Wireless webcam.** Phone camera streamed over Wi-Fi and exposed as a macOS camera through a CoreMediaIO camera extension. Confirm whether a paid Apple Developer account is required.
- **Away from home.** Tailscale or a small relay; the pinned-certificate model stays the same.
- **Universal links / Handoff.** Send a link or tab between the devices.
- MMS images in the Messages window, group SMS, notification filtering rules per app.

## 11. Open decisions

- Final app name.
- macOS version minimum: 15 assumed.
- Whether to get a paid Apple Developer account (decides the wireless webcam in 2.0).

## 12. Progress

| Milestone | Status | Notes |
|---|---|---|
| M0 Toolchain & skeletons | ✅ Done | Java 21, Android SDK 37 and emulator under `~/Android`. Mac app builds with Command Line Tools until the Xcode license is accepted. |
| M1 Pairing & connection | ✅ Done | Tested on emulator: QR-code pairing with confirmation on both sides; status updates; reconnect after Mac app restart, Wi-Fi drop and phone reboot; unknown certificate rejected; unpair clears both sides. Tested on the real Pixel 9 Pro: QR pairing over Wi-Fi, status updates, Bonjour rediscovery after a stale address. **Still to check:** the 1-hour sleep test. |
| M2 Notifications | ✅ Built, tested with a test notification on the Pixel | Post, reply, dismiss Mac→phone and phone→Mac all work. Updates that Android doesn't alert for (e.g. after replying) refresh the Mac quietly. Real apps: LinkedIn, WhatsApp and Google Messages notifications arrive; a reply from the Mac was sent by Google Messages. **Still to check:** a WhatsApp reply. |
| M3 Calls | ✅ Done | Call pop-up fixed to stay visible above all apps and take the first click. Wake-up nudge added: a sleeping phone reconnects within ~1 s after the Mac restarts or wakes (was ~100 s, and only when something else woke the phone). Incoming-call pop-up with name and photo; answer, decline and hang up from the Mac; silence (mutes the ringer for that call, since Android reserves the real "silence" for the dialer); missed-call alert with Call Back; dial from the Calls window or `tel:` links; call log and contacts. On the Pixel: real incoming calls show with the contact name and end as missed with an alert; call log (3,017) and contacts (826) synced. Real calls: declined from the Mac pop-up, and answered then completed, on two calls in a row. |
| M4 SMS | ✅ Done | Messages window: threads with contact names, conversation history, live incoming texts, sending from the SIM with sending → sent status, new conversations. Texts with one-time codes are hidden from apps by Android for a while (they still arrive as notifications). On the Pixel: 200 threads with names; sending from the Mac works and pushes exactly one update. Fixed: providers ignore query limits, which flooded the Mac with ~8,900 messages per change. Not yet seen: a failed send. |
| M5 Files, photos, clipboard, extras | ✅ Done, tested on the Pixel | Find my phone (rings on the alarm stream, stops from Mac or phone). Clipboard both ways and automatic: Mac → phone on copy (skips password-manager text); phone → Mac by watching for Android's clipboard-denied log line (READ_LOGS granted over adb, "Display over other apps", and Android's log-access prompt after each restart), with the Quick Settings tile and notification button as fallbacks. Files both ways with checksum (60 MB in 13 s; iCloud placeholders are downloaded first). Photos window with all photos, albums and paging. Now-playing controls that follow whichever app is playing. |
| M6 Screen mirroring | ✅ Done, tested on the Pixel | Native capture (MediaProjection + hardware H.264, 704×1600, still screen sends nothing) on its own pinned TLS stream; hardware-decoded Mac window with Back/Home/Recents/Notifications/Apps. Control via Accessibility: live touch from mouse and trackpad (scrolling confirmed smooth on the Pixel), typing, app launching. Approve once: session kept paused between windows; Android ends it on lock (`STOP_REASON_KEYGUARD`) unless `PROJECT_MEDIA` is granted once over adb, verified on the Pixel to skip the prompt and survive locking. The scrcpy/adb route was built and removed (Android 17 blocks switching Wireless debugging on). Not done: phone sound, separate app windows. |
