package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * Which of a composition's scales and guides are shared between its children and which are not.
 *
 * This is the difference between a layered chart and a **dual-axis** one: the same two marks over
 * one `y` say they measure the same thing, and over `resolve: {"scale": {"y": "independent"}}` they
 * each get a scale and an axis of their own. It is also what puts a colour key under every plot of
 * a concatenation instead of one beside the whole chart.
 *
 * The defaults are `defaultScaleResolve` in `compile/resolve.ts` and depend on what the composition
 * *is*: a concatenation measures its plots' positions separately unless told otherwise, a layer
 * shares everything, and a facet shares everything but `theta`. A resolve declared at the top level
 * governs the outermost composition and nothing below it, as it does upstream, where every model
 * carries its own.
 *
 * `parseGuideResolve` then settles the guides: an **independent scale means an independent guide**,
 * whatever the specification says, since one axis cannot label two scales.
 */
internal class Resolve(declared: VegaValue.Obj?) {

  private val scale: Map<String, String> = read(declared, "scale")
  private val axis: Map<String, String> = read(declared, "axis")
  private val legend: Map<String, String> = read(declared, "legend")

  /** Whether this channel is scaled separately for each child of the composition. */
  fun scaleIsIndependent(channel: String, defaultIndependent: Boolean): Boolean =
    when (scale[channel]) {
      "independent" -> true
      "shared" -> false
      else -> defaultIndependent
    }

  /** `parseGuideResolve`: an independent scale forces an independent guide. */
  fun guideIsIndependent(channel: String, scaleIsIndependent: Boolean): Boolean {
    if (scaleIsIndependent) return true
    val guide = if (channel in Channels.POSITION_CHANNELS) axis else legend
    return guide[channel] == "independent"
  }

  private fun read(declared: VegaValue.Obj?, kind: String): Map<String, String> =
    declared
      ?.obj(kind)
      ?.fields
      ?.mapNotNull { (channel, mode) -> (mode as? VegaValue.Str)?.let { channel to it.value } }
      ?.toMap()
      .orEmpty()
}
