# vega-compose-multiplatform

A renderer that draws a compiled scene with **Compose's own `DrawScope`**, on Android, iOS and the
desktop. One traversal, one set of primitives, four platforms.

```
src/main/kotlin/dev/aster/vega/compose/mp
├── SceneDrawTarget.kt   the interface a surface implements, and the vocabulary it is spoken to in
├── SceneWalk.kt         the whole of the renderer's logic — what to draw, in what order
├── DrawScopeTarget.kt   Compose. The only file here that mentions a Compose type
├── RecordingTarget.kt   the draw calls as text, which is what most of the tests assert
└── VegaChart.kt         the composable
```

## Why the walk and the target are separate

`SceneWalk` decides *what* to draw; a `SceneDrawTarget` decides *how*. Everything above
`DrawScopeTarget` is plain Kotlin with no Compose in it, which buys two things:

- the walk is tested in `commonTest`, so it is **compiled for every target this module claims** —
  Android, both iOS targets, the JVM. A Kotlin/Native restriction the JVM does not have fails there
  rather than in a release. (It already has: Kotlin/Native rejects a comma in a test name.)
- the tests need no composition, no screen and no device, because none of the logic under test lives
  in the target.

It is deliberately the same shape as the Swift renderer's `DrawTarget` in `swift/AsterVegaRender`. Two
renderers that answer the same questions in the same order are two renderers that can be compared when
one of them is wrong — and one of them was.

## Relationship to `vega-compose`

`vega-compose` is Android-only: a Compose wrapper around `vega-android-canvas`, which draws with
`android.graphics.Canvas`. This module draws with Compose itself and runs everywhere Compose does. Both
are supported; a project already on Android and happy with the View-backed path has no reason to move.

There is no `macosArm64` target here — Compose Multiplatform treats native macOS as experimental and
refuses it without an opt-in flag. The desktop is the JVM target, which is where Compose's own desktop
support lives, and macOS has a Swift renderer in `swift/AsterVegaRender` instead.

## What the tests check

`SceneWalkTest` (`commonTest`) compiles real specifications through the engine and asserts the sequence
of draw calls: which primitives, with which geometry, in which order.

`DrawScopeTargetTest` (`jvmTest`) rasterises with `ImageComposeScene` and samples pixels. That closes
the gap the walk's tests leave — they never execute a line of Compose — and the gap is not
hypothetical. Two bugs were found in the Swift renderer by exactly this and one here:

**A `Canvas` with no intrinsic size drew solid fills and turned every gradient black.** A chart
therefore takes the scene's own declared width and height unless a caller's `modifier` overrides it,
which is what a specification declaring them is asking for. The gradient pixel test is the guard.

No golden images: rasterisation and antialiasing belong to Skia and change between its versions, so a
byte-exact golden would fail on an upgrade that broke nothing.

## Opacity is per item

A group's opacity paints its own panel and is **not** inherited by its children, and a group at zero
opacity still draws them — it is a group with no background, not an invisible subtree. That is
upstream's behaviour in both of its renderers, it is the opposite of the natural guess, and two of this
project's renderers guessed wrong before pixels said otherwise. See `SceneWalk`'s own notes.

## Text

`DrawScopeTarget` takes a `TextMeasurer` — `rememberTextMeasurer()` inside a composable, which
`VegaChart` does for you. The engine positions a run and resolves its alignment; shaping the string
into glyphs is the platform's job. Without a measurer, text is not drawn, which is better than text
drawn wrongly. A caller needing a particular font face supplies a measurer that knows it.
