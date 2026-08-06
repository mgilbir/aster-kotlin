# Status

Last updated: 2026-08-06

## Current milestone

Milestones 0, 1 and 2 complete. **Milestones 3 and 4 in progress**: Vega specifications compile end to
end — including expressions, signals and the twelve data transforms the brief lists — and are verified
against upstream Vega by differential tests.

Eight differential fixtures pass, all matching upstream exactly on every mark and scale output:

| Fixture | Marks | Covers |
| --- | --- | --- |
| `bar` | 48 | band and linear scales, rect encoder, axes |
| `stacked-bar` | 42 | stack and aggregate transforms, signals, signal-valued scale property, conditional fill, gridlines |
| `line-area` | 50 | line, area, symbol and text encoders |
| `log-scale` | 75 | log axis with blanked labels, sqrt scale |
| `colour-scheme` | 39 | ordinal category10 scheme, interpolated sequential colour |
| `facet-trellis` | 55 | faceted group marks, nested scopes, per-cell scales and axes, the `parent` signal |
| `legends` | 28 | a symbol legend beside the chart and a gradient legend below it |
| `titles` | 28 | chart title and subtitle, titles on both axes, all placed against the whole drawing |

The gate is wired into `./scripts/oracle.sh`, so every further scale, mark and transform is built
against a harness that can say we are wrong — which golden tests cannot.

## Scope: how much of Vega this is

Measured against the pinned upstream packages in `oracle-js/node_modules`, so these numbers are
checkable rather than estimated.

Upstream Vega is 31 packages, roughly 28,500 lines of source, and leans on about 35,000 further lines
of `d3-*` code (d3-scale, d3-shape, d3-time-format, d3-array, d3-interpolate) that a native port has to
reimplement. It exposes **40 transforms, 119 expression functions, 12 mark types and ~15 scale types.**

This repository is around 9,000 lines of main source and 5,000 of tests. It covers the **output half**
of the pipeline, the testing and diagnostic infrastructure, and — as of Milestone 3 — a thin but
upstream-verified slice through parsing, scales, rect encoding and axes:

| Built here | Upstream equivalent | State |
| --- | --- | --- |
| Scene graph, geometry, paths, hit index | part of `vega-scenegraph` (4,994) | 7 of 12 node types; rendering and hit-testing side only. All 12 symbol shapes, pinned to upstream |
| Canvas renderer, SVG serializer | rest of `vega-scenegraph` | complete for those 7 |
| Diagnostics, canonical snapshots, goldens, oracle scaffolding | no upstream equivalent | complete |
| `vega-scale` (linear, band, point, ordinal) + d3-array ticks | 790 + parts of d3-scale, d3-array | 4 of ~15 scale types, exact against upstream |
| `vega-parser` (width, height, padding, autosize, data, signals, scales, axes, marks, group scopes) | 3,790 | a subset; no legends, titles or `layout` |
| `vega-encode` (mark encoders, axes, legends, titles) | 952 | 7 of 12 mark encoders; axes, legends and titles without overlap removal |
| `vega-expression` + `vega-functions` | 2,388 | language complete; 60 of 119 functions |
| `vega-transforms` (12 of 40) | 3,754 | the 12 the brief lists, exact against upstream |
| `vega-dataflow` | 2,081 | contracts and scheduling only; no pulse propagation |

The entire data and specification half is absent:

| Missing | Upstream size | Here |
| --- | --- | --- |
| `vega-transforms` — the other 28 transforms | most of 3,754 | 0 |
| `vega-dataflow` — pulse propagation and incremental evaluation | 2,081 | contracts only |
| `vega-functions` — the other 59 functions, mostly date, colour, geo and selection | most of 790 | 0 |
| Remaining scale types — time, utc, quantile, quantize, threshold, bin-ordinal | rest of `vega-scale` + d3-time | 0 |
| Continuous colour ramps | `d3-scale-chromatic` interpolator tables | 0, reported |
| Remaining mark encoders — arc, image, path, trail, shape | rest of `vega-encode` + d3-shape | 0 |
| Group `layout` — the trellis grid, headers and titles | `vega-view-transforms` grid layout | 0, reported |
| Line and area interpolation methods — basis, cardinal, catmull-rom, monotone, step | part of d3-shape | 0, reported |
| Label overlap removal, banded legends, group `layout` | parts of `vega-encode`, `vega-label`, `vega-view-transforms` | 0, reported |
| `vega-view`, `vega-view-transforms` — layout, overlap removal | 2,623 | bounds only |
| `vega-event-selector` — event-stream DSL | 191 | 0 |
| `vega-time`, `vega-format` — locale and time units | 587 + d3-format, d3-time-format | 0 |
| geo, force, hierarchy, label, voronoi, wordcloud, crossfilter, statistics | ~5,700 | 0, and mostly explicit non-goals (PROJECT_BRIEF.md 3.3) |

Full parity was never the goal — PROJECT_BRIEF.md 3.3 rules most of that last row out. But the brief's
own MVP definition (section 23) stands at roughly **6.5 of its 15 criteria**, and the unmet ones are the
substantive compatibility items:

