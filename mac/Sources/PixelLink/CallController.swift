import AppKit
import Observation
import SwiftUI
import UserNotifications

struct PhoneCall: Equatable {
    enum State: String { case ringing, active, ended }

    let id: String
    var state: State
    let direction: String
    var number: String?
    var name: String?
    var photo: Data?
    var reason: String?
    var activeSince: Date?

    var displayName: String { name ?? number ?? "Unknown caller" }
}

struct CallLogEntry: Codable, Identifiable {
    var id: String { "\(time)-\(number)" }
    let number: String
    let name: String?
    let type: String
    let time: Date
    let duration: Int
}

struct PhoneContact: Codable, Identifiable {
    struct Number: Codable, Hashable {
        let number: String
        let label: String
    }

    var id: String { name + (numbers.first?.number ?? "") }
    let name: String
    let numbers: [Number]
}

/// Shows the phone's calls on the Mac (a floating pop-up plus missed-call notifications) and
/// sends answer, decline, silence and dial back. Call audio stays on the phone.
@MainActor
@Observable
final class CallController {
    private(set) var current: PhoneCall?
    private(set) var log: [CallLogEntry] = []
    private(set) var contacts: [PhoneContact] = []
    private(set) var lastError: String?

    /// Sends a frame to the phone; set by AppModel.
    @ObservationIgnored var send: ((String, Message) -> Void)?
    @ObservationIgnored private var panel: NSPanel?
    @ObservationIgnored private var hideTask: Task<Void, Never>?
    @ObservationIgnored private let logFile = AppPaths.support.appendingPathComponent("calllog.json")
    @ObservationIgnored private let contactsFile = AppPaths.support.appendingPathComponent("contacts.json")

    init() {
        log = load([CallLogEntry].self, from: logFile) ?? []
        contacts = load([PhoneContact].self, from: contactsFile) ?? []
    }

    // MARK: From the phone

    func update(_ body: Message) {
        guard let id = body["id"] as? String, let state = (body["state"] as? String).flatMap(PhoneCall.State.init) else { return }
        var call = current?.id == id ? current! : PhoneCall(id: id, state: state, direction: body["direction"] as? String ?? "incoming")
        call.state = state
        call.number = body["number"] as? String ?? call.number
        call.name = body["name"] as? String ?? call.name
        call.photo = (body["photo"] as? String).flatMap { Data(base64Encoded: $0) } ?? call.photo
        call.reason = body["reason"] as? String
        if state == .active, call.activeSince == nil { call.activeSince = Date() }
        current = call
        lastError = nil
        Log.info("Call \(call.direction) \(state.rawValue)\(call.reason.map { " (\($0))" } ?? "") \(call.displayName)")

        hideTask?.cancel()
        switch state {
        case .ringing, .active:
            showPanel()
        case .ended:
            if call.reason == "missed" { notifyMissed(call) }
            // Leave "Call ended" up briefly, then clear it.
            hideTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(call.reason == "missed" ? 0.5 : 2))
                guard !Task.isCancelled, let self, self.current?.id == id else { return }
                self.current = nil
                self.panel?.orderOut(nil)
            }
        }
    }

    func result(_ body: Message) {
        guard body["ok"] as? Bool == false else { return }
        let action = body["action"] as? String ?? "action"
        lastError = "Couldn't \(action == "end" ? "end the call" : action): \(body["error"] as? String ?? "unknown error")"
        Log.info(lastError!)
    }

    func received(log body: Message) {
        let entries = (body["entries"] as? [Message] ?? []).compactMap { e -> CallLogEntry? in
            guard let number = e["number"] as? String, let time = e["time"] as? Double else { return nil }
            return CallLogEntry(number: number, name: e["name"] as? String, type: e["type"] as? String ?? "incoming",
                                time: Date(timeIntervalSince1970: time / 1000), duration: e["duration"] as? Int ?? 0)
        }
        log = entries
        save(entries, to: logFile)
    }

    func received(contacts body: Message) {
        let list = (body["contacts"] as? [Message] ?? []).compactMap { c -> PhoneContact? in
            guard let name = c["name"] as? String else { return nil }
            let numbers = (c["numbers"] as? [Message] ?? []).compactMap { n -> PhoneContact.Number? in
                guard let number = n["number"] as? String else { return nil }
                return .init(number: number, label: n["label"] as? String ?? "")
            }
            return PhoneContact(name: name, numbers: numbers)
        }
        contacts = list
        save(list, to: contactsFile)
    }

    func phoneDisconnected() {
        // A call can't be controlled without the phone, so don't leave a stale pop-up up.
        current = nil
        panel?.orderOut(nil)
    }

    // MARK: To the phone

    func answer() { send?("call.answer", [:]) }
    func end() { send?("call.end", [:]) }
    func silence() { send?("call.silence", [:]) }

    func dial(_ number: String) {
        let cleaned = number.filter { $0.isNumber || $0 == "+" || $0 == "*" || $0 == "#" }
        guard !cleaned.isEmpty else { return }
        send?("call.dial", ["number": cleaned])
        Log.info("Dialling \(cleaned)")
    }

    func refresh() {
        send?("calllog.request", ["limit": 200])
        send?("contacts.request", [:])
    }

    func name(for number: String) -> String? {
        let digits = number.filter(\.isNumber).suffix(10)
        guard digits.count >= 6 else { return nil }
        return contacts.first { $0.numbers.contains { $0.number.filter(\.isNumber).hasSuffix(digits) } }?.name
    }

    // MARK: Pop-up

    private static let panelSize = NSSize(width: 340, height: 96)

    private func showPanel() {
        if panel == nil {
            let panel = CallPanel(contentRect: NSRect(x: 0, y: 0, width: 340, height: 96),
                                  styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
            // Panels hide whenever their app isn't active by default, and a menu bar app almost never is.
            panel.hidesOnDeactivate = false
            panel.level = .statusBar
            panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
            panel.isMovableByWindowBackground = true
            panel.backgroundColor = .clear
            panel.hasShadow = true
            let hosting = FirstClickHostingView(rootView: CallPanelView(calls: self))
            // The content is empty between calls; don't let that shrink the window to 0×0.
            hosting.sizingOptions = []
            panel.contentView = hosting
            self.panel = panel
        }
        guard let panel else { return }
        // Always place it by its fixed size: top right of the screen in use.
        if !panel.isVisible, let screen = (NSScreen.screens.first { $0.frame.contains(NSEvent.mouseLocation) } ?? NSScreen.main)?.visibleFrame {
            let size = Self.panelSize
            panel.setFrame(NSRect(x: screen.maxX - size.width - 16, y: screen.maxY - size.height - 12,
                                  width: size.width, height: size.height), display: true)
        }
        panel.orderFrontRegardless()
    }

    private func notifyMissed(_ call: PhoneCall) {
        let content = UNMutableNotificationContent()
        content.title = "Missed call"
        content.body = call.name.map { "\($0)\(call.number.map { " · \($0)" } ?? "")" } ?? call.number ?? "Unknown caller"
        content.sound = .default
        if let number = call.number {
            content.userInfo = ["callback": number]
            content.categoryIdentifier = NotificationRelay.missedCallCategory
        }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "call-\(call.id)", content: content, trigger: nil))
    }

    // MARK: Storage

    private func load<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return try? decoder.decode(type, from: data)
    }

    private func save<T: Encodable>(_ value: T, to url: URL) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        try? encoder.encode(value).write(to: url, options: .atomic)
    }
}

