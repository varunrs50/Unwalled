import Foundation
import Network
import Security

typealias Message = [String: Any]

/// Listens for the phone on port 47820 with mutual TLS 1.3 and length-prefixed JSON frames.
/// All state lives on `queue`; callbacks are delivered on the main queue.
final class LinkServer {
    /// 47820; debug builds can run a second test copy on another port with PIXELLINK_PORT.
    static let port: NWEndpoint.Port = {
        #if DEBUG
        if let custom = ProcessInfo.processInfo.environment["PIXELLINK_PORT"].flatMap(UInt16.init) {
            return NWEndpoint.Port(rawValue: custom)!
        }
        #endif
        return 47820
    }()
    static let serviceType = "_pixellink._tcp"

    private let queue = DispatchQueue(label: "app.pixellink.server")
    private let identity: Identity
    private var listener: NWListener?
    private var current: PhoneConnection?
    /// Every open connection, including ones still handshaking; this is what keeps them alive.
    private var open: [ObjectIdentifier: PhoneConnection] = [:]

    // Guarded by `queue`.
    private var pinnedFingerprint: String?
    private var pairingOpen = false

    var onConnected: ((PhoneConnection) -> Void)?
    var onDisconnected: ((PhoneConnection) -> Void)?
    var onMessage: ((PhoneConnection, String, Message, String) -> Void)?

    init(identity: Identity, pinnedFingerprint: String?) {
        self.identity = identity
        self.pinnedFingerprint = pinnedFingerprint
    }

    func setPinned(_ fingerprint: String?) { queue.async { self.pinnedFingerprint = fingerprint } }
    func setPairingOpen(_ open: Bool) { queue.async { self.pairingOpen = open } }

    func start() throws {
        let tls = NWProtocolTLS.Options()
        let sec = tls.securityProtocolOptions
        sec_protocol_options_set_local_identity(sec, sec_identity_create(identity.secIdentity)!)
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)
        sec_protocol_options_set_peer_authentication_required(sec, true)
        sec_protocol_options_set_verify_block(sec, { [weak self] _, trust, complete in
            guard let self, let fp = Self.leafFingerprint(sec_trust_copy_ref(trust).takeRetainedValue()) else {
                complete(false); return
            }
            // Runs on `queue`, so reading the guarded state is safe.
            let allowed = fp == self.pinnedFingerprint || self.pairingOpen
            if !allowed { Log.info("Rejected unknown device \(fp.prefix(16))") }
            complete(allowed)
        }, queue)

        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 30
        let params = NWParameters(tls: tls, tcp: tcp)
        params.allowLocalEndpointReuse = true

        let listener = try NWListener(using: params, on: Self.port)
        let txt = NWTXTRecord(["fp": String(identity.fingerprint.prefix(16))])
        let name = (Host.current().localizedName ?? "Mac") + (Self.port == 47820 ? "" : " (test \(Self.port))")
        listener.service = NWListener.Service(name: name,
                                              type: Self.serviceType, domain: nil, txtRecord: txt)
        listener.stateUpdateHandler = { state in Log.info("Listener: \(state)") }
        listener.newConnectionHandler = { [weak self] nw in self?.accept(nw) }
        listener.start(queue: queue)
        self.listener = listener
    }

    private func accept(_ nw: NWConnection) {
        let conn = PhoneConnection(nw, queue: queue)
        open[ObjectIdentifier(conn)] = conn
        conn.onReady = { [weak self] conn in
            guard let self else { return }
            // One phone at a time: a new trusted connection replaces the old one.
            if let old = self.current, old !== conn { old.close() }
            self.current = conn
            DispatchQueue.main.async { self.onConnected?(conn) }
        }
        conn.onMessage = { [weak self] conn, type, body, id in
            DispatchQueue.main.async { self?.onMessage?(conn, type, body, id) }
        }
        conn.onClosed = { [weak self] conn in
            guard let self else { return }
            self.open[ObjectIdentifier(conn)] = nil
            if self.current === conn { self.current = nil }
            DispatchQueue.main.async { self.onDisconnected?(conn) }
        }
        conn.start()
    }

    static let nudgePort: NWEndpoint.Port = 47822

    /// Sends a wake-up nudge (one UDP datagram) to the phone at `host`.
    func nudge(_ host: String) {
        let message = Data("PLNUDGE1 \(identity.fingerprint.prefix(16))".utf8)
        let udp = NWConnection(host: NWEndpoint.Host(host), port: Self.nudgePort, using: .udp)
        udp.stateUpdateHandler = { state in
            switch state {
            case .ready:
                udp.send(content: message, completion: .contentProcessed { _ in udp.cancel() })
            case .failed, .waiting:
                udp.cancel()
            default:
                break
            }
        }
        udp.start(queue: queue)
        Log.info("Nudged phone at \(host)")
    }

    static func leafFingerprint(_ trust: SecTrust) -> String? {
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first
        else { return nil }
        return sha256Hex(SecCertificateCopyData(leaf) as Data)
    }
}

