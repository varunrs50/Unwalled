# Unwalled protocol (v1)

How the phone app and the Mac app talk to each other. Both apps must follow this file exactly.

## Transport

- **TCP port 47820** on the Mac. The Mac listens; the phone connects.
- **TLS 1.3 only, with both sides presenting certificates.** Each side has a long-term self-signed certificate (EC P-256). No certificate authority is involved. Instead, each side checks the other's certificate against a **pinned SHA-256 fingerprint** of the certificate's DER bytes, written as 64 lowercase hex characters.
  - Mac: certificate stored as `~/Library/Application Support/Unwalled/identity.p12`.
  - Phone: key in the Android Keystore under alias `unwalled-identity`. Android generates the self-signed certificate.
- **Discovery.** The Mac advertises Bonjour service `_unwalled._tcp` with TXT record `fp=<first 16 hex chars of its fingerprint>`. The phone first tries the last known host, then searches Bonjour for a service whose `fp` matches its pinned Mac.

## Wake-up nudge

A sleeping phone (Android Doze) pauses its timers, so it may not notice that the Mac restarted or slept. To get it back quickly, the Mac sends a UDP datagram to the phone's last known address, **port 47822**:

```
UWNUDGE1 <first 16 hex chars of the Mac's fingerprint>
```

The Mac sends one when it starts, when it wakes from sleep, and every 60 s while the paired phone isn't connected. On a nudge whose fingerprint prefix matches its paired Mac, the phone drops its current connection, if it has one, and reconnects immediately. The nudge carries no secrets and grants nothing: the reconnect still goes through the full TLS pinning.

## Framing

Each frame is a 4-byte big-endian unsigned length followed by that many bytes of UTF-8 JSON. The maximum frame size is 16 MiB; larger frames close the connection.

Each JSON frame is an envelope:

```json
{ "v": 1, "id": "c1f0…", "type": "status.update", "ts": 1793623653585, "body": { } }
```

| Field | Meaning |
|---|---|
| `v` | Protocol version, always `1` for now |
| `id` | Random ID. A reply copies the `id` of the request it answers. |
| `type` | Message type (tables below) |
| `ts` | Sender's clock, Unix milliseconds |
| `body` | Type-specific object; may be empty `{}` |

Unknown `type` values are ignored, so newer apps can talk to older ones.

## Pairing

The Mac shows a QR code that encodes:

```
unwalled://pair?v=1&name=<Mac name>&host=<IPv4>&port=47820&fp=<Mac fingerprint>&token=<random, 22 chars base64url>
```

1. The phone parses the QR code and asks the user to confirm "Pair with <name>?"
2. The phone connects with TLS. It accepts the Mac only if the Mac's certificate matches `fp` from the QR code.
3. While its pairing window is open (5 minutes), the Mac accepts any phone certificate, but only for pairing.
4. The phone sends `pair.request`. The Mac checks the token and asks the user "Allow <phone name>?"
5. On Allow, the Mac pins the phone's certificate fingerprint and replies `pair.result {ok: true}`. The phone saves the Mac (name, host, port, fingerprint), and the connection continues as a normal session.
6. On Deny, a wrong token or an expired window, the Mac replies `pair.result {ok: false, reason}` and closes the connection.

Outside the pairing window, the Mac rejects any certificate that isn't pinned during the TLS handshake, so an unknown device never gets to send a frame.

## Session

The first frame from a paired phone is `hello`. The Mac replies `hello.ack`. After that, frames flow freely in both directions.

Liveness is designed so a dozing phone isn't woken for nothing. The Mac relies on TCP keepalive (idle 120 s, then 3 probes 20 s apart), which the phone's network stack answers without waking the app. The phone sends `ping` every 4 min and the Mac answers `pong`; either side closes the connection after 15 min with nothing received, as a last resort. After a drop, the phone reconnects with backoff (1 s, 2 s, 4 s … up to 5 min) while on Wi-Fi, doesn't retry at all while off Wi-Fi, and reconnects immediately when it joins Wi-Fi or gets a nudge. `status.update` is sent only when the status changes: battery changes immediately, signal and network are checked every 5 min.

## Message types

### Connection

