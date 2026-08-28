package dev.aster.vega.dataflow.force

import dev.aster.vega.model.VegaValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One node of a force simulation: a mutable point that remembers where it came from.
 *
 * The whole layout is a few hundred of these being nudged, so they are mutable and reused. That is
 * the one place in this package where the engine's usual rule — transforms copy, never mutate —
 * does not apply, and it is contained: nothing outside a `force` transform ever sees a [ForceNode].
 */
internal class ForceNode(
  /** The item the simulation is laying out, kept so the result can be written back onto it. */
  val item: VegaValue,
  var index: Int,
) {
  var x: Double = Double.NaN
  var y: Double = Double.NaN
  var vx: Double = Double.NaN
  var vy: Double = Double.NaN

  /** A pinned position, which the simulation copies into `x`/`y` instead of integrating. */
  var fx: Double? = null
  var fy: Double? = null
}

/**
 * d3-force's own generator, which is why a force layout is reproducible at all.
 *
 * Not `Math.random`: d3 uses a fixed linear congruential generator seeded at 1, so a simulation run
 * twice on the same input gives the same picture. Every one of its consumers is a *jiggle* — the
 * nudge that separates two nodes sitting exactly on top of each other — so the sequence only
 * matters where the arithmetic would otherwise divide by zero. It still has to be the same
 * sequence, drawn in the same order, or the two engines part company at the first coincidence.
 */
internal class Lcg {
  private var state = 1L

  fun next(): Double {
    state = (A * state + C) % M
    return state.toDouble() / M
  }

  /** `(random() - 0.5) * 1e-6` — d3's `jiggle`. */
  fun jiggle(): Double = (next() - 0.5) * 1e-6

  private companion object {
    const val A = 1664525L
    const val C = 1013904223L
    const val M = 4294967296L // 2^32
  }
}

/** One force: a function of alpha that adds to every node's velocity. */
internal interface Force {
  fun initialize(nodes: List<ForceNode>, random: Lcg)

  fun apply(alpha: Double)
}

/**
 * d3's `forceSimulation`, minus the timer.
 *
 * There is no clock here and nothing to animate towards: a compile produces one picture, so the
 * simulation is stepped a fixed number of times and read. That is exactly what upstream does for a
 * `static` force — `for (sim.stop(); --iters >= 0;) sim.tick()` — and for a running one it is the
 * single `tick()` upstream takes at init before handing over to its timer, which is all a headless
 * render ever sees.
 *
 * Ported from `d3-force/src/simulation.js`.
 */
internal class Simulation(private val nodes: List<ForceNode>) {
  var alpha: Double = 1.0
  var alphaMin: Double = 0.001
  var alphaDecay: Double = 1 - 0.001.pow(1.0 / 300)
  var alphaTarget: Double = 0.0

  /**
   * **One minus** the decay a specification writes, which is d3's internal convention: its setter
   * stores `1 - _` and its getter gives it back. Missing that makes every node move 2.5 times too
   * far per tick.
   */
  var velocityDecay: Double = 0.6

  private val forces = LinkedHashMap<String, Force>()
  private val random = Lcg()

  init {
    initializeNodes()
  }

  fun addForce(name: String, force: Force) {
    force.initialize(nodes, random)
    forces[name] = force
  }

  fun tick(iterations: Int = 1) {
    repeat(iterations) {
      alpha += (alphaTarget - alpha) * alphaDecay
      for (force in forces.values) force.apply(alpha)
      for (node in nodes) {
        val fx = node.fx
        if (fx == null) {
          node.vx *= velocityDecay
          node.x += node.vx
        } else {
          node.x = fx
          node.vx = 0.0
        }
        val fy = node.fy
        if (fy == null) {
          node.vy *= velocityDecay
          node.y += node.vy
        } else {
          node.y = fy
          node.vy = 0.0
        }
      }
    }
  }

