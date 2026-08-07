# Supported features

Statuses: **Supported** · **Partial** · **Planned** · **Not planned** · **Blocked**

"Tests" names the test class or golden that covers the row. An unsupported construct always produces a
structured diagnostic; nothing is silently ignored (PROJECT_BRIEF.md 3.3, 14).

**Read the Marks table carefully.** A row says "Supported" only when the engine can *produce* that
construct from an input specification. Where a scene node type exists but nothing encodes data into it
yet, the row says **Renderable** — the geometry draws and hit-tests correctly, but there is no mark
encoder, so a Vega specification cannot ask for it. Six of the twelve mark types are now Supported; see
the scope note in STATUS.md for how much of upstream Vega remains.

## Input

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Hand-authored scene graph | Supported | `SampleScenes`, `SceneSnapshotTest` | Not a Vega input format | 1 |
| Compiled Vega JSON | Partial | `SpecCompilerTest`, `FixtureDifferentialTest` | Parses and compiles the subset below, including signals, expressions, 12 transforms, nested group scopes, legends and titles. No group `layout`; it reports a diagnostic. Loaded through `VegaChartController.setSpec`, or `setSpecAsync` to compile off the calling thread | 3 |
| Vega-Lite compilation | Not planned (first release) | — | Compile upstream via `oracle-js/src/compile-vega-lite.js` | — |
| Generic JSON value model | Supported | `VegaValueTest`, `JsonBridgeTest` | Numbers are always `Double` | 0 |
| Dotted / bracketed field paths | Supported | `VegaValueTest` | Malformed paths resolve to null rather than throwing | 0 |

## Marks

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Group | **Supported** | `GroupMarkTest`, `FixtureDifferentialTest` (`facet-trellis` fixture) | One container per datum, translated by its `x`/`y`, painting its fill and stroke over its declared `width` × `height`. `clip` narrows it. Nests to any depth | 3 |
| Faceting (`from.facet`) | **Supported** | `GroupMarkTest`, `FixtureDifferentialTest` (`facet-trellis` fixture) | `groupby` only, on one or more fields. Cells appear in first-appearance order and each cell's datum is the groupby fields plus `count`, matching the `aggregate` transform upstream inserts. Pre-faceted `facet.field` and extra `facet.aggregate` measures are reported | 3 |
| Group scopes (nested `data`, `signals`, `scales`, `axes`, `marks`) | **Supported** | `GroupMarkTest` | A nested definition shadows a same-named one outside. Two upstream behaviours are reproduced rather than corrected: `parent` is the group's datum, not the group item, so `parent.width` is undefined; and `width`/`height` are inherited, so a nested `"height"` range spans the whole chart unless the group declares its own `height` signal | 3 |
| Group `layout` (the trellis grid) | **Supported** | `GroupMarkTest`, `FixtureDifferentialTest` (`trellis-layout`) | Automatic row and column placement with per-direction padding, at the top level or on a group. Cells are measured by how far their contents reach, not by their declared size, so a trellis whose axis labels hang off to the left leaves room for them. `align`, `bounds`, `center` and footers are reported | 5 |
| Rect | **Supported** | `SpecCompilerTest`, `FixtureDifferentialTest` | Encoder handles x/x2/width and y/y2/height pairs, band offsets, fill, stroke, opacity, corner radius. Geometry matches upstream exactly on the bar fixture | 3 |
| Rule | **Supported** | `SpecCompilerTest` | A missing `x2`/`y2` defaults to `x`/`y`, as upstream. Bounds expand on both axes, so they are slightly conservative for a butt-capped rule | 3 |
| Line | **Supported** | `SpecCompilerTest`, `FixtureDifferentialTest` (`line-area`, `area-gaps`) | One `PathNode` per series where upstream emits one item per datum; the harness normalizes both to a point list. A `null` in the data does **not** break the line — upstream reads the coordinate as `item.y \|\| 0` and draws through zero — and the `defined` channel is what breaks it. Interpolation methods other than linear are reported, not approximated | 3 |
| Area | **Supported** | `FixtureDifferentialTest` (`line-area`, `area-gaps`) | Both orientations, via the (x, y) and (x2, y2) boundary pairs, and a band between two data fields rather than a baseline. Stacking comes from the `stack` transform. `defined` and interpolation as for `line` | 3 |
| Symbol | **Supported** | `SceneNodeTest`, `FixtureDifferentialTest` (`log-scale` fixture) | All 12 of upstream's shapes, pinned by reference vectors. Sized upstream's way, from `sqrt(size) / 2`, **not** d3-shape's area convention — the two differ by 13% on a circle. An SVG path string is reported and drawn as a circle | 3 |
| Text | **Supported** | `FixtureDifferentialTest` (`line-area` fixture) | Align, baseline, font, size, weight, style, angle and `dx`/`dy`. Metrics are platform metrics, not browser metrics | 3 |
| Arc | **Supported** | `SpecCompilerTest`, `FixtureDifferentialTest` (`pie`) | Annular sectors, so pie and donut charts draw. Angles run clockwise from twelve o'clock. `padAngle` and `cornerRadius` are reported rather than approximated — upstream insets a padded arc against a pad *radius* and rounds corners with tangent circles, and a visibly wrong slice is worse than an honestly missing one | 5 |
| Image | Renderable | `SceneExportTest` | The scene node works and needs an `AndroidImageResolver`; there is no encoder | 5 |
| Arc | Planned | — | — | 3 |
| Path (from SVG path strings) | Planned | — | Path-string parsing not implemented | 3 |
| Trail | Not planned (first release) | — | — | — |
| Shape (geo) | Not planned | — | Needs projections, an explicit non-goal | — |

