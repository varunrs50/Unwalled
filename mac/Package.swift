// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PixelLink",
    platforms: [.macOS(.v15)],
    targets: [
        .executableTarget(
            name: "PixelLink",
            path: "Sources/PixelLink",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
