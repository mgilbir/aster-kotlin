package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * A cell's caption: the value the cell holds, written the way that column is written elsewhere.
 *
 * `formatSignalRef` with `expr: "parent"`, the same rule a mark's text goes through. A *bucketed
 * instant* is spoken as a date with the specifier Vega picks at render time — the same one the axis
 * labels use, so a trellis of years is captioned "2005" and not "1104537600000".
 */
private fun headerText(def: ChannelDef, field: String): String {
  val accessor = "parent[${quoted(field)}]"
  val timeUnit = def.timeUnit
  if (def.type == MeasureType.TEMPORAL || timeUnit != null) {
    val utc = timeUnit?.contains("utc") == true || def.scale.string("type") == "utc"
    val prefix = if (utc) "utc" else "time"
    val specifier = if (timeUnit != null) Fields.timeUnitSpecifier(timeUnit) else "\"%b %d, %Y\""
    return "${prefix}Format($accessor, $specifier)"
  }
  return "isValid($accessor) ? $accessor : \"\"+$accessor"
}

/**
 * One facet channel: the field a grid is split by along one direction.
 *
 * @see FacetGrid, which is what a chart actually has — `row` and `column` are two of these, and a
 *   chart may carry either or both.
 */
internal class Facet(val channel: String, val def: ChannelDef) {

  /**
   * `assembleHeaderProperties`: what a `header` block says about the caption, renamed on the way.
   *
   * A header names its properties `titleFontSize`/`labelFontSize` and a Vega title names them
   * `fontSize`, so the map is a rename per part — `HEADER_TITLE_PROPERTIES_MAP` and its label twin.
   * Without it a header's whole styling was read and dropped.
   */
  /**
   * Which side of the grid this channel's captions hang off — `getHeaderChannel`.
   *
   * A column's captions sit above its cells and a row's to their left, unless the header says
   * otherwise; `"bottom"` and `"right"` move them to the *footer* band, which is a different group
   * with a different name rather than the same one moved.
   */
  fun headerOrient(part: String): String {
    val header = def.raw.obj("header")
    val stated = header?.string("${part}Orient") ?: header?.string("orient")
    return stated ?: if (isColumn) "top" else "left"
  }

  /** Whether this channel's captions belong to the trailing band rather than the leading one. */
  fun captionsInFooter(): Boolean = headerOrient("label") in setOf("bottom", "right")

  fun headerProperties(part: String): Map<String, VegaValue> {
    val header = def.raw.obj("header") ?: return emptyMap()
    val renamed =
      mapOf(
        "Align" to "align",
        "Anchor" to "anchor",
        "Angle" to "angle",
        "Baseline" to "baseline",
        "Color" to "color",
        "Font" to "font",
        "FontSize" to "fontSize",
        "FontStyle" to "fontStyle",
        "FontWeight" to "fontWeight",
        "Limit" to "limit",
        "LineHeight" to "lineHeight",
        "Orient" to "orient",
        "Padding" to "offset",
      )
    val out = LinkedHashMap<String, VegaValue>()
    for ((suffix, name) in renamed) {
      header.fields["$part$suffix"]?.let { out[name] = it }
    }
    return out
  }

  /** `column` grids horizontally, `row` vertically. */
  val isColumn: Boolean = channel == "column"

  val field: String = Fields.vgField(def)

  /** The `sort` object this channel orders its cells by, where it names one. */
  private val sortObject: VegaValue.Obj? = (def.sort as? VegaValue.Obj)?.takeIf { it.has("field") }

  /** `DEFAULT_SORT_OP` is `min`, not the `sum` a reader might expect from the encoding sorts. */
  private val sortOp: String? = sortObject?.let { it.string("op") ?: "min" }

  private val sortSource: String? = sortObject?.string("field")

  fun sortSourceField(): String = sortSource!!

  fun sortOperation(): String = sortOp!!

