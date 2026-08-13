# Status

Last updated: 2026-08-09

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
end to end — expressions, signals, 50 of upstream's 51 data transforms, every scale type in scope,
and an event handler that recompiles the chart — and are verified against upstream Vega by
differential tests.

One hundred and thirty-eight differential fixtures pass, all matching upstream exactly on every mark and
scale output:

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
| `published-signals` | 11 | a signal reading a signal an `extent` transform *published*, sizing a scale range and a mark width |
| `bin-settings` | 36 | `bin` publishing the settings it chose, a `nice: false` extent that does not divide evenly, an anchored grid, and a value that falls off it |
| `autosize-fit` | 30 | the plotting area shrunk so the drawing comes out the declared size, with angled labels and two axis titles overhanging |
| `autosize-fit-x` | 30 | the same on the horizontal axis only, the vertical growing the way `pad` does |
| `autosize-fit-y` | 30 | and the same the other way round |
| `stock-index-chart` | 53 | a CSV of `Jan 1 2000` dates, a label placed only by `y2`, `fit` sizing |
| `overview-plus-detail` | 96 | the same dates, two linked views |
| `parallel-coordinates` | 554 | a scale named per row by the datum, a field read off the parent under a name the datum supplies, one axis per column placed by a scaled `offset` |
| `u-district-cuisine` | 252 | a ridgeline plot: `facet.aggregate`, mark `sort`, a literal domain with a signal in it, axis labels renamed through a second scale |
| `platformer` | 7517 | `hsl` read apart and put back together, a self-referencing signal, a clipped group that scrolls past its window |
| `pacman` | 1263 | `setdata` writing a dataset from a signal |
| `edge-bundling` | 989 | pre-faceted data and `treePath` over a stratified tree |
| `radial-tree-layout` | 552 | Vega's radial tree: every node placed by a transform reading a computed signal, which used to draw the whole diagram on the origin |
| `donut-chart-labelled` | 108 | Vega's labelled donut: 33 label slots of which nine are filled, leader lines with no outline, and debug rectangles left invisible |
| `multi-source-pluck` | 12 | a dataset concatenating two sources, and a signal plucking one column out of the result |
| `interactive-legend` | 454 | a brush `rect` with no `x` until someone drags one, and a legend swatch whose opacity is a conditional rule |
| `histogram-null-values` | 47 | Vega's film-rating histogram: a scale whose ticks are `bin`'s own boundaries, a second band scale for the null bar, `fit` sizing, and 3,201 rows from a relative `url` |
| `time-units` | 40 | Vega's time-unit bar chart: a scale domain whose *field name* comes from a signal, a band axis of instants labelled by `formatType: "time"` with a specifier `timeUnitSpecifier` chose, and an italic subtitle |
| `calendar-view` | 6311 | Vega's calendar view: 21 faceted years ordered by a `sort` over the *datum*, `timeOffset` moving a week's Sunday to its Monday, axis labels hidden by a rule of their own, and a legend whose title stands beside its ramp |
| `crossfilter-flights` | 171 | Vega's cross-filter: 200,000 rows binned three ways, `crossfilter` recording which range query each fails and `resolvefilter` reading those verdicts back per histogram |
| `clock` | 93 | Vega's world clock, on a stopped clock: `now()` pinned to the same instant on both sides |
| `watch` | 92 | The same face drawn from arcs, and the second example built on `now()` |
| `error-bars` | 61 | Vega's error bars: `ci0`/`ci1` by bootstrap, and a band axis whose ticks sit on the band edges with one more pegged to the leading one |
| `hypothetical-outcome-plots` | 60 | Twelve bars whose heights are twelve draws from the chart's stream, in the order upstream draws them |
| `volcano-contours` | 21 | Vega's contour plot of Maungawhau: marching squares over a 61 x 87 raster grid, drawn through a mark-level `geopath` |
| `bar-line-toggle` | 100 | A chart that switches views by emptying one of two datasets, so half its scales have no domain at all |
| `serpentine-timeline` | 80 | Vega's serpentine timeline: a scale reached from a `formula`, a `reverse` chosen by a signal, and eleven labels haloed by a text stroke |
| `pi-monte-carlo` | 2148 | Vega's Monte Carlo estimate of pi: two styled cells laid out `align: none` and `bounds: flush`, a grid driven by a second scale, flushed end labels, and 2,000 seeded points |
| `density-heatmaps` | 89 | Vega's density heatmaps: `kde2d` over a scatter, painted as an image by `heatmap` — three grids, one per series, each with its own colour |
| `contour-plot` | 460 | Vega's contour plot: the same three densities under their contour lines, so the raster and the vector reading of one grid have to agree |
| `packed-bubble` | 32 | Vega's packed bubble chart: a force simulation left **running**, so the picture is the single tick upstream takes before its timer would |
| `force-directed` | 331 | Vega's Les Miserables node-link diagram: 300 iterations of collide, centre, n-body and link over 77 nodes, and the edges drawn from the ends the simulation resolved |
| `beeswarm` | 100 | Vega's beeswarm plot: 300 iterations of collide against two axis springs, each pulling towards a channel the mark encoded |
| `world-map` | 178 | Vega's configurable world map: a TopoJSON file decoded, a mercator projection with rotation and centring, a graticule under it, and 177 countries cut at the antimeridian |
| `county-unemployment` | 3622 | Vega's county choropleth: `albersUsa` — three projections at once, with Alaska and Hawaii inset — over 3,100 counties, and a banded legend whose lowest band has no lower bound to write |
| `map-with-tooltip` | 3623 | The same counties under a mercator, with `geoCentroid` placing a tooltip and `invert` reading the projection backwards |
| `dorling-cartogram` | 113 | Vega's Dorling cartogram: states as circles sized by `geoArea` over `albersUsa`, and a size legend whose rows are clipped so its biggest swatches overlap |
| `geo-points` | 30 | Six cities placed by `geopoint` under three projections at once, joined by rules so a misplaced point moves a line as well as a dot |
| `airport-connections` | 650 | Vega's airport map: 49 states through `albersUsa`, and a **Voronoi** cell over each of 597 airports so the pointer always has a nearest one — invisible, and compared outline by outline |

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
| `vega-encode` (mark encoders, axes, legends, titles) | 952 | all 12 mark encoders; axes, legends and titles including overlap removal, truncation and the `config` cascade; every one of Vega's seventeen interpolation methods with its own reading of `tension`; every encode channel in the vocabulary |
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
| 4. Basic transforms and scales execute in Kotlin | **Yes** — every scale type in scope, including time and UTC and the four discretizing ones, and 50 of upstream's 51 transforms |
| 5. Tap, hover, tooltip, selection, pan, zoom | Yes |
| 6. View and Compose APIs | Yes |
| 7. SVG, PNG, PDF export | Yes |
| 8. TalkBack can describe and navigate | Partial — virtual nodes are tested by instrumentation, not with TalkBack itself |
| 9. At least 100 compatibility fixtures pass | **Yes** — 148 |
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

## Vega-Lite compiles to Vega, in Kotlin

`vega-lite` is new: `VegaLiteCompiler` turns a Vega-Lite specification into a Vega one, in the value
model the runtime already parses, so a Vega-Lite chart takes exactly the path a Vega chart does from
that point on. It depends on `vega-model` alone — it emits a specification, it does not execute one.

The rules are ported from upstream's own TypeScript sources, which ship inside the pinned npm package
(`oracle-js/node_modules/vega-lite/src`), rather than inferred from the documentation. That matters
because Vega-Lite is *almost entirely* defaults: a specification names a field and gets a scale type,
a stack transform, a plot sized from its own categories, a tick count that follows that size, a label
angle, a grid, a legend and a spoken description. Each of those is one rule, and a rule that drifts
produces a chart that is plausible and wrong.

**The gate is the emitted specification, not the picture.** `scripts/vega-lite-oracle.sh` compiles
every fixture with upstream and checks two things:

1. `VegaLiteFixtureTest` compares the Vega this compiler emits against upstream's, property by
   property. Seventy-three fixtures, and all of them match exactly — every transform, scale, signal,
   axis, legend and mark encoding, down to the accessibility description string.
2. `VegaLiteFixtureDifferentialTest` runs that output through this engine's own runtime and compares
   the scene against the one upstream draws. Every mark of every fixture matches, and nothing is
   skipped: the set that held the one fixture whose cells this runtime gridded differently is empty.

Comparing the specification is what makes a failure legible: it names the rule that drifted, where a
scene comparison would only say that some marks moved.

### What the fixtures found in the runtime

Five defects, none of which any Vega fixture could have found, because nothing but a Vega-Lite
compilation writes the constructs that expose them:

- **A top-level `style` was ignored.** Every Vega-Lite chart carries `"style": "cell"` on its root
  group, and Vega's own default configuration gives that block a transparent fill and a `#ddd`
  stroke — the thin border around a plotting area. It was drawing without one. Being a border it is
  also half a unit of surface on each side, so those charts came out a unit small as well.
- **Vega's built-in `config.style` blocks were missing.** `point`, `circle` and `square` set a symbol
  size of 30 and a stroke width of 2, so every Vega-Lite scatter plot drew its points at Vega's own
  default size instead — noticeably too large.
- **`labelFlush` was reported and dropped.** It hangs the labels at the ends of a range from those
  ends rather than straddling them. Vega-Lite asks for it on every continuous horizontal axis, and
  without it those two labels hang outside the plotting area and the whole surface grows to hold
  them.
- **`gridScale` was reported and dropped.** A gridline named by a `gridScale` spans *that* scale's
  range, in that range's direction — so a horizontal axis's gridlines run from the top of the plot
  back down to the axis, because a vertical scale's range starts at the bottom. The same line drawn
  the other way round, which is exactly the kind of difference that survives unnoticed until a dashed
  gridline starts its pattern at the wrong end.
- **A shape legend drew every entry as a circle.** The entries have to be drawn with the shapes the
  `shape` scale gives them; a column of identical swatches is not a smaller version of the right
  answer, it is a legend that says nothing.

### The second eight fixtures found seven more compiler rules and four more runtime defects

The first twelve were written to cover the grammar. The next eight were aimed at *combinations* —
a bucketed instant, a domain sorted by an aggregate of another field, a ranged bar, a layer sharing
its parent's encoding — and seven of the eight failed on arrival, which is the corpus doing its job.

In the compiler: a layer inherits its parent's `encoding` and `transform`, not only its data and
size; a secondary channel (`x2`) takes its type from the channel it bounds, without which `{"field":
"end"}` reads as a category; a ranged position contributes *both* fields to its scale and both names
to its axis title; a `timeUnit` contributes the bucket's end as well as its start, and groups the
aggregate by both; a domain sorted by an aggregate of some other field reads the pre-aggregation
table, which has to be named for it; a sort every part of a merged domain agrees on belongs to the
union rather than to each part; and a log axis thins its labels *greedily* rather than by parity.

In the runtime, four more silences of the same kind as the first five:

- **`isDate` was missing from the expression language.** Every Vega-Lite chart over a temporal field
  filters with `isDate(f) || (isValid(f) && isFinite(+f))`, so the whole x scale collapsed and took
  the marks and one axis with it. It is answerable here only because an instant is its own type in
  this value model; upstream tests `value instanceof Date`, so a bare number of milliseconds is not
  a date to it either, and probing that was what settled the implementation.
- **A time scale ignored an axis `format`.** Its labels always came from the scale's own
  multi-format, which writes each tick at its own granularity — so an axis of months read "2012,
  February, March" where upstream read "Jan, Feb, Mar".
- **A legend ignored the fill in its own `encode.symbols` block**, which is how a legend explaining
  some *other* channel still shows the colour its marks are drawn in.
- **A legend over an opacity scale did not fade its swatches.** A column of equally solid symbols
  beside a fading scale explains nothing, and the legend's whole job is to demonstrate the channel
  it names.

### Arcs, and bars side by side

Three more fixtures — `pie`, `donut`, `grouped-bar` — and the two channel families behind them.

An arc is compiled as a *rectangle in polar coordinates*, which is upstream's own framing and not a
simplification: the same rect positioning rules serve both coordinate systems, with `theta` and
`radius` written out as Vega's `startAngle`/`endAngle` and `outerRadius`/`innerRadius`. Stacking
follows into polar, so a pie is a stacked bar bent round a circle, which is what it is.

`xOffset` is the channel that puts several bars inside one band, and four rules follow from it: the
offset is a band scale of its own, the outer band takes the wider padding meant for groups, the
bar's width comes from the *inner* scale, and the outer step becomes as many marks as the group
holds divided by what the padding takes — `20 * bandspace(domain('xOffset').length, 0, 0) / (1-0.2)`.
Get any one of them wrong and the bars overlap or the chart is the wrong width.

Both went in against the fixtures rather than ahead of them: `donut` failed on its hole (a mark
property named the way *Vega* names the channel, `innerRadius`, which has no Vega-Lite name of its
own) and `grouped-bar` on the step arithmetic.

### Where the compiler stands, and what it still refuses

Seventy-three fixtures, each matching upstream's compiler property for property and drawing the chart
upstream draws. The grammar covered: a single view, a layer of them to any depth, a concatenation of
either to any depth, a
repetition of any of those, both facet operators, eleven marks including `arc`,
the Cartesian and polar position pairs, nested offsets, fourteen of fifteen transforms, sorting,
binning, time units, stacking, faceting by `row` and `column`, conditional encodings, a line or an
area that draws its own points, legends, axes, and a user `config` carried through as a theme.

What it still refuses, by name, with the reason each is refused rather than approximated:

- **A selection parameter** — one carrying `select`. It stands for the rows a reader picked, and
  needs an interaction loop this engine does not run. Reported one parameter at a time, so a
  specification mixing the two kinds still gets the variables it declared. A **variable** parameter
  is implemented (below), and a condition naming a `param` is still refused by itself, leaving the
  rest of its definition standing.
- **Geographic projections and the `geoshape` mark.** The runtime draws maps — `world-map` is in the
  Vega corpus — so this is now a *compiler* gap and not an engine one: nothing here yet translates
  Vega-Lite's `projection` block and its `geoshape` mark into the Vega equivalents.
- A facet `sort` that names a **written-out list**, whose place in it has to be computed onto every
  row as a column of its own; and one that names an aggregate on a facet gridded **both** ways,
  where the key has to be written onto the rows first so each cell can take the greatest of its own.
  An aggregate sort on a facet gridded one way is implemented (below).

### Concatenation: two plots, and what they do not share

A concatenation is not a chart with more marks in it. Each of its plots keeps its **own position
scales and its own axes** — `defaultScaleResolve` makes `x` and `y` independent and everything else
shared — so `concat_0_x` stands beside `concat_1_x` and one colour legend covers both. That is the
whole shape of it: the marks move into a group per plot, the axes go with them, and the top level
keeps only the data, the shared scales, the legends and a `layout` that places the groups.

`hconcat`, `vconcat` and `concat` are one construct here because they are one in upstream's compiler
(`ConcatModel`); what differs is `columns`, and what `columns` decides is **which sizes can merge**.
A row of plots shares a height and a column shares a width, so `parseConcatLayoutSize` names the
merged size `height` or `width` where the plots stand along that axis and `childHeight`/`childWidth`
where they do not — and abandons the merge entirely if the plots disagree or any of them is sized by
a step, leaving `concat_0_height` beside `concat_1_height`. A merged size that is a plain number
then leaves the signal list for the top level, which is upstream's own last step.

Three defects came out of the two fixtures, and none of them is about concatenation as such:

- **A shared legend showed only the first plot's half of itself.** Two marks coloured by one column
  get *one* key between them, and it has to say both things: a bar fills its swatch and a point
  strokes one, so the merged legend carries `fill` and `stroke` alike. Where they disagree the first
  view's answer stands, with the two exceptions upstream names — a circle wins over any other glyph,
  and two different titles are joined rather than one being dropped. Then the *merged* legend's own
  channels decide what comes out of the symbol encoding: a point's `fill: transparent` is right on
  its own and blanks every swatch once a scaled `fill` merges in beside it.
- **A union domain was never sorted.** `{"fields": [...], "sort": true}` orders the *combined* set —
  upstream counts each part, aggregates the counts together, and only then sorts — where this engine
  had ordered each part and laid them end to end. With two plots of one table that puts the second
  plot's first category after the first plot's last, and the colours come out shifted by one.
- **A rect-based mark with nothing on one channel was 18 units tall.** `defaultSizeRef`'s
  `!hasFieldDef` branch spans the plot instead, keeping back exactly what a band scale's inner
  padding would have kept back — `0.75 * height` for a tick. It writes the *plain* size name rather
  than the plot's own, relying on the alias a plot with no gridline scale already defines.

The last of those is not about concatenation at all: a single-view tick plot with one encoded axis
was equally wrong, and no fixture had drawn one.

### Repetition: nothing but a rewrite

`repeat` is not a compiler construct at all. Upstream normalizes it away before anything is compiled
(`CoreNormalizer.mapNonLayerRepeat`), into a concatenation of the same view with `{"repeat": …}`
replaced by a real column name in each copy — so once a concatenation compiled, the whole of `repeat`
was a rewrite, and all three of its forms went in together and passed on arrival:

- a **list** repeats over one variable and lays the copies out under `columns`;
- **`row`/`column`** cross two lists into a scatter-plot matrix, and the grid's width is the number
  of columns rather than `columns`, which upstream reports as unsupported there;
- **`layer`** stacks the copies in one plot, so it stays a layer and never becomes a concatenation.

Two details carry the naming. Each copy is *named* — `child__amount`, `child__row_amount_column_score`
— and upstream's model takes a spec's own `name` over the one its parent offered it, which is why a
repeated chart's scales read `child__amount_x` rather than `concat_0_x`; honouring a declared name in
a concatenation's plots and in a layer's members is all that took. And the grid takes `align: "all"`
where a plain concatenation takes `each`: the copies are one view drawn several times, so their rows
do line up, and the normalizer says so in the spec it produces.

Three fixtures passing untouched is worth stating plainly rather than quietly: it is what a faithful
port of the layer beneath is supposed to buy, and the one defect it did find was a rewrite compiled
and then thrown away — the normalized specification has to *replace* the one being compiled, not
stand beside it.

### `resolve`, which is what makes a chart dual-axis

`resolve` decides which of a composition's scales and guides its children share. It is the whole
difference between a layered chart and a **dual-axis** one — the same two marks over one `y` say they
measure the same thing, and over `{"scale": {"y": "independent"}}` they each get a scale and an axis
of their own — and it is what puts a colour key under every plot of a concatenation instead of one
beside the whole chart.

The naming is the mechanism, and it had already been half built: a concatenation names its plots'
positions `concat_0_x`, and independence is the same idea one level down. So the scale prefix became
an explicit **name per view per channel**, and everything that mentions a scale reads it from there.
A shared channel keeps its plain name and one component takes every view's domain; an independent
one is named for the child that owns it and each child gets a component of its own.

Three rules came out of upstream with it:

- **A resolve governs the outermost composition and nothing below it**, as it does upstream, where
  every model carries its own. The child it names is a *composition child*, not an expanded view: a
  line that draws its own points is two views under one layer, and naming the scale from the view
  gives the line and its points a scale each and draws them apart.
- **An independent scale forces an independent guide** — `parseGuideResolve` — since one axis cannot
  label two scales.
- Two independent axes on a channel would otherwise stack on the same side, so upstream counts how
  many have landed on each and moves one across when they do not match, counting the side each
  *asked* for rather than the side it ended on. And only the first rules gridlines: two sets across
  one plot measure different things and say neither.

### The gallery, swept: 627 examples against upstream's own compiler

Vega-Lite ships 627 example specifications, and compiling is a pure function of the specification —
no data is needed to compare two compilers. So every one of them was compiled by upstream 6.4.3 and
by this one, and the outputs compared property by property. That is a *measurement*, not a gate: the
examples are not fixtures here, and nothing about them is checked in.

**124 of 627 matched exactly** at the start, and **159** do now. 16 were refused by name, and of those 8 are geographic,
3 are a facet inside a facet, 2 a repeat inside a concatenation, and 2 are the `trail` mark — which
this runtime draws and this compiler had simply not been told about.

The value is the *ranking*. Clustered by root cause, by how many example files each one affects:

| Files | Cause |
| --- | --- |
| 135 | the accessibility description string differs |
| 86 | `as` defaults missing on `fold`/`flatten` |
| 56 | `mark.clip` dropped |
| 42 | a stack's `sort` block missing |
| 41 | an explicit axis title should short-circuit the merge |
| 36 | a one-dimensional chart sized 300, not 20 |
| 24 | a bar on a continuous scale sized `step - 2`, not `continuousBandSize` |
| 15 | `tickMinStep` on a binned axis |
| 8 | a gradient legend's opacity encode |
| 5 | `config.scale` leaking into the Vega config |
| 2 | `toNumber` emitted as `tonumber` |

Three were fixed here, and they were chosen for what they do to the *picture* rather than for
frequency:

- **`mark.clip` was being stripped** as a Vega-Lite-only property. It is a Vega mark property and
  goes straight through; it is what keeps a line inside a domain narrower than its data.
- **A channel with no scale at all is not a continuous one.** `defaultUnitSize` falls to the
  *discrete* size for it, which is a step — so a strip of ticks or a single total is twenty units
  deep, not three hundred. This was the most common way a gallery chart came out the wrong size.
- **A rect-based mark on a continuous scale takes `continuousBandSize`** — five units for a bar —
  where this compiler used `step - 2`, making it nearly four times too wide. `getBandSize` asks the
  scale's kind first and reaches `discreteBandSize` only where the domain is discrete.

The count went 124 → 138 clean, which understates it: a cause is fixed for every file that carries
it, and most of those files carry several.

The fixture written for the size rule then found a fourth, and it was this branch's own: the size
merge looked scales up **by channel** in a map keyed by scale **name**, so inside a concatenation
every plot looked scale-less and three plots of different depths merged into one signal. It had been
invisible because every existing concat fixture declares its sizes.

A second pass took six more, working down the same ranking:

- **`toNumber` was emitted as `tonumber`.** The parse expression was built by concatenation, and
  Vega has no such function. A dated parse names a *specifier* rather than a type, too, so
  `date:'%d/%m'` is `timeParse` and `utc:` its UTC twin.
- **The `trail` mark was refused** although the runtime draws one — it was simply missing from the
  compiler's mark list.
- **`fold` and `flatten` always write their output names**, filling in `key`/`value` and each
  field's own name. Vega would default them the same way, but the names are what everything
  downstream groups and scales by, so upstream settles them once.
- **`{"binned": true}` is `isBinned`, not `isBinning`.** Reading only the string `"binned"` binned
  an already-binned column a second time, putting a whole `bin` transform and its extent signal into
  the data flow and shifting everything after it. One example went from thirty differences to one —
  which was the second half of the same rule: a binned field that states its **step** gives its
  scale a `bins: {step}`, the ends coming from the domain.
