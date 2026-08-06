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
| Compiled Vega JSON | Planned | — | `setSpec` reports `VEGA_TRANSFORM_NOT_IMPLEMENTED` | 3 |
| Vega-Lite compilation | Not planned (first release) | — | Compile upstream via `oracle-js/src/compile-vega-lite.js` | — |
| Generic JSON value model | Supported | `VegaValueTest`, `JsonBridgeTest` | Numbers are always `Double` | 0 |
| Dotted / bracketed field paths | Supported | `VegaValueTest` | Malformed paths resolve to null rather than throwing | 0 |

## Marks

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Group | Renderable | `SceneNodeTest`, `AndroidCanvasSceneRendererTest` | Transforms, clipping and paint work. No group-mark faceting or layout | 3 |
| Rect | Renderable | `SceneNodeTest`, `SvgRendererTest` | Corner radius clamped to half the shortest side. No encoder | 3 |
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
| Linear | Planned | — | — | 3 |
| Band | Planned | — | — | 3 |
| Point | Planned | — | — | 3 |
| Ordinal | Planned | — | — | 3 |
| Log, pow, sqrt | Planned | — | — | 3 |
| Time, UTC | Planned | — | — | 3 |
| Sequential colour | Planned | — | — | 3 |
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
| Axes, legends, titles | Not implemented | — | The sample scenes hand-author them as ordinary nodes; the engine generates nothing. Needs tick placement, label alignment and overlap removal | 5 |
| Label collision handling | Planned | — | — | 5 |
| Padding and `autosize` | Planned | — | — | 3 |

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
