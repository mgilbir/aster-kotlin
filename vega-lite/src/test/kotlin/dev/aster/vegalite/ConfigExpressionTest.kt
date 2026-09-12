package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An **expression** in a theme is a signal, and a theme's parameters are the document's signals.
 *
 * ```js
 * export function replaceExprRef<T extends Dict<any>>(index, {level} = {level: 0}) {
 *   …newIndex[prop] = level === 0 ? signalRefOrValue(index[prop]) : replaceExprRef(index[prop], …);
 * }
 * ```
 * ```js
 * if (config.params) {
 *   config.signals = (config.signals || []).concat(assembleParameterSignals(config.params));
 *   delete config.params;
 * }
 * ```
 *
 * `initConfig` rewrites `{"expr": …}` as `{"signal": …}` **once**, as the configuration is read, so
 * everything downstream sees a signal. This compiler left them as written, and three things went
 * wrong with a document that names a colour or a typeface once and reads it from the theme:
 * - the expressions reached the renderer as *values* that happened to be objects, so a font was
 *   named after an object and a colour was not a colour;
 * - `config.params` reached it under a name Vega has never heard of, so nothing the expressions
 *   named existed at all; and
 * - a **label** property carrying a signal stayed on the axis, where Vega does not read it: the
 *   thirteen in `CONDITIONAL_AXIS_PROP_INDEX` are painted per label, so a signal among them belongs
 *   in the axis's own `encode` block and upstream moves it there.
 *
 * One specification in the wild corpus themes itself that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ConfigExpressionTest {

  private fun compiled(config: String) =
    VegaJson.parse(
      requireNotNull(
        VegaLiteCompiler()
          .compileJson(
            """{"mark":"text","config":$config,
                "encoding":{"x":{"field":"a","type":"quantitative"},
                            "y":{"field":"b","type":"quantitative"}}}"""
          )
          .toJson()
      ) {
        "no output"
      }
    ) as VegaValue.Obj

  /** JSON as one line, so an expectation reads as the object it is. */
  private fun json(value: VegaValue?): String =
    when (value) {
      null -> ""
      is VegaValue.Obj ->
        value.fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${json(it.value)}" }
      is VegaValue.Arr -> value.values.joinToString(",", "[", "]") { json(it) }
      else -> VegaJson.write(value)
    }

  /** The configuration the chart carries. */
  private fun config(config: String) = json(compiled(config).fields["config"])

  /** What each mark is painted with. */
  private fun paint(config: String) =
    (compiled(config).fields["marks"] as VegaValue.Arr).values.joinToString(" ") {
      val update = (it as VegaValue.Obj).obj("encode")?.obj("update")
      "fill=${json(update?.fields?.get("fill"))} fontSize=${json(update?.fields?.get("fontSize"))}"
    }

  /** Each axis, with what it says about its labels. */
  private fun axes(config: String) =
    (compiled(config).fields["axes"] as VegaValue.Arr).values.joinToString(" ") {
      it as VegaValue.Obj
      "${it.string("scale")}[labelColor=${json(it.fields["labelColor"])}" +
        " encode=${json(it.fields["encode"])}]"
    }

  private val ink = """{"name":"ink","value":"#333"}"""

  /** A theme's parameters become its signals, which is what makes the expressions mean anything. */
  @Test
  fun `a theme's parameters are its signals`() {
    assertEquals(
      """{"signals":[{"name":"ink","value":"#333"}]}""",
      config("""{"params":[$ink]}"""),
    )
  }

  /** A parameter that is both computed and bound initialises from the expression. */
  @Test
  fun `a computed parameter updates and a bound one initialises`() {
    assertEquals(
      """{"signals":[{"name":"ink","value":"#333","bind":{"input":"color"}},""" +
        """{"name":"twice","update":"1 + 1"}]}""",
      config(
        """{"params":[{"name":"ink","value":"#333","bind":{"input":"color"}},
            {"name":"twice","expr":"1 + 1"}]}"""
      ),
    )
  }

  /** The reported shape: a mark-type block whose colour and size read a parameter. */
  @Test
  fun `a mark block's expressions reach the mark as signals`() {
    val themed = """{"params":[$ink],"text":{"color":{"expr":"ink"},"fontSize":{"expr":"11"}}}"""
    assertEquals(
      """{"style":{"text":{"fontSize":{"signal":"11"}}},""" +
        """"signals":[{"name":"ink","value":"#333"}]}""",
      config(themed),
    )
    assertEquals("""fill={"signal":"ink"} fontSize={"signal":"11"}""", paint(themed))
  }

  /** A **label** property carrying a signal is written where a label is painted. */
  @Test
  fun `a signal on a label is moved to the axis encode block`() {
    val themed =
      """{"params":[$ink,{"name":"face","value":"Helvetica"}],
          "axis":{"labelColor":{"expr":"ink"},"labelFont":{"expr":"face"}}}"""
    assertEquals(
      """{"axis":{"labelColor":{"signal":"ink"},"labelFont":{"signal":"face"}},""" +
        """"signals":[{"name":"ink","value":"#333"},{"name":"face","value":"Helvetica"}]}""",
      config(themed),
    )
    assertEquals(
      """x[labelColor= encode=] y[labelColor= encode=] """ +
        """x[labelColor= encode={"labels":{"update":{"fill":{"signal":"ink"},""" +
        """"font":{"signal":"face"}}}}] """ +
        """y[labelColor= encode={"labels":{"update":{"fill":{"signal":"ink"},""" +
        """"font":{"signal":"face"}}}}]""",
      axes(themed),
    )
  }

  /**
   * A property Vega reads off the axis itself stays there: only the thirteen it paints per label or
   * per tick have nowhere on the axis for a signal to sit.
   */
  @Test
  fun `a signal Vega reads off the axis stays on it`() {
    assertEquals(
      """x[labelColor= encode=] y[labelColor= encode=] """ +
        """x[labelColor= encode=] y[labelColor= encode=]""",
      axes("""{"axis":{"labelPadding":{"expr":"4"}}}"""),
    )
  }

  /** A theme that writes no expression at all is carried through as it was written. */
  @Test
  fun `a theme with no expressions is unchanged`() {
    assertEquals(
      """{"axis":{"labelColor":"blue"}}""",
      config("""{"axis":{"labelColor":"blue"}}"""),
    )
  }

  /**
   * `config.style` is a block **of** blocks, one per named style, so its expressions are a level
   * further down than everywhere else — `getStyleConfigInternal` reads each block in turn.
   */
  @Test
  fun `a style block's expressions are read a level down`() {
    assertEquals(
      """{"style":{"guide-label":{"fontSize":{"signal":"12"}}},""" +
        """"signals":[{"name":"ink","value":"#333"}]}""",
      config("""{"params":[$ink],"style":{"guide-label":{"fontSize":{"expr":"12"}}}}"""),
    )
  }

  /**
   * The blocks that are **renamed** on the way through carry their signals with them: the view is
   * the `cell` style and the title is `group-title`.
   */
  @Test
  fun `a renamed block keeps its signals`() {
    assertEquals(
      """{"style":{"cell":{"stroke":{"signal":"ink"}},""" +
        """"group-title":{"fill":{"signal":"ink"}}},""" +
        """"signals":[{"name":"ink","value":"#333"}]}""",
      config(
        """{"params":[$ink],"view":{"stroke":{"expr":"ink"}},"title":{"color":{"expr":"ink"}}}"""
      ),
    )
  }
}
