package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A table **two models name** is written out with a `format` block, whatever it comes to.
 *
 * `parseRoot` runs for the root and for every model that states its own `data`. Where it finds a
 * source already standing it merges the formats, and the assignment is unconditional:
 * ```js
 * const existingSource = findSource(model.data, sources);
 * if (existingSource) {
 *   if (!isGenerator(model.data)) {
 *     existingSource.data.format = mergeDeep({}, model.data.format, existingSource.data.format);
 *   }
 * ```
 *
 * `mergeDeep({}, undefined, undefined)` is `{}`, so two layers reading one table that says nothing
 * about its format leave an **empty** `format` behind where one layer leaves none at all. Vega
 * ignores it; a comparison against upstream does not, and 14 specifications in the wild corpus
 * differed on that key alone — five of them on nothing else.
 *
 * The merge reads `model.data.format`, which is the specification's own block rather than the
 * source node's stripped copy, so it also **reinstates a `parse`** the node had taken off an inline
 * table.
 *
 * A model that states no `data` never calls `parseRoot` at all, which is why a chart whose table
 * sits above its layers is not a shared one however many layers read it.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SharedSourceFormatTest {

  /** The `format` of each written-out table, by dataset name. */
  private fun formats(spec: String): Map<String, VegaValue?> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .filter { it.fields.containsKey("values") || it.fields.containsKey("url") }
      .associate { (it.string("name") ?: "?") to it.fields["format"] }
  }

  private val rows = """{"values":[{"a":1,"t":"2001-01-01"}]}"""
  private val point = """"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"}}"""
  private val line = """"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"}}"""

  /** The reported shape: two layers each naming the same rows. */
  @Test
  fun `a table two layers state is written with an empty format`() {
    assertEquals(
      mapOf("source_0" to VegaValue.EmptyObject),
      formats("""{"layer":[{"data":$rows,$point},{"data":$rows,$line}]}"""),
    )
  }

  /** One layer naming it leaves no format at all — the merge never runs. */
  @Test
  fun `a table one model states has no format`() {
    assertEquals(
      mapOf("source_0" to null, "source_1" to null),
      formats("""{"layer":[{"data":$rows,$point},{"data":{"values":[{"a":2}]},$line}]}"""),
    )
  }

  /**
   * And a table stated **above** the layers is named by one model however many layers read it: a
   * member with no `data` block reads its parent's flow rather than looking for a source.
   */
  @Test
  fun `a table above the layers has no format`() {
    assertEquals(
      mapOf("source_0" to null),
      formats("""{"data":$rows,"layer":[{$point},{$line}]}"""),
    )
  }

  /** A third model naming it changes nothing: the merge is idempotent once it has run. */
  @Test
  fun `a third layer leaves the same empty format`() {
    assertEquals(
      mapOf("source_0" to VegaValue.EmptyObject),
      formats("""{"layer":[{"data":$rows,$point},{"data":$rows,$line},{"data":$rows,$point}]}"""),
    )
  }

  /** A dataset named at the top level and read twice is the same rule. */
  @Test
  fun `a named table two layers read is written with an empty format`() {
    assertEquals(
      mapOf("d" to VegaValue.EmptyObject),
      formats(
        """{"datasets":{"d":[{"a":1}]},
           "layer":[{"data":{"name":"d"},$point},{"data":{"name":"d"},$line}]}"""
      ),
    )
  }

  /**
   * The merge reads the specification's own block, so a `parse` the source node had **stripped**
   * off an inline table comes back. An unshared inline table keeps none: Vega has already ingested
   * those rows, and the parse is a formula in the flow instead.
   */
  @Test
  fun `a parse stated on a shared inline table is reinstated`() {
    val withParse = """{"values":[{"a":1,"t":"2001-01-01"}],"format":{"parse":{"t":"date"}}}"""
    assertEquals(
      mapOf(
        "source_0" to
          VegaValue.Obj(
            linkedMapOf("parse" to VegaValue.Obj(linkedMapOf("t" to VegaValue.Str("date"))))
          )
      ),
      formats("""{"layer":[{"data":$withParse,$point},{"data":$withParse,$line}]}"""),
    )
    assertNull(
      formats("""{"data":$withParse,"layer":[{$point},{$line}]}""")["source_0"],
      "unshared, so the parse is a formula and the source has no format",
    )
  }

  /** A stated format survives the merge rather than being replaced by the empty one. */
  @Test
  fun `a stated format is kept`() {
    val typed = """{"values":[{"a":1}],"format":{"type":"json"}}"""
    assertEquals(
      mapOf("source_0" to VegaValue.Obj(linkedMapOf("type" to VegaValue.Str("json")))),
      formats("""{"layer":[{"data":$typed,$point},{"data":$typed,$line}]}"""),
    )
  }

  /**
   * A **`lookup`** reading the same table is not a second model naming it. `LookupNode.make` calls
   * the same `findSource` and only reuses what it finds — there is no merge on that path — so a
   * chart that joins against its own rows is written out with no format.
   */
  @Test
  fun `a lookup against the same table is not a second model`() {
    assertEquals(
      mapOf("source_0" to null),
      formats(
        """{"data":{"values":[{"a":1,"z":9}]},
           "transform":[{"lookup":"a",
                         "from":{"data":{"values":[{"a":1,"z":9}]},"key":"a","fields":["z"]}}],
           "mark":"point",
           "encoding":{"x":{"field":"a","type":"quantitative"},
                       "y":{"field":"z","type":"quantitative"}}}"""
      ),
    )
  }

  /**
   * A **url** already carries the type its extension implies, and the merge adds the parse to it.
   */
  @Test
  fun `a shared url keeps its derived type`() {
    val url = """{"url":"http://example.test/y.csv"}"""
    assertEquals(
      mapOf("source_0" to VegaValue.Obj(linkedMapOf("type" to VegaValue.Str("csv")))),
      formats("""{"layer":[{"data":$url,$point},{"data":$url,$line}]}"""),
    )
  }
}
