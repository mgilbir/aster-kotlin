package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A themed axis property beats a derived one, and who *applies* it decides whether it is written.
 *
 * ```js
 * if (hasValue && explicit) {
 *   axisComponent.set(property, value, explicit);
 * } else {
 *   const {configValue, configFrom} = ...;
 *   if (hasValue && !hasConfigValue) {
 *     axisComponent.set(property, value, explicit);
 *   } else if (
 *     !(configFrom === 'vgAxisConfig') ||
 *     (propsToAlwaysIncludeConfig.has(property) && hasConfigValue) ||
 *     isConditionalAxisValue(configValue) ||
 *     isSignalRef(configValue)
 *   ) {
 *     axisComponent.set(property, configValue, false);
 *   }
 * }
 * ```
 *
 * Three rules in one place. A value this compiler **derived** is used only where the theme said
 * nothing: a themed `format` replaces the specifier a time unit would have produced, and a themed
 * `tickCount` replaces `ceil(width/40)`. Where the theme did speak and Vega can apply it itself,
 * the axis says nothing at all — a `config.axisX.title` reaches the chart through the Vega
 * configuration beside it. And a property on `propsToAlwaysIncludeConfig`, or a themed value that
 * is a signal or a conditional, is written out even from a block Vega knows, because Vega does not
 * apply those the way Vega-Lite means them.
 *
 * This engine settled `format`, `formatType`, `tickCount` and `tickMinStep` without asking the
 * theme at all, and wrote out a themed caption Vega would have applied itself. Four specifications
 * in the wild corpus theme their axes that way.
 *
 * A caption the **channel** states is explicit — `isExplicit` says so in as many words — so it is
 * taken before the theme is asked.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ThemedAxisPrecedenceTest {

  /** The axis of that scale and part, keeping only the properties at issue. */
  private fun axis(config: String, scale: String, part: String, y: String = ""): VegaValue? {
    val spec =
      """{"data":{"values":[{"a":"2020-01-01","b":2}]},"config":$config,"mark":"point",
         "encoding":{"x":{"field":"a","type":"temporal","timeUnit":"yearmonthdate"},
                     "y":{"field":"b","type":"quantitative"$y}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val wanted = setOf("format", "tickCount", "title", "labelExpr", "formatType", "tickMinStep")
    val found =
      (compiled.fields["axes"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .firstOrNull {
          it.string("scale") == scale &&
            (it.fields["grid"] == VegaValue.Bool(true)) == (part == "grid")
        } ?: return null
    return VegaValue.Obj(LinkedHashMap(found.fields.filterKeys { it in wanted }))
  }

  private fun json(text: String) = VegaJson.parse(text)

  /**
   * The same axis over a **measured** x, where nothing derives a format or a tick step — which
   * leaves the properties at issue to the theme alone.
   */
  private fun plain(config: String, y: String = ""): VegaValue? {
    val spec =
      """{"data":{"values":[{"a":1,"b":2}]},"config":$config,"mark":"point",
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"b","type":"quantitative"$y}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val found =
      (compiled.fields["axes"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("scale") == "y" && it.fields["grid"] != VegaValue.Bool(true)
        }
    return VegaValue.Obj(
      LinkedHashMap(found.fields.filterKeys { it == "title" || it == "labelPadding" })
    )
  }

  /** The two the time unit derives, which most of these cases carry unchanged. */
  private val derivedForTheTimeUnit =
    """"tickCount":{"signal":"ceil(width/40)"},
       "tickMinStep":{"signal":"datetime(2001, 0, 2, 0, 0, 0, 0) - datetime(2001, 0, 1, 0, 0, 0, 0)"}"""

  private val derivedSpecifier =
    """{"signal":"timeUnitSpecifier([\"year\",\"month\",\"date\"], {\"year-month\":\"%b %Y \",\"year-month-date\":\"%b %d, %Y \"})"}"""

  /** The reported shape: a themed format instead of the specifier a time unit would produce. */
  @Test
  fun `a themed format replaces the derived specifier`() {
    assertEquals(
      json("""{"title":"a (year-month-date)","format":"%b %d %a",$derivedForTheTimeUnit}"""),
      axis("""{"axisX":{"format":"%b %d %a"}}""", "x", "main"),
    )
  }

  /** A themed tick count replaces the one derived from the plot's height. */
  @Test
  fun `a themed tick count replaces the derived one`() {
    assertEquals(
      json("""{"title":"b","tickCount":5}"""),
      axis("""{"axisY":{"tickCount":5}}""", "y", "main"),
    )
  }

  /** On the gridlines as well, `tickCount` being a property of both parts. */
  @Test
  fun `a themed tick count reaches the gridlines`() {
    assertEquals(json("""{"tickCount":5}"""), axis("""{"axisY":{"tickCount":5}}""", "y", "grid"))
  }

  /** A themed caption from a block Vega knows is left for Vega to apply. */
  @Test
  fun `a themed caption is left to Vega`() {
    assertEquals(
      json("""{"format":$derivedSpecifier,$derivedForTheTimeUnit}"""),
      axis("""{"axisX":{"title":"Date"}}""", "x", "main"),
    )
  }

  /** From a block Vega does not know, it is written onto the axis. */
  @Test
  fun `a themed caption from a scale-named family is written out`() {
    assertEquals(
      json("""{"title":"Measured","tickCount":{"signal":"ceil(height/40)"}}"""),
      axis("""{"axisQuantitative":{"title":"Measured"}}""", "y", "main"),
    )
  }

  /** And a caption the channel stated is explicit, so the theme is never asked. */
  @Test
  fun `a caption the channel states beats the theme`() {
    assertEquals(
      json("""{"title":"Stated","tickCount":{"signal":"ceil(height/40)"}}"""),
      axis("""{"axisY":{"title":"Themed"}}""", "y", "main", y = ""","title":"Stated""""),
    )
  }

  /** A themed **signal** is written out whatever block it came from: Vega cannot read one there. */
  @Test
  fun `a themed signal is written out`() {
    assertEquals(
      json("""{"title":"b","tickCount":{"signal":"3 + 2"}}"""),
      axis("""{"axisY":{"tickCount":{"signal":"3 + 2"}}}""", "y", "main"),
    )
  }

  /** Even for a property nothing here derives and no rule always takes from the theme. */
  @Test
  fun `a themed signal on any property is written out`() {
    assertEquals(
      json("""{"title":"b","labelPadding":{"signal":"5"}}"""),
      plain("""{"axisY":{"labelPadding":{"signal":"5"}}}"""),
    )
  }

  /** The same property as a plain number is left to Vega, which is the contrast. */
  @Test
  fun `a themed number on that property is left to Vega`() {
    assertEquals(json("""{"title":"b"}"""), plain("""{"axisY":{"labelPadding":5}}"""))
  }

  /** And a caption the channel states beats a theme that *would* have been written out. */
  @Test
  fun `a caption the channel states beats a family Vega does not know`() {
    assertEquals(
      json("""{"title":"Stated"}"""),
      plain("""{"axisQuantitative":{"title":"Measured"}}""", y = ""","title":"Stated""""),
    )
  }

  /** A themed `grid` decides whether there is a gridline axis at all, so it is always read. */
  @Test
  fun `a themed grid of false takes the gridlines away`() {
    assertEquals(null, axis("""{"axisX":{"grid":false}}""", "x", "grid"))
  }
}
