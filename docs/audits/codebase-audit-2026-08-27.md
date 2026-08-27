# Codebase audit — aster-kotlin — 2026-08-27

Exhaustive adversarial audit of the full repository (~117k lines of Kotlin across 14 Gradle
modules, plus the Swift package, oracle-js, scripts, CI and documentation). Nine areas were each
read in full by a dedicated reviewer; every Critical and a sample of High findings were then
independently re-traced against the source (12 of 12 re-traces held, including one where the
initial counter-evidence — 23 grep hits for `defined` in `normalize.js` — turned out to be all
`undefined`). Verdicts: **CONFIRMED** = the full code path was traced and the decisive lines are
cited; **PLAUSIBLE** = the mechanism is certain but the trigger, timing or upstream behaviour was
not fully established.

Counts: **11 Critical · 33 High · 61 Medium · 66 Low** (a few micro-findings are folded into
cluster entries).

What is sound, said once: the geometry core (cubic bounds from derivative roots, d3-exact curves,
arc sectors, Liang–Barsky), number/time formatting (`NumberFormat`, `Decimals`, `TimeFormat` —
replayed against upstream vectors), the d3-geo and statistics ports, the fixture discipline
(directory-scan enumeration, per-reference `vegaVersion` asserts, `INDEX.md` drift-guarded), the
scripts' error messages and `set -euo pipefail` hygiene, the release build→checksum→commit→tag
ordering, and the spec parser's consumed-key diagnostic machinery are all unusually careful,
evidence-cited work. The findings below are the residue, and they cluster in four places: promises
without mechanisms ("nothing throws"), JS semantics that leaked Kotlin, one semantic implemented N
times across hosts, and gates that skip instead of failing.

---

## 1. Summary table

