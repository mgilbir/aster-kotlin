package dev.aster.vega.scene

import dev.aster.vega.model.VegaValue
import kotlin.jvm.JvmInline

@JvmInline
public value class SceneNodeId(public val value: Long) {
  public companion object {
    public val None: SceneNodeId = SceneNodeId(0L)
  }
}

/**
 * Allocates node ids for one scene build.
 *
 * Ids are sequential from 1 and derive only from build order, never from identity hash codes, so
 * two builds of the same specification produce identical snapshots (PROJECT_BRIEF.md 18.2).
 */
public class SceneNodeIdAllocator(private var next: Long = 1L) {
  public fun allocate(): SceneNodeId = SceneNodeId(next++)

  /**
   * Where the allocator has got to, so a caller can encode the *same* items twice.
   *
   * A mark with a `hover` block is encoded once as it rests and once as it looks under the pointer,
   * and the two have to agree on ids: the hit index, the selection and the accessibility tree all
   * key on them, and a hovered item that changed its id would leave the pointer over nothing.
   */
  public fun mark(): Long = next

  public fun rewind(to: Long) {
    next = to
  }
}

/** How a mark should be described to assistive technology. */
public data class AccessibilityDescriptor(
  val label: String,
  val value: String? = null,
  val role: String? = null,
  /**
   * `aria-roledescription` — what kind of thing this is, in words.
   *
   * The other half of [role], which is a machine name from a fixed list. A reader says the pair
   * together: role `graphics-symbol` with "rect mark" is heard as "rect mark", where the role alone
   * would be heard as nothing useful. Upstream emits one for every labelled item and every guide.
   */
  val roleDescription: String? = null,
  val focusable: Boolean = false,
  /** Ordering hint within the parent group; lower values are visited first. */
  val traversalIndex: Int = 0,
  /**
   * Whether this label was **derived** rather than asked for.
   *
   * This engine labels an item the specification said nothing about, which upstream does not; see
   * `MarkEncoder.describe`. The distinction has to survive onto the node because a mark's container
   * role depends on it — upstream's rule is whether any item says something *of its own* — and a
   * derived label would otherwise make every mark look as though it did.
   */
  val derived: Boolean = false,
)

/**
 * A number as a screen reader should *say* it.
 *
 * `28.0.toString()` is read aloud as "twenty-eight point zero", so a chart of whole numbers sounds
 * as though every value carried a spurious decimal. The canonical form cannot simply be changed —
 * it has to round-trip and compare — so speech gets its own. A genuine fraction keeps its digits.
 *
 * Lives beside [AccessibilityDescriptor] because it was got wrong independently in two places, in
 * the mark encoder and in the sample scenes, which is what a shared rule is for.
 */
public fun spokenNumber(value: Double): String =
  if (value.isFinite() && value == kotlin.math.floor(value)) value.toLong().toString()
  else value.toString()

/**
 * Everything a node needs to carry for hit testing, tooltips, selection, accessibility, debugging
 * and datum lookup (PROJECT_BRIEF.md 7).
 */
