import CryptoKit
import Foundation

enum AppPaths {
    /// ~/Library/Application Support/Unwalled, created on first use.
    /// Debug builds can point a second test copy elsewhere with UNWALLED_DATA_DIR.
    static let support: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = base.appendingPathComponent("Unwalled", isDirectory: true)
        #if DEBUG
        if let custom = ProcessInfo.processInfo.environment["UNWALLED_DATA_DIR"] {
            dir = URL(fileURLWithPath: custom, isDirectory: true)
        }
        #endif
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                 attributes: [.posixPermissions: 0o700])
        return dir
    }()
}

extension Sequence where Element == UInt8 {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

func sha256Hex(_ data: Data) -> String { SHA256.hash(data: data).hex }

func randomToken(bytes: Int = 16) -> String {
    var buf = [UInt8](repeating: 0, count: bytes)
    _ = SecRandomCopyBytes(kSecRandomDefault, bytes, &buf)
    return Data(buf).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

enum NetworkInfo {
    /// The Mac's IPv4 address on the local network, preferring Wi-Fi/Ethernet (en*).
    static func localIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var candidates: [(name: String, ip: String)] = []
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  (ifa.ifa_flags & UInt32(IFF_UP)) != 0, (ifa.ifa_flags & UInt32(IFF_LOOPBACK)) == 0
            else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            let ip = String(cString: host)
            if ip.hasPrefix("169.254.") { continue }
            candidates.append((String(cString: ifa.ifa_name), ip))
        }
        return (candidates.first { $0.name.hasPrefix("en") } ?? candidates.first)?.ip
    }
}

/// Logs to stdout and to ~/Library/Logs/Unwalled/Unwalled.log, which "Report a Problem…" points
/// people to. Nothing personal goes in: no message text, names, numbers or file names.
enum Log {
    static let file = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Logs/Unwalled/Unwalled.log")
    private static let queue = DispatchQueue(label: "app.unwalled.log")
    private static let maxBytes = 2 << 20
    private static let handle: FileHandle? = {
        let fm = FileManager.default
        try? fm.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Keep one previous log once this one grows past 2 MB.
        if let size = (try? fm.attributesOfItem(atPath: file.path))?[.size] as? Int, size > maxBytes {
            let old = file.deletingPathExtension().appendingPathExtension("old.log")
            try? fm.removeItem(at: old)
            try? fm.moveItem(at: file, to: old)
        }
        if !fm.fileExists(atPath: file.path) { fm.createFile(atPath: file.path, contents: nil) }
        let h = try? FileHandle(forWritingTo: file)
        _ = try? h?.seekToEnd()
        return h
    }()
    private static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    static func info(_ message: String) {
        print("[Unwalled] \(message)")
        fflush(stdout)
        let line = "\(stamp.string(from: Date())) \(message)\n"
        queue.async { handle?.write(Data(line.utf8)) }
    }
}