## Scales

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Linear | **Supported** | `ScalesTest`, `TicksTest`, `FixtureDifferentialTest` | Domain, range, ticks and `nice` match d3 and upstream exactly. `zero` defaults to true for data-driven domains, as upstream does | 3 |
| `reverse` | **Supported** | `SpecCompilerTest`, `FixtureDifferentialTest` (`band-padding`) | Flips the **range**, leaving the domain alone, for every scale type. Reversing the domain instead maps every value to the same place, so the chart looks right and the ticks, labels and domain line all come out backwards | 3 |
| Time, UTC | **Supported** | `TimeScaleTest`, `FixtureDifferentialTest` (`time-axis`) | Ticks land on calendar boundaries from d3's own table; `nice` widens to one of them; each label is written at its own granularity, so a monthly axis reads "2026" every January and "February" otherwise. A day is a calendar day, so a daily tick holds midnight across a daylight-saving change. Locales and explicit axis format strings are not implemented | 6 |
| Reading dates | **Supported** | `TimeScaleTest`, `SpecCompilerTest` | `format.parse` with `date`, `number`, `string` and `boolean`. ISO 8601 only: a bare date is UTC and one carrying a wall-clock time is local, matching JavaScript. Anything else is reported rather than guessed at, because upstream falls back to the host's parser and that differs between browsers | 6 |
| Signal-valued scale domain | **Supported** | `FixtureDifferentialTest` (`expressions` fixture) | `{"domain": {"signal": "..."}}`, which is how a specification points a scale at what the `extent` transform published. A signal that produces nothing is reported rather than defaulted to `[0, 1]` | 4 |
| Band | **Supported** | `ScalesTest`, `FixtureDifferentialTest` | Step, bandwidth, padding, align and round match d3-scaleBand exactly | 3 |
| Point | **Supported** | `ScalesTest` | Implemented as a band scale with paddingInner 1, as d3 does | 3 |
| Ordinal | **Supported** | `ScalesTest` | Explicit range arrays only; colour schemes report `VEGA_SCALE_UNSUPPORTED_TYPE` | 3 |
| Log | **Supported** | `TransformedScalesTest`, `FixtureDifferentialTest` | Configurable base; a domain spanning or touching zero is reported rather than adjusted. Ticks are the powers and their multiples, falling back to linear spacing when that is too sparse, and crowded labels are blanked as d3 does | 3 |
| Pow, sqrt | **Supported** | `TransformedScalesTest` | `pow` defaults to exponent 1, i.e. linear, as upstream; `sqrt` is exponent 0.5. Negative domains handled by sign | 3 |
| Symlog | **Supported** | `TransformedScalesTest` | `sign(x) * ln(1 + abs(x) / constant)`; handles zero and both signs | 3 |
| Sequential colour | **Supported** | `ColorScaleTest`, `FixtureDifferentialTest` | An explicit colour range interpolated in RGB or Lab, clamped at both ends as upstream does | 3 |
| Categorical colour schemes | **Supported** | `ColorScaleTest`, `FixtureDifferentialTest` | 15 palettes, values read out of `vega.scheme()` so they are exact: category10/20/20b/20c, tableau10/20, accent, dark2, paired, pastel1/2, set1/2/3, observable10 |
| Continuous colour ramps (`viridis`, `blues`, …) | Not implemented | `ColorScaleTest` | Each needs d3's interpolator table, and Vega also samples ramps over a default extent of roughly `[0.2, 1]`. Both are reported by name so the diagnostic distinguishes "upstream has it, we do not" from "no such scheme" | 5 |
| Colour interpolation spaces | Partial | `ColorScaleTest` | `rgb` and `lab`. `hcl`, `hsl` and `cubehelix` fall back to RGB with a diagnostic | 5 |
| Quantile, quantize, threshold, bin-ordinal | Not planned (first release) | — | Reports `VEGA_SCALE_UNSUPPORTED_TYPE` | — |

