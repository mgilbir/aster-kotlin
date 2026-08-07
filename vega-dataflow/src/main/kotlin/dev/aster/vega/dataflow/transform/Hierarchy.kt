package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field

/**
 * `stratify`: reads a parent-child structure out of a flat table.
 *
 * Two columns do it — one holding each row's own key and one holding its parent's. The row whose
 * parent key is empty is the root, and there must be exactly one; every other parent key must name
 * a row that exists, and no key may appear twice. Each of those is an error rather than a
 * best-effort repair, because a tree assembled from ambiguous links is not a smaller tree, it is a
 * different one, and every layout drawn from it would be silently wrong.
 *
 * The output is the input, unchanged and in order. The tree it built is carried to the layout
 * transform after it and is never visible to a mark.
 */
public object StratifyTransform : Transform {
  override val type: String = "stratify"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val key = params.string("key")
    val parentKey = params.string("parentKey")
    if (key.isNullOrEmpty() || parentKey.isNullOrEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "stratify needs 'key' and 'parentKey'",
        operator = type,
      )
      return input
    }

    val nodes = input.mapIndexed { index, row -> TreeNode(index, row) }
    val byKey = HashMap<String, TreeNode?>(nodes.size)
    val parentOf = arrayOfNulls<String>(nodes.size)
    for ((index, row) in input.withIndex()) {
      val id = row.field(key).identifier()
      if (id != null) {
        // A repeated key is recorded as ambiguous rather than overwritten, so it is reported at
        // the point a child tries to use it and the message can name that child's parent.
        byKey[id] = if (byKey.containsKey(id)) null else nodes[index]
      }
      parentOf[index] = row.field(parentKey).identifier()
    }

    var root: TreeNode? = null
    for ((index, node) in nodes.withIndex()) {
      val parentId = parentOf[index]
      if (parentId == null) {
        if (root != null) {
          context.diagnostics.error(
            DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
            "stratify found more than one row with no parent; a tree has exactly one root",
            operator = type,
          )
          return input
        }
        root = node
      } else {
        if (!byKey.containsKey(parentId)) {
          context.diagnostics.error(
            DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
            "stratify could not find a row with key '$parentId' to be a parent",
            operator = type,
          )
          return input
        }
        val parent = byKey[parentId]
        if (parent == null) {
          context.diagnostics.error(
            DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
            "stratify found more than one row with key '$parentId', so it is not clear which is " +
              "the parent",
            operator = type,
          )
          return input
        }
        (parent.children ?: mutableListOf<TreeNode>().also { parent.children = it }) += node
        node.parent = parent
      }
    }

    if (root == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "stratify found no row without a parent, so the links form a cycle rather than a tree",
        operator = type,
      )
      return input
    }

    var reached = 0
    root.eachBefore { node ->
      node.depth = node.parent?.let { it.depth + 1 } ?: 0
      reached++
    }
    root.eachBefore { TreeNode.computeHeight(it) }
    if (reached < nodes.size) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "stratify could not reach every row from the root; ${nodes.size - reached} row(s) are in " +
          "a cycle",
        operator = type,
      )
      return input
    }

    root.sourceSize = input.size
    context.tree = root
    return input
  }

  /** Upstream's rule: null, absent and the empty string all mean "no key". */
  private fun VegaValue.identifier(): String? =
    when (this) {
      is VegaValue.Null -> null
      else -> asString().takeIf { it.isNotEmpty() }
    }
}

/**
 * `nest`: builds a tree by grouping, where `stratify` builds one from explicit links.
 *
 * Each key adds a level: rows sharing the first key become one branch, and within it rows sharing
 * the second become a sub-branch, and so on down to the rows themselves as leaves. The interior
 * nodes are invented by the transform and are **not** in the output unless `generate` asks for them
 * — which is the parameter to reach for when a treemap needs to draw the groups as well as the rows
 * inside them.
 */
