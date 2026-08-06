package dev.aster.vega.scene

import dev.aster.vega.model.VegaValue

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
}

/** How a mark should be described to assistive technology. */
public data class AccessibilityDescriptor(
  val label: String,
  val value: String? = null,
  val role: String? = null,
  val focusable: Boolean = false,
  /** Ordering hint within the parent group; lower values are visited first. */
  val traversalIndex: Int = 0,
)

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
  /** Stable tuple identity from the dataflow, preserved across incremental updates. */
  val datumId: Long? = null,
  val datumIndex: Int? = null,
  val interactive: Boolean = false,
  val tooltip: VegaValue? = null,
  val accessibility: AccessibilityDescriptor? = null,
) {
  public companion object {
    public val None: NodeMetadata = NodeMetadata()
  }
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
      (stroke?.let { result.expand(it.halfWidth) } ?: result).normalized()
    }
}

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

  public val effectiveCornerRadius: Double
    get() = cornerRadius.coerceIn(0.0, minOf(kotlin.math.abs(width), kotlin.math.abs(height)) / 2.0)

  override val bounds: RectD by
    lazy(LazyThreadSafetyMode.NONE) {
      val base = rect
      (if (stroke != null && stroke.isVisible) base.expand(stroke.halfWidth) else base).normalized()
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
        .expand(if (stroke.isVisible) stroke.halfWidth else 0.0)
        .normalized()
    }
}

/** Vega's `line`, `area` and `path` marks, plus `arc` once implemented. */
public data class PathNode(
  override val id: SceneNodeId,
  val path: PathData,
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
      val base = path.bounds
      // A miter join can extend past halfWidth; the miter limit bounds how far.
      val expansion =
        if (stroke != null && stroke.isVisible) {
          if (stroke.join == StrokeJoin.MITER)
            stroke.halfWidth * stroke.miterLimit.coerceAtLeast(1.0)
          else stroke.halfWidth
        } else 0.0
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
        if (stroke != null && stroke.isVisible) {
          maxOf(stroke.halfWidth, stroke.miterLimit.coerceAtLeast(1.0) * stroke.halfWidth)
        } else 0.0
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
) : SceneNode {

  public val text: String
    get() = layout.run.text

  override val bounds: RectD by
    lazy(LazyThreadSafetyMode.NONE) {
      val local = layout.bounds
      if (angleDegrees == 0.0) {
        local.translate(x, y).normalized()
      } else {
        Transform2D.translate(x, y)
          .concat(Transform2D.rotateDegrees(angleDegrees))
          .mapBounds(local)
          .normalized()
      }
    }
}

/** How an image fills its destination rectangle. */
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
  val x: Double,
  val y: Double,
  val width: Double,
  val height: Double,
  val fit: ImageFit = ImageFit.FILL,
  val smooth: Boolean = true,
  override val transform: Transform2D = Transform2D.Identity,
  override val opacity: Double = 1.0,
  override val visible: Boolean = true,
  override val metadata: NodeMetadata = NodeMetadata.None,
  val blendMode: SceneBlendMode = SceneBlendMode.NORMAL,
) : SceneNode {

  public val rect: RectD
    get() = RectD.fromSize(x, y, width, height)

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
private fun buildSymbolPath(node: SymbolNode): PathData {
  val r = node.reference
  if (r <= 0.0) return PathData.Empty

  // sin(60°) and tan(30°): the height of an equilateral triangle of half-width r, and the offset
  // between its centroid and the centre of its bounding box.
  val halfSqrt3 = kotlin.math.sqrt(3.0) / 2.0
  val tan30 = 1.0 / kotlin.math.sqrt(3.0)

  val local =
    node.customPath
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