## Data transforms

Upstream Vega has 40 transforms; the 12 the brief lists for the first release are implemented.
Expected values in `TransformReferenceTest` were all generated from upstream.

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| `filter` | **Supported** | `TransformReferenceTest` | A broken expression leaves the data alone rather than dropping every row | 4 |
| `formula` | **Supported** | `TransformReferenceTest` | — | 4 |
| `collect` | **Supported** | `TransformReferenceTest` | Missing values sort first ascending, as upstream; multi-field with per-field order | 4 |
| `project` | **Supported** | `TransformReferenceTest` | — | 4 |
| `identifier` | **Supported** | `TransformReferenceTest` | Numbers from 1 per pipeline, not per view, so ids are reproducible but not globally unique | 4 |
| `extent` | **Supported** | `TransformReferenceTest` | Publishes a signal; missing values excluded | 4 |
| `aggregate` | **Supported** | `TransformReferenceTest` | 17 ops. `count` counts tuples while `valid` counts values; `variance`/`stdev` are the sample forms | 4 |
| `joinaggregate` | **Supported** | `TransformReferenceTest` | — | 4 |
| `bin` | **Supported** | `TransformReferenceTest` | Step algorithm ported from vega-statistics; out-of-extent values get null bounds. Deriving a missing `extent` from the data is an addition upstream does not make, and is reported | 4 |
| `stack` | **Supported** | `TransformReferenceTest` | `zero`, `center` and `normalize`; negatives stack away from zero; `sort` reorders stacking only | 4 |
| `fold` | **Supported** | `TransformReferenceTest` | — | 4 |
| `flatten` | **Supported** | `TransformReferenceTest` | Parallel fields flatten together; empty arrays drop the row | 4 |
| `timeunit` | **Supported** | `TimeUnitTransformTest` (upstream vectors), `FixtureDifferentialTest` (`timeunit`) | Buckets a date into the calendar period the listed units describe, emitting the bucket start and the next one. The floor is *built* from the units present, so omitting the year deliberately collapses every January onto one bucket — a seasonal profile rather than a timeline. `week`, `day` and `dayofyear` need week numbering and are reported; so is inferring the units from the data | 6 |
| `pie` | **Supported** | `FixtureDifferentialTest` (`pie`) | Turns a column of numbers into start and end angles. Absolute values are taken, because a negative slice would run backwards over its neighbour rather than shrinking; with no `field` every row gets an equal share | 5 |
| The other 28 transforms | Not implemented | `TransformReferenceTest` | An unimplemented transform stops the pipeline and reports it, so later stages never run on data they were not meant to see | 4+ |
| Tuple identity, change sets | Supported | `DataflowTest` | Data model only; the transform pipeline does not use it yet | 0 |
| Deterministic operator scheduling | Supported | `DataflowTest` | Topological ordering only | 0 |
| Pulse propagation / incremental evaluation | Not implemented | — | Transforms currently recompute a whole dataset. The `DataflowOperator` contract exists; there is no incremental engine behind it | 4 |
| Geo transforms, force layout | Not planned | — | Reports `VEGA_TRANSFORM_NOT_IMPLEMENTED` | — |

