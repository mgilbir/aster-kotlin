# aster-kotlin

A native Kotlin chart engine for Android that executes Vega-compatible specifications and renders
them through Android Canvas — no WebView, no JavaScript, no SVG DOM.

The same scene graph exports to SVG, bitmap, PNG and PDF, and the chart is available through both a
traditional `View` API and a Jetpack Compose API.

See [PROJECT_BRIEF.md](PROJECT_BRIEF.md) for the full design, [STATUS.md](STATUS.md) for where the
work actually stands, and [SUPPORTED_FEATURES.md](SUPPORTED_FEATURES.md) for the feature matrix.

## Current status

Milestones 0, 1 and 2 are complete: the repository builds, the scene graph and Android Canvas renderer
work, and SVG output is deterministic and golden-tested. Milestone 3 is in progress.

**A Vega specification compiles end to end, and is verified against upstream Vega.** `SpecCompiler`
turns a compiled Vega JSON specification into a scene, and `BarFixtureDifferentialTest` compares the
result against upstream: for `test-fixtures/specs/bar.vg.json` all 48 marks and both scales match
exactly. Run `./scripts/oracle.sh` to regenerate the reference data and re-check.

That slice is deliberately thin. Supported today: `linear`, `band`, `point` and `ordinal` scales; the
`rect` mark; axes with ticks, labels, gridlines and domain lines; `autosize: pad`. **Not** supported:
expressions and signals, all 40 data transforms, the other eleven mark types, legends and titles. Each
of those reports a diagnostic rather than rendering something wrong — see `SUPPORTED_FEATURES.md` for
the matrix and `STATUS.md` for how much of upstream Vega that leaves.

`VegaChartController.setSpec` is not yet wired to the compiler; build a scene with `SpecCompiler` and
pass it to `setScene`. Charts can also be built as scene graphs directly — see
`test-fixtures/src/main/kotlin/dev/aster/vega/fixtures/SampleScenes.kt` for worked bar, stacked bar,
line, area and scatter examples.

## Supported features

Fully supported today:

- Scene graph: groups, rects, rules, paths, symbols, text, images; nested transforms; clipping
- Geometry: tight bounds including stroke extents, affine transforms, cubic path maths
- Text: platform text measurement used for both layout and drawing, wrapping, multiline, font fallback
- Hit testing: linear for small scenes, uniform grid above a threshold, separate touch and mouse
  tolerances
- Interaction: tap, hover, long press, point selection, pan, pinch zoom, mouse-wheel zoom
- Rendering: solid fills, strokes with caps/joins/dashes, opacity, linear and radial gradients
- Output: Android Canvas, SVG, bitmap, PNG, PDF, canonical JSON snapshots
- Accessibility: virtual accessibility descendants with labels, values, activation and selected state
- APIs: `VegaChartView` and the `VegaChart` Composable

Compiles from a Vega specification, verified against upstream:

- `linear`, `band`, `point`, `ordinal` scales, with d3-exact tick generation and `nice`
- the `rect` mark, with Vega's x/x2/width and y/y2/height channel pairs and band offsets
- axes: ticks, labels, gridlines, domain lines, all four orientations
- `autosize: pad` layout

Planned: expressions and signals, the 40 data transforms, the remaining eleven mark encoders, legends
and titles (Milestones 3-5), signal-driven interaction (6), richer accessibility (7), performance work
(9).

`SUPPORTED_FEATURES.md` has the per-feature matrix with test references and known differences.

## Installation

The library is not published yet. Depend on the modules from a local checkout:

```kotlin
// settings.gradle.kts
includeBuild("../aster-kotlin")
```

```kotlin
// build.gradle.kts
dependencies {
  implementation("dev.aster.vega:vega-compose")      // Compose API, Android
  implementation("dev.aster.vega:vega-android-canvas") // View API
  // Compose Multiplatform: the same renderer on Android, iOS and the desktop.
  implementation("dev.aster.vega:vega-compose-multiplatform")
}
```