| Type | Direction | Body |
|---|---|---|
| `pair.request` | phone → Mac | `{token, name, model, app_version}` |
| `pair.result` | Mac → phone | `{ok, name?, reason?}` |
| `hello` | phone → Mac | `{name, model, app_version, android}` |
| `hello.ack` | Mac → phone | `{name}` |
| `ping` / `pong` | both | `{}` |
| `unpair` | both | `{}`: the sender has forgotten the other side; the receiver forgets too and disconnects |

### Status

| Type | Direction | Body |
|---|---|---|
| `status.update` | phone → Mac | `{battery: 0-100, charging: bool, signal: 0-4 or null, network: "wifi"|"cellular"|"none", wifi: 0-4 or null, sims: [{id, label}]}`. `sims` lists the active SIMs (subscription ID and e.g. "SIM 1 · Jio") only when there are two or more; otherwise it's empty. |

The phone sends `status.update` right after `hello.ack` and whenever something in it changes: battery and charging straight away, signal, network and SIMs within 5 minutes.

### Notifications (M2)

| Type | Direction | Body |
|---|---|---|
| `notif.posted` | phone → Mac | See below. Sent for new notifications and for updates whose title or text changed. |
| `notif.removed` | phone → Mac | `{key}`: the notification is gone from the phone (dismissed, read or cancelled by its app) |
| `notif.dismiss` | Mac → phone | `{key}`: the user dismissed it on the Mac; the phone clears it |
| `notif.reply` | Mac → phone | `{key, action, text}`: fill in the reply field of action number `action` and send it |
| `notif.action` | Mac → phone | `{key, action}`: press button number `action` (e.g. "Mark as read") |

`notif.posted` body:

```json
{
  "key": "0|com.whatsapp|1|…",       // Android's notification key; stable while it exists
  "package": "com.whatsapp",
  "app": "WhatsApp",
  "title": "Asha",
  "text": "Are you coming tonight?",
  "time": 1793623653585,               // when it was posted, Unix ms
  "initial": false,                     // true: already on the phone when the connection started; store it, don't alert
  "silent": false,                      // true: an update Android itself doesn't alert for; refresh the list, no banner
  "actions": [ { "index": 0, "title": "Reply", "reply": true }, { "index": 1, "title": "Mark as read", "reply": false } ],
  "icon": "<base64 PNG, 64 px>",        // app icon; only sent the first time each app appears in a session
  "picture": "<base64 PNG, ≤128 px>"    // optional: the notification's own image, e.g. the contact's photo
}
```

Which notifications are forwarded: ones the user can swipe away, that make an alert (importance "default" or higher), and that aren't group summaries. Ongoing ones (music, navigation, Unwalled's own) are skipped.

On every new session the phone re-sends what's currently showing with `initial: true`, so the Mac's list matches the phone.

### Calls (M3)

Call audio always stays on the phone. These messages only show and control calls.

| Type | Direction | Body |
|---|---|---|
| `call.state` | phone → Mac | `{id, state, direction, number?, name?, photo?, reason?}` (see below) |
| `call.answer` | Mac → phone | `{}`: answer the ringing call |
| `call.end` | Mac → phone | `{}`: decline the ringing call, or hang up the active one |
| `call.silence` | Mac → phone | `{}`: stop the ringer without declining |
| `call.dial` | Mac → phone | `{number}`: place a call from the phone |
| `call.result` | phone → Mac | `{ok, action, error?}`: reply to any of the four above |
| `calllog.request` | Mac → phone | `{limit}` (default 100) |
| `calllog.page` | phone → Mac | `{entries: [{number, name?, type, time, duration}]}`, newest first. `type` is `incoming`, `outgoing`, `missed`, `rejected`, `blocked` or `voicemail`; `duration` in seconds. |
| `calllog.changed` | phone → Mac | `{}`: a call finished; request the log again |
| `contacts.request` | Mac → phone | `{}` |
| `contacts.list` | phone → Mac | `{contacts: [{name, numbers: [{number, label}]}]}`, sorted by name; only contacts with phone numbers |
| `contacts.changed` | phone → Mac | `{}`: contacts became available (e.g. access was just granted); request them again |

`call.state` fields:

| Field | Meaning |
|---|---|
| `id` | Phone-generated ID, the same for every update of one call |
| `state` | `ringing` (incoming, not answered yet), `active` (answered or dialling out), `ended` |
| `direction` | `incoming` or `outgoing` |
| `number` | Caller or callee number, when Android reveals it |
| `name` | Contact name, if the number is in contacts |
| `photo` | Base64 PNG of the contact photo, ≤96 px, if any |
| `reason` | Only with `ended`: `missed` (rang out), `declined` (rejected from phone or Mac), `completed` (answered then hung up) |