  /**
   * Where a node starts when nothing says: a phyllotaxis spiral around the origin.
   *
   * Deterministic, and deliberately not random — the golden angle spreads the first n points evenly
   * however many there are, so a simulation of any size starts from a fair arrangement.
   */
  private fun initializeNodes() {
    nodes.forEachIndexed { index, node ->
      node.index = index
      node.fx?.let { node.x = it }
      node.fy?.let { node.y = it }
      if (node.x.isNaN() || node.y.isNaN()) {
        val radius = INITIAL_RADIUS * sqrt(0.5 + index)
        val angle = index * INITIAL_ANGLE
        node.x = radius * cos(angle)
        node.y = radius * sin(angle)
      }
      if (node.vx.isNaN() || node.vy.isNaN()) {
        node.vx = 0.0
        node.vy = 0.0
      }
    }
  }

  private companion object {
    const val INITIAL_RADIUS = 10.0
    val INITIAL_ANGLE = PI * (3 - sqrt(5.0))
  }
}

/** `center`: shifts every node so the centre of mass lands where the specification says. */
internal class CenterForce(
  private val x: Double,
  private val y: Double,
  private val strength: Double,
) : Force {
  private var nodes: List<ForceNode> = emptyList()

  override fun initialize(nodes: List<ForceNode>, random: Lcg) {
    this.nodes = nodes
  }

  override fun apply(alpha: Double) {
    val n = nodes.size
    var sx = 0.0
    var sy = 0.0
    for (node in nodes) {
      sx += node.x
      sy += node.y
    }
    // Not a velocity: `center` moves the positions themselves, so it never fights the decay.
    sx = (sx / n - x) * strength
    sy = (sy / n - y) * strength
    for (node in nodes) {
      node.x -= sx
      node.y -= sy
    }
  }
}

/** `x` and `y`: a spring towards a target coordinate, one axis each. */
internal class AxisForce(
  private val horizontal: Boolean,
  private val target: (ForceNode, Int) -> Double,
  private val strengthOf: (ForceNode, Int) -> Double,
) : Force {
  private var nodes: List<ForceNode> = emptyList()
  private var strengths = DoubleArray(0)
  private var targets = DoubleArray(0)

  override fun initialize(nodes: List<ForceNode>, random: Lcg) {
    this.nodes = nodes
    strengths = DoubleArray(nodes.size)
    targets = DoubleArray(nodes.size)
    nodes.forEachIndexed { index, node ->
      val at = target(node, index)
      targets[index] = at
      // A node whose target is missing is not pulled at all, rather than pulled towards NaN.
      strengths[index] = if (at.isNaN()) 0.0 else strengthOf(node, index)
    }
  }

  override fun apply(alpha: Double) {
    nodes.forEachIndexed { index, node ->
      if (horizontal) {
        node.vx += (targets[index] - node.x) * strengths[index] * alpha
      } else {
        node.vy += (targets[index] - node.y) * strengths[index] * alpha
      }
    }
  }
}

/**
 * `collide`: pushes overlapping circles apart, in proportion to their areas.
 *
 * Reads and writes the *projected* position — `x + vx`, where the node is about to be — so a pair
 * that would collide next tick is separated this one.
 */