final class PhoneConnection {
    static let maxFrame = 16 * 1024 * 1024
    static let idleTimeout: TimeInterval = 75

    private let nw: NWConnection
    private let queue: DispatchQueue
    private var lastSeen = Date()
    private var watchdog: DispatchSourceTimer?
    private var closed = false

    /// SHA-256 of the phone's certificate, known once TLS is ready.
    private(set) var fingerprint: String?

    /// The phone's IP address, e.g. "192.168.1.7".
    var remoteHost: String? {
        guard case .hostPort(let host, _) = nw.endpoint else { return nil }
        var text = "\(host)"
        if let percent = text.firstIndex(of: "%") { text = String(text[..<percent]) }
        return text.hasPrefix("::ffff:") ? String(text.dropFirst(7)) : text
    }

    var onReady: ((PhoneConnection) -> Void)?
    var onMessage: ((PhoneConnection, String, Message, String) -> Void)?
    var onClosed: ((PhoneConnection) -> Void)?

    init(_ nw: NWConnection, queue: DispatchQueue) {
        self.nw = nw
        self.queue = queue
    }

    func start() {
        nw.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.fingerprint = self.readPeerFingerprint()
                Log.info("Phone connected from \(self.nw.endpoint)")
                self.startWatchdog()
                self.onReady?(self)
                self.receiveHeader()
            case .failed(let error):
                Log.info("Connection failed: \(error)")
                self.close()
            case .cancelled:
                self.close()
            default:
                break
            }
        }
        nw.start(queue: queue)
    }

    /// `sent` is called (on the connection's queue) once the frame has been handed to the network,
    /// so large transfers can wait for it before sending the next chunk.
    func send(_ type: String, _ body: Message = [:], replyTo id: String? = nil, sent: ((Bool) -> Void)? = nil) {
        let envelope: Message = [
            "v": 1, "id": id ?? UUID().uuidString.lowercased(), "type": type,
            "ts": Int64(Date().timeIntervalSince1970 * 1000), "body": body,
        ]
        guard let json = try? JSONSerialization.data(withJSONObject: envelope) else { sent?(false); return }
        var length = UInt32(json.count).bigEndian
        var frame = Data(bytes: &length, count: 4)
        frame.append(json)
        nw.send(content: frame, completion: .contentProcessed { error in sent?(error == nil) })
    }

    func close() {
        queue.async {
            guard !self.closed else { return }
            self.closed = true
            self.watchdog?.cancel()
            self.nw.cancel()
            self.onClosed?(self)
        }
    }

    private func receiveHeader() {
        nw.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, _, error in
            guard let self else { return }
            guard error == nil, let data, data.count == 4 else { self.close(); return }
            let length = data.reduce(0) { ($0 << 8) | Int($1) }
            guard length > 0, length <= Self.maxFrame else { self.close(); return }
            self.receiveBody(length)
        }
    }

    private func receiveBody(_ length: Int) {
        nw.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] data, _, _, error in
            guard let self else { return }
            guard error == nil, let data, data.count == length else { self.close(); return }
            self.lastSeen = Date()
            if let envelope = try? JSONSerialization.jsonObject(with: data) as? Message,
               let type = envelope["type"] as? String {
                let id = envelope["id"] as? String ?? ""
                if type == "ping" {
                    self.send("pong", replyTo: id)
                } else {
                    self.onMessage?(self, type, envelope["body"] as? Message ?? [:], id)
                }
            }
            self.receiveHeader()
        }
    }

    private func startWatchdog() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 15, repeating: 15)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            if Date().timeIntervalSince(self.lastSeen) > Self.idleTimeout {
                Log.info("Phone silent for \(Int(Self.idleTimeout))s, closing")
                self.close()
            }
        }
        timer.resume()
        watchdog = timer
    }

    private func readPeerFingerprint() -> String? {
        guard let meta = nw.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata
        else { return nil }
        var leaf: Data?
        sec_protocol_metadata_access_peer_certificate_chain(meta.securityProtocolMetadata) { cert in
            if leaf == nil {
                leaf = SecCertificateCopyData(sec_certificate_copy_ref(cert).takeRetainedValue()) as Data
            }
        }
        return leaf.map(sha256Hex)
    }
}
