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
internal class FacetGrid(val row: Facet?, val column: Facet?) {

  /** Row before column, which is the order upstream groups, sorts and crosses by. */
  val fields: List<String> = listOfNotNull(row?.field, column?.field)

  /**
   * Column before row: `compile/data/facet.ts` assembles `for (const channel of [COLUMN, ROW])`.
   */
  fun domainDatasets(source: String): List<VegaValue> =
    listOfNotNull(column, row).map { it.domainDataset(source) }

  /**
   * The `layout` block.
   *
   * `bounds: "full"` and `align: "all"` are what keep the cells the same size as each other rather
   * than each shrinking to its own content — without them a trellis's columns drift apart wherever
   * one cell's axis labels are wider than another's. `columns` counts the column facet's own values
   * and is 1 when a chart is faceted by rows alone.
   */
  fun layout(spacing: Double, titleOffset: Double, titled: Set<String>): VegaValue = obj {
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
  fun headerGroups(
    vertical: List<VegaValue>,
    horizontal: List<VegaValue>,
    titleOffset: Double,
  ): List<VegaValue> {
    fun leading(axes: List<VegaValue>) = axes.filter {
      it.string("orient") == "left" || it.string("orient") == "top"
    }
    return listOfNotNull(
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
  fun cellGroup(
    dataName: String,
    marks: List<VegaValue>,
    axes: List<VegaValue>,
    widthSignal: String,
    heightSignal: String,
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
