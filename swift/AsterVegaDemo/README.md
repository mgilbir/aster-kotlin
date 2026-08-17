# AsterVegaDemo

An iOS app that compiles Vega specifications with this engine and draws them with the Swift renderer.
SwiftUI, CoreGraphics, CoreText — no Compose and no Kotlin on the drawing side.

```sh
scripts/ios-demo.sh            # build, install and launch on a simulator
scripts/ios-demo.sh --check    # compile both slices; needs no simulator runtime
scripts/ios-demo.sh --device   # build for a connected device
```

## What it shows

A list of the repository's own differential fixtures, each compiled on the device and drawn. Tapping
one gives the chart, its size in points, its mark count — and **everything the engine could not
honour**, listed with the severity and the path into the specification.

That last part is the point rather than decoration. This engine's discipline is that nothing is
silently ignored, and this screen is the only place that claim is visible to someone who is not reading
the test suite.

The bundled specifications all carry their data inline. The app installs `DenyLoader`, so a
specification reaching for a URL would produce a diagnostic rather than an empty chart — which is why
the fixtures with `"url"` data are not among them.

## How it is wired

The app links `AsterVega.xcframework`, assembled by
`./gradlew :vega-runtime:assembleAsterVegaDebugXCFramework`. It carries both slices, device and
simulator: one framework directory cannot hold both, and an app that picks between two directories by
SDK links the wrong one eventually.

The renderer's sources are **compiled into the app target** rather than linked as a Swift package. That
is deliberate: `swift/AsterVegaRender/Package.swift` points its framework search path at the *macOS*
framework so that `swift test` works, and an iOS build cannot use that. Compiling the five files
directly keeps one copy of the renderer and no second manifest to keep in step.

`SceneCanvas` draws into a SwiftUI `Canvas` through `withCGContext`. **It does not flip the context** —
SwiftUI's canvas already has its origin at the top left with y growing down, which is the space a scene
is in. The package's bitmap tests *do* flip, because a bare `CGBitmapContext` has its origin at the
bottom left. That difference belongs to the caller, which is exactly why the renderer flips nothing
itself.

## Text is laid out one way and drawn another

The app compiles with `MetricTextEngine`, the portable ratio-based engine, and then draws the resulting
runs with CoreText. So a label's *position* comes from approximate metrics while its *glyphs* come from
the device's font, and a long label can overhang the box the layout reserved for it.

The seam to close that is already there — `SpecCompiler(textEngine:)` — and closing it means a
`TextEngine` implemented over CoreText, the way `AndroidTextEngine` does it on the other platform. Worth
doing; not done here.

## The project file

`AsterVegaDemo.xcodeproj` is hand-authored and minimal: one target, three build phases, no asset
catalog, `GENERATE_INFOPLIST_FILE = YES` so there is no plist to maintain. It opens in Xcode and builds
with `xcodebuild -scheme AsterVegaDemo`. Xcode will want to add things to it the moment you edit it in
the IDE; that is fine, but the checked-in file is meant to stay readable.
