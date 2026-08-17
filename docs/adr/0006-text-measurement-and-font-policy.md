# 0006. Text measurement and font-compatibility policy

Status: accepted (2026-08-06)

## Decision

Text measurement is part of chart layout, exposed through the platform-neutral `TextEngine`
interface. The Android implementation uses `TextPaint`, `Paint.FontMetrics` and `StaticLayout`, and
**the same engine measures and draws**. Tests compare structural geometry tightly and glyph bounds
loosely. Pixel-perfect parity with browser Vega is an explicit non-goal.

## Why

Label positions depend on text size, so measurement has to happen during scene compilation, before
anything is drawn. That rules out measuring inside `onDraw` and rules out a renderer that measures
differently from the layout that positioned the text.

Using two engines — say, an estimate for layout and the platform for drawing — guarantees labels that
do not sit where the layout put them, with the error varying by font, locale and device. Using one
engine makes the failure mode impossible.

Matching a browser exactly is not achievable and not worth chasing. Android and a browser ship
different fonts, hint differently, and shape differently; even two browsers disagree. What matters is
that a chart is correct and readable on the device, and that the *structural* numbers — axis
positions, mark coordinates, scale outputs — match upstream Vega closely, because those are the
engine's actual behaviour. Glyph advances are the platform's business.

`MetricTextEngine` exists as a deterministic, platform-independent engine for JVM tests: it produces
stable measurements so scene snapshots and SVG goldens are comparable across machines. It deliberately
does not match any real font, and nothing outside tests and headless SVG export should use it.

## Consequences

- `TextLayoutCache` keys on the full `TextStyle` plus constraint. Adding a field that affects
  measurement automatically widens the key, which is why the style type and the cache key are the same
  type.
- JVM golden scenes carry `MetricTextEngine` measurements; on-device rendering differs in label widths.
  Both are correct for their purpose, and the sample scenes take a `TextEngine` so either can be used.
- `AndroidTextEngine` owns a mutable `TextPaint` and is not thread-safe. If scene compilation moves off
  the main thread this needs an explicit answer — it is listed as pending in STATUS.md.
- Differential tests need two tolerance classes: tight for numeric layout and structure, wider for text
  bounds (PROJECT_BRIEF.md 18.4).

## Revisit if

Label collision handling (Milestone 5) needs measurement at a granularity `StaticLayout` cannot give,
for instance per-glyph bounds for rotated tick labels.
