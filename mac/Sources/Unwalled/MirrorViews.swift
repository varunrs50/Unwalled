import SwiftUI

/// Menu items for screen mirroring, plus what it's waiting for or why it failed.
struct MirrorMenuSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let screen = model.screen
        Button(screen.state == .running ? "Show Phone Screen" : "Phone Screen") { screen.show() }
            .disabled(model.state != .connected)
        if screen.ready, screen.state != .waiting {
            HStack {
                Text("Opens without asking on the phone").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("End") { screen.end() }.font(.caption)
                    .help("End screen sharing on the phone; it will ask again next time")
            }
        }
        switch screen.state {
        case .waiting:
            HStack(alignment: .top, spacing: 6) {
                ProgressView().controlSize(.small)
                Text("Tap the notification on your phone, then Start").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button { screen.stop() } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }
                    .help("Stop")
            }
        case .running where !screen.canControl:
            Text("To control the phone with your mouse, turn on Unwalled phone control in the Unwalled app on the phone.")
                .font(.caption).foregroundStyle(.secondary)
        case .failed(let error):
            HStack(alignment: .top) {
                Text(error).font(.caption).foregroundStyle(.orange)
                Spacer()
                Button { screen.dismissError() } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }
            }
        default:
            EmptyView()
        }
    }
}

/// Apps on the phone; click one to open it on the phone, shown in the Phone Screen window.
struct AppsView: View {
    @Environment(AppModel.self) private var model
    @State private var search = ""

    private let columns = [GridItem(.adaptive(minimum: 84, maximum: 100), spacing: 8)]

    var body: some View {
        let apps = model.screen.apps.filter { search.isEmpty || $0.label.localizedCaseInsensitiveContains(search) }
        ScrollView {
            LazyVGrid(columns: columns, spacing: 12) {
                ForEach(apps) { app in
                    Button { model.screen.launch(app) } label: {
                        VStack(spacing: 6) {
                            Group {
                                if let icon = app.icon, let image = NSImage(data: icon) {
                                    Image(nsImage: image).resizable()
                                } else {
                                    Image(systemName: "app.fill").resizable().foregroundStyle(.secondary)
                                }
                            }
                            .frame(width: 44, height: 44)
                            Text(app.label).font(.caption).lineLimit(2).multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .help("Open \(app.label) on the phone")
                }
            }
            .padding(12)
        }
        .overlay {
            if model.screen.apps.isEmpty {
                Text(model.state == .connected ? "Loading apps…" : "Phone not connected").foregroundStyle(.secondary)
            }
        }
        .searchable(text: $search, placement: .toolbar)
        .navigationSubtitle("Click an app to open it on your phone")
        .frame(minWidth: 460, minHeight: 380)
        .onAppear { if model.state == .connected { model.screen.requestApps() } }
    }
}