## Expressions

Upstream Vega exposes 119 expression functions; 60 are implemented. The language itself is complete.

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Lexer, parser, evaluator | **Supported** | `ParserTest`, `ExpressionReferenceTest` | Full JavaScript expression subset: literals, members, calls, unary, binary, logical, bitwise, conditional, array and object literals. No regular-expression literals or template strings — both are syntax errors | 4 |
| JavaScript coercion semantics | **Supported** | `ExpressionReferenceTest` (115 upstream vectors) | `+` concatenates for strings, arrays and objects; `==` is loose; strings compare lexicographically; `-7 % 3` is `-1`; `round(-2.5)` is `-2` | 4 |
| `null` versus `undefined` | Known difference | `ExpressionReferenceTest`, `GroupMarkTest` | This value model has one absent value where JavaScript has two, so a missing property stringifies as `"null"` where upstream prints `"undefined"`. Arithmetic and truthiness agree; only the printed form differs, and only for an expression that stringifies an absent value | 5 |
| Math, string, array, predicate and coercion functions | **Supported** | `ExpressionReferenceTest` | 60 of 119. `min`/`max` are `Math.min`/`Math.max`, so an array argument is NaN | 4 |
| Date functions | **Supported** | `ExpressionReferenceTest` (23 upstream vectors) | `datetime`, `utc`, `toDate`, `time`, `timezoneoffset`, `timeFormat`/`utcFormat`, and every accessor with its `utc` twin. Month and day-of-week are zero-based and quarter and day-of-year are not, as in JavaScript; construction rolls over, so `utc(2026, 12, 1)` is January 2027. A date is epoch milliseconds rather than its own type, so `isDate` cannot tell one from a number and reports | 6 |
| `timeParse`, `utcParse` | Not implemented | `ExpressionReferenceTest` | Parsing against a format string needs a `strptime`; an ISO 8601 string works through `toDate`. Reported by name | 6 |
| `format` | Partial | `ExpressionReferenceTest` | Supports `.Nf`, `.Ne`, `.N%`, `d` and `,` grouping. Other d3-format specifiers fall back to plain number formatting | 5 |
| Date and time functions | Not implemented | — | Reported by name with a reason; needs time scales first | 5 |
| `scale`, `invert`, `gradient` | Not implemented | — | Need a scale registry the evaluator cannot reach yet | 6 |
| `random` and the stochastic functions | Not planned | `ParserTest` | Excluded so a scene stays reproducible (PROJECT_BRIEF.md 18.2); reported with that reason | — |
| Colour, geo and selection helpers | Not implemented | — | Belong to subsystems that do not exist yet | — |
| Signals | **Supported** | `SignalCompileTest` | `update` beats `init` beats `value`; dependency-ordered; `width`, `height` and `padding` implicit; cycles reported by name | 4 |
| Signal event handlers (`on`) | Not implemented | `SignalCompileTest` | Reported; the signal keeps its initial value. Needs the interaction system | 6 |
| Signal bindings (`bind`) | Not planned | `SignalCompileTest` | Input widgets have no equivalent here | — |
| Signal-valued scale and axis properties | **Supported** | `FixtureDifferentialTest` | `padding`, `align`, `tickCount`, `tickSize`, `labelPadding`, `labelFontSize`, `offset` | 4 |
| Conditional encode rules | **Supported** | `SignalCompileTest` | The `[{test, ...}, {...}]` array form, for every channel kind | 4 |
| `eval` / JavaScript execution | Not planned | — | Explicitly forbidden (PROJECT_BRIEF.md 6.1) | — |

