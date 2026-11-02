import AppKit
import CryptoKit
import Observation
import UserNotifications

struct NowPlaying: Equatable {
    var app: String
    var title: String
    var artist: String
    var playing: Bool
    var art: Data?
}

struct Transfer: Identifiable, Equatable {
    enum Direction { case toPhone, fromPhone }
    enum State: Equatable { case active, done, failed(String) }

    let id: String
    let name: String
    let size: Int64
    let direction: Direction
    var bytes: Int64 = 0
    var state: State = .active

    var progress: Double { size > 0 ? min(1, Double(bytes) / Double(size)) : 0 }
}

struct PhonePhoto: Identifiable, Equatable {
    let id: Int64
    let name: String
    let time: Date
    let size: Int64
    let thumb: Data
}

/// Find my phone, now playing, shared clipboard, file transfer and recent photos (M5).
@MainActor
@Observable
final class ExtrasController {
    private(set) var ringing = false
    private(set) var nowPlaying: NowPlaying?
    private(set) var transfers: [Transfer] = []
    private(set) var photos: [PhonePhoto] = []
    var clipboardSync = UserDefaults.standard.object(forKey: "clipboardSync") as? Bool ?? true {
        didSet { UserDefaults.standard.set(clipboardSync, forKey: "clipboardSync") }
    }

    /// The current phone connection; set by AppModel.
    @ObservationIgnored var connection: (() -> PhoneConnection?)?

    @ObservationIgnored private var lastClipText: String?
    @ObservationIgnored private var lastChangeCount = NSPasteboard.general.changeCount
    @ObservationIgnored private var clipTimer: Timer?
    @ObservationIgnored private var incoming: [String: Incoming] = [:]
    /// Photos to open as soon as they finish downloading.
    @ObservationIgnored private var openWhenDone: Set<String> = []

    private final class Incoming {
        let name: String
        let size: Int64
        let temp: URL
        let handle: FileHandle
        var hasher = SHA256()
        var received: Int64 = 0
        var nextSeq = 0

        init(name: String, size: Int64, temp: URL, handle: FileHandle) {
            self.name = name
            self.size = size
            self.temp = temp
            self.handle = handle
        }
    }

    static let chunkBytes = 192 * 1024

