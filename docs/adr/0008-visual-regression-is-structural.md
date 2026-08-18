# 0008. Visual regression is checked structurally, not in pixels

Status: accepted (2026-08-18)

## Decision

A chart's appearance is guarded at three levels, and **none of them is a reference PNG**:

1. **The scene graph, against upstream.** Every fixture is compiled by this engine and by the pinned
   `vega@6.3.1`, and the two scenes are compared mark by mark and scale by scale at `1e-6`. That is the
   differential corpus — 193 Vega fixtures and 282 Vega-Lite ones — and it is what says a chart is
   *right* rather than merely unchanged.
2. **Canonical snapshots and SVG goldens, against this repository's own last answer.** `SceneSnapshot`
   serialises a scene to text and `SvgRenderer` writes the same scene out as SVG; both are committed
   and diffed. A rendering change appears as a diff somebody chose to accept, and — crucially — as a
   diff a reviewer can *read*.
3. **Sampled pixels, per renderer.** `DrawScopeTargetTest` and `ComposeDensityTest` rasterise with
   Compose's own Skia and assert facts about specific pixels and ink extents: this pixel is
   steelblue, the bounding box of the ink is three times as wide at 3× density, the gradient is not
   black. The Swift renderer's `RecordingTarget` does the same job by asserting the sequence of draw
   calls, and `CoreGraphicsTargetTests` samples a bitmap.

A host adopting this engine can do the same: assert on the **scene** it gets back, and on the SVG, and
sample pixels where a specific claim is worth making.

## Why not golden images

Rasterisation is not ours. Antialiasing, hinting, subpixel positioning and font fallback belong to
Skia, CoreGraphics and the platform's font stack, and all four change between OS and toolchain
releases. A byte-exact golden therefore fails on upgrades that broke nothing, and the pressure that
follows is always to loosen the comparison — a threshold, a blur, a percentage of differing pixels —
until the gate no longer says anything a reader would care about. A gate that cannot fail meaningfully
is worse than no gate: it costs the same and reports success.

The three levels above fail for reasons that can be *named*. "Upstream puts this bar at 62.5 and we put
it at 61" is a bug report. "1.7% of pixels differ" is an argument.

An adopter asked whether we had a scheme stable across OS releases and said their own iOS gate is
pixel-exact snapshots pinned to one device and one OS version, with a recorded history of a development
machine disagreeing with CI. That history is the argument in miniature, and it is why this repository
does not have that gate.

## Consequences

- A defect that is *only* visible in rasterisation — a wrong blend mode on one Skia version, a font
  that falls back differently — is not caught here. Two things narrow that gap: the sampled-pixel
  tests, which cover the claims worth making about a fill or a gradient; and the instrumented and UI
  tests, which run on a device and a simulator and are where TalkBack and VoiceOver are actually
  exercised.
- Reviewing a rendering change means reading a scene snapshot or an SVG diff rather than looking at two
  images. In practice that is an advantage: the diff names the mark and the property.
- A host that wants a screenshot gate anyway should pin one device and one OS version, expect to
  re-record on upgrades, and treat the images as **documentation of a decision** rather than as a
  correctness gate — the correctness gate is the scene.

## Revisit if

A rendering defect reaches a release that all three levels missed and a pixel comparison would have
caught. Then the question is not "add goldens" but "what claim about pixels do we want to make", and
the answer is another sampled assertion in the renderer's own test — the same shape
`ComposeDensityTest` has, which was written after exactly that argument about density.
