package dev.aster.vega.scene

/**
 * Where a chart ended up inside the surface that drew it: the fit scale, and the offset from that
 * surface's top-left corner.
 *
 * One placement, used by everything that has to agree about it. Drawing, hit testing and the
 * accessibility frames all go through the same three numbers, and a second copy of the arithmetic
 * is how a reader's finger lands beside the mark it looked like it hit — a defect this project has
 * had twice, once on Android and once in the Swift renderer.
 *
 * Reported to a host through `onPlaced`, for the two things only a host can do: turn a point of its
 * own into the chart's coordinates, and put its own overlay where the drawing actually is.
 *
 * **[scale] is the fit alone**, with no pan or zoom in it. A controller applies those itself —
 * `InteractionState.viewportOffset` and `viewportScale` — and handing it a scale that already
 * carried them would apply each twice. A host that drives a `VegaChartController` sets
 * `controller.contentScale = placement.scale`, which is what lets the controller invert a point the
 * way it documents.
 *
 * It lives here, in the module every renderer already depends on, because every renderer needs it.
 * It was declared in `vega-compose-multiplatform` and duplicated in the Swift package, which left
 * the two `View`-based Android surfaces unable to report a placement at all: they could not depend
 * on a Compose module to say where they had drawn something.
 *
 * **`ScenePlacement` and not `ChartPlacement`, which is what the Swift package calls its own**, and
 * the reason is the Obj-C boundary rather than taste. Everything in this module is exported to the
 * Apple framework under a *flat* namespace, so a Kotlin `ChartPlacement` and the Swift struct of
 * that name would be two types with one name in a host's scope — `swift build` fails with
 * "'ChartPlacement' is ambiguous for type lookup", which is how this was found. Hiding it from
 * Obj-C is not available: `@HiddenFromObjC` is a Kotlin/Native annotation and this file compiles
 * for the JVM too. `dev.aster.vega.compose.mp.ChartPlacement` stays as a typealias, so Kotlin
 * callers are unaffected, and the Swift struct keeps its name, its `CGFloat`s and its value
 * semantics.
 *
 * @property scale scene units to surface units — pixels on Android, points on Apple.
 * @property left how far the drawing was inset from the surface's left edge, in surface units.
 *   Whether that is padding alone or padding plus a centring offset is the surface's own business
 *   and differs between them; this says where the drawing *is*, not how it got there.
 * @property top and from its top edge.
 */
public data class ScenePlacement(val scale: Double, val left: Double, val top: Double)
