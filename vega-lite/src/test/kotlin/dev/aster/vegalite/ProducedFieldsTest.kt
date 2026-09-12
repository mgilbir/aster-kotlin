package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A transform that was not told what to call its outputs still writes columns.
 *
 * A parse cannot climb past a step that produces what it reads — `MoveParseUp` stops where
 * `fieldIntersection(node.producedFields(), child.dependentFields())` — and the intersection is
 * taken over **path prefixes**, so a step producing `properties` blocks a parse of
 * `properties.name`. This engine asked each step only for its `as`, so a `lookup` that brings the
 * secondary table's columns in under their own names looked like a step that writes nothing: the
 * flatten formula climbed above the lookup and read a column the source table has never had. Seven
 * specifications in the wild corpus join a table that way and then name a path inside what it
 * brought in — a world map looking up a country's shape and captioning it by `properties.name`.
 *
 * The names a transform gives itself are upstream's, one per node class: a lookup's are the
 * secondary table's `values`, a fold's are `key` and `value`, a density's are `value` and
 * `density`.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ProducedFieldsTest {

  /** Each dataset as `name|format|transform types`, which is where a parse shows up. */
  private fun flow(spec: String): List<String> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .map { set ->
        val transforms =
          (set.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().map { it as VegaValue.Obj }
        "${set.string("name")}|" +
          VegaJson.write(set.fields["format"] ?: VegaValue.Null).replace(Regex("""\s+"""), "") +
          "|" +
          transforms.joinToString(",") {
            it.string("type") + (it.string("as")?.let { name -> ":$name" } ?: "")
          }
      }
  }

  private val url = """{"url":"http://example.test/x.csv"}"""
  private val secondary = """{"url":"http://example.test/y.csv"}"""
  private val tooltip = """"tooltip":[{"field":"properties.name","type":"nominal"}]"""

  private fun chart(transform: String, encoded: String = tooltip) =
    """{"data":$url,"transform":[$transform],"mark":"point",
       "encoding":{"x":{"field":"a","type":"quantitative"},$encoded}}"""

  /** The reported shape: the column is read *after* the step that brings it in. */
  @Test
  fun `a flatten of what a lookup brings in stays below the lookup`() {
    assertEquals(
      listOf(
        """source_1|{"type":"csv"}|""",
        """source_0|{"type":"csv"}|lookup,formula:properties.name,filter""",
      ),
      flow(
        chart("""{"lookup":"id","from":{"data":$secondary,"key":"k","fields":["properties"]}}""")
      ),
    )
  }

  /** Renamed on the way in, the new name is what blocks the climb. */
  @Test
  fun `a flatten of what a lookup renames stays below it too`() {
    assertEquals(
      listOf(
        """source_1|{"type":"csv"}|""",
        """source_0|{"type":"csv"}|lookup,formula:props.name,filter""",
      ),
      flow(
        chart(
          """{"lookup":"id","from":{"data":$secondary,"key":"k","fields":["properties"]},
             "as":["props"]}""",
          """"tooltip":[{"field":"props.name","type":"nominal"}]""",
        )
      ),
    )
  }

  /** It stays directly below, and the specification's own filter stays below that. */
  @Test
  fun `a flatten below a lookup is above the filter that follows it`() {
    assertEquals(
      listOf(
        """source_1|{"type":"csv"}|""",
        """source_0|{"type":"csv"}|lookup,formula:properties.name,filter,filter""",
      ),
      flow(
        chart(
          """{"lookup":"id","from":{"data":$secondary,"key":"k","fields":["properties"]}},
             {"filter":"datum.a > 5"}"""
        )
      ),
    )
  }

  /**
   * With nothing producing its root, the parse climbs to the source — and is written there as the
   * formula it is, beside the empty `parse` the loader is handed. The empty object is upstream's:
   * `format.parse` is written whenever the node sits under the source, and a flatten has nothing to
   * put in it.
   */
  @Test
  fun `a flatten of a column of the table itself climbs to the source`() {
    assertEquals(
      listOf("""source_0|{"type":"csv","parse":{}}|formula:deep.name,filter"""),
      flow(
        """{"data":$url,"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
           "tooltip":[{"field":"deep.name","type":"nominal"}]}}"""
      ),
    )
  }
}
