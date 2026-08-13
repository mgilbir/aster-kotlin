package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.canonicalNumberString

/**
 * The composite marks: one mark name that stands for a whole small chart.
 *
 * `errorbar` and `errorband` are the same rewrite with different parts drawn from it — a summary of
 * one continuous field per group, and a layer per part over that summary. What differs between them
 * is only which marks the parts are: a rule and two ticks, or a band and two borders.
 *
 * Every rule here is from `compositemark/errorbar.ts` and `compositemark/errorband.ts`, and the one
 * worth reading twice is how the *extent* chooses the aggregates. `stderr` and `stdev` are a width
 * measured from a centre, so they aggregate the centre and the width and then add and subtract;
 * `ci` and `iqr` are two more aggregates, being positions rather than widths. Getting that
 * backwards gives a chart with error bars of a plausible size that mean something else.
 */
internal class Composite(
  private val config: Config,
  private val diagnostics: DiagnosticCollector,
) {

  /**
   * `extractTransformsFromEncoding`, for the buckets: a **time unit** on a channel of a composite
   * mark becomes a transform of its own, and the channel is rewritten to read what it wrote.
   *
   * The summary happens *after* the bucketing — one interval per bucket, not one per instant — so
   * the unit cannot stay on the channel, where it would be applied to a column the aggregate has
   * already collapsed. A channel that is not itself temporal is told to read the column it now
   * holds as a *time*, since nothing about an ordinal band would otherwise say so.
   */
  private fun extractTimeUnits(
    shared: Map<String, VegaValue>
  ): Pair<List<VegaValue>, Map<String, VegaValue>> {
    val transforms = mutableListOf<VegaValue>()
    val rewritten = LinkedHashMap<String, VegaValue>()
    for ((channel, value) in shared) {
      val entry = value as? VegaValue.Obj
      val unit = entry?.string("timeUnit")
      val field = entry?.string("field")
      if (entry == null || unit == null || field == null || Fields.isBinnedTimeUnit(unit)) {
        rewritten[channel] = value
        continue
      }
      val name = Fields.varName("${unit}_$field")
      transforms += obj {
        put("timeUnit", unit)
        put("field", field)
        put("as", name)
      }
      // A column with a time unit and no stated type is an instant — that is the type Vega-Lite
      // infers for one, and the rewritten channel has to say so, the unit no longer being there to
      // imply it.
      val temporal = entry.string("type") == null || entry.string("type") == "temporal"
      rewritten[channel] = obj {
        if (entry.fields["title"] == null) {
          put("title", "$field (${Fields.timeUnitParts(unit).joinToString("-")})")
        }
        entry.fields.forEach { (key, own) ->
          if (key != "timeUnit" && key != "field") put(key, own)
        }
        if (entry.string("type") == null) put("type", "temporal")
        put("field", name)
        if (!temporal) {
          val guide = if (channel == "x" || channel == "y") "axis" else "legend"
          put(
            guide,
            obj {
              put("formatType", "time")
              entry.obj(guide)?.fields?.forEach { (key, own) -> put(key, own) }
            },
          )
        }
      }
    }
    return transforms to rewritten
  }

  /**
   * What a composite mark's summary is grouped by — `extractTransformsFromEncoding`.
   *
   * Every channel that is not the continuous axis contributes its field, and a field named on two
   * channels is named twice: the list is pushed to without looking. A **tooltip** is the exception
   * — `filterTooltipWithAggregatedField` takes it out of the encoding before the grouping is read,
   * and puts back only the part of it that asks for an aggregate. Resting on a mark to read a
   * column is not a request to break the summary down by that column.
   */
  private fun groupbyOf(shared: Map<String, VegaValue>): List<String> =
    shared.entries
      // A channel that **aggregates** is a measure, not a grouping: `extractTransformsFromEncoding`
      // pushes it onto the aggregate list instead, so a tooltip asking for a mean of the column
      // being summarised does not also break the summary down by that column.
      .filterNot { (_, value) -> (value as? VegaValue.Obj)?.has("aggregate") == true }
      .mapNotNull { (_, value) -> (value as? VegaValue.Obj)?.string("field") }

  /** The marks this handles. Anything else is not a composite mark. */
  fun handles(type: String): Boolean =
    type == "errorbar" || type == "errorband" || type == "boxplot"

  /**
   * Rewrites a composite mark into the layer of ordinary views it stands for.
   *
   * `null` when the view is not a composite one, or when it is one this cannot summarise — in which
   * case the reason has been reported.
   */
  fun normalize(unit: VegaValue.Obj): List<Pair<String, VegaValue.Obj>>? {
    val markDef =
      when (val mark = unit.fields["mark"]) {
        is VegaValue.Str -> obj { put("type", mark.value) }
        is VegaValue.Obj -> mark
        else -> return null
      }
    val type = markDef.string("type") ?: return null
    if (!handles(type)) return null

    val encoding = unit.obj("encoding") ?: VegaValue.EmptyObject
    val orient = orient(markDef, encoding, type) ?: return null
    // The continuous axis is the one being summarised; the other one groups the summary.
    val continuous = if (orient == "vertical") "y" else "x"
    val def = encoding.obj(continuous)
    val field = def?.string("field")
    if (field == null) {
      diagnostics.error(
        VegaLiteDiagnostics.INVALID_ENCODING,
        "An `$type` summarises one continuous field, and `$continuous` names none.",
        jsonPath = "$.encoding.$continuous",
      )
      return null
    }
    for (channel in listOf("x2", "y2", "xError", "yError", "xError2", "yError2")) {
      if (encoding.fields[channel] == null) continue
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_ENCODING_PROPERTY,
        "An `$type` over data that is *already* summarised — one giving `$channel` rather than " +
          "the rows to summarise — is not implemented; give the raw rows, or draw the interval " +
          "yourself with a `rule` between two fields.",
        jsonPath = "$.encoding.$channel",
      )
      return null
    }

    if (type == "boxplot") return boxPlot(unit, markDef, encoding, continuous, def, field, orient)

    val summary = summary(markDef, field, type) ?: return null

    // Everything but the continuous axis is carried by every part, and it is also what the summary
    // is grouped by: one interval per category, per colour, per detail.
    val (units, shared) =
      extractTimeUnits(
        encoding.fields.filterKeys { it != continuous && it != "${continuous}2" && it != "size" }
      )
    val groupby = groupbyOf(shared)

    val outer = VegaValue.Obj(unit.fields.filterKeys { it != "mark" && it != "encoding" })
    val transform =
      (unit.array("transform") ?: emptyList()) +
        units +
        obj {
          put(
            "aggregate",
            arr(
              summary.aggregates.map { (op, name) ->
                obj {
                  put("op", op)
                  put("field", field)
                  put("as", name)
                }
              }
            ),
          )
          put("groupby", strings(groupby))
        } +
        summary.calculates.map { (expression, name) ->
          obj {
            put("calculate", expression)
            put("as", name)
          }
        }

    val tooltip = tooltip(summary, field, shared)
    val parts =
      if (type == "errorbar") errorBarParts(markDef, orient) else errorBandParts(markDef, encoding)

    val drawn = parts.filter { enabled(markDef, it.name, type) }
    return drawn.mapIndexed { index, part ->
      // An **error bar** of one part collapses back into the view — `layer.length > 1 ? {layer} :
      // {...layer[0]}` — and an error band does not, which is upstream's own asymmetry and not a
      // simplification either way: a band with its borders off is still `layer_0`.
      val name = if (drawn.size == 1 && type == "errorbar") "" else "layer_$index"
      name to
        obj {
          putAll(outer)
          put("transform", arr(transform))
          put(
            "mark",
            obj {
              // The composite mark's own configuration first, then the part's own definition, then
              // whatever the specification wrote for that part — a later key winning each time.
              putAll(config.markConfig(type).obj(part.name))
              markDef.fields["clip"]?.let { put("clip", it) }
              markDef.fields["color"]?.let { put("color", it) }
              markDef.fields["opacity"]?.let { put("opacity", it) }
              putAll(part.mark)
              put("style", "$type-${part.name}")
              (markDef.fields[part.name] as? VegaValue.Obj)?.let { putAll(it) }
            },
          )
          put(
            "encoding",
            obj {
              put(
                continuous,
                obj {
                  put("field", "${part.start}_$field")
                  put("type", def.string("type") ?: "quantitative")
                  // Titled by the field the summary is *of*, not by the column the summary landed
                  // in: an axis reading "lower_v, upper_v" names the machinery, and `v` is what the
                  // reader asked about — `getTitle` upstream is `title ?? field`.
                  put("title", def.fields["title"] ?: VegaValue.Str(field))
                  def.fields["scale"]?.let { put("scale", it) }
                  def.fields["axis"]?.let { put("axis", it) }
                },
              )
              part.end?.let {
                put("${continuous}2", obj { put("field", "${it}_$field") })
              }
              shared.forEach { (channel, value) -> put(channel, value) }
              if (encoding.fields["tooltip"] == null) put("tooltip", tooltip)
            },
          )
        }
    }
  }

  /**
   * A box plot: the quartiles as a box, the median across it, whiskers to the last point inside
   * `extent` interquartile ranges, and everything beyond them drawn as a point.
   *
   * The shape is a *layer of layers*, and it has to be, because the parts read different tables.
   * The quartiles are found first and joined back onto every row (`joinaggregate`), which is what
   * lets a row be compared with its own group's box. From there the flow forks: one branch keeps
   * the rows outside the whiskers and draws them, the other keeps the rows inside and takes their
   * extremes as the whisker ends. The box itself is a third summary of the raw rows.
   *
   * `extent: "min-max"` is the older, simpler box plot — whiskers to the extremes and no outliers,
   * because with the whiskers there nothing can be outside them.
   */
  private fun boxPlot(
    unit: VegaValue.Obj,
    markDef: VegaValue.Obj,
    encoding: VegaValue.Obj,
    continuous: String,
    def: VegaValue.Obj,
    field: String,
    orient: String,
  ): List<Pair<String, VegaValue.Obj>> {
    val extent = markDef.fields["extent"]
    val minMax = (extent as? VegaValue.Str)?.value == "min-max"
    val k =
      (extent as? VegaValue.Num)?.value ?: config.markConfig("boxplot").number("extent") ?: 1.5
    // The box's thickness: the mark's own `size`, a `size` *encoding* written as a value — which
    // is how a specification thins a box without touching the rest of the chart — or the
    // configured one.
    val size =
      markDef.fields["size"]
        ?: encoding.obj("size")?.fields?.get("value")
        ?: config.markConfig("boxplot").fields["size"]
    val ticksOrient = if (orient == "vertical") "horizontal" else "vertical"
    val boxOrient = orient

    val shared =
      encoding.fields.filterKeys {
        it != continuous && it != "${continuous}2" && it != "size" && it != "color"
      }
    // The **colour** is a grouping like any other, even where the parts do not each paint
    // themselves with it: one box per category per colour. A field named on two channels is named
    // twice, so a box plot coloured by the column it is categorised by groups by it twice.
    val groupby =
      groupbyOf(
        encoding.fields.filterKeys { it != continuous && it != "${continuous}2" && it != "size" }
      )
    val outer = VegaValue.Obj(unit.fields.filterKeys { it != "mark" && it != "encoding" })
    val declared = unit.array("transform") ?: emptyList()

    /** `Max of v`, `Q3 of v`, … — what resting on a part of the box says. */
    fun summaryTooltip(entries: List<Pair<String, String>>): VegaValue =
      arr(
        entries.map { (prefix, title) ->
          obj {
            put("field", "$prefix$field")
            put("type", "quantitative")
            put("title", "$title of $field")
          }
        } +
          shared.values.mapNotNull { value ->
            val d = value as? VegaValue.Obj ?: return@mapNotNull null
            val f = d.string("field") ?: return@mapNotNull null
            obj {
              put("field", f)
              put("type", d.string("type") ?: "nominal")
            }
          }
      )

    val fiveNumber =
      summaryTooltip(
        listOf(
          (if (minMax) "upper_whisker_" else "max_") to "Max",
          "upper_box_" to "Q3",
          "mid_box_" to "Median",
          "lower_box_" to "Q1",
          (if (minMax) "lower_whisker_" else "min_") to "Min",
        )
      )
    val whiskerTooltip =
      if (minMax) fiveNumber
      else
        summaryTooltip(
          listOf("upper_whisker_" to "Upper Whisker", "lower_whisker_" to "Lower Whisker")
        )

    fun part(
      name: String,
      relative: String,
      transform: List<VegaValue>,
      mark: VegaValue.Obj,
      start: String,
      end: String? = null,
      rawPosition: Boolean = false,
      tooltip: VegaValue? = null,
      colour: VegaValue? = null,
    ): Pair<String, VegaValue.Obj>? {
      if (!enabled(markDef, name, "boxplot")) return null
      return relative to
        obj {
          putAll(outer)
          put("transform", arr(declared + transform))
          put(
            "mark",
            obj {
              putAll(config.markConfig("boxplot").obj(name))
              markDef.fields["clip"]?.let { put("clip", it) }
              markDef.fields["color"]?.let { put("color", it) }
              markDef.fields["opacity"]?.let { put("opacity", it) }
              putAll(mark)
              put("style", "boxplot-$name")
              (markDef.fields[name] as? VegaValue.Obj)?.let { putAll(it) }
            },
          )
          put(
            "encoding",
            obj {
              put(
                continuous,
                obj {
                  put("field", if (rawPosition) field else "${start}_$field")
                  put("type", def.string("type") ?: "quantitative")
                  put("title", def.fields["title"] ?: VegaValue.Str(field))
                  def.fields["scale"]?.let { put("scale", it) }
                  def.fields["axis"]?.let { put("axis", it) }
                },
              )
              end?.let { put("${continuous}2", obj { put("field", "${it}_$field") }) }
              shared.forEach { (channel, value) -> put(channel, value) }
              // The chart's colour names the *box*, not the whiskers: a whisker marks the extent
              // of the data and is drawn black whatever category the box belongs to, so the
              // encoding's colour is withheld from the parts that state their own.
              colour?.let { put("color", it) }
                ?: encoding.fields["color"]
                  ?.takeIf { mark.string("color") == null }
                  ?.let {
                    put("color", it)
                  }
              if (encoding.fields["tooltip"] == null) tooltip?.let { put("tooltip", it) }
            },
          )
        }
    }

    fun aggregate(measures: List<Triple<String, String, String>>): VegaValue = obj {
      put(
        "aggregate",
        arr(
          measures.map { (op, from, into) ->
            obj {
              put("op", op)
              put("field", from)
              put("as", into)
            }
          }
        ),
      )
      put("groupby", strings(groupby))
    }

    val boxSummary =
      aggregate(
        listOf(
          Triple("q1", field, "lower_box_$field"),
          Triple("q3", field, "upper_box_$field"),
          Triple("median", field, "mid_box_$field"),
          Triple("min", field, "min_$field"),
          Triple("max", field, "max_$field"),
        )
      )
    fun tick(colour: String?) = obj {
      put("type", "tick")
      colour?.let { put("color", it) }
      if (colour != null) put("opacity", 1)
      put("orient", ticksOrient)
      size?.let { put("size", it) }
      put("aria", VegaValue.Bool(false))
    }
    val box = obj {
      put("type", "bar")
      size?.let { put("size", it) }
      put("orient", boxOrient)
      put("ariaRoleDescription", "box")
    }
    // A box with no height has nothing to draw a white line on, so the median takes the box's own
    // colour and stays visible: a group whose quartiles coincide is a single mark, not a gap.
    val medianColour = obj {
      put(
        "condition",
        obj {
          put("test", "datum['lower_box_$field'] >= datum['upper_box_$field']")
          val stated = encoding.obj("color")
          if (stated != null) putAll(stated) else put("value", DEFAULT_BOX_COLOUR)
        },
      )
    }
    val median = obj {
      put("type", "tick")
      (config.markConfig("boxplot").obj("median")?.fields?.get("color"))?.let { put("color", it) }
      size?.let { put("size", it) }
      put("orient", ticksOrient)
      put("aria", VegaValue.Bool(false))
    }
    // A whisker is drawn in **black** whatever the box is coloured: it marks the extent of the
    // data rather than naming a category, and taking the category's colour made a coloured
    // boxplot's whiskers disappear into its box.
    fun rule() = obj {
      put("type", "rule")
      put("color", "black")
      // The whisker runs along the *measured* axis, and saying so is what centres it on the other
      // one: a boxplot of a single column has no category to span, so its parts sit in the middle
      // of the plot rather than filling it.
      put("orient", boxOrient)
      put("aria", VegaValue.Bool(false))
    }

    if (minMax) {
      val summary =
        aggregate(
          listOf(
            Triple("q1", field, "lower_box_$field"),
            Triple("q3", field, "upper_box_$field"),
            Triple("median", field, "mid_box_$field"),
            Triple("min", field, "lower_whisker_$field"),
            Triple("max", field, "upper_whisker_$field"),
          )
        )
      val parts =
        listOfNotNull(
          part(
            "rule",
            "",
            listOf(summary),
            rule(),
            "lower_whisker",
            "lower_box",
            tooltip = whiskerTooltip,
          ),
          part(
            "rule",
            "",
            listOf(summary),
            rule(),
            "upper_box",
            "upper_whisker",
            tooltip = whiskerTooltip,
          ),
          part(
            "ticks",
            "",
            listOf(summary),
            tick("black"),
            "lower_whisker",
            tooltip = whiskerTooltip,
          ),
          part(
            "ticks",
            "",
            listOf(summary),
            tick("black"),
            "upper_whisker",
            tooltip = whiskerTooltip,
          ),
          part("box", "", listOf(summary), box, "lower_box", "upper_box", tooltip = fiveNumber),
          part(
            "median",
            "",
            listOf(summary),
            median,
            "mid_box",
            tooltip = fiveNumber,
            colour = medianColour,
          ),
        )
      return parts.mapIndexed { index, (_, spec) -> "layer_$index" to spec }
    }

    // The quartiles, joined back onto every row so a row can be judged against its own group.
    val quartiles = obj {
      put(
        "joinaggregate",
        arr(
          listOf(
            obj {
              put("op", "q1")
              put("field", field)
              put("as", "lower_box_$field")
            },
            obj {
              put("op", "q3")
              put("field", field)
              put("as", "upper_box_$field")
            },
          )
        ),
      )
      put("groupby", strings(groupby))
    }
    val lower = "datum['lower_box_$field']"
    val upper = "datum['upper_box_$field']"
    val span = "($upper - $lower)"
    val step = canonicalNumberString(k)
    val low = "$lower - $step * $span"
    val high = "$upper + $step * $span"
    val value = "datum['$field']"
    val outside = obj { put("filter", "($value < $low) || ($value > $high)") }
    val inside = obj { put("filter", "($low <= $value) && ($value <= $high)") }
    val whiskerSummary =
      aggregate(
        listOf(
          Triple("min", field, "lower_whisker_$field"),
          Triple("max", field, "upper_whisker_$field"),
          Triple("min", "lower_box_$field", "lower_box_$field"),
          Triple("max", "upper_box_$field", "upper_box_$field"),
        )
      )

    // `"outliers": false` takes the scatter of far-out rows off, and with it a whole layer — so
    // everything below is one level shallower and named accordingly.
    val wantsOutliers = markDef.fields["outliers"] != VegaValue.Bool(false)
    val outliers =
      if (!wantsOutliers) null
      else
        part(
          "outliers",
          "layer_0_layer_0",
          listOf(quartiles, outside),
          obj { put("type", "point") },
          start = field,
          rawPosition = true,
        )
    // With no outlier layer the whiskers *are* the first layer, so every name below loses a level.
    val whiskerPrefix = if (wantsOutliers) "layer_0_layer_1" else "layer_0"
    val whiskers =
      listOfNotNull(
        part(
          "rule",
          "${whiskerPrefix}_layer_0",
          listOf(quartiles, inside, whiskerSummary),
          rule(),
          "lower_whisker",
          "lower_box",
          tooltip = whiskerTooltip,
        ),
        part(
          "rule",
          "${whiskerPrefix}_layer_1",
          listOf(quartiles, inside, whiskerSummary),
          rule(),
          "upper_box",
          "upper_whisker",
          tooltip = whiskerTooltip,
        ),
        part(
          "ticks",
          "${whiskerPrefix}_layer_2",
          listOf(quartiles, inside, whiskerSummary),
          tick("black"),
          "lower_whisker",
          tooltip = whiskerTooltip,
        ),
        part(
          "ticks",
          "${whiskerPrefix}_layer_3",
          listOf(quartiles, inside, whiskerSummary),
          tick("black"),
          "upper_whisker",
          tooltip = whiskerTooltip,
        ),
      )
    val boxes =
      listOfNotNull(
        part(
          "box",
          "layer_1_layer_0",
          listOf(boxSummary),
          box,
          "lower_box",
          "upper_box",
          tooltip = fiveNumber,
        ),
        part(
          "median",
          "layer_1_layer_1",
          listOf(boxSummary),
          median,
          "mid_box",
          tooltip = fiveNumber,
          colour = medianColour,
        ),
      )
    return listOfNotNull(outliers) + whiskers + boxes
  }

  /** One layer of the composite: which mark it is and which summary positions it spans. */
  private class Part(
    val name: String,
    val mark: VegaValue.Obj,
    val start: String,
    val end: String? = null,
  )

  /**
   * A rule from the lower bound to the upper, and a tick at each end.
   *
   * The ticks are off unless asked for (`config.errorbar.ticks` is `false`) and the rule is on, so
   * an unadorned `errorbar` is one rule per group. A tick is turned across the interval, which is
   * what makes it a cap rather than a second interval.
   */
  private fun errorBarParts(markDef: VegaValue.Obj, orient: String): List<Part> {
    val ticksOrient = if (orient == "vertical") "horizontal" else "vertical"
    val thickness = markDef.fields["thickness"]
    val size = markDef.fields["size"]
    fun tick() = obj {
      put("type", "tick")
      put("orient", ticksOrient)
      put("aria", VegaValue.Bool(false))
      thickness?.let { put("thickness", it) }
      size?.let { put("size", it) }
    }
    return listOf(
      Part("ticks", tick(), start = "lower"),
      Part("ticks", tick(), start = "upper"),
      Part(
        "rule",
        obj {
          put("type", "rule")
          put("ariaRoleDescription", "errorbar")
          thickness?.let { put("size", it) }
        },
        start = "lower",
        end = "upper",
      ),
    )
  }

  /**
   * A filled band between the bounds, and a line along each edge of it.
   *
   * A band with one position channel is a *rect* rather than an area, and its borders are rules
   * rather than lines: with nothing to run along, a band is a single block.
   */
  private fun errorBandParts(markDef: VegaValue.Obj, encoding: VegaValue.Obj): List<Part> {
    val twoDimensional = encoding.fields["x"] != null && encoding.fields["y"] != null
    val interpolate = obj {
      markDef.fields["interpolate"]?.let { put("interpolate", it) }
      if (markDef.fields["interpolate"] != null)
        markDef.fields["tension"]?.let { put("tension", it) }
    }
    val band = obj {
      put("type", if (twoDimensional) "area" else "rect")
      if (twoDimensional) {
        putAll(interpolate)
        put("ariaRoleDescription", "errorband")
      }
    }
    val borders = obj {
      put("type", if (twoDimensional) "line" else "rule")
      if (twoDimensional) {
        putAll(interpolate)
        put("aria", VegaValue.Bool(false))
      }
    }
    return listOf(
      Part("band", band, start = "lower", end = "upper"),
      Part("borders", borders, start = "lower"),
      Part("borders", borders, start = "upper"),
    )
  }

  /** Whether a part is drawn: the mark's own answer, or the configuration's. */
  private fun enabled(markDef: VegaValue.Obj, part: String, type: String): Boolean {
    val stated = markDef.fields[part]
    if (stated != null) return stated != VegaValue.Bool(false) && stated != VegaValue.Null
    val configured = config.markConfig(type).fields[part]
    return configured != null && configured != VegaValue.Bool(false) && configured != VegaValue.Null
  }

  /** The aggregates a summary is built from, and the calculates that follow them. */
  private class Summary(
    val aggregates: List<Pair<String, String>>,
    val calculates: List<Pair<String, String>>,
    val titles: List<Pair<String, String>>,
    val titleNamesField: Boolean,
  )

  private fun summary(markDef: VegaValue.Obj, field: String, type: String): Summary? {
    val extentName = markDef.string("extent")
    val centre =
      markDef.string("center")
        ?: extentName?.let { if (it == "iqr") "median" else "mean" }
        ?: config.markConfig(type).string("center")
        ?: "mean"
    val extent = extentName ?: if (centre == "mean") "stderr" else "iqr"
    if ((centre == "median") != (extent == "iqr")) {
      diagnostics.warn(
        VegaLiteDiagnostics.UNSUPPORTED_ENCODING_PROPERTY,
        "An `$type` centred on the $centre with a $extent extent mixes two summaries; upstream " +
          "draws it and warns, and so does this.",
        jsonPath = "$.mark",
      )
    }
    return when (extent) {
      // A *width* measured from a centre: aggregate both, then add and subtract.
      "stderr",
      "stdev" ->
        Summary(
          aggregates = listOf(extent to "extent_$field", centre to "center_$field"),
          calculates =
            listOf(
              "datum['center_$field'] + datum['extent_$field']" to "upper_$field",
              "datum['center_$field'] - datum['extent_$field']" to "lower_$field",
            ),
          titles =
            listOf(
              "center_" to titleCase(centre),
              "upper_" to "${titleCase(centre)} + $extent",
              "lower_" to "${titleCase(centre)} - $extent",
            ),
          titleNamesField = true,
        )
      // Two *positions*, which are aggregates in their own right.
      "ci",
      "iqr" -> {
        val (lower, upper) = if (extent == "ci") "ci0" to "ci1" else "q1" to "q3"
        val centreOp = if (extent == "ci") "mean" else "median"
        Summary(
          aggregates =
            listOf(lower to "lower_$field", upper to "upper_$field", centreOp to "center_$field"),
          calculates = emptyList(),
          titles =
            listOf(
              "upper_" to "${titleCase(upper)} of $field",
              "lower_" to "${titleCase(lower)} of $field",
              "center_" to "${titleCase(centreOp)} of $field",
            ),
          titleNamesField = false,
        )
      }
      else -> {
        diagnostics.error(
          VegaLiteDiagnostics.UNSUPPORTED_ENCODING_PROPERTY,
          "An `$type` extent must be one of `ci`, `iqr`, `stderr` or `stdev`; '$extent' is none " +
            "of them, so nothing was summarised.",
          jsonPath = "$.mark.extent",
        )
        null
      }
    }
  }

  /**
   * What resting on a part says: the three summary values, then every other field the view carries.
   *
   * A summary's own tooltip is written out rather than left to the ordinary rules, because the
   * fields it names — `lower_v`, `center_v` — are ones the specification never wrote and whose
   * default titles would read as the machinery they are.
   */
  private fun tooltip(
    summary: Summary,
    field: String,
    shared: Map<String, VegaValue>,
  ): VegaValue {
    val entries = mutableListOf<VegaValue>()
    for ((prefix, title) in summary.titles) {
      entries += obj {
        put("field", "$prefix$field")
        put("type", "quantitative")
        put("title", if (summary.titleNamesField) "$title of $field" else title)
      }
    }
    for ((_, value) in shared) {
      val def = value as? VegaValue.Obj ?: continue
      if (def.string("field") == null) continue
      // `toStringFieldDef` keeps the definition and only settles its type, so a channel that says
      // what it is called says it in the tooltip too — a bucketed column reads `Year (year)`
      // rather than the `year_Year` the transform wrote.
      entries += obj {
        put("field", def.string("field"))
        put("type", def.string("type") ?: "nominal")
        def.fields["title"]?.let { put("title", it) }
        def.fields["format"]?.let { put("format", it) }
        def.fields["formatType"]?.let { put("formatType", it) }
      }
    }
    return arr(entries)
  }

  /**
   * Which axis is being summarised — `compositeMarkOrient`.
   *
   * With one continuous position the answer is that one; with two, the *unaggregated* one is the
   * dimension and the other is summarised, and a tie falls to vertical.
   */
  private fun orient(markDef: VegaValue.Obj, encoding: VegaValue.Obj, type: String): String? {
    markDef.string("orient")?.let {
      return it
    }
    val x = encoding.obj("x")
    val y = encoding.obj("y")
    val xContinuous = isContinuous(x)
    val yContinuous = isContinuous(y)
    return when {
      xContinuous && yContinuous -> {
        val xAggregate = x?.string("aggregate")
        val yAggregate = y?.string("aggregate")
        when {
          xAggregate == null && yAggregate == type -> "vertical"
          yAggregate == null && xAggregate == type -> "horizontal"
          y?.string("type") == "temporal" && x?.string("type") != "temporal" -> "horizontal"
          else -> "vertical"
        }
      }
      xContinuous -> "horizontal"
      yContinuous -> "vertical"
      else -> {
        diagnostics.error(
          VegaLiteDiagnostics.INVALID_ENCODING,
          "An `$type` needs one continuous position to summarise; neither `x` nor `y` is one.",
          jsonPath = "$.encoding",
        )
        null
      }
    }
  }

  private fun isContinuous(def: VegaValue.Obj?): Boolean {
    if (def == null) return false
    if (def.fields["field"] == null && def.fields["datum"] == null) return false
    val type = def.string("type") ?: return false
    return type == "quantitative" || type == "temporal"
  }

  /**
   * Vega-Lite's own default mark colour, which a median falls back to when its box has no height.
   */
  private val DEFAULT_BOX_COLOUR = "#4c78a8"

  private fun titleCase(text: String): String =
    if (text.isEmpty()) text else text.substring(0, 1).uppercase() + text.substring(1)
}
