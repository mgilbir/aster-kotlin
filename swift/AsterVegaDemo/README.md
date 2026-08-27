# AsterVegaDemo

An iOS app that compiles Vega specifications with this engine and draws them with the Swift renderer.
SwiftUI, CoreGraphics, CoreText — no Compose and no Kotlin on the drawing side.

```sh
scripts/ios-demo.sh            # build, install and launch on a simulator
scripts/ios-demo.sh --check    # compile both slices; needs no simulator runtime
scripts/ios-demo.sh --device   # build for a connected device
```

Two launch arguments make the app scriptable, because `simctl` can launch an app but cannot tap a row or
drag a slider:

```sh
xcrun simctl launch AsterVega-iPhone dev.aster.vega.demo -chart bar-line-toggle -signal "DataPoints=200"
```

`-chart` opens straight onto a chart (or `•paste` for the paste screen), `-signal name=value` presets a
bound signal, `-spec <path>` renders a specification from a file, and `-tap x,y` taps the chart at a point
in **scene** coordinates once it has been placed. Between them a screenshot of a
chart *under* a control, or of pasted JSON, is scriptable — the simulator cannot tap a button, and
reading the clipboard raises a system permission alert that cannot be dismissed from the command line
either.

## What it shows

**Paste a specification** — the first row — takes your own Vega JSON from the clipboard or typed in,
compiles it on the device, and draws it with whatever controls it declares. It starts with a bar chart
that has a slider, a drop-down and a checkbox wired to signals, so the interesting half of the screen is
visible before you paste anything.

Below that, the repository's own differential fixtures. Tapping one gives the chart, its size in points,
its mark count — and **everything the engine could not honour**, listed with the severity and the path
into the specification.

## Controls

A `bind` in a specification is a *description* of a control, not an implementation of one: upstream
bolts the two together in `bind.js` and only the DOM half belongs to a browser. So the engine reports
the four shapes and this app draws them as a `Toggle`, a `Slider`, a `Picker` and a `TextField`.

Moving one **recompiles** the specification with the reader's value through
`compileJson(json:signalOverrides:)`. That is the honest simple thing rather than nudging a live
dataflow: a bound signal can change a scale's domain, a transform's parameter or the data itself, and a
fresh compile is right in all of those cases without any of them having to be argued about. These
specifications compile in milliseconds.

Two of the bundled fixtures declare bindings — `bar-line-toggle` has a slider that switches the chart
from bars to a line, and `donut-chart-labelled` has one for its start angle — so the same path is
exercised outside the paste screen.

Values cross the boundary through `ForeignSignals`, which exists for the same reason `ForeignPaint`
does: every interesting `VegaValue` is a `@JvmInline value class` and so is absent from the generated
header. Before it, a host could draw a slider and had no way to say where the reader had put it.

## Data

A specification's `"url": "data/cars.json"` resolves the way a browser would resolve it against the page
serving the example: **locally first, then from `https://vega.github.io/vega/`**. The repository's own
`test-fixtures/data` is bundled by reference — the Xcode project points at it rather than copying, so
nothing is duplicated in git — and anything not in there is fetched from Vega's site and cached.

`VegaDataLoader` lives in the renderer package, so the app and the package's own tests use one
implementation with one policy. And the policy is most of it: `DataLoader` denies by default because a
specification is *data*, often data a reader pasted, and a `url` in it asks this process to fetch an
address **the specification chose**. Left open that is a server-side request forgery primitive —
`http://169.254.169.254/` reads cloud credentials on an instance, `http://localhost:8080/` reaches
whatever else the host is running. So:

- one allowed prefix, `https://vega.github.io/vega/`, and every other absolute URI is refused;
- `..` in a path is **refused rather than normalised**, which is stricter and easier to be sure about;
- absolute paths are refused, so a specification cannot ask for `/etc/passwd`.

All of that is in `sanitize`, which does no I/O and is therefore tested on its own — the engine splits the
interface that way precisely so the decision can be checked without a fetch.

