# AsterVegaRender

A renderer for this engine's scenes written in **Swift**, with no Kotlin on the drawing side and no
Compose anywhere. The engine compiles a specification to a scene; this package walks that scene and
draws it into CoreGraphics.

```
swift/AsterVegaRender
├── VegaChartView.swift       a SwiftUI view: the drawing, the gestures and the VoiceOver overlay
├── ChartSession.swift        one chart being looked at — the compile, the controls, the touches
├── DrawTarget.swift          the protocol a surface implements, and the vocabulary it is spoken to in
├── SceneWalk.swift           the whole of the renderer's logic — what to draw, in what order
├── CoreGraphicsTarget.swift  a CGContext
├── CoreTextTextEngine.swift  measures text with the font that draws it
└── RecordingTarget.swift     the draw calls as text, which is what the tests assert
```

## Using it

```swift
import AsterVegaRender
import SwiftUI

struct MeasurementChart: View {
  let specification: String
  @State private var session = ChartSession()

  var body: some View {
    Group {
      if let scene = session.scene {
        VegaChartView(scene: scene, session: session)
      } else if let failure = session.failure {
        Text(failure)
      }
    }
    .task { session.load(specification: specification) }
  }
}
```

`ChartSession` compiles either grammar off the main thread, publishes the scene, exposes the
specification's own bound controls, and reports what a touch found. `VegaChartView` draws it, answers
taps, long presses, pans, pinches and a hovering pointer, and lays a **positioned** VoiceOver element
over every mark, axis, legend and title.

Both were in the demo app until an adopter pointed out what that costs: about 2,400 lines of this
renderer owned by hand, including the parts that took real bug fixes to get right — the aspect-fit
arithmetic shared between drawing and hit testing, the drag-as-tap slop, the `@MainActor` isolation with
a serialised queue behind it because the controller is not safe for concurrent use, and the positioned
accessibility overlay, which exists because `accessibilityChildren` yields frames of `(inf, inf, 0, 0)`
that a reader cannot touch. They need Observation and SwiftUI, so they are `@available(macOS 14, iOS 17)`;
everything else in the package keeps the manifest's own floor.

## Running the tests

```sh
scripts/swift-test.sh
```

The framework the package links is produced by Kotlin/Native and SwiftPM knows nothing about Gradle,
so that script links it first. `swift test` on its own fails with a missing-module error in a clean
checkout.

## How the boundary works

Kotlin exports an Obj-C framework, and that boundary is narrower than Kotlin. The one narrowing that
matters is `ScenePaint.Solid`: it is a `@JvmInline value class`, and a value class implementing an
interface has **no Obj-C representation at all** — it is absent from the generated header, so from
Swift a fill is an opaque `ScenePaint` with no way to ask its colour. Nearly every fill in a chart is
a solid one.

Rather than reshape the scene for one platform, `ForeignRenderers.kt` gives the questions a renderer
asks plain functions: `ForeignPaint.solidFill`, `ForeignPath.kind`, `SceneNode.foreignKind()`. Those
cross the boundary, and Kotlin renderers can use them too.

## What the tests check, and what they do not

`SceneWalkTests` compiles real specifications through the engine and asserts the **sequence of draw
calls** — a recording, which is comparable and printable and needs no simulator.

`CoreGraphicsTargetTests` renders into a bitmap and samples pixels, because the recording tests never
execute a line of CoreGraphics. They do not compare against committed golden PNGs: antialiasing and
rasterisation belong to the platform and change between OS releases, so a byte-exact golden would fail
on an upgrade that broke nothing and the pressure would then be to loosen it.

The pixel tests earned their place immediately. They found two bugs the recording tests could not see:

- **Group opacity was being inherited.** Upstream's canvas renderer never sets `globalAlpha` on the
  way into a group, and its SVG renderer emits `opacity` on the group's background path while leaving
  the child element bare. A group's opacity paints its own panel and nothing else. This renderer
  propagated it and drew a half-opaque group's opaque child at half.
