import AppKit
import Foundation
import UserNotifications

struct PhoneNotification: Identifiable {
    struct Action {
        let index: Int
        let title: String
        let reply: Bool
    }

    let id: String          // Android notification key
    let package: String
    let app: String
    let title: String
    let text: String
    let time: Date
    let actions: [Action]
    let picture: Data?

    var replyAction: Action? { actions.first { $0.reply } }
}

/// Shows phone notifications as native macOS notifications and turns the user's
/// reply, button and dismiss on the Mac into frames for the phone.
@MainActor
final class NotificationRelay: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    private var categories: [String: UNNotificationCategory] = [:]
    private let iconDir = AppPaths.support.appendingPathComponent("icons", isDirectory: true)

    static let missedCallCategory = "pl.missedcall"

    /// Sends a frame to the phone; set by AppModel.
    var send: ((String, Message) -> Void)?
    /// "Call back" on a missed-call notification; set by AppModel.
    var onCallBack: ((String) -> Void)?

    func start() {
        try? FileManager.default.createDirectory(at: iconDir, withIntermediateDirectories: true)
        center.delegate = self
        let callBack = UNNotificationAction(identifier: "callback", title: "Call Back", options: [])
        categories[Self.missedCallCategory] = UNNotificationCategory(identifier: Self.missedCallCategory, actions: [callBack],
                                                                     intentIdentifiers: [], options: [])
        center.setNotificationCategories(Set(categories.values))
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            Log.info("Notification permission: \(granted ? "granted" : "not granted") \(error.map { "\($0)" } ?? "")")
        }
    }

    /// Parses a `notif.posted` body; shows it unless it was already on the phone (`initial`).
    func posted(_ body: Message) -> PhoneNotification? {
        guard let key = body["key"] as? String, let package = body["package"] as? String else { return nil }
        if let icon = (body["icon"] as? String).flatMap({ Data(base64Encoded: $0) }) {
            try? icon.write(to: iconURL(package), options: .atomic)
        }
        let actions = (body["actions"] as? [Message] ?? []).compactMap { a -> PhoneNotification.Action? in
            guard let index = a["index"] as? Int, let title = a["title"] as? String else { return nil }
            return .init(index: index, title: title, reply: a["reply"] as? Bool ?? false)
        }
        let notification = PhoneNotification(
            id: key,
            package: package,
            app: body["app"] as? String ?? package,
            title: body["title"] as? String ?? "",
            text: body["text"] as? String ?? "",
            time: Date(timeIntervalSince1970: (body["time"] as? Double ?? 0) / 1000),
            actions: actions,
            picture: (body["picture"] as? String).flatMap { Data(base64Encoded: $0) })
        if body["initial"] as? Bool != true, body["silent"] as? Bool != true { deliver(notification) }
        return notification
    }

    func removed(_ key: String) {
        center.removeDeliveredNotifications(withIdentifiers: [key])
    }

    func removeAll() {
        center.removeAllDeliveredNotifications()
    }

    func icon(for package: String) -> NSImage? {
        NSImage(contentsOf: iconURL(package))
    }

    func dismissOnPhone(_ key: String) {
        send?("notif.dismiss", ["key": key])
        removed(key)
    }

    func reply(to key: String, action: Int, text: String) {
        send?("notif.reply", ["key": key, "action": action, "text": text])
        Log.info("Reply sent for \(key.prefix(24))…")
    }

    private func deliver(_ n: PhoneNotification) {
        let content = UNMutableNotificationContent()
        content.title = n.title.isEmpty ? n.app : n.title
        content.subtitle = n.title.isEmpty ? "" : n.app
        content.body = n.text
        content.sound = .default
        content.threadIdentifier = n.package
        content.userInfo = ["key": n.id]
        content.categoryIdentifier = category(for: n.actions)
        if let attachment = attachment(for: n) { content.attachments = [attachment] }

        center.add(UNNotificationRequest(identifier: n.id, content: content, trigger: nil)) { error in
            if let error { Log.info("Couldn't show notification: \(error)") }
        }
        Log.info("Notification from \(n.app): \(content.title)")
    }

    /// Picture (e.g. contact photo) if the phone sent one, otherwise the app's icon.
    private func attachment(for n: PhoneNotification) -> UNNotificationAttachment? {
        let data = n.picture ?? (try? Data(contentsOf: iconURL(n.package)))
        guard let data else { return nil }
        // The system moves attachment files into its own store, so each needs a fresh copy.
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).png")
        guard (try? data.write(to: file)) != nil else { return nil }
        return try? UNNotificationAttachment(identifier: "image", url: file)
    }

    /// Categories describe the buttons; they're keyed by the phone's action layout and registered on first use.
    private func category(for actions: [PhoneNotification.Action]) -> String {
        let reply = actions.first { $0.reply }
        let buttons = actions.filter { !$0.reply }.prefix(2)
        let id = "pl|" + ([reply].compactMap { $0 }.map { "r\($0.index):\($0.title)" } + buttons.map { "a\($0.index):\($0.title)" })
            .joined(separator: "|")
        if categories[id] == nil {
            var unActions: [UNNotificationAction] = []
            if let reply {
                unActions.append(UNTextInputNotificationAction(
                    identifier: "reply:\(reply.index)", title: reply.title.isEmpty ? "Reply" : reply.title,
                    options: [], textInputButtonTitle: "Send", textInputPlaceholder: "Message"))
            }
            unActions += buttons.map { UNNotificationAction(identifier: "action:\($0.index)", title: $0.title, options: []) }
            categories[id] = UNNotificationCategory(identifier: id, actions: unActions, intentIdentifiers: [],
                                                    options: [.customDismissAction])
            center.setNotificationCategories(Set(categories.values))
        }
        return id
    }

    private func iconURL(_ package: String) -> URL {
        iconDir.appendingPathComponent("\(package).png")
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let key = response.notification.request.content.userInfo["key"] as? String
        let callBack = response.notification.request.content.userInfo["callback"] as? String
        let actionID = response.actionIdentifier
        let text = (response as? UNTextInputNotificationResponse)?.userText
        Task { @MainActor in
            defer { completionHandler() }
            if let callBack, actionID == "callback" || actionID == UNNotificationDefaultActionIdentifier {
                self.onCallBack?(callBack)
                return
            }
            guard let key else { return }
            if actionID == UNNotificationDismissActionIdentifier {
                self.send?("notif.dismiss", ["key": key])
            } else if actionID.hasPrefix("reply:"), let index = Int(actionID.dropFirst(6)), let text, !text.isEmpty {
                self.reply(to: key, action: index, text: text)
            } else if actionID.hasPrefix("action:"), let index = Int(actionID.dropFirst(7)) {
                self.send?("notif.action", ["key": key, "action": index])
            }
        }
    }
}
