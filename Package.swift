// swift-tools-version: 6.1
// Publit for Android: the iOS app's Swift sources (copied from elija-7580/Publit, see upstream/IOS_COMMIT),
// built for Android with Skip Fuse (https://skip.dev): Swift compiled natively with the official
// Swift SDK for Android, SwiftUI rendered as Jetpack Compose.
import PackageDescription

let package = Package(
    name: "publit-android",
    defaultLocalization: "en",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "Publit", type: .dynamic, targets: ["Publit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/skiptools/skip.git", from: "1.9.16"),
        .package(url: "https://github.com/skiptools/skip-fuse-ui.git", from: "1.0.0"),
        .package(url: "https://github.com/skiptools/skip-fuse.git", from: "1.0.0"),
        .package(url: "https://github.com/skiptools/skip-model.git", from: "1.0.0"),
    ],
    targets: [
        .target(name: "Publit", dependencies: [
            .product(name: "SkipFuseUI", package: "skip-fuse-ui"),
            .product(name: "SkipFuse", package: "skip-fuse"),
            .product(name: "SkipModel", package: "skip-model"),
        ], exclude: [
            "App/AppShortcuts.xcstrings",
            "App/InfoPlist.xcstrings",
            "App/PrivacyInfo.xcprivacy",
            "App/Assets.xcassets",
        ], resources: [.process("Resources")], swiftSettings: [.swiftLanguageMode(.v5)], plugins: [.plugin(name: "skipstone", package: "skip")]),
        .testTarget(name: "PublitTests", dependencies: [
            "Publit",
            .product(name: "SkipTest", package: "skip"),
        ], resources: [.copy("Fixtures")], swiftSettings: [.swiftLanguageMode(.v5)], plugins: [.plugin(name: "skipstone", package: "skip")]),
    ]
)