public data class NodeMetadata(
  val markName: String? = null,
  val role: String? = null,
  /**
   * The specification mark type this node came from, e.g. `"line"`.
   *
   * A `PathNode` can stand for a line, an area or a literal path, and they are not interchangeable
   * to anything that reasons about the scene — hit testing tolerances, accessibility descriptions
   * and the differential harness all need to tell them apart.
   */
  val markKind: String? = null,
  /**
   * How a series' points were joined, e.g. `"step-after"`.
   *
   * Kept because the drawn outline no longer says: two staircases and a straight line can pass
   * through the same points, and the differential harness compares the method alongside them.
   */
  val interpolate: String? = null,
  /**
   * The slack in a cardinal, Catmull-Rom or bundle curve, when the specification set one.
   *
   * Kept for the same reason as [interpolate]: two families at different tensions can pass through
   * the same data points, so the outline alone does not say what was asked for.
   */
  val tension: Double? = null,
  /** Stable tuple identity from the dataflow, preserved across incremental updates. */
  val datumId: Long? = null,
  val datumIndex: Int? = null,
  val interactive: Boolean = false,
  /**
   * The data row this node was encoded from.
   *
   * Needed by the interaction layer: `{"events": "rect:click", "update": "datum.category"}` is the
   * commonest handler there is, and it reads the datum of the mark under the pointer. Holds the
   * same object the encoder saw rather than a copy, so it costs a reference.
   */
  val datum: VegaValue? = null,
  /**
   * What a tooltip should say, which is **not** always the datum.
   *
   * Upstream's `tooltip` encode channel puts whatever it resolves to on the item, and a chart that
   * wants one line rather than a whole row writes one: `{"signal": "datum.name + ': ' + datum.v"}`.
   * With no channel the item carries its datum, which is what upstream does too.
   */
  val tooltip: VegaValue? = null,
  /**
   * The pointer shape over this item, as the CSS name a specification writes.
   *
   * Carried on the node rather than resolved to a platform constant because the platforms disagree
   * about what they have: a host maps it to a `PointerIcon` on Android and emits it verbatim in
   * SVG.
   */
  val cursor: String? = null,
  /**
   * `href` — the address this item links to.
   *
   * Upstream's SVG renderer wraps the drawn element in an `<a xlink:href="…">`, which is the whole
   * mechanism: a bar that is a link, a legend swatch that filters a page. Kept on the item rather
   * than turned into a click handler, because a link is a link — the renderer that knows how to
   * draw one knows how to make it navigable, and one that does not can ignore it.
   */
  val href: String? = null,
  /**
   * Paint order **within** the item's own mark, `zindex`.
   *
   * Zero for almost everything. It matters on hover — a raised item has to be drawn over its
   * neighbours, which is a reordering and not a repaint — and the sort is stable, so items sharing
   * a `zindex` keep the order the data gave them.
   */
  val zindex: Int = 0,
  val accessibility: AccessibilityDescriptor? = null,
  /**
   * Which of its parent's marks this node came from, upstream's `markpath`.
   *
   * [markName] and [markKind] nearly identify a mark and not quite: two `rect` marks declared side
   * by side with no names are indistinguishable by them, and this scene flattens every mark's items
   * into its group's children, so the boundary between the two is not visible without this. It
   * decides where one mark's run of items ends — which is what `zindex` reorders inside of, and
   * what a screen reader is told about once.
   */
  val markOrdinal: Int? = null,
  /**
   * What a screen reader is told about the **mark**, repeated on each of its items.
   *
   * Upstream's scene has a level this one does not: a group holds *marks* and each mark holds
   * items, so a mark's own announcement has somewhere to live. Here the items are the group's
   * children, so the announcement travels on each of them and a renderer rebuilds the container
   * from a run of items that share it. Cheaper than it looks — one instance per mark, held by
   * reference.
   */
  val markAccessibility: MarkAccessibility? = null,
) {
  public companion object {
    public val None: NodeMetadata = NodeMetadata()
  }
}

/**
 * What a screen reader is told about a whole mark, as opposed to one of its items.
 *
 * Upstream's `ariaMarkAttributes`. A mark is announced as a container — "rect mark container" — and
 * that is where a mark-level `description` is heard: once, naming the series, rather than on each
 * of the fifty bars inside it. [hidden] is `aria: false` on the mark, which takes the whole thing
 * out of the tree and suppresses the rest of these.
 */
public data class MarkAccessibility(
  /**
   * `graphics-object` for a mark whose items say something of their own, `graphics-symbol` else.
   *
   * Null when [hidden], because upstream emits nothing but `aria-hidden` for a mark it hides.
   */
  val role: String?,
  /** Upstream's `<type> mark container`, and null for the same reason as [role]. */
  val roleDescription: String?,
  /** The mark's own `description`, if it has one. */
  val label: String? = null,
  val hidden: Boolean = false,
)

/**
 * The same node with different metadata.
 *
 * A `when` over the seven types because [SceneNode] is a sealed interface of data classes and
 * Kotlin has no generic copy across them. Needed by anything that decides a property *after* the
 * items are built — a mark's container role depends on what its items turned out to say.
 */
public fun withMetadata(node: SceneNode, metadata: NodeMetadata): SceneNode =
  when (node) {
    is GroupNode -> node.copy(metadata = metadata)
    is RectNode -> node.copy(metadata = metadata)
    is RuleNode -> node.copy(metadata = metadata)
    is PathNode -> node.copy(metadata = metadata)
    is SymbolNode -> node.copy(metadata = metadata)
    is TextNode -> node.copy(metadata = metadata)
    is ImageNode -> node.copy(metadata = metadata)
  }

public sealed interface SceneNode {
  public val id: SceneNodeId
  /** Tight bounds in this node's own coordinate space, including stroke extents. */
  public val bounds: RectD
  /** Applied to this node and, for a group, to its children. */
  public val transform: Transform2D
  public val opacity: Double
  public val visible: Boolean
  public val metadata: NodeMetadata
}

/** Bounds of a node in its parent's coordinate space. */
public val SceneNode.transformedBounds: RectD
  get() = transform.mapBounds(bounds)

