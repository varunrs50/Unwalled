import Foundation
import Observation

struct SmsThread: Codable, Identifiable, Equatable {
    let id: Int64
    var addresses: [String]
    var name: String?
    var snippet: String
    var time: Date
    var unread: Bool

    var title: String { name ?? addresses.joined(separator: ", ") }
}

struct SmsMessage: Codable, Identifiable, Equatable {
    enum Status: String, Codable { case received, sending, sent, delivered, failed }

    /// Android's message ID, or a negative placeholder for a message the Mac is still sending.
    let id: Int64
    let thread: Int64
    let address: String
    let body: String
    let time: Date
    let incoming: Bool
    var status: Status
}

/// The Mac's copy of the phone's SMS threads. Kept on disk so the Messages window has
/// something to show while the phone is away.
@MainActor
@Observable
final class MessagesStore {
    private(set) var threads: [SmsThread] = []
    private(set) var messages: [Int64: [SmsMessage]] = [:]
    private(set) var lastError: String?

    /// Sends a frame to the phone; set by AppModel.
    @ObservationIgnored var toPhone: ((String, Message) -> Void)?
    /// Messages sent from the Mac that the phone hasn't confirmed yet, by client ID.
    @ObservationIgnored private var pending: [String: SmsMessage] = [:]
    @ObservationIgnored private var nextPlaceholder: Int64 = -1
    @ObservationIgnored private let file = AppPaths.support.appendingPathComponent("messages.json")

    private struct Saved: Codable {
        var threads: [SmsThread]
        var messages: [Int64: [SmsMessage]]
    }

    init() {
        if let data = try? Data(contentsOf: file), let saved = try? Self.decoder.decode(Saved.self, from: data) {
            threads = saved.threads
            messages = saved.messages
        }
    }

    // MARK: To the phone

    func refresh() {
        toPhone?("sms.threads.request", ["limit": 200])
    }

    func load(_ thread: Int64) {
        toPhone?("sms.messages.request", ["thread": thread, "limit": 200])
    }

    /// Sends `body` to `to` (a phone number) from the phone's SIM; shows it straight away as "sending".
    func send(_ body: String, to: String, thread: Int64?) {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !to.isEmpty else { return }
        let clientID = UUID().uuidString
        lastError = nil
        toPhone?("sms.send", ["to": to, "body": text, "client_id": clientID])
        if let thread {
            let placeholder = SmsMessage(id: nextPlaceholder, thread: thread, address: to, body: text, time: Date(),
                                         incoming: false, status: .sending)
            nextPlaceholder -= 1
            pending[clientID] = placeholder
            messages[thread, default: []].append(placeholder)
        }
        Log.info("SMS sent to phone for \(to)")
    }

    // MARK: From the phone

    func received(threads body: Message) {
        threads = (body["threads"] as? [Message] ?? []).compactMap { t in
            guard let id = (t["id"] as? NSNumber)?.int64Value else { return nil }
            return SmsThread(id: id, addresses: t["addresses"] as? [String] ?? [], name: t["name"] as? String,
                             snippet: t["snippet"] as? String ?? "", time: Self.date(t["time"]),
                             unread: t["unread"] as? Bool ?? false)
        }
        save()
    }

    func received(messages body: Message) {
        guard let thread = (body["thread"] as? NSNumber)?.int64Value else { return }
        let list = (body["messages"] as? [Message] ?? []).compactMap(Self.message)
        // Keep any of our own still-sending placeholders until the phone confirms them.
        messages[thread] = list + (messages[thread] ?? []).filter { $0.id < 0 }
        save()
    }

    func updated(_ body: Message) {
        let changed = (body["messages"] as? [Message] ?? []).compactMap(Self.message)
        Log.info("SMS update: \(changed.count) message(s)")
        for message in changed {
            var list = messages[message.thread] ?? []
            if let index = list.firstIndex(where: { $0.id == message.id }) {
                list[index] = message
            } else {
                // A message we sent from the Mac replaces its placeholder.
                if !message.incoming, let index = list.firstIndex(where: { $0.id < 0 && $0.body == message.body }) {
                    list.remove(at: index)
                }
                list.append(message)
                list.sort { $0.time < $1.time }
            }
            messages[message.thread] = list
            bump(thread: message.thread, with: message)
        }
        save()
    }

    func sendResult(_ body: Message) {
        guard let clientID = body["client_id"] as? String else { return }
        let placeholder = pending.removeValue(forKey: clientID)
        guard body["ok"] as? Bool == false else { return }
        lastError = "Couldn't send: \(body["error"] as? String ?? "unknown error")"
        if let placeholder, var list = messages[placeholder.thread],
           let index = list.firstIndex(where: { $0.id == placeholder.id }) {
            list[index].status = .failed
            messages[placeholder.thread] = list
        }
        Log.info(lastError!)
    }

    func markRead(_ thread: Int64) {
        guard let index = threads.firstIndex(where: { $0.id == thread }), threads[index].unread else { return }
        threads[index].unread = false
    }

    // MARK: Helpers

    private func bump(thread id: Int64, with message: SmsMessage) {
        if let index = threads.firstIndex(where: { $0.id == id }) {
            var thread = threads.remove(at: index)
            thread.snippet = message.body
            thread.time = message.time
            if message.incoming { thread.unread = true }
            threads.insert(thread, at: 0)
        } else {
            // A brand-new conversation; fetch the list so it gets its name.
            refresh()
        }
    }

    private static func message(_ m: Message) -> SmsMessage? {
        guard let id = (m["id"] as? NSNumber)?.int64Value, let thread = (m["thread"] as? NSNumber)?.int64Value else { return nil }
        return SmsMessage(id: id, thread: thread, address: m["address"] as? String ?? "", body: m["body"] as? String ?? "",
                          time: date(m["time"]), incoming: m["direction"] as? String == "in",
                          status: (m["status"] as? String).flatMap(SmsMessage.Status.init) ?? .sent)
    }

    private static func date(_ value: Any?) -> Date {
        Date(timeIntervalSince1970: ((value as? NSNumber)?.doubleValue ?? 0) / 1000)
    }

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return d
    }()

    private func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        let saved = Saved(threads: threads, messages: messages.mapValues { $0.filter { $0.id >= 0 }.suffix(200).map { $0 } })
        try? encoder.encode(saved).write(to: file, options: .atomic)
    }
}
