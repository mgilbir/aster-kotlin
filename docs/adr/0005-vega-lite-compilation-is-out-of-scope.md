# 0005. Vega-Lite compilation is outside the first release

Status: **superseded (2026-08-27) by the shipped `vega-lite` module.** See "What happened" below.
Accepted 2026-08-06.

## Decision

The engine accepts compiled Vega specifications. Vega-Lite is compiled to Vega upstream — by the
JavaScript implementation in `oracle-js`, or by the application before it hands the specification to
the runtime.

## Why

Vega-Lite is a large compiler, not a thin sugar layer: it infers scale types from data, resolves
defaults across a deep configuration hierarchy, expands faceting and layering, and generates the
axes, legends and transforms the author never wrote. Reimplementing it correctly is comparable in size
to the Vega runtime itself.

More importantly, it depends on the layer beneath it. A Vega-Lite compiler that emits Vega we cannot
execute is worthless. Getting the Vega runtime right first means the compiler, if we build it, has a
correct target and a way to be tested — compile with upstream, compile with ours, compare the emitted
Vega.

Meanwhile the cost of deferring is low: compiling Vega-Lite is a build-time or server-side step for
most applications, and the pipeline in PROJECT_BRIEF.md 3.1 has the native compiler as an optional
stage precisely so it can be added without disturbing anything below it.

## Consequences

- Applications with Vega-Lite specifications need a compilation step outside the library.
- `oracle-js/src/compile-vega-lite.js` exists so fixtures can be authored in Vega-Lite and compiled
  to Vega for testing.
- Vega-Lite-only conveniences (implicit scale-type inference, automatic axis titles) are not available
  through the native path.

## Revisit if

The Vega runtime passes its compatibility fixtures and applications are repeatedly blocked by not
having compilation on-device. The differential harness against upstream compilation would then be the
first thing to build.

## What happened

Both conditions were met and the decision was reversed. `vega-lite` is a shipped module:
`VegaLiteCompiler` compiles the grammar on-device and `VegaLiteInput.toVega` accepts either grammar
from a host that cannot know which it has been handed.

The "revisit if" clause turned out to be exactly right about the order, which is why it is worth
recording rather than deleting. The Vega runtime passed its fixtures first, so the compiler had a
correct target; and the differential harness against upstream compilation *was* the first thing
built — `scripts/vega-lite-oracle.sh` compiles each fixture with upstream and with this compiler and
compares the emitted Vega property by property, and `VegaLiteFixtureDifferentialTest` then compares
the two drawings. 283 fixtures match byte for byte.

What the record got wrong is the cost estimate underneath it: "comparable in size to the Vega
runtime itself" was an argument for never starting, and the module is about a third of it.

This section is here because `docs/adr/README.md` says to amend a record rather than silently
changing behaviour that contradicts it — and this one was contradicted by a shipped module for
several releases while still reading as accepted, which is the failure that rule exists to prevent.
