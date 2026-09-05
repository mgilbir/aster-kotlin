#!/usr/bin/env python3
"""Every seam, checked against every host's **recorded** surface.

The README carries a matrix of what each of the four public surfaces exposes. It was written by
hand, which makes it a claim rather than a check — and the two seams an adopter reported missing
(#99, #106) were both absent from a host while a table said the shape was deliberate.

So the matrix is derived here instead. Each capability names how it appears in each surface, and
the surfaces are the ones already snapshotted for other reasons:

    android-api.txt                     VegaChartView and the vega-compose composable
    vega-compose-multiplatform/api/     the Compose Multiplatform composable
    swift-api.txt                       the SwiftUI view and ChartSession

`.api` and `javap` record types and not parameter names, so a Kotlin composable is matched on the
type its parameter carries; Swift labels are matched by name. The mapping below is the part a human
reviews. Whether reality matches it is the part this checks.

A capability marked absent carries the reason, and a reason is not "nobody got round to it" — those
are the ones that turned into bug reports.
"""
import pathlib
import re
import sys

ANDROID = pathlib.Path("android-api.txt").read_text()
# The **klib** dump, not the JVM one: the JVM `.api` erases a lambda parameter to
# `Lkotlin/jvm/functions/Function1;`, so every seam on that composable looks identical and none can
# be told from another. The klib dump keeps `Function1<String, ImageBitmap?>`.
COMPOSE_MP = pathlib.Path(
    "vega-compose-multiplatform/api/vega-compose-multiplatform.klib.api"
).read_text()
SWIFT = pathlib.Path("swift/AsterVegaRender/swift-api.txt").read_text()

# The composable signatures, isolated so a marker cannot match some unrelated declaration.
VEGA_COMPOSE = "\n".join(
    line for line in ANDROID.splitlines() if "void VegaChart(" in line
)
MP_CHART = "\n".join(line for line in COMPOSE_MP.splitlines() if "VegaChart(" in line)


def block(text, header):
    """A javap class and its indented members, which is where the setters are.

    Matching the header line alone finds the class and none of its members, and every seam then
    reads as missing — which is what the first run of this said about a view that has all of them.
    """
    out, inside = [], False
    for line in text.splitlines():
        if not line.startswith(" "):
            inside = header in line
        if inside:
            out.append(line)
    return "\n".join(out)


# **The View's class block, plus the accessibility ids it declares.** `android-api.txt` is `javap`
# over the compiled classes and a resource id is not a class member — but an accessibility action id
# *is* part of what this library offers an assistive technology, and an app can name it. Without it
# the chart-action seam had no marker at all on the one host that owns the whole thing internally,
# and a row with no marker is a row that cannot fail.
ANDROID_VIEW = block(ANDROID, "dev.aster.vega.android.VegaChartView") + pathlib.Path(
    "vega-android-canvas/src/main/res/values/ids.xml"
).read_text()
# `clearImageCache` is a static on `CoreGraphicsTarget` rather than on the view, so the Swift
# surface has to be read whole rather than filtered to the two obvious types.
SWIFT_VIEW = SWIFT

