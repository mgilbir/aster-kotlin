package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A join names its table before a child of the chart names one.
 *
 * `parseData` parses a model's transforms where it stands — `parseTransformArray` runs on the
 * model's own list and `LookupNode.make` gives the joined table a root of its own there — and only
 * then descends into the children. So a join written on the chart names its table before a layer
 * that brought rows of its own names that.
 *
 * This compiler registered the table as the transform was *translated*, which happens while a
 * view's chain is being built — so the table was numbered behind whatever the first view had
 * already claimed. Every reader of it then named a different table than upstream's: the marks drawn
 * from it, and the projection fitted to it.
 *
 * Only where some view runs them. A layer that reads a table of its own skips its ancestors'
 * transforms altogether, so a chart all of whose layers do that has no join to name.
 *
 * Two specifications in the wild corpus join a table and then layer a map that brings its own.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class JoinedTableOrderTest {

  /** Every source, what it reads, and the steps it runs. */
  private fun sources(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr).values.joinToString(" | ") {
      it as VegaValue.Obj
      val from = it.string("source") ?: it.string("url")?.let { url -> "url($url)" } ?: "values"
      val steps =
        (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") { step ->
          step as VegaValue.Obj
          if (step.string("type") == "lookup") "lookup(from=${step.string("from")})"
          else step.string("type").orEmpty()
        }
      "${it.string("name")}<-$from[$steps]"
    }
  }

  private val x = """{"field":"a","type":"quantitative"}"""
  private val plain = """{"mark":"point","encoding":{"x":$x}}"""

  private fun own(url: String) = """{"data":{"url":"$url"},"mark":"point","encoding":{"x":$x}}"""

  private fun join(url: String) =
    """{"lookup":"k","from":{"data":{"url":"$url"},"key":"k"},"as":"g"}"""

  /** The reported shape: the chart joins a table and a layer brings rows of its own. */
  @Test
  fun `a chart's join is named before a layer's own rows`() {
    assertEquals(
      "source_1<-url(joined.json)[] | " +
        "source_0<-url(main.csv)[lookup(from=source_1),filter] | source_2<-url(own.json)[filter]",
      sources(
        """{"data":{"url":"main.csv"},"transform":[${join("joined.json")}],
           "layer":[${own("own.json")},$plain]}"""
      ),
    )
  }

  /** Whichever way round the layers are written, the chart's own is parsed first. */
  @Test
  fun `the layers' order does not move it`() {
    assertEquals(
      "source_1<-url(joined.json)[] | " +
        "source_0<-url(main.csv)[lookup(from=source_1),filter] | source_2<-url(own.json)[filter]",
      sources(
        """{"data":{"url":"main.csv"},"transform":[${join("joined.json")}],
           "layer":[$plain,${own("own.json")}]}"""
      ),
    )
  }

  /** Two joins are named in the order they were written, both before the children's. */
  @Test
  fun `two joins keep their own order`() {
    assertEquals(
      "source_1<-url(one.json)[] | source_2<-url(two.json)[] | " +
        "source_0<-url(main.csv)[lookup(from=source_1),lookup(from=source_2),filter] | " +
        "source_3<-url(own.json)[filter]",
      sources(
        """{"data":{"url":"main.csv"},"transform":[${join("one.json")},${join("two.json")}],
           "layer":[${own("own.json")},$plain]}"""
      ),
    )
  }

  /** With no join, a layer's own rows are numbered straight after the chart's. */
  @Test
  fun `a chart without a join is unchanged`() {
    assertEquals(
      "source_0<-url(main.csv)[filter] | source_1<-url(own.json)[filter]",
      sources("""{"data":{"url":"main.csv"},"layer":[${own("own.json")},$plain]}"""),
    )
  }

  /** And with no layer bringing its own rows there is nothing for it to be numbered ahead of. */
  @Test
  fun `a join over a chart whose layers share its rows`() {
    assertEquals(
      "source_1<-url(joined.json)[] | source_0<-url(main.csv)[lookup(from=source_1),filter]",
      sources(
        """{"data":{"url":"main.csv"},"transform":[${join("joined.json")}],
           "layer":[$plain,$plain]}"""
      ),
    )
  }

  /**
   * Where **every** layer reads a table of its own the chart's transforms are run by nobody, and
   * this compiler names no join at all.
   *
   * Upstream still names it: the chart model parses its own transforms whether or not a child ever
   * reads the result, and writes the table out as a dataset nothing draws from. Reproducing that
   * needs a root with no output hung on it, which this compiler has no way to assemble — a gap of
   * its own, and one no specification in the wild corpus reaches.
   */
  @Test
  fun `a join nothing runs is named by upstream and not here`() {
    assertEquals(
      "source_0<-url(a.json)[filter] | source_1<-url(b.json)[filter]",
      sources(
        """{"data":{"url":"main.csv"},"transform":[${join("joined.json")}],
           "layer":[${own("a.json")},${own("b.json")}]}"""
      ),
    )
  }
}
