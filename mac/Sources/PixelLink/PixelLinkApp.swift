import SwiftUI

@main
struct PixelLinkApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        MenuBarExtra {
            MenuContent().environment(model)
        } label: {
            Image(systemName: model.menuBarSymbol)
        }
        .menuBarExtraStyle(.window)

        Window("Pair Phone", id: "pairing") {
            PairingView().environment(model)
        }
        .windowResizability(.contentSize)

        Window("Messages", id: "messages") {
            PlaceholderView(title: "Messages", detail: "SMS threads arrive in milestone M4.")
        }

        Window("Calls", id: "calls") {
            PlaceholderView(title: "Calls", detail: "Call log and contacts arrive in milestone M3.")
        }
    }

    init() {
        #if DEBUG
        if CommandLine.arguments.contains("--pair") {
            let model = _model.wrappedValue
            DispatchQueue.main.async { model.startPairing() }
        }
        #endif
    }
}

struct MenuContent: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header

            if let error = model.startupError {
                Text(error).font(.caption).foregroundStyle(.red)
            }

            if !model.notifications.isEmpty {
                Divider()
                Text("Notifications").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                ForEach(model.notifications.prefix(5)) { notification in
                    NotificationRow(notification: notification)
                }
                if model.notifications.count > 5 {
                    Text("+ \(model.notifications.count - 5) more on your phone").font(.caption).foregroundStyle(.secondary)
                }
            }

            Divider()

            if model.paired == nil {
                Button("Pair a phone…") { showPairing() }
            } else {
                Button("Messages…") { open("messages") }
                Button("Calls…") { open("calls") }
            }

            Divider()

            if model.paired != nil {
                Button("Unpair \(model.paired?.name ?? "phone")") { model.unpair() }
            }
            Button("Quit PixelLink") { NSApplication.shared.terminate(nil) }
                .keyboardShortcut("q")
        }
        .buttonStyle(.borderless)
        .padding(14)
        .frame(width: 280)
    }

    @ViewBuilder private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: model.menuBarSymbol)
                .font(.title2)
                .foregroundStyle(model.state == .connected ? Color.accentColor : .secondary)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(model.paired?.name ?? "No phone paired").font(.headline)
                switch model.state {
                case .notPaired:
                    Text("Pair your phone to get started").font(.caption).foregroundStyle(.secondary)
                case .waiting:
                    Text("Waiting for phone on Wi-Fi…").font(.caption).foregroundStyle(.secondary)
                case .connected:
                    StatusLine(status: model.status)
                }
            }
        }
    }

    private func open(_ id: String) {
        openWindow(id: id)
        NSApp.activate()
    }

    private func showPairing() {
        model.startPairing()
        open("pairing")
    }
}

struct StatusLine: View {
    let status: PhoneStatus

    var body: some View {
        HStack(spacing: 10) {
            if let battery = status.battery {
                Label("\(battery)%", systemImage: batterySymbol(battery))
            }
            if let signal = status.signal {
                Label("\(signal)/4", systemImage: "cellularbars")
            }
            if status.network == "wifi" {
                Image(systemName: "wifi")
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .labelStyle(.titleAndIcon)
    }

    private func batterySymbol(_ level: Int) -> String {
        if status.charging { return "battery.100percent.bolt" }
        switch level {
        case ..<13: return "battery.0percent"
        case ..<38: return "battery.25percent"
        case ..<63: return "battery.50percent"
        case ..<88: return "battery.75percent"
        default: return "battery.100percent"
        }
    }
}

struct PlaceholderView: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 8) {
            Text(title).font(.title2)
            Text(detail).foregroundStyle(.secondary)
        }
        .frame(minWidth: 480, minHeight: 320)
    }
}

struct NotificationRow: View {
    @Environment(AppModel.self) private var model
    let notification: PhoneNotification

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Group {
                if let icon = model.relay.icon(for: notification.package) {
                    Image(nsImage: icon).resizable()
                } else {
                    Image(systemName: "app.badge").resizable().foregroundStyle(.secondary)
                }
            }
            .frame(width: 20, height: 20)
            .clipShape(RoundedRectangle(cornerRadius: 5))

            VStack(alignment: .leading, spacing: 1) {
                HStack {
                    Text(notification.title.isEmpty ? notification.app : notification.title)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(notification.time, style: .relative)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Text(notification.text)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Button {
                model.dismissNotification(notification)
            } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
            }
            .help("Clear on phone")
        }
    }
}
