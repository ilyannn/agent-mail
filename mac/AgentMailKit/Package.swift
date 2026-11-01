// swift-tools-version: 6.2
// AgentMailKit: everything in the Mac app that doesn't need AppKit. Models and
// decoding of the CLI's JSON, running the CLI, store watching, new-message
// diffing, quoting and Markdown to HTML. `swift test` runs the tests.
import PackageDescription

let package = Package(
    name: "AgentMailKit",
    platforms: [.macOS(.v26)],
    products: [
        .library(name: "AgentMailKit", targets: ["AgentMailKit"])
    ],
    dependencies: [
        .package(url: "https://github.com/swiftlang/swift-markdown.git", exact: "0.9.0")
    ],
    targets: [
        .target(
            name: "AgentMailKit",
            dependencies: [.product(name: "Markdown", package: "swift-markdown")]
        ),
        .testTarget(
            name: "AgentMailKitTests",
            dependencies: ["AgentMailKit"],
            resources: [.copy("Fixtures")]
        ),
    ],
    swiftLanguageModes: [.v6]
)
