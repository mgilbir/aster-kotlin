package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * One facet channel: the field a grid is split by along one direction.
 *
 * @see FacetGrid, which is what a chart actually has — `row` and `column` are two of these, and a
 *   chart may carry either or both.
 */
internal class Facet(val channel: String, val def: ChannelDef) {

  /** `column` grids horizontally, `row` vertically. */
  val isColumn: Boolean = channel == "column"

  val field: String = Fields.vgField(def)

  /** `column_domain` — the facet's distinct values, which the layout counts and headers title. */
  val domainData: String = "${channel}_domain"

  fun domainDataset(source: String): VegaValue = obj {
    put("name", domainData)
    put("source", source)
    put(
      "transform",
      arr(
        obj {
          put("type", "aggregate")
          put("groupby", strings(listOf(field)))
        }
      ),
    )
  }

  /** The heading over the whole grid, naming the field the cells are split by. */
  fun titleGroup(title: String, offset: Double): VegaValue = obj {
    put("name", "$channel-title")
    put("type", "group")
    put("role", "$channel-title")
    put(
      "title",
      obj {
        put("text", title)
        if (!isColumn) put("orient", "left")
        put("style", "guide-title")
        put("offset", num(offset))
      },
    )
  }
}

/**
 * Small multiples: one cell per combination of the faceting fields, gridded by Vega's layout.
 *
 * A faceted chart is not a chart with an extra channel — it is a chart of charts, and almost
 * everything about the output moves:
 *
 * - the marks go inside a **cell** group faceted from the data, and the cell is what the layout
 *   grids; the plotting area's size becomes `child_width`/`child_height`, because `width` now means
 *   the whole grid
 * - the **axes split**. The gridlines stay in every cell, where the data is, and the labelled axis
 *   moves out to a header or footer group drawn once for the whole row or column — which is what
 *   makes a trellis readable rather than a wall of repeated tick labels
 * - each faceting field needs a dataset of its own values, so the layout can count the columns and
 *   the headers can title themselves from it
 *
 * The four bands around the grid are chosen by one rule, upstream's `getHeaderType`: a y axis
 * belongs to the **row** band and an x axis to the **column** one, and each lands in the *header*
 * when its orientation is top or left and the *footer* when it is bottom or right
 * (`compile/header/parse.ts`). The captions land in the header of their own channel. So a
 * column-faceted chart captions each column above it and shares one x axis in a column footer
 * below, while a row-faceted one captions each row in a row header beside it and still puts its x
 * axis in a column footer — a *footer with no facet behind it*, which is a single group rather than
 * one per cell. That last case is why this is a grid of two channels rather than one channel with a
 * direction: with only a direction, a row-faceted chart put its x axis in a column header and drew
 * it above the chart.
 */
/**
 * What a faceted chart's marks are wrapped in, whichever way the facet was written.
 *
 * There are two, and they are different *layouts* rather than two spellings of one: a
 * `row`/`column` grid crosses two fields, so its width is however many values the column facet has;
 * a wrapped facet lays one field's values out `columns` at a time and so has to count its own rows
 * and columns to know where the shared axes go. Everything else — the cell, the split axes, the
 * child sizes — is the same, which is why they share this.
 */
internal interface FacetLayout {

  /** The columns each cell is grouped by, which every grouping in the data flow has to carry. */
  val fields: List<String>

  /**
   * The facet's own values, and — for a wrapped facet — the grid's row and column counts.
   *
   * @param vertical whether any shared axis landed in a row band, [horizontal] the same for a
   *   column band. A wrapped facet counts a direction only when something is drawn along it.
   */
  fun domainDatasets(source: String, vertical: Boolean, horizontal: Boolean): List<VegaValue>

  fun layout(spacing: Double, titleOffset: Double, config: Config): VegaValue

  /** The heading over the grid and the bands of shared axes around it, in upstream's order. */
  fun groups(
    vertical: List<VegaValue>,
    horizontal: List<VegaValue>,
    titleOffset: Double,
    config: Config,
  ): List<VegaValue>

  fun cellGroup(
    dataName: String,
    marks: List<VegaValue>,
    axes: List<VegaValue>,
    widthSignal: String,
    heightSignal: String,
    titleOffset: Double,
  ): VegaValue
}

