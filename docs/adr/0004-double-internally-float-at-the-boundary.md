# 0004. Calculations use Double; Float only at the Android boundary

Status: accepted (2026-08-06)

## Decision

Scale outputs, transform results, dataflow values, geometry, scene coordinates, path commands and SVG
output are all `Double`. Conversion to `Float` happens only when calling into Android graphics APIs.

## Why

Determinism is a hard requirement: scene snapshots and SVG goldens are the project's main correctness
signal (PROJECT_BRIEF.md 18.2, 18.3). `Float` has about 7 decimal digits of precision, so an
accumulated chain of scale → transform → layout arithmetic can land on different values depending on
evaluation order, and a golden diff would then report noise instead of behaviour.

`Double` also matches upstream Vega, which computes in JavaScript numbers. Differential tests compare
our numbers against Vega's; using `Float` internally would introduce differences that have nothing to
do with our logic and would force tolerances wide enough to hide real bugs.

Android graphics APIs take `Float`, but that conversion happens once per drawn value, at the last
moment, where it cannot accumulate.

## Consequences

- Double the memory per coordinate. Irrelevant next to the object overhead of a scene node, and the
  brief explicitly defers this kind of optimization until profiling demands it.
- Every renderer has `.toFloat()` calls at its edges. Verbose but obvious, and it makes the boundary
  visible in the code.
- Non-finite values and `-0.0` need explicit normalization before serialization, since `Double`
  carries both. `canonicalNumberString`, `normalizeZero` and `finiteOr` in `vega-model` are the single
  place that happens.

## Revisit if

Allocation profiling on a 100,000-mark scene shows coordinate storage dominating. The response would
be packed primitive arrays inside the scene graph, still holding `Double` values — not a switch to
`Float`.
