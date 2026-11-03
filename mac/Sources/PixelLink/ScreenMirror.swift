import AppKit
import AVFoundation
import Network
import Observation

struct PhoneApp: Identifiable, Equatable {
    let id: String          // package name
    let label: String
    let icon: Data?
}

/// Native screen mirroring (M6): the phone streams its screen as H.264 over a pinned TLS
/// connection, the Mac decodes it in hardware and sends clicks and keys back as touch input.
/// Nothing runs unless the window is open. See PROTOCOL.md, "Screen mirroring".
@MainActor
@Observable
final class ScreenMirrorController {
    enum State: Equatable {
        case idle
        /// Waiting for the user to confirm on the phone.
        case waiting
        case running
        case failed(String)
    }

    /// The phone holds an approved session, so the screen opens without asking on the phone.
    private(set) var ready = false

    private(set) var state: State = .idle
    /// The phone's Accessibility service is on, so clicks and keys can control it.
    private(set) var canControl = false
    private(set) var apps: [PhoneApp] = []
    /// Opens the Apps window; set by the menu bar icon, which can reach SwiftUI's openWindow.
    @ObservationIgnored var openApps: (() -> Void)?

    @ObservationIgnored var connection: (() -> PhoneConnection?)?
    @ObservationIgnored var phoneName: () -> String = { "Phone" }
    @ObservationIgnored private var window: ScreenWindow?
    @ObservationIgnored private var stream: VideoStream?

    func show() {
        if let window, state == .running {
            window.makeKeyAndOrderFront(nil)
            NSApp.activate()
            return
        }
        guard let conn = connection?() else { state = .failed("Phone isn't connected."); return }
        state = .waiting
        conn.send("screen.start")
    }

    /// Closes the window; the phone keeps its approved session for next time.
    func stop() {
        connection?()?.send("screen.stop")
        closeStream()
        state = .idle
    }

    /// Ends the phone's session, so the next Phone Screen asks on the phone again.
    func end() {
        connection?()?.send("screen.end")
        closeStream()
        ready = false
        state = .idle
    }

    func dismissError() { if case .failed = state { state = .idle } }

    /// `screen.state` from the phone.
    func phoneState(_ body: Message) {
        canControl = body["control"] as? Bool ?? canControl
        Log.info("Screen state \(body["state"] as? String ?? "?")\(body["error"].map { ": \($0)" } ?? "")")
        switch body["state"] as? String {
        case "waiting": state = .waiting
        case "running": ready = true; if state != .running { state = .running }
        case "paused": ready = true; closeStream(); state = .idle
        case "ended": ready = false; closeStream(); if state != .waiting { state = .idle }
        case "declined": ready = false; closeStream(); state = .failed("Screen sharing was cancelled on the phone.")
        case "error": closeStream(); state = .failed(body["error"] as? String ?? "Screen mirroring failed.")
        default: break
        }
    }

    func phoneDisconnected() {
        closeStream()
        ready = false
        if state != .idle { state = .idle }
    }

    /// A video connection from the paired phone.
    func accept(_ nw: NWConnection) {
        stream?.close()
        let stream = VideoStream(nw)
        stream.onInfo = { [weak self] width, height in self?.present(width: width, height: height) }
        stream.onClose = { [weak self] in
            guard let self, self.stream === stream else { return }
            self.stream = nil
            self.window?.close()
            if self.state == .running { self.state = .idle }
        }
        self.stream = stream
        stream.start()
        Log.info("Screen video connected")
    }

    private func present(width: Int, height: Int) {
        state = .running
        if window == nil {
            let window = ScreenWindow(title: phoneName(), controller: self)
            window.onClose = { [weak self] in self?.stop() }
            self.window = window
            stream?.layer = window.phoneView.videoLayer
        }
        window?.fit(width: width, height: height)
        // Frames can arrive before the window exists, and a still screen sends no more; ask for one.
        connection?()?.send("screen.keyframe")
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }

    private func closeStream() {
        stream?.close()
        stream = nil
        let window = self.window
        self.window = nil
        window?.onClose = nil
        window?.close()
    }

    // MARK: Input

    /// A live finger on the phone: `down`, any number of `move`s, then `up`.
    func touch(_ action: String, _ point: CGPoint) {
        connection?()?.send("input.touch", ["action": action, "x": Double(point.x), "y": Double(point.y)])
    }

