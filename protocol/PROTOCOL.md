# PixelLink protocol (v1)

How the phone app and the Mac app talk to each other. Both apps must follow this file exactly.

## Transport

- **TCP port 47820** on the Mac. The Mac listens; the phone connects.
- **TLS 1.3 only, with both sides presenting certificates.** Each side has a long-term self-signed certificate (EC P-256). No certificate authority is involved. Instead, each side checks the other's certificate against a **pinned SHA-256 fingerprint** of the certificate's DER bytes, written as 64 lowercase hex characters.
  - Mac: certificate stored as `~/Library/Application Support/PixelLink/identity.p12`.
  - Phone: key in the Android Keystore under alias `pixellink-identity`. Android generates the self-signed certificate.
- **Discovery.** The Mac advertises Bonjour service `_pixellink._tcp` with TXT record `fp=<first 16 hex chars of its fingerprint>`. The phone first tries the last known host, then searches Bonjour for a service whose `fp` matches its pinned Mac.

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
pixellink://pair?v=1&name=<Mac name>&host=<IPv4>&port=47820&fp=<Mac fingerprint>&token=<random, 22 chars base64url>
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

Liveness: the phone sends `ping` every 30 s and the Mac answers `pong`. Either side closes the connection if it receives nothing for 75 s. The phone then reconnects with backoff (1 s, 2 s, 4 s … up to 30 s), and immediately whenever Wi-Fi comes back.

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
| `status.update` | phone → Mac | `{battery: 0-100, charging: bool, signal: 0-4 or null, network: "wifi"|"cellular"|"none", wifi: 0-4 or null}` |

The phone sends `status.update` right after `hello.ack`, whenever battery or charging state changes, and at least every 60 s.

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

Which notifications are forwarded: ones the user can swipe away, that make an alert (importance "default" or higher), and that aren't group summaries. Ongoing ones (music, navigation, PixelLink's own) are skipped.

On every new session the phone re-sends what's currently showing with `initial: true`, so the Mac's list matches the phone.

### Later milestones

Notifications, SMS, calls, files and the other message types are listed in PLAN.md §4. Each gets its body format here as its milestone is built.
