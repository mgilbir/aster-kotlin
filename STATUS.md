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

Milestones 0, 1 and 2 complete. **Milestones 3, 4 and 5 in progress**: Vega specifications compile
end to end — expressions, signals, 33 of upstream's 40 data transforms, every scale type in scope,
and an event handler that recompiles the chart — and are verified against upstream Vega by
differential tests.

Seventy-eight differential fixtures pass, all matching upstream exactly on every mark and scale output:

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
| `axis-values` | 34 | explicit tick values: out of order, out of range, thinned, filtering a band |
| `label-overlap` | 90 | a parity axis, a greedy one, and a ramp that thins its labels unasked |
| `axis-label-angle` | 33 | labels turned 45 degrees, hung Vega's way and corrected Vega-Lite's |
| `config-theme` | 42 | a theme in `config`, every level of the precedence chain visible at once |
| `config-marks` | 31 | a theme reaching the marks, and a rect that encodes only a stroke |
| `arc-padding` | 10 | a padded, round-cornered donut beside a pie of the same data |
| `window` | 43 | a running total and a three-point moving average, partitioned by series |
| `sequence-lookup` | 27 | a curve generated from nothing, over bars joined to a second dataset |
| `scale-variants` | 43 | a symlog axis over both signs, a pow axis, a reversed point scale |
| `negative-labels` | 45 | where the minus sign applies and where the hyphen stays |
| `label-limit` | 33 | labels truncated at the limit nobody set, and at an explicit one |
| `step-lines` | 24 | the three staircase interpolations and a stepped area |
| `curves` | 27 | monotone, natural, basis and cardinal over one series, and a monotone area |
| `colour-ramps` | 57 | four continuous schemes across one domain, with a gradient legend |
| `path-marks` | 34 | outlines from SVG path strings, on path marks and on symbols |
| `trail` | 28 | a line whose thickness follows the data, beside a plain line through it |
| `reshape-matrix` | 33 | a heatmap crossed from one list, and a word-count chart beside it |
| `statistics` | 28 | a least-squares line over its scatter, and the same values as quartile rules |
| `trend-lines` | 33 | a quadratic fit sampled where it bends, a loess smoothing, the straight line |
| `density-plot` | 40 | a kernel density over a dot plot, with the theoretical normal behind both |
| `treemap` | 18 | one tree drawn twice: a squarified treemap and an icicle plot |
| `tree-layouts` | 31 | a circle pack beside a tidy node-link diagram, with its links |
| `binned-scales` | 69 | one skewed column through all four discretizing scales |
| `local-time-dst` | 35 | a local time scale across the spring clock change, beside a UTC one |
| `bin-to-ordinal` | 36 | the bin transform feeding a bin-ordinal scale, labels placed by `scale()` |
| `image-marks` | 18 | every image align and baseline anchor, and one stretched rather than fitted |
| `named-range` | 60 | `"range": "category"` and the other named ranges, resolved to upstream's own defaults |
| `scheme-forms` | 31 | a scheme named in the wrong case, one chosen by a signal, one written out as its stops |
| `centre-anchors` | 22 | `xc`/`yc` on five mark types, an `x2` with no `x`, and a pair written backwards |
| `signal-transform-params` | 29 | a signal choosing an aggregate's operation and field, and bounding a filter |
| `luminance-contrast` | 24 | `luminance()` printed as a number and picking the colour of the text printing it, over swatches either side of the gamma knee |
| `indata-membership` | 25 | `indata()` against a second dataset, matching a number and a boolean by string coercion, and printing the count it returns |
| `container-size` | 2 | `containerSize()` with no container, measured and fallen back on the way a responsive specification writes it |
| `link-paths` | 77 | one tree joined four ways: diagonal, orthogonal and curved links, and a radial fan |
| `radar` | 31 | top-level `encode`, marks drawn from earlier marks' scene items, `linear-closed`, `autosize.contains: "padding"`, mark `zindex` |
| `curves-closed` | 3 | the three closed curve families over one ring of points |
| `autosize-none` | 19 | a `none` chart inset by its padding, refusing to grow for the labels hanging off it |
| `grouped-bar` | 56 | `round` on a continuous scale, `mult`/`offset` value references, `contrast()`, labels from the bars' own scene items |
| `barley-trellis` | 468 | a chart sized by a `height` signal, cells titled from the group mark that made them, axis and legend `encode` blocks, a raised axis painting over the legend, and 120 rows loaded from a relative `url` |
| `connected-scatter` | 146 | labels nudged by an ordinal scale with a numeric range, a currency-formatted price axis, data from a relative `url` |
| `dot-plot-wilkinson` | 120 | a dataset of bare numbers, `dotbin` smoothed, a signal reading a scale, a bin boundary in the right column |
| `global-development` | 175 | an ordinal scale whose range is a data column, a legend label read through it, a legend swatch's fill opacity |
| `qq-plot` | 332 | two plots gridded side by side, a url from a signal, `quantileNormal`, gridlines that undo an axis offset |
| `budget-forecasts` | 77 | `argmin` over a filtered group, a scaled channel taking its value from a signal, `bandPosition`, a label placed by an axis `encode` block |
| `probability-density` | 533 | a scale over a dataset and two datasets over that scale — the case no fixed order of the phases resolves; a `normal` density whose mean and stdev come from another dataset's aggregate |