A ringing call can be reported twice: Android first reports the ring without the number, then again with it. The Mac updates the same `id` in place.

### SMS (M4)

Plain SMS only: Android doesn't let other apps read RCS chats or (yet) MMS content. Those still reach the Mac as notifications.

| Type | Direction | Body |
|---|---|---|
| `sms.threads.request` | Mac → phone | `{limit}` (default 200) |
| `sms.threads` | phone → Mac | `{threads: [Thread]}`, newest first |
| `sms.messages.request` | Mac → phone | `{thread, limit, before?}`: newest `limit` messages of `thread`, optionally older than `before` (Unix ms) |
| `sms.messages` | phone → Mac | `{thread, messages: [Message]}`, oldest first |
| `sms.updated` | phone → Mac | `{messages: [Message]}`: new messages, or ones whose status changed (e.g. sending → sent) |
| `sms.send` | Mac → phone | `{to, body, client_id, sim?}`: `to` is a phone number; `client_id` is chosen by the Mac; `sim` is a subscription ID from `status.update`'s `sims` (omitted: the phone's default SIM) |
| `sms.send.result` | phone → Mac | `{client_id, ok, error?}`: sent from the SIM (or failed). The message itself also arrives in `sms.updated`. |
| `sms.changed` | phone → Mac | `{}`: SMS just became readable (access granted); request threads again |

Thread: `{id, addresses: [string], name?, snippet, time, unread, count}`; `id` is Android's thread ID, `unread` is a bool.

Message: `{id, thread, address, body, time, direction, status}`:

| Field | Values |
|---|---|
| `direction` | `in` or `out` |
| `status` | `received`, `sending`, `sent`, `delivered` or `failed` |

### Find my phone (M5)