public object NestTransform : Transform {
  override val type: String = "nest"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val keys = params.stringList("keys")
    val generate = params.boolean("generate") ?: false
    val root = TreeNode(-1, null)

    fun build(rows: List<Pair<Int, VegaValue>>, depth: Int, parent: TreeNode) {
      if (depth >= keys.size) {
        val leaves = mutableListOf<TreeNode>()
        for ((index, row) in rows) {
          val leaf = TreeNode(index, row)
          leaf.parent = parent
          leaf.depth = parent.depth + 1
          leaves += leaf
        }
        if (leaves.isNotEmpty()) parent.children = leaves
        return
      }
      val groups = LinkedHashMap<String, MutableList<Pair<Int, VegaValue>>>()
      for (entry in rows) {
        groups.getOrPut(entry.second.field(keys[depth]).asString()) { mutableListOf() } += entry
      }
      val children = mutableListOf<TreeNode>()
      for ((value, members) in groups) {
        val node = TreeNode(-1, VegaValue.Obj(mapOf("key" to VegaValue.Str(value))))
        node.parent = parent
        node.depth = parent.depth + 1
        children += node
        build(members, depth + 1, node)
      }
      if (children.isNotEmpty()) parent.children = children
    }

    build(input.withIndex().map { it.index to it.value }, 0, root)
    root.eachBefore { TreeNode.computeHeight(it) }
    root.sourceSize = input.size
    context.tree = root
    if (!generate) return input

    // The interior nodes join the data, in the order upstream emits them: level by level from the
    // root, which is *not* the pre-order the layouts walk in.
    context.diagnostics.warn(
      DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
      "nest 'generate' adds a row per interior node carrying its 'key' and whatever the layout " +
        "writes; upstream also nests the group's own rows under 'values', which is a tree inside " +
        "a column and has no equivalent here",
      operator = type,
    )
    val generated = mutableListOf<VegaValue>()
    var level = listOf(root)
    while (level.isNotEmpty()) {
      val next = mutableListOf<TreeNode>()
      for (node in level) {
        if (node.children != null) {
          node.assignIndex(input.size + generated.size)
          generated += node.datum ?: VegaValue.Obj(emptyMap())
          next += node.children!!
        }
      }
      level = next
    }
    root.sourceSize = input.size + generated.size
    return input + generated
  }
}

/**
 * The tree an earlier `stratify` or `nest` built, checked against the rows it is about to be used
 * on.
 *
 * A hierarchy transform hands its rows on unchanged, and everything that reads the tree afterwards
 * — the four layouts and `treelinks` — reaches a row through the position the node recorded.
 * Upstream can be laxer: it mutates tuples in place and each node holds its own tuple, so it
 * recognises a row by identity however the list has since been rearranged. Transforms here copy
 * instead, which is what makes a pipeline reproducible, and it leaves position as the only
 * remaining link between a node and its row.
 *
 * So a transform that adds or drops rows between the two breaks the correspondence, and would break
 * it silently: every node would write its coordinates onto some other node's row, or a link would
 * join the wrong pair. That is refused here rather than guessed at.
 */
internal fun carriedTree(
  input: List<VegaValue>,
  context: TransformContext,
  type: String,
  missing: String,
): TreeNode? {
  val root = context.tree as? TreeNode
  if (root == null) {
    context.diagnostics.error(DiagnosticCodes.TRANSFORM_INVALID_PARAMETER, missing, operator = type)
    return null
  }
  if (root.sourceSize != input.size) {
    context.diagnostics.error(
      DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
      "$type was given ${input.size} row(s) where the tree was built over ${root.sourceSize}, so " +
        "something between the two added or removed rows and no node can be matched to its row " +
        "any more",
      operator = type,
    )
    return null
  }
  return root
}

