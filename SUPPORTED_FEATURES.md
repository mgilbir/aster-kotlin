# Supported features

Statuses: **Supported** · **Partial** · **Planned** · **Not planned** · **Blocked**

"Tests" names the test class or golden that covers the row. An unsupported construct always produces a
structured diagnostic; nothing is silently ignored (PROJECT_BRIEF.md 3.3, 14).

**Read the Marks table carefully.** A row says "Supported" only when the engine can *produce* that
construct from an input specification. Where a scene node type exists but nothing encodes data into it
yet, the row says **Renderable** — the geometry draws and hit-tests correctly, but there is no mark
encoder, so a Vega specification cannot ask for it. Most of the engine is currently in that state: see
the scope note in STATUS.md for how much of upstream Vega that leaves.

## Input

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Hand-authored scene graph | Supported | `SampleScenes`, `SceneSnapshotTest` | Not a Vega input format | 1 |
| Compiled Vega JSON | Partial | `SpecCompilerTest`, `BarFixtureDifferentialTest` | Parses and compiles the subset below. No signals, transforms, legends, titles or faceting; each reports a diagnostic. `VegaChartController.setSpec` still reports not-implemented — the compiler is not wired into it yet | 3 |
| Vega-Lite compilation | Not planned (first release) | — | Compile upstream via `oracle-js/src/compile-vega-lite.js` | — |
| Generic JSON value model | Supported | `VegaValueTest`, `JsonBridgeTest` | Numbers are always `Double` | 0 |
| Dotted / bracketed field paths | Supported | `VegaValueTest` | Malformed paths resolve to null rather than throwing | 0 |

## Marks

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Group | Renderable | `SceneNodeTest`, `AndroidCanvasSceneRendererTest` | Transforms, clipping and paint work. No group-mark faceting or layout | 3 |
| Rect | **Supported** | `SpecCompilerTest`, `BarFixtureDifferentialTest` | Encoder handles x/x2/width and y/y2/height pairs, band offsets, fill, stroke, opacity, corner radius. Geometry matches upstream exactly on the bar fixture | 3 |
| Rule | Renderable | `SceneNodeTest`, `HitTestTest` | Bounds expand on both axes, so they are slightly conservative for a butt-capped rule. No encoder | 3 |
| Line | Renderable | `PathTest`, golden `svg/line-chart.svg` | `PathNode` draws a polyline. No encoder, no interpolation methods, no `defined` handling | 3 |
| Area | Renderable | golden `svg/area-chart.svg` | `PathNode` draws the outline. No encoder, no stacking, no interpolation | 3 |
| Symbol | Renderable | `SceneNodeTest` | 9 shapes following d3-shape proportions; `wedge`, `arrow`, custom SVG paths absent. No encoder | 3 |
| Text | Renderable | `TextTest`, `AndroidTextEngineTest` | Metrics are platform text metrics, not browser metrics. No encoder | 3 |
| Image | Renderable | `SceneExportTest` | Needs an `AndroidImageResolver`; unresolved images report `VEGA_EXPORT_IMAGE_UNRESOLVED`. No encoder | 3 |
| Arc | Planned | — | — | 3 |
| Path (from SVG path strings) | Planned | — | Path-string parsing not implemented | 3 |
| Trail | Not planned (first release) | — | — | — |
| Shape (geo) | Not planned | — | Needs projections, an explicit non-goal | — |

## Scales

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Linear | **Supported** | `ScalesTest`, `TicksTest`, `BarFixtureDifferentialTest` | Domain, range, ticks and `nice` match d3 and upstream exactly. `zero` defaults to true for data-driven domains, as upstream does | 3 |
| Band | **Supported** | `ScalesTest`, `BarFixtureDifferentialTest` | Step, bandwidth, padding, align and round match d3-scaleBand exactly | 3 |
| Point | **Supported** | `ScalesTest` | Implemented as a band scale with paddingInner 1, as d3 does | 3 |
| Ordinal | **Supported** | `ScalesTest` | Explicit range arrays only; colour schemes report `VEGA_SCALE_UNSUPPORTED_TYPE` | 3 |
| Log, pow, sqrt, symlog | Planned | — | Reports `VEGA_SCALE_UNSUPPORTED_TYPE` | 3 |
| Time, UTC | Planned | — | — | 3 |
| Sequential colour | Planned | — | Needs colour interpolation | 3 |
| Quantile, quantize, threshold, bin-ordinal | Not planned (first release) | — | Reports `VEGA_SCALE_UNSUPPORTED_TYPE` | — |

## Data transforms

Upstream Vega has 40 transforms. None are implemented; the rows below cover the surrounding
machinery only.

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Tuple identity, change sets | Supported | `DataflowTest` | Data model only; nothing propagates pulses yet | 0 |
| Deterministic operator scheduling | Supported | `DataflowTest` | Topological ordering only; no evaluation engine | 0 |
| Pulse propagation / incremental evaluation | Not implemented | — | The `DataflowOperator` contract exists; there is no engine behind it | 4 |
| Filter, formula, collect, aggregate | Planned | — | — | 4 |
| Join aggregate, extent, bin, stack | Planned | — | — | 4 |
| Project, identifier, fold, flatten | Planned | — | — | 4 |
| Geo transforms, force layout | Not planned | — | Reports `VEGA_TRANSFORM_NOT_IMPLEMENTED` | — |

## Expressions

Upstream Vega exposes 119 expression functions. None are implemented.

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Expression compiler contract and cache | Supported | `ExpressionCompilerTest` | Interface and LRU cache only | 0 |
| Lexer, parser, evaluator | Not implemented | — | Every expression reports `VEGA_EXPRESSION_UNSUPPORTED_FUNCTION` | 4 |
| Signals | Not implemented | — | `{"signal": ...}` appears throughout real specs, so this blocks faithful parsing | 4 |
| `eval` / JavaScript execution | Not planned | — | Explicitly forbidden (PROJECT_BRIEF.md 6.1) | — |

## Layout

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Width, height, viewport | Supported | `SceneSnapshotTest` | Padding and `autosize` come with spec parsing | 1 |
| Group transforms | Supported | `SceneNodeTest` | — | 1 |
| Clip rectangles and clip paths | Supported | `AndroidCanvasSceneRendererTest`, `SvgRendererTest` | — | 1 |
| Axes | **Supported** | `SpecCompilerTest`, `BarFixtureDifferentialTest` | Ticks, labels, gridlines and domain line for all four orientations. Tick positions, label anchors, alignment and text match upstream exactly. No titles, no label overlap removal, no `encode` overrides, no format strings | 5 |
| Legends, titles | Not implemented | — | Reported as diagnostics; the sample scenes hand-author them as ordinary nodes | 5 |
| Label collision handling | Planned | — | — | 5 |
| Padding and `autosize` | Partial | `SpecCompilerTest`, `BarFixtureDifferentialTest` | `pad` and `none` implemented. `fit`, `fit-x`, `fit-y` need a second layout pass and fall back to `pad` with a diagnostic. Surface size matches upstream to within half a pixel per axis — see the note below | 3 |

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
| Fixtures passing | 1 of a target 100 | `BarFixtureDifferentialTest` | `bar.vg.json`: all 48 marks and all scale outputs match exactly | 3 |
| Axis group bounds | Known difference | `BarFixtureDifferentialTest` | Vega derives an axis group's bounds from the axis extent rather than by unioning its items, so its frame bounds exclude the half-pixel crisp offset and the domain line's stroke. Our surface is up to 1 unit larger per axis; every mark coordinate still agrees exactly | 5 |
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
| WebView, JavaScript, NDK, GPU backends | Not planned | — | — | — |
