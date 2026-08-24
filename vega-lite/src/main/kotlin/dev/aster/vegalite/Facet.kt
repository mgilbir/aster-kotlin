package dev.aster.vegalite

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.locale.VegaLocale

/**
 * A cell's caption: the value the cell holds, written the way that column is written elsewhere.
 *
 * `formatSignalRef` with `expr: "parent"`, the same rule a mark's text goes through. A *bucketed
 * instant* is spoken as a date with the specifier Vega picks at render time — the same one the axis
 * labels use, so a trellis of years is captioned "2005" and not "1104537600000".
 */
/**
 * A caption the header writes itself — `header.labelExpr`.
 *
 * The expression is the reader's, and it speaks in terms of the *cell*: `datum.value` is the column
 * the grid is broken down by and `datum.label` is what this compiler would otherwise have written.
 * Both are substituted rather than evaluated, exactly as `assembleLabelTitle` does, so a trellis of
 * hours can caption midnight "Midnight" and everything else by the clock.
 */
private fun headerLabel(def: ChannelDef, field: String, config: Config? = null): String {
  val derived = headerText(def, field, config)
  val stated = def.raw.obj("header")?.string("labelExpr") ?: return derived
  return stated.replace("datum.label", derived).replace("datum.value", "parent[${quoted(field)}]")
}