#  capability: (android view, vega-compose, compose multiplatform, swiftui)
#  a string is a marker that must be present; None means "absent, and here is why" in REASONS.
SEAMS = {
    "image resolver": ("setImageResolver", "AndroidImageResolver", r"ImageBitmap\?>\?", "resolveImage:"),
    # A marker has to be specific enough to fail. `Function1` and `Int` are present on any of these
    # signatures, so a column matching them is green whatever the seam does — which two of these were
    # until the signatures were read rather than assumed.
    "unresolved image": (
        "setOnUnresolvedImage",
        r"AndroidImageResolver, kotlin\.jvm\.functions\.Function1<\? super java\.lang\.String, kotlin\.Unit>",
        r"ImageBitmap\?>\?, dev\.aster\.vega\.compose\.mp/ImageCache\?, kotlin/Function1<kotlin/String, kotlin/Unit>",
        "onUnresolvedImage:",
    ),
    "accessibility threshold": (
        "setAccessibilityMaxExposedMarks",
        r"Typeface>, int, boolean",
        r"VegaCaptions\?, kotlin/Int",
        "accessibilityMaxExposedMarks:",
    ),
    "font family": ("setFontResolver", "Typeface", r"ComposeTextEngine\?", "resolveFont:"),
    "engine-drawn tooltip": ("setTooltipsEnabled", "boolean", None, None),
    "placement reported": ("setOnPlaced", "ScenePlacement", r"Function1<dev\.aster\.vega\.scene/ScenePlacement", "onPlaced:"),
    "clear image cache": ("clearImageCache", None, r"ImageCache\?", "clearImageCache"),
    # The raw pointer stream — `mousedown`, `mousemove`, `mouseup` — which is what every brush and
    # interval selection in Vega is written against. It was on **no** host: Android emitted a down
    # and an up but never a move during a drag, and the other two surfaces had nothing at all. There
    # was no row here for it either, which is why nothing noticed; this file's own header is about
    # two seams that were absent from a host while a table said the shape was deliberate.
    #
    # The Android column is `onTouchEvent` and that is a **weak** marker on purpose, flagged rather
    # than dressed up: the View owns the pointer stream and dispatches it itself, so there is no
    # signature a host wires and nothing in the recorded surface distinguishes "handles ACTION_MOVE"
    # from "does not". What guards that is behavioural — `VegaInteractionInstrumentedTest` drags and
    # asserts the brush followed. This row's job is the other three columns, where the seam *is* a
    # signature.
    "pointer events": (
        "onTouchEvent",
        None,
        # Three `Function1<PointD, Unit>?` in a **row**, and anchored to nothing before them on
        # purpose. This marker was first written relative to `onHover`'s two-argument callback and
        # broke the moment two parameters were inserted between the two, reporting a seam missing
        # that was right there. A marker that depends on its neighbours fails for the wrong reason.
        # The klib dump records types and not names, so the run is what identifies them.
        r"(kotlin/Function1<dev\.aster\.vega\.scene/PointD, kotlin/Unit>\?, ){2}"
        r"kotlin/Function1<dev\.aster\.vega\.scene/PointD, kotlin/Unit>\?",
        r"pointerDown\(at:\)",
    ),
    # And the other half of making a brush usable: a host has to be able to stop the drag *panning*.
    # Both happen otherwise and they fight — the viewport slides under the finger by the distance
    # the finger travels, so in the chart's own coordinates the pointer never moves and the brush
    # selects a point. The Android instrumented test found exactly that, `[60, 60]` for a drag from
    # 60 to 200, the first time it ran.
    # The chart's **own** accessibility actions — zooming, resetting the view, putting an adjusted
    # axis back. They were offered by `VegaChartController` and wired by **no host at all**: built,
    # tested, documented against `AccessibilityNodeInfo.addAction` and `UIAccessibilityCustomAction`,
    # and the call was never written anywhere (#226). A reader could reach every bar in a chart and
    # never the view they were drawn in. There was no row here either, which is how it stayed
    # invisible while three of these columns grew other accessibility seams around it.
    "chart accessibility actions": (
        # The View owns its own node, so the seam is the delegate rather than something a host
        # wires; the ids it hangs the actions on are the marker, and they exist only for this.
        "aster_vega_action_zoom_in",
        # `vega-compose` hosts that View, so it inherits the node and everything on it.
        None,
        r"kotlin.collections/List<dev\.aster\.vega\.scene/ChartAction>",
        r"perform\(_:\)",
    ),
    # Entering the chart, apart from moving within it — `pointerover`/`mouseover`, which most
    # highlight and tooltip specifications are written against. Android emitted it from
    # `ACTION_HOVER_ENTER` and the other two could not: `ChartSession.hover(at:)` dispatched a move
    # for every point, and `onHover` fires for an entry and for every move after it with the same
    # shape, so a host could not tell them apart (#228).
    "pointer entered": (
        "onHoverEvent",
        None,
        # A fourth `Function1<PointD, Unit>?`, so the run the pointer row matches is now four long;
        # matched on its own rather than by position, for the reason recorded on that row.
        r"(kotlin/Function1<dev\.aster\.vega\.scene/PointD, kotlin/Unit>\?, ){3}"
        r"kotlin/Function1<dev\.aster\.vega\.scene/PointD, kotlin/Unit>\?",
        r"hover\(at:\)",
    ),
    # The keyboard. `VegaChart.kt` had **zero** references to `ChartKey`, `KeyEvent` or
    # `onKeyEvent`, so a specification's `keydown` handlers never fired and the engine's own
    # traversal between marks was unreachable — on the surface most likely to be running on a
    # desktop, where a keyboard is the primary input (#229).
    "keyboard": (
        "dispatchKeyEvent",
        None,
        r"kotlin/Function2<dev\.aster\.vega\.scene/ChartKey, dev\.aster\.vega\.scene/Modifiers, "
        r"kotlin/Unit>\?",
        r"press\(_:modifiers:\)",
    ),
    # An **adjustable axis**: narrowing and widening the interval it draws its data against. Android
    # and Apple have had it since it was written and Compose Multiplatform had not, so a reader
    # there could reach an axis and not change it (#230).
    "adjustable axis": (
        "aster_vega_action_narrow_axis",
        None,
        r"kotlin/Function2<kotlin/String, kotlin/Boolean, kotlin/Unit>\?",
        r"adjustScaleDomain\(scale:narrow:\)",
    ),
    "viewport pan is optional": (
        "setPanEnabled",
        # `int, boolean, boolean` — the accessibility threshold, then `tooltipsEnabled`, then this.
        r"Typeface>, int, boolean, boolean",
        # Passing `onPan = null` is how this renderer says it: the parameter is nullable and the
        # detector is not attached when nothing wants it.
        r"Function2<dev\.aster\.vega\.scene/VectorD, kotlin/Boolean, kotlin/Unit>\?",
        # `ChartGestures` without `.pan`, which is what `withoutDrag` already is.
        r"static let withoutDrag",
    ),
}

