@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.dataflow.force.AxisForce
import dev.aster.vega.dataflow.force.CenterForce
import dev.aster.vega.dataflow.force.CollideForce
import dev.aster.vega.dataflow.force.Force
import dev.aster.vega.dataflow.force.ForceLink
import dev.aster.vega.dataflow.force.ForceNode
import dev.aster.vega.dataflow.force.LinkForce
import dev.aster.vega.dataflow.force.ManyBodyForce
import dev.aster.vega.dataflow.force.Simulation
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import kotlin.math.pow

/** The four values a simulation writes back, in the order upstream's `as` defaults to. */
private val FORCE_OUTPUT = listOf("x", "y", "vx", "vy")

/**
 * `force`: a physical simulation that decides where the nodes of a graph go.
 *
 * A mark-level transform, always — it lays out the mark's own scene *items*, reads the channels
 * they were encoded with, and writes their positions back over whatever the encoding said. That is
 * why `{"force": "x", "x": "xfocus"}` works: `xfocus` is a channel the mark encoded, not a column
 * in the data.
 *
 * A compile is one picture, so there is nothing to animate. Upstream runs the whole simulation
 * synchronously when `static` is true — `for (sim.stop(); --iters >= 0;) sim.tick()` — and when it
 * is false takes exactly **one** tick at initialisation before handing over to a timer that a
 * headless render never lets fire. Both are reproduced, which is what makes either one a fixture:
 * d3's generator is a fixed LCG and its starting arrangement is a phyllotaxis spiral, so a force
 * layout is deterministic in a way it is usually assumed not to be.
 *
 * The forces themselves are in `dev.aster.vega.dataflow.force`, ported from d3-force and the
 * quadtree underneath it.
 */
public object ForceTransform : Transform {
  override val type: String = "force"

  /**
   * Upstream publishes the simulation object itself, and every specification that names one uses it
   * the same way: `"require": {"signal": "force"}` on a later transform, which is a dependency edge
   * and not a value. So what is published is that the layout has run.
   */
  override val publishesSignal: Boolean = true

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    if (input.isEmpty()) return input
    val outputs = params.stringList("as").takeIf { it.size == FORCE_OUTPUT.size } ?: FORCE_OUTPUT

    val nodes = input.mapIndexed { index, item -> node(item, index) }
    val simulation = Simulation(nodes)
    params.number("alphaMin")?.let { simulation.alphaMin = it }
    params.number("alphaTarget")?.let { simulation.alphaTarget = it }
    // d3 stores one *minus* what a specification writes, and Vega's default is 0.4.
    simulation.velocityDecay = 1 - (params.number("velocityDecay") ?: 0.4)

    val declared = (params.fields["forces"] as? VegaValue.Arr)?.values.orEmpty()
    declared.forEachIndexed { index, definition ->
      build(definition, nodes, context)?.let { simulation.addForce("forces$index", it) }
    }

    val iterations = params.number("iterations")?.toInt()?.takeIf { it > 0 } ?: 300
    if (params.truthy("static")) {
      simulation.alpha = maxOf(simulation.alpha, params.number("alpha") ?: 1.0)
      simulation.alphaDecay = 1 - simulation.alphaMin.pow(1.0 / iterations)
      simulation.tick(iterations)
    } else {
      // The single tick upstream takes at initialisation, and the only one a headless render ever
      // sees. It runs **before** `iterations` is turned into a decay rate, which upstream does
      // afterwards for the timer's benefit — so this tick uses the 300-step default whatever the
      // specification asked for, and a chart of a hundred iterations still moves by a 300th.
      simulation.tick(1)
    }

    (params.fields["signal"] as? VegaValue.Str)?.let {
      context.setSignal(it.value, VegaValue.Bool(true))
    }

