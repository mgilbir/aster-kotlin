package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * The data pipeline: a tree of nodes per view, flattened into Vega's `data` array.
 *
 * The tree, the walk that flattens it and the naming all follow `compile/data/assemble.ts`, and the
 * naming is the reason to follow it rather than invent something simpler. Whether a view's
 * transforms land on `source_0` or on a derived `data_0` is decided by the walk — inline data is
 * pushed as its own dataset and derived from, a URL is transformed in place — and every scale
 * domain and mark in the compiled specification refers to whichever name came out. A different name
 * is not a cosmetic difference; it is a specification that reads from the wrong dataset.
 */
internal sealed class DataNode {
  val children: MutableList<DataNode> = mutableListOf()

  fun then(child: DataNode): DataNode {
    children += child
    return child
  }
}

internal class SourceNode(val data: VegaValue, val name: String? = null) : DataNode() {
  val isUrl: Boolean = data.string("url") != null
  val isNamed: Boolean = data.string("name") != null && !isUrl && data["values"] == null
}

/**
 * Parses field values into the types the encoding claims they have.
 *
 * A temporal field arriving as text is the common case, and where the parse lands matters: on a
 * dataset read from a URL it becomes a `format.parse` entry, and on inline values — which Vega has
 * already ingested — it becomes a `toDate` formula instead.
 */
internal class ParseNode(val parse: Map<String, String>) : DataNode() {
  fun formatParse(): VegaValue.Obj = obj { parse.forEach { (field, type) -> put(field, type) } }

  fun transforms(): List<VegaValue> = parse.map { (field, type) ->
    obj {
      put("type", "formula")
      put(
        "expr",
        if (type == "date") "toDate(datum[${quoted(field)}])"
        else "to$type(datum[${quoted(field)}])",
      )
      put("as", field)
    }
  }
}

internal class BinNode(val bins: List<BinComponent>) : DataNode() {
  fun transforms(): List<VegaValue> = bins.flatMap { bin ->
    val transforms = mutableListOf<VegaValue>()
    // Without an explicit extent the data's own is measured first and passed by signal, which is
    // also what makes the bin boundaries available to the scale domain.
    if (bin.extent == null) {
      transforms += obj {
        put("type", "extent")
        put("field", bin.field)
        put("signal", bin.extentSignal)
      }
    }
    transforms += obj {
      put("type", "bin")
      put("field", bin.field)
      put("as", strings(bin.output))
      put("signal", bin.signal)
      put("extent", bin.extent ?: signalRef(bin.extentSignal))
      bin.params.fields.forEach { (key, value) -> if (key != "extent") put(key, value) }
    }
    if (bin.rangeFormula != null) {
      transforms += obj {
        put("type", "formula")
        put("expr", bin.rangeFormula)
        put("as", "${bin.output[0]}_range")
      }
    }
    transforms
  }
}

internal data class BinComponent(
  val field: String,
  val params: VegaValue.Obj,
  val output: List<String>,
  val signal: String,
  val extentSignal: String,
  val extent: VegaValue?,
  val rangeFormula: String?,
)

internal class TimeUnitNode(val units: List<TimeUnitComponent>) : DataNode() {
  fun transforms(): List<VegaValue> = units.map {
    obj {
      put("type", "timeunit")
      put("field", it.field)
      put("units", strings(it.units))
      put("as", strings(listOf(it.output, "${it.output}_end")))
    }
  }
}

internal data class TimeUnitComponent(
  val field: String,
  val units: List<String>,
  val output: String,
)

internal class AggregateNode(
  val dimensions: List<String>,
  val ops: List<String>,
  val fields: List<String?>,
  val outputs: List<String>,
) : DataNode() {
  fun transform(): VegaValue = obj {
    put("type", "aggregate")
    put("groupby", strings(dimensions))
    put("ops", strings(ops))
    put("fields", arr(fields.map { if (it == null) VegaValue.Null else str(it) }))
    put("as", strings(outputs))
  }
}

internal class StackNode(
  val field: String,
  val groupby: List<String>,
  val sortFields: List<String>,
  val sortOrders: List<String>,
  val output: List<String>,
  val offset: String,
  /**
   * The dimension fields a stacked *path* mark imputes over, empty for anything else.
   *
   * A stacked area has to have a value at every dimension position or the band above it steps into
   * the gap; a stacked bar simply has no bar there. So upstream fills the gaps with zero first, and
   * only for a path mark.
   */
  val imputeKeys: List<String> = emptyList(),
) : DataNode() {
  fun transforms(): List<VegaValue> =
    imputeKeys.map { key ->
      obj {
        put("type", "impute")
        put("field", field)
        put("groupby", strings(sortFields))
        put("key", key)
        put("method", "value")
        put("value", 0)
      }
    } + transform()

  private fun transform(): VegaValue = obj {
    put("type", "stack")
    put("groupby", strings(groupby))
    put("field", field)
    put(
      "sort",
      obj {
        put("field", strings(sortFields))
        put("order", strings(sortOrders))
      },
    )
    put("as", strings(output))
    put("offset", offset)
  }
}

