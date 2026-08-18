# aster-kotlin

A native Kotlin chart engine for Android that executes Vega-compatible specifications and renders
them through Android Canvas — no WebView, no JavaScript, no SVG DOM.

The same scene graph exports to SVG, bitmap, PNG and PDF, and the chart is available through both a
traditional `View` API and a Jetpack Compose API.

See [PROJECT_BRIEF.md](PROJECT_BRIEF.md) for the full design, [STATUS.md](STATUS.md) for where the
work actually stands, and [SUPPORTED_FEATURES.md](SUPPORTED_FEATURES.md) for the feature matrix.

## Current status

A Vega or a Vega-Lite specification compiles end to end and draws. Expressions and signals, 49 of
upstream's 51 documented data transforms, the 16 scale types it models, all 12 of Vega's mark types,
axes, legends and titles, group layout and autosize, and event handlers that recompile the chart.
`VegaChartController.setSpec` takes a specification and compiles it; `setSpecAsync` does it off the
calling thread.

It is checked against upstream rather than against itself. `./scripts/oracle.sh` renders every fixture
with the pinned `vega@6.3.1` and compares the resulting scene mark by mark and scale by scale;
`./scripts/vega-lite-oracle.sh` compiles every Vega-Lite fixture with the pinned `vega-lite@6.4.3` and
compares the emitted Vega **property by property**, then draws both and compares those scenes too. 193
Vega differential fixtures and 282 Vega-Lite fixtures are committed together with their references, and
[`test-fixtures/INDEX.md`](test-fixtures/INDEX.md) is the generated index of what each one covers.

Anything unsupported produces a structured diagnostic rather than a wrong picture.
[SUPPORTED_FEATURES.md](SUPPORTED_FEATURES.md) is the per-feature matrix, with the known differences
from upstream; "Known limitations" below is the short version.

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

Compiles from a **Vega-Lite** specification, property for property against upstream's own compiler:

- a single view, a `layer` of views, a `facet` grid, `concat`/`hconcat`/`vconcat` and `repeat`, over
  the `arc`, `bar`, `point`, `circle`, `square`, `tick`, `line`, `area`, `trail`, `rect`, `rule` and
  `text` marks, and the `boxplot`, `errorbar` and `errorband` composites they are rewritten into
- the position, polar, offset, colour, size, shape, text, tooltip, href, detail and order channels,
  with `aggregate`, `bin`, `timeUnit`, sorting, stacking and `impute`
- every transform a specification can write: `aggregate`, `bin`, `calculate`, `density`, `extent`,
  `filter`, `flatten`, `fold`, `impute`, `joinaggregate`, `loess`, `lookup`, `pivot`, `quantile`,
  `regression`, `sample`, `stack`, `timeUnit` and `window`
- `params`, and the selections they drive
- the defaults that make Vega-Lite short: scale types and ranges, a plot sized from its own
  categories, gridlines, tick counts, label angles, legends and titles

Compiles from a Vega specification, verified against upstream:

- scales: `linear`, `log`, `pow`, `sqrt`, `symlog`, `time`, `utc`, `ordinal`, `band`, `point`,
  `sequential`, `quantile`, `quantize`, `threshold`, `bin-ordinal` and `identity`, with d3-exact tick
  generation, `nice`, and all 68 of upstream's colour schemes
- every mark encoder: `arc`, `area`, `group`, `image`, `line`, `path`, `rect`, `rule`, `shape`,
  `symbol`, `text` and `trail`, with Vega's channel pairs, band offsets and all seventeen
  interpolation methods
- axes, legends and titles, including label overlap removal, truncation and the `config` cascade
- 49 of upstream's 51 documented data transforms, the two exceptions being `wordcloud` and `contour`
- expressions and signals, event handlers, and the `autosize` and group `layout` rules

Reported by name rather than approximated: `wordcloud` and `contour`, the expression functions that
need a browser or a view, incremental dataflow, and the composite Vega-Lite marks. See
`SUPPORTED_FEATURES.md` for the full list and `STATUS.md` for what is being worked on.

### Vega-Lite

`VegaLiteInput` accepts either grammar and hands back Vega, which is what a host that shows a chart
from text a user supplied actually needs — the user pasted a chart, not a dialect:

```kotlin
val converted = VegaLiteInput.toVega(text)   // routes on `$schema`, then on shape
val compiled = controller.setSpec(converted.vegaJson ?: text)
// converted.wasVegaLite says which way it went; converted.diagnostics is what the
// Vega-Lite compiler could not honour, ahead of anything the runtime reports.
```

