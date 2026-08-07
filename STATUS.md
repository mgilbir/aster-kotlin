# Status

Last updated: 2026-08-07

## Picking this up

- **Branch `milestone-0-bootstrap`. Nothing has been pushed, and nothing goes on `main`.** The name is
  now historical; the work has run well past Milestone 0.
- Read `CONTRIBUTING.md` first. The method matters more than the remaining feature list: probe upstream
  before implementing, add a fixture and expect it to fail, report anything unsupported by name.
- `./scripts/check.sh` is the gate — format, all tests, lint, demo APK. `./scripts/oracle.sh`
  regenerates the upstream references and runs the differential comparison; both must be green.
- The next three tasks are at the bottom of this file. They are re-decided every time, not worked
  through in order — twice now the stated order was wrong and the reason is recorded in the commit
  that changed it.

## Current milestone

Milestones 0, 1 and 2 complete. **Milestones 3 and 4 in progress**: Vega specifications compile end to
end — including expressions, signals and the twelve data transforms the brief lists — and are verified
against upstream Vega by differential tests.

Thirty-three differential fixtures pass, all matching upstream exactly on every mark and scale output:

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
| `histogram` | 33 | the bin transform feeding an aggregate, gridlines |
| `dot-plot` | 28 | point scale, rule marks, four symbol shapes, a right-hand axis |
| `text-anchors` | 18 | every align and baseline, rotation, `dx`/`dy` |
| `expressions` | 26 | a signal-valued domain and padding, filter, formula, extent, conditional fill |
| `nested-groups` | 14 | a group inside a group, clipped, driven by plain data rather than a facet |
| `size-legend` | 36 | a size legend of growing swatches, and a horizontal one |
| `reshape` | 30 | fold, project, collect with a two-key sort, identifier |
| `share-of-total` | 26 | joinaggregate written back onto every row |
| `area-gaps` | 22 | a band area between two fields, and a line through a null |
| `axis-variants` | 34 | a top axis, an offset one, a grid above the marks, one with no domain or ticks |
| `band-padding` | 22 | inner and outer padding, alignment, rounding, a reversed range |
| `flatten-arrays` | 25 | parallel array fields expanded and re-aggregated |
| `time-axis` | 32 | a UTC scale ticking on months and another on hours, ISO dates read by `format.parse` |
| `timeunit` | 25 | rows bucketed into calendar months, then counted |
| `legend-columns` | 10 | legend entries wrapped into two columns |
| `trellis-layout` | 10 | five cells gridded by a layout, with row and column padding |
| `trellis-headers` | 8 | a grid with row and column headers, each titled from its own datum |
| `pie` | 13 | a donut chart: the pie transform feeding arc marks, with a legend |
| `sorted-domain` | 35 | bands ordered by a sum they never carry, colours ordered numerically |
| `box-plot` | 32 | min, q1, median, q3 and max from one aggregate, drawn as whiskers and a box |
| `stack-offsets` | 32 | a normalized stacked area, its series faceted out of the stacked rows |
| `stack-diverging` | 45 | negatives stacking away from zero, over a domain taken from both bounds |
| `axis-style` | 37 | every part of an axis restyled: colour, width, dash, opacity, italic labels |
| `domain-limits` | 37 | a domain pinned by `domainMin`/`domainMax` beside one written out in full |
| `legend-style` | 30 | a restyled legend beside dashed, round-capped marks |

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
| `vega-encode` (mark encoders, axes, legends, titles) | 952 | 8 of 12 mark encoders; axes, legends and titles without overlap removal |
| `vega-expression` + `vega-functions` | 2,388 | language complete; 60 of 119 functions |
| `vega-transforms` (14 of 40) | 3,754 | the 12 the brief lists plus `timeunit` and `pie`, exact against upstream |
| `vega-dataflow` | 2,081 | contracts and scheduling only; no pulse propagation |

The entire data and specification half is absent:

