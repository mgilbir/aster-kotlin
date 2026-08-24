package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.locale.VegaCaptions
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import dev.aster.vegalite.VegaLiteInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A chart in Dutch, which is the whole of what the locale seam is for.
 *
 * Everything the engine **generates** was English and en-US: `TimeFormat` said so at the top of the
 * file, and a Dutch reader looking at a chart of their own weekly scores got English month
 * abbreviations on it. The names cannot come from the platform — common Kotlin cannot reach a
 * platform's date or number formatting, and `kotlinx-datetime` supplies the substitution mechanism
 * and no names at all — so they come from the host, as the text engine does.
 *
 * The Dutch locale below is built here rather than shipped: an engine that carries one language's
 * names has to carry all of them, and a host that has a d3 locale JSON already has this object.
 */
class LocaleTest {

  /** Dutch captions: not a string table, because the sentences are grammar. */
  private object DutchCaptions : VegaCaptions {
    override fun axis(
      vertical: Boolean,
      title: String?,
      scaleType: String,
      domain: String,
    ): String = buildString {
      append(if (vertical) "Y-as" else "X-as")
      if (!title.isNullOrBlank()) append(" met de titel '$title'")
      append(" voor een ${scaleType}schaal")
      append(" met $domain")
    }

    override fun legend(
      kind: String,
      title: String?,
      channels: List<String>,
      domain: String,
    ): String = buildString {
      append(if (kind == "gradient") "Kleurverloop" else "Legenda")
      if (!title.isNullOrBlank()) append(" met de titel '$title'")
      append(" voor ${channels.joinToString(" en ")}")
      append(" met $domain")
    }

    override fun channelName(channel: String): String =
      when (channel) {
        "fill" -> "vulkleur"
        "stroke" -> "lijnkleur"
        else -> channel
      }

    override fun discreteDomain(count: Int, values: List<String>, endingWith: String?): String {
      val body =
        if (endingWith == null) values.joinToString(", ")
        else values.joinToString(", ") + ", tot en met " + endingWith
      // Dutch has no plural break here, which is exactly the kind of difference a fragment table
      // cannot express: "1 waarde" and "3 waarden".
      return "$count ${if (count == 1) "waarde" else "waarden"}: $body"
    }

    override fun boundaryDomain(cuts: List<String>): String =
      "${cuts.size} ${if (cuts.size == 1) "grens" else "grenzen"}: ${cuts.joinToString(", ")}"

    override fun continuousDomain(from: String, to: String): String = "waarden van $from tot $to"

    override fun identityDomain(): String = "de waarden zelf"

    override fun markRole(markType: String): String = "$markType-markering"

    override fun markContainerRole(markType: String): String = "$markType-markeringsgroep"

    override fun denseChartSummary(marks: Int): String =
      "Diagram met $marks markeringen. Te dicht om afzonderlijk te verkennen."
  }

  private val dutch =
    VegaLocale(
      months =
        listOf(
          "januari",
          "februari",
          "maart",
          "april",
          "mei",
          "juni",
          "juli",
          "augustus",
          "september",
          "oktober",
          "november",
          "december",
        ),
      shortMonths =
        listOf(
          "jan",
          "feb",
          "mrt",
          "apr",
          "mei",
          "jun",
          "jul",
          "aug",
          "sep",
          "okt",
          "nov",
          "dec",
        ),
      days = listOf("zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag"),
      shortDays = listOf("zo", "ma", "di", "wo", "do", "vr", "za"),
      periods = listOf("a.m.", "p.m."),
      date = "%d-%m-%Y",
      time = "%H:%M:%S",
      decimal = ",",
      thousands = ".",
      captions = DutchCaptions,
    )

  private fun compile(json: String, locale: VegaLocale = VegaLocale.EnglishUS) =
    SpecCompiler(VegaHeadlessTextEngine(), locale = locale).compileJson(json)

  private fun labels(json: String, locale: VegaLocale, role: String = "axis-label"): List<String> =
    requireNotNull(compile(json, locale).scene) { "no scene" }
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .filter { it.metadata.role == role }
      .map { it.layout.run.text }

