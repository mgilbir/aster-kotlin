// swift-tools-version: 6.0
import PackageDescription
import Foundation

// The framework Kotlin exports, found relative to this package rather than by absolute path so the
// checkout can live anywhere. `scripts/swift-test.sh` builds it first; without that this manifest
// still resolves and the compile fails with a missing-module error, which says what to do.
let frameworks = Context.packageDirectory + "/../../vega-runtime/build/bin/macosArm64/debugFramework"

let package = Package(
  name: "AsterVegaRender",
  platforms: [.macOS(.v13), .iOS(.v16)],
  products: [
    .library(name: "AsterVegaRender", targets: ["AsterVegaRender"])
  ],
  targets: [
    .target(
      name: "AsterVegaRender",
      swiftSettings: [.unsafeFlags(["-F", frameworks])],
      linkerSettings: [.unsafeFlags(["-F", frameworks, "-framework", "AsterVega"])]
    ),
    .testTarget(
      name: "AsterVegaRenderTests",
      dependencies: ["AsterVegaRender"],
      swiftSettings: [.unsafeFlags(["-F", frameworks])],
      linkerSettings: [.unsafeFlags(["-F", frameworks, "-framework", "AsterVega"])]
    ),
  ]
)
