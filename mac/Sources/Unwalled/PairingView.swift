import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI

struct PairingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        VStack(spacing: 16) {
            if let pairing = model.pairing {
                if let request = pairing.request {
                    RequestView(name: request.name, model: request.model)
                } else {
                    CodeView(payload: pairing.payload, expires: pairing.expires)
                }
            } else {
                Image(systemName: model.paired == nil ? "qrcode" : "checkmark.circle.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(model.paired == nil ? Color.secondary : Color.green)
                Text(model.pairingMessage ?? "Pair your phone with this Mac.")
                    .multilineTextAlignment(.center)
                HStack {
                    if model.paired != nil {
                        Button("Done") { dismissWindow(id: "pairing") }.keyboardShortcut(.defaultAction)
                    } else {
                        Button("Show pairing code") { model.startPairing() }.keyboardShortcut(.defaultAction)
                    }
                }
            }
        }
        .padding(28)
        .frame(width: 380)
        .onDisappear { if model.pairing?.request == nil { model.cancelPairing() } }
    }
}

private struct CodeView: View {
    @Environment(AppModel.self) private var model
    let payload: String
    let expires: Date
    /// Shows a code that downloads the phone app instead, for phones that don't have it yet.
    @State private var showDownload = false

    /// Always the newest release's APK; release assets keep the same name every version.
    static let phoneAppURL = "https://github.com/varunrs50/Unwalled/releases/latest/download/Unwalled.apk"

    var body: some View {
        Picker("", selection: $showDownload) {
            Text("Pair").tag(false)
            Text("Get the phone app").tag(true)
        }
        .pickerStyle(.segmented)
        .labelsHidden()

        if showDownload {
            Text("Get Unwalled for Android").font(.title2.bold())
            Text("Scan this with your phone's **Camera** to download Unwalled, then open the download to install it. Come back to **Pair** when it's installed.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            qrCode(Self.phoneAppURL)
            Text("Needs Android 14 or later.")
                .font(.caption)
                .foregroundStyle(.secondary)
            Button("Pair") { showDownload = false }
        } else {
            Text("Pair your phone").font(.title2.bold())
            Text("Open Unwalled on your phone, tap **Scan pairing code**, and point it at this code.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            qrCode(payload)
            Text("Code expires \(expires, style: .relative)")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                Button("Copy code") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(payload, forType: .string)
                }
                Button("Cancel") { model.cancelPairing() }
            }
        }
    }

    @ViewBuilder private func qrCode(_ text: String) -> some View {
        if let image = qrImage(text) {
            Image(nsImage: image)
                .interpolation(.none)
                .resizable()
                .frame(width: 220, height: 220)
                .padding(10)
                .background(.white, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private func qrImage(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let rep = NSCIImageRep(ciImage: output)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}

private struct RequestView: View {
    @Environment(AppModel.self) private var model
    let name: String
    let model_: String

    init(name: String, model: String) {
        self.name = name
        self.model_ = model
    }

    var body: some View {
        Image(systemName: "iphone.gen3.radiowaves.left.and.right")
            .font(.system(size: 44))
            .foregroundStyle(.tint)
        Text("Allow \(name) to connect?").font(.title3.bold()).multilineTextAlignment(.center)
        Text("\(model_.isEmpty ? name : model_) will be able to send its notifications, messages and calls to this Mac.")
            .multilineTextAlignment(.center)
            .foregroundStyle(.secondary)
        HStack {
            Button("Don't Allow") { model.respondToPairing(allow: false) }
            Button("Allow") { model.respondToPairing(allow: true) }.keyboardShortcut(.defaultAction)
        }
    }
}