    func key(_ name: String) { connection?()?.send("input.key", ["key": name]) }

    func type(_ text: String) { connection?()?.send("input.text", ["text": text]) }

    // MARK: Apps

    func requestApps() { connection?()?.send("apps.request") }

    func appsList(_ body: Message) {
        apps = (body["apps"] as? [Message] ?? []).compactMap { a in
            guard let id = a["package"] as? String else { return nil }
            return PhoneApp(id: id, label: a["label"] as? String ?? id, icon: (a["icon"] as? String).flatMap { Data(base64Encoded: $0) })
        }
    }

    /// Opens an app on the phone, and the phone's screen on the Mac if it isn't showing yet.
    func launch(_ app: PhoneApp) {
        connection?()?.send("apps.launch", ["package": app.id])
        show()
    }
}

/// Reads the phone's video packets and feeds them to a display layer, off the main thread.
/// Packet: u32 length, u8 kind, u64 pts (µs), payload. Kinds: 0 info JSON, 1 SPS/PPS, 2 key frame, 3 frame.
final class VideoStream: @unchecked Sendable {
    private let nw: NWConnection
    private var format: CMVideoFormatDescription?
    private var closed = false
    /// Set on the main queue before frames matter; read on the connection's queue.
    var layer: AVSampleBufferDisplayLayer?
    var onInfo: (@MainActor (Int, Int) -> Void)?
    var onClose: (@MainActor () -> Void)?

    init(_ nw: NWConnection) { self.nw = nw }

    func start() {
        nw.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.close() }
            if case .cancelled = state { self?.close() }
        }
        readPacket()
    }

    func close() {
        guard !closed else { return }
        closed = true
        nw.cancel()
        DispatchQueue.main.async { self.onClose?() }
    }

    private func readPacket() {
        nw.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, _, error in
            guard let self, error == nil, let data, data.count == 4 else { self?.close(); return }
            let length = data.reduce(0) { ($0 << 8) | Int($1) }
            guard length >= 9, length <= 8 << 20 else { self.close(); return }
            self.nw.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] body, _, _, error in
                guard let self, error == nil, let body, body.count == length else { self?.close(); return }
                self.handle(kind: body[body.startIndex], payload: body.dropFirst(9))
                self.readPacket()
            }
        }
    }

    private func handle(kind: UInt8, payload: Data) {
        switch kind {
        case 0:
            guard let info = try? JSONSerialization.jsonObject(with: payload) as? Message,
                  let w = info["width"] as? Int, let h = info["height"] as? Int else { return }
            format = nil
            DispatchQueue.main.async {
                self.onInfo?(w, h)
            }
        case 1:
            let units = Self.nalUnits(payload)
            guard let sps = units.first(where: { $0.first.map { $0 & 0x1f } == 7 }),
                  let pps = units.first(where: { $0.first.map { $0 & 0x1f } == 8 }) else { return }
            format = Self.formatDescription(sps: sps, pps: pps)
            layer?.sampleBufferRenderer.flush()
        case 2, 3:
            guard let format, let sample = Self.sample(payload, format: format) else { return }
            if let layer {
                if layer.sampleBufferRenderer.status == .failed { layer.sampleBufferRenderer.flush() }
                layer.sampleBufferRenderer.enqueue(sample)
            }
        default:
            break
        }
    }

    /// Splits Annex-B data (00 00 01 / 00 00 00 01 start codes) into NAL units.
    static func nalUnits(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var units: [Data] = []
        var start: Int?
        var i = 0
        while i + 2 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                if let s = start {
                    var end = i
                    if end > s, bytes[end - 1] == 0 { end -= 1 }
                    units.append(Data(bytes[s..<end]))
                }
                i += 3
                start = i
            } else {
                i += 1
            }
        }
        if let s = start, s < bytes.count { units.append(Data(bytes[s...])) }
        return units
    }

    private static func formatDescription(sps: Data, pps: Data) -> CMVideoFormatDescription? {
        var format: CMVideoFormatDescription?
        sps.withUnsafeBytes { spsRaw in
            pps.withUnsafeBytes { ppsRaw in
                let pointers = [spsRaw.bindMemory(to: UInt8.self).baseAddress!, ppsRaw.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: nil, parameterSetCount: 2, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &format)
            }
        }
        return format
    }

    /// Annex-B frame → length-prefixed (AVCC) sample, shown as soon as it's decoded.
    private static func sample(_ data: Data, format: CMVideoFormatDescription) -> CMSampleBuffer? {
        var avcc = Data()
        for unit in nalUnits(data) {
            let type = unit.first.map { $0 & 0x1f } ?? 0
            if type == 7 || type == 8 { continue }
            var length = UInt32(unit.count).bigEndian
            avcc.append(Data(bytes: &length, count: 4))
            avcc.append(unit)
        }
        guard !avcc.isEmpty else { return nil }
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: avcc.count,
                                                 blockAllocator: nil, customBlockSource: nil, offsetToData: 0,
                                                 dataLength: avcc.count, flags: 0, blockBufferOut: &block) == noErr,
              let block else { return nil }
        avcc.withUnsafeBytes { _ = CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count) }
        var sample: CMSampleBuffer?
        var size = avcc.count
        guard CMSampleBufferCreateReady(allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: 1,
                                        sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1,
                                        sampleSizeArray: &size, sampleBufferOut: &sample) == noErr, let sample else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) as? [NSMutableDictionary] {
            attachments.first?[kCMSampleAttachmentKey_DisplayImmediately] = true
        }
        return sample
    }
}

