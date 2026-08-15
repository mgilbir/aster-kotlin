package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.canonicalNumberString

/**
 * A selection parameter: the set of rows a reader has picked, as Vega state.
 *
 * Vega has no such construct, so the whole of it is compiled — a **store** dataset holding the
 * picked tuples, a handful of signals that write to it as events arrive, and a `vlSelectionTest` in
 * every encoding that asks about it. `compile/selection/` upstream, split the same way: this holds
 * what a selection *is*, and the pieces that read one are where they belong.
 *
 * The reading is what makes it worth compiling even for an engine with no interaction loop. A chart
 * whose colour is conditional on a selection has to draw *something* before anything is picked, and
 * what it draws — every mark in the highlighted colour, because an empty store means "all" — is
 * decided by the same expression that decides it after a click.
 */
internal class Selection(
  val name: String,
  val type: String,
  /** The columns a picked row is remembered by, or empty for the row's own identity. */
  val fields: List<String>,
  val channels: List<String>,
  val resolve: String,
  /** The event that picks a row, as a Vega stream — `click` unless the selection says otherwise. */
  val on: VegaValue,
  val toggle: String?,
  val bindsScales: Boolean,
  /**
   * The input elements a **point** selection is driven from, where it is bound to any.
   *
   * `{"bind": {"Cylinders": {"input": "range", …}}}` — one control per projected field, or one
   * control for all of them written bare. A selection driven this way is not clicked at all: the
   * controls are the interaction, and `disableDirectManipulation` takes the pointer streams off
   * unless the specification asked for them by name.
   */
  val inputs: VegaValue.Obj?,
  /**
   * The streams a selection **bound to a legend** is picked with, or none where it is not.
   *
   * `{"bind": "legend"}` — or `{"bind": {"legend": "mouseover"}}` — makes the legend itself the
   * control: clicking a swatch picks that category, and the marks are not clicked at all. It needs
   * exactly one projected field, which is the one the legend explains.
   */
  val legendStreams: List<VegaValue>,
  /**
   * `nearest`: the pick goes to the closest mark rather than to the one under the pointer.
   *
   * Vega has no such notion, so it is compiled: a **voronoi** mark is laid over the marks, each of
   * its cells covering the region closer to one point than to any other, and the selection listens
   * on that instead. Which is why the events are scoped to it by name — a click anywhere in the
   * plot lands in some cell, and without the scoping it would also land on the group.
   */
  val nearest: Boolean,
  /**
   * How the brush is painted — `defaultConfig.interval.mark`, with the selection's own over it.
   *
   * Split between the two rects it is drawn as: the fill goes under the marks and everything else
   * over them, so a stroke can be seen against the data it surrounds.
   */
  val brushPaint: VegaValue.Obj,
  /** The streams a brush is dragged and wheeled by, parsed from their selectors. */
  val translate: List<VegaValue>,
  val zoom: List<VegaValue>,
  val clear: String?,
  /** The extent this selection opens with, if it states one — the param's own `value`. */
  val initial: VegaValue? = null,
) {

  val store: String = "${name}_store"

  /**
   * The view this selection was declared in, filled in once the views are named.
   *
   * A selection belongs to **one** unit: its tuples record that unit's name so a picked row can be
   * traced back to the plot it was picked in, its machinery signals live in that plot's group, and
   * its brush is drawn around that plot's marks. A chart that is a single view calls it `""`.
   */
  var owner: UnitView? = null

  /** `unitName`: the declaring view's name, as the expression a tuple records. */
  fun unitName(): String = quoted(owner?.name ?: "")

  /** Whether the pointer becomes a hand over a mark: a *hover* selection is not clicked. */
  val showsPointer: Boolean
    get() =
      // A selection driven from **controls** or from a **legend** is not clicked, so the marks say
      // nothing about being clickable — upstream asks for `!s.bind`, a bound selection being driven
      // from the widget rather than from the drawing.
      if (inputs != null || bindsScales || legendStreams.isNotEmpty()) false
      else
        type == "point" && !bindsScales && (on as? VegaValue.Obj)?.string("type") != "pointerover"

  /** Whether a picked row is remembered by its **identity** rather than by any column of it. */
  val byIdentity: Boolean
    get() = type != "interval" && fields.isEmpty() && channels.isEmpty()

  /** Whether the voronoi overlay's cells are stripes along one channel rather than tiles. */
  private fun projectedChannels(view: UnitView): Set<String> =
    projections(view).mapNotNull { it.first }.toSet()

  companion object {

    /** `CONTINUOUS_DOMAIN_SCALES` in `scale.ts`. */
    private val CONTINUOUS_DOMAINS =
      setOf(
        "linear",
        "log",
        "pow",
        "sqrt",
        "symlog",
        "time",
        "utc",
        "quantile",
        "quantize",
        "threshold",
      )

    fun isContinuous(type: String?): Boolean = type in CONTINUOUS_DOMAINS

    /** Vega's own name for the row identity an `identifier` transform writes. */
    const val SELECTION_ID: String = "_vgsid_"

    /**
     * `defaultConfig` in `selection.ts` — the parts of a selection a specification leaves out.
     *
     * A `point` selection is clicked, remembers rows by identity, toggles with the shift key and
     * resolves globally; an `interval` is dragged. Both clear on a double click.
     */
    /**
     * Every selection the chart declares, wherever in the composition it was written.
     *
     * A selection is declared on a **unit**, which in a layer or a concatenation is a child rather
     * than the chart: the whole tree is walked so that a condition anywhere can be resolved against
     * a selection defined anywhere, and the views then claim the ones they declared.
     */
    fun of(spec: VegaValue.Obj): List<Selection> {
      val found = mutableListOf<Selection>()
      fun walk(node: VegaValue.Obj) {
        found += from(node.array("params").orEmpty())
        for (composition in listOf("layer", "hconcat", "vconcat", "concat")) {
          node.array(composition).orEmpty().forEach { (it as? VegaValue.Obj)?.let(::walk) }
        }
        node.obj("spec")?.let(::walk)
      }
      walk(spec)
      // **Not** deduplicated by name: a selection belongs to a unit, and two plots may each declare
      // one called `hover`. They share a store and a resolve signal, and each still needs its own
      // machinery in its own group, or only the plot that happened to be walked first reacts.
      return found
    }

    fun from(declared: List<VegaValue>): List<Selection> {
      return declared.mapNotNull { entry ->
        val param = entry as? VegaValue.Obj ?: return@mapNotNull null
        val name = param.string("name") ?: return@mapNotNull null
        val select = param.fields["select"] ?: return@mapNotNull null
        val (type, options) =
          when (select) {
            is VegaValue.Str -> select.value to VegaValue.EmptyObject
            is VegaValue.Obj -> (select.string("type") ?: return@mapNotNull null) to select
            else -> return@mapNotNull null
          }
        val encodings =
          options.array("encodings").orEmpty().mapNotNull { (it as? VegaValue.Str)?.value }
        val fields = options.array("fields").orEmpty().mapNotNull { (it as? VegaValue.Str)?.value }
        val bind = param.fields["bind"]
        Selection(
          name = name,
          type = type,
          fields = fields,
          channels = encodings,
          resolve = options.string("resolve") ?: "global",
          on = stream(options.fields["on"], type),
          // `toggle: false` turns it off; anything else is the expression, and the default is the
          // shift key — which is what makes a second click add to the picked set rather than
          // replace it.
          toggle =
            when (val stated = options.fields["toggle"]) {
              null ->
                // `disableDirectManipulation`: a selection driven by **controls** does not toggle,
                // clear or listen for a click unless the specification asked for one by name — the
                // controls are the interaction. A **legend** binding is the exception upstream
                // writes out: the toggle is handed back deliberately, so shift-clicking swatches
                // adds to the picked set.
                if (type != "point") null
                else if (bind !is VegaValue.Obj || bind.has("legend")) "event.shiftKey" else null
              VegaValue.Bool(false) -> null
              is VegaValue.Str -> stated.value
              else -> "event.shiftKey"
            },
          bindsScales = bind == VegaValue.Str("scales"),
          inputs =
            (bind as? VegaValue.Obj)?.takeIf {
              type == "point" && options.string("resolve") == null && !it.has("legend")
            },
          legendStreams =
            if (type != "point") emptyList()
            else
              when {
                bind == VegaValue.Str("legend") -> EventSelector.parse("click", source = "view")
                (bind as? VegaValue.Obj)?.has("legend") == true ->
                  EventSelector.parse(bind.string("legend") ?: "click", source = "view")
                else -> emptyList()
              },
          nearest = options.fields["nearest"] == VegaValue.Bool(true),
          brushPaint =
            obj {
              put("fill", "#333")
              put("fillOpacity", 0.125)
              put("stroke", "white")
              options.obj("mark")?.fields?.forEach { (key, value) -> put(key, value) }
            },
          // `defaultConfig`: a brush is dragged and wheeled unless the selection says otherwise,
          // and `false` says otherwise — a brush that cannot be moved is a brush that is only ever
          // drawn afresh.
          translate = selector(options.fields["translate"], DEFAULT_DRAG, type == "interval"),
          zoom = selector(options.fields["zoom"], "wheel!", type == "interval"),
          clear =
            when (val stated = options.fields["clear"]) {
              VegaValue.Bool(false) -> null
              is VegaValue.Str -> stated.value
              null ->
                if (bind == VegaValue.Str("legend") || (bind is VegaValue.Obj && type == "point")) {
                  null
                } else "dblclick"
              else -> "dblclick"
            },
          initial = param.fields["value"],
        )
      }
    }

    /**
     * `parseSelector`: the event stream a selection listens on.
     *
     * A bare event name is that event **within the plot** — `{source: "scope", type: "click"}` —
     * which is what makes a click on a mark a pick and a click on the surface around it nothing.
     * Anything with selector syntax in it is passed through as written, there being no parser here
     * for a grammar Vega already has one for.
     */
    /** `[pointerdown, window:pointerup] > window:pointermove!` — how a drag is written. */
    private const val DEFAULT_DRAG = "[pointerdown, window:pointerup] > window:pointermove!"

    /**
     * An event selector as the streams it stands for, or none where the selection turned it off.
     */
    private fun selector(stated: VegaValue?, default: String, applies: Boolean): List<VegaValue> {
      if (!applies || stated == VegaValue.Bool(false)) return emptyList()
      val written = (stated as? VegaValue.Str)?.value ?: default
      return EventSelector.parse(written, source = "scope")
    }

    private fun stream(stated: VegaValue?, type: String): VegaValue {
      val default = if (type == "point") "click" else DEFAULT_DRAG
      val name = (stated as? VegaValue.Str)?.value ?: default
      if (stated != null && stated !is VegaValue.Str) return stated
      // Anything with selector syntax in it is *parsed*, not passed through: a drag is written as
      // `[pointerdown, pointerup] > pointermove`, and a stream listening for an event of that name
      // listens for nothing at all.
      if (name.any { it in "[],>:!@ " }) {
        return EventSelector.parse(name, source = "scope").firstOrNull() ?: VegaValue.Str(name)
      }
      return obj {
        put("source", "scope")
        put("type", name)
      }
    }

    /**
     * A value as it is written into an expression: a number bare, a string quoted, a date built.
     */
    fun literal(value: VegaValue): String =
      when (value) {
        is VegaValue.Str -> quoted(value.value)
        is VegaValue.Obj -> Transforms(DiagnosticCollector()).dateTimeExpression(value)
        else -> canonicalNumberString(numeric(value))
      }

    private fun numeric(value: VegaValue): Double = (value as? VegaValue.Num)?.value ?: 0.0

    /** Whether anything in this chart remembers a row by its identity, which needs `_vgsid_`. */
    fun needsIdentity(selections: List<Selection>): Boolean = selections.any {
      it.type == "point" && it.byIdentity
    }
  }

  /**
   * The dataset the picked tuples live in.
   *
   * Named rather than derived: nothing computes it, the interaction writes to it, and every test
   * reads it back. A selection that remembers rows by identity keeps the store sorted by that
   * identity, which is what makes the set comparable between one render and the next.
   */
  fun storeData(view: UnitView?, initial: VegaValue?): VegaValue = obj {
    put("name", store)
    // A stated starting extent is a row **already** in the store: the chart opens with the brush
    // drawn and everything reading it already filtered, rather than opening empty and waiting for a
    // drag that has in effect already happened.
    val projected = view?.let { intervalChannels(it) }.orEmpty()
    // A **point** selection's stated value is a list of rows, each written as a field-to-value
    // object: the chart opens with those rows already picked, which is a store with them in it.
    // The projection this selection remembers by, which is the same list `_tuple_fields` publishes
    // — a channel projection where the specification named encodings, a bare field where it named
    // fields.
    val pointProjection = view?.let { projections(it) } ?: fields.map { null to it }
    if (type != "interval" && initial != null && pointProjection.isNotEmpty()) {
      val picked = (initial as? VegaValue.Arr)?.values ?: listOf(initial)
      put(
        "values",
        arr(
          picked.map { row ->
            obj {
              put("unit", owner?.name ?: "")
              put(
                "fields",
                arr(
                  pointProjection.map { (channel, field) ->
                    obj {
                      put("field", field)
                      channel?.let { put("channel", it) }
                      put("type", if (view != null) projectionType(view, channel) else "E")
                    }
                  }
                ),
              )
              // Keyed by the **channel** first and the field second — `v[p.channel] ?? v[p.field]`.
              // A selection projected through `x` is initialised as `{"x": …}`, which is the
              // channel's name and not the column's, and a stated instant is the *timestamp* it
              // names rather than the parts it was written in: a store is data, not an expression.
              put(
                "values",
                arr(
                  pointProjection.map { (channel, field) ->
                    val stated =
                      (row as? VegaValue.Obj)?.let { own ->
                        channel?.let { own.fields[it] } ?: own.fields[field]
                      } ?: (row as? VegaValue.Obj)?.fields?.get(field)
                    when (stated) {
                      null -> VegaValue.Null
                      is VegaValue.Obj ->
                        VegaValue.Num(Transforms(DiagnosticCollector()).dateTimeTimestamp(stated))
                      else -> stated
                    }
                  }
                ),
              )
            }
          }
        ),
      )
    }
    if (view != null && initial != null && projected.isNotEmpty()) {
      put(
        "values",
        arr(
          listOf(
            obj {
              put("unit", owner?.name ?: "")
              put(
                "fields",
                arr(
                  projected.map { (channel, field) ->
                    obj {
                      put("field", field)
                      put("channel", channel)
                      put("type", projectionType(view, channel))
                    }
                  }
                ),
              )
              put(
                "values",
                arr(
                  projected.map { (channel, _) ->
                    arr(initial.array(channel).orEmpty())
                  }
                ),
              )
            }
          )
        ),
      )
    }
    if (byIdentity) {
      put(
        "transform",
        arr(
          listOf(
            obj {
              put("type", "collect")
              put("sort", obj { put("field", SELECTION_ID) })
            }
          )
        ),
      )
    }
  }

  /**
   * The signals that write the store, in upstream's order — `_tuple`, `_toggle`, `_modify`.
   *
   * The tuple signal is the whole of the interaction: on the selection's own event it builds the
   * row that was picked, and `_modify` hands that to the store. `force: true` is on the tuple
   * because two clicks on the same mark are two selections, and Vega would otherwise treat the
   * unchanged value as no event at all.
   */
  /**
   * The pan and zoom signals a brush is moved by, and the marks it is drawn as.
   *
   * The brush is *two* rects, not one: a background painted under the marks so the data stays
   * legible through it, and a foreground carrying the outline and the cursor, painted over them so
   * it can be grabbed. Upstream puts one before the mark list and the other after, which is the
   * whole of how a brush is both behind the points and clickable.
   */
  fun intervalTail(view: UnitView, unit: String, initial: VegaValue?): List<VegaValue> {
    val projected = intervalChannels(view)
    if (projected.isEmpty()) return emptyList()
    val brush = "${name}_brush"
    val out = mutableListOf<VegaValue>()
    val dataSignals = projected.map { (_, field) -> "${name}_${Fields.varName(field)}" }
    val tupleValue =
      "unit: $unit, fields: ${name}_tuple_fields, values: " +
        "[${projected.joinToString(", ") { (channel, _) ->
          "[" + (initial?.array(channel).orEmpty()).joinToString(", ") { literal(it) } + "]"
        }}]"
    out += obj {
      put("name", "${name}_tuple")
      if (initial != null) put("init", "{$tupleValue}")
      put(
        "on",
        arr(
          listOf(
            obj {
              put("events", arr(listOf(obj { put("signal", dataSignals.joinToString(" || ")) })))
              put(
                "update",
                "${dataSignals.joinToString(" && ")} ? {unit: $unit, " +
                  "fields: ${name}_tuple_fields, values: [${dataSignals.joinToString(",")}]} : null",
              )
            }
          )
        ),
      )
    }
    out += obj {
      put("name", "${name}_tuple_fields")
      put(
        "value",
        arr(
          projected.map { (channel, field) ->
            obj {
              put("field", field)
              put("channel", channel)
              put("type", projectionType(view, channel))
            }
          }
        ),
      )
    }
    // A brush is grabbed **on the brush** — the selector's own streams, each scoped to the brush
    // mark; a bound scale is grabbed anywhere in the plot, there being no rectangle to take hold
    // of.
    val drags = translate.map { stream ->
      if (bindsScales) stream else scopedTo(stream, brush, onWindowOpener = true)
    }
    val openers = drags.mapNotNull { (it as? VegaValue.Obj)?.array("between")?.firstOrNull() ?: it }
    out += obj {
      put("name", "${name}_translate_anchor")
      put("value", VegaValue.EmptyObject)
      put(
        "on",
        arr(
          listOf(
            obj {
              put("events", arr(openers))
              put(
                "update",
                "{x: x(unit), y: y(unit)" +
                  projected.joinToString("") { (channel, _) ->
                    // A bound scale is panned from its **domain**, a brush from its own pixels.
                    val extent =
                      if (bindsScales) "domain(${quoted(view.scale(channel))})"
                      else "slice(${name}_$channel)"
                    ", extent_$channel: $extent"
                  } +
                  "}",
              )
            }
          )
        ),
      )
    }
    out += obj {
      put("name", "${name}_translate_delta")
      put("value", VegaValue.EmptyObject)
      put(
        "on",
        arr(
          listOf(
            obj {
              put("events", arr(drags))
              put(
                "update",
                "{x: ${name}_translate_anchor.x - x(unit), y: ${name}_translate_anchor.y - y(unit)}",
              )
            }
          )
        ),
      )
    }
    val wheels = zoom.map { stream -> if (bindsScales) stream else scopedTo(stream, brush) }
    out += obj {
      put("name", "${name}_zoom_anchor")
      put(
        "on",
        arr(
          listOf(
            obj {
              put("events", arr(wheels))
              // A zoom holds the point under the pointer still, and for a bound scale that point is
              // a *value*: it is the domain that changes, so the anchor is in the domain's units.
              put(
                "update",
                if (!bindsScales) "{x: x(unit), y: y(unit)}"
                else
                // The **scales**, not the projection: `model.scaleName(X)` and `scaleName(Y)`,
                // whether or not either channel has a projection of its own. The diagonal cell
                // of a scatter-plot matrix plots one column against itself and so projects once,
                // but it is still panned and zoomed in both directions.
                "{" +
                    listOf("x", "y")
                      .filter { view.spec.encoding.containsKey(it) }
                      .joinToString(", ") { channel ->
                        "$channel: invert(${quoted(view.scale(channel))}, $channel(unit))"
                      } +
                    "}",
              )
            }
          )
        ),
      )
    }
    out += obj {
      put("name", "${name}_zoom_delta")
      put(
        "on",
        arr(
          listOf(
            obj {
              put("events", arr(wheels))
              put("force", VegaValue.Bool(true))
              put("update", "pow(1.001, event.deltaY * pow(16, event.deltaMode))")
            }
          )
        ),
      )
    }
    out += obj {
      put("name", "${name}_modify")
      put(
        "on",
        arr(
          listOf(
            obj {
              put("events", obj { put("signal", "${name}_tuple") })
              // `modifyExpr`: what the store is told to do with the drag that has just finished.
              // A selection resolved **globally** replaces everything in the store; one resolved
              // per unit replaces only what that unit put there, so a brush dragged in one cell of
              // a matrix leaves the brushes in the other cells alone.
              val scope = if (resolve == "global") "true" else "{unit: $unit}"
              put("update", "modify(${quoted(store)}, ${name}_tuple, $scope)")
            }
          )
        ),
      )
    }
    return out
  }

  /** The two rects a brush is drawn as: the background under the marks, the outline over them. */
  fun brushMarks(view: UnitView, unit: String, background: Boolean): List<VegaValue> {
    // A selection bound to the scales has no brush: the drag moves the plot, not a rectangle on it.
    if (bindsScales) return emptyList()
    val projected = intervalChannels(view)
    if (projected.isEmpty()) return emptyList()
    val channels = projected.map { it.first }.toSet()
    val store = "data(${quoted(store)})"
    fun place(channel: String, far: Boolean): VegaValue {
      val own: VegaValue.Obj =
        if (channel in channels) {
          obj { put("signal", "${name}_$channel[${if (far) 1 else 0}]") }
        } else if (far) {
          obj { put("field", obj { put("group", if (channel == "x") "width" else "height") }) }
        } else {
          obj { put("value", 0) }
        }
      // A **globally** resolved brush is drawn only in the plot it was dragged in: one selection
      // shared between plots is one rectangle, and the others show nothing.
      if (resolve != "global") return own
      return arr(
        listOf(
          obj {
            put("test", "$store.length && $store[0].unit === $unit")
            own.fields.forEach { (key, value) -> put(key, value) }
          },
          obj { put("value", 0) },
        )
      )
    }
    val update = obj {
      put("x", place("x", far = false))
      put("y", place("y", far = false))
      put("x2", place("x", far = true))
      put("y2", place("y", far = true))
    }
    if (background) {
      return listOf(
        obj {
          put("name", "${name}_brush_bg")
          put("type", "rect")
          put("clip", VegaValue.Bool(true))
          put(
            "encode",
            obj {
              put(
                "enter",
                obj {
                  put("fill", obj { put("value", brushPaint.fields["fill"]) })
                  put("fillOpacity", obj { put("value", brushPaint.fields["fillOpacity"]) })
                },
              )
              put("update", update)
            },
          )
        }
      )
    }
    val outlined = obj {
      update.fields.forEach { (key, value) -> put(key, value) }
      val visible =
        projected.joinToString(" && ") { (channel, _) ->
          "${name}_$channel[0] !== ${name}_$channel[1]"
        }
      // Everything but the fill is painted **over** the marks, and only while the brush has extent:
      // a rectangle of no width is a click, and outlining it would draw a line across the plot.
      brushPaint.fields.forEach { (key, value) ->
        if (key == "fill" || key == "fillOpacity" || key == "cursor") return@forEach
        put(
          key,
          arr(
            listOf(
              obj {
                put("test", visible)
                put("value", value)
              },
              obj { put("value", VegaValue.Null) },
            )
          ),
        )
      }
    }
    // The pointer says the brush can be moved, and where it cannot be — a selection that states
    // `translate: false` — it says nothing rather than promising a drag that does not happen.
    val cursor =
      brushPaint.fields["cursor"] ?: if (translate.isNotEmpty()) VegaValue.Str("move") else null
    return listOf(
      obj {
        put("name", "${name}_brush")
        put("type", "rect")
        put("clip", VegaValue.Bool(true))
        put(
          "encode",
          obj {
            put(
              "enter",
              obj {
                cursor?.let { put("cursor", obj { put("value", it) }) }
                put("fill", obj { put("value", "transparent") })
              },
            )
            put("update", outlined)
          },
        )
      }
    )
  }

  /** `<name>_<field>_legend` — the signal a legend-bound selection is picked into. */
  fun legendSignalName(field: String): String =
    Fields.varName("${name}_${Fields.varName(field)}_legend")

  /** `<field>_legend` — what the legend's own parts are named after. */
  fun legendPartPrefix(field: String): String = "${Fields.varName(field)}_legend"

  /**
   * `legendBindings.topLevelSignals`: the swatch a reader clicked, as a signal.
   *
   * A legend entry carries no tuple of its own, so the signal walks the scene graph to the symbol's
   * datum — that is what `item().items[0].items[0].datum.value` is doing — and a click that lands
   * anywhere else clears it back to null.
   */
  fun legendSignals(view: UnitView?): List<VegaValue> {
    if (legendStreams.isEmpty()) return emptyList()
    val field = legendField(view) ?: return emptyList()
    val prefix = legendPartPrefix(field)
    val signal = legendSignalName(field)
    val scoped =
      listOf("symbols", "labels", "entries").flatMap { part ->
        legendStreams.map { stream ->
          obj {
            (stream as? VegaValue.Obj)?.fields?.forEach { (key, value) -> put(key, value) }
            put("markname", "${prefix}_$part")
          }
        }
      }
    return listOf(
      obj {
        put("name", signal)
        if (initial == null) put("value", VegaValue.Null)
        put(
          "on",
          arr(
            listOf(
              obj {
                put("events", arr(scoped))
                put(
                  "update",
                  "isDefined(datum.value) ? datum.value : item().items[0].items[0].datum.value",
                )
                put("force", VegaValue.Bool(true))
              },
              obj {
                put("events", arr(legendStreams))
                put("update", "!event.item || !datum ? null : $signal")
                put("force", VegaValue.Bool(true))
              },
            )
          ),
        )
      }
    )
  }

  /** The one field a legend-bound selection projects onto, which is what the legend explains. */
  fun legendField(view: UnitView?): String? {
    if (legendStreams.isEmpty()) return null
    val projected = view?.let { projections(it) }?.map { it.second } ?: fields
    return projected.singleOrNull()
  }

  /**
   * `inputBindings.topLevelSignals`: one signal per projection, each bound to its own control.
   *
   * They live at the **top** of the chart rather than in the plot, because a control is part of the
   * page rather than of the drawing; and they are written in reverse order, each being unshifted
   * onto the list as the projections are walked.
   */
  fun inputSignals(view: UnitView?): List<VegaValue> {
    val bind = inputs ?: return emptyList()
    val projected = view?.let { projections(it) } ?: fields.map { null to it }
    val started = (initial as? VegaValue.Arr)?.values?.firstOrNull() as? VegaValue.Obj
    return projected
      .map { (channel, field) ->
        obj {
          put("name", Fields.varName("${name}_$field"))
          val start = started?.fields?.get(field)
          if (start != null) put("init", literal(start)) else put("value", VegaValue.Null)
          // The control for this field, the one for its channel, or — where the binding names
          // neither — the one control the whole selection is driven from.
          val own = bind.fields[field] ?: channel?.let { bind.fields[it] }
          put("bind", own ?: bind)
        }
      }
      .reversed()
  }

  /** The name of the voronoi overlay this selection picks through, when it picks by nearness. */
  fun voronoiName(): String =
    listOf(owner?.name.orEmpty(), "voronoi").filter { it.isNotEmpty() }.joinToString("_")

  /** The stream a pick listens on, scoped to the voronoi overlay where there is one. */
  private fun pickStream(): VegaValue {
    val stream = on as? VegaValue.Obj ?: return on
    if (!nearest) return stream
    return obj {
      stream.fields.forEach { (key, value) -> put(key, value) }
      put("markname", voronoiName())
    }
  }

  /**
   * The voronoi overlay a `nearest` selection picks through — `nearest.ts`.
   *
   * One transparent cell per point, each covering the ground closer to that point than to any
   * other, laid straight over the marks it was built from. A path mark has none: what would be
   * nearest to a *line* is the line itself everywhere along it.
   */
  fun voronoiMark(view: UnitView): VegaValue? {
    if (!nearest || view.spec.mark in PATH_MARKS) return null
    val projectedOn = projectedChannels(view)
    val hasX = "x" in projectedOn || projectedOn.isEmpty()
    val hasY = "y" in projectedOn || projectedOn.isEmpty()
    return obj {
      put("name", voronoiName())
      put("type", "path")
      put("interactive", VegaValue.Bool(true))
      put("aria", VegaValue.Bool(false))
      put("from", obj { put("data", view.prefixed("marks")) })
      put(
        "encode",
        obj {
          put(
            "update",
            obj {
              put("fill", obj { put("value", "transparent") })
              put("strokeWidth", obj { put("value", 0.35) })
              put("stroke", obj { put("value", "transparent") })
              put("isVoronoi", obj { put("value", VegaValue.Bool(true)) })
              // The cells carry the marks' own tooltip: the pointer is over a cell rather than
              // over a point, so without it a chart that explains its points explains nothing.
              Marks.reactiveTooltip(view)?.let { put("tooltip", it) }
            },
          )
        },
      )
      put(
        "transform",
        arr(
          listOf(
            obj {
              put("type", "voronoi")
              // A selection projected onto **one** channel makes cells that are stripes rather than
              // tiles: the other coordinate is held at zero, so the nearest point is the nearest
              // along the axis that was projected.
              put("x", obj { put("expr", if (hasX || !hasY) "datum.datum.x || 0" else "0") })
              put("y", obj { put("expr", if (hasY || !hasX) "datum.datum.y || 0" else "0") })
              put(
                "size",
                arr(listOf(signalRef(view.widthSignal), signalRef(view.heightSignal))),
              )
            }
          )
        ),
      )
    }
  }

  fun signals(
    unit: String,
    brushes: List<String> = emptyList(),
    view: UnitView? = null,
  ): List<VegaValue> {
    val out = mutableListOf<VegaValue>()
    val datum = "(item().isVoronoi ? datum.datum : datum)"
    val projected = view?.let { projections(it) } ?: fields.map { null to it }
    val picked =
      if (byIdentity) {
        "unit: $unit, ${SELECTION_ID}: $datum[${quoted(SELECTION_ID)}]"
      } else {
        val values = projected.joinToString(", ") { (_, field) -> "$datum[${quoted(field)}]" }
        "unit: $unit, fields: ${name}_tuple_fields, values: [$values]"
      }
    // A click on a group, on a legend or on another selection's brush is not a pick: the first is
    // the plot itself, the second is a legend binding's business, and the third belongs to the
    // brush that owns it.
    val guard =
      "datum && item().mark.marktype !== 'group' && indexof(item().mark.role, 'legend') < 0" +
        brushes.joinToString("") { " && indexof(item().mark.name, '$it') < 0" }
    // A selection driven by a **legend** writes its tuple from the swatch signal, the same shape a
    // control binding produces: the legend is the control.
    val legendField = view?.let { legendField(it) } ?: fields.singleOrNull()
    if (legendStreams.isNotEmpty() && legendField != null) {
      val signal = legendSignalName(legendField)
      out += obj {
        put("name", "${name}_tuple")
        put(
          "update",
          "$signal !== null ? {fields: ${name}_tuple_fields, values: [$signal]} : null",
        )
      }
    } else if (inputs != null) {
      val values = projected.map { (_, field) -> Fields.varName("${name}_$field") }
      out += obj {
        put("name", "${name}_tuple")
        put(
          "update",
          if (values.isEmpty()) "null"
          else
            "${values.joinToString(" && ") { "$it !== null" }} ? " +
              "{fields: ${name}_tuple_fields, values: [${values.joinToString(", ")}]} : null",
        )
      }
    } else
      out += obj {
        put("name", "${name}_tuple")
        put(
          "on",
          arr(
            listOfNotNull(
              obj {
                put("events", arr(listOf(pickStream())))
                put("update", "$guard ? {$picked} : null")
                put("force", VegaValue.Bool(true))
              },
              clear?.let {
                obj {
                  put(
                    "events",
                    arr(
                      listOf(
                        obj {
                          put("source", "view")
                          put("type", it)
                        }
                      )
                    ),
                  )
                  put("update", "null")
                }
              },
            )
          ),
        )
      }
    // `_tuple_fields` — what each remembered value *is*, which the store needs to compare them:
    // `E` for an exact value, `R` for a range. A selection remembering rows by identity has no
    // projection and so no such signal.
    if (!byIdentity) {
      out += obj {
        put("name", "${name}_tuple_fields")
        put(
          "value",
          arr(
            projected.map { (channel, field) ->
              obj {
                put("field", field)
                // A projection made through a **channel** records which channel, because a test
                // has to know what the value was compared against; one made on a bare field does
                // not, there being no channel it came from.
                channel?.let { put("channel", it) }
                put("type", if (view != null) projectionType(view, channel) else "E")
              }
            }
          ),
        )
      }
    }
    toggle?.let { expression ->
      out += obj {
        put("name", "${name}_toggle")
        put("value", VegaValue.Bool(false))
        put(
          "on",
          arr(
            listOfNotNull(
              obj {
                // A legend-bound selection toggles on the legend's own stream — there is no click
                // on a mark to read the shift key from.
                if (legendStreams.isNotEmpty()) {
                  put("events", obj { put("merge", arr(legendStreams)) })
                } else put("events", arr(listOf(pickStream())))
                put("update", expression)
              },
              clear?.let {
                obj {
                  put(
                    "events",
                    arr(
                      listOf(
                        obj {
                          put("source", "view")
                          put("type", it)
                        }
                      )
                    ),
                  )
                  put("update", "false")
                }
              },
            )
          ),
        )
      }
    }
    out += obj {
      put("name", "${name}_modify")
      put(
        "on",
        arr(
          listOf(
            obj {
              put("events", obj { put("signal", "${name}_tuple") })
              put("update", "modify(${quoted(store)}, ${modifyArguments()})")
            }
          )
        ),
      )
    }
    return out
  }

  /**
   * `modifyExpr`: what the store is told to do with the tuple that was just built.
   *
   * Without a toggle it is "replace everything with this one". With one, the same three arguments
   * are each gated on the toggle signal: insert nothing and remove the tuple when toggling, insert
   * it and clear the rest when not.
   */
  private fun modifyArguments(): String {
    val tuple = "${name}_tuple"
    val scope = if (resolve == "global") "true" else "{unit: ${name}_tuple.unit}"
    val toggleSignal = "${name}_toggle"
    return if (toggle == null) "$tuple, $scope"
    else
      "$toggleSignal ? null : $tuple, " +
        (if (resolve == "global") "$toggleSignal ? null : true, "
        else "$toggleSignal ? null : {unit: ${name}_tuple.unit}, ") +
        "$toggleSignal ? $tuple : null"
  }

  /**
   * The signals an **interval** selection is: two per projected channel, and the machinery around
   * them.
   *
   * A drag is remembered twice over. `<name>_<channel>` holds the extent in **pixels**, which is
   * what the brush is drawn at and what a pan or a zoom moves; `<name>_<field>` holds the same
   * extent in **data**, inverted through the scale, which is what the store remembers and every
   * test compares against. They are kept in step in both directions — the pixel signal inverts into
   * the data one, and a change of scale re-scales the data one back into pixels — which is why a
   * brush survives a chart resizing under it.
   */
  /**
   * The streams a brush is drawn by, with the guard that keeps a drag *on* the brush from redrawing
   * it.
   *
   * `translate`'s parse step pushes `!event.item || event.item.mark.name !== "…_brush"` onto the
   * event that opens the window — a press on the brush is a move, not a new selection — and it is
   * pushed onto the filters the selector already stated rather than replacing them.
   */
  private fun dragStreams(): List<VegaValue> {
    val guard = "!event.item || event.item.mark.name !== ${quoted("${name}_brush")}"
    return on
      .let { if (it is VegaValue.Arr) it.values else listOf(it) }
      .map { drag ->
        val stream = drag as? VegaValue.Obj ?: return@map drag
        val window = stream.array("between") ?: return@map stream
        if (translate.isEmpty() || bindsScales) return@map stream
        obj {
          stream.fields.forEach { (key, value) -> put(key, value) }
          put(
            "between",
            arr(
              window.mapIndexed { index, event ->
                if (index != 0) event else withFilter(event, guard)
              }
            ),
          )
        }
      }
  }

  /**
   * A stream scoped to the brush mark, which is what makes a drag *on* the brush move it.
   *
   * `translate` names the mark on the event that **opens** its window and `zoom` on the event
   * itself, the one being a drag that starts on the brush and the other a wheel that happens over
   * it.
   */
  private fun scopedTo(
    stream: VegaValue,
    mark: String,
    onWindowOpener: Boolean = false,
  ): VegaValue {
    val event = stream as? VegaValue.Obj ?: return stream
    val window = event.array("between")
    if (!onWindowOpener || window == null) {
      return obj {
        event.fields.forEach { (key, value) -> put(key, value) }
        put("markname", mark)
      }
    }
    return obj {
      event.fields.forEach { (key, value) -> if (key != "between") put(key, value) }
      put(
        "between",
        arr(
          window.mapIndexed { index, part ->
            if (index != 0) part
            else
              obj {
                (part as? VegaValue.Obj)?.fields?.forEach { (key, value) -> put(key, value) }
                put("markname", mark)
              }
          }
        ),
      )
    }
  }

  /** One more filter on a stream, after whatever the selector stated. */
  private fun withFilter(event: VegaValue, extra: String): VegaValue {
    val stream = event as? VegaValue.Obj ?: return event
    val stated = stream.array("filter").orEmpty()
    if (stated.contains(VegaValue.Str(extra))) return stream
    return obj {
      stream.fields.forEach { (key, value) -> if (key != "filter") put(key, value) }
      put("filter", arr(stated + VegaValue.Str(extra)))
    }
  }

  fun intervalSignals(view: UnitView, initial: VegaValue?): List<VegaValue> {
    val out = mutableListOf<VegaValue>()
    val projected = intervalChannels(view)
    if (projected.isEmpty()) return out
    if (bindsScales) return boundScaleSignals(view, projected)
    val dragStreams = dragStreams()

    for ((channel, field) in projected) {
      val pixels = "${name}_$channel"
      val data = "${name}_${Fields.varName(field)}"
      val size = if (channel == "x") view.widthSignal else view.heightSignal
      val scale = view.scale(channel)
      val start = (initial?.array(channel))?.getOrNull(0)
      val end = (initial?.array(channel))?.getOrNull(1)
      out += obj {
        put("name", pixels)
        if (start != null && end != null) {
          put(
            "init",
            "[scale(${quoted(scale)}, ${literal(start)}), scale(${quoted(scale)}, ${literal(end)})]",
          )
        } else {
          put("value", arr(emptyList()))
        }
        put(
          "on",
          arr(
            // Two entries per stream the selection listens on: the press that starts the brush —
            // the *first* event of the drag's window — sets both ends to where it landed, and the
            // drag itself moves the far one. Built from the parsed selector rather than assumed,
            // since a chart may say `[pointerdown[!event.shiftKey], pointerup] > pointermove` and
            // mean a brush that shares the plot with another.
            dragStreams.flatMap { drag ->
              listOfNotNull(
                (drag as? VegaValue.Obj)?.array("between")?.firstOrNull()?.let { press ->
                  obj {
                    put("events", press)
                    put("update", "[$channel(unit), $channel(unit)]")
                  }
                },
                obj {
                  put("events", drag)
                  put("update", "[$pixels[0], clamp($channel(unit), 0, $size)]")
                },
              )
            } +
              listOfNotNull(
                obj {
                  put("events", obj { put("signal", "${name}_scale_trigger") })
                  // A **continuous** scale can be panned and zoomed, so the brush is rewritten in
                  // its new pixels; a band or a point scale cannot be, so any other change to its
                  // domain — a filter, a new category — clears the brush instead of moving it.
                  put(
                    "update",
                    if (continuous(view, channel))
                      "[scale(${quoted(scale)}, $data[0]), scale(${quoted(scale)}, $data[1])]"
                    else "[0, 0]",
                  )
                },
                clear?.let {
                  obj {
                    put(
                      "events",
                      arr(
                        listOf(
                          obj {
                            put("source", "view")
                            put("type", it)
                          }
                        )
                      ),
                    )
                    put("update", "[0, 0]")
                  }
                },
                obj {
                  put("events", obj { put("signal", "${name}_translate_delta") })
                  put(
                    "update",
                    "clampRange(panLinear(${name}_translate_anchor.extent_$channel, " +
                      "${name}_translate_delta.$channel / span(${name}_translate_anchor.extent_$channel)), 0, $size)",
                  )
                },
                obj {
                  put("events", obj { put("signal", "${name}_zoom_delta") })
                  put(
                    "update",
                    "clampRange(zoomLinear($pixels, ${name}_zoom_anchor.$channel, ${name}_zoom_delta), 0, $size)",
                  )
                },
              )
          ),
        )
      }
      out += obj {
        put("name", data)
        if (start != null && end != null) put("init", "[${literal(start)}, ${literal(end)}]")
        put(
          "on",
          arr(
            listOf(
              obj {
                put("events", obj { put("signal", pixels) })
                put(
                  "update",
                  "$pixels[0] === $pixels[1] ? null : invert(${quoted(scale)}, $pixels)",
                )
              }
            )
          ),
        )
      }
    }

    // A change of *scale* rewrites the brush rather than clearing it: the trigger fires whenever a
    // scale it reads is rebuilt, and every channel whose data extent no longer matches its pixels
    // pushes the pixels back into step.
    out += obj {
      put("name", "${name}_scale_trigger")
      put("value", VegaValue.EmptyObject)
      put(
        "on",
        arr(
          listOf(
            obj {
              put(
                "events",
                arr(projected.map { (channel, _) -> obj { put("scale", view.scale(channel)) } }),
              )
              put(
                "update",
                projected.joinToString(" && ") { (channel, field) ->
                  val data = "${name}_${Fields.varName(field)}"
                  val pixels = "${name}_$channel"
                  val scale = quoted(view.scale(channel))
                  // The `+` coerces the two sides to numbers, which is only meaningful — and only
                  // correct — where the scale's domain is numeric: a band scale inverts to a
                  // category, and `+"USA"` is not a comparison.
                  val num = if (continuous(view, channel)) "+" else ""
                  "(!isArray($data) || (${num}invert($scale, $pixels)[0] === $num$data[0] && " +
                    "${num}invert($scale, $pixels)[1] === $num$data[1]))"
                } + " ? ${name}_scale_trigger : {}",
              )
            }
          )
        ),
      )
    }
    return out
  }

  /** The channels an interval is dragged along: the ones it names, or `x` and `y` by default. */
  /**
   * What a projection stores — `project.ts`, where the tuple type is decided per field.
   *
   * A dragged interval over a continuous scale stores a **range**, because that is what a drag
   * names; over a band or a point scale there is nothing between two categories, so it stores the
   * categories it covered. A binned field stores a range that is closed at the left and open at the
   * right, which is how a bucket's own boundary is written.
   */
  private fun projectionType(view: UnitView, channel: String?): String =
    when {
      channel == null -> "E"
      type == "interval" && channel in Channels.SCALE_CHANNELS && continuous(view, channel) -> "R"
      view.spec.fieldDef(channel)?.bin != null -> "R-RE"
      else -> "E"
    }

  /**
   * `scales.ts` — a selection **bound to the scales** pans and zooms the plot instead of brushing.
   *
   * There is no rectangle and so no pixel extent: the drag moves the scale's *domain* directly, so
   * each channel keeps only its data signal and the scale reads it back through `domainRaw`. Which
   * is why the pan is divided by the plot's size rather than by the brush's span, and why the zoom
   * anchors at the inverted pointer rather than at the pointer.
   */
  private fun boundScaleSignals(
    view: UnitView,
    projected: List<Pair<String, String>>,
  ): List<VegaValue> {
    val out = mutableListOf<VegaValue>()
    for ((channel, field) in projected) {
      val data = "${name}_${Fields.varName(field)}"
      val size = if (channel == "x") view.widthSignal else view.heightSignal
      val domain = "domain(${quoted(view.scale(channel))})"
      val type = view.scaleType(channel)
      // A pan or a zoom of a *transformed* scale is not a pan or a zoom of its domain: moving a log
      // axis by a tenth of its width is a factor and not a distance, so Vega has a function each.
      val panFn =
        when (type) {
          "log" -> "panLog"
          "symlog" -> "panSymlog"
          "pow",
          "sqrt" -> "panPow"
          else -> "panLinear"
        }
      val zoomFn =
        when (type) {
          "log" -> "zoomLog"
          "symlog" -> "zoomSymlog"
          "pow",
          "sqrt" -> "zoomPow"
          else -> "zoomLinear"
        }
      val argument =
        when (type) {
          "pow",
          "sqrt" -> ", ${exponentOf(view, channel)}"
          "symlog" -> ", ${constantOf(view, channel)}"
          else -> ""
        }
      // The x sign is negative and the y sign is not: dragging right shows *lower* values, the two
      // axes running opposite ways in pixels.
      val sign = if (channel == "x") "-" else ""
      out += obj {
        put("name", data)
        put(
          "on",
          arr(
            listOfNotNull(
              clear?.let {
                obj {
                  put(
                    "events",
                    arr(
                      listOf(
                        obj {
                          put("source", "view")
                          put("type", it)
                        }
                      )
                    ),
                  )
                  put("update", "null")
                }
              },
              obj {
                put("events", obj { put("signal", "${name}_translate_delta") })
                put(
                  "update",
                  "$panFn(${name}_translate_anchor.extent_$channel, " +
                    "$sign${name}_translate_delta.$channel / $size$argument)",
                )
              },
              obj {
                put("events", obj { put("signal", "${name}_zoom_delta") })
                put(
                  "update",
                  "$zoomFn($domain, ${name}_zoom_anchor.$channel, ${name}_zoom_delta$argument)",
                )
              },
            )
          ),
        )
      }
    }
    return out
  }

  /** A power scale's exponent, which its pan and its zoom both have to be told. */
  private fun exponentOf(view: UnitView, channel: String): String {
    val stated =
      (view.scaleComponents[channel]?.properties?.get("exponent") as? VegaValue.Num)?.value
    return canonicalNumberString(stated ?: if (view.scaleType(channel) == "sqrt") 0.5 else 1.0)
  }

  /** A symlog scale's constant, likewise. */
  private fun constantOf(view: UnitView, channel: String): String {
    val stated =
      (view.scaleComponents[channel]?.properties?.get("constant") as? VegaValue.Num)?.value
    return canonicalNumberString(stated ?: 1.0)
  }

  /** `hasContinuousDomain`: whether a channel's scale can be inverted to a number. */
  private fun continuous(view: UnitView, channel: String): Boolean =
    isContinuous(view.scaleType(channel))

  /**
   * What a selection **projects** onto — `project.ts`, which reads both `encodings` and `fields`.
   *
   * A channel projection is named by the field the marks are placed by and remembers which channel
   * it came from; a bare field projection is just the column. A point selection may have either,
   * and one that has neither remembers rows by their identity instead.
   */
  fun projections(view: UnitView): List<Pair<String?, String>> {
    val byChannel = channelProjections(view)
    val named = byChannel.map { it.second }.toSet()
    return byChannel.map { (channel, field) -> channel as String? to field } +
      fields.filter { it !in named }.map { null to it }
  }

  private fun channelProjections(view: UnitView): List<Pair<String, String>> {
    // A selection that states neither is projected onto **x and y** if it is dragged, and onto the
    // row's own identity if it is clicked — so a click has no channel projection at all.
    val wanted = channels.ifEmpty { if (type == "interval") listOf("x", "y") else emptyList() }
    return wanted
      .mapNotNull { channel ->
        val def = view.spec.fieldDef(channel) ?: return@mapNotNull null
        // A channel that *aggregates* cannot be projected: there is no row-level value to compare a
        // dragged extent against. Upstream warns and skips it.
        if (def.aggregate != null) return@mapNotNull null
        if (def.field == null) return@mapNotNull null
        // A bucketed field is stored under the name the bucketing wrote, not the raw column: a
        // brush
        // over `yearmonth(date)` remembers `yearmonth_date`, which is the field the marks are
        // placed
        // by and so the one an inverted pixel extent can be compared with.
        val field = if (def.timeUnit != null) Fields.vgField(def) else def.field
        channel to field
      }
      // "Prevent duplicate projections on the same field." One column bound to **both** channels
      // is one projection, keyed by the field and keeping the channel that reached it first: the
      // diagonal cell of a scatter-plot matrix plots a column against itself, and remembering it
      // twice gave the brush there two pixel extents and two stored values for one drag.
      .distinctBy { (_, field) -> field }
  }

  /**
   * The bucketings this selection remembers its rows under — `proj.timeUnit` in `project.ts`.
   *
   * A brush over `month(date)` stores a `month_date`, so anything that *tests* the brush has to
   * have a `month_date` of its own to be tested against. `parseSelectionPredicate` inserts a copy
   * of this node above whatever does the testing, which is how a second view in the same layer —
   * one that never bucketed a date itself — comes to have the column.
   */
  fun projectedTimeUnits(): List<Pair<ChannelDef, String>> {
    val view = owner ?: return emptyList()
    val wanted = channels.ifEmpty { if (type == "interval") listOf("x", "y") else emptyList() }
    return wanted.mapNotNull { channel ->
      val def = view.spec.fieldDef(channel) ?: return@mapNotNull null
      if (def.aggregate != null || def.field == null) return@mapNotNull null
      val unit = def.timeUnit?.takeIf { !Fields.isBinnedTimeUnit(it) } ?: return@mapNotNull null
      def to unit
    }
  }

  /** The channels a **brush** is dragged along, which are the ones it is drawn between. */
  fun intervalChannels(view: UnitView): List<Pair<String, String>> =
    if (type != "interval") emptyList()
    else channelProjections(view).filter { it.first == "x" || it.first == "y" }

  /** The top-level signal the tests read: the store, resolved into one set of picked values. */
  fun resolveSignal(): VegaValue = obj {
    put("name", name)
    val how = if (resolve == "global") "union" else resolve
    val point = if (type == "point") ", true, true" else ""
    put("update", "vlSelectionResolve(${quoted(store)}, ${quoted(how)}$point)")
  }

  /**
   * The expression a `{"param": …}` condition compiles to.
   *
   * An **empty** store means every row passes, which is what draws the whole chart before anything
   * is picked — `!length(data(...)) || …`. Upstream calls the same two functions from `parse.ts`:
   * the identity form compares the row's `_vgsid_`, the projected form its columns.
   */
  fun test(emptyPasses: Boolean = true): String {
    val call =
      if (byIdentity) "vlSelectionIdTest(${quoted(store)}, datum)"
      else
        "vlSelectionTest(${quoted(store)}, datum${if (resolve == "global") "" else ", ${quoted(resolve)}"})"
    // `empty: false` does not *negate* the test — it withdraws the empty store's blanket pass, so
    // nothing is picked until something is. Negating it instead drew every row picked until the
    // first click and none of them after it.
    return if (emptyPasses) "!length(data(${quoted(store)})) || $call"
    else "length(data(${quoted(store)})) && $call"
  }
}