| Missing | Upstream size | Here |
| --- | --- | --- |
| `vega-transforms` — the other 26 transforms | most of 3,754 | 0 |
| `vega-dataflow` — pulse propagation and incremental evaluation | 2,081 | contracts only |
| `vega-functions` — the other 44 functions, mostly colour, geo and selection | most of 790 | 0 |
| Remaining scale types — quantile, quantize, threshold, bin-ordinal | rest of `vega-scale` | 0 |
| Continuous colour ramps | `d3-scale-chromatic` interpolator tables | 0, reported |
| Remaining mark encoders — image, path, trail, shape | rest of `vega-encode` + d3-shape | 0 |
| Group `layout` — the trellis grid, headers and titles | `vega-view-transforms` grid layout | 0, reported |
| Line and area interpolation methods — basis, cardinal, catmull-rom, monotone, step | part of d3-shape | 0, reported |
| Label overlap removal, banded legends, trellis footers | parts of `vega-encode`, `vega-label`, `vega-view-transforms` | 0, reported |
| `vega-view`, `vega-view-transforms` — layout, overlap removal | 2,623 | bounds only |
| `vega-event-selector` — event-stream DSL | 191 | 0 |
| `vega-time`, `vega-format` — `timeunit`, locales, format strings | 587 + d3-format, d3-time-format | tick selection and default labels only |
| geo, force, hierarchy, label, voronoi, wordcloud, crossfilter, statistics | ~5,700 | 0, and mostly explicit non-goals (PROJECT_BRIEF.md 3.3) |

Full parity was never the goal — PROJECT_BRIEF.md 3.3 rules most of that last row out. But the brief's
own MVP definition (section 23) stands at roughly **6.5 of its 15 criteria**, and the unmet ones are the
substantive compatibility items:

| MVP criterion | State |
| --- | --- |
| 1. Compiled Vega JSON loads without JavaScript | **Yes**, for a substantial subset — `VegaChartController.setSpec` loads it and the demo renders three bundled specifications on device |
| 2. Bar, line, area, scatter, stacked bar render natively | **Yes** — all five compile from a specification, and small multiples of them too |
| 3. Axes, legends, labels and titles supported | **Yes** — all four |
| 4. Basic transforms and scales execute in Kotlin | **Yes** — 10 scale types, including time and UTC, and the 12 transforms the brief lists |
| 5. Tap, hover, tooltip, selection, pan, zoom | Yes, except tooltip rendering |
| 6. View and Compose APIs | Yes |
| 7. SVG, PNG, PDF export | Yes |
| 8. TalkBack can describe and navigate | Partial — virtual nodes are tested by instrumentation, not with TalkBack itself |
| 9. At least 100 compatibility fixtures pass | 33 of 100 |
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
- **75 of upstream's 119 functions**, with the excluded ones reporting by name and reason.
- **Signals**: `update` beats `init` beats `value`, dependency-ordered, `width`/`height`/`padding`
  implicit, cycles reported as the path that closed them.
- **Conditional encode rules** (`[{test, ...}, {...}]`) for every channel kind.
- **Signal-valued scale and axis properties** via a `NumberValue` model.
- **Thirteen transforms**: filter, formula, collect, project, identifier, extent, aggregate,
  joinaggregate, bin, stack, fold, flatten and `timeunit` — with the bin step algorithm ported from
  vega-statistics.
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
- **Arc marks and the `pie` transform**: pie and donut charts, which were the commonest chart the
  engine could not draw at all. `padAngle` and `cornerRadius` are reported rather than approximated.
- **Trellis headers and titles**: a group marked `row-header` or `column-title` is arranged around
  the grid rather than gridded into it, each labelled from its own datum through a group `title` whose
  text is a signal.
- **Grid layout**: `layout` places a group mark's cells on a row-and-column grid, and a legend's
  `columns` wraps its entries the same way — one algorithm, which is why they were done together. A
  multi-column legend fills down each column before moving across, the order a reader scans a list.
- **Discrete domain ordering**: `sort` on a data-driven discrete domain, in every form upstream
  accepts — by the domain value, or by an aggregate of another field entirely. Upstream *groups* the
  dataset on the domain field rather than listing its values, which is what lets a bar chart be
  ordered by a total no row carries, and what makes a multi-field domain run field by field rather
  than row by row.
- **Axis appearance**: `labelColor`, `tickColor`, `gridColor`, `domainColor` and `titleColor`, each
  with its own width, dash, opacity and font — the five parts of an axis are the same property with a
  different prefix upstream, and are read that way here. A label's colour is a fill and every other
  part's is a stroke, which is the only asymmetry and the one a renderer cannot paper over.
