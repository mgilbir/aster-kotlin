# Host conformance

One golden per seam, and a reader on every host that implements it.

`scripts/host-parity.py` checks that a seam **exists** on each of the four public surfaces. It
cannot check that two engines **agree about what to do with it**, and that is where the defects have
been: four hosts carried `fontResolver` while three read a CSS stack three different ways (#123),
the resolver contract itself differed a fourth way — two engines asked for one name at a time and the
third handed over the whole stack — and `placement` centred a scene on two hosts while the third
pinned it to the padded top-left.

A signature cannot express any of that, so it is written down as behaviour instead.

## How it works

Each file here is a **transcript**: a case, and what an engine did with it, in a format that is the
same in Kotlin and in Swift. Every host's test suite reads the same file and asserts its engine
produces it. Agreement is transitive, so each side can be checked on whatever machine can run it —
the Apple engine on a Mac, the Android one on a device, the Compose Multiplatform one anywhere — and
no run has to have all three.

This is the shape `test-fixtures/scene-walk` already uses for the two scene walks, including the
lesson it learned: two recorders drift, so the recorder is written *once per side* with the only job
of being byte-identical, and one golden sits between them.

## The format

A line per case:

    <input> -> <observation> | <observation> | ...

Written by hand rather than generated, because a golden nobody reads is a golden nobody checks. When
one changes, the diff is the review: a line that moves means an engine now does something different,
and the question is whether every other engine moved with it.

## What is covered

| Golden | The seam | Read by |
| --- | --- | --- |
| `font-stack.txt` | how a CSS font stack is walked, and what reaches the host's resolver | `AndroidTextEngine`, `ComposeTextEngine`, `CoreTextTextEngine` |
| `image-resolver.txt` | when an `imageResolver` is asked for a url, and how often | `AndroidCanvasSceneRenderer`, `DrawScopeTarget`, `CoreGraphicsTarget` |
| `placement.txt` | where a scene is drawn inside a slot: the fit scale and the offset | `VegaChartView` ×2, `VegaChartView` (SwiftUI) |

Three engines, not four. `vega-compose` hosts `VegaChartView` through `AndroidView` rather than
drawing anything itself, so the Android reader covers both Android surfaces.

## The two things that keep this honest

**`scripts/host-conformance.py`**, run by `check.sh`, checks that every golden here is read by every
engine. A golden wired to one host is worse than no golden — the file exists, the suite is green, and
the disagreement is exactly as invisible as before — and nothing else can notice, because a missing
test has no signature.

**The goldens are declared as task inputs** in the root `build.gradle.kts`. Found the hard way: a
deliberately broken `placement.txt` was saved and `jvmTest` reported *up to date*, so the suite whose
whole purpose is catching a host disagreeing said nothing at all.
