import AppKit
import Foundation
import Observation

struct PairedPhone: Codable {
    var name: String
    var model: String
    var fingerprint: String
    var pairedAt: Date
    /// Where the phone last connected from; wake-up nudges go here.
    var lastHost: String?
}

struct PhoneStatus: Equatable {
    var battery: Int?
    var charging = false
    var signal: Int?
    var network = "none"
    var wifi: Int?
    /// The phone's SIMs, only when it has more than one.
    var sims: [SimCard] = []
}

struct SimCard: Equatable, Hashable {
    let id: Int        // Android subscription ID
    let label: String  // e.g. "SIM 1 · Jio"
}

struct PairingSession {
    let token: String
    let payload: String
    let expires: Date
    var request: (name: String, model: String, fingerprint: String, id: String)?
    weak var connection: PhoneConnection?
}

@MainActor
@Observable
final class AppModel {
    enum LinkState { case notPaired, waiting, connected }

    static let pairingWindow: TimeInterval = 5 * 60

    private(set) var paired: PairedPhone?
    private(set) var state: LinkState = .notPaired
    private(set) var status = PhoneStatus()
    private(set) var pairing: PairingSession?
    private(set) var pairingMessage: String?
    private(set) var startupError: String?
    /// Notifications currently showing on the phone, newest first.
    private(set) var notifications: [PhoneNotification] = []

    @ObservationIgnored let relay = NotificationRelay()
    @ObservationIgnored let calls = CallController()
    @ObservationIgnored let messages = MessagesStore()
    @ObservationIgnored let extras = ExtrasController()
    @ObservationIgnored let screen = ScreenMirrorController()

    @ObservationIgnored private var server: LinkServer?
    @ObservationIgnored private weak var session: PhoneConnection?
    @ObservationIgnored private var pairingTimer: Timer?
    @ObservationIgnored private let macName = Host.current().localizedName ?? "Mac"
    @ObservationIgnored private let pairedFile = AppPaths.support.appendingPathComponent("paired.json")

    var menuBarSymbol: String {
        switch state {
        case .connected: "iphone.gen3.radiowaves.left.and.right"
        case .waiting: "iphone.gen3"
        case .notPaired: "iphone.gen3.slash"
        }
    }

    init() {
        paired = loadPaired()
        relay.start()
        relay.send = { [weak self] type, body in self?.session?.send(type, body) }
        calls.send = { [weak self] type, body in self?.session?.send(type, body) }
        messages.toPhone = { [weak self] type, body in self?.session?.send(type, body) }
        extras.connection = { [weak self] in self?.state == .connected ? self?.session : nil }
        screen.connection = { [weak self] in self?.state == .connected ? self?.session : nil }
        screen.phoneName = { [weak self] in self?.paired?.name ?? "Phone" }
        relay.onCallBack = { [weak self] number in self?.calls.dial(number) }
        #if DEBUG
        listenForDebugCommands()
        #endif
        state = paired == nil ? .notPaired : .waiting
        do {
            let identity = try Identity.loadOrCreate()
            let server = LinkServer(identity: identity, pinnedFingerprint: paired?.fingerprint)
            server.onConnected = { [weak self] conn in self?.connected(conn) }
            server.onDisconnected = { [weak self] conn in self?.disconnected(conn) }
            server.onMessage = { [weak self] conn, type, body, id in self?.handle(conn, type, body, id) }
            server.onVideo = { [weak self] nw in self?.screen.accept(nw) }
            try server.start()
            self.server = server
            self.identity = identity
            Log.info("Listening on port \(LinkServer.port), fingerprint \(identity.fingerprint.prefix(16))")
        } catch {
            startupError = "Couldn't start: \(error)"
            Log.info(startupError!)
        }
        startNudging()
    }

