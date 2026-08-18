# Changelog

Notable changes, newest first. The release workflow reads the section for the
version it is publishing and uses it as the release notes, so a version without a
section here does not get released.

## 0.1.0

First release.

### The engine

A port of Vega to Kotlin Multiplatform, verified differentially against upstream
Vega 6.3.1 rather than against its own expectations: 191 fixtures are compiled by
both engines and compared mark by mark, and the d3 and `vega-*` test suites are
replayed from recorded vectors.

Expressions, signals, 50 of upstream's 51 data transforms, every scale type in
scope, geographic projections, and an event handler that recompiles the chart.

### Vega-Lite

A Vega-Lite compiler, compared **property by property** against upstream
Vega-Lite 6.4.3 on 281 fixtures — not only the picture it draws, because
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

`jvm`, `macosArm64`, `iosArm64`, `iosSimulatorArm64`, `linuxX64`, and Android.
