package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

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
) {

  val store: String = "${name}_store"

  /** Whether the pointer becomes a hand over a mark: a *hover* selection is not clicked. */
  val showsPointer: Boolean
    get() =
      type == "point" && !bindsScales && (on as? VegaValue.Obj)?.string("type") != "pointerover"

  /** Whether a picked row is remembered by its **identity** rather than by any column of it. */
  val byIdentity: Boolean
    get() = fields.isEmpty() && channels.isEmpty()

  companion object {

    /** Vega's own name for the row identity an `identifier` transform writes. */
    const val SELECTION_ID: String = "_vgsid_"

    /**
     * `defaultConfig` in `selection.ts` — the parts of a selection a specification leaves out.
     *
     * A `point` selection is clicked, remembers rows by identity, toggles with the shift key and
     * resolves globally; an `interval` is dragged. Both clear on a double click.
     */
    fun of(spec: VegaValue.Obj): List<Selection> {
      val declared = spec.array("params") ?: return emptyList()
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
  fun storeData(): VegaValue = obj {
    put("name", store)
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
  fun test(negated: Boolean = false): String {
    val call =
      if (byIdentity) "vlSelectionIdTest(${quoted(store)}, datum)"
      else
        "vlSelectionTest(${quoted(store)}, datum${if (resolve == "global") "" else ", ${quoted(resolve)}"})"
    return if (negated) "length(data(${quoted(store)})) && !$call"
    else "!length(data(${quoted(store)})) || $call"
  }
}
