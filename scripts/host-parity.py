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


ANDROID_VIEW = block(ANDROID, "dev.aster.vega.android.VegaChartView")
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
}

REASONS = {
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