| Type | Direction | Body |
|---|---|---|
| `phone.ring` | Mac → phone | `{}`: ring loudly on the alarm stream (ignores silent mode) for up to 60 s |
| `phone.ring.stop` | Mac → phone | `{}`: stop ringing (the phone's own notification can stop it too) |
| `phone.ring.state` | phone → Mac | `{ringing}` |

### Media (M5)

| Type | Direction | Body |
|---|---|---|
| `media.state` | phone → Mac | `{active, app?, title?, artist?, album?, playing?, art?}`: what's playing now; `active: false` when nothing is. `art` is a base64 JPEG ≤160 px, sent when it changes. |
| `media.control` | Mac → phone | `{action}`: `play`, `pause`, `toggle`, `next` or `previous` |

The phone sends `media.state` at session start and whenever the playing app, track or play/pause state changes.

### Clipboard (M5)

| Type | Direction | Body |
|---|---|---|
| `clip.set` | both | `{text}`: put this text on the clipboard |

Mac → phone happens automatically when the Mac's clipboard text changes. Phone → Mac happens when you tap Unwalled's Quick Settings tile or share text to "Send to Mac", because Android doesn't let background apps read the clipboard. Each side ignores a `clip.set` that matches what it just sent, so the two never loop.

### Files (M5)

Either side can send a file. Contents travel as base64 in JSON chunks of 192 KiB (256 KiB once encoded).

| Type | Direction | Body |
|---|---|---|
| `file.offer` | sender → receiver | `{id, name, size, mime}` |
| `file.chunk` | sender → receiver | `{id, seq, data}`: `seq` counts from 0; `data` is base64 |
| `file.done` | sender → receiver | `{id, sha256}`: SHA-256 hex of the whole file |
| `file.result` | receiver → sender | `{id, ok, error?}` |
| `file.cancel` | either | `{id}` |

The sender sends chunks one after another as fast as the connection accepts them. The receiver writes them to a temporary file, checks the size and SHA-256, then moves the file into place: `~/Downloads` on the Mac (renamed if the name is taken), `Downloads/Unwalled` on the phone.

### Photos (M5)

| Type | Direction | Body |
|---|---|---|
| `photos.request` | Mac → phone | `{limit, offset, album}`: limit defaults to 60, offset to 0; `album` is a folder name, `""` for all |
| `photos.list` | phone → Mac | `{photos: [{id, name, time, width, height, size, thumb}], offset, total, album, albums: [{name, count}]}`, newest first. `thumb` is a base64 JPEG ≤240 px; `total` counts the photos in `album`; `albums` lists every folder, largest first. |
| `photos.get` | Mac → phone | `{id}`: the phone sends the full image as a file transfer (`file.offer` …) whose `id` is `photo-<id>` |

### Screen mirroring (M6)

The phone captures its own screen with Android's screen-capture permission (MediaProjection), encodes it with the hardware H.264 encoder, and streams it on a **separate TLS connection to the Mac's port + 3 (47823)**, pinned to the same certificates as the message channel. Pairing never happens on that port. Control goes over the message channel through the phone's Accessibility service.

Session: Android requires the user to approve each capture session. Once approved, the phone keeps the session open while paused (no encoder, no connection, virtual display without a surface), so the Mac can resume it without asking again. Android ends it on restart, and **when the phone locks** unless the app has the `PROJECT_MEDIA` app-op (granted once over adb), which also skips the approval dialog.

| Type | Direction | Body |
|---|---|---|
| `screen.start` | Mac → phone | `{}`: resume the session, or ask the user to approve a new one |
| `screen.stop` | Mac → phone | `{}`: pause; the session stays open |
| `screen.end` | Mac → phone | `{}`: end the session; the next start asks again |
| `screen.keyframe` | Mac → phone | `{}`: send a key frame now (e.g. after the window opened) |
| `screen.sound` | Mac → phone | `{on}`: the Sound button. While on and streaming, the phone captures app playback (AudioPlaybackCapture: media, games, unknown usages; apps can opt out; never calls) and mutes its own media volume, restoring it when sound or streaming stops. |
| `screen.sound.state` | phone → Mac | `{state}`: `on`, `off`, or `needs_permission` (recording permission not granted) |
| `screen.state` | phone → Mac | `{state, control, error?}`. `state` is `waiting` (asking the user), `running`, `paused` (session kept), `ended`, `declined` or `error`. `control` says whether the Accessibility service is on. Sent on changes and after every `hello`. |
| `input.touch` | Mac → phone | `{action, x, y}`: a live finger; `action` is `down`, `move` or `up`, `x`/`y` run from 0 to 1 across the screen. Moves come at up to ~60 per second; the phone merges any that arrive while it's busy. |
| `input.key` | Mac → phone | `{key}`: `back`, `home`, `recents`, `notifications`, `lock`, or `backspace`/`enter` in the focused text field |
| `input.text` | Mac → phone | `{text}`: typed into the focused text field at the cursor |
| `apps.request` | Mac → phone | `{}` |
| `apps.list` | phone → Mac | `{apps: [{package, label, icon}]}`, launchable apps sorted by name; `icon` is a base64 PNG, 64 px |
| `apps.launch` | Mac → phone | `{package}`: open that app on the phone |

Video packets: `u32` big-endian length, then `u8` kind, `u64` presentation time in µs, payload. Kinds: `0` JSON `{width, height}` (sent first and after every resize, e.g. rotation), `1` codec config (SPS/PPS, Annex-B), `2` key frame, `3` frame (Annex-B), `4` audio config (AAC AudioSpecificConfig), `5` audio frame (raw AAC-LC, 48 kHz stereo, 128 kbit/s). Audio and video timestamps share the phone's monotonic clock; the Mac plays audio about 120 ms behind to absorb jitter. A still screen sends no frames. The long side is at most 1600 px; 6 Mbit/s, up to 60 fps.

### Insert from Phone (M7)

| Type | Direction | Body |
|---|---|---|
| `camera.capture` | Mac → phone | `{id, mode}`: `mode` is `photo` or `scan`. The phone opens its camera (the secure camera when locked) or the document scanner. |
| `camera.result` | phone → Mac | `{id, ok: false, error}`: sent only when nothing is coming (cancelled, or the phone stayed locked for a scan) |

A successful capture arrives as an ordinary file transfer (`file.offer` …) whose `id` is `camera-<id>`: a JPEG for a photo or a one-page scan, a PDF for several pages. The Mac doesn't save it to Downloads; it pastes it into the app that was in front when the capture was requested.

### Later milestones

Notifications, SMS, calls, files and the other message types are listed in PLAN.md §4. Each gets its body format here as its milestone is built.