REASONS = {
    ("adjustable axis", "vega-compose"): (
        "hosts VegaChartView through AndroidView, so it inherits that View's accessibility nodes "
        "and the axis actions on them — there is nothing for the composable to expose"
    ),
    ("keyboard", "vega-compose"): (
        "hosts VegaChartView through AndroidView, which handles key events itself — there is "
        "nothing for the composable to expose, and the capability is the View's"
    ),
    ("pointer entered", "vega-compose"): (
        "hosts VegaChartView through AndroidView, which owns the hover stream itself — there is "
        "nothing for the composable to expose, and the capability is the View's"
    ),
    ("chart accessibility actions", "vega-compose"): (
        "hosts VegaChartView through AndroidView, so it inherits that View's own accessibility "
        "node and every action on it — there is nothing for the composable to expose"
    ),
    ("pointer events", "vega-compose"): (
        "hosts VegaChartView through AndroidView, which owns the pointer stream itself — there is "
        "nothing for the composable to expose, and the capability is the View's"
    ),
    ("engine-drawn tooltip", "compose-mp"): "paints a Scene and owns no tooltip; a host draws its own",
    ("engine-drawn tooltip", "swiftui"): "same — the session publishes the datum and the host presents it",
    ("clear image cache", "vega-compose"): "no handle on the view; passing a different imageResolver rebuilds the renderer",
}

HOSTS = ("android-view", "vega-compose", "compose-mp", "swiftui")
SURFACES = (ANDROID_VIEW, VEGA_COMPOSE, MP_CHART, SWIFT_VIEW)

problems = []
print(f"{'seam':26} " + " ".join(f"{h:14}" for h in HOSTS))
for seam, markers in SEAMS.items():
    cells = []
    for host, marker, surface in zip(HOSTS, markers, SURFACES):
        if marker is None:
            cells.append("— by design")
            if (seam, host) not in REASONS:
                problems.append(f"{seam} on {host}: marked absent with no reason recorded")
            continue
        if re.search(marker, surface):
            cells.append("yes")
        else:
            cells.append("MISSING")
            problems.append(f"{seam} is missing from {host} (looked for /{marker}/)")
    print(f"  {seam:24} " + " ".join(f"{c:14}" for c in cells))

print()
for problem in problems:
    print(f"  ! {problem}")
sys.exit(1 if problems else 0)