internal class CollideForce(
  private val radiusOf: (ForceNode, Int) -> Double,
  private val strength: Double,
  private val iterations: Int,
) : Force {
  private var nodes: List<ForceNode> = emptyList()
  private var radii = DoubleArray(0)
  private var random: Lcg = Lcg()

  override fun initialize(nodes: List<ForceNode>, random: Lcg) {
    this.nodes = nodes
    this.random = random
    radii = DoubleArray(nodes.size)
    nodes.forEachIndexed { index, node -> radii[node.index] = radiusOf(node, index) }
  }

  override fun apply(alpha: Double) {
    repeat(iterations) {
      val tree = Quadtree({ it.x + it.vx }, { it.y + it.vy })
      tree.addAll(nodes)
      tree.visitAfter { quad -> prepare(quad) }
      for (node in nodes) {
        val ri = radii[node.index]
        val ri2 = ri * ri
        val xi = node.x + node.vx
        val yi = node.y + node.vy
        tree.visit { quad, x0, y0, x1, y1 -> apply(quad, x0, y0, x1, y1, node, ri, ri2, xi, yi) }
      }
    }
  }

  @Suppress("LongParameterList")
  private fun apply(
    quad: QuadNode,
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    node: ForceNode,
    ri: Double,
    ri2: Double,
    xi: Double,
    yi: Double,
  ): Boolean {
    val data = quad.data
    var rj = quad.r
    var r = ri + rj
    if (data != null) {
      // Each pair is handled once, by the lower-indexed node, and both ends are moved.
      if (data.index > node.index) {
        var x = xi - data.x - data.vx
        var y = yi - data.y - data.vy
        var l = x * x + y * y
        if (l < r * r) {
          if (x == 0.0) {
            x = random.jiggle()
            l += x * x
          }
          if (y == 0.0) {
            y = random.jiggle()
            l += y * y
          }
          l = sqrt(l)
          l = (r - l) / l * strength
          x *= l
          y *= l
          rj *= rj
          r = rj / (ri2 + rj)
          node.vx += x * r
          node.vy += y * r
          r = 1 - r
          data.vx -= x * r
          data.vy -= y * r
        }
      }
      return false
    }
    // A quadrant no circle of radius `ri` can reach is skipped whole.
    return x0 > xi + r || x1 < xi - r || y0 > yi + r || y1 < yi - r
  }

  private fun prepare(quad: QuadNode) {
    val data = quad.data
    if (data != null) {
      quad.r = radii[data.index]
      return
    }
    quad.r = 0.0
    for (child in quad.children!!) {
      if (child != null && child.r > quad.r) quad.r = child.r
    }
  }
}

/** One edge of a `link` force, with its ends already resolved to nodes. */
internal class ForceLink(
  val source: ForceNode,
  val target: ForceNode,
  /**
   * The row the link was read from, which its `distance` and `strength` expressions read.
   *
   * Upstream's `setForceParam` wraps an expression accessor as `d => v(d, _)` and d3-force calls it
   * with the **link**, so `datum.weight` is the link row's `weight`. Without the row there was
   * nothing to evaluate against and every such expression saw an empty datum: `datum.weight` was
   * NaN for every link, silently, and the springs all came out the same length.
   */
  val datum: VegaValue = VegaValue.Null,
)

/** `link`: a spring of a given rest length between two nodes. */
internal class LinkForce(
  private val links: List<ForceLink>,
  private val distanceOf: (ForceLink, Int) -> Double,
  private val strengthOf: ((ForceLink, Int) -> Double)?,
  private val iterations: Int,
) : Force {
  private var strengths = DoubleArray(0)
  private var distances = DoubleArray(0)
  private var bias = DoubleArray(0)
  private var random: Lcg = Lcg()

  override fun initialize(nodes: List<ForceNode>, random: Lcg) {
    this.random = random
    val count = IntArray(nodes.size)
    for (link in links) {
      count[link.source.index]++
      count[link.target.index]++
    }
    bias = DoubleArray(links.size)
    strengths = DoubleArray(links.size)
    distances = DoubleArray(links.size)
    links.forEachIndexed { index, link ->
      val source = count[link.source.index]
      val target = count[link.target.index]
      // The better-connected end moves less: a hub stays put and its leaves swing.
      bias[index] = source.toDouble() / (source + target)
      strengths[index] = strengthOf?.invoke(link, index) ?: (1.0 / minOf(source, target).toDouble())
      distances[index] = distanceOf(link, index)
    }
  }

  override fun apply(alpha: Double) {
    repeat(iterations) {
      links.forEachIndexed { index, link ->
        val source = link.source
        val target = link.target
        var x = target.x + target.vx - source.x - source.vx
        if (x == 0.0 || x.isNaN()) x = random.jiggle()
        var y = target.y + target.vy - source.y - source.vy
        if (y == 0.0 || y.isNaN()) y = random.jiggle()
        var l = sqrt(x * x + y * y)
        l = (l - distances[index]) / l * alpha * strengths[index]
        x *= l
        y *= l
        var b = bias[index]
        target.vx -= x * b
        target.vy -= y * b
        b = 1 - b
        source.vx += x * b
        source.vy += y * b
      }
    }
  }
}