Two consequences worth knowing:

- **Compilation happens off the main thread.** `DataLoader.load` is synchronous, so a compile can block
  on a fetch; blocking the main thread would freeze the app on exactly the specifications that make it
  interesting.
- **The loader caches.** A bound signal recompiles the whole specification on every change, so without a
  cache a slider over a remote dataset would refetch per frame.

## Touch

Tap, long press, drag to pan, pinch to zoom, and pointer hover where a pointer exists. The same
vocabulary the Android view dispatches, because a capability that works on one host and not another is a
gap in the host rather than a property of the platform.

A drag that stays within a few points is a tap and one that travels is a pan — the same distinction
Android's `GestureDetector` makes. Pan deltas and pinch factors are sent **incrementally**: the controller
adds and multiplies them, so handing over a gesture's cumulative value on every change would accelerate
the pan and compound the zoom. A "Reset view" button appears once the chart has been moved, read from the
controller's own interaction state rather than tracked here.

Hover is wired even though a finger has none: iPad has pointers, and `onContinuousHover` reports them.
Where there is genuinely no pointer the gesture never fires, which is the platform's limit rather than
this app's.

A tap goes through `VegaChartController` into the compiled dataflow: hit-tested against the scene, run
through whatever `on` handlers the specification declared, and read back out as a new scene. That is why
this app uses the controller rather than `SpecCompiler` directly — a specification's handlers only exist in
a running dataflow, so a host that recompiled from JSON on every tap would discard the state each tap had
just created. Setting a bound signal takes the same route now, which is both cheaper than a recompile and
what a specification describes.

Two details are the host's responsibility, and both have bitten this project once:

- **`contentScale`.** The controller divides an incoming point by it, so a chart drawn scaled-to-fit whose
  host sent raw view coordinates would miss every mark by exactly the fit factor. `VegaChartView` computes
  its placement once and uses it for both the drawing and the touch inversion, because two copies of that
  arithmetic is how a tap lands next to the bar it looked like it hit.
- **Serialisation.** The controller is not safe for concurrent use, and the compile deliberately runs off
  the main actor. Dispatching a tap mid-compile left the chart stuck showing "no scene"; touches now queue
  behind any compile in flight, which is also better behaviour — a tap during a slow remote load lands when
  the chart appears rather than being dropped.

What was touched is read back from the chart's own interaction state and shown under it, so a tap that
reached the dataflow is visible rather than merely believed.

## Accessibility

Each mark the engine marked focusable becomes a VoiceOver element with its own label, its own frame and its
own activation — so a chart can be explored by swiping through it rather than announced as "image".
Activating an element selects the mark, which drives the same handlers a finger does.

The elements come from `AccessibilityTree` in `vega-scene`, not from this app. Which marks are worth
announcing, in what order, and when a dense chart becomes a single summary instead of an unusable list, are
statements about screen readers rather than about a platform. Those rules used to live inside Android's
`ExploreByTouchHelper` subclass, which is precisely why iOS had none: they were in a class no other host
could reach. Android now reads the same tree, so the two cannot describe the same chart differently.

**Verified through the system**, by UI tests: `scripts/ios-demo.sh --test`. Each bar is asserted to be its
own element labelled with its datum ("Apr: 91"), the axes to describe themselves, and activating an element
to select the mark it stands for.

Those tests earned their place twice over. The first version of them passed with the whole accessibility
block deleted, because they asserted on labels a navigation bar also provides — so they now assert on labels
only the engine produces. And with real assertions they found two defects: an activation that applied the fit
scale twice and therefore selected nothing, and — more interesting — that `accessibilityChildren` produces
elements whose frame is `(inf, inf, 0, 0)`. A reader could have swiped through the chart but never touched it,
which is most of what makes a chart explorable. The elements are positioned overlay views now, with hit
testing off so they cannot intercept a finger.

## Export

