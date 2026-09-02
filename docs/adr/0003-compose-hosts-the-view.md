# 0003. The Compose API hosts the canonical View

Status: **amended (2026-08-27).** Still accepted for `vega-compose` on Android; the "revisit if"
clause was met and `vega-compose-multiplatform` draws on a `DrawScope`. See "What happened" below.
Accepted 2026-08-06.

## Decision

`VegaChart`, the public Composable, wraps `VegaChartView` in `AndroidView`. It does not draw on a
Compose `DrawScope`.

## Why

Two independent rendering paths would be two sets of behaviour to keep identical: text metrics, hit
testing, gesture translation, accessibility trees, invalidation timing. Any divergence would show up
as "the chart looks different in Compose", which is both a bad bug and an expensive one to test —
every rendering test would need to run twice.

Hosting the View gives one implementation and therefore one behaviour. `AndroidView` is a supported,
first-class interop API, and a chart is exactly the case it was designed for: a self-contained custom
view with its own drawing and input handling.

The Compose layer then has a small, honest job: own the controller's lifetime, forward events, and
avoid rebuilding anything on recomposition.

## Consequences

- A chart inside Compose is an Android `View`, so it sits in a separate composition boundary. That
  costs a little on very deep hierarchies and makes some Compose-native features (shared element
  transitions, for instance) unavailable to the chart itself.
- Compose previews cannot render the chart without a `Context`.
- `VegaChartComposeTest` tests the integration — same controller, same scene, events arriving — rather
  than re-testing drawing, which `AndroidCanvasSceneRendererTest` already covers.

## Revisit if

The Canvas backend is proven and stable, and a measured benefit appears for a direct `DrawScope`
backend — most plausibly Compose Multiplatform. At that point the scene graph is already
platform-independent, so a second backend is additive. It must not ship before the Canvas backend is
correct (CONTRIBUTING.md).

## What happened

The clause was met, in the order it named. `vega-compose-multiplatform` ships and draws directly on
a `DrawScope` — the benefit being iOS and desktop, which an Android `View` cannot reach at all — and
it came after the Canvas backend was passing the differential fixtures.

**The decision above is unchanged for what it covers.** `vega-compose`'s `VegaChart` still wraps
`VegaChartView` in an `AndroidView`, for the reason given: two rendering paths on *one* platform
would be two sets of behaviour to keep identical. What ships is not that. It is one platform-neutral
`SceneWalk` speaking to a target interface, with a `DrawScope` target beside the CoreGraphics one —
so the second backend is the additive shape this record anticipated rather than the duplicate one it
refused.

The cost the record predicted arrived too, and is paid explicitly: "two sets of behaviour to keep
identical" is real, and `test-fixtures/scene-walk/` is the answer to it. `SceneWalkGoldenTest` writes
the call sequence from one walk and `SceneWalkParityTests` asserts the other reproduces it, call for
call — which is how a divergence that both renderers' own tests passed was eventually found.

Recorded here because `docs/adr/README.md` says to amend a record rather than silently changing
behaviour that contradicts it, and this one read as flatly forbidding a module that had shipped.
