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
Vega differential fixtures and 283 Vega-Lite fixtures are committed together with their references, and
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
  .product(name: "AsterVegaRender", package: "aster-kotlin"),  // the renderer and the SwiftUI view
])
```

`AsterVegaRender` is a chart, not a drawing primitive: `ChartSession` compiles either grammar off the
main thread and publishes the scene, and `VegaChartView` draws it with the gestures and a positioned
VoiceOver overlay.

```swift
@State private var session = ChartSession()
// …
if let scene = session.scene {
  VegaChartView(scene: scene, session: session)
}
// …
.task { session.load(specification: specification) }
```

Two things a host inside a scroll view needs. **No session means no gestures** — the view installs
none and turns hit testing off, which is what the `session` parameter has always documented. And where
there *is* a session, `gestures:` says which to install: a long press and a pan claim the touch, so a
chart in a horizontal scroll view inside a scrolling page passes `.withoutDrag` and keeps its taps,
its hover and its tooltips.

```swift
VegaChartView(scene: scene, session: session, gestures: .withoutDrag)
```

A tap is a hover as well on a screen with no pointer, so `"tooltip": true` answers one.

### A hole in a chart, said rather than left

An `image` mark whose URL nothing can resolve leaves a gap and the draw carries on — a chart is better
with one mark missing than not drawn at all. Both views now say which URL it was, once per URL rather
than once per frame:

```kotlin
VegaChart(scene = it, resolveImage = ::fetch, onUnresolvedImage = { url -> log(url) })
```

```swift
VegaChartView(scene: scene, resolveImage: fetch, onUnresolvedImage: { url in log(url) })
```

It is called **from the draw**, so treat it as a report: log, enqueue, launch — not a place to set
state a recomposition or a `body` would read. Once per URL is what makes that safe, and it comes from
caching the refusal alongside the decodes, which also stops a failing resolver being asked again on
every frame. A host that has recovered clears it: `ImageCache.clear()` on Compose,
`CoreGraphicsTarget.clearImageCache()` on Apple. `ImageCache.unresolvedImages` is the same facts
without a callback, for a host that would rather poll.

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

**Where the size comes from.** A chart takes the scene's own size — one scene unit per dp — wherever
`modifier` leaves a dimension free, because a specification declares a width and a height and that is
the size it asked for. The trap is that this happens whatever `fit` says: bound neither dimension and
the slot *is* the scene, so `fit` has nothing to place. For a `width: "container"` chart that means
`config.view.continuousWidth`, 300, plus its axes, however much room was going. Either bound the chart
— `Modifier.fillMaxWidth()`, a column, a size — or pass `sizing = SceneSizing.Fill`, which takes the
slot where there is one and leaves `fit` to place the scene inside it. The caller's `modifier` is
applied **first**, so a bound it states wins over both.

```kotlin
VegaChart(scene = it, sizing = SceneSizing.Fill, textEngine = textEngine)
```

Keyboard input is the host's own modifier on both Compose renderers — `Modifier.focusable()` and
`onKeyEvent`, forwarding to `controller.dispatch(ChartInputEvent.Key(...))` — and `ChartSession.press(_:)`
on iOS, wired with SwiftUI's `.onKeyPress`. The Android View translates keys itself, since a `View` owns
its own focus and key handling.

A host that ships its own face registers it **by name** —
`rememberVegaTextEngine(mapOf("Google Sans Flex" to FontFamily(googleSansFlex)))` — or passes a resolver
of its own, and both the measurement and the drawing use it. The default resolver knows the generic CSS
keywords and nothing else, which is a real limit rather than an omission: common Compose code cannot
reach an installed family, so only a host that already holds the `FontFamily` can map a name to one.
A family nothing resolves is drawn in the platform's default face and named in
`ComposeTextEngine.unresolvedFontFamilies`, since a text engine has no diagnostics channel to report it
through. The Android View takes the same seam as
`VegaChartView.fontResolver`, and on iOS a registered family resolves by name; `CoreTextTextEngine` and
`ChartSession` take a `textScale` for the reader's text-size setting, which
`DynamicTypeSize.chartTextScale` maps for a SwiftUI host.

Gestures are reported in **scene** coordinates, with the mark under them, and it is the host that
dispatches — this renderer depends on `vega-scene` alone, so a chart that is only being looked at pays
for no dataflow:

```kotlin
VegaChart(
    scene = scene,
    selectedNodeIds = snapshot.interactionState.selection.nodeIds,
    onTap = { point, _ -> controller.dispatch(ChartInputEvent.Tap(point)) },
    onLongPress = { point, _ -> controller.dispatch(ChartInputEvent.LongPress(point)) },
    onPan = { delta, ended ->
        controller.dispatch(
            ChartInputEvent.Pan(delta, if (ended) GesturePhase.ENDED else GesturePhase.CHANGED)
        )
    },
    onZoom = { factor, at, ended ->
        controller.dispatch(
            ChartInputEvent.Zoom(factor, at, if (ended) GesturePhase.ENDED else GesturePhase.CHANGED)
        )
    },
    onHover = { point, _ -> controller.dispatch(ChartInputEvent.PointerMoved(point)) },
)
```

What the renderer owns is the part a host must not repeat: inverting the **same** placement the drawing
used, and hit testing through `SceneHitIndex` (cached per scene). Two copies of that arithmetic is how a
finger lands beside the mark it looked like it hit. Pass nothing and the chart takes no pointer input at
all.

The points and deltas are in the space a **controller** expects: pixels with the chart's centring taken
off, and the fit scale left on, because `VegaChartController` divides by `contentScale` itself. So a host
sets that from `onPlaced` and passes the viewport back in:

```kotlin
val snapshot by controller.state.collectAsState()