## Layout

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Width, height, viewport | Supported | `SceneSnapshotTest` | Padding and `autosize` come with spec parsing | 1 |
| Group transforms | Supported | `SceneNodeTest` | — | 1 |
| Clip rectangles and clip paths | Supported | `AndroidCanvasSceneRendererTest`, `SvgRendererTest` | — | 1 |
| Axes | **Supported** | `SpecCompilerTest`, `FixtureDifferentialTest` (`axis-variants`) | Ticks, labels, gridlines, domain line and title for all four orientations, plus `offset`, `zindex`, and switching any of the four parts off. A gridline runs back across the plot away from its own side, and an axis with its ticks off pulls its labels in by the tick size. No label overlap removal, no `encode` overrides, no format strings | 5 |
| Axis titles | **Supported** | `TitleTest`, `FixtureDifferentialTest` (`titles` fixture) | Placed a padding beyond however far the ticks and labels reach — gridlines excluded, as upstream excludes them — anchored along the scale's range, and turned a quarter turn on a vertical axis | 5 |
| Symbol legends | **Supported** | `LegendTest`, `FixtureDifferentialTest` (`legends` fixture) | One swatch and label per entry, filled from the scale. Row heights, label offsets and the legend's own size match upstream exactly. A legend that maps no colour gets grey outlined swatches, as upstream does | 5 |
| Gradient legends | **Supported** | `LegendTest`, `FixtureDifferentialTest` (`legends` fixture) | Sampled at the scale's own ticks plus the domain ends, so a multi-stop ramp bends where upstream's does. Vertical and horizontal, with end labels baselined inside the swatch | 5 |
| Legend placement | **Supported** | `LegendTest` | All four edges, all four corners, `orient: none` with `legendX`/`legendY`, and stacking for legends that share an orientation. A vertical axis pushes out a side legend and a horizontal one an edge legend, kept separate as upstream does | 5 |
| Legend entry layout | **Supported** | `LegendTest`, `FixtureDifferentialTest` (`size-legend` fixture) | Each entry advances by the previous cell's far edge rounded up, plus however far the next one overhangs backwards, plus the padding. With uniform swatches any reasonable rule agrees; with a size legend, where each swatch outgrows the last, they diverge by several units a row | 5 |
| Discrete (banded) legends | Not implemented | `LegendTest` | For quantize, quantile and threshold scales, none of which exist here yet. Reported | 6 |
| Legend entry columns | **Supported** | `LegendTest`, `FixtureDifferentialTest` (`legend-columns`) | `columns` wraps the entries, filling *down* each column before moving across — the order a reader scans a list — and the same grid code places a trellis | 6 |
| Legend overlap removal and limits | Not implemented | `LegendTest` | `labelOverlap`, `symbolLimit` and `encode` overrides are each reported by name | 6 |
| Trellis headers and titles | **Supported** | `FixtureDifferentialTest` (`trellis-headers`) | A group marked `row-header`, `column-header`, `row-title` or `column-title` is arranged around the grid rather than gridded into it. A header tracks the cell it labels on one axis and the grid's edge on the other; a title sits halfway along, outside the headers. Footers, `headerBand` and `titleBand` are reported | 5 |
| Group titles | **Supported** | `FixtureDifferentialTest` (`trellis-headers`) | A `title` on a group mark, built in that group's own scope — which is how a trellis header gets its words, since its text is usually `{"signal": "parent.<field>"}` | 5 |
| Chart title and subtitle | **Supported** | `TitleTest`, `FixtureDifferentialTest` (`titles` fixture) | All four orientations and all three anchors, verified against upstream in every combination. Placed against the whole drawing — axes and legends included — not the plotting area, unless `frame: "group"` says otherwise. `encode` overrides and explicit align, angle and limit are reported | 5 |
| Label collision handling | Planned | — | — | 5 |
| Padding and `autosize` | Partial | `SpecCompilerTest`, `FixtureDifferentialTest` | `pad` and `none` implemented. `fit`, `fit-x`, `fit-y` need a second layout pass and fall back to `pad` with a diagnostic. Surface size matches upstream to within half a pixel per axis — see the note below | 3 |