/**
 * `nbody`: every node attracts or repels every other, approximated by Barnes–Hut.
 *
 * A whole quadrant is treated as one charge at its centre of mass whenever it is far enough away —
 * "far enough" being `width² / theta² < distance²`. Which quadrants pass that test depends on the
 * tree, which depends on the positions, which is why nothing here can be reordered or simplified.
 */
internal class ManyBodyForce(
  private val strengthOf: (ForceNode, Int) -> Double,
  private val theta2: Double,
  private val distanceMin2: Double,
  private val distanceMax2: Double,
) : Force {
  private var nodes: List<ForceNode> = emptyList()
  private var strengths = DoubleArray(0)
  private var random: Lcg = Lcg()

  override fun initialize(nodes: List<ForceNode>, random: Lcg) {
    this.nodes = nodes
    this.random = random
    strengths = DoubleArray(nodes.size)
    nodes.forEachIndexed { index, node -> strengths[node.index] = strengthOf(node, index) }
  }

  override fun apply(alpha: Double) {
    val tree = Quadtree({ it.x }, { it.y })
    tree.addAll(nodes)
    tree.visitAfter { quad -> accumulate(quad) }
    for (node in nodes) {
      tree.visit { quad, x1, _, x2, _ -> apply(quad, x1, x2, node, alpha) }
    }
  }

  private fun accumulate(quad: QuadNode) {
    var strength = 0.0
    if (quad.isBranch) {
      var weight = 0.0
      var x = 0.0
      var y = 0.0
      for (child in quad.children!!) {
        if (child == null) continue
        val c = abs(child.value)
        if (c == 0.0) continue
        strength += child.value
        weight += c
        x += c * child.cx
        y += c * child.cy
      }
      quad.cx = x / weight
      quad.cy = y / weight
    } else {
      quad.cx = quad.data!!.x
      quad.cy = quad.data.y
      var leaf: QuadNode? = quad
      while (leaf != null) {
        strength += strengths[leaf.data!!.index]
        leaf = leaf.next
      }
    }
    quad.value = strength
  }

  private fun apply(
    quad: QuadNode,
    x1: Double,
    x2: Double,
    node: ForceNode,
    alpha: Double,
  ): Boolean {
    if (quad.value == 0.0) return true

    var x = quad.cx - node.x
    var y = quad.cy - node.y
    val w = x2 - x1
    var l = x * x + y * y

    if (w * w / theta2 < l) {
      if (l < distanceMax2) {
        if (x == 0.0) {
          x = random.jiggle()
          l += x * x
        }
        if (y == 0.0) {
          y = random.jiggle()
          l += y * y
        }
        if (l < distanceMin2) l = sqrt(distanceMin2 * l)
        node.vx += x * quad.value * alpha / l
        node.vy += y * quad.value * alpha / l
      }
      return true
    }

    if (quad.isBranch || l >= distanceMax2) return false

    if (quad.data !== node || quad.next != null) {
      if (x == 0.0) {
        x = random.jiggle()
        l += x * x
      }
      if (y == 0.0) {
        y = random.jiggle()
        l += y * y
      }
      if (l < distanceMin2) l = sqrt(distanceMin2 * l)
    }

    var leaf: QuadNode? = quad
    while (leaf != null) {
      if (leaf.data !== node) {
        val w2 = strengths[leaf.data!!.index] * alpha / l
        node.vx += x * w2
        node.vy += y * w2
      }
      leaf = leaf.next
    }
    return false
  }
}