  /**
   * What the *domain* dataset calls the aggregate the cells are ordered by — `sum_amount`.
   *
   * `vgField(sortField, {forAs: true})`: the plain aggregate name, which is what the header bands
   * read, since they are drawn from that dataset and each of its rows is already one cell's worth.
   */
  val sortAggregate: String? = sortOp?.let { "${it}_$sortSource" }

  /**
   * What the *cell* group calls the same aggregate — `sum_amount_by_era`.
   *
   * `facetSortFieldName` suffixes it with the field being faceted on, because the facet computes it
   * a second time over its own partition and the two names must not collide.
   */
  val cellSortAggregate: String? = sortAggregate?.let { "${it}_by_$field" }

  /**
   * Which way the cells run — `facetSortOrder` in `compile/facet.ts`.
   *
   * A facet channel's `sort` orders the *cells*, not anything inside one, so it lands on the group
   * mark that makes them and on the header bands beside it.
   */
  val order: String =
    when {
      sortObject != null -> sortObject.string("order") ?: "ascending"
      (def.sort as? VegaValue.Str)?.value == "descending" -> "descending"
      else -> "ascending"
    }

  /** What the cells are ordered *by*: the facet's own column, or the aggregate standing for it. */
  fun sortKey(inCell: Boolean): String = (if (inCell) cellSortAggregate else sortAggregate) ?: field

  fun reportUnsupportedSort(
    diagnostics: dev.aster.vega.model.DiagnosticCollector,
    crossed: Boolean,
  ) {
    val sort = def.sort ?: return
    if (sort is VegaValue.Str || sort == VegaValue.Null) return
    val reason =
      when {
        sort is VegaValue.Arr ->
          "names a list of values, whose place in it has to be computed onto every row as a " +
            "column of its own before the cells are made"
        sortObject == null -> "names no `field` to aggregate"
        crossed ->
          "names an aggregate on a facet that is crossed both ways, where the key has to be " +
            "written onto the rows first so that each cell can take the greatest of its own"
        else -> return
      }
    diagnostics.error(
      VegaLiteDiagnostics.UNSUPPORTED_ENCODING_PROPERTY,
      "A `sort` on the `$channel` facet that $reason is not implemented; the cells run in the " +
        "order of `$field` instead. A bare `\"ascending\"` or `\"descending\"` is honoured, so " +
        "is an aggregate of another column on a facet gridded one way, and a column already in " +
        "the order you want can be faceted on directly.",
      jsonPath = "$.encoding.$channel.sort",
    )
  }

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
          // The key the cells are ordered by, measured once per cell — which is what this dataset
          // already holds a row of. `assembleRowColumnHeaderData` puts it here rather than leaving
          // the header bands to sort on something they cannot see.
          if (sortAggregate != null) {
            put("fields", strings(listOf(sortSource!!)))
            put("ops", strings(listOf(sortOp!!)))
            put("as", strings(listOf(sortAggregate)))
          }
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
        // A header moved to the other side of the grid takes its heading with it.
        headerOrient("title").takeIf { it != "top" }?.let { put("orient", it) }
        headerProperties("title").forEach { (key, value) -> put(key, value) }
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
   * The definitions those columns came from, which the data flow still has to honour.
   *
   * A facet channel is lifted out of the encoding before the scales are built — it says nothing
   * about what happens *within* a cell — but the column it names may still need bucketing, and the
   * transform that buckets it belongs to the facet's own model, above the cell's. Left behind, a
   * trellis broken down by year had no `year_date` column to break down by.
   */
  val defs: List<ChannelDef>

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

