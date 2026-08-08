package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * Small multiples: one cell per value of a field, gridded by Vega's layout.
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
 * - the facet's own values need a dataset of their own, so the layout can count the columns and the
 *   headers can title themselves from it
 *
 * The runtime already grids and titles cells this way (`trellis-layout`, `trellis-headers`); what
 * is here is the compiler's half.
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

  /**
   * The `layout` block.
   *
   * `bounds: "full"` and `align: "all"` are what keep the cells the same size as each other rather
   * than each shrinking to its own content — without them a trellis's columns drift apart wherever
   * one cell's axis labels are wider than another's.
   */
  fun layout(spacing: Double, titleOffset: Double): VegaValue = obj {
    put("padding", num(spacing))
    put(
      "offset",
      obj { put(if (isColumn) "columnTitle" else "rowTitle", num(titleOffset)) },
    )
    if (isColumn) {
      put("columns", signalRef("length(data('$domainData'))"))
    } else {
      put("columns", num(1))
    }
    put("bounds", "full")
    put("align", "all")
  }

  /** The heading over the whole grid, naming the field the cells are split by. */
  fun titleGroup(title: String, offset: Double): VegaValue = obj {
    put("name", if (isColumn) "column-title" else "row-title")
    put("type", "group")
    put("role", if (isColumn) "column-title" else "row-title")
    put(
      "title",
      obj {
        put("text", title)
        put("style", "guide-title")
        if (!isColumn) put("orient", "left")
        put("offset", num(offset))
      },
    )
  }

  /**
   * A header or footer: the group a shared axis and a per-cell caption are drawn in.
   *
   * There are four possible groups and a chart uses two of them. The rule is upstream's and it is
   * about where a reader looks: a column's caption goes above its cell and its axis below the whole
   * grid, while a row's caption goes beside its cell and its axis to the left of the grid.
   */
  fun headerGroup(
    kind: String,
    axes: List<VegaValue>,
    /** One group per facet value, sized and sorted like the cells it sits against. */
    perCell: Boolean,
    /** Whether it also carries the cell's caption. A footer is per-cell and captions nothing. */
    captioned: Boolean,
    sizeSignal: String,
    titleOffset: Double,
  ): VegaValue? {
    if (axes.isEmpty() && !captioned) return null
    return obj {
      put("name", if (perCell) "${channel}_$kind" else "${if (isColumn) "row" else "column"}_$kind")
      put("type", "group")
      put("role", "${if (perCell) channel else if (isColumn) "row" else "column"}-$kind")
      if (perCell) {
        put("from", obj { put("data", domainData) })
        put(
          "sort",
          obj {
            put("field", "datum[${quoted(field)}]")
            put("order", "ascending")
          },
        )
        if (captioned)
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
      }
      put(
        "encode",
        obj {
          put(
            "update",
            obj { put(if (isColumn == perCell) "width" else "height", signalRef(sizeSignal)) },
          )
        },
      )
      if (axes.isNotEmpty()) put("axes", arr(axes))
    }
  }

  /** The cell itself: one group per facet value, holding the marks and the gridlines. */
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
            put("groupby", strings(listOf(field)))
          },
        )
      },
    )
    put(
      "sort",
      obj {
        put("field", strings(listOf("datum[${quoted(field)}]")))
        put("order", strings(listOf("ascending")))
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