`VegaLiteCompiler` is the compiler itself, if a host already knows which grammar it has. The demo's
"Paste your own" screen takes either, and its "Spec: Vega-Lite" entry is a bundled one.

`SUPPORTED_FEATURES.md` has the per-feature matrix with test references and known differences.

## Installation

Every library module publishes to Maven Central under `io.github.mgilbir.astervega`, one version for
all of them, cut from a tag by `.github/workflows/release.yml` (see "Releasing"). Until the first tag
is pushed there is nothing on Central yet: `./gradlew publishToMavenLocal` installs the same
coordinates locally and the snippet below then resolves against `mavenLocal()`.

```kotlin
dependencies {
  // The engine: a specification in, a scene out, plus the controller a host drives.
  implementation("io.github.mgilbir.astervega:vega-runtime:0.1.0")
  // Vega-Lite in, Vega out. Only needed if the specifications are Vega-Lite.
  implementation("io.github.mgilbir.astervega:vega-lite:0.1.0")

  // One renderer, whichever suits the host:
  implementation("io.github.mgilbir.astervega:vega-android-canvas:0.1.0")        // Android View
  implementation("io.github.mgilbir.astervega:vega-compose:0.1.0")               // Jetpack Compose
  implementation("io.github.mgilbir.astervega:vega-compose-multiplatform:0.1.0") // Compose MP
}
```

`vega-model`, `vega-expression`, `vega-dataflow`, `vega-scene` and `vega-svg` are published too and
arrive transitively; depend on one directly only to use it on its own.

On **iOS and macOS**, Swift Package Manager. The engine arrives as a pre-compiled XCFramework attached
to the release, so a consuming build needs no Kotlin toolchain and never runs Gradle:

```swift
// Package.swift
.package(url: "https://github.com/mgilbir/aster-kotlin", from: "0.1.0")
```

```swift
.target(name: "YourFeature", dependencies: [
  .product(name: "AsterVegaRender", package: "aster-kotlin"),  // the CoreGraphics renderer
])
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

`vega-compose-multiplatform` is the other Compose renderer: it paints a compiled scene straight onto a
`DrawScope`, so it runs on Android, iOS and the desktop with no `View` beneath it.

```kotlin
// The engine measures with the composition's own font, density and text scale, and the chart draws
// with the same ones. Compiling with something else — `MetricTextEngine`, say — lays the chart out
// from advances no font would draw.
val textEngine = rememberVegaTextEngine()
val compiled = remember(textEngine, specJson) { SpecCompiler(textEngine).compileJson(specJson) }

compiled.scene?.let {
  VegaChart(scene = it, modifier = Modifier.fillMaxSize(), textEngine = textEngine)
}
```

A host that ships its own face passes a resolver — `rememberVegaTextEngine { FontFamily(googleSansFlex) }`
— and both the measurement and the drawing use it.

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

## Theming a chart you did not write

A `config` block the host supplies, which the specification's own beats key by key. A chart arriving
from a server carries the colours that server chose; an app drawing it on a dark surface says otherwise
here rather than by rewriting the payload.

```kotlin
val dark = VegaJson.parse(
    """
    {"background": "#101418",
     "axis": {"labelColor": "#e6e6e6", "titleColor": "#e6e6e6", "domainColor": "#555",
              "tickColor": "#555", "gridColor": "#2a2f36"},
     "style": {"cell": {"stroke": null}},
     "text": {"color": "#e6e6e6"}, "rule": {"color": "#555"},
     "range": {"category": ["#7aa2f7", "#9ece6a", "#e0af68"]}}
    """
)

// Either grammar: the Vega-Lite compiler and the Vega one both take it.
val controller = VegaChartController(textEngine = engine, hostConfig = dark)
```

The merge is `vega-util`'s own `mergeConfig`: a block merges property by property, an object *inside* a
block overwrites, and `legend.layout` and each `style` entry recurse one level further.

Two things a host configuration cannot reach, both of them Vega-Lite's own precedence rather than a
limitation of this seam:

- **a mark's own encoded property beats every configuration block**, so a specification writing
  `mark.point.fill` keeps its white point on a dark card;
- `Normalize.pointOverlay` uses that `point` object **verbatim** as the overlay mark's definition, so
  the same is true of a line's point overlay.

A host that has to change one of those is rewriting the specification, and can inject its own `config`
in the same pass. `HostConfigTest` pins both the merge and these two limits.

## Locale example

Everything the engine *generates* — a month name on a time axis, a thousands separator, the sentence a
screen reader is given — comes from a `VegaLocale` the host supplies, beside the text engine and for
the same reason: the platform knows it and common Kotlin cannot reach it. The fields are d3's own
locale definitions, so a d3 locale JSON copies across field for field.

```kotlin
val dutch = VegaLocale(
    months = listOf("januari", "februari", /* … */),
    shortMonths = listOf("jan", "feb", /* … */),
    days = listOf("zondag", "maandag", /* … */),
    shortDays = listOf("zo", "ma", /* … */),
    periods = listOf("a.m.", "p.m."),
    date = "%d-%m-%Y",
    time = "%H:%M:%S",
    decimal = ",",
    thousands = ".",
    captions = DutchCaptions,   // see VegaCaptions
)