private fun headerText(def: ChannelDef, field: String, config: Config? = null): String {
  val accessor = "parent[${quoted(field)}]"
  // A **bucketed** column is captioned by the bucket rather than by its near edge: `binFormat`
  // writes both ends with an en dash between them, and says `"null"` where the row had no value —
  // an empty bucket is a real cell of the grid and has to be captioned as one.
  if (def.bin is Binning.Bin) {
    val end = "parent[${quoted("${field}_end")}]"
    // The same custom format type the guides use, where the configuration named one.
    val number = config?.numberFormatType?.let { "$it(" } ?: "format("
    val specifier = if (config?.numberFormatType != null) config.numberFormat.orEmpty() else ""
    return "!isValid($accessor) || !isFinite(+$accessor) ? \"null\" : " +
      "$number$accessor, \"$specifier\") + \" – \" + $number$end, \"$specifier\")"
  }
  val timeUnit = def.timeUnit
  if (def.type == MeasureType.TEMPORAL || timeUnit != null) {
    val utc = timeUnit?.contains("utc") == true || def.scale.string("type") == "utc"
    val prefix = if (utc) "utc" else "time"
    // One locale for both branches. The bucketed branch has threaded it since the locale seam
    // landed, and the plain one carried upstream's date hardcoded three lines below it —
    // defensible as parity, since upstream has no locale to ask, but it meant a grid split by a
    // bucketed field followed the reader's language and a grid split by a plain date did not.
    val locale = config?.locale ?: VegaLocale.EnglishUS
    val specifier =
      if (timeUnit != null) Fields.timeUnitSpecifier(timeUnit, locale)
      else Fields.fullDateSpecifier(locale)
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
internal class Facet(
  val channel: String,
  val def: ChannelDef,
  /** The chart's own name, where it has one: every dataset it makes is named under it. */
  private val prefix: String = "",
) {

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

  /**
   * The columns a cell is grouped by, which for a **bucketed** facet is two.
   *
   * A bucket is an interval and both ends identify it: grouping by the near edge alone would merge
   * two buckets that happen to start together, and the caption reads the far edge as well.
   */
  val groupingFields: List<String> =
    if (def.bin is Binning.Bin) listOf(field, "${field}_end") else listOf(field)

  /** The `sort` object this channel orders its cells by, where it names one. */
  private val sortObject: VegaValue.Obj? = (def.sort as? VegaValue.Obj)?.takeIf { it.has("field") }

  /** `DEFAULT_SORT_OP` is `min`, not the `sum` a reader might expect from the encoding sorts. */
  private val sortOp: String? = sortObject?.let { it.string("op") ?: "min" }

  private val sortSource: String? = sortObject?.string("field")

  /**
   * `sortArrayIndexField`: the column a facet sorted by a **list** is really ordered by.
   *
   * The list is a stated sequence, and a cell's place in it cannot be read off the column being
   * faceted on. So the place is computed onto every row first — a chain of equalities written by
   * `parseAllForSortIndex` — and the grid then orders itself by the greatest of each cell's, which
   * is the only one each cell has.
   */
  private val sortIndex: String? =
    (def.sort as? VegaValue.Arr)?.let { Fields.sortIndexField(channel, def, forAs = true) }

  /**
   * The column each cell's key is measured from, and the operation that measures it.
   *
   * A grid **crossed** both ways cannot compute the key here: the aggregate groups by every facet
   * field at once, and this key is grouped by one of them. So a joinaggregate writes it onto every
   * row first and the cell takes the *greatest* of its own, every row of a cell carrying the same
   * number. `assembleFacet`: "apply max and assign them to the same name". A list-sorted facet is
   * keyed the same way for the same reason, crossed or not.
   */
  fun cellSortSource(crossed: Boolean): String =
    if (crossed && sortSource != null) cellSortAggregate!! else sortSource ?: sortIndex!!

  fun cellSortOperation(crossed: Boolean): String =
    if (crossed && sortSource != null) "max" else sortOp ?: "max"

  /** The column the *domain* dataset measures the key from, which is never pre-computed. */
  fun sortSourceField(): String = sortSource ?: sortIndex!!

  fun sortOperation(): String = sortOp ?: "max"

  /**
   * What the *domain* dataset calls the aggregate the cells are ordered by — `sum_amount`.
   *
   * `vgField(sortField, {forAs: true})`: the plain aggregate name, which is what the header bands
   * read, since they are drawn from that dataset and each of its rows is already one cell's worth.
   */
  // Named for the operation only where the specification **stated** one. `vgField(sortField,
  // {forAs: true})` reads the definition as written, and a sort that names a column and no
  // operation names a column: the aggregate is still a `min`, since that is the default the data
  // is computed with, but the column it lands in is called what the reader called it.
  val sortAggregate: String? = sortObject?.let {
    if (it.string("op") != null) "${sortOp}_$sortSource" else sortSource
  }

  /**
   * What the *cell* group calls the same aggregate — `sum_amount_by_era`.
   *
   * `facetSortFieldName` suffixes it with the field being faceted on, because the facet computes it
   * a second time over its own partition and the two names must not collide.
   */
  val cellSortAggregate: String? = sortAggregate?.let { "${it}_by_$field" } ?: sortIndex

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
  fun sortKey(inCell: Boolean): String =
    (if (inCell) cellSortAggregate else sortAggregate ?: sortIndex) ?: field

  fun reportUnsupportedSort(
    diagnostics: dev.aster.vega.model.DiagnosticCollector,
    crossed: Boolean,
  ) {
    val sort = def.sort ?: return
    if (sort is VegaValue.Str || sort == VegaValue.Null) return
    // A stated list is honoured: its place is computed onto every row as a column of its own, and
    // the grid orders itself by the greatest of each cell's.
    if (sort is VegaValue.Arr) return
    val reason =
      when {
        sortObject == null -> "names no `field` to aggregate"
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
  val domainData: String =
    listOf(prefix, "${channel}_domain").filter { it.isNotEmpty() }.joinToString("_")

  fun domainDataset(
    source: String,
    counted: Map<String, String> = emptyMap(),
    /** Whether [source] is the **crossed** table, whose rows already hold one count per cell. */
    fromCrossed: Boolean = false,
  ): VegaValue = obj {
    put("name", domainData)
    put("source", source)
    put(
      "transform",
      arr(
        obj {
          put("type", "aggregate")
          put("groupby", strings(groupingFields))
          val fields = mutableListOf<String>()
          val ops = mutableListOf<String>()
          val names = mutableListOf<String>()
          // A cell that sizes itself counts its own categories here as well: the header band beside
          // it is as wide as the cell, and it is drawn from this dataset rather than from the
          // facet's own partition. Only the count along **this** band's direction, though — a row
          // band is as tall as a cell and knows nothing of how wide one is, which is
          // `assembleRowColumnHeaderData` reading `{row: 'y', column: 'x'}[channel]`.
          counted[if (isColumn) "x" else "y"]?.let { name ->
            // Over the crossed table the counting is already done, one row per cell, so the band
            // takes the **greatest** of what its cells carry rather than counting again over rows
            // it no longer has. "Although it is technically a max, just name it distinct so it's
            // easier to refer to it."
            fields += if (fromCrossed) name else name.removePrefix("distinct_")
            ops += if (fromCrossed) "max" else "distinct"
            names += name
          }
          // The key the cells are ordered by, measured once per cell — which is what this dataset
          // already holds a row of. `assembleRowColumnHeaderData` puts it here rather than leaving
          // the header bands to sort on something they cannot see.
          if (sortAggregate != null) {
            fields += sortSource!!
            ops += sortOp!!
            names += sortAggregate
          } else if (sortIndex != null) {
            fields += sortIndex
            ops += "max"
            names += sortIndex
          }
          if (fields.isNotEmpty()) {
            put("fields", strings(fields))
            put("ops", strings(ops))
            put("as", strings(names))
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

  /**
   * `getName`: the chart's own name in front of the grid's structural names, where it has one.
   *
   * A facet's cell, its partition, and its header bands are named through the model exactly as its
   * scales and signals are, so a chart calling itself `trellis` reads `trellis_cell` holding
   * `trellis_facet` between `trellis_row_header` and `trellis_column_footer`. Only the headings
   * stand outside it — `assembleTitleGroup` names them for the channel alone.
   */
  fun named(suffix: String): String

  /** The columns each cell is grouped by, which every grouping in the data flow has to carry. */
  val fields: List<String>

  /**
   * Each facet channel and the column it breaks the chart down by, in `FACET_CHANNELS` order.
   *
   * `unitName` reads them: a cell of a trellis is not named by the grid but by the *values* it
   * holds, so a tuple picked in it records `"child" + '__facet_column_' + (facet["Series"])`.
   */
  val byChannel: List<Pair<String, String>>

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
  fun domainDatasets(
    source: String,
    vertical: Boolean,
    horizontal: Boolean,
    counted: Map<String, String> = emptyMap(),
  ): List<VegaValue>

  fun layout(
    /** The gap between cells: one number, or a `{row, column}` pair where the two differ. */
    spacing: VegaValue,
    titleOffset: Double,
    config: Config,
    /** The position channels the cells resolve **independently**, which cannot be aligned. */
    independent: Set<String> = emptySet(),
    /** The headings this level writes, where the nest folded them together. See [groups]. */
    headings: Map<String, String>? = null,
    /**
     * Whether what this level lays out **has a size of its own**.
     *
     * `if (!this.child.component.layoutSize.get(sizeType))` — a band whose child cannot say how
     * tall it is takes `headerBand: 0.5` and is centred on what it names instead. A cell is such a
     * child; another grid is not, and that is the only place this is false.
     */
    childHasSize: Boolean = true,
    /**
     * Whether this grid is **itself a cell** of a grid above it.
     *
     * `columnDistinctSignal` answers nothing then — "for nested facet, we will add columns to group
     * mark instead". A column count read off a domain dataset would be the whole chart's count, and
     * what such a grid needs is its own cell's, which the group carries as a field.
     */
    insideFacet: Boolean = false,
  ): VegaValue

  /**
   * The heading this level would write over the grid, by channel.
   *
   * Exposed because a grid whose cells are grids writes **one** heading for a channel, not one per
   * level: `parseFacetHeader` folds the child's into its own — "Origin / Cylinders" — and nulls the
   * child's. So the levels' own headings are collected and the outermost draws the result.
   */
  fun headings(config: Config): Map<String, String>

  /** The heading over the grid and the bands of shared axes around it, in upstream's order. */
  fun groups(
    vertical: List<VegaValue>,
    horizontal: List<VegaValue>,
    titleOffset: Double,
    config: Config,
    /**
     * What a cell's width and height are called, or the expressions they are.
     *
     * Empty where there is none to state, which is a level whose child is another grid: the band
     * keeps room for a caption and the grid below sizes its own cells.
     */
    columnSize: String = "child_width",
    rowSize: String = "child_height",
    /**
     * The headings to write instead of this level's own, where the levels were folded together.
     *
     * Null asks for its own. Empty says it has none to write — the level above absorbed them.
     */
    headings: Map<String, String>? = null,
  ): List<VegaValue>

  /**
   * A cell that is **itself a grid** — the group an outer level draws around the level below it.
   *
   * Nothing about a plotting area belongs here, and that is the whole distinction from [cellGroup]:
   * no style, no size, no axes, no scales. An intermediate level partitions the table, hands the
   * level below its own facet values to break that partition down further, and arranges what that
   * level produced. The cell actually drawn in is the innermost one.
   */
  fun nestedCellGroup(
    dataName: String,
    counted: Map<String, String>,
    /** How the level below arranges its own cells, which it does *inside* this group. */
    innerLayout: VegaValue,
    /** The level below's own facet values, computed from this partition rather than the table. */
    innerData: List<VegaValue>,
    innerMarks: List<VegaValue>,
    /**
     * The scales the level below builds **inside** this cell.
     *
     * Empty unless the cell holds plots: a concatenation's plots keep their own position scales,
     * and measured per cell they have to be built where the partition is the data they can see.
     */
    innerScales: List<VegaValue> = emptyList(),
    /**
     * The column the level below counts its own cells by — `distinct_Cylinders`.
     *
     * Null unless that level breaks the chart down by **column**: rows stack however many there
     * are, where columns have to be counted, and counted *per cell* rather than over the chart.
     */
    innerColumns: String?,
  ): VegaValue

  fun cellGroup(
    dataName: String,
    marks: List<VegaValue>,
    axes: List<VegaValue>,
    widthSignal: String,
    heightSignal: String,
    titleOffset: Double,
    /** `cell` or `view`, by the same rule the chart's own group follows. */
    style: String,
    /**
     * The columns each cell counts its own categories in, where it sizes itself.
     *
     * `getCardinalityAggregateForChild`: a cell whose scale is its own is as wide as *its* rows
     * need, so the facet counts them per cell and the group's width reads that count back.
     */
    counted: Map<String, String>,
    /**
     * The scales a cell owns rather than shares — a facet resolves `theta` independently.
     *
     * They belong *inside* the group: a cell's own extent is measured over the rows the facet
     * handed it, so the scale has to be built where `facet` is the data it can see.
     */
    scales: List<VegaValue>,
    /**
     * What a `view` block says about the plotting area, which in a trellis is the **cell**.
     *
     * The block is written on the spec inside the facet — the one describing a single cell — and a
     * trellis has a plotting area per cell. Applied to the chart's own group instead, a `"stroke":
     * null` meant to take the border off every cell took it off nothing that was drawn.
     */
    viewEncode: VegaValue? = null,
    /**
     * The datasets a **cell** computes for itself, where the flow splits at the facet.
     *
     * Empty in the ordinary case: the cell's chain is hoisted above the facet and every cell reads
     * the partition Vega hands it.
     */
    cellData: List<VegaValue> = emptyList(),
    /** The sizes the cell's own axes fall back to by name, aliased to the cell's own. */
    cellSignals: List<VegaValue> = emptyList(),
  ): VegaValue
}

internal class FacetGrid(val row: Facet?, val column: Facet?, private val prefix: String = "") :
  FacetLayout {

  override fun named(suffix: String): String =
    listOf(prefix, suffix).filter { it.isNotEmpty() }.joinToString("_")

  /** Row before column, which is the order upstream groups, sorts and crosses by. */
  override val fields: List<String> = listOfNotNull(row, column).flatMap { it.groupingFields }

  override val byChannel: List<Pair<String, String>> =
    listOfNotNull(row, column).map { it.channel to Fields.vgField(it.def) }

  override val defs: List<ChannelDef> = listOfNotNull(row?.def, column?.def)

  /**
   * Column before row: `compile/data/facet.ts` assembles `for (const channel of [COLUMN, ROW])`.
   */
  override fun domainDatasets(
    source: String,
    vertical: Boolean,
    horizontal: Boolean,
    counted: Map<String, String>,
  ): List<VegaValue> {
    // A grid faceted **both** ways whose cells size themselves cannot count from the whole table:
    // a column's width is the widest of its cells and each cell is one row-value and one
    // column-value together, so the counting is done once per cell first — grouped by every facet
    // field — and each band then takes the greatest of its own. `assemble` calls it
    // `cross_<column>_<row>`.
    val crossed =
      if (column != null && row != null && counted.isNotEmpty())
        "cross_${column.domainData}_${row.domainData}"
      else null
    val crossing = crossed?.let { name ->
      obj {
        put("name", name)
        put("source", source)
        put(
          "transform",
          arr(
            obj {
              put("type", "aggregate")
              put("groupby", strings(fields))
              put("fields", strings(counted.values.map { it.removePrefix("distinct_") }))
              put("ops", strings(counted.values.map { "distinct" }))
            }
          ),
        )
      }
    }
    return listOfNotNull(crossing) +
      listOfNotNull(column, row).map {
        it.domainDataset(crossed ?: source, counted, fromCrossed = crossed != null)
      }
  }

  /**
   * The `layout` block.
   *
   * `bounds: "full"` and `align: "all"` are what keep the cells the same size as each other rather
   * than each shrinking to its own content — without them a trellis's columns drift apart wherever
   * one cell's axis labels are wider than another's. `columns` counts the column facet's own values
   * and is 1 when a chart is faceted by rows alone.
   */
  override fun layout(
    spacing: VegaValue,
    titleOffset: Double,
    config: Config,
    independent: Set<String>,
    headings: Map<String, String>?,
    childHasSize: Boolean,
    insideFacet: Boolean,
  ): VegaValue = obj {
    val titled = (headings ?: headings(config)).keys
    put("padding", spacing)
    // `if (!this.child.component.layoutSize.get(sizeType))`: a band naming another **grid** cannot
    // be as tall as what it names, there being no one cell to be as tall as, so it is centred on
    // it.
    if (!childHasSize) {
      val banded = listOfNotNull(row, column).map { it.channel }
      if (banded.isNotEmpty()) {
        put("headerBand", obj { banded.forEach { put(it, num(0.5)) } })
      }
    }
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
      // A **nested** column grid counts its columns per cell, off the group's own field, so there
      // is nothing to say here; the chart's own count would be every cell's columns at once.
      column != null ->
        if (!insideFacet) put("columns", signalRef("length(data('${column.domainData}'))"))
      row != null -> put("columns", num(1))
    }
    put("bounds", "full")
    // Cells whose scale along a direction is each their own cannot be **aligned** along it: their
    // plotting areas are different widths, and lining them up would be lining up nothing. A grid
    // faceted both ways is aligned regardless, since every cell then shares a row and a column.
    val unalignable = (row == null && "x" in independent) || (column == null && "y" in independent)
    put("align", if (unalignable) "none" else "all")
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
    config: Config,
    columnSize: String = "child_width",
    rowSize: String = "child_height",
  ): VegaValue? {
    val isColumn = channel == "column"
    val facet = if (isColumn) column else row
    // `"header": null` takes the *caption* off, not the band: the band is also where a shared axis
    // is drawn, and that axis is still wanted. A band with neither is the one that disappears.
    val wanted = if (facet?.captionsInFooter() == true) kind == "footer" else kind == "header"
    val captions = wanted && facet != null && facet.def.raw.fields["header"] != VegaValue.Null
    if (axes.isEmpty() && !captions) return null
    return obj {
      put("name", named("${channel}_$kind"))
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
                signalRef(headerLabel(facet.def, facet.field, config)),
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
      // `makeHeaderComponent` states the size only where the child *has* one —
      // `child.component.layoutSize.get(sizeType)`. A level whose child is another grid has none to
      // state: the grid below sizes its own cells, and this band is only keeping room for a
      // caption.
      val size = if (isColumn) columnSize else rowSize
      if (size.isNotEmpty()) {
        put(
          "encode",
          obj { put("update", obj { put(if (isColumn) "width" else "height", signalRef(size)) }) },
        )
      }
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
  override fun headings(config: Config): Map<String, String> =
    listOfNotNull(row, column)
      // `"header": null` takes the whole header off — its caption, its labels and the room the
      // layout was keeping for them. It is not the same as a header with nothing in it.
      .filter { it.def.raw.fields["header"] != VegaValue.Null }
      .mapNotNull { facet ->
        // The **header's** own title where it states one, and the column's derived name where it
        // does not. A heading the specification emptied is no heading at all — `assembleTitleGroup`
        // writes nothing for a falsy title — and the room the layout was keeping for it goes with
        // it. That is not the same as leaving the heading out: the band of captions stays either
        // way, since the captions are what name the cells.
        val stated = facet.def.raw.obj("header")?.fields?.get("title")
        val text =
          if (stated != null) (stated as? VegaValue.Str)?.value
          else (Fields.title(facet.def, config) as? VegaValue.Str)?.value
        text?.takeIf { it.isNotEmpty() }?.let { facet.channel to it }
      }
      .toMap()

  override fun groups(
    vertical: List<VegaValue>,
    horizontal: List<VegaValue>,
    titleOffset: Double,
    config: Config,
    columnSize: String,
    rowSize: String,
    headings: Map<String, String>?,
  ): List<VegaValue> {
    fun leading(axes: List<VegaValue>) = axes.filter {
      it.string("orient") == "left" || it.string("orient") == "top"
    }
    // Both headings first, rows before columns, then the four bands of labels around the grid.
    val titles = headings ?: headings(config)
    return listOfNotNull(row, column).mapNotNull { facet ->
      titles[facet.channel]?.let { facet.titleGroup(it, titleOffset) }
    } +
      listOfNotNull(
        band("row", "header", leading(vertical), titleOffset, config, columnSize, rowSize),
        band(
          "row",
          "footer",
          vertical - leading(vertical).toSet(),
          titleOffset,
          config,
          columnSize,
          rowSize,
        ),
        band("column", "header", leading(horizontal), titleOffset, config, columnSize, rowSize),
        band(
          "column",
          "footer",
          horizontal - leading(horizontal).toSet(),
          titleOffset,
          config,
          columnSize,
          rowSize,
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
  /**
   * `from.facet` — the partition Vega cuts the table into, one group per cell.
   *
   * Shared by the cell a grid draws and by the cell a grid draws when its cells are *themselves*
   * grids: the partition is the same either way, being a property of this level's own channels.
   */
  private fun partition(dataName: String, counted: Map<String, String>): VegaValue = obj {
    put(
      "facet",
      obj {
        put("name", named("facet"))
        put("data", dataName)
        put("groupby", strings(fields))
        val sorted = listOfNotNull(row, column).filter { it.cellSortAggregate != null }
        val cardinal = counted.entries.toList()
        val crossed = row != null && column != null
        if (row != null && column != null || sorted.isNotEmpty() || cardinal.isNotEmpty()) {
          put(
            "aggregate",
            obj {
              if (row != null && column != null) put("cross", VegaValue.Bool(true))
              if (sorted.isNotEmpty() || cardinal.isNotEmpty()) {
                put(
                  "fields",
                  strings(
                    sorted.map { it.cellSortSource(crossed) } +
                      cardinal.map { it.value.removePrefix("distinct_") }
                  ),
                )
                put(
                  "ops",
                  strings(
                    sorted.map { it.cellSortOperation(crossed) } + cardinal.map { "distinct" }
                  ),
                )
                put(
                  "as",
                  strings(sorted.map { it.cellSortAggregate!! } + cardinal.map { it.value }),
                )
              }
            },
          )
        }
      },
    )
  }

  /** The order the cells are laid out in, which is the order their values sort in. */
  private fun cellSort(): VegaValue = obj {
    put(
      "field",
      strings(listOfNotNull(row, column).map { "datum[${quoted(it.sortKey(inCell = true))}]" }),
    )
    put("order", strings(listOfNotNull(row, column).map { it.order }))
  }

  override fun nestedCellGroup(
    dataName: String,
    counted: Map<String, String>,
    innerLayout: VegaValue,
    innerData: List<VegaValue>,
    innerMarks: List<VegaValue>,
    innerScales: List<VegaValue>,
    innerColumns: String?,
  ): VegaValue = obj {
    put("name", named("cell"))
    put("type", "group")
    put("from", partition(dataName, counted))
    put("sort", cellSort())
    // How many columns the grid inside this cell is wide, as a **field of this cell's own row** —
    // upstream's note points at vega/vega#952: a nested grid's column count is per cell, and the
    // partition counted it for exactly this.
    innerColumns?.let {
      put("encode", obj { put("update", obj { put("columns", obj { put("field", it) }) }) })
    }
    if (innerData.isNotEmpty()) put("data", arr(innerData))
    put("layout", innerLayout)
    put("marks", arr(innerMarks))
    if (innerScales.isNotEmpty()) put("scales", arr(innerScales))
  }

  override fun cellGroup(
    dataName: String,
    marks: List<VegaValue>,
    axes: List<VegaValue>,
    widthSignal: String,
    heightSignal: String,
    titleOffset: Double,
    style: String,
    counted: Map<String, String>,
    scales: List<VegaValue>,
    viewEncode: VegaValue?,
    cellData: List<VegaValue>,
    cellSignals: List<VegaValue>,
  ): VegaValue = obj {
    put("name", named("cell"))
    put("type", "group")
    put("style", style)
    put("from", partition(dataName, counted))
    put("sort", cellSort())
    put(
      "encode",
      obj {
        put(
          "update",
          obj {
            put("width", signalRef(widthSignal))
            put("height", signalRef(heightSignal))
            (viewEncode?.get("update") as? VegaValue.Obj)?.fields?.forEach { (key, value) ->
              put(key, value)
            }
          },
        )
      },
    )
    if (cellSignals.isNotEmpty()) put("signals", arr(cellSignals))
    if (cellData.isNotEmpty()) put("data", arr(cellData))
    put("marks", arr(marks))
    if (axes.isNotEmpty()) put("axes", arr(axes))
    if (scales.isNotEmpty()) put("scales", arr(scales))
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
/*
 * A wrapped facet has no heading over the grid — its cells caption themselves — so [headings] is
 * empty and there is nothing for a level above to fold in.
 */
internal class FacetWrap(
  val def: ChannelDef,
  private val columns: Int?,
  private val prefix: String = "",
  /** Kept because a wrapped facet captions its **cells**, and a caption reads the number format. */
  private val config: Config? = null,
) : FacetLayout {

  override fun named(suffix: String): String =
    listOf(prefix, suffix).filter { it.isNotEmpty() }.joinToString("_")

  /** The column the cells are ordered by, the operation over it, and what it is written as. */
  private fun sortField(): Triple<String, String, String>? {
    val sort = (def.sort as? VegaValue.Obj)?.takeIf { it.has("field") } ?: return null
    val source = sort.string("field") ?: return null
    val op = sort.string("op") ?: "min"
    return Triple(source, op, if (sort.string("op") != null) "${op}_$source" else source)
  }

  private val field: String = Fields.vgField(def)

  /** `header.label…` as a text property: the caption on each cell is a header's label. */
  private fun labelProperties(): Map<String, VegaValue> {
    val header = def.raw.obj("header") ?: return emptyMap()
    val renamed =
      mapOf(
        "labelAlign" to "align",
        "labelAnchor" to "anchor",
        "labelAngle" to "angle",
        "labelBaseline" to "baseline",
        "labelColor" to "color",
        "labelFont" to "font",
        "labelFontSize" to "fontSize",
        "labelFontStyle" to "fontStyle",
        "labelFontWeight" to "fontWeight",
        "labelLimit" to "limit",
        "labelLineHeight" to "lineHeight",
      )
    val out = LinkedHashMap<String, VegaValue>()
    for ((stated, name) in renamed) header.fields[stated]?.let { out[name] = it }
    return out
  }

  private val domainData: String = named("facet_domain")

  override val fields: List<String> =
    if (def.bin is Binning.Bin) listOf(field, "${field}_end") else listOf(field)

  override val byChannel: List<Pair<String, String>> = listOf("facet" to field)

  override val defs: List<ChannelDef> = listOf(def)

  override fun domainDatasets(
    source: String,
    vertical: Boolean,
    horizontal: Boolean,
    counted: Map<String, String>,
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
              // Both edges where the column is **bucketed**: a bucket is the pair, and grouping by
              // its near edge alone leaves the far one off every row the headers are captioned
              // from. `FacetNode`'s fields are the same two.
              put("groupby", strings(fields))
              // The key the cells are ordered by, measured once per cell — which is what this
              // dataset already holds a row of, as it is for a grid.
              sortField()?.let { (source, op, name) ->
                put("fields", strings(listOf(source)))
                put("ops", strings(listOf(op)))
                put("as", strings(listOf(name)))
              }
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

  override fun layout(
    spacing: VegaValue,
    titleOffset: Double,
    config: Config,
    independent: Set<String>,
    headings: Map<String, String>?,
    childHasSize: Boolean,
    insideFacet: Boolean,
  ): VegaValue = obj {
    put("padding", spacing)
    put("bounds", "full")
    put("align", "all")
    // Only where the specification said so: with no `columns`, the whole facet is one row and the
    // layout has no number to write down.
    columns?.let { put("columns", num(it.toDouble())) }
  }

  override fun headings(config: Config): Map<String, String> = emptyMap()

  override fun groups(
    vertical: List<VegaValue>,
    horizontal: List<VegaValue>,
    titleOffset: Double,
    config: Config,
    columnSize: String,
    rowSize: String,
    headings: Map<String, String>?,
  ): List<VegaValue> {
    fun leading(axes: List<VegaValue>) = axes.filter {
      it.string("orient") == "left" || it.string("orient") == "top"
    }
    // A header that states `"title": null` has no heading over the grid at all: the cells name
    // themselves, and a caption above them naming the column would say it twice.
    val titled = def.raw.obj("header")?.fields?.get("title") != VegaValue.Null
    val heading =
      (Fields.title(def, config) as? VegaValue.Str)
        ?.takeIf { titled }
        ?.let { title ->
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
      band("row", "header", leading(vertical), columnSize, rowSize),
      band("row", "footer", vertical - leading(vertical).toSet(), columnSize, rowSize),
      band("column", "header", leading(horizontal), columnSize, rowSize),
      band("column", "footer", horizontal - leading(horizontal).toSet(), columnSize, rowSize),
    )
  }

  /**
   * A band of shared axes, drawn once per *position* in the grid rather than once per facet value.
   *
   * A wrapped facet's rows and columns are places, so the band reads the counting sequence and has
   * nothing to caption itself with — which is why the caption is on the cell instead.
   */
  private fun band(
    channel: String,
    kind: String,
    axes: List<VegaValue>,
    columnSize: String = "child_width",
    rowSize: String = "child_height",
  ): VegaValue? {
    if (axes.isEmpty()) return null
    val isColumn = channel == "column"
    return obj {
      put("name", named("${channel}_$kind"))
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
                signalRef(if (isColumn) columnSize else rowSize),
              )
            },
          )
        },
      )
      put("axes", arr(axes))
    }
  }

  /**
   * `from.facet` — the partition Vega cuts the table into, one group per cell.
   *
   * Shared with [nestedCellGroup], the partition being a property of this level's own channel
   * whether the cell it hands over is drawn in or is another grid.
   */
  /** The order the cells are laid out in, which is the order their values sort in. */
  private fun cellSort(): VegaValue = obj {
    // The **near** edge alone orders a bucketed grid: a bucket's far edge follows from its near
    // one, so sorting on both says the same thing twice. `facetSortFields` answers with one name:
    // the key a stated `sort` had the cell measure, and the facet's own column otherwise.
    val key = sortField()?.let { (_, _, name) -> "${name}_by_$field" } ?: field
    put("field", strings(listOf("datum[${quoted(key)}]")))
    // `facetSortOrder`: a `sort` object says which way in its `order`, a bare `"descending"` says
    // it by itself, and anything else runs up.
    val order =
      (def.sort as? VegaValue.Obj)?.string("order")
        ?: (def.sort as? VegaValue.Str)?.value?.takeIf { it == "descending" }
        ?: "ascending"
    put("order", strings(listOf(order)))
  }

  private fun partition(dataName: String): VegaValue = obj {
    put(
      "facet",
      obj {
        put("name", named("facet"))
        put("data", dataName)
        put("groupby", strings(fields))
        // The key each cell is ordered by, measured over the cell's own rows and suffixed with the
        // faceted column so it cannot collide with the one the domain dataset holds.
        sortField()?.let { (source, op, name) ->
          put(
            "aggregate",
            obj {
              put("fields", strings(listOf(source)))
              put("ops", strings(listOf(op)))
              put("as", strings(listOf("${name}_by_$field")))
            },
          )
        }
      },
    )
  }

  override fun nestedCellGroup(
    dataName: String,
    counted: Map<String, String>,
    innerLayout: VegaValue,
    innerData: List<VegaValue>,
    innerMarks: List<VegaValue>,
    innerScales: List<VegaValue>,
    innerColumns: String?,
  ): VegaValue = obj {
    put("name", named("cell"))
    put("type", "group")
    put("from", partition(dataName))
    put("sort", cellSort())
    innerColumns?.let {
      put("encode", obj { put("update", obj { put("columns", obj { put("field", it) }) }) })
    }
    if (innerData.isNotEmpty()) put("data", arr(innerData))
    put("layout", innerLayout)
    put("marks", arr(innerMarks))
    if (innerScales.isNotEmpty()) put("scales", arr(innerScales))
  }

  override fun cellGroup(
    dataName: String,
    marks: List<VegaValue>,
    axes: List<VegaValue>,
    widthSignal: String,
    heightSignal: String,
    titleOffset: Double,
    style: String,
    counted: Map<String, String>,
    scales: List<VegaValue>,
    viewEncode: VegaValue?,
    cellData: List<VegaValue>,
    cellSignals: List<VegaValue>,
  ): VegaValue = obj {
    put("name", named("cell"))
    put("type", "group")
    // Each cell names the value it holds, there being no band of column headings to name it in.
    put(
      "title",
      obj {
        put(
          "text",
          signalRef(headerLabel(def, field, config)),
        )
        put("style", "guide-label")
        put("frame", "group")
        // A wrapped facet captions its **cells**, so the header's *label* properties belong on the
        // cell's own title — a grid captions its bands with them instead.
        labelProperties().forEach { (key, value) -> put(key, value) }
        put("offset", num(titleOffset))
      },
    )
    put("style", style)
    put("from", partition(dataName))
    put("sort", cellSort())
    put(
      "encode",
      obj {
        put(
          "update",
          obj {
            put("width", signalRef(widthSignal))
            put("height", signalRef(heightSignal))
            (viewEncode?.get("update") as? VegaValue.Obj)?.fields?.forEach { (key, value) ->
              put(key, value)
            }
          },
        )
      },
    )
    if (cellSignals.isNotEmpty()) put("signals", arr(cellSignals))
    if (cellData.isNotEmpty()) put("data", arr(cellData))
    put("marks", arr(marks))
    if (axes.isNotEmpty()) put("axes", arr(axes))
    if (scales.isNotEmpty()) put("scales", arr(scales))
  }
}