/** Stable lowercase type name used in snapshots, diagnostics and debug output. */
public fun typeName(node: SceneNode): String =
  when (node) {
    is GroupNode -> "group"
    is RectNode -> "rect"
    is RuleNode -> "rule"
    is PathNode -> "path"
    is SymbolNode -> "symbol"
    is TextNode -> "text"
    is ImageNode -> "image"
  }

/**
 * A container that applies a transform, an optional clip and its own paint to its children.
 *
 * Groups are the only nodes that nest, so they are also where Vega's group marks, axes, legends and
 * titles land.
 */
public data class GroupNode(
  override val id: SceneNodeId,
  val children: List<SceneNode> = emptyList(),
  override val transform: Transform2D = Transform2D.Identity,
  override val opacity: Double = 1.0,
  override val visible: Boolean = true,
  override val metadata: NodeMetadata = NodeMetadata.None,
  val fill: Fill? = null,
  val stroke: Stroke? = null,
  /**
   * The group's own extent, from the `width` and `height` channels of a Vega group mark.
   *
   * A group that declares a size paints its fill and stroke as a rectangle of that size at its
   * origin, and contributes that rectangle to its own bounds even when its children are smaller.
   * `null` means a pure container — an axis group, or the scene root — which neither paints nor
   * measures anything of its own.
   */
  val size: SizeD? = null,
  val cornerRadius: Double = 0.0,
  /** Per-corner overrides of [cornerRadius]; see the same fields on [RectNode]. */
  val cornerRadiusTopLeft: Double? = null,
  val cornerRadiusTopRight: Double? = null,
  val cornerRadiusBottomRight: Double? = null,
  val cornerRadiusBottomLeft: Double? = null,
  /**
   * How far the background rectangle is nudged so a thin stroke lands on a pixel boundary.
   *
   * Null takes upstream's rule, which is not "no offset": a group stroked at a width between 0.5
   * and 1.5 is shifted by `0.5 - |width - 1|`, so the commonest case — a 1px outline — moves half a
   * pixel and comes out crisp instead of grey on both sides of the boundary. Only the group mark
   * has this; a `rect` inside one does not.
   */
  val strokeOffset: Double? = null,
  /**
   * Draws the group's stroke **after** its children rather than before.
   *
   * The difference is only visible where a child reaches the edge: a cell whose bars run to its own
   * border either have the border drawn over them or paint over it themselves.
   */
  val strokeForeground: Boolean = false,
  /**
   * Whether this group measures as its children alone, ignoring its own paint.
   *
   * A faithful port of one upstream rule, not a general relaxation. `titleLayout` finishes by
   * writing the union of the heading's and subtitle's bounds over the group's — `group.bounds
   * .clear().union(tempBounds)` — which discards the half-unit `boundStroke` had already added for
   * the group's background. So a heading given an outline through its `encode.group` block paints
   * that outline over a rectangle of no size and does **not** make the drawing half a unit wider,
   * which it would under the ordinary group rule.
   */
  val boundsFromChildren: Boolean = false,
  /** Clip rectangle in this group's own coordinate space, applied before drawing children. */
  val clip: RectD? = null,
  val clipPath: PathData? = null,
  val blendMode: SceneBlendMode = SceneBlendMode.NORMAL,
) : SceneNode {

  /** The rectangle this group's own fill and stroke cover, or `null` when it paints nothing. */
  public val paintRect: RectD?
    get() =
      when {
        fill == null && stroke == null -> null
        size != null -> RectD(0.0, 0.0, size.width, size.height)
        else -> clip
      }

  /**
   * The half-pixel nudge actually applied to the background, `strokeOffset` resolved.
   *
   * Upstream's `offset(item)`: an explicit value wins; otherwise a stroke whose width is within
   * half a unit of 1 is shifted to sit on a pixel boundary, and anything else is not shifted at
   * all.
   */
  public val effectiveStrokeOffset: Double
    get() {
      strokeOffset?.let {
        return it
      }
      val width = stroke?.width ?: return 0.0
      return if (width > 0.5 && width < 1.5) 0.5 - kotlin.math.abs(width - 1.0) else 0.0
    }

  /** That rectangle rounded, or null when it is square or there is nothing to paint. */
  public val roundedPaintPath: PathData?
    get() {
      val rect = paintRect ?: return null
      val corners =
        Corners.of(
          rect.width,
          rect.height,
          cornerRadiusTopLeft ?: cornerRadius,
          cornerRadiusTopRight ?: cornerRadius,
          cornerRadiusBottomRight ?: cornerRadius,
          cornerRadiusBottomLeft ?: cornerRadius,
        )
      if (corners.isSquare) return null
      val offset = effectiveStrokeOffset
      return RectPath.of(rect.left + offset, rect.top + offset, rect.width, rect.height, corners)
    }

  override val bounds: RectD by
    lazy(LazyThreadSafetyMode.NONE) {
      // Upstream measures a group as its declared extent unioned with its children. Clipping
      // narrows that, since nothing outside the clip is drawn. Upstream applies the narrowing one
      // level up, at a marktype layer this scene graph has no equivalent of; applying it to the
      // group itself draws the same pixels and gives the same overall surface.
      var result = size?.let { RectD(0.0, 0.0, it.width, it.height) } ?: RectD.Empty
      for (child in children) {
        if (child.visible) result = result.union(child.transformedBounds)
      }
      if (clip != null) result = intersect(result, clip)
      if (boundsFromChildren) return@lazy result.normalized()
      (stroke.wideningAt(opacity)?.let { result.expand(it.halfWidth) } ?: result).normalized()
    }
}