- **An explicit axis title wins outright across layers**, rather than joining. A layer that names
  its axis has said what the axis measures; joining titled one `Value, PM2.5 Value`.

And the fixture written to guard those found three more, all of them this branch's own and all from
the `resolve` work: a bin's offset expression named the *unprefixed* scale, so it read `scale("x", …)`
inside a plot whose scale is `concat_0_x`; a layer **inside** a concatenation got two of every axis,
because independence is resolved between the composition's children and a concatenation's children
are its plots, not the layers within one; and scale **ownership** was being decided by usage rather
than by the resolve, so a colour scale only one plot happened to draw with was written inside that
plot instead of beside the chart.

A third and fourth pass took seven more, read straight off the ranked list:

- **The implicit parse stored its type capitalised** — `"Number"` where upstream writes `"number"`.
  It had been invisible because the *expression* was built by concatenating that same capital, so
  two wrongs read `toNumber`; fixing the expression showed the `format.parse` underneath.
- **`config.scale` is Vega-Lite-only** and was reaching the Vega config, and **`config.mark` keeps
  only what Vega understands** — `color` and `filled` are resolved into a mark's own fill and stroke
  long before Vega sees anything.
- **A normalized stack's axis is a percentage**, `config.normalizedNumberFormat`, defaulting to
  `.0%`. Left off, an axis reads 0, 0.2, 0.4 for what the chart draws as fifths of a whole.
- **A `d` format asks for whole numbers**, so `defaultTickMinStep` is 1 — read *after* the
  specification's own axis block, because that is usually where the format comes from.
- **A `joinaggregate` over the whole table writes no `groupby` at all.** An empty list is a
  different statement from silence.
- **A colour ramp is painted at the mark's own opacity**, so a legend beside a chart of translucent
  points is as translucent as they are.
- **`config.scale.zero` is a fallback, not an override.** A bar or an area *measures from* zero and
  that is not a preference — the length of the mark is the value — so the theme settles only what
  the rules leave open, which is what frees a gantt chart's ranged bars without freeing these. And
  a `config.style` block is a mark config under another name, so it loses the same Vega-Lite-only
  properties: `point: true` on a line is a *normalizer's* instruction and means nothing to Vega.

Two runtime gaps are recorded rather than fixed, both found by fixtures written here and both
withdrawn from the corpus because a fixture has to pass:

- **A non-group mark's `clip` is not applied in the runtime.** The compiler emits it now, but the
  scene still measures the clipped-away points, so a clipped line makes the chart wider than
  upstream's.
- **A plot inside a concatenation is placed one unit low** where it is a ranged bar or a layer. Its
  specification matches property for property, so this is the runtime's — every mark, gridline and
  tick in that cell is off by exactly one.

### Five defects behind one chart pasted into the demo

A population pyramid — Vega-Lite's own example — pasted into the demo came out as a scatter of
squares along a diagonal. It states **no channel types at all**, and this compiler had been falling
straight through to `nominal`, which turns a summed measure into a category per distinct total.
`defaultType` is not "look at the data" — nothing has read a row when it runs — it is read off the
definition: a latitude is a number and a shape is a category whatever they carry; a `sort` written
as a **list** makes a field ordinal; a `timeUnit` makes it temporal; a `bin` or **any aggregate
except `argmin`/`argmax`** makes it quantitative; and a stated `scale.type` answers by category.
Only `count` and `bin` were honoured here.

Fixing that let the same chart name four more, none of which the corpus had reached:

- **`config.view` becomes the `cell` style**, not a `view` one — "View's default style is `cell`",
  renamed on the way through `stripAndRedirectConfig`. A chart telling its plotting area not to draw
  a border was still drawing one.
- **`config.axis.grid: false` was ignored.** Whether there are gridlines is a default, and a theme
  settles it for every axis at once; reading only the channel's own `axis` block left them on.
- **A legend along the top runs horizontally** (`defaultDirection`), and `"title": null` **drops the
  property** rather than writing an empty one — `assembleLegend`, whose comment is "title schema
  doesn't include null".
- **A sorted discrete domain reads the pre-aggregation table**, and the test is `isBoolean` on the
  *settled* sort rather than on what the specification wrote. A plain `true` reads the table being
  drawn; an aggregate, or the `{"order": "descending"}` that a bare `"descending"` settles into,
  reads the raw rows. Testing the written form missed the string spelling — which is the one a
  pyramid uses to run its ages downwards — and with it the whole `data_0` that request creates.

And one in the runtime: **`format: "s"` was not a format at all.** The specifier pattern accepted
`d`, `f`, `e` and `%`, so `s` failed to parse and fell back to plain number text. An axis shares
**one** SI prefix, chosen from its largest tick — d3's `tickFormat` pins it there — so the labels
read `−1.0k` beside `0.0k`, where formatting each value alone would put `0` beside `−1.0k`. The
precision comes from `precisionPrefix`: decimals enough to tell one step apart *after* the division.

Five defects behind one pasted chart is the argument for the demo's paste box, and for a corpus
written from upstream's own examples rather than from what this compiler already does.

### A concatenation inside a concatenation

A dashboard is a column of rows, and it needed the flat list of plots to become a tree. Only the
*shape* did: everything a plot has — views, scales, axes, a size — still belongs to the leaves, so
the leaf list stays flat and nothing downstream knows about the nesting. What the tree carries is
three things upstream does at every level rather than once:

- **The names compose.** `concat_0_concat_1_x`, because `defaultScaleResolve` makes positions
  independent at each level, and the level below inherits the name of the level above.
- **The sizes merge per level, innermost first**, because a nested concatenation has to settle its
  own size before the level above can ask what that size is. What a nested one contributes upwards
  is whatever *its* children merged into — and nothing where they disagreed, which is what stops a
  column holding a row of plots from claiming a width of its own. A level that merges takes the
  name over from its children, so the signal is written once and at the level that settled it.
- **The signals come out in tree order**: each level's own before it recurses, and within a level
  `width`, `height`, `childWidth`, `childHeight`. That is `assembleLayoutSignals`, and it was the
  only thing the first attempt got wrong — everything else matched on the first run.

A nested concatenation's group carries a `layout` and nothing else: no style and no `encode`, because
it is not a plotting area, only a place its plots are arranged in.

### A layer inside a layer, which the naming was already carrying

A nested layer needs nothing new. Its names simply run deeper — `layer_1_layer_0_marks` — which is
exactly what a composite mark inside a layer already produced, so the machinery had been there since
the box plot and only the refusal was in the way. Collecting the members recursively is the whole
change, and the fixture passed on arrival.

The one thing the recursion has to hold on to is the name of the **outermost** member. That is the
child a top-level `resolve` speaks about; the nesting below it is not a level anything resolves
against, and naming an independent scale from the innermost view would give a line and its
annotation a scale each.

### Parameters, where they are values and not selections

A **variable** parameter is a Vega signal and almost nothing else — a value, optionally an `expr`
computing it from the others, optionally a `bind` describing the input that sets it — because Vega
already has the construct. `assembleParameterSignals` is the whole translation, and the signals go
after the layout's own, a parameter being able to read a size and not the other way about.

What the fixture found was not in the parameters at all. `{"value": {"expr": "tint"}}` is how a chart
reads one into a graphic property, and it is **not a literal object**: upstream turns the expression
into a `signal` in the same value ref, so a `datum` written that way still passes through its scale
and comes out `{"scale": "y", "signal": "marker"}`. Writing it out as a value paints the mark with an
object, which a renderer reads as nothing at all — and nothing about it is reported, because a value
is exactly the thing a compiler is not supposed to look inside.

A **selection** parameter is still refused, one at a time rather than as a block, so a specification
mixing the two kinds gets the variables it declared.

### Ordering a trellis by a total none of its rows carries

A facet `sort` that names an aggregate — `{"field": "amount", "op": "sum", "order": "descending"}` —
is the ordinary way to put the biggest column first, and it needs the key computed before anything
can be ordered by it. Upstream computes it **twice**, under two names, and both are needed:

- the **domain** dataset's aggregate gains `fields`/`ops`/`as` and holds it as `sum_amount`, which is
  what the header bands sort on, each of their rows already being one cell's worth;
- the **cell** group's own `from.facet.aggregate` computes it again over its own partition as
  `sum_amount_by_era` — suffixed with the faceted field, so the two names cannot collide — which is
  what the cells sort on.

`DEFAULT_SORT_OP` is **`min`**, not the `sum` the encoding sorts might lead a reader to expect.

Two forms are still reported by name: a sort by a written-out **list**, whose place in it has to be
computed onto every row as a column of its own; and an aggregate sort on a facet gridded **both**
ways, where upstream writes the key onto the rows first so that each cell can take the greatest of
its own. Both are data-flow work rather than another name for the same aggregate.

### A trellis whose cells run backwards, and the two ways it was wrong

A facet channel's `sort` orders the *cells*, not anything inside one, so it lands on the group mark
that makes them and on the header bands beside it. Ours wrote `ascending` there unconditionally.
A bare `"descending"` is now honoured; a sort **object** or a sort **array** on a facet channel
needs a key computed onto the rows before the cells are made, which is data-flow work this compiler
has not done, and both are now reported by name rather than quietly ignored — which had left the
cells in the wrong order and said nothing.

The fixture then found two more, both in the runtime and both about the cells this compiler had been
emitting correctly all along:

- **`aggregate: {cross: true}` was ignored**, so a trellis crossed by two fields lost the cells no
  row carried and went ragged: the cells after a gap slide into it and every header beside them
  names the wrong one. Vega crosses only where there is more than one dimension, and it *adds* the
  missing cells after the ones the rows made rather than rebuilding the order.
- **An empty cell drew its gridlines.** Vega instantiates a faceted group's subflow only for keys
  that rows arrived under, so a cell `cross` invented to keep the grid rectangular is a group with
  no contents at all — visibly different from an empty plotting area with axes ruled across it.

### More than one table, which had been silently one

A layer or a plot may name its own `data`, and this compiler had been building every view's chain
onto the **first** view's source. A layered chart with a rule at a threshold from a second table
drew that rule against the bars' rows: a chart that is wrong rather than one that is missing, and
nothing reported it. It was found by asking what a construct already refused for a concatenation — a
plot with its own dataset — did for a layer, where nothing refused it at all.

The shape is upstream's. Every table the chart reads is a root, in the order it was first asked for,
because that order *is* the numbering; a `lookup`'s joined table is a root here too, since a join
reads a second table rather than deriving from the first. Then the last step of `assembleRootData`:
"move sources without transforms to the beginning" — a dataset that derives from nothing and does
nothing is a table the chart was handed, and Vega has to have it before whatever joins against it.
That rule replaced the special case the `lookup` work had put in for exactly one joined table.

With it, a concatenation's plots may each have their own dataset, and that refusal is gone.

### The facet operator: one rewrite and one layout

`{"facet": …, "spec": …}` is two constructs wearing one name, and only the first is a rewrite.

A **`row`/`column`** facet operator is the same chart as the same two channels written in the view's
encoding — compiled side by side, upstream's two outputs are byte for byte identical — so the
channels move down into the encoding and nothing else changes.

A **wrapped** facet, `{"facet": {"field": …}, "columns": n}`, is a layout of its own. A grid of two
fields knows its shape: the columns are the column facet's values. A wrapped facet has one list and
a number to wrap it at, so upstream *computes* both — `ceil(length(facet_domain) / columns)` rows and
`min(length(facet_domain), columns)` columns, as two `sequence` datasets — and draws the shared axes
once per **position** rather than once per value. The caption moves with it: there is no band of
column headings to name a cell in, because the columns are places and not values, so every cell
carries its own caption and the heading over the grid is a `column-title` naming the field.

Both fixtures then failed on the same runtime defect, and it is the kind worth naming: **a trellis's
cells came out in the order the rows arrived rather than in the order the specification asked for.**
A mark's `sort` fields are *field accessors on the scene item* — Vega hands them to `vega-util`'s
`field()`, so `datum["era"]` is the path `datum` → `era` — and this engine read only `x` and `y`,
being the properties it could see an item having. Everything else was silently ignored, which for a
faceted group means its own facet key: the one thing that decides which cell goes where. A chart with
its categories already in order hid it completely, which is why every fixture up to now had.

### Faceting: both halves, in three fixtures

`row`, `column` and both at once compile *and* draw. `faceted` (one row of cells), `faceted-rows`
(one column of them) and `faceted-grid` (two by two) each match upstream's compiler property for
property and upstream's renderer mark for mark, surface included — the cell group faceted from the
data, the `column_domain` and `row_domain` datasets the layout counts and the headers title
themselves from, the `layout` block, the `child_width`/`child_height` signals, the facet fields
joining every grouping so a stack stays inside its cell, and the axes *split*: the gridlines stay in
each cell where the data is, and the labelled axis moves out to a band drawn once for the whole grid.

Six defects on the way, every one of them a silence:

- **A `column-footer` was taken for a cell** and joined the grid, shifting every real cell along and
  widening the chart by a whole column. Vega-Lite puts a trellis's shared x axis in a column footer
  and its y axis in a row header, so the tick labels are drawn once rather than under every cell;
  the runtime knew the header roles and not the footer ones.
- **A sized group did not give its own `width` to what was inside it.** A gridline in a cell spanned
  the *chart's* width rather than the cell's, because the subscope's size came only from a group's
  own signal declarations and a Vega-Lite cell states its size in an `encode` block instead.
- **A band of labels sat at the grid's own half-unit edge.** Upstream rounds that margin *outwards*
  to a whole unit — `floor` on the near side, `ceil` on the far one, each measured against zero as
  well as against the cells (`layoutHeaders`, in `vega-view-transforms`) — so a row header clearing
  a cell whose stroked border reaches −0.5 goes to −1, and every guide inside it inherits the half
  unit. `trellis()` is now a port of that function rather than an approximation of it, which also
  settled two cases nothing had exercised: a *footer* is aligned with the last row or column and not
  the first, and `layout.offset` — which Vega-Lite writes on every faceted chart, to keep a heading
  ten units clear of the captions under it — was parsed, reported and dropped.
- **A grid's heading was centred over its headers instead of over its cells.** Upstream centres it
  along the bounds `gridLayout` returns, which are the cells alone, so a trellis with wide y-axis
  labels down its left still has its heading over the plots. Ours drifted left by half a row header.
- **A group mark's `title` ignored its own `style`.** Every caption in a trellis asks for
  `guide-label` and every heading for `guide-title`, and both were being set at a chart heading's
  thirteen points, in bold. Upstream's `guideMark` *replaces* the default `group-title` style with
  whichever is named, so the four style blocks Vega ships — `guide-label`, `guide-title`,
  `group-title`, `group-subtitle` — are now written down beside the five that were already there,
  and a title reads its size, face, weight and colour through the one it names.