/**
 * The shared body of every layout transform.
 *
 * Each one takes the tree the pipeline is carrying, sizes its nodes by `field` (or by leaf count if
 * there is none), optionally sorts each node's children, runs its own geometry, and writes the
 * result back onto the rows the nodes came from. The last output name is always the number of
 * children, which is how a mark tells a leaf from a branch without ever seeing the tree.
 *
 * A function rather than a base class, so the tree types stay inside this module: a public abstract
 * transform would have to publish [TreeNode] and [TreeField] along with it, and nothing outside can
 * do anything with either.
 */
internal fun applyTreeLayout(
  input: List<VegaValue>,
  params: VegaValue.Obj,
  context: TransformContext,
  type: String,
  outputs: List<TreeField>,
  defaultNames: List<String>,
  layout: (TreeNode) -> Boolean,
): List<VegaValue> {
  val root =
    carriedTree(
      input,
      context,
      type,
      "$type needs a tree; put a 'stratify' or 'nest' transform before it",
    ) ?: return input

  val measure = params.string("field")
  if (measure.isNullOrEmpty()) {
    root.count()
  } else {
    root.sum { datum -> if (datum == null) 0.0 else datum.field(measure).asDouble() }
  }

  (params.fields["sort"] as? VegaValue.Obj)?.let { sort ->
    val fields = sort.stringList("field")
    val orders = sort.stringList("order")
    if (fields.isNotEmpty()) {
      root.sortChildren { a, b ->
        var result = 0
        for ((index, path) in fields.withIndex()) {
          result = compareNodeField(a, b, path)
          if (orders.getOrNull(index) == "descending") result = -result
          if (result != 0) break
        }
        result
      }
    }
  }

  if (!layout(root)) return input

  val names = params.stringList("as").takeIf { it.size > outputs.size } ?: defaultNames
  val updates = HashMap<Int, Map<String, VegaValue>>()
  root.eachBefore { node ->
    if (node.index >= 0) {
      val fields = LinkedHashMap<String, VegaValue>(outputs.size + 1)
      outputs.forEachIndexed { i, output ->
        fields[names[i]] = VegaValue.Num(TreeLayouts.read(node, output))
      }
      fields[names[outputs.size]] = VegaValue.Num(node.childCount.toDouble())
      updates[node.index] = fields
    }
  }
  return input.mapIndexed { index, row -> updates[index]?.let { row.withFields(it) } ?: row }
}

/** Numbers compare as numbers and everything else as text, which is how upstream's sort behaves. */
private fun compareNodeField(a: TreeNode, b: TreeNode, path: String): Int {
  val av = a.datum
  val bv = b.datum
  val an = if (av == null) Double.NaN else av.field(path).asDouble()
  val bn = if (bv == null) Double.NaN else bv.field(path).asDouble()
  if (!an.isNaN() && !bn.isNaN()) return an.compareTo(bn)
  val at = if (av == null) "" else av.field(path).asString()
  val bt = if (bv == null) "" else bv.field(path).asString()
  return at.compareTo(bt)
}

/** `size` defaults to a unit square, which is what makes a layout scalable by a scale. */
private fun sizeOf(params: VegaValue.Obj): Pair<Double, Double> {
  val given = params.numberList("size")
  return if (given.size >= 2) given[0] to given[1] else 1.0 to 1.0
}

private val RECTANGLE_OUTPUTS =
  listOf(TreeField.X0, TreeField.Y0, TreeField.X1, TreeField.Y1, TreeField.DEPTH)

private val RECTANGLE_NAMES = listOf("x0", "y0", "x1", "y1", "depth", "children")

/**
 * `treemap`: nested rectangles, each sized by the quantity it holds.
 *
 * `method` picks the tiling, and the choice is a real trade rather than a style. `squarify` (the
 * default) makes every rectangle as close to square as it can, because area is what a reader judges
 * and a sliver's area cannot be judged — but it scrambles sibling order. `dice`, `slice` and
 * `slicedice` keep the order exactly and will happily produce slivers. `binary` keeps order too and
 * splits recursively, which is the middle ground.
 *
 * `resquarify` exists upstream to keep rectangles stable while data animates between frames.
 * Nothing animates here yet — each compile starts from nothing — so it tiles identically to
 * `squarify`, and says so rather than silently accepting a parameter that cannot mean anything.
 */
