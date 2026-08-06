# Status

Last updated: 2026-08-06

## Current milestone

Milestone 2 complete (SVG renderer). Milestone 0 (bootstrap) and Milestone 1 (scene graph and Canvas
renderer) are also complete. Milestone 3 (scales and Vega JSON parsing) is next and not started.

## Completed work

### Milestone 0 — repository bootstrap

- Gradle 9.5.0 build with all twelve modules, a version catalog, and Spotless/ktfmt formatting.
- Pure-Kotlin core (`vega-model`, `vega-expression`, `vega-dataflow`, `vega-scene`, `vega-runtime`,
  `vega-svg`, `test-fixtures`) with `explicitApi()` and `allWarningsAsErrors`.
- Android modules (`vega-android-canvas`, `vega-compose`, `demo`, `benchmark`).
- `scripts/` for check, core tests, Android tests, demo install, screenshot capture, benchmarks,
  oracle and SDK setup.
- Pinned Node oracle project with canonicalization utilities.
- `./gradlew test lint :demo:assembleDebug` passes from a clean checkout.

### Milestone 1 — scene graph and Canvas renderer

- Immutable scene graph: `GroupNode`, `RectNode`, `RuleNode`, `PathNode`, `SymbolNode`, `TextNode`,
  `ImageNode`, with lazily computed tight bounds including stroke extents.
- Geometry: `RectD`, `PointD`, `VectorD`, `SizeD`, `Transform2D` (concat, invert, bounds mapping).
- Paths: quadratics and circles converted to cubics, tight cubic bounds from derivative roots,
  adaptive flattening, even-odd containment, distance-to-outline.
- Symbol shapes matching d3-shape proportions (area-based sizing).
- Platform-neutral `TextEngine`, deterministic `MetricTextEngine` for JVM tests, bounded
  `TextLayoutCache`.
- Hit testing: linear scan for small scenes, uniform grid above a configurable threshold, separate
  mouse and touch tolerances that do not change visual bounds.
- Canonical scene snapshot serialization with golden tests.
- `AndroidCanvasSceneRenderer` with renderer-owned `Paint`/`Path`/`Matrix` reuse, gradients, dashes,
  clipping, blend modes, and diagnostics for anything it cannot represent.
- `AndroidTextEngine` using `TextPaint`/`StaticLayout` for both measurement and drawing.
- `VegaChartView` with gesture, hover, wheel and keyboard translation, revision-based invalidation,
  virtual accessibility descendants via `ExploreByTouchHelper`, and a snapshot subscription so a
  change made through the controller repaints.
- `VegaChartController.contentScale`, the host's fit-to-viewport factor, which hit testing inverts.
- `SceneExporter` for bitmap, PNG and PDF.
- `VegaChart` Composable hosting the canonical View.
- Demo app rendering bar, stacked bar, line, area, scatter and a 10,000-symbol stress test, with
  light/dark backgrounds and SVG/PNG/PDF export.

### Milestone 2 — SVG renderer

- Deterministic `SvgRenderer` covering every Milestone 1 node type: stable generated ids, XML
  escaping, canonical numeric formatting, clip paths, gradients, transforms, multiline text via
  `tspan`, image policy, optional accessibility attributes.
- Golden tests for all five sample charts, plus a test asserting the SVG mark count equals the
  scene's drawable node count.
- Well-formedness verified by parsing every generated document.

## Verification

- 197 JVM tests pass (`./scripts/test-core.sh`, `./gradlew test`).
- Android lint is clean with `warningsAsErrors` on every Android module.
- 47 instrumented tests pass on an API 37 arm64 emulator (`./scripts/test-android.sh`): 40 in
  `vega-android-canvas`, 4 in `vega-compose`, 3 in `demo`.
- The demo was installed and driven on the emulator: all six chart entries render, marks are
  selectable by tap, light and dark palettes are legible, and SVG/PNG/PDF export all wrote files with
  zero warnings. Screenshots are under `build/artifacts/`.

Four defects were found by running on a device rather than by the test suite, and all four are fixed
with regression tests:

1. **The view never repainted after a controller-driven change.** `controller.setScene(...)` published
   a new snapshot with nothing telling the view to invalidate, so the demo rendered a blank chart.
   `VegaChartView` now subscribes to `controller.state` while attached.
2. **Hit testing ignored the fit-to-viewport scale.** The view draws the scene scaled to fit, but the
   controller inverted only the interactive zoom, so every tap missed by exactly the fit factor.
   `VegaChartController.contentScale` is now part of the host contract, and the accessibility helper
   reuses the same value so exploration and visual hit testing cannot diverge.
3. **The Compose event subscription restarted on every recomposition.** `LaunchedEffect` was keyed on
   the `onEvent` lambda, which is a fresh instance each composition; events were dropped. It is now
   keyed on the controller with `rememberUpdatedState`.
4. **The demo drew under the system bars**, making the top row of controls untappable. It now applies
   `safeDrawingPadding()`.