- **A chart with no declared `width` was given a plotting area of 200 by 200**, so a faceted chart
  came out a whole phantom chart wider than upstream's. Upstream seeds the signal with `spec.width
  || 0`: a specification with no size is not an incomplete one, it is a chart measured entirely by
  what it draws, which is how every faceted Vega-Lite chart is written. Probed rather than reasoned
  about — one rect at (10, 10) and no width renders 30 by 20 plus its padding. The default is zero
  now, and the diagnostic that used to announce the substitution says instead, at `info`, that the
  surface comes from the contents.

The last of those was found by a fixture written *because* the ported layout had no test for it, and
it failed on arrival: **a row-faceted chart put its x axis in a column header and drew it above the
chart.** Which band a shared axis lands in follows the axis's own orientation — top or left is a
header, bottom or right a footer (`getHeaderType`, `compile/header/parse.ts`) — and the facet
channel only decides whether that band is one group per cell or a single one. The compiler had been
choosing the band from the facet's *direction*, which agrees with upstream for a column facet and is
upside down for a row facet.

What is left is `align` and `bounds`. This engine grids cells the way `align: "each"` does and
Vega-Lite asks for `"all"`; the two agree whenever the cells are the same size, which they are on
every fixture here, and both are still reported by name rather than assumed away.

### Four fixtures aimed at combinations, six more silences

`line-points`, `conditional-test`, `scale-overrides` and `stack-center` were written to cover
constructs the corpus had never combined, and all four failed on arrival:

- **A condition on a `test` was refused as though it needed a selection.** It does not: a `test` is
  a predicate on the row, written in a `filter`'s own grammar, and it compiles to a Vega production
  rule — an array of `{test, …}` entries with the unconditional one last. Both sides now go through
  the same predicate compiler, which is what keeps `oneOf` spelled `indexof(…) !== -1` in one place
  rather than two. A condition naming a `param` is still refused, on its own, and the rest of the
  definition still stands.
- **A line that draws its own points was drawn as a line with a `point: true` encode channel** —
  a property Vega has never heard of, reported by nobody. Upstream normalizes it into a *layer*
  before compiling, and that pass now exists (`Normalize.kt`): the base mark with the overlay
  properties stripped, then the overlay marks over the same encoding. An area fades to 0.7 first so
  what is drawn over it stays legible, the overlay inherits the stack offset (a `point` does not
  stack of its own accord, and without it the points sit at the raw values rather than on the band
  they belong to), and `x2`/`y2` are dropped because a line given a second position becomes a rule.
- **Two layers over one dataset each parsed it separately**, so the second layer's chain hung off
  the source rather than off the first's parse — a different dataset, differently numbered, and one
  of them never coerced at all. Upstream's `MergeParse` optimizer hoists the parse every branch
  agrees on above the fork and re-parents **every** branch onto it, including a branch that asked
  for no parse; that is now `DataNode.mergeParse`.
- **A quantitative column was never coerced to a number.** Two rules, both upstream's, both about
  *comparison*: a `min` or `max` aggregate needs numbers or it answers with the alphabetically
  smallest, and a path mark sorts its rows along its dimension, so a line through 1, 10, 2 is drawn
  in that order. Neither shows on a chart whose data happens to arrive typed.
- **A point scale was measured as if its inner padding were zero.** It is always 1 — n points have
  n−1 steps between them — and its `padding` is the *outer* one: vega-scale builds `point()` as
  `pointish(band().paddingInner(1))`, where `pointish` renames `padding` to `paddingOuter` and
  deletes `paddingInner`. Wrong in *both* engines here, in the same direction and by different
  amounts: the compiler wrote the wrong `bandspace` into the width signal and the runtime measured
  the wrong step range, so a point chart came out a whole step too wide.
- **A legend swatch repainted a colour it could not know.** Upstream builds the swatch from the
  mark's *own* colour encoding and then removes what would say the wrong thing: the channel this
  legend explains, and any colour that is a scaled field, since a swatch has no datum to scale.
  Building it from the mark's *default* colour instead looks identical whenever the mark has no
  colour encoding, and paints a size legend's swatches in the default blue on every chart that does.

And one that is not a silence but a wrong reader: **a mark's spoken description was assembled from
the guide titles**, so hiding an axis title with `axis: {title: null}` — a restyling — dropped the
whole channel out of what a screen reader says. Upstream reads the *field's* own title there.

### Four more, aimed at the same places from a different angle

`facet-legend`, `binned-facet`, `text-format` and `sort-array`. The first passed on arrival — a
legend beside a trellis is placed against the whole grid and already was — and the other three
found four more silences:

- **A binned field inside a facet lost its domain.** The `bin` transform publishes the boundaries it
  chose as a signal, and the transform names that signal through the *view* while the scale reading
  it did not: inside a facet every view is `child_`, so the scale read a signal that was not there.
  The two spellings agreed for exactly as long as the prefix was empty.
- **A `format` on a channel was ignored.** `{"field": "v", "format": "$.2f"}` on a text mark drew
  the bare number, because the text expression read `config.numberFormat` and never the definition's
  own. Alongside it: the value a **normalized** stack announces is the share it takes, `end - start`
  through `config.normalizedNumberFormat`, not the quantity behind it — a description that says 3
  where the bar plainly shows three quarters.
- **A written-out `sort` order was dropped entirely.** Vega has no comparator that takes a list, so
  upstream computes each row's *place* in the list as a column and sorts the domain on the smallest
  place a category carries; a value the list never names falls past the end, which is what puts it
  last. Ours emitted `sort: true` and drew the categories alphabetically — a chart with the right
  bars in the wrong order and nothing said about it.
- **A sort with no `op` defaulted to `min` where upstream uses `sum`.** The distinction is whether
  the sort field is one of the stack's own dimensions: sorting bars by a field each category already
  has once means the smallest *is* the value, and sorting them by a field the stack accumulates
  means asking which column is tallest, which the smallest segment says nothing about.

Three simplifications the assembled domain makes were missing with it, and they are not cosmetic
when the comparison is property by property: a `count` has no field to count *of*, `ascending` is
the default order, and a sort on the domain's own field is the natural order with at most a
direction to it.

### Four aimed at the runtime, through the compiler

`impute-area`, `binned-legend`, `temporal-units` and `layer-independent` were written against
constructs whose compiled Vega the runtime had never been handed. All four failed, and the failures
split evenly between the two halves.

In the compiler:

- **`impute` was refused**, so a series with a hole in it was joined straight across the hole — a
  value nobody measured, drawn as confidently as the ones they did. It is Vega's own `impute`
  transform with the *other* position channel as its key and a path mark's series fields as its
  grouping; a method other than `value` is a `window` beside it, written back over the nulls.
- **A rule's own `size` was dropped.** With no encoding for a channel the *mark definition* still
  speaks, and `size` is the Vega-Lite name for what Vega calls `strokeWidth` — that renaming being
  exactly why it cannot pass through with the mark's other properties, and why it fell on the floor.
- **A `utc` time unit did not say so.** Vega's `timeunit` transform takes a `timezone`, and without
  it a midnight instant is bucketed against the viewer's own calendar rather than against UTC.
- **An axis over a cyclic bucket asked for a tick every forty units.** Upstream compares the
  *normalized* unit — the name with any `utc` taken off the front — against month, hours, day and
  quarter, and asks for no count at all: twelve months want twelve ticks, not seven.
- **A bucketed instant reached the end of its bucket on every mark**, where only a mark that
  *occupies* the span should. Upstream decides by whether the mark has a `timeUnitBandPosition`,
  which only the rect-shaped configurations define, so a bar over months reaches the end of December
  and a point over the same months sits on the first of it.
- **The invalid-value filter read the field's type rather than its scale.** A discrete scale shows
  an invalid value as another category, so only the fields feeding a continuous domain are filtered;
  ours filtered a binned colour column too. And the filter is keyed by the *raw* field, which is how
  one column bucketed two ways on two channels leaves only one of them.
- **A `bin-ordinal` scale wrote a domain it should not have.** Its domain is inferred from the
  `bins` property; naming a column of bin starts beside it describes something else.

In the runtime, four more, and every one of them a chart that drew:

- **A path mark ignored its own `sort`.** A `sort` orders the *items* a mark draws, and a line has
  only one — the whole series — so the ordering has to reach the points inside it. Without that, a
  row inserted into the middle of a series (by an `impute`, say) was drawn last, and the line went
  out to the end and doubled back to it.
- **`tickMinStep` was reported and dropped.** It does not place ticks; it caps the *count*, and a
  span holding one minimum step allows two ticks whatever was asked for. An axis over two months
  offered sixteen.
- **A `bin-ordinal` scale could not be built without a domain**, and its scheme was sampled at the
  extremes. Upstream takes a continuous scheme's buckets from *inside* it —
  `quantizeInterpolator` samples at (i+1)/(count+1) — so two buckets of `blues` are its thirds and
  neither is the white end. A discrete palette is *sliced* rather than spread.
- **A discretizing scale's legend was symbol swatches**, which the runtime said so about rather
  than pretending otherwise. It is now the colour bar cut into its buckets: equal bands whatever the
  cut points are worth, labelled at the joins between them. Probed against upstream on a four-bucket
  `quantize` scale before a line of it was written.

And the harness was wrong again, for the eighth time. The first band has no lower bound, and
upstream's scene carries a literal `null` there which its renderers draw as nothing —
`String(null)` had been writing the four letters "null" into the reference, and this engine drew
them. Fixed in `oracle-js/src/normalize.js` as well as here, which is where CONTRIBUTING says to
look when both sides agree and the picture does not.

### Three more, and a scale that answered the wrong kind of nothing

`aggregate-ops`, `binned-axis` and `offset-facet`. The last passed on arrival — grouped bars inside
a trellis are an offset band inside a faceted cell, and both already worked — and the other two
found four things:

- **Two layers aggregating one table did it twice.** Upstream's `MergeAggregates` folds sibling
  aggregates that group by the same fields into one, and the union of their measures is a *union of
  measures*: the emitted order follows each field's first appearance, which is what puts a `count`,
  whose field is nothing at all, after every measure of a column. Left apart, each layer read a
  dataset of its own and the whole numbering shifted.
- **A pre-binned column had no far edge.** `bin: "binned"` says the data arrives already bucketed,
  and the bucket's other end is a *second column* named by the secondary channel — which is the
  whole reason the form requires an `x2`. This engine looked for an `_end` column that was never
  computed. The same edge is what the row is announced by, as the span it covers rather than as two
  separate numbers, and it groups the stack alongside the start so that two bins beginning at the
  same place stay two columns.
- **A continuous scale of something that is not a number answered `null`, where upstream answers
  `NaN`.** The distinction only shows in arithmetic and there it is the whole answer: JavaScript
  reads a null as zero and propagates a NaN. Vega-Lite decides whether a bar is too thin to see
  with `abs(scale(x, a) - scale(x, b))`, and a pre-binned column has no `_end` to give it — so the
  question came back as a real zero, the bar was judged too thin, and it came out a quarter of a
  unit narrow and shifted along.

### Two more, and the last two ways a layer could cost a dataset

`datum-rules` and `axis-values`, both of which failed on the *numbering* rather than on anything
drawn — and numbering is where a layered chart's datasets say who reads what:

- **Two layers asking the same thing of one table asked it twice.** Upstream's
  `MergeIdenticalNodes` folds identical sibling branches into one; left apart, each got a dataset
  and every scale domain read a union of two names where upstream reads one — the same rows,
  described twice.
- **A fork spent a dataset name on the table it split.** Where one of the branches is a bare
  *output* — a name and nothing else, which is what a layer reading the raw rows has — everything
  else belongs below it rather than beside it, and the fork disappears (`MergeOutputs`). A chart
  whose second layer draws a rule at a constant had every dataset numbered one too high.
- **An axis told which ticks to show was also told how many.** `defaultTickCount` returns nothing
  once `values` are given, and asking for a count beside the list is a second answer to a settled
  question.

A third fixture was written for **`strokeDash`**, withdrawn when it turned out to name a refusal
rather than a defect, and then put back once the refusal was gone. The channel tells two series
apart by their *pattern* rather than by colour, which is what a reader who cannot rely on colour
needs and what a chart printed in one ink has. Three things were missing and each was its own kind
of silence: the compiler refused the channel outright; the runtime dropped a *scaled* dash array,
drawing every series solid and identical; and a legend keyed on it was refused for naming no scale
it recognised. A fourth came out of the same reading — upstream's canonical-scale order for a
legend is `size, shape, fill, stroke, strokeWidth, strokeDash, opacity`, and this engine's began
with `fill`, so a legend encoding both `fill` and `size` was titled from the wrong one.

### What a mark says when you rest on it

`tooltips`, written on the way to the composite marks, which need one. Three silences, and the
first is the kind that is hardest to notice because the chart looks finished:

- **An explicit `tooltip` encoding was dropped entirely.** A specification asking for three fields
  in a tooltip got none, and nothing said so. There are three forms and they produce different
  things: a **list** of fields becomes an object of title-to-value pairs, which reads as a small
  table; a **single** field becomes that field's own value; and `tooltip: true` on the mark asks
  for every encoded field. An array of values is joined with *line breaks* in a tooltip and with
  spaces in a spoken description, which upstream does by building one and rewriting it.
- **Only the first entry of a channel written as a list survived the parse.** `tooltip`, `detail`
  and `order` all take one, and everything downstream read one definition — so a tooltip naming
  three fields named one, and a series split by two details was split by one.
- **A mark's `style` list left out its own type.** Upstream's `getStyles` is `[].concat(mark.type,
  mark.style ?? [])`, and the order decides which block wins: a mark that names a style is styled
  by its type *and then* by the name. Ours replaced the type with the name, so a `config.rule`
  block stopped reaching a rule that named a style of its own. Nothing in the corpus named one —
  the composite marks are what do, each part being `["rule", "errorbar-rule"]`.

### The composite marks

`errorbar` and `errorband` compile and draw: `errorbar` (a standard error either side of a mean),
`errorbar-iqr` (interquartile ranges, capped, drawn sideways) and `errorband` (a standard deviation
as a filled band with its edges). They are one rewrite with different parts drawn from it — a
summary of one continuous field per group, then a layer per part over that summary.

The rule worth reading twice is how the **extent** chooses the aggregates, because getting it
backwards gives error bars of a plausible size that mean something else. `stderr` and `stdev` are a
*width* measured from a centre, so they aggregate the centre and the width and then add and
subtract; `ci` and `iqr` are two *positions*, and so are two more aggregates. Which parts are drawn
is configuration rather than code — `config.errorbar` has `rule: true, ticks: false`, so a plain
error bar is one rule per group and asking for `ticks` adds the caps.

Three more silences came out of building it:

- **`aria: false` was ignored.** It takes a mark out of the accessibility tree, so there is nothing
  to say about it — no role description and no spoken summary — and it is a *mark* property rather
  than an encode channel. It is how a composite mark hides its own scaffolding: an error bar's two
  caps are read as part of the bar rather than as three separate objects.
- **An explicitly titled position still merged its title with the other end of the range.** An axis
  over `lower_v` and `upper_v` read "v, upper_v" where upstream reads "v", because a *stated* title
  short-circuits the merge (`getFieldDefTitle`).
- **A parse stayed where the flow put it.** Upstream's `MoveParseUp` lifts it above every step that
  does not *produce* a field it reads, which is where a composite mark's own aggregate sits — so
  the coercion of a column happened after the summary that read it rather than before.

And one difference that is a deliberate refusal rather than a defect: an `extent` of `ci` needs
`ci0`/`ci1`, a bootstrap over a thousand random resamples, which this runtime declines because a
scene has to be reproducible. The compiler emits it correctly; the runtime says why it cannot draw
it, and `stderr` is what a symmetric error bar wants anyway.

**`boxplot` compiles and draws too**, and it is the one that needed real machinery rather than more
rules. A box plot is a *layer of layers*, and it has to be, because its parts read different tables:
the quartiles are found first and joined back onto every row, which is what lets a row be compared
with its own group's box; from there the flow forks, one branch keeping the rows outside the
whiskers and drawing them as points, the other keeping the rows inside and taking their extremes as
the whisker ends. The box itself is a third summary of the raw rows.

Four things had to exist for that:

- **Nested layer names.** A composite mark names its parts *relative to itself*, so a box plot's
  whiskers are `layer_0_layer_1_layer_0`. The names are what a mark drawn from another mark reads
  by, so flattening them is not cosmetic.
- **One node per transform step.** Two views that begin with the same steps and then differ are one
  flow that forks, not two flows — and only a per-step node lets the shared prefix be recognised as
  shared. It changes nothing where there is no fork; where there is one, it is the difference
  between finding the quartiles once and twice.
- **The more capable scale type winning a shared channel.** A box plot's parts are a bar, two rules
  and two ticks, and they share one x scale: upstream ranks the types and puts `band` above `point`
  above everything continuous, "as they support more types of data". This engine had been keeping
  whichever layer was declared first, which for a box plot is the outliers — a point scale, and a
  chart whose boxes had no band to sit in.
- **A conditional with no unconditional part falling back to the mark's own value.** A median tick
  is white *unless* its box has no height, and the white has to come from somewhere for the rule to
  have anything to fall through to.

### Joining a second table, and the row that finds no match

`lookup` was the last refused transform, and the reason it was refused — the joined dataset has to
be assembled and *named* beside the view's own — turned out to be the whole of the work: a join
reads a second table, it does not derive from the first, so its data stands beside the source rather
than below it and is numbered in the same sequence. The one thing to get right in the translation is
that both grammars say `fields` and mean different things: Vega's `fields` are the rows' own columns
to match on and its `values` are what to bring across, where Vega-Lite's `lookup` is the first and
`from.fields` the second.

The fixture then found a runtime defect of a kind worth naming, because it is the failure mode this
project exists to catch. **A row that matched nothing disappeared from the axis while still being
drawn.** A discrete domain was collected with the missing values filtered out, so a column whose
join found no match had no band to stand in — and its bar was drawn anyway, at the origin, on top of
whatever was there. Vega counts a null as a value: it gets a band of its own, at the front of the
domain, labelled `null`. A chart that says a category has no name is honest; one that quietly draws
it on top of another is not.

### An instant is what a *time unit* makes it, not what the type says

Five fixtures — `nested-fields`, `invalid-modes`, `timeunit-ordinal`, `trail`, `date-predicates` —
and one rule behind three of them. `isFieldOrDatumDefForTimeFormat` in upstream's `channeldef.ts`
reads a field as an instant when it is typed temporal **or** carries a `timeUnit`, and this compiler
was reading only the type. A month bucketed onto an ordinal scale is the ordinary way to write a
seasonal chart, and it came out three ways wrong at once: its spoken description read the bucket's
raw milliseconds, its axis was given a time specifier without the `formatType` that says to read it
as one, and the column was never parsed as a date on the way in. The guide side is
`guideFormatType`, which states `time` wherever the *scale* does not already imply it — a `time`
scale needs no telling, a band of month names does.

The same reading fixed the legend, which had no `format` at all for a bucketed field, and needed
`formatType` and a computed `format` added to `LegendSpec` for the runtime to honour it.

### What a chart does with a value it cannot place

`mark.invalid` is five modes and this engine had one. Upstream's default —
`break-paths-show-path-domains` — is a macro for two different behaviours: a path *breaks* at the
gap and keeps the row, everything else *drops* the row. Both consumers were hard-coded to the mark
type, so `invalid: "filter"` on a line was ignored and `invalid: null` had no meaning. The mode is
now normalised once, on the view, and the mark's `defined` and the data flow's filter both read it —
they have to agree, because filtering a row the path was going to break at removes the break along
with the row.

`defined` itself was asking the wrong question: it looked at x and y and at the field's own type,
where `defined.ts` asks it once per *scaled* channel and answers from the scale's domain. A line
coloured by a continuous field breaks where that colour is missing too, and a channel whose scale
has a discrete domain is never invalid — a null is simply another category.

Two runtime defects came out of the fixture, both in the same function. **A series whose defined
points never numbered two vanished entirely**: runs of one point were dropped, and with no run left
the whole mark node was dropped with them. And **the point that broke a series was not in the
scene** — it is not drawn, having no length, but it is one of the series' points, and upstream's
scene keeps it. Both are now subpaths: one point each, drawing nothing.

### A field named through a path

`record.high` is a column's *name* in Vega and a path into a nested object in Vega-Lite, and the
translation between them is a formula per nested field plus a `format.parse` that may come out
empty and is written anyway. Reading a nested field without it looked for a column no row had.
Beside it, the *derived* rule from `data/parse.ts`: a column a transform computed is never parsed,
because it already has the type its transform gave it and the loader has never seen it — which is
why a `density`'s own output columns stopped being asked for as numbers.

### A signal is an identifier, and a column name need not be one

`varName` in upstream's `util.ts` runs over every name a model hands out, and this compiler was not
running it. A column called `IMDB Rating` gives a bin signal `bin_maxbins_10_IMDB Rating_bins`,
which is not a name Vega's expression language can read — every scale domain, every extent and
every axis that mentioned it was a parse error waiting to happen. Anything outside `[A-Za-z0-9_]`
becomes an underscore, and a leading digit takes one in front of it.

Two more from the same sweep. A stated `scale.scheme` is a **range** — `parseScheme` returns
`{scheme: name}` and that becomes the scale's range — where this compiler wrote it as a property
beside the `"category"` range it would otherwise default to; Vega read the range, so a chart asking
for `category20` got the ten-colour scheme. And legends are grouped by the **field** they encode,
not by the scale: one field encoded twice, as a colour *and* as a size, is one key to the reader and
one legend whose swatches carry both. The scale's own prefix stays in the key so a composition
resolving its legends independently still gets one per plot, and the discreteness with it, since a
ramp and a set of swatches cannot be the same legend.

### Three answers to "does this domain contain zero", not two

`domainHasZero()` returns `definitely`, `definitely-not` or **`maybe`**, and the third is the common
one: a domain read from a column is not known until the data is. This compiler had a boolean, and
read `maybe` as no — which put a baseline at the bottom of the data rather than at the origin
whenever the column straddled it. The `maybe` case does not decide at all; it hands the question to
Vega, which has the data: `scale('y', inrange(0, domain('y')) ? 0 : domain('y')[0])`.

That is also the answer the last invalid-value mode needed. Under `show`, a value no scale can place
is *drawn*, at the scale's own output for one, and the channel becomes a production rule whose first
arm tests for it. Every other mode has already dealt with the row — dropped it, or broken the path at
it — so `show` is the only one that reaches the encoding at all.

`labelExpr` turned out not to be a Vega axis property. `assembleAxis` destructures it out and writes
`encode.labels.update.text` from it, so passing it through was silently ignored, and passing it
through on the *gridline* axis named an encode block for a mark that is not drawn.

### A number in an expression is compared as text, so it has to be written as JavaScript writes one

`max(4.0, bandwidth('x'))` and `max(4, bandwidth('x'))` are the same instruction and a different
string, and the comparison is over the string. A Kotlin `Double` interpolated into an expression
carries a decimal point Vega-Lite's own output never has — and the display canonicaliser is not the
answer either, since it *rounds*: `(1 - 0.7) / 2` is `0.15000000000000002`, and `0.15` is a
different number. `Fields.expressionNumber` writes the shortest text that reads back as the same
double, which is what `String(n)` does. Nineteen files in the gallery differed on nothing else.

### An offset is a lane, and a lane is not always inside a band

Three rules, all from `getOffsetRange` and `rectPosition`, and each one a chart that was drawn
wrongly rather than incompletely:

- An offset with **no position** beside it has no band to sit inside, so it spans the whole plot
  measured from the middle — `[-width/2, width/2]`. The bars had been stacking in the middle.
- A **continuous** position bucketed by a time unit does have a band: one bucket wide, measured
  through the scale, inset by half the nested padding at each end. Without it a grouped bar over a
  year axis had no lane at all.
- An offset encoding takes a rect **off** the bucket's edges, so the bucket is no longer what the
  rect spans and the positioning is the ordinary banded one — and the band being filled is the
  *offset's*, not the position's, which is what makes it `bandwidth('xOffset')` rather than the
  five units a lone continuous rect gets.

Beside them, `bandPositionForBandSize`: a rect asking for a *fraction* of its bucket
(`{"width": {"band": 0.7}}`) is drawn inside it, both edges moving in by half of what is left over.
The interpolation happens in *data* space and the scale is applied to the result, which is not the
same as interpolating two scaled positions once the scale is not linear.

### An empty title is not a title

`assembleTitle` writes `titleString ? {title} : {}`, so an axis the specification titled `""` has no
caption at all — and the tooltip and the description read `fieldDef.title || defaultTitle(...)`, an
`||`, so the same empty title falls through to the field's own name there. One value, two opposite
readings, and this compiler had a third: it wrote the empty string into both.

`config.mark.tooltip` was also being stripped as a Vega-Lite-only property. It is not one — upstream's
`VL_ONLY_MARK_CONFIG_INDEX` lists eight keys and that is not among them.

### An explicit value settles a merge before any tie-breaker runs

`mergeValuesWithExplicit` in `compile/split.ts` is three lines and this compiler had none of them:
a value the *specification* stated beats one derived from a field, and only when both sides are
derived — or both stated — does the tie-breaker join them. One field encoded as both a colour and a
size, with a title written on one of them, is titled by the one that was written; this compiler
joined the two with a comma. The same rule reaches an axis, where the **guide's** own title opens
`title` in `axis/properties.ts` and settles it outright, so an axis captioned `Temperature (F)`
stays that rather than gaining `, record.high, normal.high` from the layers under it.

### The largest a mark may be drawn is not a constant

`sizeRangeMax` reads a *step*, and a step is not known at compile time when the position is
**binned**: the bin transform chooses its own boundaries. Upstream writes the expression instead —
`pow(0.95 * min(width / ((bins.stop - bins.start) / bins.step), …), 2)` — and this compiler was
writing the configured step, so a binned scatter's circles were sized against a band that was not
there. Beside it `interpolateRange`, for a scale that maps a continuum onto *buckets*: Vega cannot
interpolate a discretizing range itself, so the sequence is written out as an expression.

### Two transforms that take an `extent` are not extent transforms

`isExtent` excludes `density` and `regression` by name, and reading `extent` first turned a whole
kernel-density transform into an extent one — losing its field, its grouping, its output columns and
its resolve. `config.mark.tooltip` was also being ignored: `getMarkPropOrConfig` reads the mark
*and* the configuration, so a theme that turns tooltips on turns them on for every mark.

`impute.keyvals` written as a sequence is now generated rather than refused — `processSequence`
turns `{start, stop, step}` into a `sequence(...)` signal — and the fixture found a runtime defect
behind it: **`keyvals` was replacing the key domain instead of augmenting it.** Vega's own
documentation is explicit that "these values will be automatically augmented with the key values
observed in the input data", and reading it the other way dropped every observed key the list did
not name, so a series lost rows it already had.

A label's alignment also follows the side its axis is on — `defaultLabelAlign` compares the angle
against the axis's *main* orientation and flips when the axis has been moved — so a turned label
hanging off the top of a chart no longer anchors at the wrong end.

### A title reads differently depending on what kind of model carries it

`assembleTitle` splits on the model: a **unit or layer** anchors its title to the *group*, which
keeps it over the plotting area when an axis widens the drawing to its left; a **composition**
cannot, its groups being laid out with no one plotting area to sit over, and takes `anchor: "start"`
instead. Upstream's own note is that a centred title "does not look nice" over a grid. A plot inside
a concatenation is still a unit, so it frames its group while the concatenation above it anchors to
the start.

Beside it, `assembleHeaderProperties`: a facet's `header` names its properties `titleFontSize` and
`labelFontSize` where a Vega title names them `fontSize`, so each is a rename per part. Without the
map a header's whole styling was read and dropped.

### Four more rules, each one a chart drawn wrongly

- **`impute` as a transform**, as against an `impute` on a position channel: it names its own field,
  key and grouping rather than taking them from the encoding, and was refused outright.
- **A non-position channel gets the invalid arm too.** `nonposition.ts` asks for one, so a size
  scaled from a column with nulls draws those rows at the scale's own output for an invalid value
  rather than leaving them unsized.
- **A reversed scale reverses its bin spacing.** `getBinSpacing` writes `(reverse ? -1 : 1) *` in
  front of the half-spacing that pulls a bin's edge inward, because on a reversed axis inward is
  the other way.
- **An offset moves every position, not only a rect's.** A label over a grouped bar has to move
  into the same lane the bar did — and *only* into the lane: the position's own band drops to 0
  where the offset centres it, since centring twice moves the label half a group to the right.

And both curve fits now always name their output columns, defaulting to the two they were computed
from, as `RegressionTransformNode` does in its constructor.

### `"-x"` is a channel, not an unknown word

`isSortByChannel`: a discrete domain's `sort` may name **another channel** — `"-x"` for "tallest
bar first", which is how most sorted bar charts in the gallery are written. Read as an unrecognised
string it silently became the default alphabetical order, which is a chart sorted the wrong way
rather than one that failed. Expanded, it resolves through the same path a `sort` object takes: the
categories are ordered by the *aggregate of the other channel's field*, computed from the
pre-aggregation table, which is also what finally made this compiler name that table where upstream
names it.

### An aggregate that answers with a whole row

`{"aggregate": {"argmax": "US Gross"}, "field": "Production Budget"}` asks for the production budget
*of* the highest-grossing row, and the two columns in it play different parts everywhere. The
aggregate is taken over `US Gross` and its output column is named after that; the `field` is a path
*into* the row it answers with, appended unescaped because it is a real step into a real object; and
the default title reads `Production Budget for max US Gross`. Read as an ordinary aggregate the
whole thing collapsed into one name that no transform wrote.

Two smaller ones fell out of it. Every field definition contributes to the aggregate, not only the
channels' own — a `tooltip` written as a **list** holds several, and one of them may be the only
thing asking for one. And a parse cannot climb past a step that produces what it reads *by root*: a
step producing `argmax_US_Gross` blocks a parse of `argmax_US_Gross['Production Budget']`, though
the two names differ. Where the parse stays below, it is a formula rather than a `format.parse`,
since by then the loader has long since finished — `node.parent instanceof SourceNode` is upstream's
test and it is asked at assembly, after the optimiser has moved what it can.

### How many buckets a `bin: true` asks for depends on the channel

`autoMaxBins`: ten along an axis, where a reader can follow a fine grid; **six** on a colour, a size
or a facet, where more than a handful of steps stop being tellable apart; four on a stroke dash,
there being five patterns and four reading better. The number goes into the field's own *name*, so
reading ten everywhere renamed every column a binned colour scale produced and nothing downstream
found them.

Three more from the same pass, each a property that was being overridden rather than read:

- **A style block settles a text mark's alignment.** `getMarkPropOrConfig` reads the mark, then the
  style blocks it names, then the mark type's configuration; this compiler read only the mark, so a
  label styled `{align: "left"}` had `align: "center"` written beside it by the very default the
  style existed to replace. The **runtime** had the same gap from the other end — it read `align`,
  `baseline`, `dx` and `dy` only from the encoding, so a style block's never reached the page.
- **A stated `padding` settles a band's two ends at once.** The derived inner and outer paddings
  are for a scale that said nothing; writing them beside a stated one gave Vega three numbers where
  the specification gave it one.
- **`{"step": 50, "for": "position"}`** hands the step to the outer band rather than to one mark
  inside it, so `x_step` is 50 outright and the offset divides whatever band that produced.

And `"header": null` takes a facet's **caption** off rather than its band: the band is also where a
shared axis is drawn, and that axis is still wanted. A band with neither is the one that disappears.

### A ramp over instants is still a ramp

`isContinuousToContinuous` includes the two **time** scales, and this compiler's own list did not:
a colour ramp over dates was read as discrete and drawn as a row of square swatches over a
continuum. Its legend also takes the configured date format outright, `omitTimeFormatConfig` being
true for an axis and **false** for a legend — an axis chooses its granularity from the span it is
showing, where a legend's entries stand alone. And a `mean` over a date column still needs the
column read as dates first, aggregate or no aggregate, or it averages the characters.

Three more from the same pass. A rect's position takes the **invalid arm** like any other, which
`midPointRefWithPositionInvalidTest` builds whatever shape the position takes. A nested
concatenation may carry a title of its own, and being a composition it anchors to the start. And a
size signal is written once per *name*: a size two levels of a tree agree on was named by each of
them, and Vega reads the first and warns about the rest — nine signals where upstream has three.

The runtime gained one to match: `encode.gradient` is where a ramp's own opacity is written, so a
legend beside a chart of translucent marks is drawn as translucent as they are rather than solid.

### A column that arrived already bucketed

`binnedyearmonth` and its kin say the *data* was bucketed before it got here, and almost every rule
that reads a time unit has to know it. There is no `timeunit` transform, only a formula computing
where the bucket ends — `timeOffset('month', datum['date'], 1)`. The field keeps its own name,
`vgField` skipping the prefix it would otherwise add. The title is the field's own, there being no
derivation to announce. And the stack groups by the bucket's start alone, its far edge being a
column a formula wrote rather than a second name for the same one.

Beside it, a `groupby` is written whenever the specification **stated** one, empty or not: an empty
list and silence are two different statements and it is the statement that is carried across. A
boxplot over one ungrouped column states an empty one.

### A column of plots is one width and a stack of heights

Nine files turned on one sentence of `parseConcatLayoutSize`: a nested concatenation contributes
upward only the dimension it shares **as a whole**. A column of plots has one width and its
`childHeight` is one cell's, not the column's — so a *row* of columns must not merge on it, and this
compiler was merging on it and handing the whole chart a height of one cell. The plain name is what
says which of the two a level settled: `width`/`height` for the dimension the concatenation shares,
`childWidth`/`childHeight` for the one each cell keeps.

### January is month one to a reader and month zero to Vega

`normalizeMonth` and `normalizeQuarter` shift a **number**, and only a number: a month written as
`1` is the reader's January and `datetime()`'s month zero, where a month named `"jan"` has already
been resolved to the index Vega wants and shifting it again reads January as December. Passing the
number through unshifted put every dated comparison a month late.

Beside it, a **binned** time unit in a predicate needs no bucketing — the column already holds the
bucket — so only the cast to a number is left, and the rebuilt-from-parts expression this compiler
was writing compared the bucket against a bucket of the bucket.

### A condition falls through to the mark's own colour

`color(model, {filled})` hands the mark's own colour to `nonPosition` as its `defaultValue`, so a
production rule *ends* in it. This compiler set the colour and then overwrote the whole property
with the rule, leaving no unconditional arm — and every mark the condition did not catch was drawn
in Vega's own default rather than the chart's.

Beside it, `getHeaderChannel`: a facet's `header.orient` of `"bottom"` or `"right"` moves the
captions to the **footer** band, which is a different group with a different name rather than the
same one moved. The heading follows them, and the layout anchors it at the end of the grid.

### Inside a trellis, a series belongs to its cell

Two of these, and both drew one cell's rows into another. A stacked path mark's **imputation** is
done within `facetby.concat(stackby)`, so a gap filled across the cells is a gap filled from the
wrong rows. And a path mark split into series reads the *cell's* own partition rather than the whole
table: `markData`, not `mainData` — reading the table drew every cell's series in every cell.

With them, `timeUnitBandSize`: the same fraction a mark writes as `{"width": {"band": 0.7}}`, written
as a bare number in the configuration, which is how a theme narrows every bucket's bar at once. And
an offset may name a **datum** rather than a field, which is how a repeated layer puts each copy in
a lane of its own — there is no column to read, only the value to look up.

### An `order` channel is what makes a connected scatter plot connected

`getSort`: an `order` channel names the drawing order outright, and the mark is then sorted by that
column rather than by its position — a line running through the years rather than left to right,
which is the whole of what a connected scatter plot is. A **stacked** mark is the exception, where
`order` says how the segments stack; and a path with neither is drawn along its own dimension, or
nothing would keep it from doubling back.

Beside it, two rules about what groups a stack. `getGroupbyFields` pairs a dimension with its far
edge only where the dimension is **binned** — a bucketed instant's `_end` is a column the time unit
wrote and the stack has no use for it — and a binned dimension under an *imputation* groups by the
bin's midpoint instead, two fields not being imputable at once.

### A domain written as dates

A `DateTime` in a scale's domain cannot be handed to Vega as an object: each end becomes the
expression that builds the instant, wrapped in `{data: …}` so Vega reads it as one *datum* of the
domain rather than as a list to be spread. The runtime learned to unwrap it at the other end.

### A table that is generated rather than loaded

`{"sequence": {...}}` is the one data source that is a *transform*, and it matters twice over. The
dataset holds a `sequence` transform instead of `values`; and the flow does **not** fork below it,
a generator being nothing Vega might overwrite, so the view's own transforms belong in the same
dataset rather than in a derived one. Its column is derived too, so nothing parses it — this
compiler was writing a `toNumber` over a number a transform had just produced.

Beside it, a step signal is named after the **scale** rather than the channel: inside a
concatenation each plot counts its own categories, so a row of band charts reads `concat_1_x_step`
rather than every plot taking the first one's width.

### A binned field forced onto a discrete scale is a domain of labels

`binRequiresRange`: where a specification puts a `bin` on an *ordinal* channel, the axis has no
numbers left to derive its labels from, so the bin writes them out — `"5.0 – 6.0"` into a `_range`
column. Four things then read that column rather than the bin's start: the scale's domain, the
mark's position, the aggregate's grouping, and — because labels do not sort themselves into numeric
order, `"1.0 – 2.0"` coming before `"9.0 – 10.0"` alphabetically — the domain's sort, which orders
them by the bin's own start.

That sort also settled a question about the pre-aggregation table. It is wanted where the
*specification* stated a sort that reads a column the aggregation removes; a sort this compiler
*derived* is built from columns the grouping keeps, and reads the same table everything else does.

An **arc** takes a band on its polar positions for the same reason a bar takes one on its Cartesian
ones: a slice spans an angle, it does not sit at one — and spanning it needs the other half of
`positionAndSize`, the branch for a channel with no size of its own. Vega has no `thetaWidth`, so
the far angle is the near one plus the extent, written as an `offset` on the same reference. And a ranged position whose far end is a
**datum** contributes that constant to the domain — an area drawn down to zero has to cover zero
whether or not any row holds it.

### The `utc` in a time unit's name sits wherever it likes

`normalizeTimeUnit` reads it out of the whole name, so `binnedutcyearmonth` is universal time as
much as `utcmonth` is — and reading only the prefix left a universally bucketed column on a *local*
scale, stepped its far edge with `timeOffset` rather than `utcOffset`, and spoke it with
`timeFormat`. `utcOffset` is now an expression function too; it had never been asked for.

### `href`, and the pointer that goes with it

The `href` channel is no longer refused: it is a link the mark carries, written the way a text
channel is. And a mark that links somewhere shows the **pointer** — `baseEncodeEntry` sets the
cursor from the encoding, not from a style, since nothing else about a linked point looks clickable.

A binned field on a discrete scale is also *spoken* as the plain column it came from: there is no
numeric axis left, so upstream reads it as a category rather than as the range its label spells out.

### A caption turned to face its row

`defaultHeaderGuideAlign` and `defaultHeaderGuideBaseline` both open with *if the angle is stated*:
a caption left at whatever angle the renderer chooses is left at whatever anchor it chooses too.
State one and the caption has to be turned to face its cell — a **row**'s captions run down the side
of the grid, so each is right-aligned against the cells and centred on them.

The runtime met it halfway. A title's `angle`, `align` and `baseline` were parsed and dropped, so a
left-oriented caption read upwards however flatly the specification asked for it; and a title
hanging from its top sits a line above the row it labels, where `baseline: "middle"` puts it beside
it.

With them, two rules that are one line each: a stated `spacing` is the gap between a grid's cells
and beats the configured twenty, and a colour scale with a **midpoint** is a *diverging* one, since
the reader is being shown which side of a value each datum falls and a one-ended ramp cannot say
that.

### A table handed in by name

`{"data": {"name": "falcon"}}` reads a table the specification supplies in its top-level `datasets`
block, and both halves of that were missing. A named root **keeps its name** — `if (!root.hasName())`
guards the numbering upstream, so it does not consume a `source_n` either — and the rows the block
holds are written out beside it, since the view's own `data` states none. Renaming it breaks the
hand-off the name exists for.

### A whisker is not a category

A boxplot's whiskers are drawn in **black** whatever the box is coloured: they mark the extent of
the data rather than naming a category, and taking the category's colour made a coloured boxplot's
whiskers disappear into its box. The box's thickness also reads a `size` **encoding** written as a
value, which is how a specification thins a box without touching the rest of the chart.

### A column may be called `source.reco`

`escapePathAccess` escapes a bracket, a dot and both quotes *inside* a path step as well as between
steps, because a name with a dot in it is a name and not a path: writing `source.reco` unescaped
tells Vega to look one level into a `source` that is not there. This compiler was escaping only the
joins.

Three more of the same size. `background` on the specification beats `config.background`, a theme's
default being what a stated one overrides. A top-level **`view`** block paints the *plotting area*
rather than the surface around it, so it becomes an `encode` on the chart's own group — the two are
different colours in the same chart. And `isDiscrete` counts a **binned** field, so a binned heatmap
draws its axes over the cells as a categorical one does.

An `impute` with a method other than `value` is three transforms, not one: Vega's `impute` fills a
gap with a **constant** and nothing else, so the gap is filled with null, a `window` averages over
the frame — which belongs to the window, not to the impute — and a formula writes the result back
over the nulls.

### Three mark properties that are words rather than values

`cornerRadiusEnd` rounds the two corners at the *far* end of a bar, and Vega has four corner
properties and no notion of which end a bar grows from — so it resolves into two of them, and which
two depends on the orientation. `xOffset` and `yOffset` on a **mark** are plain nudges, unlike the
same names in an encoding, and fold into the position's `offset`. All three had been passed straight
through as though Vega knew them.

Beside them, a relative band size on a *band* scale, which is two numbers rather than one: the mark
fills `band * bandwidth(scale)` and starts `(1 - band) / 2` in, so the gaps either side of it match.

### A layer that is not there renames every layer under it

`"outliers": false` takes a boxplot's scatter of far-out rows off, and with it a whole layer — so
the whiskers *are* the first layer and everything below loses a level of naming. The names are what
every scale domain and mark reference is written against, so a layer that vanishes silently is a
chart whose parts point at each other's names.

An arc that states its own `outerRadius` is not measured from the plotting area either: a donut
naming its own reach means it, and it still needs an inner radius of zero, Vega drawing nothing at
all where neither is given.

### An angle nobody can read yet

`defaultLabelAlign` has a second half this compiler had never reached: where the label angle comes
from a **signal**, the comparison cannot be made here, so it is written out as an expression and
handed to Vega. The two answers then have to live on the labels' own `encode`, an axis property
taking a constant rather than a rule — which is why upstream's axis carries neither `labelAlign` nor
`labelBaseline` in that case and an `encode.labels.update` instead.

The runtime met it halfway again: it read the axis *property* and never the labels' encode, so a
label whose alignment was computed sat wherever the orientation would have put it.

Beside them, `replaceExprRef`: `{"expr": …}` on a guide is Vega-Lite's way of writing a signal and
Vega's way is `{"signal": …}`. Passed through, the property was unread and the guide stayed at its
default. And a mark's own `xOffset` applies wherever the position lands — including *inside* a
bucketed bar's spacing, where the two nudges compose rather than one replacing the other.

### A boxplot of one column has no category to span

Five files turned on one word: a whisker runs along the **measured** axis, and saying so is what
centres it on the other one. A boxplot with no discrete channel has nothing to span, so its parts
sit in the middle of the plot — `{signal: "height", mult: 0.5}` — where saying nothing made them
fill it from edge to edge.

### An offset divides its band the way its mark would

An offset scale is not always a band. It divides a band between the marks nested in it, and *how*
it divides follows the mark: a bar takes a band of its own inside the group, a point sits at a place
in it. That one rule settles four things — the offset scale's type, its `paddingOuter` (a point
scale's ends are padded and its marks have no width to pad within), the step expression that counts
its entries (`bandspace` counts *bands*, and a point scale has only places), and whether the
position's offset is centred in a lane at all.

A **position** with an offset nested in it is a band whatever the mark, for the same reason: a point
scale has no span for the nested marks to divide.

### An array is an object, in JavaScript

`defaultLabelOverlap` reads `!isObject(sort)`, and in JavaScript an **array** is an object — so a
written-out order suppresses a time unit's label thinning as much as a sort object does. The reason
is the same one the rule is for: a gap in a *stated* sequence is a question rather than an inference,
and a reader who wrote out Monday to Sunday is owed all seven.

Beside it, a day folded into a date. A weekday is one-based as a date, and where the day is a known
number that sum is done at compile time: upstream writes `datetime(2012, 0, 2, …)` for Monday, not
`datetime(2012, 0, 1+1, …)` — two expressions that mean the same thing and compare as different
text.

### A bucket a rect is drawn *over* rather than after

Seventeen files, and one rule: `offsetedRectFormulas`. A `bandPosition` other than the middle moves
a bucketed rect within its bucket, and the two ends it is then drawn between are not the bucket's
own — they are interpolated between the *previous* bucket's start and this one's, and between this
one's start and its end, into two columns a formula writes. The scale reaches those columns and the
mark is drawn between them.

The chart that shows why is a line with a red band over each missing point: at `bandPosition: 0` the
band has to sit *over* the gap, and reading the bucket's own edges put it after the gap instead —
half a day late, every time.

Those two columns are columns like any other, so the **grouping** has to carry them: an aggregate
that throws them away throws away the very numbers the mark is placed by.

### `rangeMin` is one end of a range, not a property beside it

A radial chart says "start the rings at twenty" without writing out the expression for the other
end, and `rangeMin`/`rangeMax` are how: they **replace the ends** of whatever range the channel
would have taken and are not emitted themselves. Passed through as properties they were ignored and
the rings were drawn from the centre.

A wrong guess is worth recording beside it. The first reading was that a radial scale's range begins
at the *mark's* `innerRadius`, which fitted this chart exactly — both numbers are twenty — and is
not the rule. The `rangeMin` beside it is what said so.

Beside them, the stack's sort names each field **once**: two channels over one column is one thing
to sort by, and repeating it in the pair of parallel lists is a comparator that reads the same
column twice.

### The chart's colour names the box, not the whiskers

A whisker marks the *extent* of the data rather than naming a category, so it is drawn black
whatever colour the box is — and that means the encoding's colour is withheld from the parts that
state their own, not merely written under them. Colouring the whiskers made them disappear into the
box they belong to.

### A name the specification wrote, and a name this compiler derived

`test_aggregate_nested` and a fixture built to mirror it disagreed about the same column, and both
were right. A column an **encoding** aggregate reads is a *reference* this compiler derived, so a
dot in it is escaped: `properties\.yield`, or Vega looks one level into `properties`. A column named
in a `transform` the specification wrote is left exactly as the writer wrote it. The rule is not
about the character, it is about who wrote the string.

Beside it, three that are one line each. `{"expr": …}` on a **mark** property and on a **scale**
property is a signal, as it already was on a guide — and a signal is a *reference*, so it replaces
the whole entry rather than sitting inside a `value`. An angle is a turn from zero, so a label the
specification wrote at minus forty-five degrees is a label at three hundred and fifteen; the two
draw alike and compare as different numbers, and the normalisation has to happen *after* the
specification's own block is copied over, not before. And an impute's grouping is
`[...stackby, ...facetby]` **concatenated**, not merged: a field that is both a series and a facet
is named twice, where the stack's *sort* names each field once.

### A parse the specification stated, over rows no loader ever saw

`{"values": [...], "format": {"parse": {...}}}` is not an instruction to the loader — Vega has
already ingested those rows — so the stated parse joins the flow's own and becomes a *formula*
there, and only what is left of the format block belongs on the source. An explicit parse also wins
over one this compiler inferred, which is what `Split(explicit, implicit)` says: a `utc:'%d %b %Y'`
the reader wrote is not to be replaced by the `toDate` a temporal encoding would have asked for.

That change turned a silent near-miss into a named gap. Reading the stated parse means emitting
`utcParse`, and `utcParse` is one of the functions this engine refuses by name for want of a
`strptime`. The chart compiled correctly and had been *rendering* by accident, on a `toDate` that
was never asked for.

Beside it, the axis title order: `axis.title` first, then the field's own, then the pair a ranged
position names — and the first of those that answers is the whole answer. A layer whose guide names
the axis has said what the axis measures, so the fields' own titles add nothing after it.

### Five rules read off the same ranking, and the two runtime defects they exposed

A layer's group **style** is the union of its children's, not one verdict for the lot. A scatter
plot with a caption pinned to its corner is `["cell", "view"]`: the points want a bordered plotting
area and the caption, which has no position at all, does not.

A shared scale merges **property by property**, not layer by layer — `parseNonUnitScaleProperty`.
The first layer to settle a property settles it, and a layer that says nothing about it is passed
over rather than ending the search. A candlestick's rules come first and have no width to speak of,
so the bar's `padding` is the only one anybody states, and reading only the first layer lost it.

A field named twice in a path mark's grouping is **listed twice**: one column driving both the
colour and the dash pattern appears once per channel, because `pathGroupingFields` pushes without
looking. Deduplicating it split a stocks chart's lines differently from upstream's.

A conditionally written channel **waives `ignoreVgConfig`**. A test with nothing to fall through to
leaves the mark unpainted when the test fails, so the configured value is written out as the rule's
last arm even though the style block already says it — a style block is what Vega applies when a
property is *absent*, and a production rule that reaches its end is not absent.

A stacked value asked for a **band position** is drawn between the segment's two ends rather than at
the far one, which is what puts a label in the middle of its own wedge instead of on the cut between
it and the next; a text mark on a polar channel asks for the middle without saying so. The same
interpolation runs for a *bucketed* column read through any scale, position or not — a size legend's
swatches come from the scale, and a mark placed at a bucket's near edge would be a size the legend
never shows. And a theme may write a Vega-Lite-only axis property in `config.axisX` as much as in
`config.axis`: `labelExpr` and the conditional ones are resolved onto the axis here, because Vega
has no name for them and would apply nothing. A guide's `test` is a *predicate* in the same grammar
a `filter` is, and it compiles through the same function — passed through as an object it went
unread, so a gridline told to dash everywhere but January dashed nowhere at all.

The fixtures written for those found two defects in **this runtime**, both now fixed:

- **A continuous scale ignored its `padding`.** A band scale pads by leaving gaps between bands, but
  a continuous scale has none to leave gaps between, so Vega asks for the effect the other way round
  — `padDomain` zooms the domain out by exactly the factor that pulls the data's ends inwards by
  `padding` pixels, after `zero` and before `nice`. Without it the leftmost bar of a time-series bar
  chart was sliced in half by the axis. The zoom happens in the scale's own space, so a log, power
  or symlog domain is padded through its own transform rather than linearly.
- **A binned scale's symbol legend drew one swatch per boundary.** A scale whose `bins` name cut
  points has *buckets*, not values: `binValues` drops the last boundary and `formatRange` labels
  each entry against the next. Ticking the scale instead drew six swatches for four buckets, each
  labelled with an edge no row can take.

### The optimizer runs until nothing moves, and which sibling survives decides the names

Fifteen gallery examples were fixed by three changes to the data flow, and all three are about the
*shape* of the tree rather than what any node does. The shape decides the naming — `data_0` versus
`data_1` — and a mark reading the wrong name is a wrong chart, not a cosmetic difference.

**Every node upstream has a `hash`, and it is over the transform it emits.** This compiler compared
only three kinds and left the derived steps out, so a line drawn over its own area was two views
asking one table for the same stack and getting a dataset each. But the hash is over the whole
*component*, not only what reaches the transform: two stacks that segment the column differently, or
whose dimension is quantitative in one plot and ordinal in another, emit the same transform from
different questions and stay apart. Merging on the transform alone drew a pie chart's labels from
the arcs' own accumulation, and one plot of `test_invalid_null` from another's.

**Time units are folded by their own optimizer, and it keeps the _last_ sibling** —
`timeUnitChildren.pop()` — where the general one keeps the first. Which of them survives is which
branch the walk meets first, so a chart whose two layers both bucket one instant has its *second*
layer numbered `data_0`. That is upstream's numbering, and every scale domain and mark reference in
the file follows from it. A binned time unit is a `TimeUnitNode` upstream even though it emits
`formula`, so it is folded the same way.

**`optimizeDataflow` runs its whole sequence again until nothing moves, at most five times.** That
is not belt and braces: one optimizer's fold makes the next one's siblings. Two layers each
bucketing an instant and then aggregating it are not sibling aggregates until the time units have
been folded together, so a single pass leaves the aggregates apart and the datasets doubled.

Beside them, `hasBandEnd`: a bucketed **instant** is a bucket like any other, so a stated
`bandPosition` puts the mark at a point inside it — a signal interpolating the two edges, since the
edges are columns and the point between them is not — and the bucket's far edge joins the scale's
domain. A label over a month asked for the middle of the month now sits in the middle of it.

### Five more from the same list, and a facet channel that was left out of the data flow

**A facet channel is lifted out of the encoding, but not out of the data flow.** It says nothing
about what a cell *looks like*, so it is taken out before the scales are built — and the column it
breaks the chart down by may still need bucketing. Upstream does that on the facet's own model,
above the cell's, so a trellis broken down by `year(date)` buckets the year before it buckets the
quarter. Left behind, there was no `year_date` column to break down by at all.

**A cell's caption goes through the same rule a mark's text does** — `formatSignalRef` with
`expr: "parent"`. A bucketed instant is spoken as a date with the specifier Vega picks at render
time, so a trellis of years is captioned `2005` rather than `1104537600000`.

**`bin` and `stack` written as _transforms_** now compile. They are the same nodes the encoding's
own produce — `BinNode.makeFromTransform` and `StackNode.makeFromTransform` — with everything
stated rather than derived, and only the output naming has a rule of its own: one name becomes the
pair of edges.

**A composite mark's grouping is every channel but the continuous axis**, colour included, and a
field named on two channels is named twice: `extractTransformsFromEncoding` pushes without looking,
so a box plot coloured by the column it is categorised by groups by it twice. A **tooltip** is the
exception — it is taken out of the encoding before the grouping is read and only the part of it that
asks for an aggregate is put back, because resting on a mark to read a column is not a request to
break the summary down by it. The `joinaggregate` keeps the repetition and the `aggregate` does not,
which is not an inconsistency: an aggregate's dimensions are a *set* in `makeFromTransform`, and a
`joinaggregate` is a transform Vega takes as written.

**A channel written only as a test still needs its scale.** `getFieldOrDatumDef` reads through a
`condition`, so a median tick that is its category's colour unless its box has no height gets a
colour scale; reading the unconditional part alone left the rule naming a field it could not look
up, and the box plot's median was drawn unpainted.

### A stack drawn inside a group, so that the rounding belongs to the stack

`getGroupsForStackedBarWithCornerRadius`: a corner radius on a stacked bar is a property of the
*stack*, not of whichever segment happens to be at its end — rounding each segment would round the
joins between them as well. So the whole stack is faceted into a group, the group is given the
radius and clipped, and the segments are drawn inside it with none. The group's extent along the
stack is the min and max of every segment's two ends **in pixels**: the accumulation is in data
space and the rounding is not, so the scale is applied inside the expression. The inner group exists
only to undo the outer one's translation, marks inside a group being positioned relative to it while
their scaled positions are absolute.

Two details decide whether the group appears at all. The test is `some(prop =>
getMarkPropOrConfig(prop, ...))` — **truthy**, so a bar that states a radius of zero is a plain bar
and stays one rect. And a bar with a `size` encoding is left alone: its segments would no longer
fill the group's thickness, and upstream does not guess.

Beside it, three smaller rules. A tooltip **written as a list** builds the `{title: expression}`
object however many entries it holds — one field written `[{…}]` is titled where the same field
written bare is not. A composite mark's tooltip keeps each channel's own title, so a bucketed column
reads `Year (year)` rather than the `year_Year` its transform wrote. And a **time unit** on a
composite mark's channel becomes a transform of its own: the summary happens after the bucketing —
one interval per bucket, not one per instant — so the unit cannot stay on a channel whose column
the aggregate has already collapsed. A channel that is not itself temporal is then told to read
that column as a *time*, nothing about an ordinal band otherwise saying so.

### A child's transforms come after its parent's, not instead of them

The plainest correctness bug of the sweep so far, and it had been sitting under a naming difference.
A layer or a concatenated plot that writes its own `transform` was having it *replace* the chart's
rather than follow it — so a population pyramid whose shared transforms compute a `gender` column
and whose plots each filter on it filtered on a column nothing had written yet. Upstream keeps the
parent's transforms in the parent model's own data chain and hangs each child's below; concatenating
the two lists is the same shape, and the optimizers then hoist the shared prefix back above the fork
by themselves. One example went from thirty differences to one.

Beside it, four smaller rules:

- **The size alias belongs to an axis, not to a scale.** `assembleAxisSignals` walks the assembled
  axes and asks each one for the extent it will draw its grid across; a plot whose axis is switched
  off has nothing to draw and needs no alias. Keying it off the *scales* gave a middle column of
  labels a `width` signal upstream does not write.
- **A plot's own `view` block paints its own plotting area**, exactly as the chart's does — which is
  how a column drawn without a border says so on itself rather than on the chart.
- **`config.concat.spacing`** settles the gap for every concatenation in a chart, and **an empty
  description is no description** — `isEmpty` drops it rather than announcing a chart whose spoken
  summary is the empty string.
- **A discretizing size scale does not start at zero**: its range is a list of sizes to choose
  between rather than a span to measure along. And a **stated** inner padding is the resolved one,
  so the outer padding is half of *that* rather than half of the configured default.
- **A mark configuration's paint reaches its swatch** — `applyMarkConfig({}, model,
  FILL_STROKE_CONFIG)` — so a bar outlined two units thick has a swatch outlined two units thick.
  The two colours are dropped again where this legend is the one explaining them.

The fixture written for the first of those found a runtime defect and named a runtime gap. The
defect: a **discretizing scale's legend labelled each swatch with one edge**, saying nothing about
which side of it the bucket lay on. `thresholdValues` prepends negative infinity and calls the far
end positive infinity, and `formatRange` reads each entry against the next — `< 12.2`, `12.2 –
16.4`, `≥ 20.6`. That is fixed. The gap: a **plot whose `view` block sets `stroke: null` loses its
group from the scene**. The compiler emits exactly what upstream emits — the specification
comparison covers it — and the scene comes out one group short, so the fixture is written without
that block and the case is recorded here rather than passed off as working.

### A bucket imputed at its middle

An imputation fills the gaps in one column keyed on another, and a bucket is *two* columns — so
`StackNode.assemble` computes the point between them into a column of its own and keys on that.
Without it the imputation keyed on the bucket's near edge, which is a different set of keys from the
one the stack groups by, and a stacked area over a binned column had gaps its own grouping could not
see. The mark then reads that same column rather than working the midpoint out again: it is already
there, because the imputation had to have it.

The fixture written for it named another runtime gap, and a later attempt on that gap narrowed what
it actually is. A stacked area over a bucketed column *with nulls in it* comes out with the wrong
number of vertices: upstream's outline has ten buckets in it and this runtime's has eleven. The
obvious reading — that the path is drawn through points the mark's `defined` says to omit — is
**wrong**: dropping the broken points gives nine, not ten. One bucket more than upstream's is
reaching the mark, so the difference is in the *rows* the imputation produces rather than in how the
path is traced. That is where the next attempt should start; the change that assumed otherwise was
reverted rather than shipped. The fixture is written over a column with no nulls meanwhile.

### Three rules about what a channel *is*

**A secondary channel is not a bucket of its own.** It names the far end of somebody else's, and
upstream reaches it as a `SecondaryFieldDef` — a definition with no type, which never takes the
bucketed branch. Reading it as a bucket put a rect's far edge halfway into a bucket that does not
exist, between `month_date_end` and a `month_date_end_end` nothing had written.

**A caption turned a quarter turn is centred**, not pushed to one side. `defaultLabelAlign` through
the header's own orientation: at ninety degrees the caption's length runs *across* its band rather
than along it, so there is no side left to push it to. Aligning every turned caption to one edge
put a trellis's row labels half off their rows.

**`{"domain": {"unionWith": […]}}` widens the domain rather than replacing it** — the stated values
first, the derived domain after, both in the one union — and the stated values stay a single entry
of that union rather than becoming one each. Vega takes a literal array as a domain in its own
right, so splitting it hands the scale two domains of a single value.

### An invalid value the theme has an answer for

`getScaleInvalidDataMode` asks **per channel**, not once for the mark. A theme that writes
`config.scale.invalid.color` has said what an unplaceable value looks like on that channel — so
whatever the mark's `invalid` mode says, those rows stay and the channel's production rule paints
them. Filtering them as well removed the very rows the answer was written for, and leaving the arm
off the encoding painted them as if they had been valid. Both halves are the same rule, read in two
places.

Beside it, `convertDomainIfItIsDateTime` in full. On a scale that measures **time**, every end of a
stated domain becomes the expression that builds the instant, wrapped in `{data: …}` so Vega reads
it as a datum rather than as a signal to be scaled — and it is the *channel* that decides, not
whether the value happens to look like a date, so a domain of two epoch milliseconds is two
instants. `domainMin` and `domainMax` are ends of a domain like any other and go the same way.

The fixture written for that found a runtime defect: **a time scale ignored `domainMin` and
`domainMax` outright**, where every other continuous scale applies them. A chart asking for one year
of a decade was drawn with the whole decade in it. `configureDomain` runs the same block whatever
the scale's type, and so does this now.

### A bucket's interpolated edges belong to the bucket, and one composite collapses where another does not

`TimeUnitNode.assemble` emits, per unit, the bucketing **and** the two interpolated edges a rect
sitting off the middle of that bucket is drawn between — from the same loop, in that order. Writing
all the bucketings first and all the edges after put a heatmap bucketed on both axes in an order
upstream never writes, and the names each pair refers to then belong to the wrong bucket.

A composite mark's grouping is every channel that is *not* aggregated, tooltips included: a tooltip
naming the column being summarised breaks the summary down by it, and one asking for a mean of that
column does not, because an aggregate is a measure rather than a grouping. That is one rule where
this compiler had two half-rules, and it settles both `errorband_tooltip` and
`boxplot_tooltip_aggregate`'s grouping.

And an **error bar** of one part collapses back into the view — `layer.length > 1 ? {layer} :
{...layer[0]}` — where an **error band** of one part does not. That asymmetry is upstream's own, in
its own source, and not a simplification either way: a band with its borders off is still `layer_0`.

### A datum written as a date is an instant

`initFieldDef` reads `isDateTime` and types such a datum **temporal**. Everything follows from
that: the scale it lands on measures time rather than categories, the plot takes a continuous
width, and the value itself becomes the expression that builds the instant — in the mark, where
Vega has no `{year: 2006}` to scale, and in the domain, wrapped so Vega reads it as a datum of the
domain. A rule drawn at a year had been a category in a band of its own, thirty-eight differences
deep.

A facet cell is styled by the same rule the chart's own group is — `cell` where it has a Cartesian
position to border, `view` where it has none — so a trellis of pies has no plotting area in any of
its cells.

A facet resolves its `theta` scale **independently** — `defaultScaleResolve` is `channel ===
'theta' ? 'independent' : 'shared'` for a facet — and the resulting `child_theta` is built *inside*
the cell group rather than beside the chart. That placement is the whole point of resolving it per
cell: the scale's extent is measured over the rows the facet handed *that* cell, and inside the
group those rows are the partition Vega named `facet`. Shared instead, a trellis of pies scaled
every cell against the whole table, so a cell holding a tenth of the data drew a tenth of a pie.

### Taking the main line's work: forty-seven commits, and what the merge itself found

`milestone-0-bootstrap` was merged in at `0166edf` — expression functions, `strptime`, projections,
guide `encode` folding, rounded rects, colour interpolation in five spaces, `symbolLimit`, title
anchoring, layout bands. Forty-eight conflicts across fifteen files. Where both sides had built the
same thing, the fuller port won: their `titleStyleLayers` over a single-name `style`, their
`TitleBuilder` whole, their `strokeWidthScale`/`strokeDashScale` renaming of the two legend channels
whose names collide with the legend border's own. Where the two were different things in one place,
both were kept: the scale padding *and* their `domainRaw` guard on `nice`; their text `limit`,
`ellipsis` and `lineBreak` *and* the style-block fallback for `align`; their `symbolFillColor`
fallback *and* the swatch's own `encode` override.

Three of their rules had to be narrowed, and three of mine:

- **A scheme carries its own interpolator**, and `interpolate` does not reach it. Their new HCL
  interpolation was being applied to `"range": "heatmap"` and every shade came out a unit or two
  off. `interpolate` applies to a range written out as a list of colours, which is what their own
  fixture states.
- **A title with no `style` still takes `group-title`.** Their `titleStyleLayers` returned nothing
  for one, so a Vega-Lite theme's heading colour — which its compiler redirects into
  `config.style.group-title` — reached nothing and every themed title drew black.
- **A line of one point closes its subpath, but one left over from a _break_ does not.** What closes
  is a line d3 was handed a single point for; a series broken into fragments was handed all of them.

- My **trellis band layout** kept its four bands and its margins, and took their `headerBand`,
  `footerBand`, `titleBand` and `titleAnchor` on top — and in the grafting, a bug of my own: the
  cell boxes are already in the enclosing coordinates, so adding the cell's translation again put
  every band label a whole cell further along.
- My **`closed` flag in the oracle** was reading false by construction for any line that named no
  `interpolate`, because `curves(undefined)` is null and the replay never happened. It now defaults
  to `linear`, which is what Vega passes, so the flag says what it means.
- My **`{"field": {"group": "y"}}`** never resolved: it fell through to a signal lookup and returned
  nothing. Nobody had noticed because nothing had moved a group *and* asked a child to move back —
  until a stacked bar with rounded corners did exactly that, and every segment was drawn its own
  group's height too far down. The enclosing group's origin is now what the reference reads.

### Two runtime gaps this batch names but does not close

`density`'s fixture is not in the corpus, and `trail`'s draws no legend. Both compile exactly as
upstream compiles them — the specification comparison covers them — and both are drawn differently
by *this runtime*:

- **`kde` samples a different grid.** The transform exists and produces a curve; its extent and
  step count do not match upstream's, so the x domain comes out narrower and the axis carries 12
  ticks where upstream has 20.
- **A legend on the `strokeWidth` channel is drawn at the symbol's size**, not the scale's stroke
  width, and a `stroke` symbol is sized as though it were an area.
- **A time scale cannot take a colour scheme.** A sequential ramp over instants compiles exactly as
  upstream compiles it and then fails to build: the scale wants a numeric range and is handed a
  scheme.
- **A missing field is `null` here and `undefined` in JavaScript**, and `undefined === null` is
  false. A condition testing a column the aggregate removed came out true for every row, so a chart
  whose grey was meant for the exceptions was grey throughout. The distinction needs a value the
  model does not have.
- **A universally bucketed column's far edge lands elsewhere.** `binnedutcyearmonth` compiles
  exactly as upstream compiles it and the bars come out a different width, so the two engines
  disagree about what `utcOffset('month', …)` steps from. Covered by the sweep, not by a fixture.
- **A group's `encode` replaces its style's paint rather than overriding it property by
  property.** A plotting area given a `fill` loses the `#ddd` border its `cell` style was drawing.
