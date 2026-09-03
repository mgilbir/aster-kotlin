# 0009. Drawing allocates nothing per frame, and the core carries no Android imports

Status: accepted (2026-09-01)

Recorded from the retired design brief, §4.2–4.6 and §8.2 which this replaces. The rules were
already being followed and cited from thirty-odd files; this is where they now live.

## Decision

**One drawing surface per chart, not one per mark.** No `View`, Composable, `Drawable`,
`RenderNode` or accessibility node is created per mark. A chart is drawn through a single custom
surface — `onDraw` on the Android View, a `DrawScope` under Compose, a `CGContext` on Apple.

**Nothing is allocated per frame after warm-up.** Scene compilation, JSON parsing, data
transformation, text layout, path parsing and large object construction all happen *before* the
frame. The draw method consumes an already-compiled scene snapshot and does arithmetic on it.

**The core has no Android imports.** Specification parsing, expression execution, dataflow,
transforms, scales, signals, the scene graph, geometry, the hit-test index, SVG generation and
canonical snapshot serialisation are plain Kotlin. Android types appear only in Android modules.

## Why

The three rules are one rule seen from three sides, and the reason is the same each time: a chart is
redrawn on every frame of a pan, and anything done per frame is done sixty times a second.

A view per mark makes a ten-thousand-mark chart a ten-thousand-node hierarchy, which is a layout and
measure pass per frame over objects that never move relative to each other. Allocating during a draw
hands the collector work proportional to mark count at exactly the moment the frame budget is
already spent. And an Android import in the core is not a portability inconvenience — it is what
decides whether the same scene compiler can run on Apple and Linux at all, which is what makes the
differential corpus meaningful on more than one host.

The allocation rule is the one that is easiest to break by accident, because breaking it is
invisible on a small chart and only shows as jank at scale.

## Consequences

- A renderer that needs per-mark state computes it during scene compilation and stores it on the
  node, not in `onDraw`.
- Accessibility is a **virtual** hierarchy over the scene rather than real views; see the
  accessibility node provider on the Android side.
- The core's portability is checked by compiling it for five targets, not by grep — a
  `LinkedHashMap` subclassed for access order is a JVM-only API that no search for `java.` finds,
  and it sat in the text-layout cache until `compileKotlinMacosArm64` failed on it.
- The per-frame rule is asserted where it can be: the benchmark set in
  [0012](0012-performance-targets-and-the-benchmark-set.md) records allocation, and a regression
  shows there rather than in a review.
