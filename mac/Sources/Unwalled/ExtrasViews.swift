import SwiftUI

struct NowPlayingRow: View {
    @Environment(AppModel.self) private var model
    let playing: NowPlaying

    var body: some View {
        HStack(spacing: 10) {
            Group {
                if let art = playing.art, let image = NSImage(data: art) {
                    Image(nsImage: image).resizable().scaledToFill()
                } else {
                    Image(systemName: "music.note").foregroundStyle(.secondary)
                }
            }
            .frame(width: 36, height: 36)
            .background(.quaternary)
            .clipShape(RoundedRectangle(cornerRadius: 6))

            VStack(alignment: .leading, spacing: 1) {
                Text(playing.title.isEmpty ? playing.app : playing.title).font(.caption.weight(.semibold)).lineLimit(1)
                Text(playing.artist.isEmpty ? playing.app : playing.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            Button { model.extras.media("previous") } label: { Image(systemName: "backward.fill") }
            Button { model.extras.media("toggle") } label: { Image(systemName: playing.playing ? "pause.fill" : "play.fill") }
            Button { model.extras.media("next") } label: { Image(systemName: "forward.fill") }
        }
    }
}

struct TransferRow: View {
    let transfer: Transfer

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Image(systemName: transfer.direction == .toPhone ? "arrow.up.circle" : "arrow.down.circle")
                Text(transfer.name).lineLimit(1).truncationMode(.middle)
                Spacer()
                switch transfer.state {
                case .active: Text("\(Int(transfer.progress * 100))%").monospacedDigit()
                case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                case .failed: Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
            }
            .font(.caption)
            if transfer.state == .active {
                ProgressView(value: transfer.progress).controlSize(.small)
            } else if case .failed(let error) = transfer.state {
                Text(error).font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

/// Recent photos on the phone. Click one to download it to ~/Downloads and open it.
struct PhotosView: View {
    @Environment(AppModel.self) private var model

    private let columns = [GridItem(.adaptive(minimum: 120, maximum: 160), spacing: 6)]

    var body: some View {
        let photos = model.extras.photos
        ScrollView {
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(photos) { photo in
                    Button {
                        model.extras.download(photo)
                    } label: {
                        if let image = NSImage(data: photo.thumb) {
                            Image(nsImage: image)
                                .resizable()
                                .scaledToFill()
                                .frame(minWidth: 0, maxWidth: .infinity)
                                .frame(height: 130)
                                .clipped()
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                    }
                    .buttonStyle(.plain)
                    .help("\(photo.name) · \(photo.time.formatted(date: .abbreviated, time: .shortened)) · click to download")
                    .onAppear { if photo.id == photos.last?.id { model.extras.loadMorePhotos() } }
                }
            }
            .padding(10)
            if model.extras.loadingPhotos && !photos.isEmpty {
                ProgressView().controlSize(.small).padding(.bottom, 10)
            }
        }
        .overlay {
            if photos.isEmpty {
                Text(model.state == .connected ? "Loading photos… (allow photos in Unwalled on the phone if this stays empty)" : "Phone not connected")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding()
            }
        }
        .navigationSubtitle(model.extras.photoTotal > 0 ? "\(model.extras.photoTotal) photos" : "")
        .toolbar {
            Picker("Album", selection: Bindable(model.extras).album) {
                Text("All Photos").tag("")
                ForEach(model.extras.albums, id: \.name) { album in
                    Text("\(album.name) (\(album.count))").tag(album.name)
                }
            }
            .disabled(model.state != .connected)
            Button { model.extras.refreshPhotos() } label: { Label("Refresh", systemImage: "arrow.clockwise") }
                .disabled(model.state != .connected)
        }
        .frame(minWidth: 520, minHeight: 420)
        .onAppear { if model.state == .connected { model.extras.refreshPhotos() } }
    }
}

/// Insert from Phone: photo or document scan from the phone, pasted into the app you were in.
struct InsertMenuSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let extras = model.extras
        Button("Take Photo with Phone") { extras.startCapture("photo") }
            .disabled(model.state != .connected)
        Button("Scan Documents with Phone") { extras.startCapture("scan") }
            .disabled(model.state != .connected)
        switch extras.capture {
        case .waiting(let mode):
            HStack(alignment: .top, spacing: 6) {
                ProgressView().controlSize(.small)
                Text(mode == "scan" ? "Scan on your phone; it'll be pasted where you were" : "Take the photo on your phone; it'll be pasted where you were")
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button { extras.cancelCapture() } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }
            }
        case .failed(let error):
            Text(error).font(.caption).foregroundStyle(.orange)
        case nil:
            EmptyView()
        }
    }
}
