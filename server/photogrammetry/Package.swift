// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "photogrammetry",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "photogrammetry", path: "Sources/photogrammetry")
    ]
)