- **Every colour was slightly wrong.** `CGColor(red:green:blue:alpha:)` builds a colour in generic
  RGB, not sRGB, and the context converted it on the way in — `steelblue` landed as rgb(86,149,193)
  instead of rgb(70,130,180). Too small to notice by eye, and a different colour.

## Gradients

`CoreGraphics` has no gradient *fill*: a gradient is drawn across a region, so the region has to be the
clip. `CoreGraphicsTarget` clips to the mark's path and draws the gradient through it — and for a
gradient-stroked mark, clips to `replacePathWithStrokedPath` instead, which turns the pen into a region.

`drawsBeforeStartLocation`/`drawsAfterEndLocation` are what make this agree with every other renderer: a
gradient's stops describe the span between its two points, and the area outside that span takes the
nearest stop's colour rather than nothing.

The *coordinates* are resolved in the walk, not here. A specification writes a gradient in fractions of
the item it fills — `x1: 0, x2: 1` is left edge to right edge whatever the mark's size — so the walk
multiplies them through the item's bounds and a target receives absolute points. The Compose renderer
splits it the same way.

## Images

Two sources, because a scene has two.

Most images are a **URL** the specification gave, and resolving one is the host's business: a chart is
often data a reader pasted, so the address in it is the specification's choice and the policy about
following it belongs to the host — the same argument `DataLoader` makes for data. `CoreGraphicsTarget`
takes a `resolveImage` closure and draws nothing without one, collecting the URLs it could not answer in
`unresolved` so a caller can say so rather than leave a silent hole. A `data:` URL needs no host and is
decoded here.

The other source is the one nobody thinks about: a **`heatmap`** or an **`isocontour`** builds its image
inside the engine and carries the pixels on the node, with no address at all. A renderer that only
understood URLs dropped every one of them — and a chart missing its raster looks like a chart whose
transform did nothing. That was true of `AndroidCanvasSceneRenderer` as well, which is fixed alongside
this.

The pixels cross the boundary as a PNG data URL from the engine's own `PngEncoder` — one call instead of
120,000. A `KotlinIntArray` is read element by element from Swift, so a modest heatmap would otherwise be
that many boundary crossings per frame. Decodes are cached by the raster's `digest`, which is stable for
identical pixels.

## Text

`CoreGraphicsTarget` takes an injectable `drawText` closure and draws no glyphs itself. The engine
positions a run and resolves its alignment; shaping it is the platform's job, and a caller without a way
to draw text gets no text rather than wrong text.

`CoreTextDrawing.draw` is that closure for Apple platforms, so a caller writes:

```swift
var target = CoreGraphicsTarget(context: context, drawText: CoreTextDrawing.draw)
```

It imports neither UIKit nor AppKit — CoreText attribute names throughout — so it is the same code on
iOS and macOS. It flips y locally around the run's anchor, because CoreText draws up from a baseline
while every coordinate this renderer produces is in a y-down space; without that the glyphs come out
mirrored, which is the classic symptom and worth naming.

`DrawTextRun` is named that way, rather than `TextRun`, because the engine exports a `TextRun` of its own
— the one a scene holds — and a file importing both modules could not otherwise say which it meant.

`CoreTextTextEngine` closes the loop: it measures with the same font, as a subclass of the engine's
`MeasuredTextEngine`, which owns the layout and asks only for advances, an ascent and a descent. Compile a
chart with it and the boxes the layout reserves are the boxes the glyphs fill. `CoreTextFonts` resolves a
specification's family/size/weight into a `CTFont` and is shared by the measuring and the drawing, because
measuring with one font and drawing with another is the same bug wearing a hat.

### Alignment is the walk's job

A scene gives a text node an anchor plus an `align` and a `baseline`; turning those into a pen position
needs the measured width, which the walk has from the layout and a target does not. So the walk emits **one
call per line**, already positioned, and a target draws from a pen position with no alignment logic at all.

That is not a stylistic choice — it is a bug fix. Before it, a right-aligned axis label was drawn
*rightwards* from its anchor instead of ending there, which put the numbers on top of the axis line, and
multi-line text drew only its first line because the walk never looked at `layout.lines`. Rotation turns
about the **anchor**, not the pen, or a rotated label swings away from its tick.