internal class FacetGrid(val row: Facet?, val column: Facet?) : FacetLayout {

  /** Row before column, which is the order upstream groups, sorts and crosses by. */
  override val fields: List<String> = listOfNotNull(row?.field, column?.field)

  /**
   * Column before row: `compile/data/facet.ts` assembles `for (const channel of [COLUMN, ROW])`.
   */
  override fun domainDatasets(
    source: String,
    vertical: Boolean,
    horizontal: Boolean,
  ): List<VegaValue> = listOfNotNull(column, row).map { it.domainDataset(source) }

  /**
   * The `layout` block.
   *
   * `bounds: "full"` and `align: "all"` are what keep the cells the same size as each other rather
   * than each shrinking to its own content — without them a trellis's columns drift apart wherever
   * one cell's axis labels are wider than another's. `columns` counts the column facet's own values
   * and is 1 when a chart is faceted by rows alone.
   */
  override fun layout(spacing: Double, titleOffset: Double, config: Config): VegaValue = obj {
    val titled = titles(config).keys
    put("padding", num(spacing))
    val offsets =
      listOfNotNull(row?.takeIf { it.channel in titled }, column?.takeIf { it.channel in titled })
    if (offsets.isNotEmpty()) {
      put(
        "offset",
        obj {
          offsets.forEach { put(if (it.isColumn) "columnTitle" else "rowTitle", num(titleOffset)) }
        },
      )
    }
    when {
      column != null -> put("columns", signalRef("length(data('${column.domainData}'))"))
      row != null -> put("columns", num(1))
    }
    put("bounds", "full")
    put("align", "all")
  }

  /**
   * A header or footer: the group a shared axis, a per-cell caption, or both are drawn in.
   *
   * A band exists at all only when something is in it, which is upstream's `if (title || hasAxes)`.
   * The `from` and `sort` follow the *channel*, not the band: a column footer under a
   * column-faceted chart is one group per column, and the same footer under a row-faceted chart is
   * a single group holding the one shared axis.
   */
  private fun band(
    channel: String,
    kind: String,
    axes: List<VegaValue>,
    captioned: Boolean,
    titleOffset: Double,
  ): VegaValue? {
    val isColumn = channel == "column"
    val facet = if (isColumn) column else row
    if (axes.isEmpty() && !(captioned && facet != null)) return null
    return obj {
      put("name", "${channel}_$kind")
      put("type", "group")
      put("role", "$channel-$kind")
      if (facet != null) {
        put("from", obj { put("data", facet.domainData) })
        put(
          "sort",
          obj {
            put("field", "datum[${quoted(facet.field)}]")
            put("order", "ascending")
          },
        )
        if (captioned) {
          put(
            "title",
            obj {
              put(
                "text",
                signalRef(
                  "isValid(parent[${quoted(facet.field)}]) ? parent[${quoted(facet.field)}] : " +
                    "\"\"+parent[${quoted(facet.field)}]"
                ),
              )
              if (!isColumn) put("orient", "left")
              put("style", "guide-label")
              put("frame", "group")
              put("offset", num(titleOffset))
            },
          )
        }
      }
      put(
        "encode",
        obj {
          put(
            "update",
            obj {
              put(
                if (isColumn) "width" else "height",
                signalRef(if (isColumn) "child_width" else "child_height"),
              )
            },
          )
        },
      )
      if (axes.isNotEmpty()) put("axes", arr(axes))
    }
  }

  /**
   * Every band the chart needs, in upstream's order: row before column, header before footer.
   *
   * @param vertical the y axes, which belong to the row band; [horizontal] the x axes, which belong
   *   to the column band.
   */
  /**
   * Which facet channels name themselves, and what they are called.
   *
   * The `layout` keeps a heading clear of the labels beneath it with `offset.rowTitle` or
   * `offset.columnTitle`, and upstream writes the offset only where there is a heading to keep
   * clear — `if (layoutHeaderComponent.title)` in `getHeaderLayoutMixins`.
   */
  private fun titles(config: Config): Map<String, String> =
    listOfNotNull(row, column)
      .mapNotNull { facet ->
        (Fields.title(facet.def, config) as? VegaValue.Str)?.let { facet.channel to it.value }
      }
      .toMap()

