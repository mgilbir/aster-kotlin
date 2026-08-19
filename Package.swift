// swift-tools-version: 6.0
import PackageDescription

// Aster Vega for Swift Package Manager.
//
// Two targets, and the split is the whole design. `AsterVega` is the engine — Kotlin/Native compiled
// to an XCFramework, so it arrives as a **binary** because SwiftPM cannot build Kotlin. `AsterVegaRender`
// is Swift source: the CoreGraphics renderer and the accessors that exist because a Kotlin value class
// has no representation across the Obj-C boundary.
//
// The url and checksum below are written by `.github/workflows/release.yml` **before** it tags, so the
// manifest at tag `vX.Y.Z` names the binary of that same release. That ordering is the reason the
// workflow creates the tag rather than being triggered by one: a checksum cannot be known until the
// artefact exists, and an adopter has to be able to pin one tag for Gradle and the same tag for Swift.
//
// `swift/AsterVegaRender/Package.swift` is the other half of this — it builds against a locally
// compiled framework and is what the tests and `scripts/swift-test.sh` use.
let version = "0.1.0"
let checksum = "bbc27831ae92b0bad2124f2b8f70ece7c3f235fdc5563571e470a4cdefe173fd"

let package = Package(
  name: "AsterVega",
  platforms: [.macOS(.v13), .iOS(.v16)],
  products: [
    .library(name: "AsterVegaRender", targets: ["AsterVegaRender"]),
    .library(name: "AsterVega", targets: ["AsterVega"]),
  ],
  targets: [
    .binaryTarget(
      name: "AsterVega",
      url:
        "https://github.com/mgilbir/aster-kotlin/releases/download/v\(version)/AsterVega-\(version).xcframework.zip",
      checksum: checksum
    ),
    .target(
      name: "AsterVegaRender",
      dependencies: ["AsterVega"],
      path: "swift/AsterVegaRender/Sources/AsterVegaRender"
    ),
  ]
)