## View example

```kotlin
val chartView = VegaChartView(context)

// Build a scene with the view's own text engine, so measurement and drawing agree.
chartView.setScene(SampleScenes.barChart(chartView.chartTextEngine))
```

A compiled Vega specification, via the compiler:

```kotlin
val compiled = SpecCompiler(chartView.chartTextEngine).compileJson(specJson)

// Never ignore the diagnostics: anything unsupported is reported here rather than rendered wrongly.
compiled.diagnostics.forEach { Log.w("chart", it.toString()) }
compiled.scene?.let { chartView.setScene(it) }
```

## Compose example

```kotlin
val controller = rememberVegaChartController()

LaunchedEffect(Unit) { controller.setScene(SampleScenes.lineChart()) }

VegaChart(
    controller = controller,
    modifier = Modifier.fillMaxSize(),
)
```

The Compose API hosts the canonical `VegaChartView` through `AndroidView`, so both APIs share
identical text metrics, rendering, hit testing and accessibility. See
[docs/adr/0003-compose-hosts-the-view.md](docs/adr/0003-compose-hosts-the-view.md).

## Export example

```kotlin
val exporter = SceneExporter()

val svg: String = scene.toSvg()

val png: ByteArray = exporter.toPng(
    scene,
    BitmapExportOptions(width = 1200.0, height = 800.0, pixelScale = 2f),
).bytes

val pdf: ByteArray = exporter.toPdf(scene, widthPoints = 1200.0, heightPoints = 800.0).bytes
```

Bitmap, PNG and PDF all render through the same Canvas backend as the live view, so exported geometry
matches what is on screen. Anything that could not be drawn faithfully comes back in `warnings`
rather than being silently dropped.

## Interaction example

```kotlin
lifecycleScope.launch {
    controller.events.collect { event ->
        when (event) {
            is ChartEvent.MarkClicked -> handleClick(event.datum)
            is ChartEvent.SelectionChanged -> updateSelection(event.selection)
            else -> Unit
        }
    }
}
```

Diagnostics are a `StateFlow`, so unsupported constructs are observable rather than invisible:

```kotlin
lifecycleScope.launch {
    controller.diagnostics.collect { diagnostics ->
        diagnostics.forEach { Log.w("chart", it.toString()) }
    }
}
```

## Known limitations

- **Only a subset of Vega compiles.** Four scale types, one mark type, axes without titles. One
  differential fixture passes out of the 100 the brief asks for.
- **No expression evaluation.** Every `{"signal": ...}` reports
  `VEGA_EXPRESSION_UNSUPPORTED_FUNCTION` and the channel is dropped. This is the biggest gap, because
  signals are pervasive in real specifications.
- **No data transforms.** All 40 report `VEGA_TRANSFORM_NOT_IMPLEMENTED`, and the dataset passes through
  untransformed — so a chart that needs one shows the wrong data, visibly and with an explanation.
- **Legends and titles are not generated** from a specification (Milestone 5).
- **`setSpec` is not wired to the compiler**; call `SpecCompiler` and `setScene` yourself.
- **Pan and zoom are a view transform**, so axes do not rescale with the zoom. Vega-style zoom that
  updates scale domains needs the dataflow (Milestone 6).
- **Text metrics are platform metrics, not browser metrics.** Structural geometry compares tightly
  against upstream Vega; glyph bounds need the wider documented tolerances.
- **Images need an `AndroidImageResolver`.** Without one, image marks report
  `VEGA_EXPORT_IMAGE_UNRESOLVED` instead of drawing.
- **Blend modes below API 29** are limited to the PorterDuff subset; anything else reports
  `VEGA_RENDER_UNSUPPORTED_BLEND_MODE`.
- **No performance measurements yet.** The targets in PROJECT_BRIEF.md 19 are unverified; nothing has
  been run on physical hardware.
- **No geographic projections, force layouts or Vega-Lite compilation.** Out of scope for the first
  release.

## Development

