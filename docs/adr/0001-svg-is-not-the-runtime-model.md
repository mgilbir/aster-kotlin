# 0001. SVG is an output format, not the runtime scene model

Status: accepted (2026-08-06)

## Decision

The engine's live representation of a chart is `dev.aster.vega.scene.Scene`: an immutable tree of
Kotlin data classes. SVG is produced by serializing that tree in `vega-svg`. No part of the runtime
holds, queries or mutates SVG elements.

## Why

Upstream Vega in a browser can lean on an SVG DOM because the browser already provides one: layout,
hit testing, accessibility and repaint all come for free. On Android there is no such DOM. Building
one would mean reimplementing a retained-mode graphics tree, an attribute system, and a
string-to-geometry parser, and then paying for all three on every frame.

A native scene graph is also what the rest of the system needs anyway:

- **Drawing** wants primitives that map directly onto `Canvas` calls, not attribute strings to reparse.
- **Hit testing** wants precomputed bounds and geometry, not `getBBox()` on a DOM node.
- **Layout** wants text metrics before anything is drawn, which an SVG string cannot provide.
- **Snapshots** want a canonical serialization we control, so a golden diff means a rendering change.

Keeping SVG on the output side means it can be canonical and deterministic — sorted attributes, fixed
numeric precision, sequential generated ids — without those constraints leaking into the runtime.

## Consequences

- Every node type needs a serializer in `vega-svg`. Adding a node type is two changes, not one.
- SVG-specific features that have no scene-graph equivalent (filters, markers, `use` elements) cannot
  be expressed. That is acceptable: Vega does not need them.
- The scene graph, not SVG, defines what the engine can draw. `SUPPORTED_FEATURES.md` tracks that.

## Revisit if

Never, for the Android renderer. If a future target is a browser (Kotlin/JS), the same scene graph
would still be the runtime model and the SVG serializer would simply be the fastest path to pixels
there.
