package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One edge's path text. `(sx, sy, tx, ty)`, or `(sa, sr, ta, tr)` for the radial shapes. */
private typealias LinkShape = (Double, Double, Double, Double) -> String

/**
 * `treelinks`: turns a tree back into a row per edge.
 *
 * Every node with a parent becomes one row holding the two whole rows, under `source` and `target`
 * — not their fields merged, because both sides carry the same column names and a merge would lose
 * one of each pair. `linkpath` after it reads coordinates out of those, which is why a specification
 * writes `source.x` rather than a bare field name.
 *
 * The output **replaces** the input: a `links` dataset holds edges, not nodes. That is also why the
 * tree stops here rather than being carried on — the rows are no longer the rows the nodes were
 * built from, so nothing after this could match a node to one.
 *
 * Edges come out breadth-first, the order `d3-hierarchy`'s own iterator walks in, because that is
 * the order the marks are painted in and two engines drawing the same edges in different orders
 * would still be drawing different pictures under a `zindex` or a partly transparent stroke.
 */
public object TreeLinksTransform : Transform {
  override val type: String = "treelinks"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val root =
      carriedTree(
        input,
        context,
        type,
        "treelinks needs a tree; it reads the one a 'stratify' or 'nest' built, either earlier in " +
          "this pipeline or in the dataset this one sources from",
      ) ?: return emptyList()
    context.tree = null

    val links = mutableListOf<VegaValue>()
    val queue = ArrayDeque<TreeNode>()
    queue.addLast(root)
    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      node.children?.let { queue.addAll(it) }
      val parent = node.parent ?: continue
      // Upstream keeps a lookup of the tuples actually present and emits an edge only when both
      // ends are in it, so a tree whose rows have been thinned loses those edges rather than
      // pointing at rows that are not there. A node with no row of its own — one `nest` invented
      // without `generate` — is absent in exactly that sense.
      if (node.index in input.indices && parent.index in input.indices) {
        links +=
          VegaValue.Obj(
            linkedMapOf("source" to input[parent.index], "target" to input[node.index])
          )
      }
    }
    return links
  }
}

/**
 * `linkpath`: draws the line between the two ends of an edge.
 *
 * The result is an SVG path string on each row, which a `path` mark then renders. Two parameters
 * choose the shape of it and they are not independent: `shape` says what kind of line, `orient`
 * says which way the diagram runs, and only some pairings exist. Upstream looks up
 * `"<shape>-<orient>"` first and falls back to `"<shape>"` alone, so `line`, `arc` and `curve`
 * ignore a Cartesian orientation entirely — they are symmetric in x and y — while `orthogonal` and
 * `diagonal` bend towards whichever axis the tree grows along and have no bare form at all.
 *
 * Under `radial` the four accessors stop being x and y and become **angle and radius**, which is
 * why the radial tree example passes `source.radians` and `source.radius` explicitly. The path is
 * then emitted around the origin, and the mark's own `x`/`y` move it to the centre of the chart.
 */