public object TreemapTransform : Transform {
  override val type: String = "treemap"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> =
    applyTreeLayout(input, params, context, type, RECTANGLE_OUTPUTS, RECTANGLE_NAMES) { root ->
      val tile = tiling(params, context) ?: return@applyTreeLayout false
      // `padding` is shorthand for the inner and all four outer paddings at once; naming a
      // specific one overrides it.
      val all = params.number("padding") ?: 0.0
      val outer = params.number("paddingOuter") ?: all
      val padding =
        TreeLayouts.Padding(
          inner = params.number("paddingInner") ?: all,
          top = params.number("paddingTop") ?: outer,
          right = params.number("paddingRight") ?: outer,
          bottom = params.number("paddingBottom") ?: outer,
          left = params.number("paddingLeft") ?: outer,
        )
      val (width, height) = sizeOf(params)
      TreeLayouts.treemap(root, width, height, padding, params.boolean("round") ?: false, tile)
      true
    }

  private fun tiling(
    params: VegaValue.Obj,
    context: TransformContext,
  ): ((TreeNode, Double, Double, Double, Double) -> Unit)? {
    val ratio = params.number("ratio")?.takeIf { it > 1 } ?: TreeLayouts.PHI
    val squarify: (TreeNode, Double, Double, Double, Double) -> Unit = { p, a, b, c, d ->
      TreeLayouts.squarify(ratio, p, a, b, c, d)
    }
    return when (val method = params.string("method") ?: "squarify") {
      "squarify" -> squarify
      "resquarify" -> {
        context.diagnostics.warn(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "treemap method 'resquarify' keeps rectangles stable across an animation; nothing " +
            "animates here, so it tiled as 'squarify'",
          operator = type,
        )
        squarify
      }
      "binary" -> { p, a, b, c, d ->
        TreeLayouts.binary(p, a, b, c, d)
      }
      "dice" -> { p, a, b, c, d ->
        TreeLayouts.dice(p, a, b, c, d)
      }
      "slice" -> { p, a, b, c, d ->
        TreeLayouts.slice(p, a, b, c, d)
      }
      "slicedice" -> { p, a, b, c, d ->
        if (p.depth and 1 == 1) TreeLayouts.slice(p, a, b, c, d)
        else TreeLayouts.dice(p, a, b, c, d)
      }
      else -> {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "treemap method '$method' is not one of binary, dice, resquarify, slice, slicedice, " +
            "squarify",
          operator = type,
        )
        null
      }
    }
  }
}

/**
 * `partition`: an icicle plot — every level of the tree gets an equal band, divided by value.
 *
 * Where a treemap spends all its space on quantity and lets depth fall where it may, this spends
 * one whole axis on depth. The same tree through the two looks like two different datasets, and
 * which to reach for depends on whether the question is "how big" or "where in the structure".
 */
public object PartitionTransform : Transform {
  override val type: String = "partition"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> =
    applyTreeLayout(input, params, context, type, RECTANGLE_OUTPUTS, RECTANGLE_NAMES) { root ->
      val (width, height) = sizeOf(params)
      TreeLayouts.partition(
        root,
        width,
        height,
        params.number("padding") ?: 0.0,
        params.boolean("round") ?: false,
      )
      true
    }
}

/**
 * `pack`: nested circles, each sized by the quantity it holds.
 *
 * A leaf's radius is the **square root** of its value, so that area carries the quantity — a radius
 * proportional to value would square the difference and make a large leaf look far larger than it
 * is. The same reasoning as a treemap; the difference is that circles cannot tile, so a pack always
 * wastes space between siblings and is read as grouping rather than as proportion.
 *
 * `radius` names a field to size leaves directly instead, in which case nothing is rescaled and the
 * layout may run outside `size`.
 */