### Toolchain

| Component | Pinned version |
| --- | --- |
| JDK | 17 or newer (17 is the bytecode target; the build runs fine on 21) |
| Kotlin | 2.4.10 |
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.5.0 |
| Compile SDK / Target SDK | 37 |
| Minimum SDK | 23 |
| Compose BOM | 2026.06.01 |
| Node (oracle only) | 20 or newer |

Everything is pinned in `gradle/libs.versions.toml`. No alpha, beta, release-candidate, dynamic or
unversioned dependencies. Deviations from the versions named in PROJECT_BRIEF.md are listed in
STATUS.md.

### Setting up from a clean macOS install

```bash
# 1. JDK and Node
brew install openjdk@21 node

# 2. Android SDK, without Android Studio
./scripts/setup-android-sdk.sh

# 3. Environment
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21"

# 4. Verify
java -version
./gradlew --version
adb version

# 5. Build and test
./gradlew test lint :demo:assembleDebug
```

`local.properties` (git-ignored) needs `sdk.dir=$HOME/Library/Android/sdk`, or set `ANDROID_HOME`.

The whole project builds and tests from the terminal; Android Studio is optional and only useful for
the emulator, profiling and layout inspection.

### Everyday commands

```bash
./scripts/test-core.sh      # JVM tests only; no SDK or device needed
./scripts/check.sh          # spotlessCheck + all tests + lint + demo APK
./scripts/test-android.sh   # instrumented tests; needs a device or emulator
./scripts/install-demo.sh   # build and install the demo app
./scripts/capture-demo.sh   # screenshot the device into build/artifacts
./scripts/benchmark.sh      # microbenchmarks; warns on an emulator
./scripts/oracle.sh         # regenerate upstream references, then compare
./scripts/emulator.sh       # start the AVD with a window and install the demo
```

`CONTRIBUTING.md` describes how the engine is built rather than what it contains: probing upstream
before implementing, the differential gate, and why a new fixture is expected to fail.

### Goldens

Scene snapshots and SVG output are golden-tested. Normal test runs never rewrite a golden. To
regenerate deliberately:

```bash
./gradlew test -PupdateGoldens=true --rerun-tasks
```

Review the resulting diff as a rendering change, not as noise.

### Formatting

```bash
./gradlew spotlessApply
```

### Portability

The core modules are plain Kotlin and are meant to move to Kotlin Multiplatform unchanged: no Android
types and no JVM-only APIs, with `PlatformDecimals` the single documented exception. `NoAndroidTypesTest`
enforces it. See `CONTRIBUTING.md`.

## Architecture

```text
JSON specification            vega-model
        ↓
Runtime compiler              vega-runtime
        ↓
Dataflow graph                vega-dataflow, vega-expression
        ↓
Immutable scene snapshot      vega-scene
        ↓
┌────────────────┬────────────────┬────────────────┬────────────────┬────────────────┐
│ Android Canvas │ SVG serializer │ Bitmap/PNG/PDF │ Compose        │ CoreGraphics   │
│ vega-android-  │ vega-svg       │ vega-android-  │ Multiplatform  │ swift/AsterVe- │
│ canvas         │                │ canvas         │ vega-compose-  │ gaRender       │
│                │                │                │ multiplatform  │                │
└────────────────┴────────────────┴────────────────┴────────────────┴────────────────┘
        ↓
Interaction and semantic accessibility trees
```

`vega-model`, `vega-expression`, `vega-dataflow`, `vega-scene`, `vega-runtime`, `vega-svg` and
`test-fixtures` contain no Android types; `NoAndroidTypesTest` enforces that.

Two demo apps render specifications on a real device: `demo/` on Android, and
[`swift/AsterVegaDemo`](swift/AsterVegaDemo) on iOS — SwiftUI over the CoreGraphics renderer, listing
each chart together with everything the engine could not honour. `scripts/ios-demo.sh` builds it.

Design decisions are recorded in [docs/adr/](docs/adr/).