| MVP criterion | State |
| --- | --- |
| 1. Compiled Vega JSON loads without JavaScript | **Yes**, for a substantial subset — `VegaChartController.setSpec` loads it and the demo renders three bundled specifications on device |
| 2. Bar, line, area, scatter, stacked bar render natively | **Yes** — all five compile from a specification, and small multiples of them too |
| 3. Axes, legends, labels and titles supported | **Yes** — all four |
| 4. Basic transforms and scales execute in Kotlin | **Yes** — 8 scale types and the 12 transforms the brief lists |
| 5. Tap, hover, tooltip, selection, pan, zoom | Yes, except tooltip rendering |
| 6. View and Compose APIs | Yes |
| 7. SVG, PNG, PDF export | Yes |
| 8. TalkBack can describe and navigate | Partial — virtual nodes are tested by instrumentation, not with TalkBack itself |
| 9. At least 100 compatibility fixtures pass | 8 of 100 |
| 10. Core runtime has no Android dependency | Yes |
| 11. Renders without WebView | Yes |
| 12. Build and test loop runs from the terminal | Yes |
| 13. Performance measured on a physical device | No |
| 14. Unsupported features produce explicit diagnostics | Yes, in the areas that exist |
| 15. Instructions to reproduce from a clean macOS install | Yes |

Remaining work for the MVP subset — excluding the non-goals — is on the order of 8,000 more lines of
Kotlin plus the fixture corpus. The foundation was built first deliberately (PROJECT_BRIEF.md milestone
ordering), and the pipeline is now verified end to end on one fixture, but the bulk of Vega's behaviour
— expressions, dataflow, transforms, the other eleven mark types — is still ahead.

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

### Milestone 4 (in progress) — expressions, signals and transforms

- **Expression language**: lexer, precedence-climbing parser and tree-walking evaluator for the full
  JavaScript expression subset Vega uses. No `eval`, no reflection, no generated bytecode.
- **JavaScript coercion semantics** in `JsSemantics`, pinned by 115 reference vectors from upstream.
- **60 of upstream's 119 functions**, with the excluded ones reporting by name and reason.
- **Signals**: `update` beats `init` beats `value`, dependency-ordered, `width`/`height`/`padding`
  implicit, cycles reported as the path that closed them.
- **Conditional encode rules** (`[{test, ...}, {...}]`) for every channel kind.
- **Signal-valued scale and axis properties** via a `NumberValue` model.
- **Twelve transforms**: filter, formula, collect, project, identifier, extent, aggregate,
  joinaggregate, bin, stack, fold, flatten — with the bin step algorithm ported from vega-statistics.
- **Full CSS named-colour table**, replacing a subset that silently failed on `firebrick`.

### Milestone 5 (in progress) — the specification reaches the screen

- **`VegaChartController.setSpec`** compiles a specification and publishes its scene, rebuilding the
  hit index so a newly loaded chart is tappable straight away. `setSpecAsync` moves compilation off
  the caller's thread.
- **The demo loads three bundled specifications** from its assets — the same fixtures the differential
  tests compare against upstream — so what the device draws is what was proved correct.
- **`scripts/emulator.sh`** starts the project AVD with a window and installs the demo.

### Milestone 3 (in progress) — scales, specification parsing and the differential harness

- **Tick generation** ported from d3-array, including the negative-reciprocal `tickIncrement`
  convention that keeps fractional steps exact. Reference vectors generated from the pinned d3.
- **Scales**: linear (with piecewise domains, clamping, invert), band, point and ordinal. Band step,
  padding, align and round match d3-scaleBand exactly.
- **Specification model and parser** for width, height, padding, autosize, data, scales, axes and
  marks, with a JSON path on every diagnostic.
- **Scale resolution** from data, applying Vega's `zero` then `nice` ordering, with `zero` defaulting to
  true for data-driven quantitative domains.
- **Rect mark encoder** handling Vega's x/x2/width and y/y2/height channel pairs, band offsets and
  paint channels.
- **Group marks and faceting**: a group nests its own data, signals, scales, axes and marks, each able
  to shadow a same-named definition outside it. `from.facet` partitions a dataset by its `groupby`
  fields — one cell per distinct combination, in first-appearance order — and binds the partition to the
  facet name, so a cell's scales resolve against its own rows. Two upstream behaviours are reproduced
  deliberately rather than corrected: `parent` is the group's aggregate *datum*, not the group item, so
  `parent.width` is undefined; and `width`/`height` are inherited rather than redefined, so a nested
  `"height"` range spans the whole chart unless the group declares its own `height` signal.
- **Axis generation**: ticks, labels, gridlines and domain lines for all four orientations, reproducing
  Vega's half-pixel crisp offset and whole-pixel tick rounding.
- **Legends**: symbol legends and gradient legends, at any of the four edges, the four corners, or an
  absolute position; titled; stacking when several share an orientation. Every constant comes from
  upstream's `config.legend` and is pinned by a test, because legend layout is pure arithmetic on those
  numbers — a row is `max(ceil(sqrt(symbolSize) + symbolStrokeWidth), labelFontSize)` tall, and a
  gradient is sampled at the scale's own ticks so a multi-stop ramp bends where upstream's does.