/** Drops rows whose scaled fields are not finite numbers. See `compile/data/filterinvalid.ts`. */
internal class FilterInvalidNode(val expressions: List<String>) : DataNode() {
  fun transform(): VegaValue = obj {
    put("type", "filter")
    put("expr", expressions.joinToString(" && "))
  }
}

/** A transform written by the specification, already in Vega's form. */
internal class PassThroughNode(val transforms: List<VegaValue>) : DataNode()

/** A named point in the flow that something else reads: a mark's source, a scale's domain. */
internal class OutputNode(val key: String) : DataNode() {
  var source: String? = null
    private set

  fun setSource(name: String) {
    source = name
  }
}

/**
 * Flattens the tree into Vega datasets, assigning `source_N` and `data_N` on the way.
 *
 * This is `makeWalkTree` from upstream, kept structurally identical: the branching rules decide the
 * numbering, and the numbering is observable in the output.
 */
internal class DataAssembler {
  private val datasets = mutableListOf<MutableDataset>()
  private var sourceIndex = 0
  private var datasetIndex = 0

  private class MutableDataset(
    var name: String?,
    var source: String? = null,
    val values: VegaValue? = null,
    val url: String? = null,
    var format: VegaValue.Obj? = null,
    val transform: MutableList<VegaValue> = mutableListOf(),
  ) {
    fun build(): VegaValue.Obj = obj {
      put("name", name)
      put("url", url)
      put("values", values)
      put("format", format)
      put("source", source)
      if (transform.isNotEmpty()) put("transform", arr(transform))
    }
  }

  fun assemble(root: SourceNode): List<VegaValue> {
    val data = root.data
    val name = "source_${sourceIndex++}"
    val dataset =
      MutableDataset(
        name = name,
        values = data["values"],
        url = data.string("url"),
        format = sourceFormat(data),
      )
    walk(root, dataset)
    return datasets.map { it.build() }
  }

  private fun sourceFormat(data: VegaValue): VegaValue.Obj? {
    val declared = data.obj("format")
    val url = data.string("url") ?: return declared
    val type =
      declared.string("type")
        ?: when {
          url.endsWith(".csv") -> "csv"
          url.endsWith(".tsv") -> "tsv"
          url.endsWith(".json") -> "json"
          else -> "json"
        }
    return obj {
      put("type", type)
      declared?.fields?.forEach { (key, value) -> if (key != "type") put(key, value) }
    }
  }

  private fun walk(node: DataNode, incoming: MutableDataset) {
    var dataset = incoming

    if (node is SourceNode && !node.isUrl) {
      // Inline or named data becomes a dataset of its own so that Vega does not overwrite it; the
      // transforms then belong to a derived one.
      datasets += dataset
      dataset = MutableDataset(name = null, source = dataset.name)
    }

    when (node) {
      is ParseNode ->
        if (dataset.source == null) {
          val existing = dataset.format
          dataset.format = obj {
            putAll(existing)
            put("parse", node.formatParse())
          }
        } else {
          dataset.transform += node.transforms()
        }
      is BinNode -> dataset.transform += node.transforms()
      is TimeUnitNode -> dataset.transform += node.transforms()
      is AggregateNode -> dataset.transform += node.transform()
      is StackNode -> dataset.transform += node.transforms()
      is FilterInvalidNode -> dataset.transform += node.transform()
      is PassThroughNode -> dataset.transform += node.transforms
      is OutputNode -> {
        if (dataset.source != null && dataset.transform.isEmpty()) {
          node.setSource(dataset.source!!)
        } else {
          if (dataset.name == null) dataset.name = "data_${datasetIndex++}"
          node.setSource(dataset.name!!)
          if (node.children.size == 1) {
            datasets += dataset
            dataset = MutableDataset(name = null, source = dataset.name)
          }
        }
      }
      is SourceNode -> Unit
    }

    when (node.children.size) {
      0 ->
        if (node is OutputNode && (dataset.source == null || dataset.transform.isNotEmpty())) {
          datasets += dataset
        }
      1 -> walk(node.children[0], dataset)
      else -> {
        if (dataset.name == null) dataset.name = "data_${datasetIndex++}"
        var source = dataset.name
        if (dataset.source == null || dataset.transform.isNotEmpty()) {
          datasets += dataset
        } else {
          source = dataset.source
        }
        for (child in node.children) {
          walk(child, MutableDataset(name = null, source = source))
        }
      }
    }
  }
}
