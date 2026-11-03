import SwiftUI

/// The Messages window: SMS threads on the left, the conversation on the right. Texts go out from the phone's SIM.
struct MessagesView: View {
    @Environment(AppModel.self) private var model
    @State private var selection: Int64?
    @State private var search = ""
    @State private var composingNew = false

    private var store: MessagesStore { model.messages }
    private var connected: Bool { model.state == .connected }

    var body: some View {
        NavigationSplitView {
            threadList
                .navigationSplitViewColumnWidth(min: 240, ideal: 280)
        } detail: {
            if let thread = store.threads.first(where: { $0.id == selection }) {
                ConversationView(thread: thread)
                    .id(thread.id)
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "message").font(.largeTitle).foregroundStyle(.tertiary)
                    Text("Select a conversation").foregroundStyle(.secondary)
                }
            }
        }
        .toolbar {
            Button {
                composingNew = true
            } label: {
                Label("New Message", systemImage: "square.and.pencil")
            }
            .disabled(!connected)
            .help("New text message")
        }
        .sheet(isPresented: $composingNew) { NewMessageSheet() }
        .frame(minWidth: 720, minHeight: 480)
        .onAppear { if connected { store.refresh() } }
    }

    private var threadList: some View {
        let threads = store.threads.filter { thread in
            search.isEmpty || thread.title.localizedCaseInsensitiveContains(search)
                || thread.snippet.localizedCaseInsensitiveContains(search)
                || thread.addresses.contains { $0.contains(search) }
        }
        return VStack(spacing: 0) {
            List(threads, selection: $selection) { thread in
                ThreadRow(thread: thread).tag(thread.id)
            }
            .searchable(text: $search, placement: .sidebar)
            .overlay {
                if threads.isEmpty {
                    Text(store.threads.isEmpty ? "Text messages appear once the phone shares them" : "No matches")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding()
                }
            }
            Text("RCS chats from Google Messages aren't readable by other apps. They still arrive as notifications you can reply to.")
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .padding(10)
        }
    }
}

private struct ThreadRow: View {
    let thread: SmsThread

    var body: some View {
        HStack(spacing: 10) {
            ContactAvatar(name: thread.name, photo: nil, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(thread.title).fontWeight(thread.unread ? .semibold : .regular).lineLimit(1)
                    Spacer(minLength: 4)
                    Text(thread.time, format: .relative(presentation: .named)).font(.caption).foregroundStyle(.secondary)
                }
                // Empty when Android withholds the message from apps, e.g. a recent one-time code.
                Text(thread.snippet.isEmpty ? "No preview" : thread.snippet)
                    .font(.caption)
                    .italic(thread.snippet.isEmpty)
                    .foregroundStyle(thread.snippet.isEmpty ? .tertiary : .secondary)
                    .lineLimit(2)
            }
            if thread.unread {
                Circle().fill(.tint).frame(width: 8, height: 8)
            }
        }
        .padding(.vertical, 3)
    }
}

private struct ConversationView: View {
    @Environment(AppModel.self) private var model
    let thread: SmsThread
    @State private var draft = ""

    private var store: MessagesStore { model.messages }
    private var connected: Bool { model.state == .connected }

    var body: some View {
        let messages = store.messages[thread.id] ?? []
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(messages) { message in
                            Bubble(message: message).id(message.id)
                        }
                    }
                    .padding(14)
                }
                .onAppear { proxy.scrollTo(messages.last?.id, anchor: .bottom) }
                .onChange(of: messages.count) { proxy.scrollTo(messages.last?.id, anchor: .bottom) }
            }

            if let error = store.lastError {
                Text(error).font(.caption).foregroundStyle(.red).padding(.horizontal)
            }

            Divider()
            HStack(alignment: .bottom) {
                SimPicker()
                TextField(connected ? "Text message" : "Phone not connected", text: $draft, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(1...6)
                    .onSubmit(sendDraft)
                    .disabled(!connected)
                Button(action: sendDraft) {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .buttonStyle(.borderless)
                .disabled(!connected || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .keyboardShortcut(.return, modifiers: .command)
            }
            .padding(10)
        }
        .navigationTitle(thread.title)
        .navigationSubtitle(thread.name == nil ? "" : thread.addresses.joined(separator: ", "))
        .onAppear {
            store.markRead(thread.id)
            if connected { store.load(thread.id) }
        }
    }

    private func sendDraft() {
        guard let to = thread.addresses.first else { return }
        store.send(draft, to: to, thread: thread.id)
        draft = ""
    }
}

private struct Bubble: View {
    let message: SmsMessage

    var body: some View {
        HStack {
            if !message.incoming { Spacer(minLength: 60) }
            VStack(alignment: message.incoming ? .leading : .trailing, spacing: 2) {
                Text(message.body)
                    .textSelection(.enabled)
                    .padding(.horizontal, 11)
                    .padding(.vertical, 7)
                    .foregroundStyle(message.incoming ? Color.primary : Color.white)
                    .background(message.incoming ? AnyShapeStyle(.quaternary) : AnyShapeStyle(Color.accentColor),
                                in: RoundedRectangle(cornerRadius: 16))
                HStack(spacing: 4) {
                    Text(message.time, format: .dateTime.hour().minute())
                    if !message.incoming { Text(statusText) }
                }
                .font(.caption2)
                .foregroundStyle(message.status == .failed ? Color.red : Color.secondary)
            }
            if message.incoming { Spacer(minLength: 60) }
        }
    }

    private var statusText: String {
        switch message.status {
        case .sending: "· Sending…"
        case .sent: "· Sent"
        case .delivered: "· Delivered"
        case .failed: "· Not sent"
        case .received: ""
        }
    }
}

private struct NewMessageSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var to = ""
    @State private var body_ = ""

    var body: some View {
        let matches = to.count < 2 ? [] : model.calls.contacts.filter { $0.name.localizedCaseInsensitiveContains(to) }.prefix(5)
        VStack(alignment: .leading, spacing: 12) {
            Text("New Message").font(.headline)
            TextField("To: name or number", text: $to)
            ForEach(Array(matches)) { contact in
                ForEach(contact.numbers, id: \.self) { number in
                    Button("\(contact.name) · \(number.label) \(number.number)") { to = number.number }
                        .buttonStyle(.borderless)
                }
            }
            TextField("Message", text: $body_, axis: .vertical).lineLimit(3...8)
            HStack {
                SimPicker()
                Spacer()
                Button("Cancel") { dismiss() }
                Button("Send") {
                    model.messages.send(body_, to: to, thread: nil)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(to.filter(\.isNumber).count < 3 || body_.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .textFieldStyle(.roundedBorder)
        .padding(20)
        .frame(width: 380)
    }
}

/// Which SIM sends the text; only shown on phones with more than one.
struct SimPicker: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sims = model.status.sims
        if sims.count > 1 {
            @Bindable var store = model.messages
            // A SIM chosen earlier may no longer be in the phone.
            let selection = sims.contains { $0.id == store.sim } ? store.sim : nil
            Menu {
                Picker("Send from", selection: Binding(get: { selection }, set: { store.sim = $0 })) {
                    Text("Phone's default SIM").tag(Int?.none)
                    ForEach(sims, id: \.id) { Text($0.label).tag(Int?.some($0.id)) }
                }
                .pickerStyle(.inline)
            } label: {
                Label(sims.first { $0.id == selection }?.label ?? "Default SIM", systemImage: "simcard")
                    .labelStyle(.titleAndIcon)
                    .font(.caption)
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .help("Which SIM sends this text")
        }
    }
}
