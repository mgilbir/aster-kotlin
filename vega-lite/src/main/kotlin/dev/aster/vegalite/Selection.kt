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
      type == "point" && !bindsScales && (on as? VegaValue.Obj)?.string("type") != "pointerover"

  /** Whether a picked row is remembered by its **identity** rather than by any column of it. */
  val byIdentity: Boolean
    get() = type != "interval" && fields.isEmpty() && channels.isEmpty()

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
      return found.distinctBy { it.name }
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
              null -> if (type == "point") "event.shiftKey" else null
              VegaValue.Bool(false) -> null
              is VegaValue.Str -> stated.value
              else -> "event.shiftKey"
            },
          bindsScales = bind == VegaValue.Str("scales"),
          clear =
            when (val stated = options.fields["clear"]) {
              VegaValue.Bool(false) -> null
              is VegaValue.Str -> stated.value
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
    private fun stream(stated: VegaValue?, type: String): VegaValue {
      val default = if (type == "point") "click" else null
      val name = (stated as? VegaValue.Str)?.value ?: default
      if (stated != null && stated !is VegaValue.Str) return stated
      if (name == null) return VegaValue.Null
      if (name.any { it in "[],>:!@ " }) return VegaValue.Str(name)
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
    val onBrush = obj {
      put("source", "scope")
      put("type", "pointerdown")
      put("markname", brush)
    }
    out += obj {
      put("name", "${name}_translate_anchor")
      put("value", VegaValue.EmptyObject)
      put(
        "on",
        arr(
          listOf(
            obj {
              put("events", arr(listOf(onBrush)))
              put(
                "update",
                "{x: x(unit), y: y(unit)" +
                  projected.joinToString("") { (channel, _) ->
                    ", extent_$channel: slice(${name}_$channel)"
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
              put(
                "events",
                arr(
                  listOf(
                    obj {
                      put("source", "window")
                      put("type", "pointermove")
                      put("consume", VegaValue.Bool(true))
                      put(
                        "between",
                        arr(
                          listOf(
                            onBrush,
                            obj {
                              put("source", "window")
                              put("type", "pointerup")
                            },
                          )
                        ),
                      )
                    }
                  )
                ),
              )
              put(
                "update",
                "{x: ${name}_translate_anchor.x - x(unit), y: ${name}_translate_anchor.y - y(unit)}",
              )
            }
          )
        ),
      )
    }
    val onWheel = obj {
      put("source", "scope")
      put("type", "wheel")
      put("consume", VegaValue.Bool(true))
      put("markname", brush)
    }
    out += obj {
      put("name", "${name}_zoom_anchor")
      put(
        "on",
        arr(
          listOf(
            obj {
              put("events", arr(listOf(onWheel)))
              put("update", "{x: x(unit), y: y(unit)}")
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
              put("events", arr(listOf(onWheel)))
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
              put("update", "modify(${quoted(store)}, ${name}_tuple, true)")
            }
          )
        ),
      )
    }
    return out
  }

  /** The two rects a brush is drawn as: the background under the marks, the outline over them. */
  fun brushMarks(view: UnitView, unit: String, background: Boolean): List<VegaValue> {
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
                  put("fill", obj { put("value", "#333") })
                  put("fillOpacity", obj { put("value", 0.125) })
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
      put(
        "stroke",
        arr(
          listOf(
            obj {
              put("test", visible)
              put("value", "white")
            },
            obj { put("value", VegaValue.Null) },
          )
        ),
      )
    }
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
                put("cursor", obj { put("value", "move") })
                put("fill", obj { put("value", "transparent") })
              },
            )
            put("update", outlined)
          },
        )
      }
    )
  }

  fun signals(unit: String): List<VegaValue> {
    val out = mutableListOf<VegaValue>()
    val datum = "(item().isVoronoi ? datum.datum : datum)"
    val picked =
      if (byIdentity) {
        "unit: $unit, ${SELECTION_ID}: $datum[${quoted(SELECTION_ID)}]"
      } else {
        val values = fields.joinToString(", ") { "$datum[${quoted(it)}]" }
        "unit: $unit, fields: ${name}_tuple_fields, values: [$values]"
      }
    // A click on a group, on a legend or on another selection's brush is not a pick: the first is
    // the plot itself, the second is a legend binding's business, and the third belongs to the
    // brush that owns it.
    val guard =
      "datum && item().mark.marktype !== 'group' && indexof(item().mark.role, 'legend') < 0"
    out += obj {
      put("name", "${name}_tuple")
      put(
        "on",
        arr(
          listOfNotNull(
            obj {
              put("events", arr(listOf(on)))
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
            fields.map {
              obj {
                put("type", "E")
                put("field", it)
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
                put("events", arr(listOf(on)))
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
  fun intervalSignals(view: UnitView, initial: VegaValue?): List<VegaValue> {
    val out = mutableListOf<VegaValue>()
    val projected = intervalChannels(view)
    if (projected.isEmpty()) return out
    val brush = "${name}_brush"
    val notOnBrush = "!event.item || event.item.mark.name !== ${quoted(brush)}"

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
            listOfNotNull(
              obj {
                put(
                  "events",
                  obj {
                    put("source", "scope")
                    put("type", "pointerdown")
                    put("filter", arr(listOf(str(notOnBrush))))
                  },
                )
                put("update", "[$channel(unit), $channel(unit)]")
              },
              obj {
                put(
                  "events",
                  obj {
                    put("source", "window")
                    put("type", "pointermove")
                    put("consume", VegaValue.Bool(true))
                    put(
                      "between",
                      arr(
                        listOf(
                          obj {
                            put("source", "scope")
                            put("type", "pointerdown")
                            put("filter", arr(listOf(str(notOnBrush))))
                          },
                          obj {
                            put("source", "window")
                            put("type", "pointerup")
                          },
                        )
                      ),
                    )
                  },
                )
                put("update", "[$pixels[0], clamp($channel(unit), 0, $size)]")
              },
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
  private fun projectionType(view: UnitView, channel: String): String =
    when {
      type == "interval" && channel in Channels.SCALE_CHANNELS && continuous(view, channel) -> "R"
      view.spec.fieldDef(channel)?.bin != null -> "R-RE"
      else -> "E"
    }

  /** `hasContinuousDomain`: whether a channel's scale can be inverted to a number. */
  private fun continuous(view: UnitView, channel: String): Boolean =
    view.scaleType(channel) in CONTINUOUS_DOMAINS

  fun intervalChannels(view: UnitView): List<Pair<String, String>> {
    if (type != "interval") return emptyList()
    val wanted = channels.ifEmpty { listOf("x", "y") }
    return wanted.mapNotNull { channel ->
      val def = view.spec.fieldDef(channel) ?: return@mapNotNull null
      // A channel that *aggregates* cannot be projected: there is no row-level value to compare a
      // dragged extent against. Upstream warns and skips it.
      if (def.aggregate != null) return@mapNotNull null
      if (def.field == null) return@mapNotNull null
      // A bucketed field is stored under the name the bucketing wrote, not the raw column: a brush
      // over `yearmonth(date)` remembers `yearmonth_date`, which is the field the marks are placed
      // by and so the one an inverted pixel extent can be compared with.
      val field = if (def.timeUnit != null) Fields.vgField(def) else def.field
      channel to field
    }
  }

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