/// Borderless panels can't take clicks as key windows by default; this one can, without activating the app.
private final class CallPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

/// Without this, the first click on a background app's window only brings it forward instead of pressing the button.
private final class FirstClickHostingView<Content: View>: NSHostingView<Content> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

struct CallPanelView: View {
    let calls: CallController

    var body: some View {
        if let call = calls.current {
            HStack(spacing: 12) {
                ContactAvatar(name: call.name, photo: call.photo, size: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(call.displayName).font(.headline).lineLimit(1)
                    if call.name != nil, let number = call.number {
                        Text(number).font(.caption).foregroundStyle(.secondary)
                    }
                    statusLine(call)
                }
                Spacer(minLength: 0)
                buttons(call)
            }
            .padding(14)
            .frame(width: 340, height: 96)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
        }
    }

    @ViewBuilder private func statusLine(_ call: PhoneCall) -> some View {
        switch call.state {
        case .ringing:
            Text(call.direction == "incoming" ? "Incoming call on your phone" : "Calling…")
                .font(.caption).foregroundStyle(.secondary)
        case .active:
            if let since = call.activeSince {
                (Text(since, style: .timer) + Text(" · audio on your phone"))
                    .font(.caption).foregroundStyle(.secondary)
            }
        case .ended:
            Text(call.reason == "missed" ? "Missed call" : "Call ended").font(.caption).foregroundStyle(.secondary)
        }
        if let error = calls.lastError {
            Text(error).font(.caption2).foregroundStyle(.red).lineLimit(1)
        }
    }

    @ViewBuilder private func buttons(_ call: PhoneCall) -> some View {
        switch call.state {
        case .ringing where call.direction == "incoming":
            VStack(spacing: 4) {
                HStack(spacing: 10) {
                    RoundCallButton(symbol: "phone.down.fill", color: .red, help: "Decline") { calls.end() }
                    RoundCallButton(symbol: "phone.fill", color: .green, help: "Answer") { calls.answer() }
                }
                Button("Silence") { calls.silence() }
                    .buttonStyle(.borderless)
                    .font(.caption2)
            }
        case .ringing, .active:
            RoundCallButton(symbol: "phone.down.fill", color: .red, help: "End call") { calls.end() }
        case .ended:
            EmptyView()
        }
    }
}

private struct RoundCallButton: View {
    let symbol: String
    let color: Color
    let help: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(color, in: Circle())
        }
        .buttonStyle(.plain)
        .help(help)
    }
}

struct ContactAvatar: View {
    let name: String?
    let photo: Data?
    let size: CGFloat

    var body: some View {
        Group {
            if let photo, let image = NSImage(data: photo) {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    Circle().fill(.quaternary)
                    if let initials {
                        Text(initials).font(.system(size: size * 0.38, weight: .medium)).foregroundStyle(.secondary)
                    } else {
                        Image(systemName: "person.fill").font(.system(size: size * 0.45)).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }

    private var initials: String? {
        guard let name, name.first?.isLetter == true else { return nil }
        return name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined().uppercased()
    }
}