  /** The shape from the review: five weekly scores on a temporal axis. */
  private val weeklyScores =
    """
    {
      "width": 300, "height": 120, "padding": 5,
      "data": [{
        "name": "t",
        "values": [
          {"t": "2026-05-20T10:00:00", "v": 18},
          {"t": "2026-06-17T10:00:00", "v": 5}
        ],
        "format": {"parse": {"t": "date"}}
      }],
      "scales": [
        {"name": "x", "type": "time", "domain": {"data": "t", "field": "t"}, "range": "width"},
        {"name": "y", "type": "linear", "domain": [0, 27], "range": "height"}
      ],
      "axes": [{"orient": "bottom", "scale": "x", "format": "%b %Y", "tickCount": 2}],
      "marks": [{"type": "line", "from": {"data": "t"}, "encode": {"enter": {
        "x": {"scale": "x", "field": "t"}, "y": {"scale": "y", "field": "v"}}}}]
    }
    """
      .trimIndent()

  @Test
  fun `a time axis writes the locale's month names`() {
    // Distinct, because a fortnightly tick lands in each month more than once and which ticks d3
    // picks is not what this test is about.
    assertEquals(
      listOf("May 2026", "Jun 2026"),
      labels(weeklyScores, VegaLocale.EnglishUS).distinct(),
    )
    assertEquals(listOf("mei 2026", "jun 2026"), labels(weeklyScores, dutch).distinct())
  }

  @Test
  fun `the multi-format an axis falls back to is localised too`() {
    // No `format`, so each tick is labelled at its own granularity — the case a captured payload
    // hits, since `timeUnit: yearmonthdate` produces exactly this.
    val json = weeklyScores.replace(""", "format": "%b %Y"""", "")
    assertTrue(
      labels(json, dutch).any { it.contains("mei") || it.contains("jun") },
      "no Dutch month in ${labels(json, dutch)}",
    )
  }

  @Test
  fun `numbers take the locale's separators`() {
    val json =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "scales": [{"name": "y", "type": "linear", "domain": [-4000, 12000], "range": "height"}],
        "axes": [{"orient": "left", "scale": "y",
                  "values": [-4000, 0, 4000, 8000, 12000]}]
      }
      """
        .trimIndent()

    assertEquals(
      listOf("−4,000", "0", "4,000", "8,000", "12,000"),
      labels(json, VegaLocale.EnglishUS),
    )
    // The separators swap, which is the whole of Dutch number formatting, and the minus stays the
    // typographic one because that is what this locale asked for.
    assertEquals(listOf("−4.000", "0", "4.000", "8.000", "12.000"), labels(json, dutch))
  }

  @Test
  fun `a fraction takes the locale's decimal separator`() {
    val json =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "scales": [{"name": "y", "type": "linear", "domain": [0, 1], "range": "height"}],
        "axes": [{"orient": "left", "scale": "y", "values": [0, 0.5, 1]}]
      }
      """
        .trimIndent()

    assertEquals(listOf("0.0", "0.5", "1.0"), labels(json, VegaLocale.EnglishUS))
    assertEquals(listOf("0,0", "0,5", "1,0"), labels(json, dutch))
  }