VegaChart(
    scene = snapshot.snapshot.scene,
    onPlaced = { controller.contentScale = it.scale },
    // Pan and zoom are the controller's state; without these the gesture updates it and the chart
    // does not move.
    viewportOffset = snapshot.snapshot.interactionState.viewportOffset,
    viewportScale = snapshot.snapshot.interactionState.viewportScale,
    onPan = { delta, ended -> controller.dispatch(ChartInputEvent.Pan(delta, phase(ended))) },
)
```

`VegaChartView` on iOS reads the viewport off its session, so there is nothing to pass there.

## Tooltips

A specification's `"tooltip": true` reaches the host as a **value**, and no renderer draws a bubble: what
a bubble looks like is a design-system decision. What the engine does own is the step in between —
turning that value into lines a host can show:

```kotlin
// Kotlin, any renderer.
val tooltip = controller.tooltipContent            // null when there is nothing under the pointer
tooltip?.rows                                       // [("Question", "Total score"), ("Value", "18")]
tooltip?.text                                       // "Question: Total score\nValue: 18"
controller.snapshot.interactionState.tooltipAnchor  // where to put it, in the pixels you dispatched
```

```swift
// Swift: the same thing, published on the session.
if let tooltip = session.tooltip {
    TooltipBubble(rows: tooltip.rows).position(tooltip.anchor ?? .zero)
}
```

Three details that are easy to get wrong and are handled here:

- a mark with **no** tooltip channel produces an *empty object*, which is not a tooltip — treating it as
  one puts an empty bubble on every mark;
- a number is formatted with the chart's own locale, the way the axis beside it is, so a tooltip and a
  tick label never disagree in front of a reader;
- an instant is written with the locale's own date-and-time format rather than a hardcoded one.

`TooltipContent` reproduces `vega-tooltip`'s *shape* — an object becomes a row per field, in order,
anything else becomes one value — and does not claim byte-fidelity with its HTML, which this repository
does not pin and therefore cannot verify differentially.

## Diagnostics: what to do with one

A specification that compiles is not the same as a specification that compiled *cleanly*, and an
adopting team asked for a policy rather than a list of codes. `DiagnosticSeverity` **is** the policy —
each level says what it means for the chart — and this is it spelled out for a host:

| Severity | What it means | What a host should do |
| --- | --- | --- |
| `INFO` | The chart is unaffected. A note, often something the specification asked to be told. | Log it. Nothing else. |
| `WARNING` | The chart renders, but not exactly as the specification asked — an approximation, a property applied differently. | Draw it. Log it where somebody will see it: this is the level that accumulates when a payload drifts away from what the engine implements. |
| `ERROR` | A construct could not be honoured. The **surrounding chart still renders** without it. | Draw it, and decide by feature whether a chart missing that construct is worth showing. A missing legend is not a missing axis. |
| `FATAL` | No chart could be produced. `compiled.scene` is null. | Show your own fallback — the placeholder, an error state. Do not blank a chart the reader was already looking at: `VegaChartController` deliberately keeps the previous scene, so a failed recompile leaves the old chart up. |

Two things that follow from it and are easy to get wrong. **Nothing throws** — a compile returns
diagnostics, so a host that never reads them silently accepts every approximation. And the
severities are not a scale of *how broken*, they are a statement about **what is on screen**: an
`ERROR` chart is drawable and a `FATAL` one is not, which is the only distinction a UI has to make.

The codes are part of the public contract (`DiagnosticCodes`, `VegaLiteDiagnostics`), so a host may
match on one to special-case a construct it knows it sends.

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

## A responsive width

`width: "container"` asks the page how much room there is, and there is no page — so the host answers,
because it is the only party that knows. It is a **compile** input rather than a draw-time one: Vega-Lite
turns `"container"` into a signal, and every scale range, axis extent and mark position downstream is
resolved from it.

```kotlin
val controller = VegaChartController(textEngine = engine, containerSize = SizeD(width, height))

