# Architectural decision records

One file per decision, numbered in the order they were taken. A record states the decision, why it
was taken, what it costs, and what would make us revisit it.

Amend a record rather than silently changing behaviour that contradicts it. A record's **Status**
line is the first thing to change, and the table below carries the same word — 0003 and 0005 were
each contradicted by a shipped module for several releases while still reading as plainly accepted,
which is what the rule exists to prevent and what nothing checked.

| ADR | Decision |
| --- | --- |
| [0001](0001-svg-is-not-the-runtime-model.md) | SVG is an output format, not the runtime scene model |
| [0002](0002-android-canvas-is-the-primary-renderer.md) | Android Canvas is the primary renderer |
| [0003](0003-compose-hosts-the-view.md) | The Compose API hosts the canonical View — amended: Compose Multiplatform draws on a `DrawScope` |
| [0004](0004-double-internally-float-at-the-boundary.md) | Calculations use Double; Float only at the Android boundary |
| [0005](0005-vega-lite-compilation-is-out-of-scope.md) | Vega-Lite compilation is outside the first release — **superseded**: the `vega-lite` module ships |
| [0006](0006-text-measurement-and-font-policy.md) | Text measurement and font-compatibility policy |
| [0007](0007-scene-identity-and-incremental-updates.md) | Scene identity and incremental update policy |
| [0008](0008-visual-regression-is-structural.md) | Visual regression is checked structurally, not in pixels |
