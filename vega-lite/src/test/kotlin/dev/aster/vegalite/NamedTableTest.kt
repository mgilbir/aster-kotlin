package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A table already standing is **found** rather than made, and a name is what finds it.
 *
 * ```js
 * if (data.name && other.hasName() && data.name !== other.dataName) continue;
 * ...
 * if (isInlineData(data) && isInlineData(otherData)) { if (deepEqual(...)) return other; }
 * else if (isUrlData(data) && isUrlData(otherData)) { if (data.url === otherData.url) return other; }
 * else if (isNamedData(data)) { if (data.name === other.dataName) return other; }
 * ```
 *
 * `findSource` decides what makes two mentions the same table, and it is not that they were written
 * the same way. A dataset given a `name` is that name's, so a view that says `{"name": "places"}`
 * and nothing else reads the table another view declared under that name.
 *
 * This compiler keyed a table by the value as written, so each mention stood up a root of its own:
 * the table was fetched again per mention, and every table derived from it was numbered around the
 * copies.
 *
 * Two specifications in the wild corpus name a table once and draw from it three times.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class NamedTableTest {

  /** Every dataset, what it reads, and the steps it runs. */
  private fun sources(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr).values.joinToString(" | ") {
      it as VegaValue.Obj
      val from =
        it.string("source")
          ?: it.string("url")?.let { url -> "url($url)" }
          ?: it.fields["values"]?.let { "values" }
          ?: "none"
      val steps =
        (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") { step ->
          (step as VegaValue.Obj).string("type").orEmpty()
        }
      "${it.string("name")}<-$from[$steps]"
    }
  }

  private val x = """{"field":"a","type":"quantitative"}"""
  private val named = """{"name":"places","url":"places.csv"}"""
  private val bare = """{"name":"places"}"""

  /** The reported shape: declared once, read again by name. */
  @Test
  fun `a bare name reads the table of that name`() {
    assertEquals(
      "places<-url(places.csv)[filter]",
      sources(
        """{"hconcat":[{"data":$named,"mark":"point","encoding":{"x":$x}},
             {"data":$bare,"mark":"circle","encoding":{"x":$x}}]}"""
      ),
    )
  }

  /** Two names are two tables, whatever else they say. */
  @Test
  fun `two names are two tables`() {
    assertEquals(
      "places<-url(places.csv)[filter] | others<-url(others.csv)[filter]",
      sources(
        """{"hconcat":[{"data":$named,"mark":"point","encoding":{"x":$x}},
             {"data":{"name":"others","url":"others.csv"},"mark":"circle",
              "encoding":{"x":$x}}]}"""
      ),
    )
  }

  /** And two names over **one** address are still two tables: the name is asked first. */
  @Test
  fun `two names over one address are two tables`() {
    assertEquals(
      "one<-url(places.csv)[filter] | two<-url(places.csv)[filter]",
      sources(
        """{"hconcat":[{"data":{"name":"one","url":"places.csv"},"mark":"point",
              "encoding":{"x":$x}},
             {"data":{"name":"two","url":"places.csv"},"mark":"circle",
              "encoding":{"x":$x}}]}"""
      ),
    )
  }

  /** One address under no name at all is still one table, which is what already worked. */
  @Test
  fun `one address is one table`() {
    assertEquals(
      "source_0<-url(places.csv)[filter]",
      sources(
        """{"hconcat":[{"data":{"url":"places.csv"},"mark":"point","encoding":{"x":$x}},
             {"data":{"url":"places.csv"},"mark":"circle","encoding":{"x":$x}}]}"""
      ),
    )
  }

  /** Two readings of one topology are two tables: the feature is part of the address. */
  @Test
  fun `two features of one topology are two tables`() {
    assertEquals(
      "source_0<-url(t.json)[] | source_1<-url(t.json)[]",
      sources(
        """{"hconcat":[
             {"data":{"url":"t.json","format":{"type":"topojson","feature":"a"}},
              "mark":"geoshape"},
             {"data":{"url":"t.json","format":{"type":"topojson","feature":"b"}},
              "mark":"geoshape"}]}"""
      ),
    )
  }

  /**
   * A name read **before** it is declared finds nothing, and the declaration that follows finds no
   * address to compare itself against — so the chart comes out with two datasets of one name, the
   * first of them empty. It is upstream's own reading and it is what upstream emits; the rule is
   * that a mention is answered by what is already standing, and nothing is standing yet.
   */
  @Test
  fun `a name read before it is declared finds nothing`() {
    assertEquals(
      "places<-none[] | data_0<-places[filter] | places<-url(places.csv)[filter]",
      sources(
        """{"hconcat":[{"data":$bare,"mark":"point","encoding":{"x":$x}},
             {"data":$named,"mark":"circle","encoding":{"x":$x}}]}"""
      ),
    )
  }
}