## Interaction

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Hit testing (linear and grid) | Supported | `HitTestTest` | Grid, not R-tree; the two agree by test | 1 |
| Touch vs mouse tolerance | Supported | `HitTestTest` | Tolerance never changes visual bounds | 1 |
| Tap, hover, long press | Supported | `VegaChartControllerTest`, `VegaChartViewTest` | The host must publish `contentScale`, otherwise taps miss by the fit factor | 1 |
| Tooltip content and anchor | Partial | `VegaChartControllerTest` | Content comes from node metadata; no tooltip rendering yet | 6 |
| Point selection | Supported | `VegaChartControllerTest` | — | 1 |
| Interval selection | Partial | `HitTestTest` (`nodesIntersecting`) | No drag-to-select gesture yet | 6 |
| Pan | Supported | `VegaChartControllerTest` | View transform; axes do not rescale | 1 |
| Pinch and wheel zoom | Supported | `VegaChartControllerTest` | As above; clamped to 0.1×–50× | 1 |
| Signal updates from input | Planned | — | Needs the signal system | 6 |
| Keyboard focus navigation | Partial | `VegaChartViewTest` | Key events are translated; traversal semantics come with accessibility work | 7 |

## Outputs

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Android Canvas | Supported | `AndroidCanvasSceneRendererTest` | — | 1 |
| SVG | Supported | `SvgRendererTest` + 5 goldens | Text advances differ from a browser's | 2 |
| Bitmap | Supported | `SceneExportTest` | — | 1 |
| PNG | Supported | `SceneExportTest` | — | 1 |
| PDF | Supported | `SceneExportTest` | Single page; unsupported operations surface as warnings | 1 |
| Canonical scene snapshot | Supported | `SceneSnapshotTest` + 5 goldens | — | 1 |