public object PackTransform : Transform {
  override val type: String = "pack"

  /** The names `radius` can actually reach, being the layout node's own properties. */
  private val NODE_PROPERTIES = listOf("value", "depth", "height", "x", "y", "r")

  /**
   * Upstream resolves `radius` against the **layout node**, not against the row.
   *
   * So it reads the node's own `value`, `depth`, `height`, `x`, `y` or `r`, and any other name
   * comes back as zero however plainly it names a data column. Matched rather than corrected: a
   * pack that quietly sized itself differently from Vega would be worse than one that does the
   * surprising thing and reports it.
   */
  private fun radiusAccessor(
    field: String?,
    context: TransformContext,
  ): ((TreeNode) -> Double)? {
    val name = field?.takeIf { it.isNotEmpty() } ?: return null
    if (name !in NODE_PROPERTIES) {
      context.diagnostics.warn(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "pack 'radius' names '$name', which is read off the layout node rather than the row; " +
          "upstream does the same, so every radius came out zero. Only " +
          "${NODE_PROPERTIES.joinToString(", ")} resolve",
        operator = type,
      )
      return { 0.0 }
    }
    return { node -> nodeProperty(node, name) }
  }

  private fun nodeProperty(node: TreeNode, name: String): Double =
    when (name) {
      "value" -> node.value
      "depth" -> node.depth.toDouble()
      "height" -> node.height.toDouble()
      "x" -> node.x
      "y" -> node.y
      else -> node.r
    }

  private val OUTPUTS = listOf(TreeField.X, TreeField.Y, TreeField.R, TreeField.DEPTH)

  private val NAMES = listOf("x", "y", "r", "depth", "children")

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> =
    applyTreeLayout(input, params, context, type, OUTPUTS, NAMES) { root ->
      val (width, height) = sizeOf(params)
      val radiusField = params.string("radius")?.takeIf { it.isNotEmpty() }
      val radiusOf = radiusAccessor(params.string("radius"), context)
      TreeCircles.pack(root, width, height, params.number("padding") ?: 0.0, radiusOf)
      true
    }
}

/**
 * `tree`: a node-link diagram, either as a tidy tree or as a dendrogram.
 *
 * The two methods differ in where the leaves go, and that difference is the whole choice. `tidy`
 * (Reingold-Tilford, in Buchheim's linear-time form) puts every node at its own depth and
 * guarantees that identical subtrees are drawn identically wherever they appear — which is what
 * makes structure comparable by eye. `cluster` puts every **leaf** on the last row whatever its
 * depth, because in a clustering result the leaves are the things being compared and the interior
 * nodes are only the joins.
 *
 * `size` fits the whole diagram to a box; `nodeSize` fixes the spacing per node instead and lets
 * the diagram be whatever size that comes to, which is what a scrollable tree wants.
 */
public object TreeTransform : Transform {
  override val type: String = "tree"

  private val OUTPUTS = listOf(TreeField.X, TreeField.Y, TreeField.DEPTH)

  private val NAMES = listOf("x", "y", "depth", "children")

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> =
    applyTreeLayout(input, params, context, type, OUTPUTS, NAMES) { root ->
      val nodeSize = params.numberList("nodeSize").takeIf { it.size >= 2 }?.let { it[0] to it[1] }
      val (width, height) = sizeOf(params)
      // `separation` off packs cousins as tightly as siblings, which is what a dense tree wants and
      // what makes two neighbouring subtrees hard to tell apart.
      val separate = params.boolean("separation") != false
      when (val method = params.string("method") ?: "tidy") {
        "tidy" -> TreeCircles.tidy(root, width, height, nodeSize, separate)
        "cluster" -> TreeCircles.cluster(root, width, height, nodeSize, separate)
        else -> {
          context.diagnostics.error(
            DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
            "tree method '$method' is neither 'tidy' nor 'cluster'",
            operator = type,
          )
          return@applyTreeLayout false
        }
      }
      true
    }
}
