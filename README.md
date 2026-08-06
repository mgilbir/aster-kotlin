# aster-kotlin

A native Kotlin chart engine for Android that executes Vega-compatible specifications and renders
them through Android Canvas — no WebView, no JavaScript, no SVG DOM.

The same scene graph exports to SVG, bitmap, PNG and PDF, and the chart is available through both a
traditional `View` API and a Jetpack Compose API.

See [PROJECT_BRIEF.md](PROJECT_BRIEF.md) for the full design, [STATUS.md](STATUS.md) for where the
work actually stands, and [SUPPORTED_FEATURES.md](SUPPORTED_FEATURES.md) for the feature matrix.

## Current status

Milestones 0, 1 and 2 are complete: the repository builds, the scene graph and Android Canvas
renderer work, and SVG output is deterministic and golden-tested.

**Vega JSON specifications are not accepted yet.** `VegaChartController.setSpec` reports
`VEGA_TRANSFORM_NOT_IMPLEMENTED` rather than rendering nothing; specification parsing and scales
arrive in Milestone 3. Until then charts are built as scene graphs directly — see
`test-fixtures/src/main/kotlin/dev/aster/vega/fixtures/SampleScenes.kt` for worked examples of bar,
stacked bar, line, area and scatter charts.

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

Planned: Vega JSON parsing and scales (Milestone 3), dataflow and transforms (4), generated axes and
legends (5), signal-driven interaction (6), richer accessibility (7), performance work (9).

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
  implementation("dev.aster.vega:vega-compose")      // Compose API
  implementation("dev.aster.vega:vega-android-canvas") // View API
}
```

## View example

```kotlin
val chartView = VegaChartView(context)

// Build a scene with the view's own text engine, so measurement and drawing agree.
chartView.setScene(SampleScenes.barChart(chartView.chartTextEngine))
```

Once Milestone 3 lands, the same view accepts a compiled Vega specification:

```kotlin
chartView.controller.setSpec(specJson)
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

- **No Vega JSON input yet.** Charts must be built as scene graphs until Milestone 3.
- **No expression evaluation yet.** Every expression reports
  `VEGA_EXPRESSION_UNSUPPORTED_FUNCTION`; the evaluator arrives in Milestone 4.
- **Axes, legends and titles are hand-authored** in the sample scenes rather than generated from a
  specification (Milestone 5).
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
./scripts/oracle.sh         # upstream Vega reference output
```

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
┌─────────────────┬─────────────────┬─────────────────┐
│ Android Canvas  │ SVG serializer  │ Bitmap/PNG/PDF  │
│ vega-android-   │ vega-svg        │ vega-android-   │
│ canvas          │                 │ canvas          │
└─────────────────┴─────────────────┴─────────────────┘
        ↓
Interaction and semantic accessibility trees
```

`vega-model`, `vega-expression`, `vega-dataflow`, `vega-scene`, `vega-runtime`, `vega-svg` and
`test-fixtures` contain no Android types; `NoAndroidTypesTest` enforces that.

Design decisions are recorded in [docs/adr/](docs/adr/).