The gate is wired into `./scripts/oracle.sh`, so every further scale, mark and transform is built
against a harness that can say we are wrong — which golden tests cannot.

## Scope: how much of Vega this is

Measured against the pinned upstream packages in `oracle-js/node_modules`, so these numbers are
checkable rather than estimated.

Upstream Vega is 31 packages, roughly 28,500 lines of source, and leans on about 35,000 further lines
of `d3-*` code (d3-scale, d3-shape, d3-time-format, d3-array, d3-interpolate) that a native port has to
reimplement. It exposes **40 transforms, 119 expression functions, 12 mark types and ~15 scale types.**

This repository is around 25,000 lines of main source and 11,000 of tests. It covers the **output half**
of the pipeline, the testing and diagnostic infrastructure, and — as of Milestone 3 — a thin but
upstream-verified slice through parsing, scales, rect encoding and axes:

| Built here | Upstream equivalent | State |
| --- | --- | --- |
| Scene graph, geometry, paths, hit index | part of `vega-scenegraph` (4,994) | 7 of 12 node types; rendering and hit-testing side only. All 12 symbol shapes pinned to upstream, plus outlines read from SVG path strings |
| Canvas renderer, SVG serializer | rest of `vega-scenegraph` | complete for those 7 |
| Diagnostics, canonical snapshots, goldens, oracle scaffolding | no upstream equivalent | complete |
| `vega-scale` (linear, log, pow, sqrt, symlog, time, utc, band, point, ordinal, sequential) + d3-array ticks | 790 + parts of d3-scale, d3-array | every scale type in scope — the 11 continuous and discrete ones plus quantize, quantile, threshold and bin-ordinal — exact against upstream, with all 68 colour schemes |
| `vega-parser` (width, height, padding, autosize, data, signals, scales, axes, legends, titles, marks, group scopes, `layout`, `config`) | 3,790 | a subset, and every property it does not read is reported by name |
| `vega-encode` (mark encoders, axes, legends, titles) | 952 | 11 of 12 mark encoders — all but `shape`, which needs projections and is an explicit non-goal; axes, legends and titles including overlap removal, truncation and the `config` cascade; nine interpolation methods |
| `vega-expression` + `vega-functions` | 2,388 | language complete; 82 of 119 functions, with 17 more reported by name and reason |
| `vega-transforms` (35 of 40) | 3,754 | the 12 the brief lists plus `timeunit`, `pie`, `window`, `sequence`, `lookup`, `impute`, `cross`, `pivot`, `countpattern`, the statistical family — `quantile`, `regression`, `loess`, `kde`, `density`, `dotbin` — and the whole hierarchy family: `stratify`, `nest`, `treemap`, `partition`, `pack`, `tree`, plus `treelinks` and `linkpath`, which turn a laid-out tree into the edges drawn between its nodes. Exact against upstream |
| `vega-dataflow` | 2,081 | contracts and scheduling only; no pulse propagation |

The entire data and specification half is absent:

| Missing | Upstream size | Here |
| --- | --- | --- |
| `vega-transforms` — the other 19 transforms | most of 3,754 | 0 |
| `vega-dataflow` — pulse propagation and incremental evaluation | 2,081 | contracts only |
| `vega-functions` — the other 44 functions, mostly colour, geo and selection | most of 790 | 0 |
| Remaining scale types — quantile, quantize, threshold, bin-ordinal | rest of `vega-scale` | all four; their legends draw the right colours as swatches rather than upstream's stacked bar |
| Remaining mark encoders — image and shape | rest of `vega-encode` | 0; `image` needs a device resolver, `shape` needs projections |
| Line and area interpolation — `catmull-rom` and `bundle` | part of d3-shape | 0, reported; the step and spline families are implemented |
| Banded legends, trellis footers, legend `symbolLimit` | parts of `vega-encode`, `vega-view-transforms` | 0, reported |
| `vega-view`, `vega-view-transforms` — the view lifecycle and incremental layout | 2,623 | bounds, grid layout and label overlap removal |
| `vega-event-selector` — event-stream DSL | 191 | 0 |
| `vega-time`, `vega-format` — `timeunit`, locales, format strings | 587 + d3-format, d3-time-format | tick selection and default labels only |
| geo, force, label, voronoi, wordcloud, crossfilter | ~4,300 | 0, and all explicit non-goals (PROJECT_BRIEF.md 3.3). The statistics and most of the hierarchy family that used to sit here are now done |

Full parity was never the goal — PROJECT_BRIEF.md 3.3 rules most of that last row out. But the brief's
own MVP definition (section 23) stands at roughly **6.5 of its 15 criteria**, and the unmet ones are the
substantive compatibility items:

| MVP criterion | State |
| --- | --- |
| 1. Compiled Vega JSON loads without JavaScript | **Yes**, for a substantial subset — `VegaChartController.setSpec` loads it and the demo renders three bundled specifications on device |
| 2. Bar, line, area, scatter, stacked bar render natively | **Yes** — all five compile from a specification, and small multiples of them too |
| 3. Axes, legends, labels and titles supported | **Yes** — all four |
| 4. Basic transforms and scales execute in Kotlin | **Yes** — every scale type in scope, including time and UTC and the four discretizing ones, and 35 of upstream's 40 transforms |
| 5. Tap, hover, tooltip, selection, pan, zoom | Yes, except tooltip rendering |
| 6. View and Compose APIs | Yes |
| 7. SVG, PNG, PDF export | Yes |
| 8. TalkBack can describe and navigate | Partial — virtual nodes are tested by instrumentation, not with TalkBack itself |
| 9. At least 100 compatibility fixtures pass | 78 of 100 |
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
- **82 of upstream's 119 functions**, with the excluded ones reporting by name and reason.
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
- **`cross`, `pivot` and `countpattern`** — a matrix, a long table made wide, and the word counts a
  cloud is drawn from. Two surprises between them: a crossed row holds the two originals **whole**
  under `a` and `b` rather than merging their fields, so an expression reaching into one writes
  `datum.a.value`; and `pivot` sorts its column names **alphabetically** before `limit` takes the
  first few, so limiting a pivot drops whatever sorts late however often it occurs.