- **`domainMin`, `domainMax` and `domainMid`**, which is how a chart is pinned to a fixed range
  instead of rescaling with its data. Implementing them meant getting `zero` right first: it follows
  the **scale type** — linear, pow and sqrt, and nothing else — not whether the domain was written
  out, so a linear scale handed `[10, 20]` still starts at 0 where this engine had been leaving it
  at 10. The limits then replace an end rather than clamping it, and run after `zero`, which is why
  `domainMin: 30` beats it.
- **Legend appearance**, through the same `GuideStroke` the axis uses: label and title colour,
  font, weight, style and opacity, plus `symbolStrokeColor`, `symbolDash` and `symbolOpacity`. The
  last is the odd one — upstream makes it the swatch's *overall* opacity rather than a fill or
  stroke opacity, so it fades the outline along with what is inside it.
- **`strokeDash`, `strokeCap` and `strokeJoin` on a mark**, which the scene graph and both renderers
  already had; only the encoder was not reading them.
- **Nothing dropped in silence.** Each parser now names the properties it *consumes* and reports the
  remainder, rather than a hand-kept list of what is missing. That inversion is the point: the
  hand-kept list is how the gap grew — the axis honoured fifteen of upstream's 74 properties and
  ignored fifty-nine, each arriving at a moment when nobody was looking at the whole list. Scales,
  legends, titles, marks and encode channels are covered the same way, so a property nobody
  anticipated, including one upstream adds later, becomes a diagnostic. Where there is something
  specific to say about what will be drawn instead, it is said by name.
- **Titles**: a chart title and subtitle at any of the four edges and any of the three anchors — all
  twelve combinations verified against upstream — plus a title on each axis. A title is placed against
  the whole drawing rather than the plotting area, so a chart with wide y-axis labels has its title
  visibly off the plot's centre, exactly as upstream draws it.
- **Time and UTC scales**: ticks land on calendar boundaries chosen from d3's own table, `nice` widens
  to one of them, and each label is written at its *own* granularity — so a monthly axis puts the year
  back every January and an hourly one puts the date back every midnight. Dates arrive as ISO strings
  and are read by `format.parse`. Calendar arithmetic goes through `kotlinx-datetime`, which keeps the
  core portable to Kotlin Multiplatform; a day is a calendar day, so a daily tick stays at midnight
  across a daylight-saving change rather than drifting to 01:00.
- **Layout**: `autosize: pad` and `none`, sizing the surface from the drawing's reach plus padding.
- **Differential harness**: `oracle-js/src/reference.js` emits a normalized comparison model, checked in
  under `test-fixtures/reference/`; `Differential` flattens our scene the same way and compares.
  `scripts/oracle.sh` regenerates references and runs the comparison.
- **`VegaHeadlessTextEngine`** reproduces upstream's canvas-free text estimate so layout is comparable
  on the JVM. A comparison engine only.

## Verification

- 944 JVM tests pass (`./scripts/test-core.sh`, `./gradlew test`).
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

None. Thirty-three fixtures exist and all thirty-three pass. The brief's MVP asks for 100; growing the
corpus is the main task now, and each new fixture is expected to surface gaps rather than pass
immediately. That keeps happening, which is the point of the harness: `stacked-bar` surfaced two real
bugs, and `facet-trellis` surfaced a third — `range: "height"` was descending for every scale type,
where upstream ascends for a discrete one. A row-faceted trellis was therefore upside down, and
nothing but a differential fixture would have said so.

`sorted-domain` is the most recent, and it surfaced two more of the same kind. A scale domain's
`sort` was read as a **boolean**, so the object form every sorted bar chart is written with —
`{"op": "sum", "field": "amount", "order": "descending"}` — was truthy, and the domain came out
alphabetical. Silently: the chart still had bars, still had labels, and was in the wrong order.
Underneath that, `sort: true` sorted the values' *rendered* form, so a numeric domain read 100, 20,
3, 9. And once the fixture was passing, the multi-field domain next to it turned out to be assembled
row by row where upstream assembles it field by field — the two agree until two fields interleave,
after which every entry but the first has moved.

