# Changelog

Notable changes, newest first. The release workflow reads the section for the
version it is publishing and uses it as the release notes, so a version without a
section here does not get released.

## Unreleased

### Fixed

- **The README no longer teaches the Vega-Lite fallback that 0.2.0 removed**, and the
  demo no longer performs it. `converted.vegaJson ?: text` hands a document Vega-Lite
  could not compile to a parser that only understands Vega, which answers with
  complaints phrased in the wrong grammar and buries the one diagnostic that says what
  happened. That is the defect fixed in `ChartSession` for 0.2.0 (#61) — and the
  repository went on documenting it in `README.md` and doing it in both of the demo's
  conversion paths, so a Kotlin host following either reintroduced it. The example now
  branches on a null `vegaJson` and surfaces `converted.diagnostics`; the demo's paste
  screen reports the refusal through a new `PasteReport.refused` without asking the
  runtime, and its bundled-asset path stops the same way. A sentence now says what the
  nullability actually means, since a null `vegaJson` and a `wasVegaLite` of false look
  identical at the call site and only one of them is safe to pass on. Pinned by
  `VegaJsonFallbackTest`, which scans the main sources, the Swift sources and the
  README. (#101)

### Added

- **A Kotlin host can tell a failed compile from one that has not happened**, through
  `ChartState.failure`. A compile that produces no scene deliberately keeps the previous
  snapshot — a reader holds on to what they were looking at — which left nothing on the
  state saying a failure had occurred, so a host wanting its own "this chart cannot be
  drawn" copy had to infer it from the diagnostics. That inference is wrong in both
  directions: `PARSE_NOTHING_TO_DRAW` is INFO by deliberate choice, and a document can
  report errors and still draw a chart the reader can see. It is now stated: the first
  ERROR or FATAL diagnostic's message where a compile drew nothing, a plain sentence
  where nothing said anything more useful, and null again after the next compile that
  draws or any `setScene`. The Swift `ChartSession.failure` has carried exactly this
  since 0.2.0, so the same host logic is now expressible on both platforms. `ChartState`
  gains a fourth constructor parameter, defaulted, which changes its Obj-C initialiser
  selector; nothing in this repository constructs one. (#100)

- **A facet header follows the reader's language whether or not the field was bucketed.**
  `Facet.headerText` threaded the locale for a field carrying a `timeUnit` and wrote
  upstream's `%b %d, %Y` outright for one that did not, three lines apart — so a grid
  split by a bucketed field was captioned in the reader's language and a grid split by a
  plain temporal field was captioned in American English. Both branches now read the same
  `year-month-date` entry, so a bucketed date and an unbucketed one cannot be captioned
  differently. `VegaLocale.EnglishUS` is byte-for-byte unchanged, which is what leaves the
  283 fixture comparisons still. (#98)

## 0.2.0

### Fixed

- **The Swift package builds on macOS again.** 0.1.0 declared
  `platforms: [.macOS(.v13), .iOS(.v16)]` and shipped an XCFramework carrying the
  two iOS slices and nothing else, so a consumer running `swift build` on a Mac —
  a plain command-line build, an Xcode preview, a package-scheme check — got `no
  such module 'AsterVega'`. iOS itself was unaffected: both slices were there, and
  an iOS Simulator build of the published package succeeds. The release XCFramework
  now carries all three slices; the debug one stays iOS-only, so
  `scripts/ios-demo.sh` keeps its fast path — the exclusion was written for that
  speed and was applied to what ships as well, which is the whole of the defect.
  The release workflow now asserts the slice list against the platforms the
  manifest promises before it tags, because nothing in the build compared the two:
  the artefact was correct by iOS's standards and wrong by the manifest's, and only
  a consumer found out. (#56)

- **A SwiftUI chart with no session installs no gestures**, and one with a session
  says which. Every handler already returned early without a session, but
  `DragGesture(minimumDistance: 0)` claimed the drag on touch-down anyway — so a
  chart in a horizontal scroll view inside a scrolling page stopped both from
  scrolling, where the same host code on Compose scrolled both ways. The button
  trait and `accessibilityRespondsToUserInteraction` are now gated on the same
  condition as the action they advertise, so VoiceOver no longer offers an
  activation that does nothing. And the tap is a `SpatialTapGesture` separate from
  the pan, so `VegaChartView(gestures: .withoutDrag)` keeps taps, hover and
  tooltips without claiming a touch. (#72)

- **The Apple renderer no longer paints a label the axis deliberately hid.** An
  overlapping axis label is hidden at zero opacity rather than removed, and the
  Swift walk guarded on `visible` alone — so the node reached the text branch,
  `brush` answered nil, and `CoreTextDrawing` read that as a paint it could not
  express and drew black. Measured on `label-overlap.vg.json`: 43 labels, 24
  hidden, 43 drawn where the Compose renderer drew 19. The Compose walk's
  zero-opacity guard is now mirrored here, so every mark type is covered and the
  two walks are back in step; the text branch also declines a run with no paint at
  all, and a nil brush is no longer painted. (#71)

- **A Vega-Lite document that will not compile is reported, not reinterpreted as
  Vega.** `ChartSession` fell back to the unconverted text, which a parser that
  only understands Vega then complained about in the wrong grammar's terms —
  measured on a refused construct, three diagnostics about `data`, a missing
  size and nothing to draw, a 0×0 scene published as the chart, and `failure`
  nil. The conversion's own diagnostics are now the chart's, `failure` says why,
  and the chart on screen is left alone. Text that is not Vega-Lite still reaches
  the Vega parser untouched. (#61)

- **A resize only recompiles a chart that asked for one, and no longer on the
  calling thread.** `VegaChartController.containerSize` recompiled
  unconditionally, so a host reporting its layout size on every resize paid a
  full compile per step of a drag for every chart on the page — including every
  chart that states its own width and height. It now skips the compile when the
  loaded document never reads `containerSize()`, and `setContainerSizeAsync` does
  the same work off the calling thread. (#59)
- **`ChartSession.settle()` waits for everything queued, not only the compile.**
  Work `serialised` deferred was started as a task nobody held, so a caller that
  set `containerSize` during a compile and then settled returned before the resize
  had run. The queue is a chain now and `settle()` loops until it is empty. The
  compile is queued with everything else, closing a narrower hazard where a load
  ran beside a deferred touch against a controller documented as unsafe for
  concurrent use. **Setting `ChartSession.containerSize` is asynchronous** — await
  `settle()` where the new scene is needed. (#60)

- **The accessibility summary threshold counts data marks, and the guides survive
  it.** It counted every focusable element, so a chart's axes and legend pushed it
  over before the data was dense — measured: 118 points, two axes and a legend is
  121 focusable elements, and the whole tree collapsed at 118 marks. A reader lost
  per-mark exploration of the entire chart rather than of the crowded part, and
  lost the axes and the legend too. The summary now stands in for the marks alone
  and counts only those, and the threshold is a parameter on every host —
  `AccessibilityTree.elements(maxExposedMarks =)`, the Compose and SwiftUI views,
  and the Android view's `accessibilityMaxExposedMarks`. (#65)

- **`legend.labelExpr` is honoured**, as `axis.labelExpr` already was. Upstream
  destructures it out and writes `encode.labels.update.text` from it on both
  guides; this compiler did it for the axis only, so a `legend.labelExpr`
  survived into the emitted Vega and the *Vega* parser reported it as an unknown
  property two stages later, while the labels were drawn from the scale's domain
  at full length. Since the untruncated labels are what the legend's width is
  computed from, a chart over long category names overflowed whatever held it,
  with no host workaround. (#70)

- **One bytecode level per release.** 0.1.0 published its jars at Java 17 and
  `vega-compose-multiplatform`'s Android AAR at Java 21, because the pin named a
  Kotlin target *type* and the AGP Kotlin Multiplatform Android target is not one
  of those — so the AAR took the level of whichever JDK cut the release. A
  Robolectric test on a JDK 17 runtime, in a build that resolves the Android
  variant, died with `UnsupportedClassVersionError` at the first composable it
  reached. The level is now pinned per compile *task*, so no target can escape it,
  and `checkBytecodeLevel` asserts it against the class files themselves rather
  than against the configuration that was wrong. (#68)

- **`tickCount` in its interval form is no longer warned about.** The field was
  read twice — once as a number, which warned `PARSE_UNKNOWN_PROPERTY` for
  `"day"` or `{"interval": "day", "step": 20}`, and then again, correctly, as an
  interval. A chart was laid out exactly as it asked and complained about at the
  same time: one warning per axis, four on a page, all false. The warning now
  fires only where a reading really fails — an interval that is not a calendar
  unit, or one named by a signal, both of which used to fall through to a
  chosen count in silence. (#69)

- **A document that draws nothing says so.** A specification with no marks, no
  axes, no legends and no title now reports `PARSE_NOTHING_TO_DRAW` at INFO.
  `{"width": 100, "height": 50}` used to compile to a usable, empty chart with
  no diagnostic of any kind, so a host reading "no diagnostics" as "there is a
  chart" could not tell an empty placeholder object from one that drew. The
  message lists the document's own top-level keys, which is what makes a
  Vega-Lite specification handed to the Vega parser — previously silent —
  legible: it reads `mark, encoding`. (#57)

### Added

- **A host can supply its own rules for a locale, not just tables.**
  `VegaLocale.rules: VegaFormatRules?` decides what a name-bearing directive says
  and what digits a number is written with — the two things a lookup table
  provably cannot express, being a contextual name (Polish `stycznia` beside a day
  number, `styczeń` alone) and a numbering system, since the engine wrote
  `value.toString()` and that is ASCII always. It is also where a *device's* own
  preferences can get in. **A specification's format decides the shape and the
  rules decide the details inside it**: `"format": "%b %d, %Y"` keeps that order,
  those fields and those widths whatever a host supplies, and literal text the
  document typed is never passed to a rule. Every method may answer null, and
  `EnglishUS` carries no rules, so the differential comparisons are untouched.

- **The two renderers' draw-call sequences are compared against each other.** Both
  `SceneWalk` implementations claim to emit "the same calls in the same order" and
  nothing checked it — which is how the Apple renderer came to paint labels the
  axis had hidden while Compose did not, with both renderers' own tests green.
  `test-fixtures/scene-walk/*.calls.txt` is written from the Compose walk by
  `SceneWalkGoldenTest` and asserted against the Swift walk by
  `SceneWalkParityTests`. It found a second divergence immediately: the Swift
  `DrawTextRun` carried no `letterSpacing`, so a spaced label was **measured**
  with spacing and **drawn** without it. Fixed. (#71)

- **A host is told which image URL could not be resolved**, once per URL:
  `VegaChart(onUnresolvedImage =)` and `VegaChartView(onUnresolvedImage:)`, both
  additive. Making it usable required fixing something else first — only
  successful decodes were cached, so a URL that had already failed was handed back
  to the host's resolver on **every frame**, and a report fired from the draw
  would have fired with it. Refusals are cached now on both renderers, and
  `ImageCache.clear()` / `CoreGraphicsTarget.clearImageCache()` give a host that
  has recovered another go. `ImageCache.unresolvedImages` is the same facts
  without a callback. (#58)

- **A host can register named font families with the Compose renderer**, through
  `namedFontFamily` or `rememberVegaTextEngine(fontFamilies)`. The default
  resolver matched the generic CSS keywords and nothing else, so a themed
  specification naming a real face was drawn in the platform's default one — and
  said nothing about it. The Apple and Android engines resolve a device family by
  name; common Compose code cannot, so only a host holding the `FontFamily` can.
  `ComposeTextEngine.unresolvedFontFamilies` names what went unresolved, a text
  engine having no diagnostics channel of its own. (#66)

- **`VegaChart(sizing = SceneSizing.Fill)`**, so `fit` has something to do. The
  composable appended `Modifier.size(scene.width.dp, scene.height.dp)`
  unconditionally, so a caller that bounded neither dimension got the scene's own
  size whatever `fit` said — for a `width: "container"` chart, about 300 units plus
  axes in however much room was going. `Fill` takes the slot where there is one
  and falls back to the scene's size where there is not. `SceneSizing.Scene`
  remains the default. (#67)

- **An image resolver on both public view APIs.** `DrawScopeTarget` and
  `CoreGraphicsTarget` have each taken one from the start and neither
  `VegaChart` nor `VegaChartView` had a parameter for it, so a chart with a
  remote image mark drew every other mark and a hole where the image would be,
  with no supported way to supply a fetcher. Exposing it also required the
  Compose decode cache to outlive a frame: a target is built per draw, so a
  heatmap's raster was being re-decoded every frame and a host's fetcher would
  have been called every frame. `ImageCache` and `rememberVegaImageCache` are
  the seam; it is bounded and least-recently-used. (#58)

- **A locale decides the order of a date, not only its names.** `%b` always
  resolved to the reader's month abbreviation, but the *pattern* came from tables
  with no locale in them, so a Dutch axis read `mei 21, 2026`. `VegaLocale.date`
  is d3's `%x` and is now derived from, and `time` likewise for the clock — so a
  locale copied out of a d3 locale JSON reads `21 mei 2026` and gets 24-hour ticks
  without being told to. This is a deliberate **divergence from upstream**, whose
  `timeUnitSpecifier` takes no locale: `VegaLocale.EnglishUS` is pinned to
  upstream's own answers and is the locale every differential comparison runs
  under, which `LocaleDefaultsTest` and the upstream vector replay now assert.
  `timeUnitSpecifierOverrides` and `timeTickFormatOverrides` state a table
  outright where derivation is not wanted. The Vega-Lite compiler carries a locale
  for the first time — the pattern is written on that side — so
  `VegaLiteCompiler`, `VegaLiteInput.toVega` and `Config` take one, and
  `ChartSession` passes its own. `VegaLocale.init`, `doCopy`,
  `TimeUnits.specifier` and `toVega` change signature at the Obj-C boundary, and
  `DateField` is new; recorded in `foreign-api.txt`. (#62)

- **A specification that reads `containerSize()` with no host size supplied now
  says so**, per dimension, as `EXPRESSION_CONTAINER_SIZE_UNANSWERED` at INFO.
  The answer is unchanged — `[null, null]` is what a browser gives outside a
  container and what upstream gives headless — but a document branching on a
  breakpoint used to take its "no container" arm with nothing in the diagnostics
  channel to explain the layout. `CompiledSpec.readsContainerSize` publishes the
  same fact, so a host can decide whether a resize is worth a recompile at all;
  it is false for any chart that declares its own width and height.
  `Expression.functionDependencies` is the new seam behind both. (#63)

- **`usermeta` reaches the host**, as `VegaSpec.usermeta` and so as
  `CompiledSpec.spec?.usermeta`, on Kotlin and Swift alike. It was previously
  discarded with one `usermeta is ignored` warning per compile, whatever it
  carried — so the diagnostic was the whole of the feature, and a document
  carrying supplementary data for the host to consume lost it unconditionally.
  Absent is null and `{}` is an empty map; a non-object `usermeta` is reported
  and dropped. Vega-Lite already carried it onto the Vega it emits, so a
  document in either grammar arrives with it intact. `VegaSpec.init` and
  `doCopy` gain a parameter, which is a new Obj-C signature — recorded in
  `foreign-api.txt`. (#64)

## 0.1.0

First release.

### The engine

A port of Vega to Kotlin Multiplatform, verified differentially against upstream
Vega 6.3.1 rather than against its own expectations: 193 fixtures are compiled by
both engines and compared mark by mark, and the d3 and `vega-*` test suites are
replayed from recorded vectors.

Expressions, signals, 49 of upstream's 51 documented data transforms, every scale
type in scope, geographic projections, and an event handler that recompiles the
chart.

### Vega-Lite

A Vega-Lite compiler, compared **property by property** against upstream
Vega-Lite 6.4.3 on 282 fixtures — not only the picture it draws, because
Vega-Lite's value is in the defaults it supplies and each of those is one property
of the output. All **627** of upstream's own gallery examples compile to the
specification upstream compiles them to.

`VegaLiteInput.toVega` accepts either grammar and says which it read, on every
host.

### Renderers

- Android `Canvas`, with accessibility nodes
- Jetpack Compose, and Compose Multiplatform for Android, iOS and the desktop
- CoreGraphics for Apple platforms, in Swift
- SVG, PNG and PDF export

### Targets

`jvm`, `macosArm64`, `iosArm64`, `iosSimulatorArm64`, `linuxX64`, and Android. No
`iosX64`: `ktecma262` 0.2.0 does not publish that slice, so the target cannot
resolve its dependencies. See the platform table in README.md.

### What a host supplies

Everything the engine cannot know is a **constructor parameter beside the text engine**, and each
one is reachable from Kotlin *and* from Swift:

- **`locale`** — every generated string: month names, number separators, the sentences a screen
  reader is given. `VegaCaptions` is an interface rather than a table, because those sentences are
  grammar. Parsing stays English, d3's parsing being part of the wire format.
- **`timeZone`** — which zone *local* means, for a `time` scale's ticks, `timeunit`'s buckets, the
  local expression functions and the zone a timestamp with no offset in the data is read in. Null is
  the device's own. `utc` forms are untouched.
- **`hostConfig`** — a `config` block the app supplies, which the specification's own beats key by
  key: how a chart drawn on a dark surface is legible when the server chose colours for a white page.
- **`containerSize`** — what `width: "container"` asks for, a responsive width having no answer
  inside the specification.
- **`hostData`** — a table the **app** holds rather than the payload: `setData(name, rows)`, which is
  upstream's `view.data`. The rows arrive where inline values would, so `format.parse` and every
  transform run over them unchanged.

### What a host gets back

- A **scene**, and diagnostics rather than exceptions. `DiagnosticSeverity` says what each level means
  for the chart, and README.md states the policy a host should apply to it.
- **Interaction**, on every renderer: tap, long press, pan, pinch, hover and keys, dispatched into the
  specification's own event handlers. Pan and zoom are drawn, not merely accumulated.
- **Accessibility**: one element per mark, axis, legend and title, positioned so a reader can explore
  by touch — as virtual nodes on Android, as positioned elements in SwiftUI, as semantics in Compose.
- **Tooltips** as rows and text in the chart's own locale, with the anchor to put them at. Drawing the
  bubble is the app's, since what a bubble looks like is a design-system decision.
- **Fonts and text size** from the host: a resolver for a bundled face on every renderer, and the
  reader's text-scale setting applied to measurement *and* drawing.

### Consuming it

Maven Central under `io.github.mgilbir.astervega`, one version for every module,
and a Swift package whose `Package.swift` at tag `vX.Y.Z` names the XCFramework of
that same release — the release workflow writes the checksum and then creates the
tag, so one tag serves both ecosystems.

Every published module carries a committed ABI dump under `api/`, for the JVM
surface and for the native one, checked by `scripts/check.sh`.

### Fixed before the first release

- `mark.clip` on a mark that is not a group was read for bounds and never applied
  when drawing, so a value past the scale's domain was painted over the axis
  instead of being hidden.
- A chart's own `name` was used verbatim where it prefixes every scale, axis and
  signal reference; upstream substitutes non-word characters, so `score-total`
  became `score_total` there and this engine disagreed with it.
- An unknown top-level Vega-Lite property was dropped without a diagnostic, and
  `usermeta` — which upstream carries onto the compiled Vega — was one of them.
- A `$schema` naming another major version of Vega-Lite was compiled with version
  6 rules and said nothing about it.
- A **group** was picked anywhere inside its bounds, so a tap on blank space selected
  the frame every compiled chart wraps its marks in and a host reported one selected
  mark with nothing under the finger. Upstream picks a group only where it paints.
  A mark hidden by a clip was tappable for the same reason, and a rounded bar was
  picked in the corners it does not fill.
- **Pan and zoom updated the controller and did not move the chart** on the Compose
  Multiplatform and Swift renderers, so a gesture made "reset" available and appeared
  to do nothing.
- The Compose Multiplatform renderer took **no pointer input at all**: a mark could be
  activated by a screen reader and not by a finger.
- Three compile inputs — the locale, the host configuration and the container width —
  were **unreachable from Swift**, so three implemented features were available on
  Android only.
- The reader's text-size setting was honoured on Android and in Compose and ignored on
  iOS; a font the app ships could not reach a chart on the Android View at all.
- A `Modifier` passed to the Compose Multiplatform chart could not make it any bigger
  than its scene, which made the default fit mean nothing.
- The derived label a screen reader reads spoke a raw epoch number on a time axis, and
  then — briefly — a datum rounded to the axis's tick precision, which is a nicer label
  and a false statement about one measurement.
