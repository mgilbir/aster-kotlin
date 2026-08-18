// swift-tools-version: 6.0
import PackageDescription

// Aster Vega for Swift Package Manager.
//
// Two targets, and the split is the whole design. `AsterVega` is the engine — Kotlin/Native compiled
// to an XCFramework, so it arrives as a **binary** because SwiftPM cannot build Kotlin. `AsterVegaRender`
// is Swift source: the CoreGraphics renderer and the accessors that exist because a Kotlin value class
// has no representation across the Obj-C boundary.
//
// The url and checksum below point at a release asset and are rewritten by `.github/workflows/release.yml`
// when one is published. That has an ordering consequence worth stating plainly rather than discovering:
// a checksum cannot be known until the artefact exists, so the manifest *inside* a tag names the
// **previous** release's binary. Swift consumers should therefore track `main`, or a tag one release
// ahead of the engine they want. `swift/AsterVegaRender/Package.swift` is the other half of this — it
// builds against a locally compiled framework and is what the tests and `scripts/swift-test.sh` use.
let version = "0.0.0-unreleased"
let checksum = "0000000000000000000000000000000000000000000000000000000000000000"

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
