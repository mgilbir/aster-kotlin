# Changelog

Notable changes, newest first. The release workflow reads the section for the
version it is publishing and uses it as the release notes, so a version without a
section here does not get released.

## Unreleased

### Fixed

- **One bytecode level per release.** 0.1.0 published its jars at Java 17 and
  `vega-compose-multiplatform`'s Android AAR at Java 21, because the pin named a
  Kotlin target *type* and the AGP Kotlin Multiplatform Android target is not one
  of those — so the AAR took the level of whichever JDK cut the release. A
  Robolectric test on a JDK 17 runtime, in a build that resolves the Android
  variant, died with `UnsupportedClassVersionError` at the first composable it
  reached. The level is now pinned per compile *task*, so no target can escape it,
  and `checkBytecodeLevel` asserts it against the class files themselves rather
  than against the configuration that was wrong. (#68)

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