public object LinkPathTransform : Transform {
  override val type: String = "linkpath"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val shape = params.accessor("shape", "line")
    val orient = params.accessor("orient", "vertical")
    val path = PATHS["$shape-$orient"] ?: PATHS[shape]
    if (path == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "linkpath has no '$shape' shape for a '$orient' layout; the pairs that exist are " +
          "${PATHS.keys.joinToString(", ")}",
        operator = type,
      )
      return input
    }
    if (params.fields.containsKey("require")) {
      context.diagnostics.warn(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "linkpath 'require' names a signal that should re-run the transform when it changes; a " +
          "compile here runs every transform once, so it changed nothing",
        operator = type,
      )
    }

    val sourceX = params.accessor("sourceX", "source.x")
    val sourceY = params.accessor("sourceY", "source.y")
    val targetX = params.accessor("targetX", "target.x")
    val targetY = params.accessor("targetY", "target.y")
    val output = params.accessor("as", "path")

    var unresolved: String? = null
    fun read(row: VegaValue, accessor: String): Double {
      val value = row.field(accessor).asDouble()
      if (value.isNaN() && unresolved == null) unresolved = accessor
      return value
    }

    val result =
      input.map { row ->
        val text =
          path(
            read(row, sourceX),
            read(row, sourceY),
            read(row, targetX),
            read(row, targetY),
          )
        row.withField(output, VegaValue.Str(text))
      }
    // Reported once rather than per row: a wrong accessor is wrong for every edge, and a tree of
    // any size would otherwise bury every other diagnostic under one mistake.
    unresolved?.let {
      context.diagnostics.warn(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "linkpath found no number at '$it', so those paths came out unusable. An edge row holds " +
          "the two whole rows under 'source' and 'target', so an accessor has to reach through " +
          "one of them",
        operator = type,
      )
    }
    return result
  }

  /** Numbers go into the path text the way JavaScript concatenates them, `1` rather than `1.0`. */
  private fun n(value: Double): String = JsSemantics.numberToString(value)

  private val line: LinkShape = { sx, sy, tx, ty -> "M${n(sx)},${n(sy)}L${n(tx)},${n(ty)}" }

  /**
   * A circular arc bulging to one side, drawn as a half-turn of a circle through both ends.
   *
   * The radius is half the distance, so the arc is a semicircle whichever way the two ends lie, and
   * the sweep flag is fixed — which is what makes a pair of edges between the same two nodes
   * distinguishable in an arc diagram.
   */
  private val arc: LinkShape = { sx, sy, tx, ty ->
    val dx = tx - sx
    val dy = ty - sy
    val radius = hypot(dx, dy) / 2
    val rotation = 180 * atan2(dy, dx) / PI
    "M${n(sx)},${n(sy)}A${n(radius)},${n(radius)} ${n(rotation)} 0 1 ${n(tx)},${n(ty)}"
  }

  private val curve: LinkShape = { sx, sy, tx, ty ->
    val dx = tx - sx
    val dy = ty - sy
    val ix = 0.2 * (dx + dy)
    val iy = 0.2 * (dy - dx)
    "M${n(sx)},${n(sy)}C${n(sx + ix)},${n(sy + iy)} ${n(tx + iy)},${n(ty - ix)} ${n(tx)},${n(ty)}"
  }

  private val orthogonalX: LinkShape = { sx, sy, tx, ty ->
    "M${n(sx)},${n(sy)}V${n(ty)}H${n(tx)}"
  }

  private val orthogonalY: LinkShape = { sx, sy, tx, ty ->
    "M${n(sx)},${n(sy)}H${n(tx)}V${n(ty)}"
  }

  private val diagonalX: LinkShape = { sx, sy, tx, ty ->
    val m = (sx + tx) / 2
    "M${n(sx)},${n(sy)}C${n(m)},${n(sy)} ${n(m)},${n(ty)} ${n(tx)},${n(ty)}"
  }

  private val diagonalY: LinkShape = { sx, sy, tx, ty ->
    val m = (sy + ty) / 2
    "M${n(sx)},${n(sy)}C${n(sx)},${n(m)} ${n(tx)},${n(m)} ${n(tx)},${n(ty)}"
  }

  /** The Cartesian shapes reused in polar coordinates: the caller's pairs are angle and radius. */
  private fun radial(shape: LinkShape): LinkShape = { sa, sr, ta, tr ->
    shape(sr * cos(sa), sr * sin(sa), tr * cos(ta), tr * sin(ta))
  }

  /**
   * The radial elbow: around the circle at the parent's radius, then straight out to the child.
   *
   * The sweep flag picks the shorter way round, which is what keeps an edge inside the sector it
   * belongs to instead of sending it the long way across the diagram.
   */
  private val orthogonalRadial: LinkShape = { sa, sr, ta, tr ->
    val sc = cos(sa)
    val ss = sin(sa)
    val tc = cos(ta)
    val ts = sin(ta)
    val sweep = if (abs(ta - sa) > PI) ta <= sa else ta > sa
    "M${n(sr * sc)},${n(sr * ss)}" +
      "A${n(sr)},${n(sr)} 0 0,${if (sweep) 1 else 0} ${n(sr * tc)},${n(sr * ts)}" +
      "L${n(tr * tc)},${n(tr * ts)}"
  }

  /** The radial diagonal bends at the mean radius, where a Cartesian one bends at the mean x. */
  private val diagonalRadial: LinkShape = { sa, sr, ta, tr ->
    val sc = cos(sa)
    val ss = sin(sa)
    val tc = cos(ta)
    val ts = sin(ta)
    val mr = (sr + tr) / 2
    "M${n(sr * sc)},${n(sr * ss)}" +
      "C${n(mr * sc)},${n(mr * ss)} ${n(mr * tc)},${n(mr * ts)} ${n(tr * tc)},${n(tr * ts)}"
  }

  /** Every pairing upstream defines, in its own order. */
  private val PATHS: Map<String, LinkShape> =
    linkedMapOf(
      "line" to line,
      "line-radial" to radial(line),
      "arc" to arc,
      "arc-radial" to radial(arc),
      "curve" to curve,
      "curve-radial" to radial(curve),
      "orthogonal-horizontal" to orthogonalX,
      "orthogonal-vertical" to orthogonalY,
      "orthogonal-radial" to orthogonalRadial,
      "diagonal-horizontal" to diagonalX,
      "diagonal-vertical" to diagonalY,
      "diagonal-radial" to diagonalRadial,
    )

  /** Upstream's `_.x || default`: an empty string is as absent as a missing parameter. */
  private fun VegaValue.Obj.accessor(key: String, fallback: String): String =
    string(key)?.takeIf { it.isNotEmpty() } ?: fallback
}