/// The mirroring window: the phone's screen, with Back / Home / Recents in the toolbar.
final class ScreenWindow: NSWindow, NSWindowDelegate, NSToolbarDelegate {
    let phoneView: ScreenView
    var onClose: (() -> Void)?
    private weak var controller: ScreenMirrorController?

    init(title: String, controller: ScreenMirrorController) {
        self.controller = controller
        phoneView = ScreenView(controller: controller)
        super.init(contentRect: NSRect(x: 0, y: 0, width: 360, height: 800),
                   styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        self.title = title
        contentView = phoneView
        isReleasedWhenClosed = false
        delegate = self
        let toolbar = NSToolbar(identifier: "screen")
        toolbar.delegate = self
        toolbar.displayMode = .iconOnly
        self.toolbar = toolbar
        toolbarStyle = .unifiedCompact
        center()
    }

    /// Sizes the window to the phone's screen shape, keeping its current height.
    func fit(width: Int, height: Int) {
        guard width > 0, height > 0 else { return }
        let aspect = NSSize(width: width, height: height)
        contentAspectRatio = aspect
        let maxHeight = (screen ?? NSScreen.main)?.visibleFrame.height ?? 900
        let h = min(max(contentLayoutRect.height, 500), maxHeight - 80)
        setContentSize(NSSize(width: (h * aspect.width / aspect.height).rounded(), height: h))
    }

    func windowWillClose(_ notification: Notification) { onClose?() }

    // MARK: Toolbar

    private static let items: [(NSToolbarItem.Identifier, String, String, String)] = [
        (.init("back"), "Back", "chevron.backward", "back"),
        (.init("home"), "Home", "circle", "home"),
        (.init("recents"), "Recent Apps", "square.on.square", "recents"),
        (.init("notifications"), "Notifications", "bell", "notifications"),
        (.init("apps"), "Apps", "square.grid.2x2", "apps"),
    ]

    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        [.flexibleSpace] + Self.items.map(\.0) + [.flexibleSpace]
    }

    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        toolbarDefaultItemIdentifiers(toolbar)
    }

    func toolbar(_ toolbar: NSToolbar, itemForItemIdentifier id: NSToolbarItem.Identifier, willBeInsertedIntoToolbar flag: Bool) -> NSToolbarItem? {
        guard let (_, label, symbol, _) = Self.items.first(where: { $0.0 == id }) else { return nil }
        let item = NSToolbarItem(itemIdentifier: id)
        item.label = label
        item.toolTip = label
        item.image = NSImage(systemSymbolName: symbol, accessibilityDescription: label)
        item.isBordered = true
        item.target = self
        item.action = #selector(toolbarKey(_:))
        return item
    }

    @objc private func toolbarKey(_ sender: NSToolbarItem) {
        guard let key = Self.items.first(where: { $0.0 == sender.itemIdentifier })?.3 else { return }
        MainActor.assumeIsolated {
            if key == "apps" { controller?.openApps?() } else { controller?.key(key) }
        }
    }
}

