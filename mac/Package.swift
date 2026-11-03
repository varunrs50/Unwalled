// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Unwalled",
    platforms: [.macOS(.v15)],
    targets: [
        .executableTarget(
            name: "Unwalled",
            path: "Sources/Unwalled",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
