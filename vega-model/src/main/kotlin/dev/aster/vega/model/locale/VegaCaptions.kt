package dev.aster.vega.model.locale

/**
 * The sentences a screen reader is given, as **sentences a language builds** rather than a table of
 * strings.
 *
 * This is the part of localisation that a string table cannot do, and it is worth being explicit
 * about why. The captions are not labels; they are grammar. `"Y-axis titled 'Value' for a linear
 * scale with values from 0 to 100"` puts a title inside quotation marks a language chooses, joins a
 * list with a conjunction a language chooses, and agrees a plural with a number — English's
 * `boundary`/`boundaries` is the easy case, and a language with three plural forms or a case system
 * has no rule that survives being split into fragments. So a host implements the sentences.
 *
 * Every method is given the pieces already formatted in the host's own locale: a value has been
 * through the number or date formatter before it arrives here.
 *
 * [English] is upstream's own wording, ported from `vega-scenegraph/src/util/aria.js` and
 * `vega-scale/src/caption.js`, and is what a chart says when no locale is supplied — so a reader
 * who has met Vega charts elsewhere hears the same phrasing, and the differential captions
 * harvested from upstream still match.
 */
public interface VegaCaptions {

  /**
   * An axis.
   *
   * @param vertical true for a left or right axis. Which axis it *is* rather than which side it is
   *   on, because that is what upstream says and it is what a reader needs.
   * @param title the axis's title, or null when it has none.
   * @param scaleType the scale's kind, as one word: `discrete`, `linear`, `sqrt`, `quantile`.
   * @param domain what the domain covers, already phrased by one of the three domain methods.
   */
  public fun axis(vertical: Boolean, title: String?, scaleType: String, domain: String): String

  /**
   * A legend.
   *
   * @param kind `symbol` or `gradient` — the shape of the legend.
   * @param channels what it explains, each already through [channelName].
   */
  public fun legend(kind: String, title: String?, channels: List<String>, domain: String): String

  /** One encoding channel as a reader hears it: upstream turns `fill` into "fill color". */
  public fun channelName(channel: String): String

  /**
   * A discrete domain.
   *
   * @param count how many values there are in total, which is not always how many are read out.
   * @param values the ones to read.
   * @param endingWith the last value, when [values] is a truncated head of a longer domain.
   */
  public fun discreteDomain(count: Int, values: List<String>, endingWith: String?): String

  /** The cut points of a discretizing scale, whose values are ranges rather than points. */
  public fun boundaryDomain(cuts: List<String>): String

  /** The two ends of a continuous domain. */
  public fun continuousDomain(from: String, to: String): String

  /** A domain that is the data itself, which is what an identity scale has. */
  public fun identityDomain(): String

  /**
   * What kind of thing a mark is, in words — `aria-roledescription`.
   *
   * A reader says the role and this together, so this is what is actually heard: "line mark" in
   * English, and a Dutch reader should not hear the English word for the shape of the chart.
   */
  public fun markRole(markType: String): String

  /** The container a mark's items are announced inside. */
  public fun markContainerRole(markType: String): String

  /**
   * What a chart too dense to explore mark by mark says instead.
   *
   * A scatter plot of four thousand points is not explorable one swipe at a time, so past a
   * threshold the accessibility tree offers one element for the whole chart — and that element has
   * to say why it is the only one.
   */
  public fun denseChartSummary(marks: Int): String

  /**
   * What an assistive technology calls the chart's own actions: zooming, and undoing it.
   *
   * Defaulted rather than abstract, so a locale that already implements this interface keeps
   * compiling — and so a locale that has not translated them yet says something in English rather
   * than nothing at all. A reader offered a nameless action cannot tell what it does.
   */
  public fun zoomInAction(): String = "Zoom in"

  public fun zoomOutAction(): String = "Zoom out"

  public fun resetZoomAction(): String = "Reset zoom"

  /**
   * And undoing an **axis** adjustment, which is a different thing from undoing a zoom.
   *
   * A zoom magnifies the drawing; adjusting an axis changes the interval the data is drawn against,
   * so the ticks and the labels change with it. A reader who has done both needs to be able to undo
   * both, and a single "reset" that did the two at once would undo work they did not ask to lose.
   *
   * There is no *narrow* or *widen* caption to go with it, deliberately: those are not actions but
   * the increment and decrement of an adjustable element, and both platforms name those themselves
   * — a reader hears "swipe up or down to adjust" in their own language, from the system, and a
   * caption here would be a second name for the same gesture.
   */
  public fun resetAxesAction(): String = "Reset the axes"

  /**
   * Narrowing and widening an adjustable axis, **for the host that has to name them**.
   *
   * The note above says there is no caption for these because both platforms name the increment and
   * the decrement themselves — a reader hears "swipe up or down to adjust" from the system, in
   * their own language. That is true of Android and of Apple, and not of Compose Multiplatform: it
   * has no adjustable trait, so the only way to offer the two directions there is as named actions,
   * and a nameless action is one a reader cannot use.
   *
   * So these exist for the one host that needs them rather than for all three, which is why the
   * wording says what the action *does* rather than which way to swipe: there is no swipe.
   */
  public fun narrowAxisAction(): String = "Narrow this axis"

  public fun widenAxisAction(): String = "Widen this axis"

  public companion object {
    /** Upstream's wording, and the default. */
    public val English: VegaCaptions = EnglishCaptions
  }
}

private object EnglishCaptions : VegaCaptions {

  override fun axis(
    vertical: Boolean,
    title: String?,
    scaleType: String,
    domain: String,
  ): String = buildString {
    append(if (vertical) "Y-axis" else "X-axis")
    if (!title.isNullOrBlank()) append(" titled '$title'")
    append(" for a $scaleType scale")
    append(" with $domain")
  }

  override fun legend(
    kind: String,
    title: String?,
    channels: List<String>,
    domain: String,
  ): String = buildString {
    append("$kind legend".trim().replaceFirstChar { it.uppercase() })
    if (!title.isNullOrBlank()) append(" titled '$title'")
    append(" for ${join(channels)}")
    append(" with $domain")
  }

  override fun channelName(channel: String): String =
    if (channel == "fill" || channel == "stroke") "$channel color" else channel

  override fun discreteDomain(count: Int, values: List<String>, endingWith: String?): String {
    val body =
      if (endingWith == null) values.joinToString(", ")
      else values.joinToString(", ") + ", ending with " + endingWith
    return "$count value${if (count == 1) "" else "s"}: $body"
  }

  override fun boundaryDomain(cuts: List<String>): String =
    "${cuts.size} boundar${if (cuts.size == 1) "y" else "ies"}: ${cuts.joinToString(", ")}"

  override fun continuousDomain(from: String, to: String): String = "values from $from to $to"

  override fun identityDomain(): String = "the values themselves"

  override fun markRole(markType: String): String = "$markType mark"

  override fun markContainerRole(markType: String): String = "$markType mark container"

  override fun denseChartSummary(marks: Int): String =
    "Chart with $marks marks. Too dense to explore individually."

  /** An English list: commas, and "and" before the last. */
  private fun join(names: List<String>): String =
    if (names.size < 2) names.joinToString("")
    else names.dropLast(1).joinToString(", ") + " and " + names.last()
}