- **`timeParse`/`utcParse` are refused for want of a `strptime`.** A specification that states its
  own date format now compiles to the `utcParse` upstream compiles it to, and this engine cannot
  run it — where before it ran a `toDate` nobody asked for.
- **`domainMid` does not split a colour scale.** A diverging scale compiles exactly as upstream
  compiles it and the runtime maps it as an ordinary ramp, so the two halves come out swapped.
- **A facet's footer band reserves no height**, so a chart captioned below its cells comes out
  short by the caption. `facet-footer` is the one fixture in `GRID_LAYOUT_PENDING`.
- **A quantile legend labels its buckets with a representative value** rather than the range they
  cover — `75.75` where upstream reads `75.8 – 95.0` — which is the same gap as the banded legend
  drawn as swatches.

### One difference is still open

Every mark matches exactly and the surface around them is still between half a unit and a unit small
in each direction, uniformly, on every fixture. Because nothing drawn has moved, the shortfall has to
be in a guide *extent* — the one input to the surface that the mark comparison cannot see, since text
bounds are excluded by design (docs/adr/0006). `VegaLiteFixtureDifferentialTest` asserts the shape of
the discrepancy rather than tolerating it silently: never larger than upstream, never more than a unit
smaller, so a regression past that still fails.

There is a second, stranger one, recorded here so the next person does not spend the afternoon on it.
With `labelOverlap: true` **and** `labelFlush: true` on the same axis, upstream hides every other
label at a spacing where the final label bounds do not overlap at all — the same axis with either
property alone keeps them. It is an artifact of Vega's incremental dataflow rather than a rule; this
compiler is a pure function and has no earlier pass to inherit it from. With `labelFlush` implemented
the two engines now agree on these fixtures anyway, but the mechanism is not the same one.

