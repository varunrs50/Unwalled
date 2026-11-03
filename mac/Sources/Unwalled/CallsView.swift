import SwiftUI

/// The Calls window: dial a number, browse recent calls, search contacts. Every call is placed by the phone.
struct CallsView: View {
    @Environment(AppModel.self) private var model
    @State private var tab = Tab.recents
    @State private var search = ""
    @State private var dialNumber = ""

    enum Tab: String, CaseIterable { case recents = "Recents", contacts = "Contacts" }

    private var calls: CallController { model.calls }
    private var connected: Bool { model.state == .connected }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                TextField("Enter a number", text: $dialNumber)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { dial(dialNumber) }
                Button {
                    dial(dialNumber)
                } label: {
                    Label("Call", systemImage: "phone.fill")
                }
                .disabled(!connected || dialNumber.isEmpty)
            }
            .padding(12)

            Picker("", selection: $tab) {
                ForEach(Tab.allCases, id: \.self) { Text($0.rawValue) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding(.horizontal, 12)

            TextField("Search", text: $search)
                .textFieldStyle(.roundedBorder)
                .padding(12)

            switch tab {
            case .recents: recents
            case .contacts: contactList
            }

            if !connected {
                Text("Phone not connected: calling is unavailable.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(8)
            } else if let error = calls.lastError {
                Text(error).font(.caption).foregroundStyle(.red).padding(8)
            }
        }
        .frame(minWidth: 380, minHeight: 480)
        .onAppear { if connected { calls.refresh() } }
    }

    private var recents: some View {
        let entries = calls.log.filter { matches($0.name ?? calls.name(for: $0.number), $0.number) }
        return List(entries) { entry in
            HStack(spacing: 10) {
                Image(systemName: symbol(entry.type))
                    .foregroundStyle(isMissed(entry.type) ? .red : .secondary)
                    .frame(width: 18)
                VStack(alignment: .leading, spacing: 1) {
                    Text(entry.name ?? calls.name(for: entry.number) ?? entry.number)
                        .foregroundStyle(isMissed(entry.type) ? .red : .primary)
                    Text(detail(entry)).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text(entry.time, format: .relative(presentation: .named)).font(.caption).foregroundStyle(.secondary)
                callButton(entry.number)
            }
        }
        .overlay { if entries.isEmpty { emptyState(calls.log.isEmpty ? "No recent calls yet" : "No matches") } }
    }

    private var contactList: some View {
        let list = calls.contacts.filter { contact in matches(contact.name, contact.numbers.map(\.number).joined(separator: " ")) }
        return List(list) { contact in
            HStack(spacing: 10) {
                ContactAvatar(name: contact.name, photo: nil, size: 28)
                VStack(alignment: .leading, spacing: 1) {
                    Text(contact.name)
                    ForEach(contact.numbers, id: \.self) { number in
                        HStack {
                            Text("\(number.label) \(number.number)").font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            callButton(number.number)
                        }
                    }
                }
            }
        }
        .overlay { if list.isEmpty { emptyState(calls.contacts.isEmpty ? "Contacts appear once the phone shares them" : "No matches") } }
    }

    private func callButton(_ number: String) -> some View {
        Button {
            dial(number)
        } label: {
            Image(systemName: "phone.fill")
        }
        .buttonStyle(.borderless)
        .disabled(!connected)
        .help("Call \(number) from your phone")
    }

    private func emptyState(_ text: String) -> some View {
        Text(text).foregroundStyle(.secondary)
    }

    private func dial(_ number: String) {
        guard connected else { return }
        calls.dial(number)
    }

    private func matches(_ name: String?, _ number: String) -> Bool {
        let query = search.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return true }
        return (name?.localizedCaseInsensitiveContains(query) ?? false) || number.filter(\.isNumber).contains(query.filter(\.isNumber).isEmpty ? "\u{0}" : query.filter(\.isNumber))
    }

    private func symbol(_ type: String) -> String {
        switch type {
        case "outgoing": "phone.arrow.up.right"
        case "missed", "rejected": "phone.arrow.down.left"
        case "blocked": "nosign"
        case "voicemail": "recordingtape"
        default: "phone.arrow.down.left"
        }
    }

    private func isMissed(_ type: String) -> Bool { type == "missed" || type == "rejected" }

    private func detail(_ entry: CallLogEntry) -> String {
        let kind = switch entry.type {
        case "outgoing": "Outgoing"
        case "missed": "Missed"
        case "rejected": "Declined"
        case "blocked": "Blocked"
        case "voicemail": "Voicemail"
        default: "Incoming"
        }
        guard entry.duration > 0 else { return kind }
        let minutes = entry.duration / 60, seconds = entry.duration % 60
        return "\(kind) · \(minutes > 0 ? "\(minutes)m " : "")\(seconds)s"
    }
}
