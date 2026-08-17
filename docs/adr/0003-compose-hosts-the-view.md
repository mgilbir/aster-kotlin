# 0003. The Compose API hosts the canonical View

Status: accepted (2026-08-06)

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
correct (PROJECT_BRIEF.md 21).