  @Test
  fun `a stated numeric format is localised as well`() {
    val json =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "scales": [{"name": "y", "type": "linear", "domain": [0, 1000000], "range": "height"}],
        "axes": [{"orient": "left", "scale": "y", "values": [0, 500000, 1000000],
                  "format": ",.2f"}]
      }
      """
        .trimIndent()

    assertEquals(listOf("0.00", "500,000.00", "1,000,000.00"), labels(json, VegaLocale.EnglishUS))
    assertEquals(listOf("0,00", "500.000,00", "1.000.000,00"), labels(json, dutch))
  }

  @Test
  fun `a caption is a sentence the locale builds`() {
    val axis =
      requireNotNull(compile(weeklyScores, dutch).scene)
        .flatten()
        .map { it.node }
        .first { it.metadata.role == "axis" && it.metadata.accessibility != null }
    val caption = requireNotNull(axis.metadata.accessibility?.label)

    assertTrue(caption.startsWith("X-as"), caption)
    assertTrue(caption.contains("waarden van"), caption)
    assertTrue(caption.contains("mei"), "the domain is read in Dutch: $caption")
  }

  @Test
  fun `a mark's role description is the locale's`() {
    val marks =
      requireNotNull(compile(weeklyScores, dutch).scene)
        .flatten()
        .map { it.node }
        .mapNotNull { it.metadata.accessibility?.roleDescription }

    assertTrue(marks.any { it == "line-markering" }, "role descriptions were $marks")
  }

  /**
   * `timeFormat` in an expression is one of the seven locale-dependent functions, and it is the one
   * a real backend reaches for: a `calculate` transform writing a label for a tooltip.
   */
  @Test
  fun `timeFormat in an expression writes the locale's names`() {
    val json =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "data": [{
          "name": "t",
          "values": [{"t": "2026-05-20T10:00:00"}],
          "format": {"parse": {"t": "date"}},
          "transform": [{"type": "formula", "as": "label", "expr": "timeFormat(datum.t, '%B %d')"}]
        }],
        "scales": [{"name": "x", "type": "band", "domain": {"data": "t", "field": "label"},
                    "range": "width"}],
        "axes": [{"orient": "bottom", "scale": "x"}]
      }
      """
        .trimIndent()

