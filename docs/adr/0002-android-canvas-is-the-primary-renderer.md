# 0002. Android Canvas is the primary renderer

Status: accepted (2026-08-06)

## Decision

Charts are drawn by `AndroidCanvasSceneRenderer` onto the `Canvas` a custom `View` receives in
`onDraw`. One surface per chart. No NDK, no direct Skia bindings, no OpenGL or Vulkan backend.

## Why

`Canvas` is hardware-accelerated through the platform's own Skia pipeline, so we get GPU rasterization
without owning a GPU backend. It also already supports everything the mark set needs: paths,
gradients, dash effects, clipping, blend modes, bitmaps and text.

The alternatives all cost more than they return at this stage:

- **NDK / direct Skia.** A second toolchain, ABI splits, and a much harder debugging story, in
  exchange for a pipeline the platform already runs for us.
- **OpenGL / Vulkan.** We would have to implement path tessellation, text rasterization and antialiasing
  ourselves. Justified only for scenes far larger than our targets, and profiling has not shown a need
  (ADR 0009).
- **A view per mark.** Layout, measurement and accessibility costs scale with mark count, which is
  exactly wrong for a chart with 100,000 points.

Canvas also makes the export story fall out for free: `Bitmap`, `PdfDocument` and the live view all
accept a `Canvas`, so one renderer serves all three and exported geometry cannot drift from what is on
screen.

## Consequences

- The renderer must reuse its `Paint`, `Path` and `Matrix` instances, because `onDraw` runs per frame.
  It does, and it is therefore not thread-safe: one renderer per surface.
- Blend modes below API 29 are limited to the PorterDuff subset. Anything outside it reports
  `VEGA_RENDER_UNSUPPORTED_BLEND_MODE` rather than being silently approximated.
- Text goes through `TextPaint`/`StaticLayout`, which is the platform's text stack, not the browser's.
  See [0006](0006-text-measurement-and-font-policy.md).

## Revisit if

Profiling on physical hardware shows Canvas cannot hold 60 fps while panning a precompiled
10,000-mark scene (ADR 0012). Even then, the first responses are scene-level caching and
culling, not a new backend.
