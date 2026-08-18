package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue

/**
 * Rewrites a view into the views it is shorthand for, before anything is compiled.
 *
 * Upstream has a normalization pass in front of its compiler for exactly the constructs that are
 * *several* views written as one, and it is a separate pass for a good reason: everything after it
 * only ever sees ordinary units and layers. A line that draws its own points is two marks over one
 * encoding, and so — once the composite marks arrive — is a box plot.
 *
 * Nothing here decides anything about appearance; each output is a view the rest of the compiler
 * already knows how to handle.
 */
internal class Normalize(
  private val config: Config,
  @Suppress("unused") private val diagnostics: DiagnosticCollector,
) {

  /** The marks that carry a path and can therefore have something drawn along it. */
  private val OVERLAID = setOf("line", "rule", "trail", "area")

  /**
   * `point: true` on a line, `line: true` on an area — the overlay normalization
   * (`normalize/pathoverlay.ts`).
   *
   * The result is the base mark with the overlay properties stripped, then the overlay marks in
   * upstream's order: the line before the points, both over the same encoding. `null` when the view
   * asks for no overlay, which is the ordinary case.
   */
  fun pathOverlay(unit: VegaValue.Obj): List<VegaValue.Obj>? {
    val markDef =
      when (val mark = unit.fields["mark"]) {
        is VegaValue.Str -> obj { put("type", mark.value) }
        is VegaValue.Obj -> mark
        else -> return null
      }
    val type = markDef.string("type") ?: return null
    if (type !in OVERLAID) return null

    val encoding = unit.obj("encoding") ?: VegaValue.EmptyObject
    val markConfig = config.markConfig(type)
    val point = pointOverlay(markDef, markConfig, encoding)
    val line = if (type == "area") lineOverlay(markDef, markConfig) else null
    if (point == null && line == null) return null

    val outer = VegaValue.Obj(unit.fields.filterKeys { it != "mark" && it != "encoding" })
    // The base layer, with `point` and `line` taken off it. An area that says nothing about its own
    // opacity is faded to 0.7 first, so the line or the points drawn over it stay legible — which
    // is why a plain area and an area with `line: true` are different colours.
    val base = LinkedHashMap<String, VegaValue>()
    if (
      type == "area" &&
        markProperty(markDef, markConfig, "opacity") == null &&
        markProperty(markDef, markConfig, "fillOpacity") == null
    ) {
      base["opacity"] = VegaValue.Num(REDUCED_OPACITY)
    }
    base.putAll(markDef.fields)
    base.remove("point")
    base.remove("line")

    val layers = mutableListOf<VegaValue.Obj>()
    layers += obj {
      putAll(outer)
      // A mark definition with nothing left but its type collapses back to the name, which is what
      // upstream's `dropLineAndPoint` does and what keeps a plain line's output unchanged.
      put("mark", if (base.size > 1) VegaValue.Obj(base) else VegaValue.Str(type))
      // `shape` is dropped: on a path mark it is only there to ask for the overlay in the first
      // place, and it belongs to the points it asked for.
      put("encoding", obj { encoding.fields.forEach { (k, v) -> if (k != "shape") put(k, v) } })
    }

    val overlayEncoding = overlayEncoding(unit, encoding)
    if (line != null) {
      layers += obj {
        putAll(outer)
        put(
          "mark",
          obj {
            put("type", "line")
            LINE_INHERITED.forEach { key -> markDef.fields[key]?.let { put(key, it) } }
            putAll(line)
          },
        )
        put("encoding", overlayEncoding)
      }
    }
    if (point != null) {
      layers += obj {
        putAll(outer)
        put(
          "mark",
          obj {
            put("type", "point")
            put("opacity", 1)
            put("filled", VegaValue.Bool(true))
            POINT_INHERITED.forEach { key -> markDef.fields[key]?.let { put(key, it) } }
            putAll(point)
          },
        )
        put("encoding", overlayEncoding)
      }
    }
    return layers
  }

  /**
   * The encoding the overlay marks take.
   *
   * Two corrections, both upstream's. A stacked base means the overlay has to stack the same way or
   * its points sit at the raw values instead of on the band they belong to — and a `point` does not
   * stack of its own accord, so the offset is written out. And `x2`/`y2` are dropped: a line given
   * a second position becomes a rule and draws a bar out of every point.
   */
  private fun overlayEncoding(unit: VegaValue.Obj, encoding: VegaValue.Obj): VegaValue.Obj {
    // Parsed with its own collector: this is asking a question, not compiling, and the answers a
    // second parse would report have already been reported by the one that counts.
    val stack = Parse(config, DiagnosticCollector()).unit(unit, "$")?.let { Stack.of(it) }
    return obj {
      for ((channel, def) in encoding.fields) {
        if (channel == "x2" || channel == "y2") continue
        if (channel == stack?.fieldChannel && def is VegaValue.Obj) {
          put(
            channel,
            obj {
              putAll(def)
              put("stack", stack.offset)
            },
          )
        } else {
          put(channel, def)
        }
      }
    }
  }

  /**
   * Whether points are drawn along the path, and how.
   *
   * `"transparent"` is not a colour here: it asks for points that are *there but invisible*, which
   * is how a line gets a hit area the width of a symbol without showing one.
   */
  private fun pointOverlay(
    markDef: VegaValue.Obj,
    markConfig: VegaValue.Obj,
    encoding: VegaValue.Obj,
  ): VegaValue.Obj? {
    val declared = markDef.fields["point"]
    return when {
      declared == VegaValue.Str("transparent") -> obj { put("opacity", 0) }
      declared is VegaValue.Obj -> declared
      declared == VegaValue.Bool(true) -> VegaValue.EmptyObject
      // `false` or `null`: asked for and refused.
      declared != null -> null
      else -> {
        val configured = markConfig.fields["point"]
        when {
          configured is VegaValue.Obj -> configured
          truthy(configured) -> VegaValue.EmptyObject
          // A `shape` channel on a path mark has nothing to shape unless there are points, so it
          // asks for them.
          encoding.fields["shape"] != null -> VegaValue.EmptyObject
          else -> null
        }
      }
    }
  }

  /** Whether an area's edge is drawn as a line of its own, and how. */
  private fun lineOverlay(markDef: VegaValue.Obj, markConfig: VegaValue.Obj): VegaValue.Obj? {
    val declared = markDef.fields["line"]
    return when {
      declared == VegaValue.Bool(true) -> VegaValue.EmptyObject
      declared is VegaValue.Obj -> declared
      declared != null -> null
      else -> {
        val configured = markConfig.fields["line"]
        when {
          configured is VegaValue.Obj -> configured
          truthy(configured) -> VegaValue.EmptyObject
          else -> null
        }
      }
    }
  }

  private fun markProperty(
    markDef: VegaValue.Obj,
    markConfig: VegaValue.Obj,
    name: String,
  ): VegaValue? = markDef.fields[name] ?: markConfig.fields[name]

  /** JavaScript's own truth test, which is what upstream's `if (markConfig.point)` performs. */
  private fun truthy(value: VegaValue?): Boolean =
    when (value) {
      null,
      is VegaValue.Null -> false
      is VegaValue.Bool -> value.value
      is VegaValue.Num -> value.value != 0.0
      is VegaValue.Str -> value.value.isNotEmpty()
      else -> true
    }

  private companion object {
    /** What an area fades to when something is drawn over it. `DEFAULT_REDUCED_OPACITY`. */
    const val REDUCED_OPACITY = 0.7

    /** What an overlaid line takes from the mark it outlines: its shape, not its colour. */
    val LINE_INHERITED = listOf("clip", "interpolate", "tension", "tooltip")

    val POINT_INHERITED = listOf("clip", "tooltip")
  }
}