`box-plot` and `stack-offsets` are the first two fixtures in a while to pass on arrival: the quantile
aggregates and the normalized stack were already right. `stack-diverging` was not, and what it found
was in none of those places. **Every negative number on a continuous axis was drawn with the wrong
character.** d3-format signs a formatted magnitude with U+2212 MINUS SIGN, not the ASCII hyphen, so a
tick reading `-10` here reads `−10` upstream — a different glyph of a different width, and the one
that is typographically correct. The distinction is not uniform, which is why it went unnoticed: it
follows the *format string*, so a continuous axis label and a gradient legend label get it while a
discrete axis label, which is the domain's own string, keeps the hyphen. Chasing it into the `format`
expression function turned up two more: an exponent was zero-padded where JavaScript's
`toExponential` does not pad, and a specifier naming no type was being treated as plain fixed
formatting when d3 aliases it to `.12~g` — twelve significant digits, trimmed — so `format(x, "")`
printed `5.000000` for 5.

`axis-style` passed on arrival too, but only after the harness was taught to look. Upstream's axis
takes **74 properties** and this engine honoured fifteen; the other fifty-nine were dropped without a
word, which is exactly the failure this project exists to avoid — an axis that draws ten ticks where
`values` named four still looks like a chart. Every one of them is now reported by name, and the
styling family is implemented. Writing the fixture first showed the harness could not have caught it:
`strokeDash` was not compared at all, so a dashed gridline and a solid one were indistinguishable to
it, the same way a symbol's outline once was. That makes four things the normalizer has had to be
taught to see.

`domain-limits` found one more, in a corner nothing had reached before: a symbol sized to **zero**
was bounded as nothing at all, where upstream bounds it as a degenerate point at its anchor. A test
had asserted the empty form deliberately, with no upstream evidence behind it. It matters under
`autosize: pad`, where a chart is measured by how far its marks reach — a point counts and an empty
rectangle drops out — and a size scale bottoming out at its domain minimum produces exactly that.

`legend-style` found the fourth and fifth. **A legend was placed without regard for how far the
marks reach**, so a chart whose line overhangs the plotting area drew its legend over the overhang.
The rule turned out to be narrower than "marks": a top-level line whose 3-unit stroke hangs half a
unit past the right edge leaves the legend exactly where it was, and *the same line inside a faceted
group* moves it — established by moving one line between the two and watching the legend slide.
And two more channels the harness could not see: a stroke's dash on a **path**, and every node's own
`opacity`, neither of which was reported on our side at all. A legend swatch faded to 0.6 by
`symbolOpacity` has the same geometry, fill and stroke as one at full strength, so nothing else in
the comparison could have told them apart.

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

## What a reader should not have to rediscover

Behaviours reproduced deliberately because upstream does them and a chart written against upstream
depends on them. Each has a test and a comment; this is the index.