Two smaller fixes came out of the same pass: `VegaAccessibilityHelper` was adding
`ACTION_ACCESSIBILITY_FOCUS`, which `ExploreByTouchHelper` rejects; and the sample scenes hard-coded
dark chrome, so a dark background was unreadable — they now take a `SampleScenes.Palette`.

## Known failing fixtures

None. There are no differential fixtures yet: `scripts/oracle.sh` produces reference output from
upstream Vega and exits non-zero rather than reporting a vacuous pass, because the Kotlin side cannot
consume a Vega specification until Milestone 3.

## Performance observations

None recorded. No measurement has been taken on physical hardware yet, and emulator numbers are not
authoritative (PROJECT_BRIEF.md 18.6). The benchmark module and fixtures exist
(`benchmark/src/androidTest`, `scripts/benchmark.sh`) but have not been run on a device.

The performance targets in PROJECT_BRIEF.md 19 are therefore all unverified.

## Architectural decisions pending

1. **Incremental scene diffing.** `Scene` carries a revision and the controller republishes whole
   snapshots. Whether to add node-level diffing, and whether renderers should consume a diff instead
   of a snapshot, is open until Milestone 4 shows how often data changes touch few marks.
2. **Interval-selection representation.** `ChartSelection.interval` is a scene-space `RectD`. Whether
   selections should instead be expressed in data space (so they survive a scale change) needs to be
   settled with the signal system in Milestone 6.
3. **Pan/zoom placement.** Pan and zoom currently live in `InteractionState` as a view transform,
   which keeps static content from being rebuilt but means axes do not rescale. Vega-style zoom that
   updates scale domains needs the dataflow, so this stays a view transform until Milestone 6.
   Related: `contentScale` is host-pushed state on the controller rather than something the controller
   derives. That is the simplest correct arrangement for one surface per controller, but it becomes
   wrong the moment two surfaces of different sizes share a controller. Decide whether the transform
   belongs in a per-surface object when the Compose `DrawScope` backend is considered (Milestone 8).
4. **Text measurement on the compile thread.** `AndroidTextEngine` is not thread-safe. If scene
   compilation moves off the main thread, either the engine needs per-thread instances or measurement
   needs to be hoisted into a separate pass. Decide with Milestone 5.

## Deviations from PROJECT_BRIEF.md

- **JDK.** The brief pins JDK 17. The build runs on the JDK available on the development machine
  (21) and targets JVM bytecode 17 through `jvmTarget`/`sourceCompatibility` rather than a Gradle
  toolchain, so no JDK is auto-downloaded. Any JDK 17 or newer works.
- **`compileSdk`/`targetSdk` 37.** API 37 ships as minor-versioned platforms; the setup script
  installs both `android-37.0` and `android-37.1`.
- **`androidx.benchmark` Gradle plugin.** Version 1.4.1 is incompatible with AGP 9.3.1 (it reads the
  removed `TestedExtension`). The benchmark module uses `benchmark-junit4` with
  `AndroidBenchmarkRunner` directly, without the plugin, so it compiles and runs but does not get the
  plugin's result-pulling and configuration checks. Revisit when a compatible plugin release lands.
- **`org.jetbrains.kotlin.android` plugin.** Not applied: AGP 9 has built-in Kotlin support and
  rejects the plugin.
- **Espresso and the Compose test rule.** `createComposeRule` idles through Espresso, and Espresso
  3.7.0 (the newest stable) crashes on API 37 with
  `NoSuchMethodException: android.hardware.input.InputManager.getInstance`. `VegaChartComposeTest`
  therefore drives composition through `ActivityScenario` instead. Nothing there needs Compose's
  semantics tree or gesture injection, so this costs no coverage; revisit when a compatible Espresso
  release ships.
- **Emulator system image.** The brief's example path `system-images;android-37;google_apis;arm64-v8a`
  does not exist; the published path is `system-images;android-37.0;google_apis;arm64-v8a`.
- **JUnit 5 `5.13.4`, Spotless `8.9.0`, ktfmt `0.64`, AGP `9.3.1`, Kotlin `2.4.10`, Compose BOM
  `2026.06.01`** all exist as pinned in the brief and are used unchanged.

## Next three tasks

1. **Milestone 3, part 1: Vega specification models and parsing.** Parse `width`, `height`,
   `padding`, `autosize`, `data`, `scales`, `axes` and `marks` into `vega-model` types with source
   locations, emitting `VEGA_PARSE_*` diagnostics for anything unsupported.
2. **Milestone 3, part 2: scales.** Linear, band, point and ordinal scales in `vega-runtime`, with
   unit tests for reversed domains, zero-length domains, `nice`, and out-of-domain values.
3. **Milestone 3, part 3: mark encoding and the differential harness.** Encode rect, line, symbol and
   text marks from a parsed specification, then wire `scripts/oracle.sh` step 5 to compare the Kotlin
   scene against upstream Vega for `test-fixtures/specs/bar.vg.json` and grow to 20 fixtures.