### And one the numbers could not have found

**Every SVG export was missing the border around its plotting area.** The exporter painted a group's
own fill and stroke only when the group *clipped*, and a Vega-Lite plotting area is a group that
states a size and a `#ddd` border and does not clip — so the border was in the scene graph, drawn on
the device by the canvas renderer, and counted by the differential comparison, and absent from every
file the SVG writer produced. The scene graph had already worked out which rectangle a group paints
(`GroupNode.paintRect`, which the canvas renderer uses); the exporter was not asking it.

It is the eighth entry in the same list as the harness gaps below and the seventh found by putting
the two pictures side by side: it can only be seen by looking, because both engines' *scenes* agreed
the whole time.

## Verification

- 2,372 JVM tests pass and none is skipped (`./gradlew test`); the portable core is most of
  them, and `./scripts/test-core.sh` runs it without an Android SDK.
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

None. One hundred and thirty-eight fixtures exist and all of them pass — and that sentence became worth
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

One kind of edge was missing and is worth recording, because the phases had been hiding it. A
transform may **publish** a signal: `{"type": "extent", "field": "v", "signal": "vals"}` writes `vals`,
and `{"type": "bin", "signal": "bins"}` writes the bin settings it chose. Nothing declares those names,
so a signal reading one looked like a signal reading nothing and was resolved first, against a name
with no value. Upstream has no such gap — `parseTransform` does `scope.addSignal(spec.signal,
scope.proxy(t))`, so the published name *is* an operator standing in for the transform — so reading one
now waits for the dataset whose pipeline writes it.

The old phases were insulated from this by accident: every signal was resolved again after all the
data, so a wrong early value was overwritten. Resolving each signal exactly once at its ordered
position removes that safety net, which is why the edge is required rather than an improvement.
`dot-plot-wilkinson` was the only fixture covering the pattern and covered it by luck — its `ddh`
reaches the data through a second path, via `size` and a scale — so `published-signals` isolates it:
it fails without the edge and passes with it.

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

## `autosize: fit` is implemented

`fit` shrinks the plotting area so the whole drawing comes out the declared size, where `pad` grows
the surface instead. It had been falling back to `pad` with a diagnostic, because the size cannot be
known until the drawing has been measured and there was no second pass.

Upstream does have one, and it is not iterative: `viewSizeLayout` measures once, sets the `width` and
`height` signals to what is left, and re-runs the dataflow with the layout step short-circuited, so
the second pass never re-measures. This compiler is a pure function of the specification, so it does
the same thing by compiling twice. The first pass exists only to be measured and its diagnostics are
discarded — the second pass reports the same ones against the size actually drawn.

Three things about it were worth taking from the source rather than guessing:

- The overhang is **rounded outward** before it is subtracted: a label reaching 30.5 units to the
  left costs the plotting area 31.
- The origin the content is translated by comes from the **first** pass, not the second.
- The surface is *not* the declared size. It is measured exactly as `pad` measures it, from the
  frame's own bounds — and because the shrink reserved a whole unit for a fractional overhang, the
  drawing comes back a fraction smaller than the room made for it. Upstream's references carry the
  fraction: 159.660254 where the declared height was 150 and the padding 10.

That last one cost a wrong turn worth recording. `viewSizeLayout` rounds its overhang outward, so the
`pad` path was changed to round as well — and that broke the surface size of thirteen fixtures. The
rounding is real, but it sizes the *canvas*; the surface the harness compares is the frame's bounds
plus the padding, which is fractional whenever a label ends on a fraction. Both quantities exist
upstream and only one of them is being compared.

The cost is a second compile for a `fit` chart and nothing at all for the others, against a budget
where the heaviest fixture takes 366 microseconds (below).

## Scale `bins`, and `histogram-null-values` with it

A scale's `bins` names the boundaries its ticks land on, and setting it does one more thing that is
easy to miss: upstream's `includeZero` is `!scale.bins && (linear || pow || sqrt)`, so a scale with
bins loses the `zero` a linear scale otherwise includes. That single property accounted for three of
the four scale differences the fixture had left — the domain was `[0, 10]` against upstream's
`[1, 10]`, and the tick count followed from it.

The boundaries themselves come from `configureBins`: an array is taken as it stands, and a
`{start, stop, step}` description is expanded, with `start` pulled up to the first whole step inside
the domain and `stop` pulled down to the last, so a binning computed over a wider extent than the
axis shows does not hang ticks off either end. The `bin` transform publishes exactly that
description, which is how a histogram points its axis at whatever the binning worked out.

Bin boundaries are never thinned. Upstream raises the tick count to the number of bins before
`validTicks` runs, so the halving a long `values` list gets never applies to them.

With that, `histogram-null-values` passes, and it took four separate pieces to get there: the
dependency order, `bin` publishing its settings, `autosize: fit`, and this. Each of them turned up a
defect in code that was already passing.

## What the survey says now, and why it went down

`ExampleTriage` reports **75 of the 93 clean**. Read the movement rather than the number. It went
70 → 79 as the stochastic, cross-filter and layout work landed, and then **79 → 75 when mark-level
`transform` was implemented** — which is the survey becoming honest rather than the engine getting
worse. Five examples carry a `transform` on a *mark*; that used to be one warning and a block
dropped whole, and the pipeline now runs it and reports `force`, `label` and `wordcloud` by name.
One of the five improved outright: `force-directed-layout`'s `linkpath` is implemented and now
actually runs, and its node count went up accordingly.

The 18 remaining split cleanly: 11 geo/topojson, 2 raster wanting `kde2d` and `heatmap`, 4 wanting a
mark-level layout transform, and nothing else.

## The raster family, first third: `isocontour` and `geopath`

`volcano-contours` is the smallest of the three raster examples and needed three things, none of
which is the raster image the other two want.

- **`isocontour`** — marching squares over a grid, ported line for line from
  `vega-geo/src/util/contours.js` rather than reimplemented. Two parts are not what a fresh
  implementation would arrive at. Rings are **stitched** from isolines through a fragment table
  keyed on `x * 2 + y * (dx + 1) * 4`, so a contour closing on itself is recognised as the same
  fragment arriving from both ends. And a ring is classified by its **signed** area — positive is an
  exterior ring, negative a hole — after which each hole is assigned to the first polygon containing
  it. Getting either wrong gives a picture that looks like contours and is not these contours.
- **`geopath` with no projection**, which is what upstream falls back to: d3 then passes the
  coordinates straight through, with no spherical clipping, no adaptive resampling and no
  antimeridian cutting. Exactly right here, where the "geometry" is a contour on a raster grid whose
  coordinates are already in chart units. A specification naming a `projection` is still refused and
  says why.
- **Mark-level `transform`**, which had been reported and dropped. Upstream calls these
  post-encoding transforms and runs them over the scene *items*; this engine runs them over the
  rows, because nothing between the encoding and the drawing reads anything a mark transform
  touches, and because a scene node here holds a parsed path rather than a mutable property bag.