  override val defs: List<ChannelDef> = listOfNotNull(row?.def, column?.def)

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
    // `titleAnchor`: a heading over a *trailing* band is anchored at the end of the grid rather
    // than the start, which is where the band it names now sits.
    val anchors =
      listOfNotNull(row, column).filter { it.channel in titled && it.captionsInFooter() }
    if (anchors.isNotEmpty()) {
      put("titleAnchor", obj { anchors.forEach { put(it.channel, "end") } })
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
    titleOffset: Double,
  ): VegaValue? {
    val isColumn = channel == "column"
    val facet = if (isColumn) column else row
    // `"header": null` takes the *caption* off, not the band: the band is also where a shared axis
    // is drawn, and that axis is still wanted. A band with neither is the one that disappears.
    val wanted = if (facet?.captionsInFooter() == true) kind == "footer" else kind == "header"
    val captions = wanted && facet != null && facet.def.raw.fields["header"] != VegaValue.Null
    if (axes.isEmpty() && !captions) return null
    return obj {
      put("name", "${channel}_$kind")
      put("type", "group")
      put("role", "$channel-$kind")
      if (facet != null) {
        put("from", obj { put("data", facet.domainData) })
        put(
          "sort",
          obj {
            put("field", "datum[${quoted(facet.sortKey(inCell = false))}]")
            put("order", facet.order)
          },
        )
        if (captions) {
          put(
            "title",
            obj {
              put(
                "text",
                signalRef(headerText(facet.def, facet.field)),
              )
              if (!isColumn) put("orient", "left")
              put("style", "guide-label")
              put("frame", "group")
              put("offset", num(titleOffset))
              // A caption in the trailing band hangs off the other side of its cell.
              facet.headerOrient("label").takeIf { it != "top" }?.let { put("orient", it) }
              // `defaultHeaderGuideBaseline`/`defaultHeaderGuideAlign`: a **row**'s captions run
              // down the side of the grid, so each is turned to face its cell — right-aligned
              // against the cells and centred on them. A column's sit above and need neither,
              // which upstream expresses by leaving their angle undefined.
              // `defaultHeaderGuideAlign`/`defaultHeaderGuideBaseline` both open with "if the
              // angle is stated" — a caption left at whatever angle the renderer chooses is left
              // at whatever anchor it chooses too. State one and the caption has to be turned to
              // face its cell: a **row**'s runs down the side of the grid, so it is right-aligned
              // against the cells and centred on them.
              val angle = facet.def.raw.obj("header")?.number("labelAngle")
              if (angle != null && !facet.isColumn) {
                // `defaultLabelAlign` through the header's own orientation, a row's captions
                // being `left`/`y`: a caption turned a *quarter* turn is **centred** rather than
                // pushed to one side, its own length now running across the band rather than
                // along it, so there is no side left to push it to.
                val turned = ((angle % 360) + 360) % 360
                put("baseline", "middle")
                put("align", Guides.labelAlign(turned, "y", "left"))
                put("angle", num(angle))
              }
              facet.headerProperties("label").forEach { (key, value) -> put(key, value) }
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
      // `"header": null` takes the whole header off — its caption, its labels and the room the
      // layout was keeping for them. It is not the same as a header with nothing in it.
      .filter { it.def.raw.fields["header"] != VegaValue.Null }
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
        band("row", "header", leading(vertical), titleOffset),
        band("row", "footer", vertical - leading(vertical).toSet(), titleOffset),
        band("column", "header", leading(horizontal), titleOffset),
        band(
          "column",
          "footer",
          horizontal - leading(horizontal).toSet(),
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
            val sorted = listOfNotNull(row, column).filter { it.cellSortAggregate != null }
            if (row != null && column != null || sorted.isNotEmpty()) {
              put(
                "aggregate",
                obj {
                  if (row != null && column != null) put("cross", VegaValue.Bool(true))
                  if (sorted.isNotEmpty()) {
                    put("fields", strings(sorted.map { it.sortSourceField() }))
                    put("ops", strings(sorted.map { it.sortOperation() }))
                    put("as", strings(sorted.map { it.cellSortAggregate!! }))
                  }
                },
              )
            }
          },
        )
      },
    )
    put(
      "sort",
      obj {
        put(
          "field",
          strings(listOfNotNull(row, column).map { "datum[${quoted(it.sortKey(inCell = true))}]" }),
        )
        put("order", strings(listOfNotNull(row, column).map { it.order }))
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

  override val defs: List<ChannelDef> = listOf(def)

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
          signalRef(headerText(def, field)),
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