val controller = VegaChartController(textEngine = view.chartTextEngine, locale = dutch)
```

The captions are an interface rather than a string table, because they are grammar: a sentence puts a
title inside quotation marks a language chooses, joins a list with a conjunction it chooses, and agrees
a plural with a number. `VegaCaptions.English` is upstream's own wording and the default.

**Parsing is deliberately not localised.** A specification writing `"Jan 5 2026"` in its own data means
January in every language — d3's parsing is part of the wire format — so `TimeFormat.MONTHS` stays
English and only what is *written* follows the locale.

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

- **No incremental dataflow.** A transform recomputes its whole dataset; the `DataflowOperator`
  contract exists with no pulse propagation behind it. Correct, and slower than upstream on a large
  table.
- **`wordcloud` and `contour`** are the two documented data transforms that are not implemented. An
  unimplemented transform stops that dataset's pipeline and says so, rather than passing rows on that
  later stages were not meant to see.
- **No locale is built in.** The engine generates English and en-US **unless the host supplies a
  locale** — `VegaLocale`, whose fields are d3's own locale definitions, given to `SpecCompiler` or
  `VegaChartController` beside the text engine. Nothing ships a language: common Kotlin cannot reach a
  platform's date or number formatting, so the names come from the host or from nowhere.
- **`width: "container"` falls back to `config.view.continuousWidth`.** `containerSize()` has no
  container to measure outside a browser; a host that knows its available width sets the `width` signal
  itself.
- **Pan and zoom are a view transform**, so the axes do not rescale with the zoom. Vega-style zoom that
  updates the scale domains needs the dataflow.
- **Text metrics are platform metrics, not browser metrics.** Structural geometry compares tightly
  against upstream Vega; glyph bounds need the wider documented tolerances.
- **Images need a resolver.** Without one, image marks report `VEGA_EXPORT_IMAGE_UNRESOLVED` instead of
  drawing.
- **Blend modes below API 29** are limited to the PorterDuff subset; anything else reports
  `VEGA_RENDER_UNSUPPORTED_BLEND_MODE`.
- **No performance measurements on physical hardware.** The targets in PROJECT_BRIEF.md 19 are
  unverified; the microbenchmarks run, but not on a device.

## Development

### Toolchain

| Component | Pinned version |
| --- | --- |
| JDK | 17 or newer (17 is the bytecode target; the build runs fine on 21) |
| Kotlin | 2.4.10 |
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.5.0 |
| Compile SDK / Target SDK | 37 |
| Minimum SDK | 26 |
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
Vega-Lite specification       vega-lite  ──┐
                                           ↓
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

`vega-model`, `vega-expression`, `vega-dataflow`, `vega-scene`, `vega-runtime`, `vega-lite`,
`vega-svg` and `test-fixtures` contain no Android types; `NoAndroidTypesTest` enforces that.

`vega-lite` compiles Vega-Lite to Vega and depends on `vega-model` alone: it emits a specification,
it does not execute one, so a Vega-Lite chart takes exactly the path a Vega chart does from there.

Two demo apps render specifications on a real device: `demo/` on Android, and
[`swift/AsterVegaDemo`](swift/AsterVegaDemo) on iOS — SwiftUI over the CoreGraphics renderer, listing
each chart together with everything the engine could not honour. `scripts/ios-demo.sh` builds it.

Design decisions are recorded in [docs/adr/](docs/adr/).

## Releasing

Run the **Release** workflow. It verifies on Linux, publishes from macOS, writes the XCFramework's
checksum into `Package.swift`, commits that, and **then** tags the commit it wrote.

```sh
# 1. Bump the one version line and write the changelog section. Both are checked, and the release
#    fails before publishing anything if either is missing.
$EDITOR build.gradle.kts CHANGELOG.md
git commit -am "Aster Vega 0.1.0" && git push