  override fun groups(
    vertical: List<VegaValue>,
    horizontal: List<VegaValue>,
    titleOffset: Double,
    config: Config,
  ): List<VegaValue> {
    fun leading(axes: List<VegaValue>) = axes.filter {
      it.string("orient") == "left" || it.string("orient") == "top"
    }
    // Both headings first, rows before columns, then the four bands of labels around the grid.
    val titles = titles(config)
    return listOfNotNull(row, column).mapNotNull { facet ->
      titles[facet.channel]?.let { facet.titleGroup(it, titleOffset) }
    } +
      listOfNotNull(
        band("row", "header", leading(vertical), captioned = true, titleOffset),
        band("row", "footer", vertical - leading(vertical).toSet(), captioned = false, titleOffset),
        band("column", "header", leading(horizontal), captioned = true, titleOffset),
        band(
          "column",
          "footer",
          horizontal - leading(horizontal).toSet(),
          captioned = false,
          titleOffset,
        ),
      )
  }

  /**
   * The cell itself: one group per combination of facet values, holding the marks and the
   * gridlines.
   *
   * `aggregate: {cross: true}` when the chart is faceted both ways, so that a combination no row
   * carries still gets a cell and the grid stays rectangular — `const cross = !!row && !!column` in
   * `compile/facet.ts`.
   */
  override fun cellGroup(
    dataName: String,
    marks: List<VegaValue>,
    axes: List<VegaValue>,
    widthSignal: String,
    heightSignal: String,
    titleOffset: Double,
  ): VegaValue = obj {
    put("name", "cell")
    put("type", "group")
    put("style", "cell")
    put(
      "from",
      obj {
        put(
          "facet",
          obj {
            put("name", "facet")
            put("data", dataName)
            put("groupby", strings(fields))
            if (row != null && column != null) {
              put("aggregate", obj { put("cross", VegaValue.Bool(true)) })
            }
          },
        )
      },
    )
    put(
      "sort",
      obj {
        put("field", strings(fields.map { "datum[${quoted(it)}]" }))
        put("order", strings(fields.map { "ascending" }))
      },
    )
    put(
      "encode",
      obj {
        put(
          "update",
          obj {
            put("width", signalRef(widthSignal))
            put("height", signalRef(heightSignal))
          },
        )
      },
    )
    put("marks", arr(marks))
    if (axes.isNotEmpty()) put("axes", arr(axes))
  }
}

/**
 * One field's values laid out `columns` at a time — `{"facet": {"field": …}, "columns": n}`.
 *
 * A grid of two fields knows its own shape: the columns are the column facet's values and the rows
 * are the row facet's. A wrapped facet knows neither, because it has one list and a number to wrap
 * it at, so upstream *computes* both — `ceil(length(facet_domain) / columns)` rows and
 * `min(length(facet_domain), columns)` columns — as two `sequence` datasets, and the shared axes
 * are drawn once per entry of those rather than once per facet value. That is the whole difference,
 * and it is why this is a layout of its own rather than a grid with one channel filled in.
 *
 * The caption moves too. A grid captions each column in a header band above it; a wrapped facet has
 * no such band — its columns are positions, not values — so every **cell** carries its own caption,
 * and the heading over the whole grid is a `column-title` naming the field.
 */
internal class FacetWrap(val def: ChannelDef, private val columns: Int?) : FacetLayout {

  private val field: String = Fields.vgField(def)

  private val domainData: String = "facet_domain"

  override val fields: List<String> = listOf(field)