    assertEquals(listOf("May 20"), labels(json, VegaLocale.EnglishUS))
    assertEquals(listOf("mei 20"), labels(json, dutch))
  }

  /**
   * The line the seam must not cross.
   *
   * d3's **parsing** is part of the wire format: a specification writing `"Jan 5 2026"` in its own
   * data means January in every language. A locale that replaced the parsing names would break the
   * reading of every such specification, which is why `TimeFormat.MONTHS` stays English and the
   * locale's names are only ever written.
   */
  @Test
  fun `a Dutch chart still parses English month names out of its data`() {
    val json =
      """
      {
        "width": 200, "height": 100, "padding": 5,
        "data": [{
          "name": "t",
          "values": [{"t": "Jan 5 2026"}, {"t": "Feb 5 2026"}],
          "format": {"parse": {"t": "date:'%b %d %Y'"}}
        }],
        "scales": [{"name": "x", "type": "time", "domain": {"data": "t", "field": "t"},
                    "range": "width"}],
        "axes": [{"orient": "bottom", "scale": "x", "format": "%b", "tickCount": 2}]
      }
      """
        .trimIndent()

    // The **domain** is what parsing produced, so it is what this asserts: the two instants are the
    // 5th of January and the 5th of February however the labels come out.
    val scale = requireNotNull(compile(json, dutch).scales["x"]) { "no x scale" }
    val domain = (scale as TimeScale).domain
    assertEquals(
      listOf("05-01", "05-02"),
      domain.map { TimeFormat.format(it, "%d-%m", scale.zone, dutch) },
      "a Dutch chart read its own data's English month names",
    )
    // And then *written* in Dutch, which is the pair of behaviours this test holds apart.
    assertTrue(
      labels(json, dutch).all { it in dutch.shortMonths },
      "labels were ${labels(json, dutch)}",
    )
  }

  /**
   * A bucketed axis writes the date in the order the **language** writes it, not `TimeUnits`'s.
   *
   * This was the one part of a date the locale seam could not reach, and the middle assertion is
   * the whole of the complaint: `%b` has always resolved to the reader's month abbreviation, so a
   * Dutch axis said `mei` — and said `mei 21, 2026`, because the *pattern* came from a table with
   * no locale in it (`%Y-%m-%d` in `TimeUnits.SPECIFIERS`, `%b %d, %Y` in the two entries Vega-Lite
   * overrides) and `Functions` had a locale in scope for every other date function and did not pass
   * it on.
   *
   * Upstream has no lever for this either — its `timeUnitSpecifier` takes no locale — so the table
   * is **empty by default** and a chart that does not ask is byte-for-byte what it was. That
   * default is what the 283 Vega-Lite fixtures compare against.
   *
   * Both grammars now derive that pattern the same way, from the locale's own `%x`; see #97 and
   * `OneDatePerLocaleTest`. The middle assertion below is where the difference used to show.
   */
  @Test
  fun `a locale decides the order of a bucketed axis's date fields`() {
    val bucketed =
      """
      {"data": {"values": [{"t": "2026-05-20T10:00:00", "v": 1},
                           {"t": "2026-06-17T10:00:00", "v": 2}]},
       "mark": "point",
       "encoding": {"x": {"field": "t", "type": "temporal", "timeUnit": "yearmonthdate"},
                    "y": {"field": "v", "type": "quantitative"}}}
      """
        .trimIndent()

    fun drawn(locale: VegaLocale) =
      labels(requireNotNull(VegaLiteInput.toVega(bucketed, locale = locale).vegaJson), locale)

    // `EnglishUS` is the locale upstream's own tests assume, and it answers what upstream answers —
    // comma and all. It is the only locale that states its tables rather than deriving them, which
    // is
    // what keeps the 283 Vega-Lite fixture comparisons meaningful.
    assertTrue(
      drawn(VegaLocale.EnglishUS).contains("May 21, 2026"),
      drawn(VegaLocale.EnglishUS).toString(),
    )

    // And Dutch reads its own `date` — d3's `%x`, `%d-%m-%Y` — whole: the order it writes **and**
    // the directives and separators it writes them with. So `21-05-2026`.
    //
    // This assertion used to read `21 mei 2026`, on the rule that the order came from the locale
    // and the directives stayed Vega-Lite's. That rule is what #97 reported, because the Vega path
    // never had it: the same `VegaLocale` produced `21-05-2026` on a Vega chart and `21 mei 2026`
    // here, and a host cannot see which grammar ran — both arrive as the same document from the
    // same endpoint. Rebuilding an entry from an order and a set of directives keeps the order and
    // discards everything else a language writes, which is how the comma went missing from
    // `%b %d, %Y` and how Spanish lost its `de`.
    //
    // The cost is on this line and is worth naming: a locale whose `%x` is numeric now gets a
    // numeric month where upstream's table would have written a name. A host that wants the name
    // says so, which is the assertion below.
    assertTrue(drawn(dutch).contains("21-05-2026"), drawn(dutch).toString())
    assertTrue(
      drawn(dutch).none { it.contains("mei 21") },
      "the American order is gone, not merely joined: ${drawn(dutch)}",
    )

    // A locale that states a table has said what it wants, and it wins.
    val stated = dutch.copy(timeUnitSpecifierOverrides = mapOf("year-month-date" to "%-d/%-m/%Y "))
    assertTrue(drawn(stated).contains("21/5/2026"), drawn(stated).toString())

    // Including a host that wants its month spelled out, which is what deriving from a numeric `%x`
    // no longer does on its own. The escape hatch is the same one, and this is the whole of it.
    val named = dutch.copy(timeUnitSpecifierOverrides = mapOf("year-month-date" to "%-d %b %Y "))
    assertTrue(drawn(named).contains("21 mei 2026"), drawn(named).toString())
  }

  /**
   * `timeUnitSpecifier()` in an expression takes the locale too, and a document's own second
   * argument still wins.
   *
   * The precedence is the part worth pinning. A host's language is a *default* under what the
   * document asked for by name; Vega-Lite's generated table is the exception and is not one here —
   * `Fields.timeUnitSpecifier` builds it **from** the locale, so the two agree by construction
   * rather than by one out-ranking the other.
   */
  @Test
  fun `a locale is under a specification's own timeUnitSpecifier overrides`() {
    // Through a `formula` transform, so it is the function table the compiler builds that is under
    // test rather than one assembled here — `Functions` is where the locale was in scope and
    // unused.
    fun specifierOf(locale: VegaLocale, expression: String): List<String> {
      val json =
        """
        {
          "width": 200, "height": 100, "padding": 5,
          "data": [{
            "name": "t",
            "values": [{"x": 1}],
            "transform": [{"type": "formula", "as": "s", "expr": "$expression"}]
          }],
          "scales": [{"name": "x", "type": "band", "domain": {"data": "t", "field": "s"},
                      "range": "width"}],
          "axes": [{"orient": "bottom", "scale": "x"}]
        }
        """
          .trimIndent()
      return labels(json, locale)
    }

    // Upstream's own answer, from the locale upstream's tests assume.
    assertEquals(
      listOf("%Y-%m-%d"),
      specifierOf(VegaLocale.EnglishUS, "timeUnitSpecifier(['year','month','date'])"),
    )
    // And Dutch, derived from its own `%x`. This table's entries are **numeric**, so the whole
    // pattern transfers — separators included — where Vega-Lite's name-bearing table takes only the
    // order. Two tables, two derivations, one order.
    assertEquals(
      listOf("%d-%m-%Y"),
      specifierOf(dutch, "timeUnitSpecifier(['year','month','date'])"),
    )
    assertEquals(listOf("%m-%Y"), specifierOf(dutch, "timeUnitSpecifier(['year','month'])"))
    // An hour has no date order in it to disagree about, so it falls back to upstream's table.
    assertEquals(listOf("%H:00"), specifierOf(dutch, "timeUnitSpecifier(['hours'])"))

    // Written by the document, so it wins over the locale.
    assertEquals(
      listOf("%d/%m/%y"),
      specifierOf(
        dutch,
        "timeUnitSpecifier(['year','month','date'], {'year-month-date': '%d/%m/%y'})",
      ),
    )
  }

  /**
   * A plain `time` axis takes its clock from the locale as well.
   *
   * The other place a date's shape was fixed, and a different table: an axis with no `timeUnit` has
   * no single granularity, so each tick is labelled by the finest field that is not zero. That
   * cascade is d3's `scale.tickFormat` and its `%I %p` is an afternoon in a place that writes
   * 14:00. The locale's own `time` — d3's `%X` — is what says which clock a language keeps, and
   * nothing was reading it; `EnglishUS` states an empty table so d3's cascade survives for every
   * fixture that compares against it.
   */
  @Test
  fun `a locale decides the clock a plain time axis labels its ticks with`() {
    val hours =
      """
      {"width": 300, "height": 120, "padding": 5,
       "data": [{"name": "t", "values": [{"t": "2026-05-20T04:00:00", "v": 1},
                                         {"t": "2026-05-20T20:00:00", "v": 2}],
                 "format": {"parse": {"t": "date"}}}],
       "scales": [{"name": "x", "type": "time", "domain": {"data": "t", "field": "t"},
                   "range": "width"}],
       "axes": [{"orient": "bottom", "scale": "x", "tickCount": 4}],
       "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
         "x": {"scale": "x", "field": "t"}, "y": {"value": 60}}}}]}
      """
        .trimIndent()

    // d3's cascade, from the locale upstream's tests assume: a twelve-hour clock with `%p`.
    assertEquals(
      listOf("06 AM", "09 AM", "12 PM", "03 PM", "06 PM"),
      labels(hours, VegaLocale.EnglishUS),
    )
    // And Dutch keeps a 24-hour clock **without being told to**, because its own `time` is
    // `%H:%M:%S`. Before this it read `06 a.m.` — the locale's half-day markers on a clock the
    // locale had no say in, which is the same shape of gap as a month name on an American date
    // order.
    assertEquals(listOf("06:00", "09:00", "12:00", "15:00", "18:00"), labels(hours, dutch))
    // A stated table wins, and states the whole cascade for the keys it names.
    assertEquals(
      listOf("06h", "09h", "12h", "15h", "18h"),
      labels(hours, dutch.copy(timeTickFormatOverrides = mapOf("hour" to "%Hh"))),
    )
  }
}