# 2. Run the workflow. There is nothing to type: the version comes from build.gradle.kts, and the
#    run fails if that version is already tagged.
gh workflow run Release --ref main

# 3. Release the deployment at central.sonatype.com/publishing/deployments — it is uploaded as
#    USER_MANAGED and waits, because publishing to Central cannot be undone.
```

**The workflow creates the tag, and does not react to one.** That is the fix to an ordering problem
worth stating plainly, because the obvious arrangement cannot work. A Swift `binaryTarget` needs the
artefact's SHA256 written into `Package.swift`; a checksum cannot be known until the artefact exists;
and the artefact is built from the tag. Tag first, and the manifest inside the tag names the *previous*
release's binary — so an adopter pinning `v0.1.0` for Gradle and `0.1.0` for Swift gets two different
versions, or for a first release, nothing at all. Building first and tagging the commit that carries the
checksum gives one tag that is correct for both ecosystems, which is what an adopter asked for.

Two guards go with it. The push of the manifest commit to `main` is a fast-forward or nothing: if
something landed while the release was building, the artefacts on the runner are no longer what `main`
says, and a failed release is the honest outcome — nothing is public at that point, since the Central
deployment is still `USER_MANAGED`. And the release job refuses to tag if anything other than
`Package.swift` differs from the commit the artefacts were built from.

Four secrets are needed, and without them the publish step says so and skips rather than failing:
`CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`, `MAVEN_GPG_PRIVATE_KEY` (ASCII-armoured) and
`MAVEN_GPG_PASSPHRASE`.

Two more things are worth knowing rather than discovering. Everything is uploaded as **one bundle**,
checked by `verifyCentralBundle` before it leaves the runner, because uploading publications separately
lets the server assemble the deployment from whatever it believes arrived — and for `ktecma262` 0.1.3 it
assembled four modules out of seven, with the dropped ones reporting success. And
`verifyPublishedVariants` runs on macOS, because Kotlin creates a publication only for targets the host
can compile while the root module lists every declared one: `ktecma262` 0.1.2 was published from Linux
with no native variants at all, and a version on Central cannot be replaced.

### The public API

Every published module carries a committed ABI dump under `api/` — `.api` for the JVM surface and
`.klib.api` for the native one an iOS consumer links against. `./scripts/check.sh` compares them, so a
change to what other people compile against shows up in this repository's diff rather than in their
build. `./gradlew updateLegacyAbi` rewrites the dumps; review that diff as an API change.

`vega-compose` and `vega-android-canvas` are the two modules **not** covered, and the reason is a
tooling gap rather than a decision: since AGP 9 their Kotlin support comes from the Android plugin's own
`KotlinBaseApiPlugin`, so `binary-compatibility-validator` creates no tasks for them, and Kotlin's own
ABI validation reads a module's Maven publications, which for an Android library it does not support.
Their surface is small — `VegaChartView`, the `VegaChart` composable and their options — and it is the
one place a consumer has to read the diff by hand.

### Platforms

| Target | Published | Note |
| --- | --- | --- |
| `jvm` | Yes | What Android consumes and what every test runs on |
| `android` | Yes | `vega-compose`, `vega-android-canvas`, and the Android target of `vega-compose-multiplatform` |
| `iosArm64`, `iosSimulatorArm64` | Yes | And both slices of the `AsterVega` XCFramework |
| `macosArm64` | Yes | The Swift package's tests build against this one |
| `linuxX64` | Yes | Proves the core's portability rather than shipping |
| `iosX64` | **No** | Not a decision, and not something this repository can fix on its own: `ktecma262` 0.2.0 — the ECMA-262 regular-expression engine the core needs, because a pattern in a specification is JavaScript's — publishes `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64` and `jvm`, and no `iosX64`. Adding the target here fails at dependency resolution. It needs a `ktecma262` release with the slice first; ask if you need an Intel simulator |

## Licence

BSD 3-Clause — see [LICENSE](LICENSE).

That is Vega's own licence, and this project uses it for that reason rather than
by preference: a port is a derivative work, so the terms of what it ports come
with it. Vega, Vega-Lite and the `vega-*` packages are BSD 3-Clause (© University
of Washington Interactive Data Lab); d3 and TopoJSON, which Vega is built on and
this port therefore follows, are ISC (© Mike Bostock).
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) records both texts and which
parts of this repository derive from each; [test-fixtures/](test-fixtures/) says
where every expectation came from.
