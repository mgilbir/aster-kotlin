# AsterVegaRender

A renderer for this engine's scenes written in **Swift**, with no Kotlin on the drawing side and no
Compose anywhere. The engine compiles a specification to a scene; this package walks that scene and
draws it into CoreGraphics.

```
swift/AsterVegaRender
├── DrawTarget.swift          the protocol a surface implements, and the vocabulary it is spoken to in
├── SceneWalk.swift           the whole of the renderer's logic — what to draw, in what order
├── CoreGraphicsTarget.swift  a CGContext
└── RecordingTarget.swift     the draw calls as text, which is what the tests assert
```

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