- **Titles**: a chart title and subtitle at any of the four edges and any of the three anchors — all
  twelve combinations verified against upstream — plus a title on each axis. A title is placed against
  the whole drawing rather than the plotting area, so a chart with wide y-axis labels has its title
  visibly off the plot's centre, exactly as upstream draws it.
- **Layout**: `autosize: pad` and `none`, sizing the surface from the drawing's reach plus padding.
- **Differential harness**: `oracle-js/src/reference.js` emits a normalized comparison model, checked in
  under `test-fixtures/reference/`; `Differential` flattens our scene the same way and compares.
  `scripts/oracle.sh` regenerates references and runs the comparison.
- **`VegaHeadlessTextEngine`** reproduces upstream's canvas-free text estimate so layout is comparable
  on the JVM. A comparison engine only.

## Verification

- 642 JVM tests pass (`./scripts/test-core.sh`, `./gradlew test`).
- Android lint is clean with `warningsAsErrors` on every Android module.
- 48 instrumented tests pass on an API 37 arm64 emulator (`./scripts/test-android.sh`): 40 in
  `vega-android-canvas`, 4 in `vega-compose`, 4 in `demo` — including one that compiles every bundled
  specification with the device's own font metrics, which the differential tests cannot cover because
  they deliberately measure text upstream's way.
- The demo was installed and driven on the emulator: all nine chart entries render, marks are
  selectable by tap, light and dark palettes are legible, and SVG/PNG/PDF export all wrote files with
  zero warnings. The three specification entries load Vega JSON from assets, compile it on a
  background thread and report zero diagnostics; tapping a bar on a compiled chart selects it, which
  is what proves the hit index is rebuilt when a specification is published. `scripts/emulator.sh`
  starts the AVD with a window and installs the demo, for looking at rather than only asserting on.

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

None. Six fixtures exist and all six pass. The brief's MVP asks for 100; growing the corpus is the main
task now, and each new fixture is expected to surface gaps rather than pass immediately. That keeps
happening, which is the point of the harness: `stacked-bar` surfaced two real bugs, and `facet-trellis`
surfaced a third — `range: "height"` was descending for every scale type, where upstream ascends for a
discrete one. A row-faceted trellis was therefore upside down, and nothing but a differential fixture
would have said so.

Building legends surfaced a fourth, and a worse one, in code that had been "passing" for six fixtures:
**every symbol was the wrong size.** Upstream ships its own symbol table rather than d3-shape's, sizing
each shape from `sqrt(size) / 2` where d3 sizes by area, so our circles were 13% too wide and cross,
diamond and the triangles were each wrong in their own way. The harness could not see it because it
compared a symbol's `size` channel — the requested size — and never the outline. Both sides now report
the drawn extent, and the twelve shapes are pinned by reference vectors.

**The surface size now matches upstream exactly on all eight fixtures**, where it had been documented
since Milestone 3 as up to a unit larger per axis. Closing it meant reproducing three separate things
upstream does when it measures a chart, none of which show up in any single mark's coordinates: an axis
is measured by its extent rather than by the items it drew, gridlines are excluded from that
measurement, and a stroked path reserves four stroke widths for a miter join rather than the ten a
canvas defaults to. Building titles is what forced the issue — a title is placed against that
measurement, so it could not be approximated.

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
4. ~~**Wiring the compiler into the controller.**~~ **Settled.** `setSpec` compiles on the calling
   thread and `setSpecAsync` compiles on a dispatcher, with compilations serialized so one text engine
   is only ever used by one at a time. Diagnostics *replace* rather than accumulate, since they
   describe the specification now loaded. A specification that produces no scene leaves the chart
   alone and explains why, rather than blanking it.
5. ~~**Text measurement on the compile thread.**~~ **Settled**, by the cheapest correct route:
   per-instance ownership rather than locking. `AndroidTextEngine` keeps one shared `TextPaint` and
   the renderer hands it out mid-draw, so a lock inside the engine would not have helped. Instead a
   controller that compiles gets its own instance — `VegaChartView.newCompatibleTextEngine()` makes
   that the easy thing to do — and two instances configured alike measure alike, so the layouts still
   match what the surface draws.

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

1. **Grow the fixture corpus.** The pipeline is now end to end — JSON in, chart on a device — and 8 of
   the brief's 100 fixtures pass. Every fixture so far has found something no unit test would have:
   an upside-down trellis, a whole wrong symbol table, nine units of phantom chart. That rate is the
   argument for making this the priority over any single new feature.
2. **Grid layout, shared by group `layout` and legend entry columns.** The automatic trellis grid —
   `layout` with columns, padding, headers — and a legend's multi-column entry grid are the same row
   and column algorithm, and both are currently reported. Doing them together is why this is one task
   rather than two.
3. **Time and UTC scales.** A date and time layer: parsing, `timeunit`, tick intervals from second to
   year, and locale-aware formatting. Also unblocks the date expression functions and the `timeunit`
   transform, both currently reported. Substantial, and it touches formatting everywhere.