| ID | Severity | Area | Issue | Location | Verdict |
|---|---|---|---|---|---|
| C1 | Critical | expression | Missing datum field coerces to 0 (JS: undefined → NaN) — filters/arithmetic give the opposite answer from upstream on ordinary data | vega-expression JsSemantics.kt:46, Evaluator.kt:81 | CONFIRMED |
| C2 | Critical | expression | `collectSignals` deletes a genuine signal dependency when the name also appears as a member property — expression never re-evaluates, stale chart, zero diagnostics | vega-expression VegaExpressionCompiler.kt:64-73 | CONFIRMED |
| C3 | Critical | dataflow | `window` keys results by row value; structurally identical rows collapse to the last row's result (`sum`, `row_number`, `lag` all wrong on duplicates) | vega-dataflow transform/Window.kt:48-56 | CONFIRMED |
| C4 | Critical | runtime/scale | `buildTime` ignores `domainRaw` on time/utc scales — brush-zoom on the committed overview-plus-detail fixture silently does nothing | vega-runtime compile/ScaleResolver.kt:684-740 | CONFIRMED |
| C5 | Critical | controller | A stale `setSpecAsync` result clobbers a newer `setSpec`: `loadedSpecJson`/`signals.reset()`/`publish` run after the lock with no generation check | vega-runtime VegaChartController.kt:521-543 | CONFIRMED |
| C6 | Critical | compose-mp | `pointerInput` keyed on `viewportOffset`/`viewportScale` — the README's documented controller wiring cancels and restarts gesture detection on the first pan increment; continuous pan/pinch dies | vega-compose-multiplatform VegaChart.kt:435,475,506 | CONFIRMED |
| C7 | Critical | vega-lite | `dateTimeTimestamp` throws `IllegalArgumentException` on JS-legal rollover date parts (`month:13`, Feb 30, hour 24); zero `try` in the module, so the pasted-text API crashes the host | vega-lite vegalite/Transforms.kt:995-1020 | CONFIRMED |
| C8 | Critical | expression | One-arg `datetime(x)` treats x as a year (upstream: `new Date(x)` = timestamp); a real epoch-millis argument saturates `toInt()` and throws uncaught | vega-expression Functions.kt:1525-1548 | CONFIRMED |
| C9 | Critical | runtime/compile | Gradient legend with non-numeric explicit `values` throws `ClassCastException` out of public `compileJson` (`entry.value as VegaValue.Num`, no catch-all above) | vega-runtime compile/LegendBuilder.kt:989 | CONFIRMED |
| C10 | Critical | release | Since commit 3b37546c the release verify job never arms the Vega-Lite scene gate — all 1126 `VegaLiteFixtureDifferentialTest` cases skip via assumption; the publish job's comment claims verify "has already run that comparison in full" | .github/workflows/release.yml:85-102 vs ci.yml:75-83 | CONFIRMED |
| C11 | Critical | oracle | The differential model erases subpath structure (MoveTo ≡ LineTo; no `defined` channel in normalize.js) — a regression drawing a line straight through a `defined:false` gap passes every oracle assertion | oracle-js/src/normalize.js + vega-runtime differential/Differential.kt:459-460 | CONFIRMED |
| H1 | High | runtime/compile | Finite huge `tickCount` (e.g. 1e9) OOMs or hangs: `List(n)` uncapped; `countWithMinStep` walks down one at a time | AxisBuilder.kt:1283, scale/Ticks.kt:114, GuideFormat.kt:73 | CONFIRMED |
| H2 | High | runtime/scale | Niced time scale re-derives from the original domain — `domainMin`/`domainMax`/`padding` computed then discarded (Vega-Lite defaults temporal scales to `nice:true`) | compile/ScaleResolver.kt:696-731 | CONFIRMED |
| H3 | High | runtime/compile | Projections absent from every encode-time expression scope — `geoScale('p')` in a mark channel falsely reports "projection not defined" | compile/SignalResolver.kt:346-359, 697-709 | CONFIRMED |
| H4 | High | runtime/compile | No recursion depth limit — deeply nested group marks StackOverflow instead of a diagnostic | compile/ScopeCompiler.kt:189/605/1372 | CONFIRMED |
| H5 | High | controller | Sync compile paths (`setSpec`, `hostData`/`containerSize` setters, interaction recompiles) never take `compileLock`, contradicting the class's own serialization claim | VegaChartController.kt:350,464,214,764 | CONFIRMED |
| H6 | High | controller | With a loader configured, every recompile re-fetches every `url` dataset synchronously — a click handler or a 500 ms timer polls the network on the dispatching (UI) thread | compile/DataResolver.kt:229-246 + load/HttpDataLoader.kt:77 | CONFIRMED |
| H7 | High | controller+compile | Every data mark reports a tooltip: encoder falls back to the whole datum when no tooltip channel exists; README promises exactly the opposite ("empty object … is not a tooltip") | compile/MarkEncoder.kt:1470 + VegaChartController.kt:928-945 | CONFIRMED |
| H8 | High | svg | Group opacity emitted on the `<g>` container composites the whole subtree — children washed out; opacity-0 group's children vanish (canvas renderers document and do the opposite) | vega-svg SvgRenderer.kt:197 | CONFIRMED |
| H9 | High | vega-lite | No exception boundary and no depth cap anywhere — a pathological pasted document (deep `layer` nesting, 10k transforms) crashes the host with StackOverflowError instead of diagnostics+null | VegaLiteInput.kt:68-79, VegaLiteCompiler.kt:101 | CONFIRMED |
| H10 | High | vega-lite | Interval selection initialised with written dates emits the raw `DateTime` objects into the store; upstream emits epoch ms — initial filtering wrong until first drag | vegalite/Selection.kt:461-492 | CONFIRMED |
| H11 | High | vega-lite | Unknown encoding channels kept silently (upstream warns and drops) — a typo like `colour` enters aggregate `groupby`, descriptions and tooltips, producing a different chart | vegalite/Parse.kt:232-274 | CONFIRMED |
| H12 | High | swift | Pan deltas divided by fit scale before dispatch; the controller contract is raw pixels (Compose MP documents this explicitly) — chart doesn't track the finger at any fit ≠ 1 | AsterVegaRender VegaChartView.swift:345-349 | CONFIRMED |
| H13 | High | swift | `load("")` clears state synchronously but neither cancels nor queues — an in-flight compile resurrects the cleared chart; `loading` stays true | ChartSession.swift:385-397 | CONFIRMED |
| H14 | High | swift | `set(signal:to:)` is the one mutating entry point not wrapped in `serialised {}` — races the off-actor compile the queue exists to prevent | ChartSession.swift:602-607 | CONFIRMED |
| H15 | High | model | `parseFieldPath("list[1].b")` inserts an empty segment → resolves to Null — any `coordinates[0].lat`-style field silently reads nothing | vega-model VegaValue.kt:205-227 | CONFIRMED |
| H16 | High | expression | `erfInverse` early-returns +Infinity, losing the sign: `quantileNormal(0)` = +Inf (should be −Inf), `quantileLogNormal(0)` = +Inf (should be 0) | vega-expression Statistics.kt:85-96 | CONFIRMED |
| H17 | High | expression | Three unstructured escape hatches crash the compile: `data()` with no args (NoSuchElementException), `regexp('(')` (raw ktecma262 syntax error), unbounded parser recursion (StackOverflowError) — all bypass the typed-exception diagnostic net | Evaluator.kt:205, VegaValue.kt:54, Parser.kt | CONFIRMED |
| H18 | High | expression | `span([])`/`span(null)` return NaN; upstream returns 0 — `span(domain('x'))` on an empty domain poisons layout signals | Functions.kt:735-742 | CONFIRMED |
| H19 | High | expression | `utcOffset` registered twice; surviving copy returns a number where upstream (and its own twin `timeOffset`) return a date | Functions.kt:600-614 | CONFIRMED |
| H20 | High | dataflow | `stack` `offset:"center"` splits positive/negative cursors; upstream uses one cursor over `abs` — negatives mis-stack | transform/Stack.kt:72-86 | CONFIRMED |
| H21 | High | dataflow | Aggregate treats `""` as a valid 0 (upstream: missing) — dirty CSV columns shift mean/min/valid/missing | transform/Aggregate.kt:296,319 | CONFIRMED |
| H22 | High | dataflow | `joinaggregate` with `ci0`/`ci1` writes null (no bootstrap closure passed), no diagnostic | transform/Aggregate.kt:207,286 | CONFIRMED |
| H23 | High | dataflow | `pie` ignores its `sort` parameter — angles assigned in data order, silently | transform/Pie.kt | CONFIRMED |
| H24 | High | dataflow | `bin` ignores its `steps` parameter (array of allowable steps), silently | transform/Bin.kt:204-265 | CONFIRMED |
| H25 | High | android view | Pinch-zoom anchor is raw view coordinates while every other pointer event is placement-relative — zoom point drifts on any centred/padded chart | vega-android-canvas VegaChartView.kt:296 | CONFIRMED |
| H26 | High | android a11y | TalkBack activation dispatches a tap in scene coordinates into a pipeline that inverts view coordinates — activates the wrong mark whenever scale ≠ 1 or panned | VegaAccessibilityHelper.kt:145 | CONFIRMED |
| H27 | High | android view | Draw viewport's right/bottom edges ignore centring — an opaque scene background (VL default white) overpaints the right/bottom slack asymmetrically; zoomed content escapes there | VegaChartView.kt:529-533 | CONFIRMED |
| H28 | High | vega-compose | The Compose surface cannot obtain a measurement-compatible text engine (defaults to `MetricTextEngine`; the view's engine is unreachable); the demo measures at fontScale 1 and draws at the device's | vega-compose VegaChart.kt:167-174 + demo/DemoActivity.kt:87,114 | CONFIRMED |
| H29 | High | vega-compose | Inline lambda `imageResolver`/`fontResolver` rebuild the renderer every recomposition (identity-compared setters) — once-per-URL contract voided, caches cleared per frame | vega-compose VegaChart.kt:96-104 + VegaChartView.kt:92-98 | CONFIRMED |
| H30 | High | compose-mp | `blendMode` silently ignored by the MP renderer (View maps all 15 modes; no diagnostic, no parity-table row) | vega-compose-multiplatform SceneWalk.kt (absent) | CONFIRMED |
| H31 | High | gates | A single full `check.sh` run can report "Green, and every gate ran" while the VL scene gate never executed armed (references generated after the tests that need them) | scripts/check.sh:185-197,305-314 | CONFIRMED |
| H32 | High | release | Missing publish secrets → publish skips with `exit 0`, release job still tags, pushes and creates a release page claiming Maven Central coordinates that don't exist; version can never be re-released | release.yml:157-162, 269-375 | CONFIRMED |
| H33 | High | gates | Workflow comments claim missing upstream vectors "fail rather than skip" — both replay tests `assumeTrue(file.isFile)` (skip); the recording steps are the only protection | ci.yml:70-73, release.yml:130-135 vs UpstreamTransformVectorsTest.kt:90 | CONFIRMED |
| M1 | Medium | runtime/compile | Missing-scale/unpositionable errors emitted per datum — 10k-row mark yields ~20k ERROR diagnostics; `reportOnce` exists unused in the same file | MarkEncoder.kt:1754-1962 | CONFIRMED |
| M2 | Medium | runtime/compile | Sort comparators treat unreadable keys as 0 → non-transitive → JVM TimSort can throw "Comparison method violates its general contract!" (≥32 items, field some rows lack) | ScopeCompiler.kt:552-572, MarkEncoder.kt:1646-1664 | PLAUSIBLE |
| M3 | Medium | runtime/compile | Non-position scaled channels read `field ?: value`, dropping a `signal` input (`scaledInput` order is signal→field→value 80 lines away) | MarkEncoder.kt:1770-1774 vs 1849-1852 | CONFIRMED |
| M4 | Medium | runtime/compile | Three font-weight parsers disagree (`"bolder"` → 700/800/700; numeric-in-string accepted by two of three) | MarkEncoder.kt:1481, TitleBuilder.kt:362, GuideStyle.kt:145 | CONFIRMED |
| M5 | Medium | runtime/compile | `facet.aggregate.cross` materializes the full n·m cross product unbounded (no MAX_BINS-style cap) | ScopeCompiler.kt:1108-1130 | CONFIRMED |
| M6 | Medium | diagnostics | Codes are a documented public contract but semantically misassigned: load failures report `VEGA_PARSE_UNKNOWN_PROPERTY`, missing image width reports `VEGA_EXPORT_IMAGE_UNRESOLVED`, "scale not built" reports `SCALE_UNSUPPORTED_TYPE`; ~6 unrelated runtime conditions share `PARSE_UNKNOWN_PROPERTY` | DataResolver.kt:229-247, MarkEncoder.kt:1334-1343 | CONFIRMED |
| M7 | Medium | runtime/compile | `autosize.contains:"padding"` with padding wider than the size yields an unclamped negative plotting area, no diagnostic | SpecCompiler.kt:351-360 | PLAUSIBLE |
| M8 | Medium | controller | `datumOf` returns `metadata.tooltip`, not the datum — `MarkClicked.datum` is the tooltip value whenever a tooltip channel exists (README example passes it to `handleClick`) | VegaChartController.kt:1113 | CONFIRMED |
| M9 | Medium | loader/security | `blockPrivateNetworks` misses IPv6-mapped IPv4 (`[::ffff:127.0.0.1]`, `[::ffff:169.254.169.254]`), `fec0::/10`, NAT64 | HttpDataLoader.kt:179-198 | CONFIRMED |
| M10 | Medium | loader | Response cap enforced after the whole body is in memory, counting UTF-16 chars not bytes — class doc claims a hostile server "cannot stream unbounded data into memory" | HttpDataLoader.kt:63-65,126-131 | CONFIRMED |
| M11 | Medium | controller | NaN gesture input poisons the viewport permanently (`Pan.delta` unvalidated; `Zoom` validates factor but not anchor) — all hit tests miss until `resetViewport()` | VegaChartController.kt:984-1024 | CONFIRMED |
| M12 | Medium | controller | Non-atomic `_state.value = _state.value.copy(...)` and `nextRevision++` across threads — lost updates and duplicate revisions defeat revision-based invalidation | VegaChartController.kt:592,613,1101-1111 | CONFIRMED |
| M13 | Medium | scales | `TransformedScale.invert` uses only first/last domain stops — silently wrong number on multi-stop pow/log scales (brush `invert('s', x())`) | scale/Scales.kt:485-527 | CONFIRMED |
| M14 | Medium | controller | `stop()` is undone by the next publish — timers silently restart against a detached view when the host keeps feeding `setData` | VegaChartController.kt:850-915 | CONFIRMED |
| M15 | Medium | hit testing | Children of an opacity-0 group are drawn by the canvas renderers but pruned from the hit index — visible marks that cannot be tapped | vega-scene HitTest.kt:115 | CONFIRMED |
| M16 | Medium | hit testing | Broad phase gates on `bounds.expand(boundsTolerance)`, so `strokeTolerance` beyond it is unreachable — Mouse's 2 px is effectively 0 on axis-aligned rules, Touch's 8 px ≈ 6 | HitTest.kt:74 | CONFIRMED |
| M17 | Medium | paint order | `paintOrder()` (item `zindex`) applied only by the SVG renderer — hit index and all on-screen walks iterate raw children; tap can go to the mark drawn underneath in the export | HitTest.kt:131, Scene.kt:134, SvgRenderer.kt:223 | CONFIRMED |
| M18 | Medium | geometry | Hit testing uses even-odd containment; every renderer (and upstream) paints nonzero winding — taps on the visibly filled centre of a self-intersecting symbol miss | Path.kt:90, HitTest.kt:208-221 | CONFIRMED |
| M19 | Medium | svg | Multiline text with `baseline: middle/bottom` exported (n−1)·lineHeight(/2) too low — per-line `dominant-baseline` instead of the block correction other renderers apply | SvgRenderer.kt:397,420-428 | CONFIRMED |
| M20 | Medium | snapshots | Canonical snapshot omits dashOffset, miterLimit, per-corner radii, blendMode, custom symbol paths, image align/smooth, href, zindex — ADR-0008 level 2 blind to regressions in all of them; also snapshots `effectiveCornerRadius` (abs-clamped) while drawing uses `Corners.of` (unclamped) | SceneSnapshot.kt:63-153 vs SceneNode.kt:438 | CONFIRMED |
| M21 | Medium | hit testing | A filled path with `fillOpacity: 0` loses its interior hit target (rect branch deliberately keeps it, with an upstream-citing comment; path branch checks `isVisible`) | HitTest.kt:215 vs :246 | CONFIRMED |
| M22 | Medium | svg/security | Spec-supplied `href` emitted verbatim (XML-escaped only) into `<a xlink:href>` — `javascript:` URLs survive into exports opened in browsers | SvgRenderer.kt:146 | CONFIRMED |
| M23 | Medium | vega-lite | A spec with no `data` emits ERROR + a non-null broken spec referencing dataset `""` (upstream compiles it) — README's stop-on-null pattern passes it to the runtime | VegaLiteCompiler.kt:2642-2649 | CONFIRMED |
| M24 | Medium | vega-lite | Comma-merged event selector in `select.on` keeps only the first stream (`"click, touchend"` drops touch), no diagnostic | vegalite/Selection.kt:339-353 | CONFIRMED |
| M25 | Medium | vega-lite | Fallback diagnostics lie: unsupported-transform message omits implemented `bin`/`stack`/`timeUnit`/`impute`; malformed-predicate message says params "are not implemented" (they are) | vegalite/Transforms.kt:499-509,767-775 | CONFIRMED |
| M26 | Medium | swift a11y | VoiceOver activation point omits the viewport offset — activates the wrong mark once panned/zoomed (overlay frames are correct; only the synthesized tap is short) | VegaChartView.swift:441-452 | CONFIRMED |
| M27 | Medium | swift text | Letter-spacing measured unscaled but drawn scaled by `textScale` — spaced labels break out of their boxes at any Dynamic Type size ≠ 1 | CoreTextTextEngine.swift:81-83 vs CoreTextDrawing.swift:86-89 | CONFIRMED |
| M28 | Medium | goldens | Scene-walk fixture list hard-coded twice (Kotlin + Swift), neither scans the directory — a golden can silently stop being asserted on one engine (the exact failure mode the conformance README warns about) | SceneWalkParityTests.swift:30-40 + SceneWalkGoldenTest.kt:56-67 | CONFIRMED |
| M29 | Medium | swift demo | Paste screen claims "no network loader" and README says "installs DenyLoader" — every session actually uses a loader that falls back to fetching `https://vega.github.io/vega/…` | AsterVegaDemo PasteSpecView.swift:75, SpecLibrary.swift:33 | CONFIRMED |
| M30 | Medium | expression | `sort()` compares timestamps and mixed types as strings; upstream uses d3 `ascending` — date arrays spanning a digit-count boundary sort wrong | Functions.kt:566-570,1675-1683 | CONFIRMED |
| M31 | Medium | expression | `clampRange` returns a descending range untouched; upstream normalizes lo/hi first (reversed y-domains feeding pan/zoom) | Functions.kt:807-830 | CONFIRMED |
| M32 | Medium | expression | `inrange`'s left/right exclusivity flags silently ignored — every comparison inclusive | Functions.kt:743-750 | CONFIRMED |
| M33 | Medium | expression | Negative `timeSequence` step loops downward emitting up to 100k timestamps; upstream returns `[]` | Functions.kt:1434-1453 | CONFIRMED |
| M34 | Medium | expression | `toInt32`/`toUint32` saturate at ±2⁶³ instead of wrapping mod 2³² (`1e20 | 0` → −1, JS: 1661992960) | JsSemantics.kt:215-226 | CONFIRMED |
| M35 | Medium | expression | Fractional/non-canonical indices return elements (`[10,20,30][1.5]` → 20; JS: undefined); Obj lookup uses model asString not JS ToString | Evaluator.kt:84-103 | CONFIRMED |
| M36 | Medium | expression | `isNaN`/`atob`/`btoa`/`encodeURIComponent` unimplemented while `knownUnsupported` is empty — the "empty map is a claim, and a test checks it" guarantee audits only `functionContext`, not upstream's codegen table | Functions.kt:106 + ExpressionReferenceTest:56 | CONFIRMED |
| M37 | Medium | expression | `Timestamp` behaves as a number in `+`/`String()`/truthiness; JS `Date` prefers string in `+` and is always truthy — a tooltip stringifying `datetime()` shows millis | JsSemantics.kt:118-126, Functions.kt:832-842 | CONFIRMED |
| M38 | Medium | expression | Array-vs-string `==`/`<` compares numerically; JS compares via ToPrimitive string (`[1,2] == "1,2"` true in JS, false here) | JsSemantics.kt:178-193 | CONFIRMED |
| M39 | Medium | model/parser | `parseSignal` and `parseData` silently drop unknown properties — data **triggers**, signal `push`/`react`/`description` vanish; this repo's own VL compiler emits `"push":"outer"` and nothing reads it; contradicts the parser's "nothing silently dropped" class doc | SpecParser.kt:1206-1231,1623-1712 | CONFIRMED |
| M40 | Medium | expression | `CachingExpressionCompiler` is an unsynchronized LRU mutated on every hit, shared between the controller's handler path and background compiles | Expression.kt:347-378 + VegaChartController.kt:492 | PLAUSIBLE |
| M41 | Medium | model | `jsonNumber` uses platform `Double.toString` for fractional/huge values under a comment claiming JSON.stringify rules — output differs per KMP target (`1.5E-6` vs `0.0000015`); `Decimals.jsString` exists two files away | JsonBridge.kt:107-113 | CONFIRMED |
| M42 | Medium | dataflow | Numeric aggregate ops filter ±Infinity and NaN-coercing strings; upstream propagates (`sum` of `[1,"abc"]`: upstream NaN, here 1) — self-inconsistent with the CI block that deliberately keeps Infinity | transform/Aggregate.kt:319 | CONFIRMED |
| M43 | Medium | dataflow | Sum over a group with no numeric values → 0; upstream → undefined (comment claiming upstream reports 0 was probed false) — passes null-filters upstream fails | transform/Aggregate.kt:160-162,322 | CONFIRMED |
| M44 | Medium | dataflow | `groupTuples` keys on raw values; upstream group keys coerce to string — `1001` and `"1001"` form two groups here, one upstream; `asComparableKey()` exists for exactly this and is unused here | transform/Aggregate.kt:480-524 | CONFIRMED |
| M45 | Medium | dataflow | Force link `distance`/`strength` expressions evaluate against a null datum — `datum.weight` is NaN for every link, silently | transform/Force.kt:287-297 | CONFIRMED |
| M46 | Medium | dataflow | `Orient2d` shares mutable scratch buffers on a JVM-global singleton — two concurrent voronoi compiles can corrupt the exact predicate | voronoi/Orient2d.kt:44-53 | PLAUSIBLE |
| M47 | Medium | dataflow | `countpattern` compiles spec-authored JS regexes with Kotlin `Regex` instead of the ktecma262 engine the codebase adopted for exactly this | transform/Reshape2.kt:149-171 | CONFIRMED |
| M48 | Medium | dataflow | `Dataflow.kt` is a public, documented, tested incrementality API with zero consumers — `TupleId` promises identity "preserved across incremental updates" that nothing implements | Dataflow.kt (whole file) | CONFIRMED |
| M49 | Medium | android render | Pre-Q `MULTIPLY` maps to PorterDuff modulate, not CSS multiply — marks over transparent regions disappear on API 26-28; claimed supported | AndroidCanvasSceneRenderer.kt:693 | CONFIRMED |
| M50 | Medium | hosts | `GesturePhase.ENDED` never dispatched for pans (both hosts) or MP zooms — `ChartEvent.ViewportChanged` never fires; the MP callbacks' `ended` parameter is dead despite KDoc | VegaChartView.kt:692-714 + MP VegaChart.kt:491-499 | CONFIRMED |
| M51 | Medium | compose-mp | `onPlaced` fires on every draw, from the draw phase, no dedup (View: on change, off draw) — same seam, different cadence, undocumented | MP VegaChart.kt:203 | CONFIRMED |
| M52 | Medium | android view | Built-in tooltip drawn at a placement-relative anchor in raw view coordinates — bubble sits off by the placement offset on padded/centred charts | VegaChartView.kt:634-648 | CONFIRMED |
| M53 | Medium | android view | TAB/arrows/ESC/HOME/END consumed unconditionally while `dispatch(Key)` is a no-op — keyboard/d-pad focus trap | VegaChartView.kt:744-772 + VegaChartController.kt:663 | CONFIRMED |
| M54 | Medium | android render | Per-node-per-frame allocations on the draw path (DashPathEffect, gradient shaders + array copies, placement, tooltip measure) contradict the "allocates nothing per mark" header | AndroidCanvasSceneRenderer.kt:556-626, VegaChartView.kt:518-556 | CONFIRMED |
| M55 | Medium | compose-mp | Draw path re-shapes text per frame (measurer cache of 8) and rebuilds every `Path` per frame — 10k-symbol scene allocates 10k paths/frame during pan | DrawScopeTarget.kt:171-247, ComposeTextEngine.kt:206 | CONFIRMED |
| M56 | Medium | compose-mp | Gradient-filled text draws solid black on MP (View draws the gradient); stroke-only text: View draws nothing, MP fills with stroke colour | DrawScopeTarget.kt:193,220 | CONFIRMED |
| M57 | Medium | export | `SceneExporter()` default engine is fontScale 1/no resolver — exported text ≠ live view for any non-default host; PDF warnings can't see PDF-backend degradation; no dimension cap (30k×30k = OOM); export fits top-left while the view centres | SceneExport.kt:41-117 | CONFIRMED (PDF part PLAUSIBLE) |
| M58 | Medium | compose-mp | `onHover` fires for touch moves despite "Mouse and stylus only" — hover/tooltip state churns during a touch pan | MP VegaChart.kt:506-538 | PLAUSIBLE |
| M59 | Medium | scripts | `test-core.sh` predates vega-lite/vega-loader and omits them (~800 tests) — README sells it as the JVM suite; a broken VL compiler passes it | scripts/test-core.sh:6-12 | CONFIRMED |
| M60 | Medium | gates | One `[api-snapshot-only]` marker anywhere on a branch exempts every snapshot change on the branch, including a real API break in another commit | scripts/changelog-gate.py:75-77 | CONFIRMED |
| M61 | Medium | docs | HANDOFF.md violates its own "delete when it stops being true": names branch `milestone-0-bootstrap`, claims 177 fixtures (193); STATUS.md still points into it | HANDOFF.md:1-13 | CONFIRMED |
| M62 | Medium | ADRs | ADR 0005 ("Vega-Lite compilation is out of scope") and ADR 0003 ("the public Composable does not draw on a DrawScope") both contradicted by shipped modules, never amended — against docs/adr/README.md's own rule | docs/adr/0005, 0003 | CONFIRMED |
| L1 | Low | runtime/compile | `tickCountFor` dead (superseded by `countWithMinStep`, will drift) | AxisBuilder.kt:829-843 | CONFIRMED |
| L2 | Low | runtime/compile | `EMPTY_COMPILED` dead; `Operator.Data` branch builds and discards a scope | SpecCompiler.kt:499-504,1067 | CONFIRMED |
| L3 | Low | runtime/compile | Orphaned `decimalsFor`/`roundTo` duplicate the live pair in LegendBuilder | GuideCaption.kt:332-343 | CONFIRMED |
| L4 | Low | runtime/compile | Stacked/misattached KDoc blocks (SpecCompiler:966, ScopeCompiler:141,517, AxisBuilder) | multiple | CONFIRMED |
| L5 | Low | runtime/compile | Public `encode()` handed a GROUP spec claims the group encoder "is not implemented" (it lives in `encodeGroup`) | MarkEncoder.kt:179-187 | CONFIRMED |
| L6 | Low | runtime/compile | `TITLE_MIN_EXTENT`/`TITLE_MAX_EXTENT` name the axis extent clamps, not title properties | AxisDefaults.kt:68-69 | CONFIRMED |
| L7 | Low | runtime/compile | `fontStyle:"oblique"` silently renders upright (strokeCap/Join warn on unknowns in the same file) | MarkEncoder.kt:781-784 | CONFIRMED |
| L8 | Low | controller | `SignalInput.resolve` conflates explicit `min:0`/`max:0` with absent (`takeIf { it != 0.0 }`); the max branch guard is dead code | SignalInput.kt:77-81 | CONFIRMED |
| L9 | Low | scales | `Ticks.nice` writes back a partially-niced domain on non-convergence; 8 passes vs d3's 10 | scale/Ticks.kt:136-181 | CONFIRMED |
| L10 | Low | scales | `logTicks` non-integer-base branch truncates a fractional count d3 passes through | scale/Ticks.kt:322-325 | PLAUSIBLE |
| L11 | Low | scales | `formatNumber` with decimals ≤ 0 saturates at Long.MAX for values ≥ 2⁶³ | scale/Scales.kt:1328-1339 | CONFIRMED |
| L12 | Low | scales | Time label cascade tests local fields (`hour != 0`) not interval floors — wrong tier in zones whose DST skips midnight; `MAX_TICKS` silently truncates explicit small intervals over long domains | scale/TimeTicks.kt:141-154 | CONFIRMED |
| L13 | Low | interaction | A permitted `window:` event stream registers a watch that can never match (`source` is always "view") with no diagnostic, unlike every sibling path | interaction/EventDispatcher.kt:275-333 | CONFIRMED |
| L14 | Low | loader | `http:///path` parses to empty host, passes an empty allowlist; ports unchecked; redirect message says 10, throws after 9 | load/Uri.kt:126-151, HttpDataLoader.kt:145 | CONFIRMED |
| L15 | Low | controller | Hover early-return freezes `tooltipAnchor` at entry; hover republish reuses a stale `scene.revision` (goes backwards) | VegaChartController.kt:928-945 | CONFIRMED |
| L16 | Low | loader | JVM transport materializes the whole response before the 64 MiB cap; charset header ignored (UTF-8 assumed) | vega-loader JvmHttp.kt:36 | CONFIRMED |
| L17 | Low | scene | `FontStack` splits on ',' before quote-stripping — a quoted family containing a comma is never offered whole | FontStack.kt:35 | CONFIRMED |
| L18 | Low | svg | `escapeXml` handles the five entities only — C0 control chars in data-derived text produce malformed XML (snapshot serializer escapes them) | SvgRenderer.kt:643 | CONFIRMED |
| L19 | Low | svg | Linear-gradient def keys include node bounds the emitted element never uses (duplicate identical defs); default `idPrefix="v"` cross-wires two charts inlined into one page, unwarned | SvgRenderer.kt:569 | CONFIRMED |
| L20 | Low | hit testing | Rotated clipped group's clip window is its AABB — points in clipped-away corners still hit descendants | HitTest.kt:127 | CONFIRMED |
| L21 | Low | scene | `GroupNode.paintRect` falls back to the clip rect when size is null — a size-less clipped filled group paints its whole clip; upstream paints nothing | SceneNode.kt:309 | PLAUSIBLE |
| L22 | Low | scene | `TextLayoutCache` is a bare LinkedHashMap with remove/re-insert on hit; nothing enforces the single-thread ownership it assumes | Text.kt:345 | PLAUSIBLE |
| L23 | Low | vega-lite | A layer member whose mark fails to parse is dropped and the rest compiles — non-null result, diagnostic-only guard (upstream throws) | VegaLiteCompiler.kt:1986-1993 | CONFIRMED |
| L24 | Low | vega-lite | Bin-merge signal renames applied by blanket `String.replace` over every string in the finished spec, including dataset values | VegaLiteCompiler.kt:1220-1238 | PLAUSIBLE |
| L25 | Low | vega-lite | `varName` uses Unicode `isLetterOrDigit`; upstream's `\W→_` is ASCII — emitted names differ for non-ASCII fields | vegalite/Fields.kt:352-355 | CONFIRMED |
| L26 | Low | vega-lite | Non-integral doubles fall back to Kotlin `Double.toString` (`1.0E-7` vs JS `1e-7`) in emitted expressions | Fields.kt:345-350, LayoutSize.kt:240 | PLAUSIBLE |
| L27 | Low | vega-lite | Non-object children of concat/layer arrays skipped without a diagnostic (upstream errors) | Concat.kt:81-83, VegaLiteCompiler.kt:1935 | CONFIRMED |
| L28 | Low | vega-lite | No cap on repeat grids (100×100 = 10k fully-compiled copies) | Repeat.kt:74-94 | PLAUSIBLE |
| L29 | Low | oracle | `record-number-strings.mjs` passes six expressions to one `push()` call — five of six intended vector families never recorded | oracle-js/src/record-number-strings.mjs:54 | CONFIRMED |
| L30 | Low | swift | `CoreTextTextEngine` header claims "no mutable state" while `unresolvedFontFamilies` is mutated during off-actor measurement, unsynchronized | CoreTextTextEngine.swift:24,124 | CONFIRMED |
| L31 | Low | oracle | `acorn: "^8.18.0"` is a range in the oracle that forbids ranges (lockfile saves it) | oracle-js/package.json:19 | CONFIRMED |
| L32 | Low | oracle | `eval-probe.js`/`transform-probe.js` never call `pinDeterminism()` — probes of now()/random-dependent behaviour are irreproducible | oracle-js/src | CONFIRMED |
| L33 | Low | swift | Non-cancellation compile errors derive `failure` from the *previous* document's diagnostics | ChartSession.swift:465-485 | CONFIRMED |
| L34 | Low | swift | `Affine.apply(rect:)` maps two diagonal corners — wrong AABB under rotation/shear (Compose shares the shape, so parity goldens agree) | SceneWalk.swift:493-500 | PLAUSIBLE |
| L35 | Low | gates | host-conformance "read by" check is a filename-substring match over test files — a comment counts as a reader | scripts/host-conformance.py:80-84 | CONFIRMED |
| L36 | Low | swift | Three process-lifetime unbounded caches (CGImage by URL and digest, CTFont per style, dataset text); only images clearable, wholesale | CoreGraphicsTarget.swift:356 etc. | CONFIRMED |
| L37 | Low | expression | Lexer silently mangles `\xNN`/`\u{…}`/line-continuation escapes (`'\x41'` → `"x41"`) | Lexer.kt:134-156 | CONFIRMED |
| L38 | Low | expression | Timestamp↔Num strict equality via boxed Double: `NaN === NaN` true, `-0 === 0` false — inverted from JS | JsSemantics.kt:144-147 | CONFIRMED |
| L39 | Low | expression | `clamp` corrects swapped bounds; upstream's max/min composition doesn't (`clamp(5,10,0)`: 10 upstream, 5 here) | Functions.kt:341-348 | CONFIRMED |
| L40 | Low | expression | `hypot` naive sqrt overflows where Math.hypot is scaled | Functions.kt:318-327 | CONFIRMED |
| L41 | Low | expression | `round` via `floor(x+0.5)` differs at the half-ulp boundary and loses −0 | Functions.kt:330-332 | CONFIRMED |
| L42 | Low | expression | `parseInt('0xFF')` → 0 (JS 255); `parseFloat('Infinity')` → NaN (JS Infinity) | Functions.kt:398-404,1631-1639 | CONFIRMED |
| L43 | Low | model | `SpecParser` is public but single-use: diagnostics/config leak across `parse()` calls, undocumented | SpecParser.kt:955-977 | CONFIRMED |
| L44 | Low | expression | Dead-code cluster: unused `NUMERIC` regex, `TimeFormat.pad`, no-op isoWeek remnant, `isDate` registered twice, unreachable `map["if"]`, `evaluateOrNull` never null, lexer KDoc claims 2 arrays (4) | multiple | CONFIRMED |
| L45 | Low | expression | NBSP is JS whitespace, not Kotlin's — expressions pasted from web pages fail with "Unexpected character ' '" | Lexer.kt:68-69 | CONFIRMED |
| L46 | Low | model | `%s` truncates toward zero; d3 floors — off by one second pre-1970 | TimeFormat.kt:205 | CONFIRMED |
| L47 | Low | dataflow | `variance`/`stdev` of one value writes `Num(NaN)`; upstream leaves the property undefined | Aggregate.kt:161,354-362 | CONFIRMED |
| L48 | Low | dataflow | `missing` counts NaN; upstream counts only null/undefined/`""` | Aggregate.kt:293 | CONFIRMED |
| L49 | Low | dataflow | `argmin`/`argmax` skip ±Infinity and non-numeric values upstream compares raw | Aggregate.kt:301-317 | CONFIRMED |
| L50 | Low | dataflow | A NaN-coercing value bins to null (drops under `!= null` filters); upstream bins to NaN (kept) — the file's own KDoc stresses that exact distinction | Bin.kt:135 | CONFIRMED |
| L51 | Low | dataflow | `stack normalize` over a zero-sum group → 0 (drawn at zero); upstream → NaN (not drawn) | Stack.kt:60-68 | CONFIRMED |
| L52 | Low | dataflow | `pie` takes `abs()` of values — negatives and zero totals diverge from upstream; presented in docs as fidelity | Pie.kt:31 | CONFIRMED |
| L53 | Low | dataflow | `pivot` drops null keys; upstream emits a `"null"` column | Reshape2.kt:95 | CONFIRMED |
| L54 | Low | dataflow | `lookup` never matches null keys; upstream indexes `String(null)` | Generate.kt:124-126 | PLAUSIBLE |
| L55 | Low | dataflow | `ntile`/`nth_value` default a missing param to 1; upstream errors | Window.kt:223,229 | CONFIRMED |
| L56 | Low | dataflow | `extent` with an Infinity reports the finite extent; upstream warns and reports `[undefined, undefined]` | BasicTransforms.kt:223-239 | CONFIRMED |
| L57 | Low | dataflow | Mixed-type sort columns order lexically where upstream's comparator ties (stable order holds) | Transform.kt:421-435 | PLAUSIBLE |
| L58 | Low | dataflow | Two number-to-path-text formatters: Voronoi falls back to Kotlin `toString` (`1.0E-5`), LinkPath uses JS form | Voronoi.kt:122-127 vs Links.kt:153 | CONFIRMED |
| L59 | Low | dataflow | Hierarchy layout `as` ignored unless longer than the default list; dead locals (`radiusField`, `REFUSED`); Label KDoc describes an unimplemented `sort` priority | Hierarchy.kt:305,539, Aggregate.kt:471, Label.kt:112 | CONFIRMED |
| L60 | Low | hosts | MP walk maps rects/clips by two corners and doesn't scale corner radii — wrong under rotation (unreachable from compiled scenes today); `GroupNode.clipPath` ignored by MP | MP SceneWalk.kt:83,410-462 | CONFIRMED |
| L61 | Low | hosts | Font stack: a generic anywhere preempts platform resolution of earlier concrete names (CSS would pick the concrete); `Typeface.DEFAULT` as the not-installed probe can false-negative on OEM builds | AndroidTextEngine.kt:220-243 | CONFIRMED |
| L62 | Low | hosts | Gradient bounds resolved against `node.rect` (View) vs `node.bounds` incl. stroke (MP) | AndroidCanvasSceneRenderer.kt:264 vs MP SceneWalk.kt:87 | CONFIRMED |
| L63 | Low | hosts | Every snapshot change re-invalidates the whole semantic tree — TalkBack floods during pan | VegaChartView.kt:344-361 | CONFIRMED |
| L64 | Low | export | `toPng(quality)` is a meaningless public parameter; `ByteExport` double-copies the stream | SceneExport.kt:72-81 | CONFIRMED |
| L65 | Low | supply chain | GitHub Actions pinned by mutable tag, not SHA — release job has `contents:write` | ci.yml:49-67, release.yml:36-50 | CONFIRMED (risk PLAUSIBLE) |
| L66 | Low | release | Partial-failure recovery after the tag is pushed is undocumented and blocked by the already-tagged guard; `--latest` unconditional | release.yml:330-375 | PLAUSIBLE |

---

## 2. System map

**Pipeline.** `VegaLiteInput.toVega` routes on `$schema`, then shape (`marks` ⇒ Vega; unit/composite
VL keys ⇒ Vega-Lite; everything else passes through unchanged with `wasVegaLite=false`).
`VegaLiteCompiler` normalizes (repeat → concat, facet folding, composite-mark rewriting, path
overlays) → parses → builds a view tree → merges scales → reproduces upstream's dataflow-optimizer
fixpoint (the optimizers decide the observable `data_N` numbering) → emits a Vega spec plus a
textual signal-rename pass. From there both grammars take one path: `SpecCompiler.compileJson`
merges `hostConfig` *before* parsing, runs `compileOnce` twice for `fit` autosize (first pass
measured, its diagnostics discarded), resolves datasets/scales/signals interleaved in one
topological order (`DataflowOrder`), then `ScopeCompiler` builds the scene per scope: underlay
axes → marks in declaration order (groups recurse; hover variants share ids via allocator rewind)
→ trellis layout → raised axes → legends → title. `MarkEncoder` merges `enter + update`, applies
upstream's span rules and band offsets. There is **no dataflow engine at runtime**: every
interaction, `setData`, `containerSize` or signal change recompiles the whole spec
(`VegaChartController.applyFired`), which is the documented "correct, slower" trade.

**State owner.** `VegaChartController`: `StateFlow<ChartState>`, `events` flow, `diagnostics`
StateFlow, sync `setSpec` + async `setSpecAsync` (only the async paths take `compileLock` — H5),
pan/zoom as a pure view transform in `InteractionState` (axes deliberately don't rescale).

**Renderers.** Four surfaces draw one immutable `Scene`: Android Canvas (`VegaChartView`, the
canonical host), `vega-compose` (AndroidView wrapper), Compose Multiplatform (`SceneWalk` →
`DrawScope`), and Swift CoreGraphics (`SceneWalk.swift`, structurally mirrored). SVG/PNG/PDF export
reuse the Canvas backend. Parity is enforced at the *call-shape* level (scene-walk byte goldens,
host-conformance goldens, `host-parity.py` presence checks) — not at the semantic level, which is
where H8/H12/H25/H30/M49-M56 live.

**Verification stack.** Ground truth is `oracle-js` (pinned vega 6.3.1 / vega-lite 6.4.3, pinned
seed/clock/TZ): 193 Vega fixtures compared as flattened scenes, 283 VL fixtures compared as
emitted specs *and* drawn scenes; ~9,800 recorded upstream vectors replayed in unit tests; scene
snapshots and SVG goldens as self-regression; ABI dumps + `javap`/symbol-graph snapshots for API
surfaces. The gates are thoughtful but several **skip instead of failing** when their inputs are
missing (C10, H31, H33) — the gap between "green" and "ran".

**Key invariants and where they actually live.** Determinism: enforced (pinned clock, seeded RNG,
LinkedHashMap discipline) — holds. "Nothing throws, diagnostics instead": *assumed only* — no
try/catch net exists at any public boundary (C7-C9, H4, H9, H17). Id order = paint order: enforced
by allocator discipline, but `zindex` reordering is applied only in SVG (M17). "One text engine,
one compile at a time": enforced only on the async paths (H5). Scene immutability before
publication: holds.

---

## 3. Findings by category

Full detail for Critical/High; Medium/Low carry scenario and direction in compressed form. Every
ID above appears exactly once below.

### 3.1 Correctness

**C1 — Missing datum field coerces as JS `null` (0), not `undefined` (NaN)** —
`vega-expression/JsSemantics.kt:46-52` + `Evaluator.kt:81-105`. `Evaluator.property` yields
`VegaValue.Null` for an absent field and `toNumber(Null)=0`. Upstream: `datum.missing < 10` is
false; here true. Scenario: a filter `datum.x < 10` over rows lacking `x` keeps every such row
here and drops them upstream — a different chart on the most ordinary of dirty data.
SUPPORTED_FEATURES.md:256 claims "arithmetic and truthiness agree", which is true for JSON null
and false for missing. Related: `isDefined(datum.nullField)` is false here, true upstream.
CONFIRMED. Direction: introduce a distinct undefined marker (or a sentinel Null variant) whose
JsSemantics coercions are NaN/`"undefined"`, produced by absent-property access only.

**C2 — `collectSignals` deletes genuine dependencies** — `VegaExpressionCompiler.kt:64-73`.
The non-computed-member branch does `names.remove(property.name)` on the shared set, so
`"year == datum.year"` reports no dependency on signal `year`; `DataflowOrder`/`SignalResolver`
then resolve the expression before the signal exists and never re-evaluate on change. Scenario: a
slider bound to `year` filtering `datum.year` — chart never updates, zero diagnostics.
CONFIRMED (independently re-traced). Direction: delete the removal block (the walk never adds
non-computed property names) and pin the collision case in ParserTest.

**C3 — `window` collapses duplicate rows** — `vega-dataflow/transform/Window.kt:48-56`.
`HashMap<VegaValue, VegaValue>` keyed by the row itself; `VegaValue.Obj` is structural, so
`[{v:1},{v:1}]` with `ops:["sum"]` returns `[2,2]` (upstream `[1,2]`); `row_number` and `lag`
equally wrong. Duplicate rows are ordinary. None of the 14 replayed window vectors contains
duplicates. CONFIRMED (re-traced). Direction: map positions, not values — `Stack.kt:39` already
does this and says why in a comment.

**C4 — `buildTime` ignores `domainRaw`** — `compile/ScaleResolver.kt:684-740`. The time branch
reads `spec.domain` and uses `rawApplies()` only to suppress `nice`; the linear path's
`rawDomain → bounds → pad → nice` ladder is never consulted. Scenario: the committed
`overview-plus-detail.vg.json` fixture — brush the overview, the detail panel recompiles with the
full domain; the flagship interaction silently renders unzoomed. Static compiles pass the oracle
because `detailDomain` is null at compile time. CONFIRMED (re-traced). Direction: route the time
domain through the same ladder.

**C7 — VL date-part rollover throws** — `vegalite/Transforms.kt:995-1020` via
`Selection.kt:429-433`. `part("month")` feeds `LocalDateTime(month = month+1)`; kotlinx-datetime
throws `IllegalArgumentException` for month 13, Feb 30, hour 24 — all of which JS `Date` rolls
over and upstream compiles (probed: `month:13` → Jan 2001). No `try` exists anywhere in the
module, so the exception escapes `VegaLiteInput.toVega`, the API the README markets for pasted
text. CONFIRMED (re-traced: zero `try {` in main source). Direction: roll like JS `Date`, plus the
H9 boundary.

**C8 — one-arg `datetime()` wrong and crashing** — `Functions.kt:1525-1548`. Upstream codegen is
`datetime: 'new Date'`, so `datetime(1600000000000)` is that instant; here arg 0 is always a
*year*, `toInt()` saturates and `LocalDate(2147483647,1,1)` throws — uncaught, because every catch
site is typed `ExpressionEvaluationException`. `datetime(datum.epochMillis)` is a documented
upstream idiom. CONFIRMED. Direction: one-arg = timestamp; guard the year range → `Timestamp(NaN)`.

**C9 — gradient legend `ClassCastException`** — `compile/LegendBuilder.kt:989`.
`scale.fraction((entry.value as VegaValue.Num).value)` on unvalidated `LegendSpec.values`.
Scenario: a continuous colour scale plus `"legends":[{"fill":"c","values":["2020-01-01"]}]` — the
natural way to write date values — CCE out of public `compileJson`. CONFIRMED (re-traced).
Direction: coerce via `asNumberOrNull`, skip/report non-numeric entries, and add the boundary
below.

**H2 — niced time scale drops bounds and padding** — `ScaleResolver.kt:696-731`. `bounded` and
`padded` are computed, then the `nice` branch re-derives from the original `domain`
(`stepper.floor(domain.first())`). Vega-Lite defaults temporal scales to `nice:true`, so a VL
`scale.domainMax` on a temporal axis silently shows the full span — the adjacent comment says the
opposite was intended. CONFIRMED (re-traced). Direction: nice over `padded`.

**H3 — projections absent from encode scopes** — `SignalResolver.kt:346-359,697-709`.
`Resolution.scope()` builds `SignalScope` without projections and `withDatum` silently resets them
to empty. `"size": {"signal": "4 * geoScale('p')"}` reports "projection 'p' … not define[d]" per
compile and leaves the channel unset; every geo fixture passes because none uses a geo function
inside `encode`. CONFIRMED. Direction: thread `CompileScope.projections` through; make `withDatum`
copy all fields.

**H15 — `parseFieldPath` bracket-then-dot** — `vega-model/VegaValue.kt:205-227`. After `]`, a `.`
unconditionally appends the empty `current`: `"list[1].b"` → `["list","1","","b"]` → Null.
Scenario: `"field": "coordinates[0].lat"` over GeoJSON-shaped rows — mark not drawn, no
diagnostic. Tests cover `list[1]` but never the combination. CONFIRMED. Direction: skip the
segment-add when a bracket segment just closed.

**H16 — `erfInverse` loses the sign of x** — `Statistics.kt:85-96`. The non-finite branch
early-returns +Infinity instead of falling through to `p * x` as upstream does; `quantileNormal(0)`
= +Inf (should be −Inf), `quantileLogNormal(0)` = +Inf (should be 0). A QQ-plot with rank p=0 puts
a point at the wrong infinity. CONFIRMED. Direction: set p and fall through to the multiply.

**H10 — interval selection date init emits objects** — `vegalite/Selection.kt:461-492`.
`storeData`'s interval branch writes the raw `{"year":…}` objects where upstream (probed) writes
epoch ms; `vlSelectionTest` then compares numbers to objects and initial filtering is wrong until
the first drag. The `timeZone` parameter's own documented rationale is implemented only for the
point branch. CONFIRMED. Direction: route through `dateTimeTimestamp` like the point branch; add a
fixture.

**H20 — stack center mis-stacks negatives** — `transform/Stack.kt:72-86`. Kotlin splits ±cursors
under `center`; upstream `stackCenter` uses one cursor over `abs` from `(max − sum)/2`. Probe:
`[3,-5]` → upstream spans `[0,3],[3,8]`; here `[0,3],[0,-5]`. The one center fixture is
all-positive. CONFIRMED. Direction: port `stackCenter` as written.

**H21 — `""` counted as a valid 0 in aggregates** — `Aggregate.kt:296,319`. Upstream's cell `add`
treats `v === ''` as missing; here `toNumber("")=0` enters the numeric list. `[1, ""]` → mean 0.5
(upstream 1). CONFIRMED. Direction: screen `Str("")` as missing in the aggregate layer only (see
open question Q12 re the loader).

**H22 — `joinaggregate` ci ops write null** — `Aggregate.kt:207,286`. No `confidence` closure is
passed, so `Measure.compute` returns Null for `ci0`/`ci1`, silently. CONFIRMED. Direction: build
the memoized bootstrap closure `AggregateTransform.apply` already builds.

**H23/H24 — `pie.sort` and `bin.steps` silently ignored** — `Pie.kt`, `Bin.kt:204-265`. Both are
upstream `Definition` parameters; neither is read, neither refusal is diagnosed — inconsistent
with the module's otherwise loud unknown-transform philosophy. CONFIRMED. Direction: implement
(both are small), and add a generic unread-parameter warning per transform.

**Medium correctness** — M13 (`TransformedScale.invert` first/last-stop only → silently wrong
brush inversions on multi-stop scales; return NaN or invert piecewise); M30-M35, M37-M38
(JS-fidelity cluster in the expression engine: string-sorting dates, `clampRange`, `inrange`
flags, negative `timeSequence`, `toInt32` saturation, fractional indexing, Timestamp-`+`
semantics, array-vs-string `==` — each with a one-line upstream-verified divergence; fix by
porting the upstream expression); M42-M45 (aggregate Infinity filtering, sum-over-nothing 0 vs
undefined — the code follows a comment that was probed false —, group keys not string-coerced
despite `asComparableKey()` existing for exactly that, force link accessors evaluating against a
null datum); M49 (pre-Q MULTIPLY is modulate: marks over transparent regions vanish on API 26-28 —
either report unsupported pre-Q or document); M19 (SVG multiline baseline: fold `top + ascent`
into each tspan's `y`, emit `dominant-baseline="alphabetic"`); M27 (Swift letter-spacing:
measurement and drawing disagree by the `textScale` factor — scale the kern in `advanceOf`); M56
(gradient text black on MP; stroke-only text filled on MP, blank on View — agree on one rule).

**Low correctness** — L9-L12 (tick/nice/DST edges), L38-L42, L45-L46 (expression edge parity),
L47-L58 (dataflow edge parity: NaN/Infinity/null-key/zero-sum divergences from upstream, each
probed), L21 (paintRect clip fallback), L34/L60 (two-corner AABB mapping under rotation, shared by
both walks), L61-L62 (font-stack generic preemption; gradient bounds rect-vs-bounds), L8, L14-L15.

### 3.2 Unintended paths & concurrency

**C5 — stale async compile clobbers newer state** — `VegaChartController.kt:521-543`. The compile
runs inside `compileLock`, but `loadedSpecJson = json; signals.reset(); publish(compiled)` run
after the lock with no generation check. Scenario: `setSpecAsync(A)` in flight; UI thread calls
`setSpec(B)`; A resumes and publishes — the reader sees A, and every subsequent interaction
recompiles A. Same shape for `setContainerSizeAsync` (publish outside the lock → out-of-order
publishes). CONFIRMED (re-traced: no epoch/generation anywhere in the class). Direction: stamp
requests with a monotonic generation at entry; drop stale publishes; publish inside the lock.

**C6 — Compose MP gestures die under the documented wiring** — `MP VegaChart.kt:435,475,506`.
All three `pointerInput` modifiers are keyed on `viewportOffset`/`viewportScale` (re-traced). The
README wires those from controller state, so the first `onPan` dispatch changes the key, cancels
the suspending block, and the restarted `detectTransformGestures` waits in `awaitFirstDown` that a
finger already down never satisfies. The keys aren't even read inside the transform block.
CONFIRMED structurally (one open question on Compose re-down synthesis). Direction: key on
`scene`/`fit`/`density` only; read viewport via `rememberUpdatedState`.

**H5 — sync compiles bypass the lock** — `VegaChartController.kt:350,464,214,764`. Only the two
async methods lock; `setSpec`, the `hostData`/`containerSize` setters and every interaction
recompile call `compiler.compileJson` bare — two compiles concurrently on one `TextEngine` (the
exact race the constructor doc warns hosts about) plus unsynchronized `SignalUpdater` maps.
CONFIRMED. Direction: one serialization point for all compiles.

**H13/H14 — ChartSession gaps in an otherwise-serialised design** — `ChartSession.swift:385-397,
602-607`. `load("")` clears state synchronously but neither cancels nor queues: the in-flight
compile resurrects the cleared chart. `set(signal:to:)` mutates the controller inline while a
compile may be running off-actor — the one entry point not wrapped in `serialised {}`. Both
CONFIRMED. Direction: route both through the queue; cancel on clear.

**H17 — unstructured escape hatches** — `Evaluator.kt:205` (`data()` with no args →
`NoSuchElementException`), `VegaValue.kt:54` (`regexp('(')` compiles in the `Pattern` initializer,
raw ktecma262 error — the failure class the Pattern KDoc says the engine was adopted to avoid),
`Parser.kt` (no depth limit; `"((((…"` a few thousand deep is a StackOverflowError, unrecoverable
on Kotlin/Native). All bypass the `ExpressionEvaluationException`-typed net. CONFIRMED. Direction:
wrap pattern compilation and argument access; add a parser depth counter.

**H4/H9 — no recursion caps at either compiler** — `ScopeCompiler.kt` and
`VegaLiteCompiler`/`VegaLiteInput`. Machine-generated or adversarial nesting crashes the process
instead of producing the diagnostic the contract promises. CONFIRMED. Direction: depth counters →
FATAL diagnostic; plus a last-resort `catch (t: Throwable) → FATAL + null scene` at `compileJson`
and `toVega` so the "nothing throws" sentence becomes true by construction (see Design tension 1).

**H6 — synchronous re-fetch per recompile** — `DataResolver.kt:229-246` + `HttpDataLoader.kt:77`.
`DataResolver` is built per compile with no cache and the interaction model recompiles per tap;
with a loader opted in, each click issues a blocking GET (10 s/30 s timeouts) on the dispatching
thread, and a `{"type":"timer","throttle":500}` stream polls the network twice a second (upstream
loads once). CONFIRMED (no cache in `compile/`). Direction: memoize `sanitize(url) → text` on the
controller, invalidated by `setSpec`; document that `load` runs on the compiling thread.

**M11/M12/M14** — NaN gestures poison `viewportOffset` forever (guard both delta and anchor as
`scaleFactor` already is); StateFlow copy-writes and `nextRevision++` are non-atomic across
threads (`update {}` + atomic counter); `stop()` is resurrected by the next publish (a stopped
latch cleared by `setSpec`). All CONFIRMED.

**M40/M46/L22/L30** — unsynchronized shared mutable state: the expression LRU shared between
handler evaluation and background compiles (PLAUSIBLE), `Orient2d`'s JVM-global scratch buffers
under concurrent voronoi compiles (PLAUSIBLE), `TextLayoutCache`'s undocumented single-thread
assumption, Swift's `unresolvedFontFamilies` mutated off-actor under a "no mutable state" header.
Direction: pick a threading model and enforce it (Design tension 5).

**M5, L28** — unbounded materialization (`facet.aggregate.cross` n·m; VL `repeat` grids). Cap with
a diagnostic.

**H1** — `tickCount: 1e9` OOMs (`List(n)` uncapped) or hangs (`countWithMinStep` walks down one at
a time); `ScaleResolver` already has the `MAX_BINS = 10_000` precedent. CONFIRMED. Clamp and
compute the walk-down directly.

### 3.3 Incoherences

**M17 — `zindex` honoured only in SVG** — the hit index and all three on-screen walks iterate raw
children while `SvgRenderer` reorders with `paintOrder()`, whose own comment says every renderer
must. A static `zindex` on an earlier sibling: SVG paints it on top (upstream-correct), screens
don't, and taps go to the mark under it. CONFIRMED. Direction: build walks and hit entries over
`paintOrder(children)`.

**M18 — even-odd hit testing vs nonzero painting** — `containsEvenOdd` is the only containment
test; every renderer and upstream use nonzero winding. Self-intersecting symbol paths (pentagram)
paint their centre and refuse taps on it. CONFIRMED. Direction: add a winding test for fills.

**M44 — two group-key semantics in one module** — `asComparableKey()` exists to reproduce
upstream's string coercion (its KDoc cites the join bug that motivated it) and is used by
lookup/impute/dotbin, while `groupTuples` (aggregate/window/pivot/regression/…) keys raw.
CONFIRMED. Direction: one keying function.

**M47 — `countpattern` uses Kotlin `Regex`** — the one transform that parses user regexes bypasses
the ktecma262 engine the codebase adopted for exactly this class of bug. CONFIRMED.

**M4 — three font-weight parsers disagree** (`"bolder"` = 700/800/700); **M3** — one of two
readers of the scaled-channel input order already drifted (drops `signal`); **L58** — two
number-to-path-text formatters in one module; **L1/L3** — dead twins that will drift
(`tickCountFor`, `decimalsFor`). All CONFIRMED. Direction: single shared helpers.

**M39 — the parser's two silent blocks** — `parseSignal`/`parseData` drop unknown properties
(data triggers, signal `push`/`react`) with no diagnostic, against the parser's own "nothing
silently dropped" doc — and this repo's own VL compiler emits `"push":"outer"` that nothing reads.
CONFIRMED. Direction: extend the consumed-key machinery to both; answer the `push` scoping
question (Q6).

**M48 — a public incrementality API with no engine** — `Dataflow.kt`'s `ChangeSet`/`TupleId`
contract is consumed by nothing; `TupleId` promises identity survival that nothing implements. The
README discloses "no incremental dataflow" but the API still advertises it to an SDK consumer.
CONFIRMED. Direction: internal-annotate or delete until the engine exists.

**M51 — `onPlaced` cadence differs per host** (View: on change from layout; MP: every draw, from
the draw phase); **M50** — `ENDED` unreachable on MP (dead parameter documented as live), so
`ViewportChanged` never fires there; **H30** — blend modes rendered on View, silently dropped on
MP; **M56/L62** — text and gradient rules differ per host. These are the "presence ≠ agreement"
gaps the README's own #123 story predicts. Direction: Design tension 3.

**M29 — the Swift demo's loader contradicts both its UI text and its README**; **M28** — the
scene-walk fixture list exists twice with no sync check. CONFIRMED.

**L44/L59/L2** — dead-code clusters (unused regexes, no-op remnants, double registrations,
discarded scopes, dead locals).

### 3.4 Affordance mismatches

**H7 — every mark grows a tooltip** — `MarkEncoder.kt:1470` (`channels["tooltip"] … ?: datum`,
re-traced) + `VegaChartController.updateHover`. The README's tooltip section exists to promise the
opposite ("a mark with no tooltip channel produces an empty object, which is not a tooltip");
`HoverEncodeTest` pins the fallback, so the code is deliberate and the contract is wrong on one
side or the other. A host following the README (or `tooltipsEnabled`) shows a full-row bubble on
every mark of every chart; upstream shows nothing. CONFIRMED, corroborated independently by two
reviewers. Direction: keep `metadata.datum` and `metadata.tooltip` distinct — the datum fallback
also breaks **M8** (`datumOf` returns the tooltip value, so `MarkClicked.datum` is the string
`"bar b"` when a tooltip channel exists — return `metadata.datum`).

**H28 — the Compose surface can't do the one thing its docs insist on** — README and ADR 0003 both
lecture that measurement and drawing must share an engine, yet `vega-compose` exposes no
`textEngine` seam, its `rememberVegaChartController` defaults to `MetricTextEngine` ("matches no
real font"), and the view's compatible engine is unreachable. The repo's own demo has the bug: it
compiles with `AndroidTextEngine()` (fontScale 1) and draws at the device's scale — labels
escaping their boxes at any non-default text size. CONFIRMED. Direction: a
`textEngine`/`fontScale` parameter, and fix the demo.

**H29 — the natural way to pass a resolver is the broken way** — identity-compared setters plus
inline lambdas = renderer rebuilt per recomposition: image caches cleared, resolvers re-asked,
`onUnresolvedImage` re-fired — the once-per-URL parity row voided by idiomatic Compose code. The
README documents "a different `imageResolver` … starts an empty cache" as a *feature* without
warning it fires accidentally. CONFIRMED. Direction: value-keyed rebuilds or an explicit
`clearImageCache` seam; at minimum a `remember` warning in the parameter docs.

**M23 — `isUsable` is true for broken output** — a VL spec with no `data` (legal upstream) emits
ERROR plus a non-null spec referencing dataset `""`; the README's stop-on-null pattern forwards it
and the runtime fails with an unrelated error. CONFIRMED. Direction: compile like upstream or make
it fatal; decide the ERROR⇒null policy (Q7).

**M53** — a focusable chart that consumes every navigation key while `dispatch(Key)` does nothing:
the easy path (default `isFocusable=true`) is a keyboard trap. **M36** — the "empty
`knownUnsupported` map is a claim, and a test checks it" guarantee audits the wrong upstream table,
so `isNaN` is both unimplemented and invisible to the audit. **L5** — the public group encoder
lies about itself. **L43** — `SpecParser` is public but single-use, undocumented. **L64** —
`toPng(quality)` is a knob wired to nothing.

### 3.5 Missing functionality

**H11 — unknown encoding channels kept silently** — upstream warns and drops; here a `colour`
typo changes the aggregate's `groupby` (different data), enters descriptions and tooltips.
CONFIRMED (probed against pinned upstream). Direction: validate against the channel set,
warn-and-drop.

**H18/H19** — `span` empty→NaN (upstream 0); `utcOffset` double-registered with the wrong return
type. **M24** — comma-merged event selectors: only the first stream kept, touch users can't
select. **M25** — the two VL fallback diagnostics misdescribe what's implemented (bin/stack/
timeUnit/impute omitted; "parameters … not implemented" predates params support). **M1** — the
diagnostic flood: per-datum errors where `reportOnce` sits unused. **M2** — non-transitive sort
comparators can make TimSort throw (total-order missing keys). **M7** — negative plot area
unclamped. **L13** — the inert `window:` stream registers silently. **L55** — `ntile` defaults
where upstream errors. **M57** — export: no size cap, engine mismatch with the live view, PDF
degradation invisible to `warnings`. **L66** — no documented recovery for a half-failed release.

### 3.6 Boundary & safety

**M9 — SSRF guard misses IPv6-mapped IPv4** — `[::ffff:169.254.254.169]`-style literals pass
`blockPrivateNetworks`; mitigated by deny-by-default and the allowlist pairing advice, real for
hosts that enable the block alone. CONFIRMED. Direction: normalize mapped v6 → re-check as v4; add
`fec0::/10`, `64:ff9b::/96`.

**M10/L16 — the response "cap" runs after the body is fully in memory** and counts UTF-16 chars;
the JVM transport `readBytes()` unbounded and ignores charset. Direction: pass the byte budget
into the transport contract.

**M22 — `javascript:` hrefs survive into SVG exports** — spec-controlled, XML-escaped only,
clickable when the export is opened in a browser; the project's own threat model treats specs as
untrusted. CONFIRMED emission; PLAUSIBLE exploitation. Direction: scheme-allowlist, or route
through the image-policy enum (and check what upstream's `sanitize({context:'href'})` does — Q8).

**L14** — empty-host URLs reach the transport; ports unchecked. **L19** — default SVG `idPrefix`
cross-wires two charts inlined into one page. **L36** — three unbounded process-lifetime caches on
the Swift side. **L65** — Actions pinned by mutable tag with `contents:write` in the release job.
**L18** — C0 control chars make exports malformed XML.

### 3.7 Documentation

**C10/H31/H33 — the gate documentation describes a stricter system than exists.** The release
verify job lost the armed VL scene gate yesterday (commit 3b37546c) while the publish job's
comment still claims verify "has already run that comparison in full" (re-traced:
`vega-lite-oracle.sh:81` runs only `:vega-lite:test`, its own header says the 1126-test scene gate
"assumes itself away" without pre-generated references, and release.yml lacks the
`--references-only` step ci.yml:83 has). `check.sh`'s ledger can print "Green, and every gate ran"
on a run where that gate never armed. Both workflows assert in comments that missing vectors "fail
rather than skip"; both replay tests `assumeTrue`. Direction: copy the `--references-only` step
into release verify, add a skip-count assertion there (ci.yml already has one), fix the comments,
and consider `REQUIRE_VECTORS=1` making assumptions into failures in CI.

**C11 — "compares the resulting scene mark by mark" overclaims** — the comparison model erases
subpath structure (MoveTo ≡ LineTo on the Kotlin side, no `defined` handling on the JS side,
re-traced: all 23 `defined` grep hits in normalize.js are `undefined`), and line/area get no
bounds comparison. A `defined`-gap regression is invisible to the project's ground truth.
Direction: encode pen-up/pen-down in the points channel on both sides.

**Stale KDoc that inverts the truth** (all CONFIRMED): `SpecCompiler.kt:108` ("legends and titles
are not implemented" — 2,000 lines say otherwise); `LegendBuilder.kt:92` (lists five "not
generated" features all implemented in the same file); `VegaLiteCompiler.kt:38` (main entry point
describes a compiler several releases old — facet/concat/repeat/params/composites all "reported by
name and not approximated" and all implemented); README:72 contradicts README:48 on composite
marks; `MarkDefaults`/`AxisDefaults`/`LegendDefaults` claim "config overrides are not implemented"
against the README's config-cascade feature; `Params.kt` header; `Functions.kt:57` (claims
random/date/selection helpers "deliberately excluded" — all present — and cites a test file that
doesn't exist); SUPPORTED_FEATURES self-contradicts on dates (row 258 vs 451) and its `aggregate`
row says 17 ops (25); `Aggregate.kt:321`'s comment is probed-false **and load-bearing** (the code
follows it); the geo area KDoc describes hole-handling neither engine has; `Bin.kt`'s header;
`AxisBuilder`'s call-site comment vs `boundedLabels`' own; `Stack`/`Pie` docs present divergences
as fidelity; `Label.kt` describes an unimplemented sort; M61 (HANDOFF) and M62 (ADRs 0005/0003)
above; `PathNode` "plus `arc` once implemented"; `Scene.flatten()` "paint order" (declaration
order); vega-loader absent from the README's published-modules list and architecture diagram;
`VegaDataLoader.swift` "exactly two prefixes" (one); the Swift demo README's "five files"
(twelve); `SvgRendererTest`'s comment is right while its assertions can't see H8. Also L29's
broken vector generator (`push(a, b, c…)` records one of six families) and L31/L32 (oracle range
pin; probes without `pinDeterminism()`).

### 3.8 Developer experience

**M59 — `test-core.sh` lies by omission** (no vega-lite/vega-loader; README sells it as the JVM
suite). **M60 — the changelog gate's marker is branch-scoped**, so one legitimate
`[api-snapshot-only]` commit exempts a real API break in another commit — the exact 0.4.0
near-miss the gate was written for. **Newcomer walkthrough**: README setup step 2 needs step 3's
JAVA_HOME exports on a truly clean machine; step 5 (`./gradlew test …`) goes green while ~9,800
vector replays and 1,126 VL scene tests silently skip — a newcomer reasonably believes the tests
ran; only `check.sh` warns about the vectors and nothing warns about the scenes. `check.sh --list`
and the scripts' error messages are genuinely excellent otherwise; four scripts resolve `adb`
inconsistently with the rest (L-cluster). **L35/L7 of the gates**: host-conformance's "read by" is
a substring match. **DX positives worth keeping** (once): pipeline-stopping unknown-transform
diagnostics, the `publishesSignal` gate, ios-demo.sh's failure messages, the fixture harness's
40-line capped diffs, `VegaJsonFallbackTest` linting the repo's own docs for the anti-pattern the
README warns about. Other DX findings: wrong-arity expression calls report "Unknown function"
(the arity guard falls through to the map lookup); a Vega spec pasted with a VL `$schema` gets
`MISSING_MARK` with no "this looks like Vega" hint; locale/timeZone must be threaded to `toVega`
*and* the runtime with agreement enforced only by prose; two views sharing one controller silently
fight over `contentScale`; `Package.swift` on `main` pins the previous release's binary while
Swift sources evolve (branch consumers can get source/binary skew); NBSP lexing (L45).

---

## 4. Design tensions

**T1 — "Nothing throws" is a promise without a mechanism.** The README stakes the diagnostic
model on it ("Nothing throws — a compile returns diagnostics"), yet no public boundary has a
catch-all or a depth cap: C7-C9, H4, H9, H17, M2 are seven independent ways a spec crashes the
host, and each fix so far has been local. The contract currently holds only if every one of ~60k
compiler lines is individually careful. Alternative: make it true by construction — a last-resort
`catch (Throwable) → FATAL diagnostic + null` at `compileJson`, `toVega` and expression
evaluation, plus depth counters at the three recursive walks. Cost: a swallowed-bug risk (log the
throwable into the diagnostic); the alternative of dropping the claim from the README is cheaper
but gives up the feature adopters were sold.

**T2 — Recompile-everything meets stateful reality.** "Correct, and slower" was the documented
trade, but recompilation is no longer merely slow: it re-fetches URLs synchronously per tap (H6),
re-randomizes nothing only because RNG is pinned per compile, resets signals a stale async publish
can resurrect (C5), and coexists with a public incremental-dataflow API nothing implements (M48)
whose `TupleId` promises identity survival the scene doesn't have. Alternative: keep the recompile
model but give the controller a compile-input cache (url→text, parsed spec) and a single
generation-stamped publish path; separately either build the pulse engine Milestone 4 promised or
delete/internalize the contract so the API stops advertising it.

**T3 — One semantic, five implementations.** The scene is shared; the *meaning* of a scene is
re-derived per host, and it drifted exactly where the parity harness can't see: opacity (H8),
blend (H30), gesture coordinate spaces (H12/H25/C6), placement cadence (M51), text rules (M56),
gradient bounds (L62), fill rule (M18), zindex (M17). `host-parity.py` checks seams exist;
scene-walk goldens check call bytes; neither checks agreement about semantics — the README even
names this gap and #123 as its history. Alternative: push per-node semantics down into
`vega-scene` as the single implementation (an "interpretation layer": effective opacity, paint
order, blend capability, gradient geometry, text block metrics) that every walk consumes, and
extend host-conformance with coordinate-space and semantic goldens (a pan golden would have caught
H12; a blend golden H30).

**T4 — The value substrate has two truths and no `undefined`.** `VegaValue` conflates JS `null`
and `undefined` (C1), carries two coercion families (model-canonical vs `JsSemantics`, plus a
third ad-hoc one in `groupTuples`, M44), and prints numbers three ways (M41, L26, L58). Every
JS-fidelity bug in this report bottoms out here. Alternative: add an `Undefined` object (or a
tagged Null) produced by absent access, define its coercions once in `JsSemantics`, and make
`asComparableKey`/`jsString` the only keying/printing functions — a breaking-ish internal change
that retires a whole bug class; the piecemeal alternative (patch each divergence) is what the
codebase is already doing, and this audit found eleven more.

**T5 — Concurrency by convention.** The design intends "compile off-thread, publish immutable,
draw on main" but enforces almost none of it: half the compile entry points skip the lock (H5),
publishes are unordered (C5), StateFlow updates are read-modify-write (M12), and four caches
(expression LRU, text layout, Orient2d buffers, Swift font-set) assume confinement nothing
asserts (M40/M46/L22/L30). Alternative: a single confined compile executor owned by the
controller (all mutations funnel through it; sync APIs become "run now if on the executor, else
block"), `update{}`-only state, and thread asserts in debug builds. The Swift side already built
exactly this (`serialised {}`) and its two gaps (H13/H14) show even a good queue needs every door
to go through it.

---

## 5. Expectation gaps

- **Expected**: "a mark with no tooltip channel produces an empty object, which is not a tooltip."
  **Found**: the encoder substitutes the whole datum; every data mark reports a tooltip (H7), and
  `MarkClicked.datum` is the tooltip value (M8).
- **Expected**: "compares the emitted Vega property by property … compares the resulting scene mark
  by mark." **Found**: no `defined`/subpath channel (C11), no line/area bounds, several encoder
  properties absent from snapshots (M20).
- **Expected**: "Nothing throws." **Found**: seven distinct crash paths from spec text
  (C7-C9, H4, H9, H17, M2).
- **Expected**: `setSpecAsync` "does it off the calling thread" as a safe async variant.
  **Found**: last-writer-wins is not guaranteed; a stale compile can clobber a newer one (C5), and
  mixing it with any sync path un-serializes the text engine (H5).
- **Expected** (parity table): the same seam means the same behaviour on every host. **Found**:
  same-named seams differ in coordinate space (H12/H25), cadence (M51), reachability
  (`unresolvedFontFamilies` "via the view" is unreachable from `vega-compose`), phase delivery
  (M50), and rendering semantics (H8/H30/M56).
- **Expected**: "exported geometry matches what is on screen … anything unfaithful comes back in
  `warnings`." **Found**: the default exporter measures with a different engine than the live view,
  PDF-backend degradation is invisible to warnings, and export pins top-left while views centre
  (M57).
- **Expected**: "once per URL is what makes that safe." **Found**: true per renderer, and
  idiomatic Compose code rebuilds the renderer per recomposition (H29).
- **Expected**: `./scripts/check.sh` = "THE gate: everything this host can check, with a ledger."
  **Found**: the ledger can say "every gate ran" on a run where the VL scene gate never armed
  (H31); the release workflow has the same hole with a comment asserting the opposite (C10).
- **Expected**: "on a fresh clone they fail rather than skip." **Found**: `assumeTrue` (H33) — and
  a newcomer's `./gradlew test` silently skips ~11k assertions.
- **Expected**: KDoc as a map of the code. **Found**: the main entry points of three modules
  (`SpecCompiler`, `VegaLiteCompiler`, `Functions.kt`) describe earlier, smaller implementations;
  one probed-false comment is load-bearing (M43); two ADRs describe a repo that no longer exists
  (M62).
- **Expected**: a published module list you can trust. **Found**: `vega-loader` is published,
  ABI-dumped, and absent from the README's list and diagram.

---

## 6. Open questions

1. **Q1 (C4)** — is there any interaction-layer compensation that makes the overview-plus-detail
   brush appear to work on device? Nothing was found in `interaction/`; a device check settles it.
2. **Q2 (C6)** — does any Compose version synthesize a re-down for a restarted `pointerInput`
   mid-gesture? A test feeding viewport state back during an injected drag settles it (and is
   worth having regardless).
3. **Q3 (H1)** — upstream also hangs on `tickCount: 1e9`; does "match upstream" outrank the
   crash-free contract, or is a documented clamp acceptable?
4. **Q4 (M17)** — how reachable is static item `zindex` from compiled scenes? One differential
   fixture answers whether this is on-screen wrongness or SVG-only machinery.
5. **Q5 (C11)** — what does upstream put in a `defined:false` item's x/y, and do the engines agree
   today only because both echo datum coordinates? A gap-toggling fixture measures the blind spot.
6. **Q6 (M39)** — is `"push": "outer"` semantically moot under the flat recompile model, or are
   faceted interval selections broken? Needs a runtime-scoping answer or a fixture.
7. **Q7 (M23/L23)** — should ERROR-severity VL output imply `vega == null` (strict null contract)
   or stay best-effort? The README supports either reading; `VegaLiteCompilation` should say.
8. **Q8 (M22)** — does upstream sanitize `href` via `loader.sanitize({context:'href'})`? If yes,
   matching upstream and closing the `javascript:` hole are one change.
9. **Q9 (H33)** — was `assumeTrue` on the vector files added after the 0.1.0 release failure the
   workflow comments describe? `git log` on the two test files settles whether the comments were
   ever true.
10. **Q10** — should the release workflow require a green ci.yml run on the dispatched sha before
    verify? Today the VL scene gate's only coverage is that CI ran, and nothing enforces it.
11. **Q11 (dataflow)** — the vendored `vega-transforms/src/Window.js` uses signed frame offsets
    with a local explanatory comment, which reads like a patched oracle. Is the pin intentionally
    patched, and is that recorded anywhere?
12. **Q12 (H21)** — do the loaders parse `""` to null before transforms see it (upstream's type
    inference does)? That decides whether the fix belongs in the aggregate layer or only bites
    inline `values` data.
13. **Q13** — is a controller shared across two live views supported? If not, one documented
    sentence (and a debug assert) prevents the `contentScale` tug-of-war.
14. **Q14** — MVP completion item 13 (performance measured on physical hardware) remains unmet and
    is honestly disclosed in the README; the benchmark module also covers a fraction of
    PROJECT_BRIEF §18.6. Is the brief still the contract, or should it be amended the way the ADRs
    should?
15. **Q15 (L25)** — does this repo's own expression parser accept non-ASCII identifiers like
    `café`? That decides whether the `varName` divergence is a parity diff or a broken chart on
    non-ASCII field names.