- **The `impute` transform**, which is what stops a line jumping the gap where a series has no row.
  Its key domain is the union across the **whole dataset**, not per group — a group is missing a key
  precisely when some other group has it — and `keyvals` replaces that domain outright, so a series
  can be padded past where its data ever reached. The rows it adds are appended rather than merged
  into position, and carry only the group's fields, the key and the filler.
- **The `trail` mark**: a line whose thickness follows the data. Filled rather than stroked — a
  stroke has one width and the whole point is that it does not — and built as one closed capsule per
  segment, overlapping at the shared points, which is what makes the joins look continuous without
  any segment knowing about its neighbours. Its `size` is a **width**, halved to a radius, where a
  symbol's `size` is a squared extent; neither name says which.
- **The `path` mark, and outlines from SVG path strings generally.** A `path` mark places a written
  outline once per datum, scaled and rotated about its anchor; and a `symbol` whose `shape` is not
  one of the twelve names is now read the same way rather than drawn as a circle. The parser takes
  the whole grammar, including the parts real path data leans on — implicit repeated commands, where
  a repeated `M` means `L`; numbers run together where a sign or a point already ended the last one;
  the `S`/`T` reflections; and the elliptical arc, converted from its endpoint form to a centre and
  then to cubics. A string it cannot finish reading stops there and is reported, because a truncated
  glyph is more use than no chart.
- **The 53 continuous colour schemes** — `viridis`, `blues`, `redblue`, `turbo` and the rest — so a
  heatmap or a choropleth can be drawn at all. They turned out to be much smaller than expected once
  probed: Vega ships its **own** tables rather than d3's and joins the stops with plain piecewise
  RGB, which the sequential colour scale already did. `blues` starting at `#cfe1f2` had looked like
  a scale-level extent of `[0.2, 1]` applied over d3's ramp; the table simply starts there.
- **The spline interpolations**: `monotone`, `natural`, `basis` and `cardinal`, ported from
  d3-shape as its own state machines rather than rewritten in closed form — each has a first and
  last segment unlike its middle ones, and `cardinal`'s opening control point falls out of a state
  arrangement no reading of the geometry would suggest. `monotone` is really two curves and Vega
  picks between them from the mark's `orient`, so a horizontal series is monotone in *y*; getting
  that wrong leaves the curve overshooting on exactly the charts the method exists to fix. Only
  `catmull-rom` and `bundle` remain reported, both carrying their own parameterisation.
- **The step interpolations**: `step`, `step-before` and `step-after`, on lines and on areas. The
  middle one is the odd one — it never draws the data point at all, turning at the two midpoints
  either side and joining them straight through — and a stepped area's return leg steps the
  opposite way round, or the two boundaries cross at every riser. `basis`, `cardinal`,
  `catmull-rom`, `monotone` and `natural` each still need their own spline generator and are still
  reported.
- **`labelLimit`**, which is a *default* rather than an option: upstream's config carries 180 for
  an axis and 160 for a legend, so a long category name is already being shortened on every chart
  drawn against upstream and was being drawn in full here. The scene keeps the **whole** string on
  the text run and only the drawn lines and the measured width shrink, so a truncated label still
  reports the value it came from — to the differential harness, and to a screen reader.
- **`sequence` and `lookup`.** `sequence` is how a specification draws a function with no data to
  bind to; its `stop` is exclusive, which is the less common convention and would otherwise put an
  extra point on every curve. `lookup` is the only join there is, and it has two shapes — with
  `values` it copies named fields out of the matched row, and without them it writes the whole row
  into one field.
- **The `window` transform**: running totals, ranks and moving averages, which is the commonest
  thing a specification asks for that no other transform can express. Two kinds of operation share
  it and behave differently — the ranking family looks at the whole partition and the aggregate
  family at the *frame* — and the frame's default is `[null, 0]`, the partition start up to this
  row, which is what makes a bare `sum` a running total rather than a partition total.
- **Arc padding and corner rounding**, ported from d3-shape rather than approximated. Neither is
  what its name suggests: `padAngle` is a gap measured at a pad *radius* and converted back into an
  angle separately for each edge, so the two sides of a gap stay parallel instead of splaying
  outwards; and `cornerRadius` is clamped by where the slice's own straight edges would meet, so a
  thin slice rounds less than a fat one and one too small to round loses its corners rather than
  folding inside out. The arc's cubic approximation was tightened from a quarter turn to an eighth
  at the same time — the old error was invisible on screen but moved a 72-unit donut's measured
  extent enough to fail the comparison.