  override fun domainDatasets(
    source: String,
    vertical: Boolean,
    horizontal: Boolean,
  ): List<VegaValue> {
    if (!vertical && !horizontal) return emptyList()
    val cardinality = "length(data(\"$domainData\"))"
    val counts =
      listOfNotNull(
        if (vertical) "row" to (columns?.let { signalRef("ceil($cardinality / $it)") } ?: num(1.0))
        else null,
        if (horizontal)
          "column" to
            (columns?.let { signalRef("min($cardinality, $it)") } ?: signalRef(cardinality))
        else null,
      )
    return listOf(
      obj {
        put("name", domainData)
        put("source", source)
        put(
          "transform",
          arr(
            obj {
              put("type", "aggregate")
              put("groupby", strings(listOf(field)))
            }
          ),
        )
      }
    ) +
      counts.map { (direction, stop) ->
        obj {
          put("name", "${domainData}_$direction")
          put(
            "transform",
            arr(
              obj {
                put("type", "sequence")
                put("start", num(0.0))
                put("stop", stop)
              }
            ),
          )
        }
      }
  }

  override fun layout(spacing: Double, titleOffset: Double, config: Config): VegaValue = obj {
    put("padding", num(spacing))
    put("bounds", "full")
    put("align", "all")
    // Only where the specification said so: with no `columns`, the whole facet is one row and the
    // layout has no number to write down.
    columns?.let { put("columns", num(it.toDouble())) }
  }

  override fun groups(
    vertical: List<VegaValue>,
    horizontal: List<VegaValue>,
    titleOffset: Double,
    config: Config,
  ): List<VegaValue> {
    fun leading(axes: List<VegaValue>) = axes.filter {
      it.string("orient") == "left" || it.string("orient") == "top"
    }
    val heading =
      (Fields.title(def, config) as? VegaValue.Str)?.let { title ->
        obj {
          put("name", "facet-title")
          put("type", "group")
          put("role", "column-title")
          put(
            "title",
            obj {
              put("text", title.value)
              put("style", "guide-title")
              put("offset", num(titleOffset))
            },
          )
        }
      }
    return listOfNotNull(
      heading,
      band("row", "header", leading(vertical)),
      band("row", "footer", vertical - leading(vertical).toSet()),
      band("column", "header", leading(horizontal)),
      band("column", "footer", horizontal - leading(horizontal).toSet()),
    )
  }

  /**
   * A band of shared axes, drawn once per *position* in the grid rather than once per facet value.
   *
   * A wrapped facet's rows and columns are places, so the band reads the counting sequence and has
   * nothing to caption itself with — which is why the caption is on the cell instead.
   */
  private fun band(channel: String, kind: String, axes: List<VegaValue>): VegaValue? {
    if (axes.isEmpty()) return null
    val isColumn = channel == "column"
    return obj {
      put("name", "${channel}_$kind")
      put("type", "group")
      put("role", "$channel-$kind")
      put("from", obj { put("data", "${domainData}_$channel") })
      put(
        "encode",
        obj {
          put(
            "update",
            obj {
              put(
                if (isColumn) "width" else "height",
                signalRef(if (isColumn) "child_width" else "child_height"),
              )
            },
          )
        },
      )
      put("axes", arr(axes))
    }
  }

  override fun cellGroup(
    dataName: String,
    marks: List<VegaValue>,
    axes: List<VegaValue>,
    widthSignal: String,
    heightSignal: String,
    titleOffset: Double,
  ): VegaValue = obj {
    put("name", "cell")
    put("type", "group")
    // Each cell names the value it holds, there being no band of column headings to name it in.
    put(
      "title",
      obj {
        put(
          "text",
          signalRef(
            "isValid(parent[${quoted(field)}]) ? parent[${quoted(field)}] : " +
              "\"\"+parent[${quoted(field)}]"
          ),
        )
        put("style", "guide-label")
        put("frame", "group")
        put("offset", num(titleOffset))
      },
    )
    put("style", "cell")
    put(
      "from",
      obj {
        put(
          "facet",
          obj {
            put("name", "facet")
            put("data", dataName)
            put("groupby", strings(fields))
          },
        )
      },
    )
    put(
      "sort",
      obj {
        put("field", strings(fields.map { "datum[${quoted(it)}]" }))
        put("order", strings(fields.map { "ascending" }))
      },
    )
    put(
      "encode",
      obj {
        put(
          "update",
          obj {
            put("width", signalRef(widthSignal))
            put("height", signalRef(heightSignal))
          },
        )
      },
    )
    put("marks", arr(marks))
    if (axes.isNotEmpty()) put("axes", arr(axes))
  }
}