## Differential testing against upstream Vega

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Comparison harness | Supported | `Differential` | Compares mark count, type, role, coordinates, extents and scale outputs in absolute content space | 3 |
| Reference generation | Supported | `oracle-js/src/reference.js`, `scripts/oracle.sh` | References are checked in, so JVM tests need no Node and no network | 3 |
| Fixtures passing | 26 of a target 100 | `FixtureDifferentialTest` | `bar`, `stacked-bar`, `line-area`, `log-scale`, `colour-scheme`, `facet-trellis`, `legends`, `titles`, `histogram` (bin + aggregate + grid), `dot-plot` (point scale, rules, four symbol shapes, right axis), `text-anchors` (every align and baseline, rotation, dx/dy), `expressions` (signal-valued domain and padding, filter, formula, extent, conditional fill), `nested-groups` (a group inside a group, clipped), `size-legend` (a size legend and a horizontal one), `reshape` (fold, project, collect with a two-key sort, identifier), `share-of-total` (joinaggregate written back onto every row), `area-gaps` (a horizontal band area and a line through a null), `axis-variants` (a top axis, an offset one, a grid above the marks, one stripped of its domain and ticks), `band-padding` (separate inner and outer padding, non-central alignment, rounding, a reversed range), `flatten-arrays` (parallel array fields expanded and re-aggregated), `time-axis` (two UTC scales at different granularities), `timeunit` (rows bucketed into calendar months, then counted), `legend-columns` (entries wrapped into two columns), `trellis-layout` (five cells gridded by a layout), `trellis-headers` (a grid with row and column headers), `pie` (a donut drawn from the pie transform). Every mark, scale output and surface size matches exactly | 3 |
| Fixture discovery | Supported | `FixtureDifferentialTest` | Fixtures are enumerated from the directory rather than listed, so adding one is a single file and forgetting its reference fails loudly | 3 |
| Surface size | **Exact** | `FixtureDifferentialTest` | Every fixture's surface matches upstream to the unit, with no tolerance. Three separate behaviours had to be reproduced to get there — see the axis-measurement row below | 3 |
| Curved mark extents | Known difference | `Differential` | Upstream measures an arc exactly, from its centre and radii, because its renderer emits a true circular arc. This scene graph has only cubics — every backend it draws on takes them — so an arc's bounds are the bounds of the curves actually painted. About one part in a hundred thousand: a thousandth of a pixel on a 90-unit radius, and on the side of describing what is drawn | — |
| Symbol extents compared | Supported | `Differential`, `oracle-js/src/normalize.js` | Both sides report a symbol's drawn extent, not just its `size` channel. Added because comparing `size` alone hid a wrong shape table for six shapes | 3 |
| Spurious paint detected | Supported | `Differential` | A fill or stroke present here and absent upstream is reported, so an outline that should not be there cannot pass by being an extra channel | 3 |
| Fixture SVG side by side | Supported | `FixtureSvgTest` | Writes every fixture to `build/fixture-svg/` beside the oracle's own SVG, so a disagreement can be looked at rather than only read as coordinate deltas | 3 |
| Nested scale outputs | Not compared | `Differential` | The reference records only top-level scales, because a faceted group resolves its scales once per cell and there is no single scale of that name to compare. The cells' geometry is compared in full, which is what those scales produce | 5 |
| Series normalization | Supported | `Differential` | Vega emits one item per datum for a line or area; this engine builds one path. Both sides collapse to an outline point list, compared numerically | 3 |
| Axis measurement | **Supported** | `AxisBuilder`, `TitleTest`, `FixtureDifferentialTest` | An axis is measured by its *extent* — the full scale range along it, the tick and label reach across it — and not by the items it happened to draw. Gridlines are excluded, and the half-pixel crisp offset is not counted. `AxisBuilder.BuiltAxis.guideBounds` carries this separately from the node's own bounds, and legends, titles and the surface size are all placed against it. Previously a documented difference of up to 1 unit per axis; now exact | 3 |
| Miter allowance in bounds | **Supported** | `Style`, `FixtureDifferentialTest` | A stroked path reserves 4 stroke widths for a miter join, which is what upstream reserves — not the 10 a canvas defaults to. A 3-unit line is 6 units longer than its points, not 15, and under `autosize: pad` that lands in the chart's size. The two only draw differently at a join sharper than 29°, which no chart mark produces | 3 |
| Text bounds in comparisons | **Supported** | `VegaHeadlessTextEngine` | Reproduces upstream's own baseline arithmetic, including its double rounding: a 12px top-baselined box starts one unit above its anchor where a 10, 11 or 13px one starts on it. Confined to the comparison engine, so a real device does not inherit the artefact | 3 |
| Text baseline placement | Known difference | — | Upstream positions glyphs by its own approximation, `round(0.79 × fontSize)` for a top baseline; this engine asks the platform, through SVG's `dominant-baseline` or Android's font metrics. They differ by up to a unit at common sizes. Deliberate (docs/adr/0006): real metrics are the better answer for something being read, and the harness excludes glyph bounds for exactly this reason | — |
| Text content compared as text | Supported | `Differential`, `oracle-js/src/normalize.js` | A numeric field makes a numeric `text` upstream; both engines draw its string form, so the comparison is on that rather than on the type | 3 |
| Text `dx`/`dy` under rotation | **Supported** | `FixtureDifferentialTest` (`text-anchors` fixture) | Applied inside the rotation, as upstream does, so an offset on rotated text runs along the text rather than along the page. Both sides of the harness had folded them into the anchor before rotating, which agreed with each other and with neither renderer | 3 |
| Text metrics in comparisons | Supported | `VegaHeadlessTextEngine` | Reproduces upstream's canvas-free estimate, `trunc(0.8 × chars × fontSize)`, so layout is comparable. A comparison engine only — never used for display | 3 |

