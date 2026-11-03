import Foundation
import Security

/// The Mac's long-term TLS identity: an EC P-256 key and self-signed certificate.
/// Generated once with the system's openssl and kept in Application Support.
struct Identity {
    let secIdentity: SecIdentity
    let fingerprint: String

    enum IdentityError: Error {
        case generationFailed(String)
        case importFailed(OSStatus)
    }

    static func loadOrCreate() throws -> Identity {
        let p12 = AppPaths.support.appendingPathComponent("identity.p12")
        let pass = AppPaths.support.appendingPathComponent("identity.pass")
        if !FileManager.default.fileExists(atPath: p12.path) {
            try generate(p12: p12, pass: pass)
        }

        let password = try String(contentsOf: pass, encoding: .utf8)
        let options: [String: Any] = [
            kSecImportExportPassphrase as String: password,
            kSecImportToMemoryOnly as String: true,
        ]
        var items: CFArray?
        let status = SecPKCS12Import(try Data(contentsOf: p12) as CFData, options as CFDictionary, &items)
        guard status == errSecSuccess,
              let entry = (items as? [[String: Any]])?.first,
              let identityRef = entry[kSecImportItemIdentity as String]
        else { throw IdentityError.importFailed(status) }

        let identity = identityRef as! SecIdentity
        var cert: SecCertificate?
        SecIdentityCopyCertificate(identity, &cert)
        let der = SecCertificateCopyData(cert!) as Data
        return Identity(secIdentity: identity, fingerprint: sha256Hex(der))
    }

    private static func generate(p12: URL, pass: URL) throws {
        let dir = p12.deletingLastPathComponent()
        let key = dir.appendingPathComponent("identity.key")
        let crt = dir.appendingPathComponent("identity.crt")
        let ecKey = dir.appendingPathComponent("identity.ec")
        defer {
            try? FileManager.default.removeItem(at: ecKey)
            try? FileManager.default.removeItem(at: key)
            try? FileManager.default.removeItem(at: crt)
        }

        try randomToken(bytes: 24).write(to: pass, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: pass.path)

        // Security's PKCS#12 importer needs a named-curve key in PKCS#8 form.
        try openssl(["ecparam", "-name", "prime256v1", "-genkey", "-param_enc", "named_curve",
                     "-noout", "-out", ecKey.path])
        try openssl(["pkcs8", "-topk8", "-nocrypt", "-in", ecKey.path, "-out", key.path])
        try openssl(["req", "-new", "-x509", "-key", key.path, "-out", crt.path, "-days", "3650",
                     "-sha256", "-subj", "/CN=Unwalled Mac"])
        try openssl(["pkcs12", "-export", "-inkey", key.path, "-in", crt.path, "-out", p12.path,
                     "-passout", "file:\(pass.path)",
                     "-keypbe", "PBE-SHA1-3DES", "-certpbe", "PBE-SHA1-3DES", "-macalg", "sha1"])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: p12.path)
        Log.info("Generated new Mac identity")
    }

    private static func openssl(_ args: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        process.arguments = args
        let errPipe = Pipe()
        process.standardError = errPipe
        process.standardOutput = FileHandle.nullDevice
        process.standardInput = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let err = String(data: errPipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
            throw IdentityError.generationFailed(err)
        }
    }
}