/**
 * Whether a stroke widens a node's bounds.
 *
 * Upstream's `boundStroke` tests the **item's** own opacity as well as the stroke's — `item.stroke
 * && item.opacity !== 0 && item.strokeOpacity !== 0` — so a mark drawn at zero opacity reaches
 * exactly as far as its geometry and no further. It matters wherever an invisible mark sits at the
 * edge of a chart: Vega's labelled donut lays its label bins out with debug rectangles left at
 * `opacity: 0`, and counting their two-unit stroke made the whole surface a unit taller than
 * upstream's.
 */
internal fun Stroke?.wideningAt(opacity: Double): Stroke? =
  if (this != null && isVisible && opacity != 0.0) this else null

private fun intersect(a: RectD, b: RectD): RectD {
  if (a.isEmpty || b.isEmpty) return RectD.Empty
  val rect =
    RectD(
      maxOf(a.left, b.left),
      maxOf(a.top, b.top),
      minOf(a.right, b.right),
      minOf(a.bottom, b.bottom),
    )
  return if (rect.isEmpty) RectD.Empty else rect
}

/** Vega's `rect` mark. Corner radii are clamped so opposite radii cannot overlap. */
public data class RectNode(
  override val id: SceneNodeId,
  val x: Double,
  val y: Double,
  val width: Double,
  val height: Double,
  val cornerRadius: Double = 0.0,
  /**
   * Per-corner overrides, each falling back to [cornerRadius] when absent.
   *
   * Null rather than `0.0` because the fallback has to be distinguishable from a corner
   * deliberately squared off: `cornerRadius: 4, cornerRadiusTopLeft: 0` is a bar rounded on three
   * corners.
   */
  val cornerRadiusTopLeft: Double? = null,
  val cornerRadiusTopRight: Double? = null,
  val cornerRadiusBottomRight: Double? = null,
  val cornerRadiusBottomLeft: Double? = null,
  val fill: Fill? = null,
  val stroke: Stroke? = null,
  override val transform: Transform2D = Transform2D.Identity,
  override val opacity: Double = 1.0,
  override val visible: Boolean = true,
  override val metadata: NodeMetadata = NodeMetadata.None,
  val blendMode: SceneBlendMode = SceneBlendMode.NORMAL,
) : SceneNode {

  /** The rectangle with negative extents normalized away. */
  public val rect: RectD
    get() = RectD.fromSize(x, y, width, height)

  /** The four radii actually drawn: the overrides where given, clamped to fit. */
  public val corners: Corners
    get() =
      Corners.of(
        width,
        height,
        cornerRadiusTopLeft ?: cornerRadius,
        cornerRadiusTopRight ?: cornerRadius,
        cornerRadiusBottomRight ?: cornerRadius,
        cornerRadiusBottomLeft ?: cornerRadius,
      )

  public val effectiveCornerRadius: Double
    get() = cornerRadius.coerceIn(0.0, minOf(kotlin.math.abs(width), kotlin.math.abs(height)) / 2.0)

  /**
   * The outline as a path, or null when all four corners are square and a plain rectangle will do.
   *
   * Both renderers go through this rather than their own rounded-rectangle primitive, so that the
   * corner geometry is Vega's in every output — see [RectPath].
   */
  public val roundedPath: PathData?
    get() = corners.takeIf { !it.isSquare }?.let { RectPath.of(x, y, width, height, it) }

  override val bounds: RectD by
    lazy(LazyThreadSafetyMode.NONE) {
      val base = rect
      (stroke.wideningAt(opacity)?.let { base.expand(it.halfWidth) } ?: base).normalized()
    }
}

