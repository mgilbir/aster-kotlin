# Supported features

Statuses: **Supported** · **Partial** · **Planned** · **Not planned** · **Blocked**

"Tests" names the test class or golden that covers the row. An unsupported construct always produces a
structured diagnostic; nothing is silently ignored (PROJECT_BRIEF.md 3.3, 14).

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
| Group | Supported | `SceneNodeTest`, `AndroidCanvasSceneRendererTest` | — | 1 |
| Rect | Supported | `SceneNodeTest`, `SvgRendererTest` | Corner radius clamped to half the shortest side | 1 |
| Rule | Supported | `SceneNodeTest`, `HitTestTest` | Bounds expand on both axes, so they are slightly conservative for a butt-capped rule | 1 |
| Line | Supported (as `PathNode`) | `PathTest`, golden `svg/line-chart.svg` | Interpolation methods other than linear are not implemented | 1 |
| Area | Supported (as `PathNode`) | golden `svg/area-chart.svg` | As above | 1 |
| Symbol | Supported | `SceneNodeTest` | Shapes follow d3-shape; `wedge`, `arrow`, `triangle`, custom SVG paths not built in | 1 |
| Text | Supported | `TextTest`, `AndroidTextEngineTest` | Metrics are platform text metrics, not browser metrics | 1 |
| Image | Partial | `SceneExportTest` | Needs an `AndroidImageResolver`; unresolved images report `VEGA_EXPORT_IMAGE_UNRESOLVED` | 1 |
| Arc | Planned | — | — | 3 |
| Path (from SVG path strings) | Planned | — | Path-string parsing not implemented | 3 |
| Shape, trail | Not planned (first release) | — | — | — |

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

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Tuple identity, change sets | Supported | `DataflowTest` | — | 0 |
| Deterministic operator scheduling | Supported | `DataflowTest` | Cycles raise `CyclicDataflowException` | 0 |
| Filter, formula, collect, aggregate | Planned | — | — | 4 |
| Join aggregate, extent, bin, stack | Planned | — | — | 4 |
| Project, identifier, fold, flatten | Planned | — | — | 4 |
| Geo transforms, force layout | Not planned | — | Reports `VEGA_TRANSFORM_NOT_IMPLEMENTED` | — |

## Expressions

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Expression compiler contract and cache | Supported | `ExpressionCompilerTest` | — | 0 |
| Lexer, parser, evaluator | Planned | — | Every expression currently reports `VEGA_EXPRESSION_UNSUPPORTED_FUNCTION` | 4 |
| `eval` / JavaScript execution | Not planned | — | Explicitly forbidden (PROJECT_BRIEF.md 6.1) | — |

## Layout

| Feature | Status | Tests | Known differences | Target milestone |
| --- | --- | --- | --- | --- |
| Width, height, viewport | Supported | `SceneSnapshotTest` | Padding and `autosize` come with spec parsing | 1 |
| Group transforms | Supported | `SceneNodeTest` | — | 1 |
| Clip rectangles and clip paths | Supported | `AndroidCanvasSceneRendererTest`, `SvgRendererTest` | — | 1 |
| Axes, legends, titles | Partial | golden scenes | Hand-authored in `SampleScenes`; not generated from a spec | 5 |
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
