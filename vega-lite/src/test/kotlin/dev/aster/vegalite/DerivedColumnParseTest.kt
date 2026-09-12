package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A column a transform wrote is read back as what the **encoding** says, unless the transform
 * already said the same thing.
 *
 * ```js
 * const parsedAs = ancestorParse.getWithExplicit(field);
 * if (parsedAs.value !== undefined) {
 *   if (parsedAs.explicit || parsedAs.value === implicit[field] ||
 *       parsedAs.value === 'derived' || implicit[field] === 'flatten') {
 *     delete implicit[field];
 *   } else {
 *     ancestorParse.set(field, implicit[field], false);
 *   }
 * }
 * ```
 *
 * `parseTransformArray` records what each transform says it wrote — a **number** for a `bin`, an
 * `aggregate`, a `window` and a `joinaggregate`, a **date** for a time unit, and an opaque
 * `'derived'` for the rest — and `makeWithAncestors` drops the implicit parse only where the two
 * agree, where nothing is claimed about the value at all, or where all that was wanted was to
 * flatten a path.
 *
 * This compiler dropped it whenever *any* transform wrote the column. A box plot of an instant then
 * lost the step that reads its own summary back as a date: the quartiles came out of the aggregate
 * as milliseconds and were drawn, labelled and compared as numbers, with a time axis measuring a
 * span of epoch integers. One specification in the wild corpus box-plots an instant.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class DerivedColumnParseTest {

  /** Every dataset with the steps it runs, a formula named by the column it writes. */
  private fun flow(spec: String): String =
    ((VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
          as VegaValue.Obj)
        .fields["data"]
        as VegaValue.Arr)
      .values
      .joinToString(" | ") {
        it as VegaValue.Obj
        val steps =
          it.array("transform").orEmpty().joinToString(" ") { step ->
            step as VegaValue.Obj
            step.string("type").orEmpty() +
              if (step.string("type") == "formula") "(${step.string("as")})" else ""
          }
        "${it.string("name")}<-${it.string("source")} $steps"
      }

  private val rows = """"data":{"values":[{"t":"2020-01-01","g":"a","n":1}]}"""

  private fun drawn(transform: String, type: String) =
    """{$rows,"transform":[$transform],"mark":"point",
        "encoding":{"x":{"field":"m","type":"$type"},"y":{"field":"g","type":"nominal"}}}"""

  private val meanOfInstants =
    """{"aggregate":[{"op":"mean","field":"t","as":"m"}],"groupby":["g"]}"""

  /** The reported shape: an aggregate wrote a number and the encoding wants an instant. */
  @Test
  fun `a summary read back as an instant is parsed as one`() {
    assertEquals(
      "source_0<-null  | data_0<-source_0 aggregate formula(m) filter",
      flow(drawn(meanOfInstants, "temporal")),
    )
  }

  /** Read back as a number, which is what the aggregate said it wrote, nothing is added. */
  @Test
  fun `a summary read back as a number is left alone`() {
    assertEquals(
      "source_0<-null  | data_0<-source_0 aggregate filter",
      flow(
        drawn(
          """{"aggregate":[{"op":"mean","field":"n","as":"m"}],"groupby":["g"]}""",
          "quantitative",
        )
      ),
    )
  }

  /** A `window` writes a number too, so a column read back as an instant is parsed as one. */
  @Test
  fun `a window column read back as an instant is parsed as one`() {
    assertEquals(
      "source_0<-null  | data_0<-source_0 window formula(m) filter",
      flow(drawn("""{"window":[{"op":"first_value","field":"t","as":"m"}]}""", "temporal")),
    )
  }

  /**
   * A `calculate` derives an **opaque** value — `'derived'` — and the encoding is trusted about it,
   * so nothing is parsed however it is read back.
   */
  @Test
  fun `a calculated column is trusted as the encoding reads it`() {
    assertEquals(
      "source_0<-null  | data_0<-source_0 formula(m) filter",
      flow(drawn("""{"calculate":"datum.t","as":"m"}""", "temporal")),
    )
  }

  /** And a time unit already wrote a date, so reading it back as one adds nothing. */
  @Test
  fun `a bucketed instant read back as an instant is left alone`() {
    assertEquals(
      "source_0<-null  | data_0<-source_0 formula(t) timeunit filter",
      flow(drawn("""{"timeUnit":"year","field":"t","as":"m"}""", "temporal")),
    )
  }
}