- **`config` blocks for the marks**: `config.mark`, the per-mark-type blocks, and named
  `config.style` blocks a mark opts into through its own `style` property. The ordering is the part
  that is not guessable — `config.mark` sits *below* the engine's built-in per-type defaults and
  `config.{marktype}` sits above them, because upstream's own default configuration is written into
  those per-type blocks. So `config.mark.fill` never recolours a rect and `config.rect.fill` does.
- **`config` blocks for the guides.** `config.axis`, its `axisX`/`axisY`, `axisTop`/`axisBottom`/
  `axisLeft`/`axisRight` and `axisBand` variants, `config.legend`, the `guide-label` and
  `guide-title` styles, and `background`/`padding`/`autosize`. This is where a Vega-Lite-compiled
  specification puts everything it does not say inline, so a chart that ignores it is not one with a
  few options missing — it is one drawn in somebody else's theme. Two things had to be got right: the
  precedence, which runs through five levels before the axis's own properties, and the fact that a
  `style` block names its properties the way a *mark* does, so `fill` becomes `labelColor` on the
  way through. Without that translation the form every theme uses would set nothing at all.
- **Turned axis labels.** `labelAngle`, with `labelAlign` and `labelBaseline`. The angle alone is
  all upstream applies — the alignment and baseline stay where the orientation put them, so a
  45-degree label on a bottom axis is still centred and top-baselined and hangs off to the left of
  its tick. That looks wrong and is what plain Vega draws; the two overrides are how it is corrected,
  and they are what Vega-Lite emits alongside an angle. Both forms are in the fixture, side by side.
- **Label overlap removal.** `parity` and `greedy`, with `labelSeparation`, on axis labels and
  gradient legend labels. A **legend** does this by default and an **axis** does not — the
  `labelOverlap: true` in upstream's config sits in the `legend` block and the `axis` block has no
  entry — so a dense axis really does print every label on top of the last unless asked otherwise.
  A hidden label is not removed: it stays at zero opacity, so a chart has the same marks however
  wide it is drawn, and only the guide's measured extent shrinks.
- **Explicit axis tick values.** `values` replaces the ticks the scale would generate, and the
  gridlines follow. Four rules, three of them surprising: a value outside the *range* is dropped
  rather than clamped, the survivors come out in range order however they were written, too many for
  the tick count are thinned by repeated halving with the two ends as a fallback, and the labels are
  formatted at the precision the *number of values* implies — so `[0.5, 1.5]` on a `[0, 2]` domain
  reads "1" and "2".
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

- 1,348 JVM tests pass (`./scripts/test-core.sh`, `./gradlew test`).
- Android lint is clean with `warningsAsErrors` on every Android module.
- 63 instrumented tests pass on an API 37 arm64 emulator (`./scripts/test-android.sh`): 49 in
  `vega-android-canvas`, 4 in `vega-compose`, 10 in `demo`. Three groups of them cover what no JVM
  test can: one compiles every bundled specification with the device's own font metrics, which the
  differential tests deliberately cannot because they measure text upstream's way; three tap a real
  view with a synthetic `MotionEvent` and read the pixels back, which is the only way to know the
  gesture detector, the view's touch handling and the content scale all carry a finger through to a
  signal handler; and three decode a real bitmap into an `image` mark, which the differential
  harness cannot do at all and so cannot tell a drawn image from a silently skipped one.
- The demo was installed and driven on the emulator: all ten chart entries render, marks are
  selectable by tap, light and dark palettes are legible, and SVG/PNG/PDF export all wrote files with
  zero warnings. The three specification entries load Vega JSON from assets, compile it on a
  background thread and report zero diagnostics; tapping a bar on a compiled chart selects it, which
  is what proves the hit index is rebuilt when a specification is published. `scripts/emulator.sh`
  starts the AVD with a window and installs the demo, for looking at rather than only asserting on.
- **"Paste your own"** takes a Vega specification from the clipboard or typed into the field, and is
  the only entry that can fail. It was driven by hand on the emulator with both a working
  specification and one using `geojson`, `shape` and a per-corner radius: the chart renders and the
  diagnostics appear beneath it, naming each part that was dropped and where. That is the first time
  "nothing silently ignored" has been visible to anyone outside this repository, and it is worth
  keeping that way — it is the screen where the discipline pays for itself.

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

## The official Vega examples

