# Unwalled

### Fuck ecosystems.

Your phone shouldn't decide which computer you're allowed to use. Unwalled links your **Android phone to your Mac** the way an iPhone works with a Mac: open source, local only, no accounts.

Calls, texts, notifications, files, photos, clipboard and music controls from your phone, built into your Mac's menu bar as native notifications and windows. When you need the phone itself, its screen opens in a Mac window you control with your mouse and keyboard. Everything stays on your Wi-Fi: no cloud, no account, no server in between.

> **Status: early, but working.** Tested on a Pixel 9 Pro (Android 17) with an Apple Silicon MacBook Air. Other Android 14+ phones should work; please [open an issue](../../issues) if something doesn't.

---

## Contents

- [What it does](#what-it-does)
- [What you need](#what-you-need)
- [Install](#install)
- [Set up](#set-up)
- [Using Unwalled](#using-unwalled)
- [Optional extras](#optional-extras)
- [Troubleshooting](#troubleshooting)
- [Privacy and security](#privacy-and-security)
- [Updating and uninstalling](#updating-and-uninstalling)
- [What it can't do (yet)](#what-it-cant-do-yet)
- [How it works](#how-it-works)
- [Roadmap](#roadmap)
- [Contributing and building from source](#contributing-and-building-from-source)
- [License](#license)

---

## What it does

| Feature | On the Mac |
|---|---|
| **Notifications** | Phone notifications appear as Mac notifications with the app's icon. Reply right there (WhatsApp, Messages, Telegram…), tap their buttons, or dismiss them, and they clear on the phone too. |
| **Calls** | When the phone rings, a panel shows who's calling, with **Answer**, **Decline** and **Silence**. Call anyone from the **Calls** window or any phone-number link on the Mac. Missed calls come with **Call Back**. |
| **Text messages** | A **Messages** window with your conversations. Read and send texts from the Mac; they go out from your phone's SIM. |
| **Phone screen** | Your phone's screen in a Mac window: click to tap, drag or scroll to swipe, type into text fields. Approve it once on the phone and it opens without asking after that. |
| **Insert from Phone** | Take a photo or scan a document with the phone, and it's pasted straight into whatever you're working on (Notes, Mail, Pages…). Photos work even when the phone is locked. |
| **Files** | Drag files onto the menu bar panel to send them to the phone, or share anything on the phone with **Send to Mac**. |
| **Photos** | Every photo on the phone, by album. Click one to open it on the Mac. |
| **Clipboard** | Copy on the Mac, paste on the phone, and the other way round. Passwords from password managers are never sent. |
| **Find my phone** | Rings the phone at full volume, even on silent. |
| **Now playing** | What's playing on the phone, with ⏮ ⏯ ⏭ in the menu bar. |
| **Status** | The phone's battery, signal and Wi-Fi in the menu bar. |

## What you need

- A **Mac** with Apple Silicon (M1 or later) and **macOS 15** or later.
- An **Android phone** with **Android 14** or later.
- Both on the **same Wi-Fi**. Some guest and office networks keep devices apart; a home network is fine.

## Install

### On the Mac

Open **Terminal** (in Applications → Utilities), paste this line and press Return:

```bash
curl -fsSL https://raw.githubusercontent.com/varunrs50/Unwalled/main/scripts/install.sh | bash
```

It checks your Mac, downloads the latest Unwalled, verifies it, puts it in **Applications** and opens it. Run the same line again any time to update. When Unwalled opens, **allow notifications** and **allow finding devices on your local network**.

<details>
<summary>Prefer to download it yourself?</summary>

1. Download **Unwalled.dmg** from the [latest release](../../releases/latest), open it, and drag **Unwalled** into **Applications**.
2. Open Unwalled. Because it isn't notarized by Apple yet, macOS stops it the first time: open **System Settings → Privacy & Security**, scroll down, and click **Open Anyway** next to Unwalled.

</details>

Unwalled lives in the menu bar (there's no Dock icon), and the **Pair Phone** window opens.

### On the phone

1. In the Mac's **Pair Phone** window, click **Get the phone app**.
2. Scan the code with your phone's **Camera** and open the link. It downloads **Unwalled.apk**.
3. Open the download and tap **Install**. If asked, allow your browser or Files app to install apps.

You can also download [**Unwalled.apk**](../../releases/latest/download/Unwalled.apk) on the phone directly.

## Set up

### 1. Pair the phone with the Mac

1. Back on the Mac, click **Pair** in the Pair Phone window. Open **Unwalled** on the phone and allow **local network** access and **notifications**.
2. Tap **Scan pairing code** and point it at the QR code on the Mac. Scanning with your normal **Camera** app works too.
3. The phone asks **"Pair with ⟨your Mac⟩?"**: tap **Pair**. The Mac asks **"Allow ⟨your phone⟩ to connect?"**: click **Allow**.

That's done once. From then on the phone connects by itself whenever both are on the same Wi-Fi. If the pairing window is closed, open it from the Unwalled menu bar icon → **Pair a phone…**.

### 2. Work through the checklist on the phone

The Unwalled app on the phone shows a **Set up** checklist with a button for each item. Each one turns on a feature:

| Item | What to do | Turns on |
|---|---|---|
| **Notifications** | Find **Unwalled** in the list and switch it on | Notifications on the Mac, replies, now playing |
| **Calls** | Allow the phone, call log and contacts prompts | Incoming calls, call history, calling from the Mac |
| **Messages** | Allow the SMS prompt | The Messages window |
| **Photos** | Choose **Allow all** | The Photos window |
| **Run in the background** | Tap **Allow** | Keeps the connection alive when the phone is idle. **Don't skip this one.** |
| **Phone control** | In the Accessibility list, tap **Unwalled phone control** and switch it on | Using the phone's screen from the Mac, and opening the camera for Insert from Phone |
| **⟨Maker⟩ battery setting** (Samsung, Xiaomi, OnePlus…) | Tap **Open settings**, follow the steps shown, then tap **I've done this** | Stops your phone maker's own battery saver from closing Unwalled |

> **"Restricted setting"?** Android blocks some permissions for apps installed outside the Play Store. If it says that for **Notifications** or **Phone control**: open **Settings → Apps → Unwalled**, tap **⋮** (top right), choose **Allow restricted settings**, and try again.

### 3. Optional: open at login

In the Unwalled menu, choose **Settings…** and turn on **Open Unwalled when you log in**.

---

## Using Unwalled

Click the Unwalled icon in the menu bar to see your phone's status, recent notifications, what's playing and file transfers, plus:

| Menu item | What it does |
|---|---|
| **Messages…** | Your text conversations. **New Message** starts one. On a dual-SIM phone, the SIM button next to the message box picks which SIM sends it. |
| **Calls…** | Recent calls and contacts. Click one to call it from the phone. |
| **Photos…** | All the phone's photos, by album. Click one to open it. |
| **Phone Screen** | The phone's screen in a window. The first time, tap **Start** on the phone. Click to tap, drag or scroll to swipe, hold for a long press, right-click or Esc for Back, and type into the field you're in (⌘V pastes the Mac clipboard). Back, Home, Recents and **Apps** are in the toolbar. Closing the window pauses sharing; **End** in the menu stops it. |
| **Take Photo with Phone** / **Scan Documents with Phone** | Opens the camera or the document scanner on the phone. Take the shot and it's pasted where you were on the Mac. Several scanned pages arrive as one PDF. The first time, macOS asks to let Unwalled use **Accessibility** so it can paste for you. |
| **Send File to Phone…** | Pick files to send, or drag them onto the open menu bar panel. They go to **Downloads/Unwalled** on the phone. |
| **Find My Phone** | Rings the phone. Stop it from the Mac or the phone. |
| **Settings…** | Open at login, clipboard sharing, clear what this Mac stores from your phone, unpair. |

- **Calls:** when the phone rings, a panel appears at the top right of the screen. After answering, talk on the phone or a headset; the call's sound stays on the phone.
- **Notifications:** hover over one and click **Reply** to answer, or use its buttons. Clearing it on the Mac clears it on the phone.
- **Files from the phone:** share a file or photo from any app and choose **Send to Mac**. It lands in your Mac's **Downloads** folder.
- **Phone clipboard to the Mac:** after copying on the phone, tap **Copy to Mac** in Quick Settings (add the tile from the checklist) or on Unwalled's notification. To make it automatic, see [Optional extras](#optional-extras).

---

## Optional extras

Two things Android only allows after a one-time command from a computer. Everything else works without them.

| Extra | What it adds |
|---|---|
| **Automatic clipboard** | Whatever you copy on the phone appears on the Mac by itself, without tapping **Copy to Mac**. |
| **Phone screen without asking** | **Phone Screen** opens from the Mac without approving on the phone, even when the phone is locked in another room. |

<details>
<summary><b>How to set them up</b></summary>

1. **Get adb**, Android's connection tool, on the Mac. Either download [Platform Tools](https://developer.android.com/tools/releases/platform-tools) and unzip it, or with Homebrew run `brew install android-platform-tools`.
2. **Connect the phone.** On the phone, open **Settings → About phone** and tap **Build number** seven times. Then go to **Settings → System → Developer options**, turn on **USB debugging**, and plug the phone into the Mac with a cable. Tap **Allow** when the phone asks.
3. **Run the commands you want** in Terminal:

   Automatic clipboard:

   ```bash
   adb shell pm grant app.unwalled android.permission.READ_LOGS
   ```

   ```bash
   adb shell appops set app.unwalled SYSTEM_ALERT_WINDOW allow
   ```

   Then open Unwalled on the phone and tap **Allow** on "Allow Unwalled to access all device logs?". Android asks again after the phone restarts.

   Phone screen without asking:

   ```bash
   adb shell appops set app.unwalled PROJECT_MEDIA allow
   ```

4. Unplug the phone. You can turn **USB debugging** off again; the extras stay on until Unwalled is uninstalled.

To undo them: `adb shell pm revoke app.unwalled android.permission.READ_LOGS` and `adb shell appops set app.unwalled PROJECT_MEDIA default`.

If you built Unwalled yourself, use `app.unwalled.debug` instead of `app.unwalled`.

</details>

---

## Troubleshooting

<details>
<summary><b>The menu bar says "Waiting for phone on Wi-Fi…"</b></summary>

- Make sure both are on the **same Wi-Fi network**. Guest networks and some office networks keep devices apart.
- Open Unwalled on the phone. If it asks for **local network** access, allow it.
- On the Mac: **System Settings → Privacy & Security → Local Network** must have Unwalled switched on.
- If the Mac's firewall is on, allow incoming connections for Unwalled.
- Quit and reopen Unwalled on the Mac; it wakes the phone to reconnect.

</details>

<details>
<summary><b>The connection drops when the phone has been idle</b></summary>

Make sure **Run in the background** is ticked in the phone's checklist. Some phone makers also need their own setting:

- On **Samsung, Xiaomi, OnePlus, Oppo, realme, vivo, Huawei, Honor and ASUS**, the checklist has an extra **battery setting** item for your phone's maker, with the steps and a button that opens the right screen.
- For other phones, see [dontkillmyapp.com](https://dontkillmyapp.com).

</details>

<details>
<summary><b>No notifications on the Mac</b></summary>

- On the phone, **Notifications** must be ticked in Unwalled's checklist.
- On the Mac: **System Settings → Notifications → Unwalled**: turn on **Allow notifications**, with the **Banners** or **Alerts** style.
- Notifications that were already on the phone before it connected show in the menu bar, not as new banners.

</details>

<details>
<summary><b>The incoming-call panel doesn't appear</b></summary>

Make sure **Calls** is ticked in the phone's checklist. If you allowed it while the phone was already connected, quit and reopen Unwalled on the Mac once.

</details>

<details>
<summary><b>The Photos window is empty or missing photos</b></summary>

Unwalled needs **Allow all** photo access. To change it: **Settings → Apps → Unwalled → Permissions → Photos and videos → Allow all**. Photos that are only in the cloud (Google Photos backup, not on the phone) can't be shown.

</details>

<details>
<summary><b>Clicking in the Phone Screen window does nothing</b></summary>

Turn on **Phone control** in the phone's checklist. If Android says it's a restricted setting, see the note under [Set up](#2-work-through-the-checklist-on-the-phone).

</details>

<details>
<summary><b>"Scan pairing code" does nothing, or isn't there</b></summary>

The in-app scanner comes from Google Play services, so phones without it (some Huawei and Honor models, for example) don't show it. Scan the Mac's QR code with the phone's normal Camera app instead, or click **Copy code** in the Mac's pairing window and paste the text into the box in Unwalled on the phone.

</details>

---

## Privacy and security

- **Nothing leaves your network.** There's no Unwalled server, no account, no analytics and no tracking. Your phone talks only to the Mac it's paired with.
- **Encrypted and authenticated.** Every connection is encrypted (TLS 1.3), and each device checks the other's identity from pairing. Other devices on your Wi-Fi can't connect or listen in.
- **Pairing needs both sides.** You confirm on the phone *and* on the Mac. Pairing codes expire after a few minutes.
- **Stored on your Mac only.** So its windows open instantly, the Mac keeps copies of your message list, call history and contacts in `~/Library/Application Support/Unwalled/`. **Settings… → Clear History** deletes them; **Unpair** forgets the phone.
- **Every permission is yours to grant**, one feature at a time, and you can turn any of them off in Android settings.

## Updating and uninstalling

**To update,** run the install line in Terminal again; it replaces the old version and keeps your pairing. On the phone, download the new [Unwalled.apk](../../releases/latest/download/Unwalled.apk) and tap **Update**. You don't need to pair again.

**To uninstall:**

- **Mac:** quit Unwalled from its menu, then delete **Unwalled** from Applications and the folder `~/Library/Application Support/Unwalled/`.
- **Phone:** uninstall Unwalled like any app.

---

## What it can't do (yet)

- **Call sound on the Mac:** you answer on the Mac, but talk on the phone or a headset. Android doesn't let apps send call audio elsewhere.
- **RCS chats:** the Messages window shows SMS only. RCS chats still arrive as notifications you can reply to.
- **One-time codes:** Android hides SMS verification codes from apps, so they show as "No preview" in Messages. They still appear as notifications.
- **Away from home:** both devices must be on the same Wi-Fi for now.
- **Separate windows per app:** the phone's screen opens as one window.
- **Phone sound in the Phone Screen window:** not yet.

## How it works

```
┌──────────────────────────┐       Wi-Fi (same network)        ┌──────────────────────────┐
│ Android phone            │                                   │ Mac                      │
│                          │──── encrypted (TLS 1.3) link ────▶│                          │
│ Unwalled app             │◀───── messages both ways ─────────│ Unwalled (menu bar)      │
│  • background service    │                                   │  • finds the phone       │
│  • notifications, calls, │◀────── "wake up" nudge ───────────│  • Mac notifications     │
│    texts, media, files   │                                   │  • Messages, Calls,      │
│  • screen and camera     │                                   │    Photos, Phone Screen  │
└──────────────────────────┘                                   └──────────────────────────┘
```

The Mac announces itself on your Wi-Fi; the phone finds it and stays connected. Pairing exchanges the two devices' identities once, and every connection after that is checked against them. The technical details are in [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md) and [`PLAN.md`](PLAN.md).

## Roadmap

| | | Status |
|---|---|---|
| M0–M5 | Pairing, notifications, calls, texts, files, photos, clipboard, find my phone, media | ✅ |
| M6 | Phone screen on the Mac with mouse and keyboard control | ✅ |
| M7 | Insert from Phone: photos and document scans | ✅ |
| M8 | Settings, open at login, setup checklist, app icon, downloads | ✅ |
| M9 | Compatibility with every phone maker (Samsung, Xiaomi, OnePlus…) | Next |
| 2.0 | Away from home, call sound on the Mac, phone as a wireless webcam | Ideas |

## Contributing and building from source

Bug reports and pull requests are welcome. The quickest way to report a problem:

1. On the Mac, open **Settings… → Report a Problem…**. It opens a bug report on GitHub, filled in with your Mac's details, and shows Unwalled's log file to attach.
2. On the phone, scroll to the bottom of Unwalled and tap **Send diagnostics**. Attach the file it creates, which has your phone model, Android version, setup status and Unwalled's own log.

Neither contains your messages, contacts or phone numbers, only device names, versions and what Unwalled was doing.

To build Unwalled yourself or work on the code, see **[docs/BUILDING.md](docs/BUILDING.md)**.

## License

Unwalled is released under the [Apache License 2.0](LICENSE). Copyright 2026 Varun Kumar R S.

Thanks to [KDE Connect](https://kdeconnect.kde.org/), whose approach to reading the clipboard on modern Android Unwalled follows.

Unwalled is an independent project. It isn't affiliated with or endorsed by Google or Apple. Android and Pixel are trademarks of Google LLC; Mac, macOS and iPhone are trademarks of Apple Inc.