    /// A sleeping phone can miss that this Mac restarted or slept; nudge it to reconnect
    /// at launch, on wake, and every minute while it's away. See PROTOCOL.md, "Wake-up nudge".
    private func startNudging() {
        nudgePhone()
        nudgeTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { if self?.state == .waiting { self?.nudgePhone() } }
        }
        nudgeTimer?.tolerance = 10
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil,
                                                          queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.nudgePhone() }
        }
    }

    private func nudgePhone() {
        guard let host = paired?.lastHost else { return }
        server?.nudge(host)
    }

    @ObservationIgnored private var nudgeTimer: Timer?

    @ObservationIgnored private var identity: Identity?

    // MARK: Pairing

    func startPairing() {
        guard let identity else { return }
        guard let host = NetworkInfo.localIPv4() else {
            pairingMessage = "This Mac isn't on a network."
            return
        }
        let token = randomToken()
        var components = URLComponents()
        components.scheme = "unwalled"
        components.host = "pair"
        components.queryItems = [
            .init(name: "v", value: "1"),
            .init(name: "name", value: macName),
            .init(name: "host", value: host),
            .init(name: "port", value: String(LinkServer.port.rawValue)),
            .init(name: "fp", value: identity.fingerprint),
            .init(name: "token", value: token),
        ]
        let payload = components.url!.absoluteString
        pairing = PairingSession(token: token, payload: payload, expires: Date().addingTimeInterval(Self.pairingWindow))
        pairingMessage = nil
        server?.setPairingOpen(true)
        pairingTimer?.invalidate()
        pairingTimer = Timer.scheduledTimer(withTimeInterval: Self.pairingWindow, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.cancelPairing(message: "The pairing code expired. Start again to get a new one.") }
        }
        // The code holds a one-time token, so only development builds log it (tests read it from the log).
        #if DEBUG
        Log.info("Pairing open: \(payload)")
        #else
        Log.info("Pairing open")
        #endif
    }

    func cancelPairing(message: String? = nil) {
        if let conn = pairing?.connection, let request = pairing?.request {
            conn.send("pair.result", ["ok": false, "reason": "cancelled"], replyTo: request.id)
            conn.close()
        }
        pairing = nil
        pairingMessage = message
        pairingTimer?.invalidate()
        server?.setPairingOpen(false)
    }

    func respondToPairing(allow: Bool) {
        guard let session = pairing, let request = session.request, let conn = session.connection else { return }
        guard allow else {
            conn.send("pair.result", ["ok": false, "reason": "denied"], replyTo: request.id)
            conn.close()
            pairing?.request = nil
            return
        }
        let phone = PairedPhone(name: request.name, model: request.model,
                                fingerprint: request.fingerprint, pairedAt: Date(), lastHost: conn.remoteHost)
        savePaired(phone)
        server?.setPinned(phone.fingerprint)
        conn.send("pair.result", ["ok": true, "name": macName], replyTo: request.id)
        self.session = conn
        state = .connected
        pairing = nil
        pairingTimer?.invalidate()
        server?.setPairingOpen(false)
        pairingMessage = "Paired with \(phone.name)."
        Log.info("Paired with \(phone.name) (\(phone.fingerprint.prefix(16)))")
    }

    func unpair() {
        session?.send("unpair")
        session?.close()
        forget()
    }

    func dismissNotification(_ notification: PhoneNotification) {
        relay.dismissOnPhone(notification.id)
        notifications.removeAll { $0.id == notification.id }
    }

    /// Deletes what this Mac keeps from the phone (messages, calls, contacts, app icons, captured
    /// photos); it's fetched again from the phone if connected.
    func clearHistory() {
        messages.clearCache()
        calls.clearCache()
        notifications = []
        relay.removeAll()
        for dir in ["icons", "Captures"] {
            try? FileManager.default.removeItem(at: AppPaths.support.appendingPathComponent(dir, isDirectory: true))
        }
        try? FileManager.default.createDirectory(at: AppPaths.support.appendingPathComponent("icons", isDirectory: true), withIntermediateDirectories: true)
        if state == .connected {
            calls.refresh()
            messages.refresh()
        }
        Log.info("Cleared history")
    }

    private func forget() {
        notifications = []
        relay.removeAll()
        try? FileManager.default.removeItem(at: pairedFile)
        paired = nil
        server?.setPinned(nil)
        status = PhoneStatus()
        state = .notPaired
        Log.info("Unpaired")
    }

    // MARK: Connection events

    private func connected(_ conn: PhoneConnection) {
        // Wait for hello / pair.request before treating the phone as connected.
    }

    private func disconnected(_ conn: PhoneConnection) {
        if pairing?.connection === conn {
            pairing?.request = nil
            pairing?.connection = nil
        }
        if session === conn || session == nil {
            session = nil
            calls.phoneDisconnected()
            extras.phoneDisconnected()
            screen.phoneDisconnected()
            if paired != nil { state = .waiting }
        }
    }

    private func handle(_ conn: PhoneConnection, _ type: String, _ body: Message, _ id: String) {
        let isPaired = paired != nil && conn.fingerprint == paired?.fingerprint

        switch type {
        case "pair.request":
            guard var session = pairing, let fp = conn.fingerprint else {
                conn.send("pair.result", ["ok": false, "reason": "not_pairing"], replyTo: id)
                conn.close()
                return
            }
            guard body["token"] as? String == session.token, Date() < session.expires else {
                conn.send("pair.result", ["ok": false, "reason": "bad_token"], replyTo: id)
                conn.close()
                return
            }
            session.request = (body["name"] as? String ?? "Phone", body["model"] as? String ?? "", fp, id)
            session.connection = conn
            pairing = session
            NSApp.activate()
            #if DEBUG
            if CommandLine.arguments.contains("--auto-accept") { respondToPairing(allow: true) }
            #endif

        case "hello" where isPaired:
            session = conn
            notifications = []
            state = .connected
            let name = body["name"] as? String ?? paired?.name
            if name != paired?.name || conn.remoteHost != paired?.lastHost {
                paired?.name = name ?? "Phone"
                paired?.lastHost = conn.remoteHost ?? paired?.lastHost
                if let paired { savePaired(paired) }
            }
            conn.send("hello.ack", ["name": macName], replyTo: id)
            calls.refresh()
            extras.phoneConnected()
            messages.refresh()
            Log.info("Hello from \(paired?.name ?? "phone")")

        case "status.update" where isPaired:
            let previous = status
            status = PhoneStatus(
                battery: body["battery"] as? Int,
                charging: body["charging"] as? Bool ?? false,
                signal: body["signal"] as? Int,
                network: body["network"] as? String ?? "none",
                wifi: body["wifi"] as? Int,
                sims: (body["sims"] as? [Message] ?? []).compactMap { s in
                    (s["id"] as? Int).map { SimCard(id: $0, label: s["label"] as? String ?? "SIM") }
                })
            if status != previous { Log.info("Status: battery \(status.battery ?? -1)% charging \(status.charging) signal \(status.signal ?? -1) network \(status.network)") }

        case "notif.posted" where isPaired:
            guard let notification = relay.posted(body) else { break }
            Log.info("notif.posted from \(notification.app) initial=\(body["initial"] as? Bool ?? false)")
            notifications.removeAll { $0.id == notification.id }
            notifications.insert(notification, at: 0)
            notifications.sort { $0.time > $1.time }

        case "notif.removed" where isPaired:
            guard let key = body["key"] as? String else { break }
            notifications.removeAll { $0.id == key }
            relay.removed(key)
            Log.info("notif.removed \(key.prefix(24))…")

        case "call.state" where isPaired:
            calls.update(body)

        case "call.result" where isPaired:
            calls.result(body)

        case "calllog.page" where isPaired:
            calls.received(log: body)

        case "calllog.changed" where isPaired:
            session?.send("calllog.request", ["limit": 200])

        case "phone.ring.state" where isPaired: extras.ringState(body)
        case "media.state" where isPaired: extras.mediaState(body)
        case "clip.set" where isPaired: extras.clipFromPhone(body)
        case "file.offer" where isPaired: extras.offer(body)
        case "file.chunk" where isPaired: extras.chunk(body)
        case "file.done" where isPaired: extras.done(body)
        case "file.cancel" where isPaired: extras.cancel(body)
        case "file.result" where isPaired: extras.result(body)
        case "photos.list" where isPaired: extras.photosList(body)
        case "camera.result" where isPaired: extras.captureResult(body)
        case "screen.state" where isPaired: screen.phoneState(body)
        case "apps.list" where isPaired: screen.appsList(body)

        case "sms.threads" where isPaired:
            messages.received(threads: body)

        case "sms.messages" where isPaired:
            messages.received(messages: body)

        case "sms.updated" where isPaired:
            messages.updated(body)

        case "sms.send.result" where isPaired:
            messages.sendResult(body)

        case "sms.changed" where isPaired:
            messages.refresh()

        case "contacts.changed" where isPaired:
            session?.send("contacts.request", [:])

        case "contacts.list" where isPaired:
            calls.received(contacts: body)

        case "unpair" where isPaired:
            conn.close()
            forget()

        default:
            break
        }
    }

    // MARK: Storage

    private func loadPaired() -> PairedPhone? {
        guard let data = try? Data(contentsOf: pairedFile) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(PairedPhone.self, from: data)
    }

    private func savePaired(_ phone: PairedPhone) {
        paired = phone
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = .prettyPrinted
        try? encoder.encode(phone).write(to: pairedFile, options: .atomic)
    }
}

