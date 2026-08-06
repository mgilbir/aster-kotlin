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
  /** Clip rectangle in this group's own coordinate space, applied before drawing children. */
  val clip: RectD? = null,
  val clipPath: PathData? = null,
  val blendMode: SceneBlendMode = SceneBlendMode.NORMAL,
) : SceneNode {

  override val bounds: RectD by
    lazy(LazyThreadSafetyMode.NONE) {
      val own =
        when {
          fill != null || stroke != null -> clip ?: RectD.Empty
          else -> RectD.Empty
        }
      var result = own
      for (child in children) {
        if (child.visible) result = result.union(child.transformedBounds)
      }
      val strokeExpanded = stroke?.let { result.expand(it.halfWidth) } ?: result
      val clipped = if (clip != null) intersect(strokeExpanded, clip) else strokeExpanded
      clipped.normalized()
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
  /** A horizontal tick, Vega's `stroke` symbol. */
  STROKE,
}

/**
 * Vega's `symbol` mark. [size] is the symbol's *area* in square pixels, as in Vega, not its radius.
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

  /** Radius of the circle whose area is [size]; the reference length for every shape. */
  public val radius: Double
    get() = if (size <= 0.0) 0.0 else kotlin.math.sqrt(size / kotlin.math.PI)

  /** The symbol outline in scene coordinates, including rotation about ([x], [y]). */
  public val outline: PathData by lazy(LazyThreadSafetyMode.NONE) { buildSymbolPath(this) }

  override val bounds: RectD by
    lazy(LazyThreadSafetyMode.NONE) {
      val base = outline.bounds
      (if (stroke != null && stroke.isVisible) base.expand(stroke.halfWidth) else base).normalized()
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
 * Shape proportions follow upstream Vega: every shape is sized relative to the radius of the circle
 * with the same area, so symbols of different shapes look equally heavy.
 */
private fun buildSymbolPath(node: SymbolNode): PathData {
  val r = node.radius
  if (r <= 0.0) return PathData.Empty

  val local =
    node.customPath
      ?: PathData.build {
        when (node.shape) {
          SymbolShape.CIRCLE -> circle(0.0, 0.0, r)
          SymbolShape.SQUARE -> {
            val half = kotlin.math.sqrt(node.size) / 2.0
            rect(-half, -half, half * 2.0, half * 2.0)
          }
          SymbolShape.CROSS -> {
            // Vega uses a cross whose arms are one third of its extent.
            val s = kotlin.math.sqrt(node.size / 5.0) / 2.0
            moveTo(-3 * s, -s)
            lineTo(-s, -s)
            lineTo(-s, -3 * s)
            lineTo(s, -3 * s)
            lineTo(s, -s)
            lineTo(3 * s, -s)
            lineTo(3 * s, s)
            lineTo(s, s)
            lineTo(s, 3 * s)
            lineTo(-s, 3 * s)
            lineTo(-s, s)
            lineTo(-3 * s, s)
            close()
          }
          SymbolShape.DIAMOND -> {
            // d3-shape's symbolDiamond: a rhombus with the requested area.
            val tan30 = kotlin.math.sqrt(1.0 / 3.0)
            val dy = kotlin.math.sqrt(node.size / (tan30 * 2.0))
            val dx = dy * tan30
            moveTo(0.0, -dy)
            lineTo(dx, 0.0)
            lineTo(0.0, dy)
            lineTo(-dx, 0.0)
            close()
          }
          SymbolShape.TRIANGLE_UP -> triangle(this, node.size, Orientation.UP)
          SymbolShape.TRIANGLE_DOWN -> triangle(this, node.size, Orientation.DOWN)
          SymbolShape.TRIANGLE_RIGHT -> triangle(this, node.size, Orientation.RIGHT)
          SymbolShape.TRIANGLE_LEFT -> triangle(this, node.size, Orientation.LEFT)
          SymbolShape.STROKE -> {
            moveTo(-r, 0.0)
            lineTo(r, 0.0)
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

private enum class Orientation {
  UP,
  DOWN,
  LEFT,
  RIGHT,
}

/**
 * Equilateral triangle with area == [size], matching d3-shape's `symbolTriangle` geometry (apex at
 * `2y`, base at `-y` with `y = -sqrt(size / (3 * sqrt(3)))`).
 */
private fun triangle(builder: PathBuilder, size: Double, orientation: Orientation) {
  val sqrt3 = kotlin.math.sqrt(3.0)
  val y = -kotlin.math.sqrt(size / (sqrt3 * 3.0))
  val apex = 2.0 * y
  val base = -y
  val half = -sqrt3 * y

  when (orientation) {
    Orientation.UP -> {
      builder.moveTo(0.0, apex)
      builder.lineTo(-half, base)
      builder.lineTo(half, base)
    }
    Orientation.DOWN -> {
      builder.moveTo(0.0, -apex)
      builder.lineTo(half, -base)
      builder.lineTo(-half, -base)
    }
    Orientation.RIGHT -> {
      builder.moveTo(-apex, 0.0)
      builder.lineTo(-base, half)
      builder.lineTo(-base, -half)
    }
    Orientation.LEFT -> {
      builder.moveTo(apex, 0.0)
      builder.lineTo(base, -half)
      builder.lineTo(base, half)
    }
  }
  builder.close()
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