    val laid = nodes.map { node ->
      node.item.withFields(
        linkedMapOf(
          outputs[0] to VegaValue.Num(node.x),
          outputs[1] to VegaValue.Num(node.y),
          outputs[2] to VegaValue.Num(node.vx),
          outputs[3] to VegaValue.Num(node.vy),
        )
      )
    }
    publishLinks(declared, nodes, laid, context)
    return laid
  }

  /**
   * A link dataset written back with its ends resolved to the laid-out items.
   *
   * Upstream replaces `link.source` — a node id in the file — with the node **object**, in place,
   * which is what lets the `linkpath` on the next mark read `datum.source.x`. Nothing here mutates,
   * so the dataset is republished instead; the effect on the specification is the same and the
   * dependency is now visible rather than hidden in a shared tuple.
   */
  private fun publishLinks(
    declared: List<VegaValue>,
    nodes: List<ForceNode>,
    laid: List<VegaValue>,
    context: TransformContext,
  ) {
    for (definition in declared) {
      val obj = definition as? VegaValue.Obj ?: continue
      if (obj.string("force") != "link") continue
      val name = obj.string("links") ?: continue
      val rows = context.scope.dataset(name)
      if (rows.isEmpty()) continue
      val byId = identify(obj, nodes, laid)
      context.scope.setDataset(
        name,
        rows.map { row ->
          val source = byId[key(row.field("source"))]
          val target = byId[key(row.field("target"))]
          if (source == null || target == null) row
          else row.withFields(linkedMapOf("source" to source, "target" to target))
        },
      )
    }
  }

  /** Each node's laid-out item under the id a link names it by. */
  private fun identify(
    definition: VegaValue.Obj,
    nodes: List<ForceNode>,
    laid: List<VegaValue>,
  ): Map<String, VegaValue> {
    val id = definition.string("id")
    val byId = HashMap<String, VegaValue>(nodes.size)
    nodes.forEachIndexed { index, node ->
      // With no `id` a link names a node by its **position**, which is what d3's default
      // accessor reads: `d.index`, and the simulation numbered them itself.
      val at = if (id == null) index.toString() else key(node.item.field(id))
      byId[at] = laid[index]
    }
    return byId
  }

  /** Ids are matched as text, so a link naming node `3` finds it whether it wrote 3 or "3". */
  private fun key(value: VegaValue): String =
    if (value is VegaValue.Num) JsSemantics.toStringValue(value) else value.asString()

  private fun node(item: VegaValue, index: Int): ForceNode {
    val node = ForceNode(item, index)
    // An item may already carry a position — an encoding that set one, or a previous run — and a
    // simulation that starts from it moves the node from there rather than from the spiral.
    node.x = coordinate(item, "x")
    node.y = coordinate(item, "y")
    node.vx = coordinate(item, "vx")
    node.vy = coordinate(item, "vy")
    node.fx = coordinate(item, "fx").takeIf { !it.isNaN() }
    node.fy = coordinate(item, "fy").takeIf { !it.isNaN() }
    return node
  }

  private fun coordinate(item: VegaValue, name: String): Double {
    val value = item.field(name)
    if (value is VegaValue.Null) return Double.NaN
    return value.asDouble()
  }

  private fun build(
    definition: VegaValue,
    nodes: List<ForceNode>,
    context: TransformContext,
  ): Force? {
    val params = definition as? VegaValue.Obj ?: return null
    return when (val kind = params.string("force")) {
      "center" ->
        CenterForce(
          x = params.number("x") ?: 0.0,
          y = params.number("y") ?: 0.0,
          strength = params.number("strength") ?: 1.0,
        )
      "collide" ->
        CollideForce(
          radiusOf = accessor(params, "radius", 1.0, context),
          // d3's default, **not** the 0.7 Vega's schema documents: Vega only hands the forces the
          // parameters a specification actually wrote, so an omitted one falls to d3's own.
          strength = params.number("strength") ?: 1.0,
          iterations = params.number("iterations")?.toInt() ?: 1,
        )
      "nbody" ->
        ManyBodyForce(
          strengthOf = accessor(params, "strength", -30.0, context),
          theta2 = (params.number("theta") ?: 0.9).let { it * it },
          distanceMin2 = (params.number("distanceMin") ?: 1.0).let { it * it },
          distanceMax2 = params.number("distanceMax")?.let { it * it } ?: Double.POSITIVE_INFINITY,
        )
      "x",
      "y" ->
        AxisForce(
          horizontal = kind == "x",
          target = fieldAccessor(params, kind, context),
          strengthOf = accessor(params, "strength", 0.1, context),
        )
      "link" -> link(params, nodes, context)
      else -> {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "force: unrecognized force '${kind ?: ""}'; the layout ran without it",
          operator = type,
        )
        null
      }
    }
  }

  private fun link(
    params: VegaValue.Obj,
    nodes: List<ForceNode>,
    context: TransformContext,
  ): Force? {
    val name = params.string("links")
    val rows = if (name == null) emptyList() else context.scope.dataset(name)
    if (rows.isEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "force: the link force names dataset '${name ?: ""}', which has no rows; " +
          "the layout ran with no links at all",
        operator = type,
      )
      return null
    }
    val id = params.string("id")
    val byId = HashMap<String, ForceNode>(nodes.size)
    nodes.forEachIndexed { index, node ->
      byId[if (id == null) index.toString() else key(node.item.field(id))] = node
    }
    val links = mutableListOf<ForceLink>()
    var missing = 0
    for (row in rows) {
      val source = byId[key(row.field("source"))]
      val target = byId[key(row.field("target"))]
      if (source == null || target == null) missing++ else links += ForceLink(source, target)
    }
    if (missing > 0) {
      // Upstream throws — `node not found` — and draws nothing at all. Reporting and carrying on
      // is the more useful failure, but it has to be *said*, because the picture still appears.
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "force: $missing link${if (missing == 1) "" else "s"} name a node that is not in the " +
          "mark's data; they were dropped and the layout ran without them",
        operator = type,
      )
    }
    val strength = params.fields["strength"]
    return LinkForce(
      links = links,
      distanceOf = linkAccessor(params, "distance", 30.0, context),
      // Left null on purpose: d3's default is not a constant but `1 / min(degree)`, so a link
      // between two hubs pulls far less than one holding a leaf on.
      strengthOf = if (strength == null) null else linkAccessor(params, "strength", 1.0, context),
      iterations = params.number("iterations")?.toInt() ?: 1,
    )
  }

  /** A number, an `{"expr": …}` over the item, or the default. */
  private fun accessor(
    params: VegaValue.Obj,
    key: String,
    fallback: Double,
    context: TransformContext,
  ): (ForceNode, Int) -> Double {
    val expression = expressionOf(params, key, context)
    if (expression != null) return { node, _ -> expression.evaluate(node.item).number() }
    val constant = params.number(key) ?: fallback
    return { _, _ -> constant }
  }

  private fun linkAccessor(
    params: VegaValue.Obj,
    key: String,
    fallback: Double,
    context: TransformContext,
  ): (ForceLink, Int) -> Double {
    val expression = expressionOf(params, key, context)
    if (expression != null) return { _, _ -> expression.evaluate(VegaValue.Null).number() }
    val constant = params.number(key) ?: fallback
    return { _, _ -> constant }
  }

  /**
   * The `x` of an `x` force: a **channel name**, not a number.
   *
   * Vega declares it as a field, so `"x": "xfocus"` reads the item's `xfocus` — which is a channel
   * the mark encoded for exactly this purpose. A number is accepted too, and is what a force with
   * no target at all falls back to.
   */
  private fun fieldAccessor(
    params: VegaValue.Obj,
    key: String,
    context: TransformContext,
  ): (ForceNode, Int) -> Double {
    val expression = expressionOf(params, key, context)
    if (expression != null) return { node, _ -> expression.evaluate(node.item).number() }
    val declared = params.fields[key]
    if (declared is VegaValue.Str) {
      val path = declared.value
      return { node, _ -> node.item.field(path).asDouble() }
    }
    val constant = declared?.asDouble() ?: 0.0
    return { _, _ -> constant }
  }

  private fun expressionOf(
    params: VegaValue.Obj,
    key: String,
    context: TransformContext,
  ): TupleExpression? {
    val source =
      (params.fields[key] as? VegaValue.Obj)?.fields?.get("expr")?.asString()?.takeIf {
        it.isNotEmpty()
      } ?: return null
    return TupleExpression(source, context, type).takeIf { it.isUsable }
  }

  private fun VegaValue?.number(): Double = this?.asDouble() ?: Double.NaN

  /** `static` and friends: a boolean a signal may have delivered as a number or a string. */
  private fun VegaValue.Obj.truthy(key: String): Boolean =
    when (val value = fields[key]) {
      null,
      is VegaValue.Null -> false
      is VegaValue.Bool -> value.value
      is VegaValue.Num -> value.value != 0.0 && !value.value.isNaN()
      is VegaValue.Str -> value.value.isNotEmpty() && value.value != "false"
      else -> true
    }
}