## Rendering details

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Solid fill and stroke | Supported | `AndroidCanvasSceneRendererTest` | — | 1 |
| Stroke caps, joins, miter limit | Supported | `SvgRendererTest` | — | 1 |
| Dash arrays | Supported | `SvgRendererTest` | Odd-length arrays are doubled for Android's even-length requirement | 1 |
| Opacity | Supported | `AndroidCanvasSceneRendererTest` | — | 1 |
| Linear and radial gradients | Supported | `SvgRendererTest` | Capped at 32 stops on Canvas; excess reported | 1 |
| Blend modes | Partial | — | Below API 29 only the PorterDuff subset; anything else reports `VEGA_RENDER_UNSUPPORTED_BLEND_MODE` | 1 |
| Nested transforms | Supported | `SceneNodeTest`, `AndroidCanvasSceneRendererTest` | — | 1 |
| Multiline text | Supported | `SvgRendererTest`, `AndroidTextEngineTest` | — | 1 |
| Text wrapping | Supported | `AndroidTextEngineTest` | `StaticLayout` line breaking on Android, space-based in `MetricTextEngine` | 1 |
| Font fallback | Supported | `AndroidTextEngineTest` | Platform fallback; not browser-compatible | 1 |
| HTML labels, CSS parsing | Not planned | — | — | — |

## Accessibility

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Virtual accessibility descendants | Supported | `VegaChartViewTest` | One provider for the whole chart | 1 |
| Mark labels with value | Supported | `VegaChartViewTest` | — | 1 |
| Activation action | Supported | `VegaChartViewTest` | — | 1 |
| Selected state | Supported | `VegaChartViewTest` | — | 1 |
| Dense-chart summary | Partial | `VegaChartViewTest` | Collapses above 120 focusable marks; the summary text is minimal | 7 |
| Light and dark palettes | Supported | `DemoActivityTest` | `SampleScenes.Palette` switches chrome colours; geometry is identical | 1 |
| Axis and legend descriptions | Planned | — | — | 7 |
| Domain adjustment and reset-zoom actions | Planned | — | — | 7 |

## Platform

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| View API | Supported | `VegaChartViewTest` | — | 1 |
| Compose API | Supported | `VegaChartComposeTest` | Hosts the View via `AndroidView`; no direct `DrawScope` backend. Tests avoid Espresso, which is broken on API 37 | 8 |
| No Android types in the core | Supported | Enforced by module structure and `NoAndroidTypesTest` | — | 0 |
| Core portable to Kotlin Multiplatform | Supported | `NoAndroidTypesTest` | One file, `PlatformDecimals`, is platform code and says why: rounding a decimal at N places must round the double's **exact** binary value — `2.675` is stored as `2.67499999…`, so `%.2f` is `2.67` and rounding the string is `2.68` — and expanding a double exactly needs arbitrary-precision arithmetic common Kotlin lacks. It becomes the `expect` when the core goes multiplatform; the guard test permits that one file and fails on anything else | — |
| `minSdk` | 26 | — | Raised from 23 for `kotlinx-datetime`, which is implemented on `java.time`. Core library desugaring was the alternative and was removed: 26 covers effectively every device, and carrying a backport to avoid raising it is the worse trade | — |
| WebView, JavaScript, NDK, GPU backends | Not planned | — | — | — |