| Behaviour | Where |
| --- | --- |
| `size` on a symbol is the squared extent, not the area; upstream ships its own symbol table | `SceneNode.buildSymbolPath` |
| `range: "height"` descends for a continuous scale and ascends for a discrete one | `ScaleResolver.numericRange` |
| `reverse` flips the range, not the domain | `ScaleResolver.oriented` |
| A `null` in a series does not break a line; `defined` does | `MarkEncoder.point` |
| `parent` in a facet is the group's datum, so `parent.width` is undefined | `ScopeCompiler.nest` |
| `width`/`height` are inherited by a subscope, so a nested `"height"` range spans the whole chart | `CompileScope` |
| An axis is measured by its extent, not by the items it drew, and gridlines are excluded | `AxisBuilder.BuiltAxis` |
| A stroked path reserves four stroke widths for a miter join, not ten | `Stroke.miterLimit` |
| A title is placed against the whole drawing, not the plotting area | `TitleBuilder` |
| `timeunit` builds its floor from the units present and defaults the year to 2012 | `TimeUnitTransform.floor` |
| A day is a calendar day, so a daily tick holds midnight across a daylight-saving change | `TimeStepper` |
| Trellis marks are emitted in specification order, not grouped by role | `ScopeCompiler.trellis` |
| A multi-column legend fills down each column before moving across | `GridLayout.columnMajorOrder` |
| Arc angles run clockwise from twelve o'clock | `PathBuilder.arcTo` |
| A discrete domain is grouped, not listed, so `sort` orders groups and may name a field the domain never mentions | `ScaleResolver.orderedDomain` |
| A domain over several fields runs field by field, not row by row | `ScaleResolver.orderedDomain` |
| A domain `sort` object with neither `op` nor `field` sorts by the value, and a `field` with no `op` does nothing at all | `SpecParser.parseDomainSort` |
| A number formatted through a format string is signed with U+2212, not a hyphen; one stringified by JavaScript is not | `formatTickLabel`, `MINUS_SIGN` |
| A format specifier naming no type is `.12~g`, not plain fixed formatting | `NumberFormatSubset.parse` |
| An exponent is not zero-padded, because d3 formats `e` by calling JavaScript's own `toExponential` | `PlatformDecimals.exponential` |
| A tick's stroke width counts towards the chart's size and a gridline's does not | `GuideStyle`, `AxisBuilder` |
| `zero` follows the scale *type*, so a linear scale given `[10, 20]` still starts at 0 | `ScaleResolver.continuousDomain` |
| `domainMin`/`domainMax` replace an end rather than clamping it, and run after `zero` | `ScaleResolver.continuousDomain` |
| A zero-sized symbol bounds as a point at its anchor, not as nothing | `SceneNode.buildSymbolPath` |
| A *group* mark's overhang pushes a legend outwards; a top-level mark's does not | `ScopeCompiler.markReach` |
| `symbolOpacity` is a legend swatch's overall opacity, not a fill or stroke opacity | `LegendBuilder.symbolEntries` |

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
- **`compileSdk`/`targetSdk` 37, `minSdk` 26.** API 37 ships as minor-versioned platforms; the setup
  script installs both `android-37.0` and `android-37.1`. `minSdk` is 26 rather than the 23 this
  project started with: the core does its calendar arithmetic with `kotlinx-datetime`, which is
  implemented on `java.time`, and 26 is where that arrives. The alternative — core library desugaring
  — was tried and removed, since 26 covers effectively every device now and carrying a backport to
  avoid raising it is the worse trade.
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

1. **Implement what the audit just named, starting with `domainMin`/`domainMax`, legend styling and
   the mark stroke channels.** Reporting the gap was the cheap half and it is done; these three are
   the parts of it that a chart notices. `domainMin`/`domainMax` pin an axis to a fixed range, which
   is what stops a dashboard's bars rescaling every refresh. Legend styling is the same
   `{part}Color`/`Width`/`Dash`/`Opacity` family the axis already has, against the same `GuideStroke`
   and `GuideStyle` — a legend beside a styled axis currently cannot be made to match it. And
   `strokeDash`, `strokeCap` and `strokeJoin` on a mark are already in the scene graph and the
   renderers; only the encoder does not read them.
2. **`values`, then `labelAngle`.** The two most consequential things a specification can ask an axis
   for and not get. `values` replaces the tick set outright — upstream keeps the grid in step with it,
   and on a band scale it filters the domain rather than positioning freely — and `labelAngle` is how
   every chart with long category names is made readable. The angle is the harder of the two: it
   changes the label's align and baseline defaults and its contribution to the axis extent, so the
   chart's size moves with it.
3. **Keep growing the fixture corpus.** 33 of the brief's 100 pass, and the return has not dropped
   off: of the last five, two passed and three found defects — a domain `sort` that read an object as
   a boolean and quietly ordered a bar chart alphabetically, and the wrong character on every negative
   axis label. Between them the corpus has also found a missing scale-domain form, a legend layout
   rule that only diverges once swatches grow, unreported opacity, rotated text offsets both sides of
   the harness had wrong in the same way, a line-gap behaviour this engine had documented backwards,
   gridlines running the wrong way from a top or right axis, labels keeping a gap for ticks that were
   switched off, and `reverse` reversing the wrong end of the scale. Worth aiming at next: a `config`
   block of any kind, which is where a Vega-Lite-compiled specification puts everything and where
   every default in this engine is still a hard-coded constant with no way for a specification to move
   it; and a local `time` scale crossing a daylight-saving boundary, where only UTC is covered today.
   Still untouched beyond that: `sequence`, `window` and `lookup` and the other 25 transforms;
   `trail`, `shape`, `image` and `path` marks; and `quantile`, `quantize`, `threshold` and
   `bin-ordinal` scales.
4. **Label overlap removal** and **arc padding and corner rounding**, both long-standing and both
   still worth doing — each a self-contained feature that is honestly reported today.