    init() {
        clipTimer = Timer.scheduledTimer(withTimeInterval: 0.6, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkClipboard() }
        }
    }

    private func send(_ type: String, _ body: Message = [:]) {
        connection?()?.send(type, body)
    }

    // MARK: Find my phone

    func toggleRing() { send(ringing ? "phone.ring.stop" : "phone.ring") }

    func ringState(_ body: Message) { ringing = body["ringing"] as? Bool ?? false }

    // MARK: Now playing

    func mediaState(_ body: Message) {
        guard body["active"] as? Bool == true else { nowPlaying = nil; return }
        let art = (body["art"] as? String).flatMap { Data(base64Encoded: $0) } ?? nowPlaying?.art
        nowPlaying = NowPlaying(app: body["app"] as? String ?? "", title: body["title"] as? String ?? "",
                                artist: body["artist"] as? String ?? "", playing: body["playing"] as? Bool ?? false, art: art)
    }

    func media(_ action: String) { send("media.control", ["action": action]) }

    // MARK: Clipboard

    /// Mac → phone: pushes new clipboard text. Skips text the phone just sent us and anything
    /// password managers mark as concealed.
    private func checkClipboard() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastChangeCount else { return }
        lastChangeCount = pb.changeCount
        guard clipboardSync, let text = pb.string(forType: .string), !text.isEmpty, text != lastClipText else { return }
        let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
        let transient = NSPasteboard.PasteboardType("org.nspasteboard.TransientType")
        guard pb.types?.contains(where: { $0 == concealed || $0 == transient }) != true else { return }
        lastClipText = text
        send("clip.set", ["text": String(text.prefix(200_000))])
    }

    func clipFromPhone(_ body: Message) {
        guard clipboardSync, let text = body["text"] as? String else { return }
        lastClipText = text
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        lastChangeCount = pb.changeCount
        Log.info("Clipboard from phone (\(text.count) characters)")
    }

    // MARK: Files: Mac → phone

    func chooseFilesToSend() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send to Phone"
        NSApp.activate()
        guard panel.runModal() == .OK else { return }
        panel.urls.forEach(sendFile)
    }

    /// Files in iCloud Drive or other cloud folders may be placeholders with no data on disk, so the
    /// file is read through a file coordinator (which downloads it) into a private copy first.
    func sendFile(_ url: URL) {
        guard connection?() != nil else { return }
        let id = "mac-\(UUID().uuidString)"
        let name = url.lastPathComponent
        transfers.insert(Transfer(id: id, name: name, size: 0, direction: .toPhone), at: 0)
        DispatchQueue.global(qos: .userInitiated).async {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pixellink-out-\(UUID().uuidString)")
            var copy: URL?
            var error: NSError?
            try? FileManager.default.startDownloadingUbiquitousItem(at: url)
            NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &error) { readable in
                // Read the bytes rather than copying the file: a copy can clone the empty placeholder.
                let target = dir.appendingPathComponent(name)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                guard FileManager.default.createFile(atPath: target.path, contents: nil),
                      let input = try? FileHandle(forReadingFrom: readable),
                      let output = try? FileHandle(forWritingTo: target) else { return }
                defer { try? input.close(); try? output.close() }
                while let data = try? input.read(upToCount: 4 << 20), !data.isEmpty { output.write(data) }
                copy = target
            }
            DispatchQueue.main.async {
                let size = copy.flatMap { try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize } ?? 0
                guard let copy else {
                    try? FileManager.default.removeItem(at: dir)
                    Log.info("Couldn't read \(name): \(error?.localizedDescription ?? "unreadable")")
                    self.finish(id, error: "Couldn't read the file")
                    return
                }
                self.update(id) { $0 = Transfer(id: id, name: name, size: Int64(size), direction: .toPhone) }
                self.stream(copy, id: id, size: size, original: url) { try? FileManager.default.removeItem(at: dir) }
            }
        }
    }

    private func stream(_ url: URL, id: String, size: Int, original: URL, cleanup: @escaping () -> Void) {
        guard let conn = connection?(), let handle = try? FileHandle(forReadingFrom: url) else {
            cleanup()
            finish(id, error: "Connection lost")
            return
        }
        let mime = (try? original.resourceValues(forKeys: [.contentTypeKey]))?.contentType?.preferredMIMEType ?? "application/octet-stream"
        conn.send("file.offer", ["id": id, "name": url.lastPathComponent, "size": size, "mime": mime])
        Log.info("Sending \(url.lastPathComponent) (\(size) bytes) to phone")

        // Read and send one chunk at a time, waiting for each to leave before reading the next.
        var hasher = SHA256()
        var seq = 0
        var sentBytes: Int64 = 0
        func next() {
            let data = (try? handle.read(upToCount: Self.chunkBytes)) ?? Data()
            if data.isEmpty {
                try? handle.close()
                cleanup()
                let sha = hasher.finalize().map { String(format: "%02x", $0) }.joined()
                conn.send("file.done", ["id": id, "sha256": sha])
                return
            }
            hasher.update(data: data)
            sentBytes += Int64(data.count)
            let progress = sentBytes
            conn.send("file.chunk", ["id": id, "seq": seq, "data": data.base64EncodedString()]) { ok in
                DispatchQueue.main.async {
                    guard ok else { self.finish(id, error: "Connection lost"); try? handle.close(); cleanup(); return }
                    self.update(id) { $0.bytes = progress }
                    next()
                }
            }
            seq += 1
        }
        next()
    }

    // MARK: Files: phone → Mac

    func offer(_ body: Message) {
        guard let id = body["id"] as? String, let name = body["name"] as? String else { return }
        let size = (body["size"] as? NSNumber)?.int64Value ?? 0
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent("pixellink-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: temp.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: temp) else { return }
        let safeName = name.replacingOccurrences(of: "/", with: "_")
        incoming[id] = Incoming(name: safeName, size: size, temp: temp, handle: handle)
        transfers.insert(Transfer(id: id, name: safeName, size: size, direction: .fromPhone), at: 0)
    }

    func chunk(_ body: Message) {
        guard let id = body["id"] as? String, let file = incoming[id] else { return }
        guard body["seq"] as? Int == file.nextSeq, let data = (body["data"] as? String).flatMap({ Data(base64Encoded: $0) }) else {
            reject(id, "Chunks arrived out of order")
            return
        }
        file.handle.write(data)
        file.hasher.update(data: data)
        file.received += Int64(data.count)
        file.nextSeq += 1
        update(id) { $0.bytes = file.received }
    }

    func done(_ body: Message) {
        guard let id = body["id"] as? String, let file = incoming.removeValue(forKey: id) else { return }
        try? file.handle.close()
        let sha = file.hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard file.received == file.size, sha == body["sha256"] as? String else {
            try? FileManager.default.removeItem(at: file.temp)
            reject(id, "File arrived damaged")
            return
        }
        do {
            let destination = Self.uniqueDownloadURL(for: file.name)
            try FileManager.default.moveItem(at: file.temp, to: destination)
            connection?()?.send("file.result", ["id": id, "ok": true])
            finish(id, error: nil)
            Log.info("Saved \(destination.lastPathComponent) to Downloads")
            if openWhenDone.remove(id) != nil {
                NSWorkspace.shared.open(destination)
            } else {
                notifyReceived(destination)
            }
        } catch {
            reject(id, "Couldn't save: \(error.localizedDescription)")
        }
    }

    func cancel(_ body: Message) {
        guard let id = body["id"] as? String else { return }
        if let file = incoming.removeValue(forKey: id) {
            try? file.handle.close()
            try? FileManager.default.removeItem(at: file.temp)
        }
        finish(id, error: "Cancelled")
    }

    func result(_ body: Message) {
        guard let id = body["id"] as? String else { return }
        finish(id, error: body["ok"] as? Bool == true ? nil : body["error"] as? String ?? "Failed")
    }

    func phoneDisconnected() {
        for (id, file) in incoming {
            try? file.handle.close()
            try? FileManager.default.removeItem(at: file.temp)
            finish(id, error: "Connection lost")
        }
        incoming = [:]
        ringing = false
        nowPlaying = nil
    }

    private func reject(_ id: String, _ error: String) {
        if let file = incoming.removeValue(forKey: id) {
            try? file.handle.close()
            try? FileManager.default.removeItem(at: file.temp)
        }
        connection?()?.send("file.result", ["id": id, "ok": false, "error": error])
        finish(id, error: error)
    }

    private func finish(_ id: String, error: String?) {
        update(id) { $0.state = error.map(Transfer.State.failed) ?? .done }
        // Keep finished transfers visible briefly, then tidy them away.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(error == nil ? 6 : 15))
            self?.transfers.removeAll { $0.id == id && $0.state != .active }
        }
    }

    private func update(_ id: String, _ change: (inout Transfer) -> Void) {
        guard let index = transfers.firstIndex(where: { $0.id == id }) else { return }
        change(&transfers[index])
    }

    private func notifyReceived(_ url: URL) {
        let content = UNMutableNotificationContent()
        content.title = "Received from phone"
        content.body = url.lastPathComponent
        content.userInfo = ["reveal": url.path]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "file-\(UUID().uuidString)", content: content, trigger: nil))
    }

    static func uniqueDownloadURL(for name: String) -> URL {
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        var url = downloads.appendingPathComponent(name)
        let base = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        var n = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = downloads.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        return url
    }

    // MARK: Photos

    static let photosPage = 60

    /// Albums (folders) on the phone with their photo counts; "" in `album` means all photos.
    private(set) var albums: [(name: String, count: Int)] = []
    private(set) var photoTotal = 0
    private(set) var loadingPhotos = false
    var album = "" { didSet { if album != oldValue { refreshPhotos() } } }

    var morePhotos: Bool { photos.count < photoTotal }

    func refreshPhotos() { requestPhotos(offset: 0) }

    func loadMorePhotos() {
        guard morePhotos, !loadingPhotos else { return }
        requestPhotos(offset: photos.count)
    }

    private func requestPhotos(offset: Int) {
        loadingPhotos = true
        send("photos.request", ["limit": Self.photosPage, "offset": offset, "album": album])
    }

    func photosList(_ body: Message) {
        loadingPhotos = false
        // Ignore pages for an album the user has since switched away from.
        guard (body["album"] as? String ?? "") == album else { return }
        let page: [PhonePhoto] = (body["photos"] as? [Message] ?? []).compactMap { p in
            guard let id = (p["id"] as? NSNumber)?.int64Value,
                  let thumb = (p["thumb"] as? String).flatMap({ Data(base64Encoded: $0) }) else { return nil }
            return PhonePhoto(id: id, name: p["name"] as? String ?? "Photo", time: Date(timeIntervalSince1970: ((p["time"] as? NSNumber)?.doubleValue ?? 0) / 1000),
                              size: (p["size"] as? NSNumber)?.int64Value ?? 0, thumb: thumb)
        }
        let offset = body["offset"] as? Int ?? 0
        photos = offset == 0 ? page : photos + page.filter { p in !photos.contains { $0.id == p.id } }
        photoTotal = body["total"] as? Int ?? photos.count
        if let list = body["albums"] as? [Message] {
            albums = list.compactMap { a in (a["name"] as? String).map { ($0, a["count"] as? Int ?? 0) } }
        }
        Log.info("Photos: showing \(photos.count) of \(photoTotal) in \(album.isEmpty ? "all" : album), \(albums.count) albums")
    }

    /// Downloads a photo to ~/Downloads and opens it.
    func download(_ photo: PhonePhoto) {
        openWhenDone.insert("photo-\(photo.id)")
        send("photos.get", ["id": photo.id])
    }
}
