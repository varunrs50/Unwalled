import AppKit
import Foundation
import Observation

struct PairedPhone: Codable {
    var name: String
    var model: String
    var fingerprint: String
    var pairedAt: Date
}

struct PhoneStatus {
    var battery: Int?
    var charging = false
    var signal: Int?
    var network = "none"
    var wifi: Int?
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
            try server.start()
            self.server = server
            self.identity = identity
            Log.info("Listening on port \(LinkServer.port), fingerprint \(identity.fingerprint.prefix(16))")
        } catch {
            startupError = "Couldn't start: \(error)"
            Log.info(startupError!)
        }
    }

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
        components.scheme = "pixellink"
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
        Log.info("Pairing open: \(payload)")
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
                                fingerprint: request.fingerprint, pairedAt: Date())
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
            if let name = body["name"] as? String, name != paired?.name {
                paired?.name = name
                if let paired { savePaired(paired) }
            }
            conn.send("hello.ack", ["name": macName], replyTo: id)
            Log.info("Hello from \(paired?.name ?? "phone")")

        case "status.update" where isPaired:
            status = PhoneStatus(
                battery: body["battery"] as? Int,
                charging: body["charging"] as? Bool ?? false,
                signal: body["signal"] as? Int,
                network: body["network"] as? String ?? "none",
                wifi: body["wifi"] as? Int)
            Log.info("Status: battery \(status.battery ?? -1)% charging \(status.charging) signal \(status.signal ?? -1) network \(status.network)")

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
    /// post distributed notification "app.pixellink.debug.reply" with object = reply text,
    /// or "app.pixellink.debug.dismiss" to dismiss the newest notification.
    fileprivate func listenForDebugCommands() {
        let dnc = DistributedNotificationCenter.default()
        dnc.addObserver(forName: .init("app.pixellink.debug.reply"), object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, let n = self.notifications.first(where: { $0.replyAction != nil }),
                      let action = n.replyAction else { return }
                self.relay.reply(to: n.id, action: action.index, text: note.object as? String ?? "test reply")
            }
        }
        dnc.addObserver(forName: .init("app.pixellink.debug.dismiss"), object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let n = self.notifications.first else { return }
                self.dismissNotification(n)
            }
        }
    }
}
#endif