SVG, PNG and PDF, from the chart on screen, through the system share sheet.

The three come from two places and the split is deliberate. **SVG is the engine's** — `vega-svg`'s `toSvg`,
the serializer the differential harness compares against upstream — so an exported file is markup this
project has verified rather than a second opinion written in Swift. **PNG and PDF are the platform's**, drawn
through the same walk and target that put the chart on screen, which is what makes an export look like what
the reader saw. The PDF is drawn rather than rasterised, so its text stays text at any zoom.

Exporting happens off the main thread: a PNG at scale 3 is the renderer drawing the whole scene again.

The only thing that had ever stopped this on iOS was one line of build configuration — `vega-svg` was not on
the framework's export list, so a serializer that already compiled for `iosArm64` was unreachable from
Swift.

## Diagnostics

Listing what the engine could not honour is the point rather than decoration. This engine's discipline is
that nothing is silently ignored, and these screens are the only place that claim is visible to someone
who is not reading the test suite.

The bundled specifications all carry their data inline. The app installs **`VegaDataLoader`**, not
`DenyLoader`: a `url` is resolved against the datasets in the app bundle first and fetched from
`https://vega.github.io/vega/` when it is not there, so a pasted example from Vega's own gallery
works without the app shipping every dataset Vega has. Every other absolute address is refused, and
so is every private or loopback one, because a pasted specification chooses that address and this
screen compiles pasted text — see `VegaDataLoader`'s own note on request forgery.

Both this paragraph and the caption on the paste screen used to say the app had no network loader at
all, which was true of neither. The claim mattered: a reader deciding whether it was safe to paste
someone else's chart was being told the wrong thing about what that would fetch.

## How it is wired

The app links `AsterVega.xcframework`, assembled by
`./gradlew :vega-runtime:assembleAsterVegaDebugXCFramework`. It carries both slices, device and
simulator: one framework directory cannot hold both, and an app that picks between two directories by
SDK links the wrong one eventually.

The renderer's sources are **compiled into the app target** rather than linked as a Swift package. That
is deliberate: `swift/AsterVegaRender/Package.swift` points its framework search path at the *macOS*
framework so that `swift test` works, and an iOS build cannot use that. Compiling the five files
directly keeps one copy of the renderer and no second manifest to keep in step.

`VegaChartView` — in `AsterVegaRender`, not here — draws into a SwiftUI `Canvas` through
`withCGContext`. **It does not flip the context** —
SwiftUI's canvas already has its origin at the top left with y growing down, which is the space a scene
is in. The package's bitmap tests *do* flip, because a bare `CGBitmapContext` has its origin at the
bottom left. That difference belongs to the caller, which is exactly why the renderer flips nothing
itself.

## Text

Labels are measured with **CoreText**, the same font that draws them, through `CoreTextTextEngine`. That
matters more than it sounds: this app first shipped measuring with the portable ratio-based engine — whose
advance widths are a fixed fraction of the font size and match no real font — and then drawing with
CoreText, so every box the layout reserved was the wrong width and the axis numbers sat on the axis line.

The engine's own documentation says it plainly: *the same implementation must be used for measuring and
for drawing.* `CoreTextTextEngine` is a Swift subclass of `MeasuredTextEngine`, which owns the layout and
asks only how wide a string is — so the platform engine is three measurements and no second layout.

## The project file

`AsterVegaDemo.xcodeproj` is hand-authored and minimal: one target, three build phases, no asset
catalog, `GENERATE_INFOPLIST_FILE = YES` so there is no plist to maintain. **A new source file has to be
added to the Sources build phase**, which is the one chore this buys — a file merely sitting in
`Sources/` compiles under `--check` and then fails the real build with "cannot find X in scope" — which
is exactly how the controls first failed to build.

It opens in Xcode and builds with `xcodebuild -scheme AsterVegaDemo`. Xcode will want to add things to it
the moment you edit it in the IDE; that is fine, but the checked-in file is meant to stay readable.