// And again whenever the layout changes. Setting it recompiles, so this belongs on a layout pass
// rather than on every frame of a resize animation; the signal values a reader has set survive it.
controller.containerSize = SizeD(newWidth, newHeight)
```

With nothing supplied a chart takes `config.view.continuousWidth` — 300, which is exactly what upstream
falls back to outside a browser, and what the differential fixtures compare against. A zero or absent
dimension does the same for that dimension alone, so a host that knows only its width says only its
width.

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

### The order of a date, not only its names

`%b` resolves to the reader's month abbreviation, so a Dutch axis has always said `mei`. What it also
said was `mei 21, 2026`, because the *pattern* — the order of the fields and what separates them —
came from tables with no locale in them: `%Y-%m-%d` in `TimeUnits`, and `%b %d, %Y` in the two entries
Vega-Lite writes into a bucketed axis's format.

**A locale now decides that too, and it needs nothing from you to do it.** `VegaLocale.date` is d3's
`%x`, "the date order this language writes", and it is derived from — so a locale copied field for
field out of a d3 locale JSON reads `21 mei 2026` because its own `%x` is `%d-%m-%Y`. The clock comes
from `time` the same way: `%H:%M:%S` gives a temporal axis 24-hour ticks instead of `%I %p`.

Two derivations, because there are two tables and they are not the same shape. `TimeUnits`'s entries
are **numeric**, so the whole pattern transfers, separators and all. Vega-Lite's spell the month as a
**name** — that is what it is for — so only the *order* transfers and the directives stay `%b %d %Y`:
substituting a name into a numeric pattern would give `21-mei-2026`.

This is a deliberate **divergence from upstream**, whose `timeUnitSpecifier` takes no locale at all.
`VegaLocale.EnglishUS` is therefore pinned to upstream's own answers — it states both tables empty
rather than deriving — and it is the locale the differential fixtures and the recorded upstream vectors
compare against. It is also the only locale that is pinned; state an empty map yourself to opt another
one in, or state a table to say exactly what you want:

```kotlin
val dutch = VegaLocale(
    /* … the names … */
    date = "%d-%m-%Y",              // derived from: `21 mei 2026`, and `%d-%m-%Y` for numerals
    time = "%H:%M:%S",              // derived from: 24-hour ticks on a temporal axis
    // Only where you want something else. Keyed by a unit or a recognised run of units, coarsest
    // first, exactly as `TimeUnits` keys them; a null value *removes* an entry, which is how you say
    // "do not combine these two".
    timeUnitSpecifierOverrides = mapOf("year-month-date" to "%-d %b %Y "),
    timeTickFormatOverrides = mapOf("hour" to "%Hh"),
)
```

Give the **same** locale to `VegaLiteInput.toVega` and to the compiler or controller that draws the
result. The pattern is written on the Vega-Lite side and the month names are resolved on the runtime
side, so a locale supplied to one and not the other is an axis whose order and whose names come from
different places. `ChartSession` does this for you from its own `locale`.

A specification's own second argument to `timeUnitSpecifier(units, specifiers)` still wins: the
document asked for that by name, and a host's language is a default beneath it.

## Host data example

A chart can be drawn from data the **app** holds rather than data the payload carried. The
specification names a dataset and leaves it empty — `{"data": {"name": "diary"}}` in Vega-Lite,
`{"name": "diary"}` in Vega — and the host fills it, which is upstream's `view.data(name, rows)`:

```kotlin
controller.setSpec(specificationFromServer)