One thing the port needed that the source does not say out loud: `smoothLinear` reads
`values[yt * dx + xt]` **before** the guards that decide whether to use it, so a point on the grid's
far edge indexes past the end. JavaScript gives `undefined` there and the guards then skip the
interpolation; Kotlin throws. The absence is spelled out.

That the fixture matches exactly is worth more than most: contour geometry is about as sensitive as
this corpus gets, and 21 paths of it agree to the last decimal.

## A scale over no data is not a scale over `[0, 1]`

`bar-line-toggle` switches between a column chart and a line chart by *emptying* one of two
datasets: `{"type": "filter", "expr": "DataPoints<=50"}` on one and the negation on the other. With
25 points the line dataset has no rows, so the two scales built from it have no domain.

This engine warned and fell back to `[0, 1]`. Upstream does not: the extent of nothing is
`[undefined, undefined]`, its own arithmetic turns that into `[NaN, NaN]`, and a scale with a `NaN`
domain generates **no ticks**. So upstream draws one axis and this engine drew both, with 47 ticks
where upstream had 25 — a whole second chart's worth of furniture over the one that was asked for.
`domainMin` and `domainMax` still replace their end, which is how such a scale keeps `[NaN, 100]`
rather than nothing at all.

Two smaller things came out of the same chart, and both were in the *harness*.

- **A numeric channel written as a string.** The specification says `"labelFontSize": "12"`, and
  upstream carries the string onto the scene item and hands it to a renderer that emits
  `font-size="12px"`. This engine parses it once and stores 12. Comparing `"12"` against `12` as
  different *types* reported a difference that did not exist — and hid any real one on that channel,
  since the string matched nothing on the other side either. `normalize.js` reads a numeric-looking
  string as the number it is, on the channels it already treats as numbers and nowhere else.
- **The decimals a label keeps when the tick step says nothing.** Six, and it is d3's rather than a
  choice: a degenerate span makes `precisionFixed` return `NaN`, so the `,f` specifier keeps no
  precision at all and d3's default for `f` applies. The axis over `[NaN, 100]` reads `100.000000`,
  and only the accessibility caption could see that this engine was saying `100`.

## A transform's expression parameter is an edge after all

This was written down here as a known gap and as the "obvious next increment", with the reasoning
that `filter`'s `expr`, `formula`'s `expr` and `cross`'s `filter` are *per-row* expressions rather
than `{"signal": ...}` references and therefore not dependencies. The first half is true and the
conclusion was wrong.

Upstream's `parseExpression` walks **every** expression's AST and lets the `scale`, `data` and
`indata` visitors register what they find as operator **parameters**. A `formula` calling
`scale('x', datum.v)` is wired to that scale exactly as a signal-valued parameter would be. Vega's
serpentine timeline is written that way — a `formula` that scales a column — and without the edge
the scale was asked for before it had been built, which this engine reported as a scale the
specification "does not define".

The three parameters are now read for their dependencies. Two things came out of it that are worth
knowing:

- **A diagnostic disappeared, and that is the fix.** `SignalCompileTest` had a case asserting that a
  transform reading a computed signal from a *later* dataset came out null and was reported by name.
  It resolves correctly now. The diagnostic existed because the edge was missing; what survives it
  is the report for a signal nothing can supply at all.
- **The risk named here — that a new edge turns a working chart into a reported cycle — did not
  materialise.** All 103 existing fixtures were unaffected.

Two smaller things came with the same chart. A scale's `reverse` may be a **signal**, which is how a
timeline offers to run right-to-left, and it was being read as a constant. And a **text mark can be
stroked** — a halo under a label on a busy background — which this engine dropped and, worse, which
the comparison could not see: `textMark` emitted a fill and never a stroke. That is the *seventh*
channel to have been invisible to the harness. Before trusting a green fixture on a new kind of
property, check that both `normalize.js` and `Differential.kt` emit it.

## Six gaps behind one chart

`pi-monte-carlo` looked like a layout problem — two `group/scope` marks short — and was six
independent gaps, each of which had been quietly reported for a long time.

- **`hypot`** was missing from the expression functions. It is variadic, not the two-argument
  function its name suggests, and with no arguments it is zero.