#if DEBUG
extension AppModel {
    /// Lets tests drive the Mac side without clicking notifications:
    /// post distributed notification "app.unwalled.debug.<port>.reply" with object = reply text,
    /// "…dismiss" to dismiss the newest notification, or "…call" with object answer / end / silence / dial:<number>.
    fileprivate func listenForDebugCommands() {
        let dnc = DistributedNotificationCenter.default()
        let prefix = "app.unwalled.debug.\(LinkServer.port.rawValue)."
        dnc.addObserver(forName: .init(prefix + "call"), object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, let command = note.object as? String else { return }
                switch command {
                case "answer": self.calls.answer()
                case "end": self.calls.end()
                case "silence": self.calls.silence()
                default: if command.hasPrefix("dial:") { self.calls.dial(String(command.dropFirst(5))) }
                }
            }
        }
        // "…extras" with object ring | media:<action> | photos | photo:<index> | send:<path>
        dnc.addObserver(forName: .init(prefix + "extras"), object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, let command = note.object as? String else { return }
                if command == "ring" { self.extras.toggleRing() }
                if command == "photos" { self.extras.refreshPhotos() }
                if command.hasPrefix("media:") { self.extras.media(String(command.dropFirst(6))) }
                if command.hasPrefix("photo:"), let i = Int(command.dropFirst(6)), i < self.extras.photos.count {
                    self.extras.download(self.extras.photos[i])
                }
                if command.hasPrefix("send:") { self.extras.sendFile(URL(fileURLWithPath: String(command.dropFirst(5)))) }
            }
        }
        // "…mirror" with object screen | stop | apps | app:<package>
        dnc.addObserver(forName: .init(prefix + "mirror"), object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, let command = note.object as? String else { return }
                if command == "screen" { self.screen.show() }
                if command == "stop" { self.screen.stop() }
                if command == "apps" { self.screen.requestApps() }
                if command.hasPrefix("app:") {
                    let id = String(command.dropFirst(4))
                    self.screen.launch(self.screen.apps.first { $0.id == id } ?? PhoneApp(id: id, label: id, icon: nil))
                }
                Log.info("Mirror: screen \(self.screen.state), control \(self.screen.canControl), apps \(self.screen.apps.count)")
            }
        }
        // "…smsload" with object "<thread id>": load a conversation, as opening it in Messages does.
        dnc.addObserver(forName: .init(prefix + "smsload"), object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated {
                if let thread = (note.object as? String).flatMap(Int64.init) { self?.messages.load(thread) }
            }
        }
        // "…sms" with object "<number>|<text>": send a text from the Mac.
        dnc.addObserver(forName: .init(prefix + "sms"), object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, let parts = (note.object as? String)?.split(separator: "|", maxSplits: 1), parts.count == 2 else { return }
                let to = String(parts[0])
                let thread = self.messages.threads.first { $0.addresses.contains { $0.filter(\.isNumber).hasSuffix(to.filter(\.isNumber).suffix(7)) } }?.id
                self.messages.send(String(parts[1]), to: to, thread: thread)
            }
        }
        dnc.addObserver(forName: .init(prefix + "reply"), object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, let n = self.notifications.first(where: { $0.replyAction != nil }),
                      let action = n.replyAction else { return }
                self.relay.reply(to: n.id, action: action.index, text: note.object as? String ?? "test reply")
            }
        }
        dnc.addObserver(forName: .init(prefix + "dismiss"), object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let n = self.notifications.first else { return }
                self.dismissNotification(n)
            }
        }
    }
}
#endif
