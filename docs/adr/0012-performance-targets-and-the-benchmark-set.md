# 0012. Performance targets, the benchmark set, and what a missed target has to record

Status: accepted (2026-09-01)

Recorded from `PROJECT_BRIEF.md` §18.6 and §19 when that document was retired. These are the two
sections whose *content* would have been lost rather than merely their provenance: the benchmark
scenes and the required measurements are a list, and a citation to a deleted list is nothing.

## Decision

**Targets**, on a representative mid-range physical Android device. Engineering targets, not
compatibility guarantees:

- A static 10,000-mark chart renders within one display frame after scene compilation, where possible.
- Panning a precompiled 10,000-mark scene holds approximately 60 frames per second.
- Hit testing among 100,000 indexed points completes in under 4 ms.
- A hover or selection update that does not affect layout does not rerun the whole dataflow.
- Drawing allocates no significant per-frame heap after warm-up — see
  [0009](0009-drawing-allocates-nothing-per-frame.md).
- Scene serialisation and rendering stay deterministic.

**The benchmark scenes**: 100 bars; 1,000 bars; 10,000 symbols; 100,000 symbols; 1,000-, 10,000- and
100,000-point lines; 20 series with legends; dense labels; continuous pan and zoom; rapid signal
updates.

**Measured separately**, because an aggregate number hides which stage moved: JSON parsing, runtime
compilation, data transforms, scene generation, text layout, canvas draw, hit testing, SVG
serialisation, bitmap export, and allocation count.

**A missed target records** the fixture, device, Android version, build type, median, P90, P95,
allocation count, and a trace artefact.

## Why

The stage breakdown is the part that earns its keep. A chart that got slower is a useless
observation; a chart whose *text layout* got slower names the change that did it. The stages are
listed rather than left to judgement so that two runs a year apart are comparable.

**Emulator numbers are not authoritative, and that is not a style preference.** Android's own
guidance is that emulator resource sharing produces misleading measurements — the host's scheduler
is in the loop. A release threshold validated on an emulator is a threshold validated against
whatever else the laptop was doing. The instrumented suites run on an emulator in CI and prove
*correctness* there; performance is a separate claim needing separate hardware.

The recording list exists because a performance regression is argued about, and an argument needs
the same fields each time or it becomes a discussion about methodology.

## Consequences

- `benchmark/` holds the microbenchmarks for the stages above; a new stage in the pipeline is a new
  measurement, not a line item inside an existing one.
- CI does not gate on these numbers. It cannot: no runner is a representative mid-range physical
  device, and gating on a number the hardware does not support would make the gate meaningless
  rather than strict.
- The determinism target is checked by different machinery — the canonical snapshots in
  [0008](0008-visual-regression-is-structural.md) — because it is a correctness property that
  happens to appear in a performance list.
