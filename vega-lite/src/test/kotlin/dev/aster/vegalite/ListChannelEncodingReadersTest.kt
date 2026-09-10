package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every reader of an encoding spreads a **list** channel, and these are the ones that did not.
 *
 * `forEachFieldDef` and `reduceFieldDef` both spread an array before they call — `if (isArray(el))
 * for (const channelDef of el) f(…)` — so a `tooltip` naming four columns is four definitions to
 * every pass that walks the encoding. The same shape had already been fixed in five separate places
 * here; these are the four that were left, found by sweeping for the pattern rather than by waiting
 * for a specification to hit them.
 *
 * - `BinNode.makeFromEncoding` — a bucketed column named in a tooltip is bucketed.
 * - `CalculateNode.parseAllForSortIndex` — a `sort` array on such an entry gets its index column.
 *   `isScaleFieldDef` is `hasProperty(channelDef, 'scale') || hasProperty(channelDef, 'sort')`, so
 *   the entry qualifies by carrying a `sort` at all.
 * - `AggregateNode.makeFromEncoding` — an entry that asks for a mean makes the view aggregate.
 * - `isAggregate(encoding)`, which spreads explicitly: `if (isArray(channelDef)) return
 *   some(channelDef, (fieldDef) => !!fieldDef.aggregate)`.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ListChannelEncodingReadersTest {

  private fun transforms(tooltip: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"point",
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"},
                           "tooltip":$tooltip}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .flatMap { (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
      .mapNotNull { it as? VegaValue.Obj }
      .map { t ->
        val type = (t.fields["type"] as VegaValue.Str).value
        val out = t.fields["as"]
        val name =
          when (out) {
            is VegaValue.Str -> out.value
            is VegaValue.Arr ->
              out.values.mapNotNull { (it as? VegaValue.Str)?.value }.joinToString(",")
            else -> null
          }
        if (name == null) type else "$type:$name"
      }
  }

  /**
   * `isAggregate(encoding)`, which spreads explicitly and decides whether a scatter is faded:
   * ```js
   * if (isArray(channelDef)) {
   *   return some(channelDef, (fieldDef) => !!fieldDef.aggregate);
   * }
   * ```
   *
   * An aggregated plot has no overlapping points to see through, so it does not take the reduced
   * opacity — and a tooltip entry asking for a mean is enough to make the plot an aggregated one.
   */
  @Test
  fun `an aggregate on a tooltip entry stops the scatter being faded`() {
    fun opacity(tooltip: String?): VegaValue? {
      val extra = tooltip?.let { ""","tooltip":$it""" } ?: ""
      val compiled =
        VegaJson.parse(
          requireNotNull(
            VegaLiteCompiler()
              .compileJson(
                """
                {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"circle",
                 "encoding":{"x":{"field":"c","type":"nominal"},
                             "y":{"field":"b","type":"quantitative"}$extra}}
                """
              )
              .toJson()
          ) {
            "did not compile"
          }
        ) as VegaValue.Obj
      val mark = (compiled.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
      val update = (mark.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj
      return (update.fields["opacity"] as? VegaValue.Obj)?.fields?.get("value")
    }
    assertEquals(VegaValue.Num(0.7), opacity(null), "a plain scatter is faded")
    assertEquals(
      VegaValue.Num(0.7),
      opacity("""[{"field":"c","type":"nominal"},{"field":"a","type":"quantitative"}]"""),
      "and a tooltip that aggregates nothing leaves it faded",
    )
    assertEquals(
      null,
      opacity(
        """[{"field":"c","type":"nominal"},
            {"field":"a","aggregate":"mean","type":"quantitative"}]"""
      ),
      "an aggregated plot has no overlaps to see through",
    )
  }

  /** `BinNode.makeFromEncoding`: a bucketed column named in a tooltip is bucketed. */
  @Test
  fun `a bin on a tooltip entry is compiled`() {
    assertEquals(
      listOf("extent", "bin:bin_maxbins_10_a,bin_maxbins_10_a_end", "filter"),
      transforms(
        """[{"field":"c","type":"nominal"},{"field":"a","bin":true,"type":"quantitative"}]"""
      ),
    )
  }

  /**
   * `parseAllForSortIndex`: the entry qualifies by carrying a `sort` at all. On the **second**
   * entry, which is where a definition that is not the channel's own actually lives.
   */
  @Test
  fun `a sort array on a tooltip entry gets its index column`() {
    assertEquals(
      listOf("formula:tooltip_c_sort_index", "filter"),
      transforms(
        """[{"field":"a","type":"quantitative"},
            {"field":"c","type":"nominal","sort":["x","y"]}]"""
      ),
    )
  }

  /**
   * `AggregateNode.makeFromEncoding`: an entry that asks for a mean makes the view aggregate —
   * again on the second entry, so the walk has to reach past the channel's own definition.
   */
  @Test
  fun `an aggregate on a tooltip entry aggregates the view`() {
    assertEquals(
      listOf("aggregate:mean_a", "filter"),
      transforms(
        """[{"field":"c","type":"nominal"},
            {"field":"a","aggregate":"mean","type":"quantitative"}]"""
      ),
    )
  }
}