/// Shows the video and turns mouse and keyboard into touch input.
final class ScreenView: NSView {
    let videoLayer = AVSampleBufferDisplayLayer()
    private weak var controller: ScreenMirrorController?
    /// Where the phone's finger is while scrolling, and when a move was last sent.
    private var scrollPoint: CGPoint?
    private var lastMove = Date.distantPast
    private var wheelTimer: Timer?

    init(controller: ScreenMirrorController) {
        self.controller = controller
        super.init(frame: .zero)
        wantsLayer = true
        layer = CALayer()
        layer?.backgroundColor = NSColor.black.cgColor
        videoLayer.videoGravity = .resizeAspect
        videoLayer.frame = bounds
        videoLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        layer?.addSublayer(videoLayer)
    }

    required init?(coder: NSCoder) { fatalError() }

    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    /// The point as a fraction of the phone's screen (the window keeps the phone's shape).
    private func normalized(_ event: NSEvent) -> CGPoint {
        let p = convert(event.locationInWindow, from: nil)
        return CGPoint(x: min(max(p.x / bounds.width, 0), 1), y: min(max(1 - p.y / bounds.height, 0), 1))
    }

    // Clicks and drags: the finger goes down, follows the mouse, and lifts with the button,
    // so holding is a long press and dragging scrolls or moves things as it happens.

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        send("down", normalized(event))
    }

    override func mouseDragged(with event: NSEvent) {
        sendMove(normalized(event))
    }

    override func mouseUp(with event: NSEvent) {
        send("up", normalized(event))
    }

    override func rightMouseDown(with event: NSEvent) {
        MainActor.assumeIsolated { controller?.key("back") }
    }

    // Scrolling: a trackpad swipe drives the finger 1:1 and lifts when your fingers do, so Android
    // adds its own fling; momentum events are ignored. A mouse wheel lifts after a short pause.

    override func scrollWheel(with event: NSEvent) {
        if !event.momentumPhase.isEmpty { return }
        let precise = event.hasPreciseScrollingDeltas
        if event.phase.contains(.began) || (event.phase.isEmpty && scrollPoint == nil) {
            if let point = scrollPoint { send("up", point) }
            scrollPoint = normalized(event)
            send("down", scrollPoint!)
        }
        guard var point = scrollPoint else { return }
        let scale: CGFloat = precise ? 1 : 16
        point.x += event.scrollingDeltaX * scale / bounds.width
        point.y += event.scrollingDeltaY * scale / bounds.height
        // At the edge of the screen, lift and start again from where the scroll began.
        if point.y < 0.02 || point.y > 0.98 || point.x < 0.02 || point.x > 0.98 {
            let start = normalized(event)
            send("up", scrollPoint!)
            point = start
            send("down", start)
        }
        scrollPoint = point
        sendMove(point)

        if event.phase.contains(.ended) || event.phase.contains(.cancelled) {
            endScroll()
        } else if event.phase.isEmpty {
            wheelTimer?.invalidate()
            wheelTimer = Timer.scheduledTimer(withTimeInterval: 0.15, repeats: false) { [weak self] _ in
                MainActor.assumeIsolated { self?.endScroll() }
            }
        }
    }

    private func endScroll() {
        guard let point = scrollPoint else { return }
        send("up", point)
        scrollPoint = nil
    }

    /// Moves are capped at about 60 a second; the phone merges any that arrive faster.
    private func sendMove(_ point: CGPoint) {
        guard Date().timeIntervalSince(lastMove) >= 0.016 else { return }
        lastMove = Date()
        send("move", point)
    }

    private func send(_ action: String, _ point: CGPoint) {
        MainActor.assumeIsolated { controller?.touch(action, point) }
    }

    override func keyDown(with event: NSEvent) {
        MainActor.assumeIsolated {
            if event.modifierFlags.contains(.command) {
                if event.charactersIgnoringModifiers == "v", let text = NSPasteboard.general.string(forType: .string) {
                    controller?.type(text)
                }
                return
            }
            switch event.keyCode {
            case 53: controller?.key("back")          // Esc
            case 51: controller?.key("backspace")     // Delete
            case 36, 76: controller?.key("enter")     // Return
            default:
                if let text = event.characters, !text.isEmpty, text.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) {
                    controller?.type(text)
                }
            }
        }
    }
}