// Later, and again whenever the store changes.
controller.setData("diary", entries.map { entry ->
    ForeignData.row(mapOf(
        "bucket" to VegaValue.Str(entry.partOfDay),
        "at" to ForeignData.instant(entry.at.toEpochMilliseconds()),
        "v" to VegaValue.Num(entry.value),
    ))
})
```

From Swift the rows are Swift values, since a Kotlin value class has no Obj-C representation:

```swift
session.setData("diary", rows: [
    ["bucket": .text("morning"), "at": .instant(entry.at), "v": .number(3)]
])
```

Four things worth knowing:

- The rows arrive **where inline values would**, so the dataset's own `format.parse` and its transforms
  run over them unchanged. A host does not reimplement a parse rule or a `timeunit` to get its table
  drawn.
- `ForeignData.instant` hands over a date **as a date**. A host holding one should not format it to a
  string for the engine to parse back: that crosses a time zone twice, and twice is where a day goes
  missing.
- Setting data **recompiles**, which is how this engine answers a change of any compile input. It is a
  seam for new data, not somewhere to write per frame; equal rows do nothing.
- Three things are refused rather than guessed, each with a diagnostic: a name no dataset carries, a
  **derived** dataset (filling one would discard the transforms it exists for — supply the rows for its
  source instead), and a dataset whose `url` is then **not fetched**, which is also the way to draw a
  chart whose payload names an address you would rather not open.

A dataset declared **inside a group mark** is filled the same way, by its own name. And as with the time
zone, this is a *compile* input: the Compose renderer draws a scene that has already been compiled, so
it is set wherever the controller or `SpecCompiler` lives.

### The same seams from iOS

`ChartSession` takes every compile input the Kotlin controller does, because a capability that exists
for one host and not the other is a gap in this boundary rather than a fact about the platform:

```swift
let session = ChartSession(
    locale: dutch,                                   // month names, separators, spoken captions
    hostConfigJson: theme,                           // a `config` block, as JSON
    containerSize: SizeD(width: 360, height: 0),     // what `width: "container"` asks for
    timeZone: TimeZone(identifier: profile.zone)     // which zone "local" is
)
session.setData("diary", rows: rows)                 // a table the app holds
```

A theme that is not a JSON object lands in `hostConfigFailure` and the chart is drawn unthemed; a time
zone the platform cannot resolve lands in `timeZoneFailure` and the chart is drawn in the device's zone.
Neither throws, because both usually come from a server.

Take `containerSize` from something **stable** — the parent's width, a size class, a fixed column — and
not from the chart view's own geometry: a chart sized to its container changes its scene's width, the
view's aspect ratio follows the scene, and a width read back from that view can oscillate. That loop is
why it is not wired up automatically.

## Metadata a document carries for you

`usermeta` is the one top-level property whose whole purpose is to survive compilation — upstream's
schema calls it "optional metadata that will be passed to Vega" — and nothing in the engine reads it.
It is how whoever wrote the chart hands the app something the grammar has no channel for: a table of
the values behind marks that carry no accessible text of their own, a version to branch on, an
identifier to log against.

```kotlin
val compiled = controller.setSpec(specificationFromServer)
val source = compiled.spec?.usermeta?.get("source")?.asString()
```

```swift
let source = session.usermeta?["source"]
```

Null when the document has none, and an **empty map** when it wrote `{}` — those are two different
statements and reading them as one loses the difference between a document that carries no metadata
and one whose metadata was filtered to nothing. A `usermeta` that is not an object is reported and
dropped, since a host reading it back by key would otherwise find nothing and have no way to know
why.

Vega-Lite carries it through to the Vega it emits, so a document written in either grammar arrives
with it intact.

## Time zone example

A chart of days needs to know which zone the days are in, and on a handset that is not always the
device's. So `timeZone` sits beside `locale` — a different question with the same shape, since a Dutch
reader in Curaçao needs one of each:

```kotlin
val controller = VegaChartController(
    textEngine = view.chartTextEngine,
    // Whatever the app already knows the reader's zone to be: a profile setting, an account
    // preference, `java.util.TimeZone.getDefault().id` for the device's own.
    timeZone = VegaTimeZones.of(profile.timeZoneId),
)
```

`VegaTimeZones.of` answers **null** for an identifier the platform does not carry rather than throwing,
because that string usually comes from a server; null means "the device's own zone", which is what every
host had before this existed and is what upstream does.

It settles four things, and the last is the one worth reading twice:

- a `time` scale's ticks, and therefore where a mark lands and what its label says;
- `timeunit`'s buckets — which day, week or hour a row is grouped into;
- the local expression functions (`hours`, `timeFormat`, `datetime`, `timeOffset`, …);
- and **`format.parse`**: a timestamp with no offset in the data, `2026-05-20T00:30`, names a different
  instant in every zone, and `Date.parse` reads it in local time. So the zone decides what local *is*,
  not whether it applies.

The `utc` forms are untouched: a `utc` scale, a `utc:` parse pattern and the `utc*` functions stay UTC
whatever a host says. One consequence of upstream's rules is easy to misread as a bug —
`utchours("2026-05-20T00:30")` **parses** that string in local time and only then reads the hour in UTC,
so its answer moves with this setting too. `TimeZoneTest` pins each of these.

Since the zone is a **compile** input, it is set where the controller or `SpecCompiler` is built. The
Compose renderer draws a scene that has already been compiled, so there is nothing to pass there;
`ChartSession(timeZone:)` is the seam on iOS and takes a `Foundation.TimeZone`.

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

`test-fixtures/scene-walk/*.calls.txt` are a third kind, and they are read by **two** test suites.
`SceneWalkGoldenTest` writes them from the Compose walk, and `SceneWalkParityTests` in
`swift/AsterVegaRender` asserts the Swift walk produces the same bytes — which is how "the two
renderers emit the same calls in the same order", a claim both files make about themselves, is checked
rather than believed. When those two disagree, one of the walks changed; read the diff before
regenerating.

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
build. `./gradlew updateKotlinAbi` rewrites the dumps; review that diff as an API change.

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