/** Vega's `rule` mark: a single straight stroked segment. */
public data class RuleNode(
  override val id: SceneNodeId,
  val x1: Double,
  val y1: Double,
  val x2: Double,
  val y2: Double,
  val stroke: Stroke,
  override val transform: Transform2D = Transform2D.Identity,
  override val opacity: Double = 1.0,
  override val visible: Boolean = true,
  override val metadata: NodeMetadata = NodeMetadata.None,
  val blendMode: SceneBlendMode = SceneBlendMode.NORMAL,
) : SceneNode {

  override val bounds: RectD by
    lazy(LazyThreadSafetyMode.NONE) {
      RectD(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
        .expand(stroke.wideningAt(opacity)?.halfWidth ?: 0.0)
        .normalized()
    }
}

/** Vega's `line`, `area` and `path` marks, plus `arc` once implemented. */
public data class PathNode(
  override val id: SceneNodeId,
  val path: PathData,
  /**
   * Whether the mark has **no** outline at all, as against one that draws nothing.
   *
   * Upstream distinguishes the two and they measure differently: `item.path == null` gives bounds
   * of exactly `(0, 0, 0, 0)`, while a non-null string that happens to draw nothing leaves the
   * bounds empty. A `geopath` over a geometry with no coordinates is the first — d3's path
   * generator returns null — and a `linkpath` writing an empty string is the second, which is why
   * Vega's labelled donut and its contour plot disagree about the same-looking mark.
   */
  val absent: Boolean = false,
  val fill: Fill? = null,
  val stroke: Stroke? = null,
  override val transform: Transform2D = Transform2D.Identity,
  override val opacity: Double = 1.0,
  override val visible: Boolean = true,
  override val metadata: NodeMetadata = NodeMetadata.None,
  val blendMode: SceneBlendMode = SceneBlendMode.NORMAL,
) : SceneNode {

  override val bounds: RectD by
    lazy(LazyThreadSafetyMode.NONE) {
      if (absent) return@lazy RectD(0.0, 0.0, 0.0, 0.0)
      val base = path.bounds
      // A miter join can extend past halfWidth; the miter limit bounds how far.
      val expansion =
        stroke.wideningAt(opacity)?.let {
          if (it.join == StrokeJoin.MITER) it.halfWidth * it.miterLimit.coerceAtLeast(1.0)
          else it.halfWidth
        } ?: 0.0
      (if (expansion > 0.0) base.expand(expansion) else base).normalized()
    }
}

public enum class SymbolShape {
  CIRCLE,
  SQUARE,
  CROSS,
  DIAMOND,
  TRIANGLE_UP,
  TRIANGLE_DOWN,
  TRIANGLE_LEFT,
  TRIANGLE_RIGHT,
  /**
   * Vega's plain `triangle`, which is [TRIANGLE_UP] shifted so it balances on its centroid rather
   * than on the centre of its bounding box. The two are not interchangeable.
   */
  TRIANGLE,
  /** A horizontal tick, Vega's `stroke` symbol. */
  STROKE,
  ARROW,
  WEDGE,
}

/**
 * Vega's `symbol` mark.
 *
 * [size] follows Vega's own convention, which is **not** d3's: it is the squared extent, so every
 * shape fits inside a `sqrt(size)` box and [reference] is `sqrt(size) / 2`. A circle of size 100
 * has radius 5, where d3-shape — which sizes by area — would give 5.64. Vega ships its own symbol
 * table for exactly this reason, and a chart that mixes the two conventions draws symbols 13% too
 * large.
 */
public data class SymbolNode(
  override val id: SceneNodeId,
  val x: Double,
  val y: Double,
  val size: Double = 30.0,
  val shape: SymbolShape = SymbolShape.CIRCLE,
  val angleDegrees: Double = 0.0,
  val fill: Fill? = null,
  val stroke: Stroke? = null,
  /** Set for `shape` values the engine does not have a built-in generator for. */
  val customPath: PathData? = null,
  override val transform: Transform2D = Transform2D.Identity,
  override val opacity: Double = 1.0,
  override val visible: Boolean = true,
  override val metadata: NodeMetadata = NodeMetadata.None,
  val blendMode: SceneBlendMode = SceneBlendMode.NORMAL,
) : SceneNode {

  /** Half of `sqrt(size)`: the reference length every shape is built from, as upstream. */
  public val reference: Double
    get() = if (size <= 0.0) 0.0 else kotlin.math.sqrt(size) / 2.0

  /** The symbol outline in scene coordinates, including rotation about ([x], [y]). */
  public val outline: PathData by lazy(LazyThreadSafetyMode.NONE) { buildSymbolPath(this) }

  override val bounds: RectD by
    lazy(LazyThreadSafetyMode.NONE) {
      val base = outline.bounds
      // Upstream bounds a symbol's stroke with the miter allowance, because a miter join on a
      // triangle's tip really does reach that far past the vertex. It applies the same allowance to
      // a
      // circle, which is over-generous; reproducing it keeps our bounds comparable with upstream's.
      val expansion =
        stroke.wideningAt(opacity)?.let {
          maxOf(it.halfWidth, it.miterLimit.coerceAtLeast(1.0) * it.halfWidth)
        } ?: 0.0
      (if (expansion > 0.0) base.expand(expansion) else base).normalized()
    }
}

/** Vega's `text` mark. The layout is precomputed so nothing measures text while drawing. */
public data class TextNode(
  override val id: SceneNodeId,
  val x: Double,
  val y: Double,
  val layout: TextLayout,
  val fill: Fill? = null,
  val stroke: Stroke? = null,
  val angleDegrees: Double = 0.0,
  override val transform: Transform2D = Transform2D.Identity,
  override val opacity: Double = 1.0,
  override val visible: Boolean = true,
  override val metadata: NodeMetadata = NodeMetadata.None,
  val blendMode: SceneBlendMode = SceneBlendMode.NORMAL,
  /**
   * True when the item carries **no text at all**, which is not the same as carrying an empty one.
   *
   * A banded legend's lowest label is the case: that bucket reaches to negative infinity and
   * upstream's formatter returns nothing rather than a number, so the item exists — it is measured
   * and it occupies a row — and has no `text` property. Distinguishing the two is what lets a
   * comparison see an empty string somebody meant to write.
   */
  val absent: Boolean = false,
) : SceneNode {

  public val text: String
    get() = layout.run.text

  override val bounds: RectD by
    lazy(LazyThreadSafetyMode.NONE) {
      // Upstream's `anchorPoint` reads `item.x || 0`, and `NaN` is falsy — so a text item whose
      // position was never computed is *measured* at the origin even though nothing is drawn there.
      // An axis's `tickExtra` label is exactly that: it scales a value its datum does not carry, so
      // its scene position is `NaN`, its bounds are the box an empty string occupies at the origin,
      // and its renderer emits no element at all. All three have to be reproduced together —
      // keeping the `NaN` out of the bounds loses five units of chart height, and letting it into a
      // `min` or a `max` poisons every measurement above it.
      val ax = if (x.isNaN()) 0.0 else x
      val ay = if (y.isNaN()) 0.0 else y
      val placed = layout.bounds.translate(ax, ay)
      if (angleDegrees == 0.0) placed.normalized() else rotatedAbout(placed, ax, ay).normalized()
    }

  /**
   * The corners of [rect] turned about ([px], [py]), in upstream's own arithmetic.
   *
   * The arithmetic is copied rather than merely equivalent, because the last bit decides a chart's
   * size. `cos(-90°)` is 6.1e-17 and not zero, so a corner's coordinate carries a crumb wherever it
   * is multiplied; upstream rotates the corner's **absolute** position, where the crumb is absorbed
   * by a number two orders of magnitude larger and the result lands exactly on the integer.
   * Rotating the offset from the anchor first and translating afterwards leaves the crumb intact,
   * and a title whose reach is 38.000000000000014 rather than 38 costs the plotting area a whole
   * unit — `viewSizeLayout` takes the ceiling of it.
   */
  private fun rotatedAbout(rect: RectD, px: Double, py: Double): RectD {
    val radians = angleDegrees * kotlin.math.PI / 180.0
    val cos = kotlin.math.cos(radians)
    val sin = kotlin.math.sin(radians)
    val cx = px - px * cos + py * sin
    val cy = py - px * sin - py * cos
    fun corner(x1: Double, y1: Double): PointD =
      PointD(cos * x1 - sin * y1 + cx, sin * x1 + cos * y1 + cy)
    return RectD.fromPoints(
      listOf(
        corner(rect.left, rect.top),
        corner(rect.left, rect.bottom),
        corner(rect.right, rect.top),
        corner(rect.right, rect.bottom),
      )
    )
  }
}

/** How an image fills its destination rectangle. */
/** Where an image mark's `x` sits on the image. */
public enum class ImageAlign {
  LEFT,
  CENTER,
  RIGHT;

  public companion object {
    public fun fromName(name: String?): ImageAlign =
      when (name?.lowercase()) {
        "center" -> CENTER
        "right" -> RIGHT
        else -> LEFT
      }
  }
}

/** Where an image mark's `y` sits on the image. */
public enum class ImageBaseline {
  TOP,
  MIDDLE,
  BOTTOM;

  public companion object {
    public fun fromName(name: String?): ImageBaseline =
      when (name?.lowercase()) {
        "middle" -> MIDDLE
        "bottom" -> BOTTOM
        else -> TOP
      }
  }
}

public enum class ImageFit {
  /** Stretch to the destination rectangle, ignoring the source aspect ratio. */
  FILL,
  /** Preserve aspect ratio, fitting inside the destination rectangle. */
  CONTAIN,
}

/**
 * Vega's `image` mark.
 *
 * The core holds only a [url] and the geometry; resolving bytes is an Android or export concern,
 * and an unresolvable image produces `VEGA_EXPORT_IMAGE_UNRESOLVED` rather than a blank space.
 */
public data class ImageNode(
  override val id: SceneNodeId,
  val url: String,
  /**
   * Pixels the mark carries rather than an address it points at.
   *
   * A `heatmap` produces one. When it is set the [url] is empty and the renderer draws these
   * directly — upstream's equivalent is a `<canvas>` on the scene item, which a renderer that has
   * to work without one cannot use.
   */
  val raster: RasterImage? = null,
  val x: Double,
  val y: Double,
  val width: Double,
  val height: Double,
  val fit: ImageFit = ImageFit.FILL,
  val smooth: Boolean = true,
  /**
   * Where [x] sits on the image: its left edge, its middle or its right edge.
   *
   * Held here rather than folded into [x] because upstream does the same — a scene item keeps the
   * `x` the specification gave it and the renderer shifts by the offset. Folding it in would make
   * the scene disagree with Vega's on every centred image, and would lose the difference between
   * "at 120, centred" and "at 100, left-aligned": they draw identically and are not the same thing
   * to anything that lays out again.
   */
  val align: ImageAlign = ImageAlign.LEFT,
  val baseline: ImageBaseline = ImageBaseline.TOP,
  override val transform: Transform2D = Transform2D.Identity,
  override val opacity: Double = 1.0,
  override val visible: Boolean = true,
  override val metadata: NodeMetadata = NodeMetadata.None,
  val blendMode: SceneBlendMode = SceneBlendMode.NORMAL,
) : SceneNode {

  /** Where the image is actually drawn, once [align] and [baseline] have shifted it. */
  public val rect: RectD
    get() =
      RectD.fromSize(
        x -
          when (align) {
            ImageAlign.LEFT -> 0.0
            ImageAlign.CENTER -> width / 2
            ImageAlign.RIGHT -> width
          },
        y -
          when (baseline) {
            ImageBaseline.TOP -> 0.0
            ImageBaseline.MIDDLE -> height / 2
            ImageBaseline.BOTTOM -> height
          },
        width,
        height,
      )

  override val bounds: RectD by lazy(LazyThreadSafetyMode.NONE) { rect.normalized() }
}

/**
 * Generates a symbol outline in scene coordinates.
 *
 * Every proportion here is upstream Vega's own symbol table, not d3-shape's. Vega replaces d3's
 * table wholesale: it sizes each shape from `r = sqrt(size) / 2` so all of them fit inside a
 * `sqrt(size)` box, where d3 sizes by area. The two differ visibly — a circle 13% too wide — and
 * the table also splits `triangle` (balanced on its centroid) from `triangle-up` (balanced on its
 * bounding box), which d3 does not.
 */
/** Scales a path about the origin, for a custom symbol outline. */
private fun scalePath(path: PathData, factor: Double): PathData =
  PathData(
    path.commands.map { command ->
      when (command) {
        is PathCommand.MoveTo -> PathCommand.MoveTo(command.x * factor, command.y * factor)
        is PathCommand.LineTo -> PathCommand.LineTo(command.x * factor, command.y * factor)
        is PathCommand.CubicTo ->
          PathCommand.CubicTo(
            command.x1 * factor,
            command.y1 * factor,
            command.x2 * factor,
            command.y2 * factor,
            command.x * factor,
            command.y * factor,
          )
        PathCommand.Close -> PathCommand.Close
      }
    }
  )

private fun buildSymbolPath(node: SymbolNode): PathData {
  val r = node.reference
  // A symbol sized to nothing is still *somewhere*: upstream bounds it as a degenerate point at its
  // anchor, not as an empty rectangle. The difference shows up when a size scale bottoms out — the
  // point still counts towards the chart's reach under `autosize: pad`, where an empty rectangle
  // would silently drop out of the measurement.
  if (r <= 0.0) return PathData.build { moveTo(node.x, node.y) }

  // sin(60°) and tan(30°): the height of an equilateral triangle of half-width r, and the offset
  // between its centroid and the centre of its bounding box.
  val halfSqrt3 = kotlin.math.sqrt(3.0) / 2.0
  val tan30 = 1.0 / kotlin.math.sqrt(3.0)

  val local =
    // A custom outline is written in a unit box and scaled by the symbol's reference length, so the
    // same path string sizes with `size` exactly as a built-in shape does. Drawing it at its
    // literal coordinates instead leaves every custom symbol the same size whatever the data says.
    node.customPath?.let { outline -> scalePath(outline, r) }
      ?: PathData.build {
        when (node.shape) {
          SymbolShape.CIRCLE -> circle(0.0, 0.0, r)
          SymbolShape.SQUARE -> rect(-r, -r, r * 2.0, r * 2.0)
          SymbolShape.CROSS -> {
            val s = r / 2.5
            moveTo(-r, -s)
            lineTo(-r, s)
            lineTo(-s, s)
            lineTo(-s, r)
            lineTo(s, r)
            lineTo(s, s)
            lineTo(r, s)
            lineTo(r, -s)
            lineTo(s, -s)
            lineTo(s, -r)
            lineTo(-s, -r)
            lineTo(-s, -s)
            close()
          }
          SymbolShape.DIAMOND -> {
            moveTo(-r, 0.0)
            lineTo(0.0, -r)
            lineTo(r, 0.0)
            lineTo(0.0, r)
            close()
          }
          SymbolShape.TRIANGLE_UP -> {
            val h = halfSqrt3 * r
            moveTo(0.0, -h)
            lineTo(-r, h)
            lineTo(r, h)
            close()
          }
          SymbolShape.TRIANGLE_DOWN -> {
            val h = halfSqrt3 * r
            moveTo(0.0, h)
            lineTo(-r, -h)
            lineTo(r, -h)
            close()
          }
          SymbolShape.TRIANGLE_RIGHT -> {
            val h = halfSqrt3 * r
            moveTo(h, 0.0)
            lineTo(-h, -r)
            lineTo(-h, r)
            close()
          }
          SymbolShape.TRIANGLE_LEFT -> {
            val h = halfSqrt3 * r
            moveTo(-h, 0.0)
            lineTo(h, -r)
            lineTo(h, r)
            close()
          }
          SymbolShape.TRIANGLE -> {
            val h = halfSqrt3 * r
            val o = h - r * tan30
            moveTo(0.0, -h - o)
            lineTo(-r, h - o)
            lineTo(r, h - o)
            close()
          }
          SymbolShape.STROKE -> {
            moveTo(-r, 0.0)
            lineTo(r, 0.0)
          }
          SymbolShape.ARROW -> {
            val s = r / 7.0
            val t = r / 2.5
            val v = r / 8.0
            moveTo(-s, r)
            lineTo(s, r)
            lineTo(s, -v)
            lineTo(t, -v)
            lineTo(0.0, -r)
            lineTo(-t, -v)
            lineTo(-s, -v)
            close()
          }
          SymbolShape.WEDGE -> {
            val h = halfSqrt3 * r
            val o = h - r * tan30
            val b = r / 4.0
            moveTo(0.0, -h - o)
            lineTo(-b, h - o)
            lineTo(b, h - o)
            close()
          }
        }
      }

  val placement =
    if (node.angleDegrees == 0.0) {
      Transform2D.translate(node.x, node.y)
    } else {
      Transform2D.translate(node.x, node.y).concat(Transform2D.rotateDegrees(node.angleDegrees))
    }
  return local.transformedBy(placement)
}

/** Returns a copy of this path with every coordinate mapped through [transform]. */
public fun PathData.transformedBy(transform: Transform2D): PathData {
  if (transform.isIdentity) return this
  return PathData(
    commands.map { command ->
      when (command) {
        is PathCommand.MoveTo -> {
          val p = transform.apply(command.x, command.y)
          PathCommand.MoveTo(p.x, p.y)
        }
        is PathCommand.LineTo -> {
          val p = transform.apply(command.x, command.y)
          PathCommand.LineTo(p.x, p.y)
        }
        is PathCommand.CubicTo -> {
          val c1 = transform.apply(command.x1, command.y1)
          val c2 = transform.apply(command.x2, command.y2)
          val p = transform.apply(command.x, command.y)
          PathCommand.CubicTo(c1.x, c1.y, c2.x, c2.y, p.x, p.y)
        }
        PathCommand.Close -> PathCommand.Close
      }
    }
  )
}