- **Upstream's own `config.style` blocks were missing.** `cell` is `{fill: 'transparent', stroke:
  '#ddd'}`, and it is what makes a `style: "cell"` group *painted* — a group that paints nothing is
  not a mark at all, which is exactly why the chart came out two marks short. `point`, `circle` and
  `square` came with it, and they carry a **size of 30** where a bare symbol's is 100.
- **`layout.align` and `layout.bounds`.** `align: "none"` lets each cell keep its own overhang
  instead of pooling the widest per column, and `bounds: "flush"` measures a cell by its *declared*
  extent rather than by how far its contents reach. `GridLayout.place` is now a port of upstream's
  `gridLayout` rather than an implementation of the one alignment that had been needed, because the
  three differ only in how the per-cell lead-in is pooled and that is easy to get subtly wrong.
- **`gridScale`.** A gridline spans a *second* scale's range instead of the plotting area, and it
  takes the two ends in **that scale's own order** — so a descending range gives a gridline whose
  start is the far end, which the plain form never does. Ours ran the other way.
- **`labelFlush`.** A label within the threshold of an end of the range is aligned to that end
  rather than centred, so the first and last sit inside the plot. `true` means one pixel, and
  **zero is not off**: upstream's test is `flush === 0 || !!flush`.
- **`{"field": {"group": "width"}}` was reading the `width` *signal*.** Upstream compiles it to
  `item.mark.group.width` — the size the enclosing group item was encoded with. The two agree at the
  top level, which is why reading the signal survived so long, and they do not inside a cell that
  declares a width of its own: a rule meant to span the cell spanned the chart instead.

And one forced default: the arc encoder fell back to the built-in blue when `style` gave it no
fill, which defeated the pairing rule sitting right above it — a mark that encodes *either* paint
channel gets neither default. Vega's Monte Carlo quadrant is a bare outline, and it was coming out
as a filled quarter-disc.

## A label that is nowhere, three times over

`error-bars` sets `config.axisBand: {bandPosition: 1, tickExtra: true, tickOffset: 0}`, and getting
that right needed four things — one of which is the same fact stated three ways.

- **`tickOffset`** is not zero by default. Upstream's `config.axisBand` carries a `-0.5` that
  corrects the half-pixel an axis group's own translation adds, and it applies to a **band** scale
  only — a point or ordinal axis never sees that block. This engine had the `-0.5` hard-coded inside
  the band tick placement, so a specification aiming its ticks at the band boundaries could not
  switch it off.
- **A band axis's labels sit at the band's centre whatever its ticks do.** Upstream's label mark
  hard-codes `band: 0.5` and takes only the *offset* from the shared band settings, so
  `bandPosition: 1` moves the ticks to the edges and leaves the labels alone. A `Tick` now carries
  its own label position.
- **`tickExtra`** appends one more tick at the *start* of the first tick's band. Upstream's datum
  carries `{extra: {value: <first tick's value>}}` and no `value` of its own, and the scaled-value
  codegen reads the first as "that value's position, with no bandwidth added".
- And then the same fact three times. That datum has no `value`, so its **label** scales something
  absent. Upstream's scene records `y: NaN`; its `anchorPoint` reads `item.y || 0` and **`NaN` is
  falsy**, so the *bounds* are the box an empty string occupies at the origin; and its SVG contains
  no element for it at all. All three have to be reproduced together, and each one on its own is
  wrong in a different direction: keeping the `NaN` out of the bounds loses five units of chart
  height, letting it into a `min` or a `max` poisons every measurement above it, and drawing the
  element puts an empty `<text>` in the output upstream does not have.

The comparison harness now accepts a `NaN` on either side where the reference records one. It is
not a relaxation: a reference holding a real number still demands that number, and this only says
that where *upstream* has no usable value, this engine may hold the painted zero — which is what an
extent does — or the same absence, which is what a position that was never computed does.

## `random()` and `now()` are implemented, and the refusal is lifted

Both were refused for reproducibility (PROJECT_BRIEF.md 18.2): a chart that draws a different
picture every run cannot be compared with anything, including itself. That reasoning was right and
the conclusion was not. Both are ordinary non-determinism with an injection point at each end, and
pinning both ends makes them *more* testable than most of what is already here.

- `RandomStream` is upstream's `randomLCG`, arithmetic included. The multiplier overflows 2^53 —
  `1103515245 * seed` reaches 2.4e18 — so JavaScript loses low bits and the sequence that follows is
  a property of that loss. It is computed in doubles for exactly that reason; doing it correctly in
  a `Long` gives a different and arguably better generator that is not upstream's.
- The stream is **one per compile**, shared by every scope, because upstream's is a module-level
  binding shared by a whole view. A chart's picture therefore depends on the *order* its expressions
  run in as much as on the generator, and `sampleNormal` keeps Box–Muller's second value between
  calls, so three normals cost two pairs of uniforms rather than three. Both are reproduced.
- `oracle-js/src/determinism.js` puts the same generator into upstream with
  `vega.setRandom(vega.randomLCG(42))` and stops `Date.now` at 2026-01-01T00:00:00Z. The seed and
  the instant are duplicated in `RandomStream.DEFAULT_SEED` and `Clock.PINNED`; they have to agree,
  and the comparison is only meaningful because they do.
- `SpecCompiler` takes a `randomSeed` and a `Clock`, both defaulting to the pinned values. A host
  that wants a genuinely stochastic chart, or a live clock, passes its own; nothing else reads
  either. The default keeps the property 18.2 was protecting — a compile is a pure function of its
  specification — while making the chart *possible*.

`clock` and `watch` are fixtures and pass exactly. That is the whole point of the exercise: two
charts that were "impossible to verify" now have references that hold to the last decimal.

Six of the eight examples in this category need more than the clock and the generator, and scouting
them said what:

- **`hypothetical-outcome-plots`** — done. Not a draw-*order* problem at all: `DataResolver` built a
  scope per row without passing the chart's stream, so each row got a fresh generator and every one
  of the twelve bars was the *same* first draw. The tell was in the numbers — twelve identical
  values where upstream had twelve different ones — and it says something worth keeping: a shared
  stream has to be threaded through every scope, and the place it will be forgotten is the one that
  builds a scope per datum.
- **`error-bars`** — done, and a fixture. See "A label that is nowhere, three times over" below.
- **`pi-monte-carlo`** — done, and it was six unrelated gaps rather than one. See "Six gaps behind
  one chart" below.
- **`bar-line-toggle`** — done, and it needed nothing to do with `on` handlers. See "A scale over
  no data is not a scale over [0, 1]" below.
- **`serpentine-timeline`** — done. Not a layout problem: a `formula` calling `scale('sS1', ...)`
  was running before that scale existed, because a transform's *expression* parameter was not an
  edge in the dependency graph. It is now — see below — and `reverse` can come from a signal.
- **`word-cloud`** — upstream's own headless output is degenerate (`fontSize: 0`, a width of
  `-Infinity`), because the `wordcloud` transform measures text against a canvas that is not there.
  There is nothing to compare against; this one needs a different kind of evidence.

## `crossfilter-flights`, and a mark count that meant the opposite of what it said

This was recorded as unfixturable: "it draws 600,098 scene nodes and the differential run dies with
`Java heap space`", with a note that raising the test heap was a decision for whoever took it. Both
halves were wrong in the same way. **Upstream draws 171 marks.** The 600,098 was *this* engine
drawing every one of the 200,000 unfiltered rows three times over, because `crossfilter` and
`resolvefilter` were missing and the histograms' datasets came through unaggregated. A node count
from the triage is a measure of how wrong we are, not of how big the chart is.

The heap did have to move, but for an unrelated reason and by a much smaller amount: 200,000 rows
through three `bin` transforms and a `crossfilter` is a large live set when every transform copies
its rows instead of mutating them. `maxHeapSize = "2g"` is pinned in `build.gradle.kts` — the JVM
default is a quarter of physical memory, so without pinning it the gate means something different
on every machine.

The two transforms are simple once the incremental machinery is set aside. `crossfilter` records,
per row, one bit per dimension whose range query the row falls outside; `resolvefilter` keeps the
rows whose verdict is zero once the `ignore` mask is cleared, which is how a delay histogram shows
everything the *time* and *distance* brushes admit including the bars its own brush excludes. Two
details are upstream's and not obvious: the range is half-open, `[lo, hi)`, because upstream bisects
a sorted index with `bisectLeft` at both ends; and a set bit means *rejected*, which is why the test
is for zero. Upstream's sorted indices, previous/current bitmaps and changed-dimension mask all
exist to avoid rescanning 200,000 rows when a brush moves a pixel, and none of it is observable in
a single compile.

The verdicts ride on the rows rather than in a side table. Upstream keys its bitmap by an `_index`
it stamps on every tuple; these transforms are pure functions over copied rows, so there is no
stable index to key by, and a column on the row survives being sourced into another dataset exactly
as the row does.

One silent drop came out of it. A `text` or `symbol` mark that names **no** `x` at all was dropped
entirely, where upstream reads `item.x || 0` and draws it at the origin — which is how each
histogram's heading, written as `{"y": -5}` and nothing else, is placed hard against the left of its
group. Every other mark encoder in the file already defaulted the same way; these two returned null,
and without a diagnostic.

## `calendar-view`, and a function that had been returning its argument

`calendar-view` draws 21 years of the S&P 500 as a wall calendar: 6,311 rects, two axes per year,
and a legend across the top. It needed six things, and the first two were both *silences* rather
than gaps.

- **`timeOffset('day', d)` moved nothing.** The step defaults to one, and the default was read by
  coercing the missing argument — `Number(undefined)` is 0, so the function handed back the date it
  was given. d3's rule is `step == null ? 1 : Math.floor(step)`, which distinguishes absent from
  zero, and the two-argument form is the one specifications actually write. It had been "supported"
  since the transform work. What it cost here was invisible for most of the year: every week was
  labelled by its Sunday instead of its Monday, so only the weeks that straddle a month boundary
  came out under the wrong month name.
- **A mark `sort` could only read `x` and `y`.** `{"field": "datum.year", "order": "descending"}` is
  a path into the scene *item*, not a channel — upstream has no special case, `vega-util`'s `field`
  walks it and the item happens to carry both its geometry and its datum. Anything it could not
  read was silently a tie, which leaves the items in declaration order and looks exactly like a
  sort that worked; the calendar came out oldest-year-first. Unreadable paths are reported now, and
  the descending test is upstream's exact `=== 'descending'` rather than a prefix match.
- **An axis label's `encode` may set its `opacity`**, which is how the calendar names only the first
  week of each month and blanks the other forty-eight. It is a rule over the tick's own value, so no
  axis property could express it. A label hidden this way still *measures*, unlike one dropped by
  overlap removal.
- **`formatSpan`.** A format specifier naming no precision does not mean "no decimals": upstream
  resolves it against the span being labelled, so `"%"` over a `[-0.06, 0.06]` ramp reads `−6%` and
  not `−6.000000%`. `Ticks.spanSpecifier` is that rule, and it applies to axis labels, legend
  labels and the spoken caption alike — the axis had the same gap and only explicit-precision
  specifiers had ever been tried.
- **A legend title beside its entries** (`titleOrient: "left"`), which also changes the title's own
  anchoring: upstream reads a left or right title as `middle`-anchored where a top one is
  `start`-anchored, so it is centred against the **bar alone** rather than against the labels under
  it. With it came the legend's own `titleLimit`, which defaults to 180 and nothing had to ask for:
  the title here is truncated, and since its width is what pushes the entries across, an
  untruncated one moved the whole legend by 29 units.
- **A legend measures as its own box.** Upstream's `legendBounds` anchors the aggregate at the
  legend's padding and then *sets* the item's bounds to the resulting rectangle, so anything
  hanging above or left of the origin is drawn and not measured. A vertically centred title
  reaches three quarters of a unit above the legend's top edge, and measuring it there made the
  chart a unit taller.

Two stale diagnostics went with it. `titleX`, `titleY`, `titleAngle`, `titleAlign` and
`titleBaseline` on an axis have been honoured since the parallel-coordinates work and were still
being reported as unimplemented — the other half of "nothing silently ignored", and the third time
it has happened. And the caption harness compared the XML *spelling* of an `aria-label`, so a legend
title containing an ampersand could never match; it unescapes now.

## `time-units`, and the crumb that cost a whole unit

`time-units` is Vega's time-unit bar chart, and it needed five things. The first was the one the
handoff predicted; the rest were behind it.

- **A scale domain's `field` may be a signal.** `{"data": "flights", "field": {"signal": "measure"}}`
  is how a chart offers a measure picker: one scale over whichever column the control chose.
  `DomainSpec.FromField` held a `String`, so the object never parsed into a name, the domain came out
  empty and every bar was drawn at zero height. It is a [FieldRef] now, resolved the way
  `MarkEncoder.scaleName` resolves one — the signal supplies the **name**, so it is one lookup and
  not the two the same object makes under a mark's `field`. It is also an edge in `DataflowOrder`:
  the scale cannot be built before the signal that names its column.
- **Axis `formatType`.** Upstream's `tickFormat` checks it *before* the scale type, so `time` wins
  over every scale including the discrete ones whose labels are otherwise their own values. That is
  the only thing that makes a band scale of instants read as `Sun, Mon, Tue` rather than as epoch
  milliseconds, since there is no temporal scale anywhere to infer it from. An axis `format` written
  as `{"signal": ...}` was parsed and dropped too, which is the same failure mode `zindex` had.
- **`timeUnitSpecifier(units, specifiers)`**, and `%q` and `%U` with it. The specifier is chosen from
  the longest recognised *run* of units, which is what collapses `["year", "month", "date"]` into one
  `%Y-%m-%d` instead of three fields, and the units are put in calendar order first so
  `["date", "year", "month"]` gives the same answer.
- **`title.fontStyle` and `subtitleFontStyle`.** Italic is measured as well as drawn.
- **One floating-point crumb, worth a whole unit of plotting area.** `cos(-90°)` is 6.1e-17 rather
  than zero, so a rotated corner carries a crumb wherever it is multiplied. Upstream rotates the
  corner's **absolute** position about the anchor, where the crumb is absorbed by a coordinate two
  orders of magnitude larger and the sum lands exactly on the integer; this engine rotated the offset
  from the anchor and translated afterwards, which keeps the crumb. The y-axis title reached
  -38.00000000000001 instead of -38, `viewSizeLayout` takes `Math.ceil` of that, and the chart came
  out 599 units wide over a plotting area a unit too narrow. `TextNode.bounds` now copies upstream's
  arithmetic rather than merely matching it in exact terms. This is the second time a last bit has
  decided a chart — the first was the `1e-14` inside `bin`'s floor.

One harness change came with it, and it is a strengthening rather than a loosening. A scale domain's
`Date` was recorded as `String(date)`, which pins a reference to Node's own wording of the machine's
zone — `"Sun Jan 01 2012 00:00:00 GMT+0100 (Central European Standard Time)"` — and hides the number
underneath. It is the instant now, which is exactly as strict, is what a temporal domain *is*, and
made five existing references shorter. Note that `Differential.compareScales` still has no branch for
a `TimeScale`, so those domains are recorded and not yet compared; that branch is now writable and
was not before.

## A mark whose channel resolves to nothing

`interactive-legend` draws a brush `rect` whose `x` is `brush[0]`, and there is no brush until
someone drags one. This engine reported "Rect mark has no x, x2, width or xc channel" and dropped the
item; upstream keeps it, leaves the property off, and paints nothing. The mark has to be in the scene
to be dragged at all, so dropping it is not a smaller version of the chart — it is a chart that
cannot be used.

The distinction is between a channel that is *absent* and one that is *declared and unresolved*. The
first is still a malformed specification and still reported; the second now yields a zero extent,
which paints nothing for the same reason upstream's does.

**A harness change came with it, and it is the kind worth reading rather than trusting.** Upstream's
scene item carries `width: NaN` — `x2 - x` with both undefined — and `canonicalNumber` writes that
into the reference as the *string* `"NaN"`, deliberately, so that a NaN stays visible instead of
being rounded away. This engine's scene node is the painted form, so it holds the zero its renderer
would draw. Upstream's own SVG proves the two agree: the item with `width: NaN` renders as
`M0,0h0v200h0Z`.

`Differential` now says so explicitly — a reference geometry channel of `NaN` or an infinity is
checked against **zero** on this side. Checked, not skipped: a real number there is still a
difference. Before this the channel could not be compared at all, since a string never matches a
number, so this is a channel gained rather than a check dropped.

The second half of that fixture was the legend swatch's `opacity`, which is a conditional rule —
dim the swatch when its series is deselected — and no `symbolOpacity` property can express one. It is
resolved against the entry now, and the "only implemented as a constant" warning it used to draw is
gone with it.

## The labelled donut, and four defects behind it

`donut-chart-labelled` was the last official example not passing and not refused. It needed `pluck`
and multi-source datasets, which HANDOFF knew about, and four things it did not — each of them wrong
in code that every other fixture was happy with.

**An empty sort field sorted.** The chart offers sorting as an option and leaves it off:
`{"field": {"signal": "sortField"}}` with `sortField` an empty string. Upstream reads that as a
property no row has, so every comparison ties and the declared order survives. This engine read an
empty path as *the datum itself*, compared two whole objects, and reordered the data — so every slice
was the wrong size, and nothing said so. Fixing it corrected all nine arcs at once.

**A `path` mark with no outline was dropped.** The leader lines are drawn from the same 33 label
slots as the labels, and only the nine belonging to a slice carry a path. Upstream makes an item
regardless; this engine made nine. The same rule as the brush rect before it, one mark type over.

**A stroke widened the bounds of an invisible mark.** Upstream's `boundStroke` tests the *item's*
opacity as well as the stroke's — `item.stroke && item.opacity !== 0 && item.strokeOpacity !== 0` —
and the chart lays its bins out with debug rectangles left at `opacity: 0`. Counting their two-unit
stroke made the whole surface a unit taller than upstream's.

**A Vega null was drawn as the word "null".** This one the mark comparison could not see, and the
rendered SVG showed it immediately: a text mark is compared by its anchor, not its content, so 33
labels matched upstream perfectly while 24 of them printed "null" across the chart. Upstream's
`textValue` is `line == null ? '' : (line + '').trim()`; this engine was stringifying the null.
Fixed at the source — a channel holding a Vega null now resolves to nothing rather than to four
letters, which also means a `fill` of null is no longer a colour nobody can parse.

That last one is the fourth time the rendered SVG has caught something the comparison could not, and
the first where the fixture was already green when the picture was wrong.

## Every encode channel, and the six curve families behind the last of them

`ENCODE_UNSUPPORTED` is now empty. It held ten entries — the four per-corner radii, `limit` and
`ellipsis`, `tension`, polar `radius`/`theta`, `blend` and `clip` — and each is drawn rather than
reported. Five things are worth keeping from doing them.

**`tension` was not a small channel.** It read as one property to thread through, and threading it
through meant discovering that six of Vega's seventeen interpolation methods were missing:
`basis-open`, `bundle`, `cardinal-open`, `catmull-rom` and its open and closed variants. `tension`
means a different quantity to each family it applies to — a cardinal stiffness, a Catmull-Rom
distance exponent, a bundle blend — and each has its own neutral value (0, 0.5, 0.85). Reading an
unspecified `tension` as 0 for all three would have turned an unspecified Catmull-Rom into a cardinal
spline and an unspecified bundle into a straight line. `CurveKind.defaultTension` is that table.

**A Catmull-Rom spline at alpha 0 is not a Catmull-Rom spline.** d3 does not degenerate it; it hands
the series to the *cardinal* curve instead. The difference is real, because the correction that
distinguishes the two is scaled by a span raised to the power alpha — and a zero span raised to the
power zero is one, not zero, so the correction stays on and the end conditions come out different.
`CurvesTest` caught this as the one assertion that says the two families agree at alpha 0.

**The open families draw nothing for a short series.** Two points or fewer and `basis-open`,
`cardinal-open` and `catmull-rom-open` emit no path at all — not a straight line, which is what every
other family does. Three points and they emit a single position and a `Z`. That `Z` is also
conditional: it appears on a line and must not appear on the first boundary of an area, where it
would cut the outline off before its baseline. Only the caller knows which it is drawing, which is
why `curve()` takes a `partOfArea` flag.

**Both strengthenings were on the harness, and one of them paid immediately.** `normalize.js` had a
hand-written table mapping an `interpolate` name to a d3 curve; it now imports `vega-scenegraph`'s own
`curves()` by file path, which brings the name table, the monotone orientation choice and the
`tension` semantics from upstream instead of from a copy that could be wrong the same way the port
is. The moment it went in, `edge-bundling`'s reference changed by two thousand lines: its `bundle`
interpolation had been falling through to the raw point list on both sides, so a chart drawing every
edge as a straight line would have passed. It now compares the outline, and matches. The second
strengthening is corner radii: `GEOMETRY_CHANNELS` compared a rect by `x`, `y`, `width` and `height`
only, so four independently rounded corners were invisible. They are compared both ways — inventing a
radius the reference does not have is as much a difference as missing one.

**A rounded rectangle is emitted as a path, in both renderers.** SVG `rx`/`ry` cannot hold four
different radii, and even for one it draws a true elliptical arc where Vega draws a Bézier
approximation of one, with a control-point offset of `1 - 0.448084975506` — Mortensen's circle
approximation, not the familiar `4/3 · (√2 - 1)`. Android's `drawRoundRect` has the same two problems.
Both now go through `RectPath`, whose output is pinned to upstream's own path strings in
`RectPathTest`, including the clamp: the limit is `min(width, height) / 2` for all four corners as a
group, and it is `min` of the *signed* extents, so a rectangle drawn with a negative width comes out
square rather than rounded.

Two smaller things. `radius`/`theta` place a label around a centre, and upstream keeps both on the
item and offsets at paint time; this engine's scene holds the anchor already offset, so the harness
folds the polar offset in — the same equivalence it already applies to a text mark's `dx`/`dy`, and
in the same order Vega applies them, so a rotated label turns about the offset point. And `clip` is
both a mark property and an encode channel: `overview-plus-detail` writes `clip: {value: true}` into
`enter`, and it is what keeps the detail view's line inside its own panel. The differential cannot see
clipping at all — upstream's scenegraph still holds the items its renderer hides — so that one is
evidenced by the exported SVG rather than by the comparison.

## The eleven channels that were never on the list, and a rotated path mark's bounds

The encode group was declared finished a commit too early. `ENCODE_UNSUPPORTED` was empty, but that
table only holds channels *known* to be missing — and diffing Vega's own schema against
`ENCODE_CONSUMED` found eleven more that were falling through to the generic "not implemented and was
ignored". Two of them, `scaleX` and `scaleY`, were **already drawn**: they had been taken off the
unsupported list without ever being added to the consumed one, so every specification using them was
told it had been ignored while it was in fact honoured. The rule this suggests: the two tables are a
partition of what the encoders read, and only checking them against the schema shows a channel that
has fallen between.

The other nine are implemented here. Four were plumbing to something the scene already had —
`strokeDashOffset`, `strokeMiterLimit`, and `aspect`/`smooth` on an image, which the encoder read and
the comparison could not see. Three were text: `lineHeight`, `lineBreak` and `dir`. Two were the group
mark's `strokeOffset` and `strokeForeground`.

**Truncation was being applied to the wrong string.** Upstream truncates *per line*, and trims each
line first; this engine applied the limit to the whole run with its newlines still in it, so a
two-line label was measured as one long one and its first line cut down to nothing. `displayText`
became `displayLines`. Two smaller findings came with it: a limit of zero or less is not a truncation
from the other end, it is no truncation at all — a comment here had claimed otherwise — and a
right-to-left run keeps its **tail** with the ellipsis in front, which is `dir`, not the sign of the
limit. Upstream's own SVG for the fixture reads `…r five` where the left-to-right copy reads
`one t…`.

**`scaleX`/`scaleY` are a `path` mark's channels, not a symbol's.** The fixture was written with them
on a symbol, and passed while proving nothing: upstream's SVG for that mark is
`transform="translate(330,40)" d="M-10,-10h20v20h-20Z"`, unscaled. Only `vega-scenegraph`'s
`marks/path.js` reads them.

**One difference found and kept.** A `path` mark that is both rotated and scaled has bounds here that
do not match upstream's, and upstream's do not match what upstream draws. Its renderer rotates the
outline about the item's own `(x, y)`; its *bounds* code rotates the already-placed points about the
**origin**, because the bounds context it uses defines no `translate` or `rotate` and so falls into
`pathRender`'s other branch, where the rotation is a matrix about zero. For the fixture's square at
`(250, 40)` scaled by `(2, 0.5)` and turned 20 degrees, upstream reports a top-left of
`(200.7, 111.6)` — 70 units below the shape it drew. This engine reports the box it actually painted.
Reproducing the quirk would make chart sizes agree under `autosize: pad` and would put every hit
target for such a mark in the wrong place, which is the worse of the two, so the fixture leaves the
rotation out and this paragraph is the record. Nothing else about a rotated path mark differs: the
outline, the anchor and the drawn transform all match.

## Every map had been verified on its colours

`normalize.js` compared a mark's drawn extent for `symbol`, `arc` and `path`. Not for **`shape`** —
and a `geoshape` mark carries no `x` or `y` at all, so for the whole geographic family the only things
ever compared were the fill and the stroke. `world-map`, `county-unemployment`, `map-with-tooltip`,
`dorling-cartogram`, `geo-points`, `airport-connections` and `volcano-contours` were green on colour.
The overall surface size did constrain the geometry indirectly under `autosize: pad`, which is why the
maps were not in fact wrong, but nothing was checking them.

Adding `shape` to that set produced 15,000 differences across four fixtures, of which three were real
findings.

**The path string was rounding the model, not the output.** `PathStringSink` rounded coordinates to
d3-geo's three decimals, which is right for a `d` attribute and wrong for a scene: the string it
produces is parsed straight back into the scene graph, where it *is* the geometry — every bound and
every hit test comes off it. So every map's measured extent was systematically out by up to a
thousandth. The sink now takes `digits = null` from the `geoshape` transform and keeps full precision;
the SVG renderer rounds on the way out, which is where a digit count belongs. The default stays at
three, because `GeoProjectionTest` and `GeoProjectionTypesTest` compare 67 upstream path *strings* and
those are the strongest evidence the projections have.

**A `fit` was destroying a mercator's automatic clip.** d3 distinguishes the rectangle a projection
chooses for itself from the one a specification asks for: a mercator clips to the square its own scale
makes one full turn of the world, and that square is recomputed every time the scale or the
translation moves. A fit measures with the *user's* clip removed and puts it back afterwards; this
engine had only one clip and put the stale automatic square back over the recomputed one, after the
fit had changed the scale — which clipped the whole map away. `reclip()` now rebuilds the postclip
from the two, intersecting them the way d3 does, and a user `clipExtent` on a mercator survives a
recentre for the first time as well.

**A `shape` mark that draws nothing measures nothing, and a `path` mark measures a point.** Upstream's
two bound functions really do differ — `marks/path.js` short-circuits `item.path == null` to
`bounds.set(0, 0, 0, 0)`, while `markItemPath` runs the generator into the bounds context and leaves
the bounds cleared when it draws nothing. This engine applied the path rule to both, so the 467
counties in Vega's own map that have no outline each measured as a point at the group's origin. Under
`autosize: pad` that is 467 marks in the top-left corner.

One harness detail came out of it. An empty rectangle must not be put through the world transform
before it is reported: the sentinel a cleared `Bounds` holds is `MAX_VALUE`, and translating that
gives a number no longer recognisable as empty — which is how the same absent county reported a width
of zero on one side and an infinity on the other.

## The projection properties that size a map rather than place it

`fit`, `extent`, `size`, `clipAngle`, `parallels` and `pointRadius` were parsed and reported. All six
are honoured now, and two were not where the earlier audit expected them.

`clipAngle` and `parallels` were **plumbing**: `Projection` had taken both since the geographic family
was ported, and the resolver never passed them. So an `orthographic` globe was drawing its far side
and every conic stood on its family's default parallels — a different map of the same world, not the
same map redrawn, because the parallels rebuild the raw formula.

`fit` is the interesting one, and it is the only projection property that is **data**:
`{"signal": "data('states')"}`. It is resolved in the scope that declared it, like every other signal,
and it is why a fitted projection cannot be built until the data it fits has loaded. The arithmetic is
d3's `fitExtent`, and none of its three oddities is arbitrary: the projection is reset to scale **150**
because a fit is measured against d3's reference scale and the answer is a multiple of it; the scale
factor is the **smaller** of the two ratios, so the geometry fits inside the box rather than filling it
and spilling out; and the user's clip is removed for the measurement, because a clip in screen
coordinates would cut the geometry to a rectangle the fit has not chosen yet.

**And inline `values` could not be a document.** `"values": {a FeatureCollection}` with
`"format": {"property": "features"}` is how a specification writes a map without a data file, and the
parser read `values` as an array or not at all — so the dataset was silently empty. Upstream applies
`format` to inline values exactly as it does to a loaded file, including TopoJSON. It does now here,
which took splitting each reader into a text half and a document half.

## A legend had no background, and a legend's `strokeWidth` is not a width

Nothing drew the panel behind a legend's entries. `fillColor`, `strokeColor` and `cornerRadius` were
parsed and reported, and a chart that put its legends on a tinted card got no card.

The part worth knowing is where the outline's **width** comes from. Upstream builds the legend group's
encode from `_('fillColor')` and `_('strokeColor')` — the legend's own value over the config's — and
then from `config.strokeWidth` and `config.strokeDash`, the configuration *alone*. That is not a
tidying slip: a legend's own `strokeWidth` names a **scale**, exactly as `fill` and `size` do, and
writing `"strokeWidth": 2` on a legend makes upstream throw `Invalid field reference: 2`. The fixture
found that out by trying it. So the width and the dash are read from `config.legend` here too, and the
reason is recorded next to the field rather than left as a puzzle.

Two harness findings came with it, both in the same shape as every other one:

- **A group's dash was never compared.** `groupMark` reported a group's fill and stroke *colour* and
  not its dash pattern, so an outlined legend matched a dashed one.
- **A legend's reach ignored its own outline.** `legendBox` measured the declared extent, which is
  right — a legend that clips an entry still occupies the box it declared — but a `strokeColor` draws
  half a stroke width outside that box on every side, and upstream measures it. A chart with an
  outlined legend is a unit wider than one without, and this engine was making it the same width.

## The title's own styling, and a heading written as two lines

Ten title properties were reported rather than drawn: `color`, `lineHeight`, an explicit `align`,
`angle` and `baseline`, `limit`, and the subtitle's own `subtitleColor`, `subtitleFont`,
`subtitleFontWeight` and `subtitleLineHeight`. Two things came out of implementing them.

**`align`, `angle` and `baseline` are overrides, not alternatives.** Upstream writes the values derived
from `anchor` and `orient` into the title's `enter` block and the explicit ones into `update`, so an
explicit value wins over the derived one. Reporting them as unimplemented was doubly wrong: a chart
that turns its left-hand title to read *up* the page was told it had been ignored and then had the
derived angle applied.

**`font` had been claimed as consumed and was never read.** `TITLE_CONSUMED` listed it — a theme
setting the heading's face in `config.title` is ordinary — and `TitleBuilder.run()` hard-coded
`TitleDefaults.FONT_FAMILY`. The third stale claim of its kind after `scaleX`/`scaleY`, and the same
lesson: a name in the consumed table is a promise, and nothing was checking it.

**And a title written as an array was rejected outright.** `"text": ["two", "lines"]` is upstream's
multi-line form, for the subtitle too, and the parser accepted only a string — so a two-line heading
produced `A title needs a 'text'` and no chart at all. The lines are joined with the newline this
engine lays text out on, and the *accessibility caption* joins them back with a space, which is
upstream's `array(text).join(' ')` and the same rule the axis and legend captions already follow.
`GuideCaptionTest` caught that half.

## Six guide properties that only needed passing on

`Stroke` has carried `cap` and `dashOffset` since it was written, and both renderers have honoured
them since then. No guide passed either. So `tickCap`, `gridCap`, `domainCap`, `tickDashOffset`,
`gridDashOffset` and `domainDashOffset` were all reported as unimplemented while the machinery to
draw them sat one function call away — every tick butt-capped and every dash pattern starting at the
line's end.

The fix was to widen `GuideStroke`, which upstream reads as one family per part — the prefix is the
only thing that changes — rather than as six separate properties. It now carries `dashOffset`, `cap`,
`align`, `baseline` and `lineHeight` alongside the colour, width, dash, opacity and font it already
had, and `guideStyleKeys` generates all of them for every prefix. That closed the legend's
`labelAlign`, `labelBaseline`, `titleAlign`, `titleBaseline` and `titleLineHeight` in the same change,
and the axis's `labelLineHeight` and `titleLineHeight` with them. Consuming a name upstream does not
define — `gridAlign` on an axis, say — costs nothing, because a guide that has no such property simply
never writes one.

Two exceptions to the family rule, both upstream's:

- **`symbolDashOffset`, not `symbolStrokeDashOffset`.** The legend's symbol part is read through the
  `symbolStroke` prefix, because upstream spells the colour `symbolStrokeColor` — but the dash and its
  offset are `symbolDash` and `symbolDashOffset`. The fixture caught it: every swatch drew the right
  pattern from the wrong starting point.
- **`symbolFillColor` is a fallback, not an override.** Upstream sets the channel from it and *then*
  overwrites it from the scale for every legend that maps one, so a `fill` scale always wins and only
  a `size` or `shape` legend takes the stated colour. It had been reported as unimplemented, which
  read as a bigger gap than it was.

Also done here: `symbolOffset`, which shifts a swatch **and its label** along the row — upstream builds
the label's offset by extending the symbol's, so the gap between them stays `labelOffset` whatever the
symbol offset is; the gradient ramp's own `gradientStrokeColor`, `gradientStrokeWidth` and
`gradientOpacity`, the last of which fades the outline with the colours because upstream puts it on the
item rather than on either paint; and the axis `titleLimit`.

## Where an axis sits, and the half pixel that is not in the measurement

`tickBand`, `tickRound`, `position` and `translate` were reported and are drawn now. Each was a small
change and one of them found something.

**`tickBand` is three properties in one, and only one of its two values touches all three.**
Upstream's `tickBand()` reads `"extent"` as `bandPosition: 1`, `tickExtra: true` **and**
`tickOffset: 0`; `"center"` sets the first two back to their defaults and *leaves `tickOffset` alone*.
That matters because `config.axisBand` gives a band axis a `tickOffset` of `-0.5`, which is what
corrects the half pixel the axis group's own translation adds. Zeroing it for `"center"` as well moved
every tick and label on that axis by half a unit, and the fixture caught it as two ticks out of four
landing a whole unit wrong — the rounding turning half a unit into either nothing or one.

**And `translate` is excluded from the axis's measurement, not just added to its position.** Upstream's
`axisLayout` computes the axis bounds at `x`, calls `boundStroke` on them, and only then sets the item
to `x + delta`. So the nudge onto the pixel grid is in the drawing and not in the size of the chart.
This engine already took a `CRISP_OFFSET` back out of the guide bounds — which was right while the
nudge was always half a pixel, and wrong the moment `translate: 0` made it something else, because
then it was subtracting a half pixel that had never been added. A `translate: 0` axis made the chart
half a unit wider than upstream's. It now reads the actual translate back.

## `labelOffset`, and two properties a screen reader needs

`labelOffset` slides an axis label **along** the axis, which is the other direction from
`labelPadding`. It was reported and it is drawn now, and the shape of the change is the interesting
part: the first attempt put it in `labelOffsetAlong`, which is a band scale's own centring rule and is
only consulted for band scales — so a linear axis's labels did not move at all, and the fixture said so
immediately. It belongs at the single place every label's coordinate passes through, after the band
centring rather than inside it.

`aria` and `description` are now honoured on both an axis and a legend. Neither is visible to the
differential harness — upstream carries them as properties of the guide group and nothing about the
drawing changes — so they are pinned by `GuideAccessibilityTest` instead: `aria: false` removes the
guide from the accessibility tree, a `description` replaces the caption this engine generates from the
scale, and a description that is only whitespace is not a description.

That leaves **two** axis properties reported out of upstream's 79: `labelBound`, which needs the
overlap remover to take a bounding rectangle, and `tickMinStep`, which needs the tick generator to
take a floor on its step.

## `tickMinStep`, which is a floor implemented as a ceiling

Asking for a minimum gap between ticks sounds like it should set a step. It cannot: d3 chooses the
step, and the only lever is the tick *count*. Upstream's `tickCount` therefore walks the count
**down** until the step d3 would pick reaches the floor, and every part of that is load-bearing:

- the count is first capped at `floor((hi - lo) / minStep || 1) + 1`. The `|| 1` is JavaScript
  turning a zero into a one, so a floor wider than the whole domain leaves **two** ticks rather than
  none — which is what the fixture's `tickMinStep: 200` over a `[0, 97]` domain draws;
- then, because d3's step sizes grow monotonically as the count shrinks, the count is decremented one
  at a time until `tickStep(lo, hi, count)` reaches the minimum. A closed form would need d3's
  `e10`/`e5`/`e2` rounding inverted, which is why upstream loops and so does this;
- and the walk-down is **skipped** for log and time scales, whose steps are not linear in the count at
  all. Only the cap applies there.

The fixture asks four axes for twelve ticks over the same domain with four different floors, and gets
ten labels, five, two and a log axis's own progression — the same four sets upstream draws.

One axis property is now reported out of upstream's 79: `labelBound`, which needs the overlap remover
to take a bounding rectangle.

## `symbolLimit` is not a truncation

A legend told to show at most five entries shows **four**, and spends the fifth row on `…7 entries`.
Upstream keeps `limit - 1` and gives the last slot to a summary of what it left out — so the number in
that row counts the entries *not shown*, and the swatch beside it takes the size of the next value,
which means a size legend's summary row is drawn at the size of the first thing it stands for.

Worth having got from the source rather than from the name. A plain `take(limit)` would have looked
right in a screenshot and been wrong by one entry and one row, and the fixture's reference reads
`['alpha', 'beta', 'gamma', '…7 entries']` for a limit of four over ten values.

## A legend over instants, and one report that was never a gap

`formatType` on a legend is the only thing that can make its labels read as dates: a gradient
legend's scale is a **colour ramp**, and a colour ramp knows nothing about time. There is nothing to
infer it from, which is why the property exists and why ignoring it printed thirteen-digit
millisecond counts where upstream printed "Jan 2024".

The axis had solved this already, so the change was an extraction rather than an implementation:
`GuideFormat` now holds the two pieces both guides need — the temporal labeller and
`countWithMinStep` — and `tickMinStep` on a gradient legend came along with it for nothing.

Two things the fixture's captions taught, which the labels alone would not have:

- **A caption formats the same domain differently from the labels.** Upstream expands the
  abbreviating directives before reading one out, so a ramp labelled `%b Y` is *described* as
  "January 2024" while its labels say "Jan 2024". This engine already did that for a discrete domain
  and not for a continuous one, so the legend captions read out raw milliseconds.
- **A caption with no format at all carries its zone.** "Monday, 15 January 2024, 12:00:00 AM UTC" —
  because a caption that reads out a whole timestamp should say which clock it is on. With an explicit
  format it does not, and `GuideCaptionTest` compares all three legends in the fixture.

And `clipHeight` was never a gap. It has been implemented since `dorling-cartogram`; it was simply
missing from `LEGEND_CONSUMED`, so every legend using it was told it had been ignored. That is the
fourth stale report of its kind, after `scaleX`/`scaleY` and the title's `font`, and all four came from
the same thing: the consumed table is a **promise**, and until the schema diff nothing checked it.

## The title is a guide too

`aria`, `name` and `interactive` on a title were the last three of upstream's 31 title properties this
engine did not read. `aria: false` is the only way to keep a decorative heading — a watermark, a chart
drawn twice with one copy labelled — out of what a screen reader reads, so it matters more than its
size suggests. All three go on both the heading and its subtitle, because upstream builds them as two
marks under one guide and either can be focused.

The schema diff now finds **nothing** unaccounted for in `encodeEntry`, `axis`, `legend`, `layout`,
`projection` or `scale`, and **nothing** in `title` either. The whole inventory is closed: every property upstream
defines in every block is read.

Running the same subtraction over the *encode* vocabulary found the same thing one level down: several
channels reported as unimplemented had a property behind them the whole time, one entry away in the
translation map — a tick's `strokeCap` and `strokeDashOffset`, a label's `lineHeight`, and an axis
title's `align`, `baseline`, `angle`, `limit`, `x` and `y`, every one of which was already honoured
under its own name. The two whole-block gaps went with them: `encode.gradient` styles the ramp and
`encode.legend` the group the legend sits in, which is where its background and its placement live.

One channel is deliberately **not** folded. A ramp label's `align` and `baseline` are *not* the same
thing as `labelAlign` and `labelBaseline`: upstream derives a gradient label's alignment from where
along the bar it sits, never reads the property, and lets only an `encode` block override it — probed
both ways to be sure. Folding the channel into the property would quietly make the property work on
the one kind of legend that is supposed to ignore it, so the mapping now depends on what kind of legend
it is.

That work turned up a placement rule nothing had exercised: a legend title aligned or anchored away
from its default reaches *left* of the legend's own origin, and upstream drags the whole legend right
rather than letting it be cut off — `legendGroupLayout` measures `title.bounds.x1 - padding` and
translates both the title and the entries by the overflow.

A guide's `encode` block is a vocabulary of its own, and a channel it cannot express is now named one
at a time — `A title's 'title' encode block sets 'x', which is not
read` — rather than the block being written off whole. That is the difference between an unfinished
feature and a wrong chart: a heading whose `encode` positioned its own text is a heading this engine
would draw in the wrong place, and there is no property to warn about, only a channel.

A title's `encode` splits three ways, which is worth knowing before reading the code: `group` styles
the group the heading sits in, `title` its text, `subtitle` the second line — and a block naming none
of the three is upstream's **deprecated** form, which applies to the *text*. That last one is the form
`encode.update.dx` is written in, and it is the reason this engine had been reading `dx` out of an
encode block for some time without reading anything else.

The `group` block also produced the one scene-graph rule this needed: a title group's paint does not
widen it. `titleLayout` finishes by writing the union of the heading's and subtitle's bounds *over* the
group's, which discards the half-unit `boundStroke` had already added — so an outlined heading paints
that outline round a rectangle of no size and the drawing does not grow. Ported as an explicit
`boundsFromChildren` on the group node rather than by relaxing the general group rule, which is right
for a group mark that declares a size.

A title's `style` turned out not to be decoration. Upstream builds the heading's text mark with
`style: "group-title"` and lets a specification's `style` take **that slot**, so naming one does not
add to the 13-point bold — it removes it, and what the named block does not say falls through to the
*renderer's* defaults of 11 point and no weight rather than to the title's. Written here as two
configuration layers beneath `config.title`, so the ordinary precedence still holds: the title's own
property beats the theme, which beats the style, which beats the renderer. The subtitle is untouched —
its slot is `group-subtitle`, which a title's `style` never takes.

Closing `legend` took admitting that a line in this file was wrong. `strokeDash` and `strokeWidth` had
been written off here as "not gaps, they name scales" — which is exactly what makes them gaps. On a
legend those two are **channels**: keyed to a `strokeDash` scale a legend draws a swatch per dash
pattern, and keyed to a `strokeWidth` scale its rows are of different heights, because upstream's
row-height expression reads that scale rather than the `symbolStrokeWidth` property. The legend
background's own width and dash are a separate thing and do come from `config.legend` alone — which is
why the channel has to be read from the legend's *own* object and not the config-layered one, or a
themed border becomes a channel naming a scale called "2".

Two more things fell out of that. `gridAlign` looked like a dead letter — it only means anything to a
multi-column grid — but `config.legend` defaults it to `each`, and the entry grid's row **centring**
is conditional on being aligned at all. So the default was doing work no legend had noticed until one
had rows of unequal height. And a mark's `strokeDash` read through a **scale** was dropped silently:
the encoder resolved a constant or a field but returned nothing for a scaled one, so every line in a
chart that distinguished its series by line style came out solid.

`labelFlushOffset`, the last axis property, was a **stale** report: its explanation said it needed
`labelFlush`, which had been implemented for some time. It nudges a flushed label along the axis, and
it is signed *outwards* — a label flushed to the start moves back towards it — applied only where the
flush rule decided the alignment, since an explicit `labelAlign` means the label is not being flushed
at all.

## The last block that listed its gaps by exception

`layout` was the one place still reporting what it *could not* do rather than naming what it does. The
difference is not stylistic: a table of exceptions has no way of noticing a property nobody thought
about, which is the whole failure this project's diagnostics exist to prevent. `titleAnchor` is what it
cost — no entry in the table and no reader, so a trellis that anchored its cell titles was told nothing
at all.

Inverted to a `LAYOUT_CONSUMED` set and `reportUnhandled`, like every other block. Six layout
properties are now named: `center`, `offset`, `headerBand`, `footerBand`, `titleBand` and
`titleAnchor`.

## `domainRaw` short-circuits five things, and one of them is not where you look

`domainRaw` is what makes an interactive zoom work: a brush publishes the interval it wants and
nothing is allowed to round it outwards. Upstream reads it **first** in `configureDomain` and returns
before it looks at `zero`, `domainMin`, `domainMax` or `domainMid` — so it is not an override applied
over the rest, it is a bypass of the rest.

`nice` is the one that catches you. It is applied by the *caller*, after the domain has been resolved,
at six separate sites — one per scale family, because each rounds differently. Returning early from the
domain resolution therefore skips four of the five and leaves the fifth to widen `[17, 43]` back out to
`[16, 44]`, which is exactly what the fixture reported. There is no single place all six pass through,
so the check is at each of them.

One more reading worth having: a raw domain of fewer than two values is **not** an override. Upstream's
`rawDomain` returns `raw.length` and its caller only treats a value greater than `-1` as handled, so an
empty array short-circuits with a length of zero and an unresolvable signal — which is what a brush
publishes until a reader touches the chart — falls through to the ordinary domain.

That leaves one scale property reported out of upstream's 23: `domainImplicit`.

## Six colour spaces, and the `NaN` that holds a hue still

`hcl`, `hsl` and `cubehelix` — and the `-long` variant of each — fell back to RGB with a diagnostic.
All six are ported now, pinned to `d3-interpolate`'s own output in `ColorInterpolationTest` and proved
by a fixture that draws the same two ends through all eight spaces: eight visibly different ramps, and
eight sets of colours that match upstream's exactly.

Three details are upstream's and each is invisible from the ends of a ramp, which is the only place a
casual test would look:

- **A hue is a circle, so there are two ways round it.** `d - 360 * round(d / 360)` is d3's short-arc
  rule, and it is not a modulo: a difference of exactly 180 maps to 180 rather than to −180, so a pair
  of complementary colours turns the same way whichever was written first. The `-long` variants skip
  the correction, which is the whole difference between a ramp that stays in one colour family and one
  that visits the rest of the spectrum on the way. Viridis's own two ends go through magenta under
  `hcl` and through blue under `hcl-long`.
- **A grey has no hue — `NaN`, not zero.** An angle at the origin means nothing, and d3 reads
  `NaN - x` as "no difference" and so holds the channel constant *at the end that has a value*. A ramp
  from grey to red therefore keeps red's hue throughout and only moves its chroma; averaging the zero
  a naive port would put there drags the whole ramp through red. Pure black and white have a `NaN`
  saturation too, where even the radius is undefined.
- **Cubehelix's `-120` is not a normalisation.** Green defined the helix with its zero at blue, and
  dropping the offset turns every ramp a third of the way round the circle.

The object form `{"type": "rgb", "gamma": 2.2}` is read for its type, and its gamma is now *reported*
rather than dropped: only `interpolateRgb` has one in d3, and it bends the ramp's middle without moving
either end — so a chart that asked for it and got the plain ramp would look composed and be wrong
exactly where nobody checks.

## Three aggregate operations, and four diagnostics that were lying

Upstream has 26 aggregate operations and this engine had 23. The three missing were `product` and the
two exponentially weighted means, and the pair is unlike everything else in the family twice over:

- **their answer depends on the order of the rows.** Upstream accumulates `exp = r * exp + v` as the
  rows arrive, so a value's weight is `r` to the power of how many rows follow it and the *last* row
  counts most. Every other operation here is symmetric in its input.
- **they are the only two that take a parameter.** `aggregate_params` sits positionally alongside
  `ops`, and it is a *specification* property rather than an internal one — so `exponential` is
  reachable from a chart and was reachable before this only as a diagnostic. `exponential` normalises
  by `(1 - r) / (1 - r^n)` so the weights sum to one; `exponentialb` scales only by `(1 - r)`, which
  is what makes it comparable across groups of different sizes.

The more interesting half of this batch was what it found *not* to be missing. Four diagnostics said
"is not implemented" about things that are:

- `impute` accepts `value`, `mean`, `median`, `min` and `max` — upstream's whole enumerated set, and
  all five have worked here since the transform was written;
- `pivot` takes any aggregate operation, so with the three above added it takes all 26;
- `window` implements all thirteen of upstream's window operations *and* every aggregate one.

Each of those messages fired only for a name upstream itself rejects, and each read as an engine gap.
They now say what is actually true — "'x' is neither a window operation nor an aggregate one" — which
matters because a diagnostic that overstates a gap is the same failure as one that hides it: a reader
plans around something that is not there.

## `config.range` is what a named range stands for

`"range": "category"` is not a keyword. It is a **key into `config.range`**, and only when the
configuration says nothing about it does it fall through to a built-in default. This engine had the six
defaults and ignored the configuration, so a theme that set its own categorical palette got
`tableau10` anyway — and the whole `config.range` block was reported as unread, which was at least
honest.

Upstream substitutes and *re-reads*: `parseScaleRange` replaces the name with whatever the theme wrote
and parses the result as an ordinary range. Doing it in the parser rather than in the resolver is not a
detail of style — it is what lets a theme's `category` be a `{"scheme": ...}` where the built-in default
is a literal list of symbol names. The two are the same property, not two kinds of thing, and a resolver
that had already decided which kind it was could not accept the other.

That also retires the last of the "named range is not implemented" messages. All six of upstream's
names work, and what is left is a name that is neither one of them nor defined by the configuration —
which the diagnostic now says.

## `hsl(h, s, l)` had been returning null for months, and nothing noticed

An escaping accident — a Kotlin string template written as `${'$'}h`, which is the literal text `$h`
rather than the value — made three expression builders parse a nonsense string and return null:
`hsl(h, s, l)`, `rgb(r, g, b)`, and the default `as` name for a facet aggregate. Five occurrences
across two files, all from the same cause.

**Every test read a colour apart and none built one.** `ExpressionReferenceTest` had eleven vectors for
`luminance('hsl(...)')` and none for `hsl(210, 0.6, 0.4)`, so the half of each function that was broken
was the half nobody asked about. A vector for each is in now, and the pairing is worth stating as a
rule: a function with two arities needs a vector for **both**, because a test of one is not weak
evidence for the other — it is none.

**The differential could not see it either, and that was the more serious half.** Vega's colour helpers
return **objects** — `hsl(h, s, l)` is a `d3.Hsl`, not a string — and a mark encoder writes the object
straight onto the item, where the renderer stringifies it. Passing such an object through the harness's
`canonicalNumber` gave `undefined`, which `JSON.stringify` omits, so the channel *vanished from the
reference*. Every one of the 7,514 rects in Vega's platformer had its fill compared as "absent", which
is why a fill of null matched perfectly. The harness now stringifies a colour object exactly as the
renderer does — a gradient is an object too and keeps its own shape — and the platformer's terrain is
compared for the first time.

That immediately found a second, smaller thing: 85 of those rects came out pure black against
upstream's `rgb(0, 0, 4)`. Building the colour by writing the fractions out as CSS percentages and
parsing them back loses precision exactly where it shows, in a colour so dark that a channel is a
single digit. It is built from the numbers now.

And with `ColorSpaces` carrying the perceptual spaces, `lab(l, a, b)` and `hcl(h, c, l)` are
implemented rather than refused. Their components are **not** fractions — a Lab lightness runs 0 to
100 and an HCL chroma is a radius in those units — which the `hsl` beside them invites you to assume,
and which would give a colour that is nearly black for every input.

## Two things a signal could not see, and the geo measurements that needed both

`geoBounds` and `geoScale` were the last two of upstream's four geo expression functions this engine
did not have, and neither was hard. What was hard was that a signal could not reach a projection at
all.

**A dataset's transforms had been given the projections since `geoCentroid` was implemented; a
signal's own `update` had not.** So `geoScale('p')` reported the projection as undefined however late
in the dataflow it ran. The map is rebuilt at each step of the order rather than held once, because a
projection is *made of* signals — `rotate: [{signal: "lon"}, 0]` — so what is buildable changes as the
order is walked.

**And a projection reference created no ordering edge.** Upstream registers a projection in the **same
namespace as a scale** and visits `geoScale('p')` with its `scaleVisitor`, so the reference already
arrived here as a scale dependency — there was simply nowhere for it to land, because a projection is
not an operator in this engine. A projection with a `fit` is built from a dataset, so asking it
anything has to wait for that dataset: `geoScale('p')` on a projection fitted to `data('land')` answered
`1070`, which is `albers`'s own unfitted default, where upstream answered `34.3`.

One reading the fixture forced, and it is upstream's: **`geoBounds` takes a GeoJSON object, not an
array.** `geoBounds('p', data('land'))` measures nothing at all, because `geoStream` looks for a `type`
and an array has none — upstream returns `[[Infinity, Infinity], [-Infinity, -Infinity]]` and draws the
rectangle nowhere. The features have to be wrapped in a `FeatureCollection`, which is what the fixture
does.

## Nine functions nothing called, and what the sweep found

After `hsl(h, s, l)` turned out to have been returning null for want of a test, the obvious next
question was which other functions nothing calls. Subtracting the names any expression test mentions
from the names `Functions.kt` registers gives nine: `atan2`, `bandspace`, `lerp`, `pluck`, `sequence`,
`sort`, `timeSequence`, `timezoneoffset` and `trim`. The diff takes a second and is worth keeping in the
toolkit beside the schema one.

All nine matched upstream on the first run. That is the useful outcome: 22 new vectors and no new
findings, which turns "probably fine" into "checked" for the parts of the expression vocabulary the
fixtures never happen to exercise. Three of the answers are worth having written down anyway:

- `bandspace` counts **steps**, not bands, so five bands at 0.1 inner padding come to 5.3 — and
  padding is allowed to eat a whole band without the answer going negative, which would invert the
  scale;
- `lerp` short-circuits at 0 and 1 rather than computing `lo + f*(hi - lo)`, so a specification asking
  for the end of a range gets the end of it exactly;
- `timeSequence` steps in **local** time even when the result is read back as UTC, which is why its
  first entry for January 2024 in Amsterdam formats as the last day of 2023.

## `domainImplicit`, and a domain that grows as it is used

An ordinal scale normally maps an undeclared value to its `unknown` — nothing, usually, so a mark with
an unexpected category draws unpainted. `domainImplicit` makes such a value **join** the domain
instead: d3 spells it by setting `unknown` to its own `implicit` sentinel, and the effect is that the
scale's domain grows as it is used, each new value taking the range entry after the last one claimed.

That is why it is off by default, and worth saying rather than leaving to be inferred: **order of use
decides which colour a value gets**, so a chart that reorders its rows would repaint itself. It is for
a domain nobody can write down in advance. The fixture declares two values, feeds four, and the last
two take the third range entry and then wrap to the first — beside the same scale without the flag,
which simply has no colour for them.

With it, none of upstream's 23 scale properties is reported any more.

## `labelBound` culls nothing, and implementing it would have been wrong

The documented meaning is "drop an axis label that hangs past the scale's range". Implementing that is
easy, and it would have made this engine disagree with upstream on every chart that sets the property.

Upstream applies the test as `boundRectangle.encloses(item.bounds)` inside its `Overlap` transform, and
`Overlap` runs **before the label bounds exist** — `Bound` comes later in the mark's pipeline. So on a
static render every item still holds a *cleared* `Bounds` of `[+∞, +∞, −∞, −∞]`, which any rectangle
trivially encloses. Nothing is ever outside.

Established by experiment rather than by reading: a band axis 120 units wide whose first label
overflows by 68 keeps that label under `labelBound: false`, `true` and `40` alike. The first attempt
here culled two labels the reference kept, which is how the question got asked at all.

So the property is **consumed and inert**, with the reason written where the code would otherwise be —
a diagnostic saying "not implemented" would overstate a gap that has no visible consequence, and a
correct-per-the-documentation implementation would be a real difference. With it, every one of
upstream's 79 axis properties is read.

## A comma grouped an exponent

`format(200000, ',.1')` came out `2,e+5`. d3 splits a formatted value at the **first character that is
not a digit** and groups only what is before it; this engine split on the decimal point alone, so an
exponent's `e+5` was reversed into the grouping and came back with a comma in it. A percentage's `%`
was the same case.

Found by an axis, not by a formatting test: a `,` specifier over a domain of a million resolves to one
significant figure, and one significant figure of 200,000 is `2e+5`. Six vectors for it now, including
the two forms — `,.1` and `,.2e` — that reach exponential notation by different routes.

## `timeunit` picks its own buckets, and its `step` was published but not applied

Two gaps in one transform, and only one of them was on the list.

**The inference** was reported as unimplemented, and it is a table: seventeen intervals from a second
to a year, chosen by which one's duration is nearest the data's span divided by `maxbins` — *nearest in
ratio*, not in difference, which is why choosing between two neighbouring intervals compares
`target / lower` against `upper / target`. Off the ends of the table the step comes from d3's own tick
step instead: years above, milliseconds below. Ninety daily rows at the default forty bins give days;
the same ninety asked to fit four give months.

**The step was worse, because it was silent.** `step` was read, published in the transform's own
signal, and never applied: `step: 3` bucketed by month and then announced that it had bucketed by
quarter. It applies to the **finest** unit only — a `{year, month}` bucket at step 3 is a quarter, not
three years of quarters — with a phase of 1 for the units counted from one rather than zero, because
`3 * floor(month / 3)` puts January in a bucket starting at month zero, which is December of the year
before.

## An explicit format on a time axis was ignored

The fixture for the above wanted `"format": "%b"` on a `utc` axis and got `2024`, `February`, `March`
— the multi-format, which writes each tick at its own granularity and carries the year on the first
one of a year. A named format should replace that for every tick alike.

The linear branch of the tick generator had always made that distinction and the **time** branch never
had: it took `scale.tickLabels(count)` unconditionally, so an axis that named a format was labelled as
if it had not. The accessibility caption had the same hole, one layer down — it read the whole
timestamp out where upstream describes a `%b` axis as "January to March", expanding the abbreviating
directive as it does everywhere else.

Neither would have been found by the fixture the property belonged to. Both were found because a
fixture *about something else* happened to put a format on a time axis, which is the argument for
writing fixtures that combine features rather than isolate them.

## Layout `center`, and two more diagnostics that were overstating

`center` puts a cell narrower than its column in the middle of it rather than at its start. Upstream
guards it twice over and both guards are load-bearing: horizontally it needs more than one **row**, and
vertically more than one **column**, because a single row of cells has nothing to centre against —
every column is exactly as wide as the one cell in it. The correction is `x > 0` too, so the widest
cell in a column, which has no slack, is not pulled backwards out of it.

The facet aggregate message went the way the `impute`, `pivot` and `window` ones did earlier: a facet
measures with any aggregate operation, so with all 26 implemented the report fired only for a name
upstream also rejects. It says that now.

## A trellis has footers, and this engine was gridding them

`row-footer` and `column-footer` were not in `TrellisRole`, so they fell through to `CELL` — which does
not merely misplace them, it **grids them among the cells**. A trellis of four cells with four row
footers laid out as an eight-cell grid. Nothing reported it, because a group mark with no recognised
role is a cell by design and that is exactly what an unrecognised one looked like.

With the two roles added, the whole `layout` block closes: `headerBand`, `footerBand`, `titleBand`,
`titleAnchor` and `offset`, which is all ten of upstream's properties. Four things in it are upstream's
and none is guessable:

- **a band is `null` by default for a header and `0.5` for a title.** `null` means the cell's own
  origin, not its middle — so a header without a band lines up with the corner of the cell it names,
  and a title without one is centred on the grid.
- **a title is centred on the *cells*, not on the grid plus its headers.** Upstream returns the cell
  bounds from `gridLayout` and lays the headers out afterwards, so the question never arises there;
  here the header pass had already widened the bounds the title was centred on, which moved every
  title by half the width of its own row labels.
- **`titleAnchor: "end"` measures from the footers**, not from the far side of the cells — so a title
  anchored to the end of a trellis that has footers clears them.
- **more headers than rows is not an error and not a reason to drop one.** Upstream lays out the first
  `limit` and leaves the rest where they were; the limit is the number of rows or columns and *not*
  the number of cells, so six column headers over a two-by-three grid label three columns and the
  other three stay put. Dropping them changes the mark count, which is a bigger difference than a
  label in the wrong place.

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
3. **Keep growing the fixture corpus.** The brief's 100 is reached; the corpus is now aimed at the three categories the brief used to rule out. Aiming it at *combinations* the
   engine has not met rather than at more variations of a single feature is what makes it find
   things: that is how `scale()` in an expression turned up missing. Untried combinations that
   remain include an axis on a discretizing scale, a group whose signals shadow the outer scope's,
   and a `timeunit` transform feeding a `time` scale across the same daylight-saving boundary the
   `local-time-dst` fixture crosses.

Faceting came off this list by being finished, and what it leaves at the front of the Vega-Lite
work is the **composite-mark normalizer** — `boxplot`, `errorbar` and `errorband`, which upstream
rewrites into layered views before compiling anything. Layers already work, so it is the rewriting
that is missing, and it is the last piece that adds *charts* rather than compositions. After it,
`hconcat`/`vconcat`/`concat` and `repeat`, which need a second kind of layout each.

~~A fourth candidate: **a transform cannot read a computed signal**.~~ **Resolved**, by the
dependency ordering that replaced the compile phases. It was described here as needing "the signal
pass to run in dependency order across the data boundary, which is most of a real dataflow", and
that was the right diagnosis — `DataflowOrder` is that ordering.

Two things about it are worth keeping. The chart named as the evidence, Vega's `radial-tree-layout`,
is now a fixture and matches upstream exactly; it had been drawing its whole diagram on the origin.
And the reasoning that no fixture could cover it — "upstream draws it correctly, so a fixture written
around it would only record the disagreement" — was **wrong**, and expensively so. Recording the
disagreement is precisely what a differential fixture is for: it fails, it names the gap, and it
passes when the gap closes. Every example taken up since has followed that route.

One item still needs something this environment does not have: performance on **physical hardware**
(PROJECT_BRIEF.md 19, criterion 13). The emulator is available and useful for behaviour, but
PROJECT_BRIEF.md 18.6 says emulator timings are not authoritative, and it is right.
**TalkBack** (criterion 8) is no longer one of them. It was enabled on the emulator and the demo
explored with it; the tree was already correct and two things it *said* were wrong, both now fixed
and pinned by instrumented tests. What remains untested there is physical hardware and a real user,
which is a different claim from "not verified at all".

A note on the harness, because it is now the fifteenth time. The differential comparison has had to be
taught to see a symbol's outline, fill and stroke opacity, a dash pattern, a node's own opacity, an
unfilled mark's missing opacity, the corners a curve puts between a series' points, a rectangle's four
corner radii, a series' `tension`, the whole drawn extent of every `shape` mark, and — adding `linkpath` — the outline a `path` mark actually draws,
which until then was compared only by the anchor it hung from. Each was invisible for the same reason — two marks agreeing on every channel
being compared and differing in the drawn result. Before trusting a green fixture on a *new* kind of
property, check that
`oracle-js/src/normalize.js` and `Differential.kt` both emit it. And look at the two pictures: three
of the six were found that way, most recently a renderer drawing the full text of a label it had
measured as truncated.