The 93 specifications in [`vega/vega/docs/examples`](https://github.com/vega/vega/tree/main/docs/examples)
are the real measure of this engine, and they are far harder than the fixture corpus: the corpus was
written *here*, against what already worked, while the examples were written by other people with no
regard for what this supports.

**22 of 93 compile with no errors.** Eleven did before data loading existed. `scripts/` has no
runner yet — the survey is `ExampleTriage`, a test that is skipped unless `-Dexamples.dir=` points at
a directory of specs, and which writes a ranked report beside them. Each example gets its own thread
and a twenty-second deadline, because one specification that loops must not take the survey with it.

What blocks the rest, by first error rather than by count — a failed scale reports once and then
every encoding using it reports again, so counting raw diagnostics buries the cause under its
symptoms:

| Root cause | Examples |
| --- | --- |
| `topojson`, needing map projections (a non-goal) | 10 |
| A signal where a literal is expected: `tree.method`, `aggregate.op`, a scale `range` | ~8 |
| Missing expression functions: `setdata` and `now` are what is left of this row. `bandwidth`, `bandspace` and `lerp` arrived earlier; `indata`, `containerSize` and `luminance` since. The survey has not been re-run against the examples directory, so how many of the eight this actually frees is unmeasured rather than estimated | was ~8 |
| A data-driven domain in a form the scale resolver does not read | 4 |
| `kde2d`, `graticule` and the geo transforms | ~5 |

Two examples used to **hang** — `circle-packing` and `zoomable-circle-packing`, both `stratify`
into `pack` over `flare.json`. That is fixed, and the cause is worth remembering: Welzl's smallest
enclosing circle restarts its scan every time the basis grows, so it only terminates while each new
basis genuinely encloses more than the last. d3 throws when that stops being true; this engine
returned a degenerate basis instead, which looked like graceful degradation and was in fact a
livelock. The solver is now bounded and falls back to a guaranteed — if not minimal — enclosing
circle, so a pack comes out slightly loose rather than never. Found only because the survey gives
each example a deadline.

## Known failing fixtures

None. Seventy-eight fixtures exist and all seventy-eight pass — and that sentence became worth
something only once the gate could no longer skip itself, below.

**The gate could report success without running.** `FixtureDifferentialTest` reads the fixtures and
their upstream references straight off disk, so Gradle could not see them; after `scripts/oracle.sh`
rewrote every reference it called the test task up to date, skipped it, and the script printed
"Differential tests passed for 56 fixture(s)". A `binned-scales` fixture whose legend did not match
upstream was committed behind exactly that green. Both directories are now declared as task inputs
(`build.gradle.kts`), so a changed reference forces the comparison to run.

If a gate ever looks suspiciously green after a reference regeneration, re-run it with
`--rerun-tasks` before believing it.

The corpus is otherwise the main task: the brief's MVP asks for 100, and each new fixture is
expected to surface gaps rather than pass immediately. That kept happening for a long time —
`stacked-bar` surfaced two real bugs, and `facet-trellis` surfaced a third, `range: "height"`
descending for every scale type where upstream ascends for a discrete one, which had a row-faceted
trellis upside down. Nothing but a differential fixture would have said so.

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

`radar` — Vega's own radar chart, added because a user reported it rendering wrong — found four
gaps in the engine and **two more in the harness**, which is the higher number worth noticing. The
engine's four: the top-level `encode` block was ignored, so every polar coordinate was drawn around
(0,0) with three quadrants off the surface; `autosize.contains: "padding"` was parsed and not
applied, so the radius was 200 where upstream's was 160; a mark could not be drawn from another
mark's scene items, which cost two of the five marks outright; and a `line` was never filled, so the
polygons were outlines where upstream shades them.

The harness's two are the interesting ones, because the fixture passed the moment those four were
fixed and the chart was still visibly wrong. **Nothing compared whether a line closes.** A
`linear-closed` line emits exactly the points a `linear` one does — the join is a `Z`, not a vertex —
so an unclosed polygon matched its reference on every channel. And **nothing compared paint order**:
marks were flattened in declaration order on both sides, so the grey radial grid painting *over* the
data instead of under it was invisible to a comparison that matches marks positionally within each
type. Mark `zindex` had been parsed and silently dropped since it was first read. Both are now
compared, the second by walking Vega's own `sceneVisit` rather than by writing the rule out again —
a negative `zindex` raises a mark rather than sinking it, which no second copy would have guessed.
That makes six things the normalizer has had to be taught to see, and it keeps being the case that
a fixture's first pass is worth less than the look at the rendered SVG that follows it.

`grouped-bar` — Vega's own grouped bar chart — was the next example taken end to end, and it found
three more silences. A **`round: true` on a continuous scale had been parsed and dropped**, so every
bar in the chart was a fraction of a unit wide and every axis label a fraction of a unit out of
place. `mult`, `offset`, `exponent` and `round` were read only on a *scaled* value reference, where
upstream appends them to every one — so `{"field": "x2", "offset": -5}` put each label at the end of
its bar instead of just inside it, and `{"field": "y", "offset": {"field": "height", "mult": 0.5}}`
put it at the bar's top edge instead of its middle. Neither was reported. And `contrast()` was
genuinely absent and said so, which is the behaviour that is supposed to happen; it is now
implemented, with the margin between white and black on Vega's default blue coming out under 1%, so
an approximation right to two digits would still pick the wrong label colour.

`barley-trellis` — Vega's barley small multiples, the third example taken end to end — found four
more, and one of them was a whole class. **`width` and `height` are signals, not just properties**,
and this chart declares its height as `6 * (offset + cellHeight)`; the size was being fixed from the
properties before the signals ran, so the bottom axis sat at 200 in a chart 690 tall. A mark could
not be drawn from a **group** mark, only from a plain one, so the six cell titles were missing.
Guide `encode` blocks were reported and not honoured, which cost the dashed gridlines and left every
legend symbol the wrong size — and, because a legend's rows are measured from its symbols, every
legend label two units out of place. And a legend over a `stroke` scale was not getting upstream's
explicit `transparent` fill, a test that turns out to be on the **fill** channel alone rather than
on whether the legend maps any colour at all.

The guide-encode gap is the class. Upstream builds each part of a guide from an encode block and
*extends* it with the specification's, so `encode.grid.enter.strokeDash` and `gridDash` are one
thing written two ways — which means the block can be folded into the properties rather than
overlaid on the finished nodes, and folding is what lets it participate in measurement.

`barley-trellis` also caught a paint-order difference the comparison still cannot see, because the
comparison matches marks positionally *within* each type and two guides never interleave: a
`zindex: 1` axis paints after the legends upstream and was painting before them here. It was found
by putting the two SVGs side by side, which is now the third time that has been the thing that
found it.

The examples that follow were blocked on something duller than a missing feature: **their data**.
Nearly every specification in the gallery names it relatively, `"url": "data/barley.json"`, and the
engine had a loader, a file loader and an HTTP loader with a base URL — and no way to say "disk if it
is there, the published copy if it is not", which is the one arrangement a corpus of examples needs.
It also had no seam on `VegaChartController`, so no host could opt in to loading at all, which is the
same as not having a loader. Both are now there: `FallbackDataLoader` composes loaders in order, and
`VegaDataLoaders.directoryThenNetwork` is disk-then-`https://vega.github.io/vega/`, optionally
writing what it fetched to disk so the second run is offline. The fixture harness uses the **file**
half only and nothing else, because a green test run must not depend on a connection; missing data is
fetched by `scripts/oracle.sh` and committed beside the reference it belongs to.

`connected-scatter` is the first example that had been waiting only on that, and once its data
arrived it found two more gaps. An **ordinal scale with a numeric range** could not position a mark
— refused outright as "no numeric range" — which is exactly how a label is nudged clear of the point
it belongs to, so every label in the chart sat on top of its own dot. And an axis's `format` was
reported and ignored, so a price axis read `1.5` where upstream reads `$1.50`; the currency symbol
turned out to be a slot of its own in d3's grammar, and the caption a screen reader hears follows
each axis's own format rather than the scale's.

`budget-forecasts` found five, and the first is the one with the widest reach. **A signal computed
from other signals was not available to a transform.** Only signals written down as a literal were
resolved before the data; anything with an `update` waited until after it, so a filter reading
`clamp(handleYear, 1980, 2010)` saw nothing, nothing is zero to arithmetic, and the filter dropped
every row in the chart. A signal that reaches for none of `data`, `indata`, `scale`, `invert` or
`bandwidth` — and none of whose own dependencies do — is now resolved first, which is the order
upstream's dataflow ranking produces anyway.

Then: `argmin` and `argmax`, which return the **whole tuple** at the extreme rather than the value.
An **aggregate over no rows** was producing a row of nulls where upstream produces no rows at all,
which drew a tooltip frame at the origin of a chart nobody was pointing at. A channel with both a
scale and a signal — `{"scale": "x", "signal": "currentYear"}` — was reading the signal and dropping
the scale, putting a draggable handle 2010 pixels from the left of a chart 700 wide; upstream builds
the base value *then* wraps it in the scale, so the scale has to be read first. And `bandPosition`,
which puts a tick at the start of its band instead of its centre.

The last one needed a guide `encode` block that could not fold into a property: a label placed by
`{"scale": "x", "field": "value"}`. Resolving it against the tick as a datum turned up a rule worth
writing down — a guide writes its own `text` and position into `update` on **every pass**, so a
specification's `enter` for one of those is overwritten before anything is drawn. Upstream ignores
such a block too, so the diagnostic now says it changes nothing rather than blaming this engine for
a rule that is Vega's.

`error-bars` was taken up and then **deliberately dropped**. It computes `ci0`/`ci1`, which upstream
derives by bootstrap — a thousand resamples drawn with `Math.random()` — so the same data gives a
different chart every run. It belongs with the examples refused for reproducibility, and the
diagnostic now says which of the two it is rather than only "not implemented".

`global-development` and `qq-plot` came next and were each one gap from passing once the scale range,
the signal url and the distribution functions were in. `global-development` needed the legend
counterpart of the axis-label encode: a label read through a scale, `{"scale": "label", "field":
"value"}`, turning a cluster's id into its name, plus a swatch `fillOpacity` — which is *not*
`symbolOpacity`, that one fading the outline along with the swatch.

`qq-plot` found two, and the second had been sitting in plain sight since the trellis was written.
An axis `offset` was dragging its gridlines with it, where upstream gives every gridline endpoint an
offset of `sign * axis.offset` so they stay on the plot. And **every cell in a scope has to go on one
grid, whichever group mark produced it.** Gridding each group mark separately looks identical
whenever there is only one, which is what every trellis fixture had; this chart is two group marks
under `columns: 2`, and both landed at the origin on top of each other.

`dot-plot` was taken up next and gave up two before stalling on a third. A **top-level signal could
not call `scale()`** — the compiler built scales *from* signals and so refused it — where upstream
ranks its dataflow and builds a scale that waits on no signal first. `scale('x', step) - scale('x',
0)`, turning a step in data units into a size in pixels, is ordinary; a scale whose own domain comes
from a signal is still refused, because there is no order in which that could work. And a **dataset
of bare values** was left unwrapped: upstream's `ingest` turns a non-object row into `{"data": value}`,
which is why that example reads `"field": "data"` over data that appears to have no such column.

The third took two wrong guesses to find, and both are worth recording. `dotbin` was the obvious
suspect and is innocent — its output is identical to upstream's for all 48 points, smoothed and
unsmoothed. So was `nice`, which already defaulted to true as upstream does. What was actually wrong
was **one epsilon**: a value landing exactly on a bin boundary divides to a whole number only in
exact arithmetic, and `(9.1 - 1.95) / 0.65` is 10.999999999999998 in doubles, so flooring it put the
row one column to the left. Upstream adds `1e-14` inside the floor. Three of those 48 points sit on a
boundary, which was enough to make the tallest column one dot short and the chart 9.75 units too
short — and nothing else in the chart was wrong, which is why it took a signal-by-signal comparison
to see at all.

## The three compile phases are gone

`probability-density` needed something structural rather than a feature, and it is the last big
structural difference from upstream to close. The engine ran three fixed phases — all data, then all
signals, then all scales — and that chart cannot be resolved by any fixed order of the three:
`xscale`'s domain is `{"data": "points", "field": "u"}`, so the scale waits on a dataset, and the
`density` dataset's `extent` is `{"signal": "domain('xscale')"}`, so a dataset waits on that scale.
Its diagnostic said "density needs an 'extent'" while the extent was plainly there.

`DataflowOrder` replaces the phases with one dependency ranking over all three kinds, which is what
upstream gets for free: `vega-parser` puts every dataset, scale and signal into a single dataflow and
the topological rank decides what runs when. The edges come from the same places upstream's do —
`dataVisitor` and `scaleVisitor` read the string literal a `data()` or `scale()` is handed, so
`Expression` now reports `dataDependencies` and `scaleDependencies` by name instead of one "reads
something deferred" flag. That flag had a hole worth recording: `domain` and `range` are scaleVisitor
functions upstream and were missing from its list, so a signal reading `domain('xscale')` looked free
of both data and scales and was resolved before either existed.

Ties are broken towards signals, then datasets, then scales. That is not only determinism: it is what
preserves the property the old phases had, that a signal reaching for no dataset resolves before every
dataset, so a transform parameter written as a signal reads a number. It also strengthens it — every
signal that has *become* resolvable is resolved before the next dataset runs, so a transform can now
read a signal computed from an earlier dataset. Upstream agrees: `{"name": "rows", "update":
"length(data('t'))"}` read by a later dataset's `formula` gives `p: 2`, and did give null here.

Three special cases went away with the phases: `SpecCompiler.dataFreeSignals`, `ScaleSpec.isSignalFree`
and the "a scale cannot be read while signals are resolving" diagnostic, which was describing the old
ordering rather than anything true. A genuine cycle is still reported, now as the path that closed it
across all three kinds, and one operator on it is placed anyway so the chart draws and says it is
wrong rather than not drawing.

What the graph still cannot see is a signal read through a transform's **expression** parameter —
`filter`'s `expr`, `formula`'s `expr`, `cross`'s `filter`, which are the only three upstream declares
as `type: 'expr'`. Those are per-row expressions rather than `{"signal": ...}` references, so a dataset
carrying one is not held back for the signal it reads, and the "read a signal whose value is not known
yet" warning in `DataResolver` is still the only thing that says so. A group mark also still resolves
its own data, then signals, then scales in three phases; nothing in the corpus needs them interleaved,
because the enclosing scope's signals and scales are all settled by the time a group is reached.

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

Nothing on hardware. No measurement has been taken on a physical device, and emulator numbers are
not authoritative (PROJECT_BRIEF.md 18.6). The benchmark module and fixtures exist
(`benchmark/src/androidTest`, `scripts/benchmark.sh`) but have not been run on one, so the targets
in PROJECT_BRIEF.md 19 are all unverified.

**One number was taken on the JVM**, because a design decision hung on it. Before building an
incremental dataflow so that a changed signal need not recompile everything, it was worth knowing
what a full recompile actually costs. Compiling each of the 55 fixtures 200 times after 50 warm-up
rounds, on a warm JIT:

| | microseconds per full compile |
| --- | --- |
| heaviest fixture (`axis-values`) | 366 |
| median fixture | 112 |

A 60 fps frame is 16,600 microseconds. Even allowing an order of magnitude for ART, a cold cache
and a phone's CPU, the heaviest fixture recompiles in well under a frame. **So a fired signal
handler can re-resolve the signals and recompile the whole specification, and an incremental
dataflow is not needed for interaction to be smooth.** That removes what looked like the largest
piece of work in the interaction system.

Two caveats on the number. It is a desktop JVM with the JIT warm, which is the most favourable case
there is; and it measures compilation only, not the Canvas draw that follows. It is an order of
magnitude, enough to rule an approach in, and not a substitute for measuring on hardware.

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
| d3 interpolates as `a(1-t) + bt`, not `a + t(b-a)`; the last bits decide which pixel a tick lands on | `LinearScale.interpolate` |
| An axis `values` list is filtered by range, ordered by position, and thinned by halving | `AxisBuilder.ticksFor` |
| Explicit tick labels are formatted at the precision the *number of values* implies | `AxisBuilder.labeller` |
| A legend removes overlapping labels by default and an axis does not; the config entry is in the `legend` block | `LabelOverlap` |
| `labelAngle` turns a label about its anchor and leaves its alignment alone, so a turned label hangs off to one side | `AxisBuilder` |
| A guide's config chain runs style → axis → axisX/Y → axis{Side} → axisBand → the axis itself | `GuideConfig` |
| A `style` block names properties the way a mark does, so `fill` has to become `labelColor` | `GuideConfig.prefixed` |
| `config.mark` loses to the built-in per-type defaults and `config.{marktype}` beats them | `MarkEncoder.MarkConfig` |
| A mark that encodes *either* paint channel gets **neither** default, so a stroke-only rect is hollow | `MarkEncoder.style` |
| A window's default frame is the partition start to this row, so a bare `sum` is a running total | `WindowTransform` |
| A window's ranking operations ignore the frame; only its aggregate operations respect it | `WindowTransform` |
| A `sequence`'s `stop` is exclusive, so `0..5` is five rows, and its field is named `data` | `SequenceTransform` |
| A `lookup` with no `values` writes the whole matched row into one field | `LookupTransform` |
| `labelLimit` truncates by default — 180 on an axis, 160 on a legend — and the scene keeps the whole string | `TextRun.displayText` |
| A `step` line never draws the data point itself: it turns at the two midpoints either side | `PathBuilder.steps` |
| `monotone` is two curves — d3's X and Y forms — and Vega picks between them from the mark's `orient` | `CurveKind.MONOTONE` |
| The colour ramps are Vega's own tables, not d3's: `blues` starts a fifth of the way in, and nothing narrows it | `ColorSchemes.ramps` |
| A custom symbol outline is written in a unit box and scaled by the symbol's own reference length | `SceneNode.scalePath` |
| A repeated `M` in a path string means `L`, which is the one place the implicit-command shorthand changes meaning | `SvgPath.parse` |
| A trail is *filled*, not stroked, and its `size` is a width where a symbol's is a squared extent | `TrailPath` |
| `impute` takes its key domain from the whole dataset, and appends the rows it adds rather than merging them | `ImputeTransform` |
| A crossed row holds the two originals whole under `a` and `b`, rather than merging their fields | `CrossTransform` |
| `pivot` sorts its column names alphabetically *before* applying `limit`, so a late-sorting column goes however common it is | `PivotTransform` |
| `padAngle` is a gap at a pad *radius*, converted back to an angle per edge, so the sides stay parallel | `ArcPath.sector` |
| `cornerRadius` is clamped by where the slice's own edges would meet, not just by its thickness | `ArcPath.sector` |
| A label hidden by overlap removal stays in the scene at zero opacity, so the mark count does not move | `LabelOverlap` |

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

1. **What the interaction system still does not do.** The chain works end to end and three things
   inside it are stubbed rather than finished: a `debounce` fires on every event instead of after
   the quiet period, because nothing schedules; a `{"signal": "..."}` or `{"scale": "..."}` source
   parses but only ever fires from a real event; and an `encode` handler, which sets properties on
   the event's own mark rather than a signal, is reported and does nothing. The first needs a
   scheduler the controller does not have; the second needs a signal to notice its own change; the
   third needs the compiler to hand back a mutable node. All three are reported by name today.
2. **The fixture descriptions are written for the wrong reader.** Now that a chart's
   `description` is announced, the corpus's own prose is what TalkBack says first — and it was
   written for a developer reading the corpus: "so both legend forms and both placement axes are
   covered". That is the right sentence in the wrong place. Either the fixtures grow a second,
   reader-facing description, or the demo's bundled specifications stop being fixture files. The
   engine is not at fault; the demo reads badly and a user would notice before any of us did.
3. **Keep growing the fixture corpus.** 58 of the brief's 100. Aiming it at *combinations* the
   engine has not met rather than at more variations of a single feature is what makes it find
   things: that is how `scale()` in an expression turned up missing. Untried combinations that
   remain include an axis on a discretizing scale, a group whose signals shadow the outer scope's,
   and a `timeunit` transform feeding a `time` scale across the same daylight-saving boundary the
   `local-time-dst` fixture crosses.

A fourth candidate turned up while `treelinks` was being added, and is stated here rather than
ranked because whoever re-decides the list should weigh it. **A transform cannot read a computed
signal**, because signals resolve after the data and only the plain constants are seeded first. It
is now reported by name, which it was not — but reporting is not resolving, and Vega's own
`radial-tree-layout` is written on exactly that dependency: every node sits at
`originX + radius * cos(...)` where `originX` has an `update`, so the whole diagram is drawn on the
origin. It is the only known case of the engine producing a confidently wrong *picture* rather than
a missing feature, and no fixture can cover it — upstream draws it correctly, so a fixture written
around it would only record the disagreement. Resolving it needs the signal pass to run in
dependency order across the data boundary, which is most of a real dataflow.

One item still needs something this environment does not have: performance on **physical hardware**
(PROJECT_BRIEF.md 19, criterion 13). The emulator is available and useful for behaviour, but
PROJECT_BRIEF.md 18.6 says emulator timings are not authoritative, and it is right.
**TalkBack** (criterion 8) is no longer one of them. It was enabled on the emulator and the demo
explored with it; the tree was already correct and two things it *said* were wrong, both now fixed
and pinned by instrumented tests. What remains untested there is physical hardware and a real user,
which is a different claim from "not verified at all".

A note on the harness, because it is now the seventh time. The differential comparison has had to be
taught to see a symbol's outline, fill and stroke opacity, a dash pattern, a node's own opacity, an
unfilled mark's missing opacity, the corners a curve puts between a series' points, and — adding
`linkpath` — the outline a `path` mark actually draws, which until then was compared only by the
anchor it hung from. Each was invisible for the same reason — two marks agreeing on every channel
being compared and differing in the drawn result. Before trusting a green fixture on a *new* kind of
property, check that
`oracle-js/src/normalize.js` and `Differential.kt` both emit it. And look at the two pictures: three
of the six were found that way, most recently a renderer drawing the full text of a label it had
measured as truncated.
