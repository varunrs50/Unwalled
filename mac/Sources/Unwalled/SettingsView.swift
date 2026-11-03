import ServiceManagement
import SwiftUI

/// Unwalled's settings: start at login, clipboard, stored history and the paired phone.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var loginError: String?
    @State private var confirmClear = false
    @State private var confirmUnpair = false

    var body: some View {
        @Bindable var extras = model.extras
        Form {
            Section {
                Toggle("Open Unwalled when you log in", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { _, on in setLaunchAtLogin(on) }
                if let loginError {
                    Text(loginError).font(.caption).foregroundStyle(.orange)
                }
                Toggle("Share clipboard with phone", isOn: $extras.clipboardSync)
            }

            Section("Phone") {
                LabeledContent("Paired phone", value: model.paired?.name ?? "None")
                LabeledContent("Status", value: statusText)
                if model.paired != nil {
                    Button("Unpair…", role: .destructive) { confirmUnpair = true }
                }
            }

            Section {
                Button("Report a Problem…") { reportProblem() }
            } footer: {
                Text("Opens a bug report on GitHub and shows Unwalled's log file to attach. The log doesn't contain your messages, names or numbers.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section {
                Button("Clear History…") { confirmClear = true }
            } footer: {
                Text("Removes the messages, calls, contacts and photos this Mac keeps from your phone. They're fetched again while it's connected.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 440)
        .fixedSize(horizontal: false, vertical: true)
        .confirmationDialog("Clear history on this Mac?", isPresented: $confirmClear) {
            Button("Clear History", role: .destructive) { model.clearHistory() }
        } message: {
            Text("Nothing on your phone is deleted.")
        }
        .confirmationDialog("Unpair \(model.paired?.name ?? "phone")?", isPresented: $confirmUnpair) {
            Button("Unpair", role: .destructive) { model.unpair() }
        } message: {
            Text("You'll need to scan a pairing code again to reconnect.")
        }
        .onAppear { launchAtLogin = SMAppService.mainApp.status == .enabled }
    }

    private var statusText: String {
        switch model.state {
        case .connected: "Connected"
        case .waiting: "Waiting for the phone on Wi-Fi"
        case .notPaired: "Not paired"
        }
    }

    private func reportProblem() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let os = ProcessInfo.processInfo.operatingSystemVersion
        var url = URLComponents(string: "https://github.com/varunrs50/Unwalled/issues/new")!
        url.queryItems = [
            .init(name: "template", value: "bug_report.yml"),
            .init(name: "version", value: version),
            .init(name: "macos", value: "macOS \(os.majorVersion).\(os.minorVersion).\(os.patchVersion)"),
        ]
        if let link = url.url { NSWorkspace.shared.open(link) }
        NSWorkspace.shared.activateFileViewerSelecting([Log.file])
    }

        private func setLaunchAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            loginError = nil
        } catch {
            loginError = "macOS didn't allow it: \(error.localizedDescription). Move Unwalled to Applications and try again."
            launchAtLogin = SMAppService.mainApp.status == .enabled
        }
        if SMAppService.mainApp.status == .requiresApproval {
            loginError = "Allow Unwalled in System Settings → General → Login Items."
        }
    }
}
